import { MaterialCommunityIcons } from '@expo/vector-icons';
import {
  checkMachineCompatibility,
  checkTaskCompatibility,
  checkWorkCompatibility,
  IMPLEMENT_CATALOG,
  TILLAGE_SYSTEMS,
  type ImplementEntry,
  type TillageWorkType,
} from '@fermapp/domain';
import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { Alert, Pressable, StyleSheet, View } from 'react-native';
import { Button } from 'react-native-paper';
import { apiGet, apiSend, isQueued } from '../../api/client';
import type { Machine, Task, TaskPriority, TaskStatus, TeamMember, WorkSession } from '../../api/types';
import { RunCard } from '../../components/RunCard';
import { Choice, ProgressTrack, QueryState, Txt, type IconName } from '../../components/ui';
import { MACHINE_TYPES, number, parseRoDate, PRIORITIES, shortDate, TASK_TYPES, toRoDate, WORK_TYPES } from '../../lib/format';
import { useAction, useParcels } from '../../lib/hooks';
import { canOperate, canPlan, useAuth } from '../../store/auth';
import { useCatalogPrefs } from '../../store/catalogPrefs';
import { useToast } from '../../store/toast';
import { colors, fonts, space } from '../../theme';
import { machineFuelLPerH } from '../catalog/WorkCostPanel';
import { intelligenceKey, useParcelIntelligence } from '../intelligence/api';
import { usePhotoUpload } from '../intelligence/PhotosSection';
import { SheetInput } from '../intelligence/sections';
import { mapOverviewKey, useMapOverview } from '../map/api';
import { isRunActive } from '../operations/finish';
import { CompatibilityNotice, TillageBadge } from '../tillage/components';
import { TillageSection } from '../tillage/TillageSection';
import { canManage } from '../../store/auth';
import type { RadialSheetProps } from './types';

const toast = (message: string) => useToast.getState().show(message);
const DAY_MS = 86_400_000;
const PRIORITY_COLOR: Record<TaskPriority, string> = { LOW: colors.straw, MEDIUM: colors.water, HIGH: colors.amber, URGENT: colors.rust };
const taskTypeOptions = Object.entries(TASK_TYPES).map(([value, label]) => ({ value, label }));
const priorityOptions = (Object.keys(PRIORITIES) as TaskPriority[]).map((value) => ({ value, label: PRIORITIES[value]! }));

// ---------------------------------------------------------------- shared bits

function SheetButtons({ children }: { children: React.ReactNode }) {
  return <View style={styles.buttons}>{children}</View>;
}

function Note({ children, color }: { children: React.ReactNode; color?: string }) {
  return <Txt variant="small" color={color}>{children}</Txt>;
}

/** Quick date chips plus a typed date, kept as DD.MM.YYYY like the rest of the app. */
function DuePicker({ value, onChange, label = 'Date (DD.MM.YYYY)' }: { value: string; onChange: (value: string) => void; label?: string }) {
  const presets = [
    { label: 'Today', days: 0 },
    { label: 'Tomorrow', days: 1 },
    { label: 'In 3 days', days: 3 },
    { label: 'Next week', days: 7 },
  ].map((preset) => ({ value: toRoDate(new Date(Date.now() + preset.days * DAY_MS)), label: preset.label }));
  const valid = Boolean(parseRoDate(value));
  return (
    <View style={{ gap: space.sm }}>
      <Choice options={presets} value={presets.find((preset) => preset.value === value)?.value ?? null} onChange={onChange} />
      <SheetInput label={label} value={value} onChangeText={onChange} />
      {!valid && value ? <Note color={colors.rust}>Use the DD.MM.YYYY format.</Note> : null}
    </View>
  );
}

const pickerRow = (selected: boolean, disabled?: boolean) => [styles.pick, selected && styles.pickOn, disabled && { opacity: 0.45 }];

// ---------------------------------------------------------------- tractor

/** The widest implement the tractor can pull, from the indicative catalog. */
export function implementSetup(implement: ImplementEntry, powerHp: number | null | undefined) {
  const sorted = [...implement.widthsM].sort((a, b) => a - b);
  const pullable = powerHp ? sorted.filter((width) => width * implement.hpPerMeter <= powerHp) : [];
  const workingWidthM = pullable.length ? pullable[pullable.length - 1]! : sorted[Math.floor((sorted.length - 1) / 2)]!;
  return { workingWidthM, speedKmh: implement.speedKmh, efficiencyPct: implement.efficiencyPct };
}

const TRACTOR_OPERATIONS = Object.entries(WORK_TYPES).map(([value, label]) => ({ value, label }));

export function MachineSheet({ parcel, close, navigate }: RadialSheetProps) {
  const machines = useQuery({ queryKey: ['machines'], queryFn: () => apiGet<Machine[]>('/machines') });
  const overview = useMapOverview();
  const parcels = useParcels();
  const [machineId, setMachineId] = useState<string | null>(null);
  const [type, setType] = useState<string>('DISCING');

  // A machine already running an operation cannot be sent to a second parcel.
  const busy = useMemo(() => {
    const names = new Map((parcels.data ?? []).map((item) => [item.id, item.name]));
    const result = new Map<string, string>();
    // A machine whose run reached 100% is available again.
    (overview.data ?? []).forEach((item) => item.work.forEach((work) => work.machine && isRunActive(work) && result.set(work.machine.id, names.get(item.parcelId) ?? 'another parcel')));
    return result;
  }, [overview.data, parcels.data]);

  const list = useMemo(
    () => [...(machines.data ?? [])].sort((a, b) => Number(busy.has(a.id)) - Number(busy.has(b.id)) || Number(b.type === 'TRACTOR') - Number(a.type === 'TRACTOR')),
    [machines.data, busy],
  );
  const machine = list.find((item) => item.id === machineId);
  const implement = IMPLEMENT_CATALOG.find((item) => item.operation === type);
  const setup = implement ? implementSetup(implement, machine?.powerHp) : null;
  const tillage = parcel.tillageSystem ?? null;
  const workCheck = checkWorkCompatibility(tillage, type);
  const machineCheck = checkMachineCompatibility(machine, tillage);
  const startChecked = () => {
    const problems = [workCheck.message, machineCheck.message].filter(Boolean);
    if (problems.length === 0) return start.mutate();
    Alert.alert('Not recommended', problems.join('\n\n'), [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Start anyway', style: 'destructive', onPress: () => start.mutate() },
    ]);
  };

  const start = useAction<void, unknown>(
    () =>
      apiSend('POST', '/work', {
        parcelId: parcel.id,
        machineId,
        type,
        ...setup,
        fuelLPerH: machineFuelLPerH(machine, type),
        fuelPricePerL: useCatalogPrefs.getState().fuelPricePerL,
      }),
    [['work'], ['dashboard'], mapOverviewKey, intelligenceKey(parcel.id)],
    (result) => {
      toast(isQueued(result) ? 'Saved. The operation starts when the connection returns.' : `${machine?.name ?? 'Machine'} started ${WORK_TYPES[type]?.toLowerCase()} on ${parcel.name}.`);
      close();
    },
  );

  return (
    <QueryState query={machines}>
      {() => (
        <View style={styles.body}>
          {tillage ? (
            <View style={styles.statusLine}>
              <TillageBadge system={tillage} size="sm" />
              <Note>{`Machines are checked against ${TILLAGE_SYSTEMS[tillage].name}.`}</Note>
            </View>
          ) : null}
          {list.length === 0 ? <Note>No machines yet. Add them from Work, Machines.</Note> : null}
          {list.map((item) => {
            const where = busy.get(item.id);
            return (
              <Pressable
                key={item.id}
                disabled={Boolean(where)}
                accessibilityRole="radio"
                accessibilityState={{ selected: item.id === machineId, disabled: Boolean(where) }}
                onPress={() => setMachineId(item.id)}
                style={pickerRow(item.id === machineId, Boolean(where))}
              >
                <MaterialCommunityIcons name={item.type === 'COMBINE' ? 'tractor' : 'tractor-variant'} size={24} color={item.id === machineId ? colors.wheat : colors.straw} />
                <View style={{ flex: 1 }}>
                  <Txt variant="subtitle" numberOfLines={1}>{item.name}</Txt>
                  <Note>{[MACHINE_TYPES[item.type] ?? item.type, item.powerHp ? `${item.powerHp} HP` : null, item.operator?.fullName].filter(Boolean).join(', ')}</Note>
                </View>
                {where ? (
                  <View style={[styles.tag, { backgroundColor: `${colors.amber}33` }]}>
                    <Txt variant="small" color={colors.amber} style={styles.tagText} numberOfLines={1}>{`In work, ${where}`}</Txt>
                  </View>
                ) : !checkMachineCompatibility(item, tillage).compatible && tillage ? (
                  <View style={[styles.tag, { backgroundColor: `${colors.amber}26` }]}>
                    <Txt variant="small" color={colors.amber} style={styles.tagText} numberOfLines={1}>{`Not for ${TILLAGE_SYSTEMS[tillage].badge}`}</Txt>
                  </View>
                ) : (
                  <View style={[styles.tag, { backgroundColor: `${colors.leaf}26` }]}>
                    <Txt variant="small" color={colors.leaf} style={styles.tagText}>Free</Txt>
                  </View>
                )}
              </Pressable>
            );
          })}
          <Choice label="Operation" options={TRACTOR_OPERATIONS} value={type} onChange={setType} />
          <CompatibilityNotice check={workCheck} />
          <CompatibilityNotice check={machineCheck} />
          {setup && implement ? (
            <Note>{`${implement.name}, ${number(setup.workingWidthM)} m at ${setup.speedKmh} km/h. Adjust the settings in Work if needed.`}</Note>
          ) : (
            <Note>{`No standard implement for ${WORK_TYPES[type]?.toLowerCase()}. Set width and speed in Work.`}</Note>
          )}
          <SheetButtons>
            <Button
              style={styles.flex}
              mode="outlined"
              onPress={() => {
                close();
                navigate('Work', { parcelId: parcel.id, machineId: machineId ?? undefined, type });
              }}
            >
              Adjust in Work
            </Button>
            <Button style={styles.flex} mode="contained" disabled={!machineId || !setup || start.isPending} loading={start.isPending} onPress={startChecked}>
              Start now
            </Button>
          </SheetButtons>
        </View>
      )}
    </QueryState>
  );
}

// ---------------------------------------------------------------- irrigation

export function IrrigationSheet({ parcel, close, navigate, openSheet }: RadialSheetProps) {
  const role = useAuth((state) => state.role);
  const overview = useMapOverview();
  const runs = overview.data?.find((item) => item.parcelId === parcel.id)?.irrigation ?? [];
  const control = useAction<{ id: string; action: string }>(
    async ({ id, action }) => {
      await apiSend('POST', `/irrigation/${id}/${action}`);
    },
    [['irrigation'], ['dashboard'], mapOverviewKey, intelligenceKey(parcel.id)],
  );

  return (
    <View style={styles.body}>
      {runs.length === 0 ? (
        <View style={styles.statusLine}>
          <MaterialCommunityIcons name="water-off-outline" size={20} color={colors.straw} />
          <Txt>Not irrigating right now.</Txt>
        </View>
      ) : (
        runs.map((run) => (
          <RunCard
            key={run.id}
            run={run}
            title={run.label}
            subtitle={run.status === 'PAUSED' ? 'Paused' : `Running, ${run.doseMm != null ? `${run.doseMm} mm` : 'no dose set'}`}
            color={colors.water}
            canControl={canOperate(role)}
            busy={control.isPending}
            onAction={(action) => control.mutate({ id: run.id, action })}
          />
        ))
      )}
      <SheetButtons>
        {canPlan(role) ? (
          <Button style={styles.flex} mode="outlined" icon="calendar-clock" onPress={() => openSheet('plan', { type: 'MOISTURE_CHECK', title: `Irrigation, ${parcel.name}` })}>
            Schedule
          </Button>
        ) : null}
        {canOperate(role) ? (
          <Button
            style={styles.flex}
            mode="contained"
            icon="water"
            buttonColor={colors.water}
            textColor={colors.loam}
            onPress={() => {
              close();
              navigate('Irrigation', { parcelId: parcel.id });
            }}
          >
            {runs.length ? 'New run' : 'Start irrigation'}
          </Button>
        ) : null}
      </SheetButtons>
    </View>
  );
}

// ---------------------------------------------------------------- to do

const STATUS_ORDER: Record<TaskStatus, number> = { IN_PROGRESS: 0, TODO: 1, DONE: 2 };

export function TasksSheet({ parcel, close, navigate }: RadialSheetProps) {
  const { role, user } = useAuth();
  const tasks = useQuery({ queryKey: ['tasks'], queryFn: () => apiGet<Task[]>('/tasks') });
  const [type, setType] = useState<string | null>(null);
  const [due, setDue] = useState(toRoDate(new Date(Date.now() + DAY_MS)));
  const invalidate = [['tasks'], ['dashboard'], mapOverviewKey, intelligenceKey(parcel.id)];

  const move = useAction<{ id: string; status: TaskStatus }>(async ({ id, status }) => {
    await apiSend('POST', `/tasks/${id}/status`, { status });
  }, invalidate);
  const add = useAction<void>(
    async () => {
      await apiSend('POST', '/tasks', { type, priority: 'MEDIUM', dueDate: parseRoDate(due), parcelId: parcel.id });
    },
    invalidate,
    () => {
      setType(null);
      toast('Task added.');
    },
  );

  const mayMove = (task: Task) => role !== 'OPERATOR' || task.assignee?.id === user?.id;

  return (
    <QueryState query={tasks}>
      {(all) => {
        const mine = all.filter((task) => task.parcel?.id === parcel.id);
        const open = mine.filter((task) => task.status !== 'DONE').sort((a, b) => STATUS_ORDER[a.status] - STATUS_ORDER[b.status] || a.dueDate.localeCompare(b.dueDate));
        const done = mine.filter((task) => task.status === 'DONE').slice(-3);
        return (
          <View style={styles.body}>
            {open.length === 0 ? <Note>Nothing open on this parcel.</Note> : null}
            {[...open, ...done].map((task) => {
              const finished = task.status === 'DONE';
              const overdue = !finished && new Date(task.dueDate).getTime() < Date.now();
              const editable = mayMove(task) && !move.isPending;
              return (
                <View key={task.id} style={[styles.task, { borderLeftColor: PRIORITY_COLOR[task.priority] }]}>
                  <Pressable
                    accessibilityRole="checkbox"
                    accessibilityState={{ checked: finished, disabled: !editable }}
                    accessibilityLabel={task.title}
                    disabled={!editable}
                    hitSlop={8}
                    onPress={() => move.mutate({ id: task.id, status: finished ? 'TODO' : 'DONE' })}
                  >
                    <MaterialCommunityIcons name={finished ? 'checkbox-marked-circle' : 'checkbox-blank-circle-outline'} size={26} color={finished ? colors.leaf : colors.straw} />
                  </Pressable>
                  <View style={{ flex: 1 }}>
                    <Txt numberOfLines={2} style={finished ? styles.doneText : undefined}>{task.title}</Txt>
                    <Note color={overdue ? colors.rust : undefined}>
                      {[`${overdue ? 'Overdue' : 'Due'} ${shortDate(task.dueDate)}`, task.assignee?.fullName, task.status === 'IN_PROGRESS' ? 'In progress' : null].filter(Boolean).join(', ')}
                    </Note>
                  </View>
                  {task.status === 'TODO' && editable ? (
                    <Pressable accessibilityRole="button" style={styles.inline} onPress={() => move.mutate({ id: task.id, status: 'IN_PROGRESS' })}>
                      <Txt color={colors.wheat}>Start</Txt>
                    </Pressable>
                  ) : null}
                </View>
              );
            })}
            {canPlan(role) ? (
              <View style={styles.group}>
                <Txt variant="subtitle">Add a task</Txt>
                <Choice options={taskTypeOptions} value={type} onChange={setType} />
                <CompatibilityNotice check={checkTaskCompatibility(parcel.tillageSystem, type)} />
                <DuePicker value={due} onChange={setDue} label="Deadline (DD.MM.YYYY)" />
                <Button mode="contained" disabled={!type || !parseRoDate(due) || add.isPending} loading={add.isPending} onPress={() => add.mutate()}>
                  Add task
                </Button>
              </View>
            ) : null}
            <Button
              mode="text"
              onPress={() => {
                close();
                navigate('To Do', { parcelId: parcel.id });
              }}
            >
              Open all tasks
            </Button>
          </View>
        );
      }}
    </QueryState>
  );
}

// ---------------------------------------------------------------- field work

interface FieldWork {
  work: string;
  label: string;
  detail?: string;
  icon: IconName;
  task: string;
}

export const FIELD_WORKS: FieldWork[] = [
  { work: 'DISCING', label: 'Discing', icon: 'disc', task: 'SOIL_PREP' },
  { work: 'PLOWING', label: 'Ploughing', icon: 'shovel', task: 'SOIL_PREP' },
  { work: 'SUBSOILING', label: 'Land preparation', detail: 'Subsoiling', icon: 'terrain', task: 'SOIL_PREP' },
  { work: 'SOWING', label: 'Sowing', icon: 'seed-outline', task: 'SOWING' },
  { work: 'FERTILIZING', label: 'Fertilizing', icon: 'sprinkler-variant', task: 'FERTILIZATION' },
  { work: 'SPRAYING', label: 'Herbicide', detail: 'Spraying', icon: 'spray', task: 'HERBICIDE' },
  { work: 'HARVESTING', label: 'Harvesting', icon: 'barley', task: 'HARVEST' },
];

export function FieldWorkSheet({ parcel, close, navigate, openSheet }: RadialSheetProps) {
  const role = useAuth((state) => state.role);
  const [selected, setSelected] = useState<FieldWork | null>(null);
  const tillage = parcel.tillageSystem ?? null;
  const recommended = new Set(tillage ? TILLAGE_SYSTEMS[tillage].operations.map((operation) => operation.workType) : []);
  return (
    <View style={styles.body}>
      {tillage ? (
        <View style={styles.statusLine}>
          <TillageBadge system={tillage} size="sm" />
          <Note>{`Recommended for ${TILLAGE_SYSTEMS[tillage].name}: ${TILLAGE_SYSTEMS[tillage].operations.map((operation) => operation.label).join(', ')}`}</Note>
        </View>
      ) : null}
      <View style={styles.grid}>
        {FIELD_WORKS.map((item) => {
          const on = selected?.work === item.work;
          const discouraged = !checkWorkCompatibility(tillage, item.work).compatible;
          return (
            <Pressable
              key={item.work}
              accessibilityRole="radio"
              accessibilityState={{ selected: on }}
              accessibilityLabel={item.label}
              onPress={() => setSelected(item)}
              style={[styles.tile, on && styles.pickOn]}
            >
              <MaterialCommunityIcons name={item.icon} size={26} color={on ? colors.wheat : colors.chaff} />
              <Txt variant="small" color={colors.chaff} numberOfLines={1} style={styles.tileLabel}>{item.label}</Txt>
              {item.detail ? <Txt variant="small" numberOfLines={1} style={styles.tileDetail}>{item.detail}</Txt> : null}
              {discouraged ? (
                <MaterialCommunityIcons name="alert-outline" size={14} color={colors.amber} style={styles.tileMark} accessibilityLabel="Not recommended" />
              ) : recommended.has(item.work as TillageWorkType) ? (
                <MaterialCommunityIcons name="check-decagram" size={14} color={colors.leaf} style={styles.tileMark} accessibilityLabel="Recommended" />
              ) : null}
            </Pressable>
          );
        })}
      </View>
      {selected ? <CompatibilityNotice check={checkWorkCompatibility(tillage, selected.work)} /> : null}
      <SheetButtons>
        {canPlan(role) ? (
          <Button style={styles.flex} mode="outlined" icon="calendar-clock" disabled={!selected} onPress={() => selected && openSheet('plan', { type: selected.task, title: selected.label })}>
            Schedule
          </Button>
        ) : null}
        {canOperate(role) ? (
          <Button
            style={styles.flex}
            mode="contained"
            icon="play"
            disabled={!selected}
            onPress={() => {
              if (!selected) return;
              close();
              navigate('Work', { parcelId: parcel.id, type: selected.work });
            }}
          >
            Start now
          </Button>
        ) : null}
      </SheetButtons>
    </View>
  );
}

// ---------------------------------------------------------------- crop status

const CROP_STATUS: Record<string, string> = { NOT_SOWN: 'Not sown', GROWING: 'Growing', READY: 'Ready to harvest', HARVESTED: 'Harvested' };

function Indicator({ icon, label, value, color = colors.chaff }: { icon: IconName; label: string; value: string; color?: string }) {
  return (
    <View style={styles.indicator}>
      <MaterialCommunityIcons name={icon} size={18} color={color} />
      <Txt variant="small">{label}</Txt>
      <Txt variant="subtitle" color={color} numberOfLines={2}>{value}</Txt>
    </View>
  );
}

export function CropStatusSheet({ parcel, close, openParcel }: RadialSheetProps) {
  const intelligence = useParcelIntelligence(parcel.id);
  const work = useQuery({ queryKey: ['work'], queryFn: () => apiGet<WorkSession[]>('/work') });
  const last = (work.data ?? [])
    .filter((item) => item.parcel.id === parcel.id)
    .sort((a, b) => b.startedAt.localeCompare(a.startedAt))[0];

  return (
    <QueryState query={intelligence}>
      {(data) => {
        const crop = data.crop;
        const humidity = data.weather?.current?.humidityPct ?? data.weather?.today?.humidityPct ?? null;
        const ndvi = data.satellite.latest;
        return (
          <View style={styles.body}>
            <View style={styles.statusLine}>
              <MaterialCommunityIcons name="sprout" size={22} color={colors.leaf} />
              <View style={{ flex: 1 }}>
                <Txt variant="subtitle">{crop ? `${crop.name} ${crop.year}` : 'No crop this season'}</Txt>
                {crop ? <Note>{`${CROP_STATUS[crop.status] ?? crop.status}${crop.sowingDate ? `, sown ${shortDate(crop.sowingDate)}` : ''}`}</Note> : null}
              </View>
            </View>
            {crop && crop.status === 'GROWING' ? (
              <View style={{ gap: space.xs }}>
                <ProgressTrack percent={crop.percent} color={colors.leaf} />
                <Note>{`Day ${crop.vegetationDay} of ${crop.totalDays}, ${Math.round(crop.percent)}% of the season${crop.estimatedHarvestDate ? `, harvest around ${shortDate(crop.estimatedHarvestDate)}` : ''}`}</Note>
              </View>
            ) : null}
            <View style={styles.indicators}>
              <Indicator icon="water-percent" label="Humidity" value={humidity != null ? `${Math.round(humidity)}%` : '-'} color={colors.water} />
              <Indicator
                icon="leaf"
                label="NDVI"
                value={ndvi ? number(ndvi.ndvi, 2) : '-'}
                color={ndvi?.health.healthClass === 'PROBLEM' ? colors.rust : ndvi?.health.healthClass === 'MODERATE' ? colors.amber : colors.leaf}
              />
              <Indicator icon="chart-timeline-variant" label="Stage" value={crop ? (CROP_STATUS[crop.status] ?? crop.status) : '-'} />
              <Indicator icon="tractor-variant" label="Last operation" value={last ? `${WORK_TYPES[last.type] ?? last.type}, ${shortDate(last.startedAt)}` : 'None yet'} color={colors.wheat} />
            </View>
            <Button
              mode="contained"
              onPress={() => {
                close();
                openParcel(parcel);
              }}
            >
              Open the parcel sheet
            </Button>
          </View>
        );
      }}
    </QueryState>
  );
}

// ---------------------------------------------------------------- note

export function NoteSheet({ parcel, close }: RadialSheetProps) {
  const [text, setText] = useState('');
  const save = useAction<void>(
    async () => {
      await apiSend('POST', `/parcels/${parcel.id}/observations`, { text: text.trim() });
    },
    [intelligenceKey(parcel.id)],
    () => {
      toast('Observation saved.');
      close();
    },
  );
  return (
    <View style={styles.body}>
      <SheetInput label="What did you see in the field?" value={text} onChangeText={setText} multiline />
      <Button mode="contained" icon="content-save-outline" disabled={!text.trim() || save.isPending} loading={save.isPending} onPress={() => save.mutate()}>
        Save observation
      </Button>
    </View>
  );
}

// ---------------------------------------------------------------- photo

export function PhotoSheet({ parcel, close }: RadialSheetProps) {
  const photos = usePhotoUpload(parcel.id);
  const finish = (saved: boolean) => {
    if (!saved) return;
    toast('Photo added to the parcel history.');
    close();
  };
  return (
    <View style={styles.body}>
      <SheetInput label="Note for the photo (optional)" value={photos.notes} onChangeText={photos.setNotes} multiline />
      <SheetButtons>
        <Button style={styles.flex} mode="contained" icon="camera-outline" loading={photos.uploading} disabled={photos.uploading} onPress={() => void photos.takePhoto().then(finish)}>
          Take photo
        </Button>
        <Button style={styles.flex} mode="outlined" icon="image-outline" disabled={photos.uploading} onPress={() => void photos.pickPhoto().then(finish)}>
          From gallery
        </Button>
      </SheetButtons>
    </View>
  );
}

// ---------------------------------------------------------------- tillage

export function TillageSheet({ parcel, close, navigate }: RadialSheetProps) {
  const role = useAuth((state) => state.role);
  const crop = parcel.seasons?.find((season) => season.year === new Date().getFullYear())?.crop;
  return (
    <View style={styles.body}>
      <TillageSection parcel={parcel} canEdit={canManage(role)} crop={crop} />
      <Button
        mode="text"
        icon="chart-donut"
        onPress={() => {
          close();
          navigate('TillageAnalysis');
        }}
      >
        Farm tillage analysis
      </Button>
    </View>
  );
}

// ---------------------------------------------------------------- planning

export function PlanSheet({ parcel, params, close }: RadialSheetProps) {
  const team = useQuery({ queryKey: ['team'], queryFn: () => apiGet<TeamMember[]>('/team') });
  const [type, setType] = useState<string | null>(typeof params.type === 'string' ? params.type : null);
  const [title, setTitle] = useState(typeof params.title === 'string' ? params.title : '');
  const [due, setDue] = useState(toRoDate(new Date(Date.now() + DAY_MS)));
  const [priority, setPriority] = useState<TaskPriority>('MEDIUM');
  const [assigneeId, setAssigneeId] = useState<string | null>(null);

  const save = useAction<void>(
    async () => {
      await apiSend('POST', '/tasks', {
        type,
        title: title.trim() || undefined,
        priority,
        dueDate: parseRoDate(due),
        parcelId: parcel.id,
        assigneeId: assigneeId ?? undefined,
      });
    },
    [['tasks'], ['dashboard'], mapOverviewKey, intelligenceKey(parcel.id)],
    () => {
      toast(`Scheduled for ${due}.`);
      close();
    },
  );

  return (
    <View style={styles.body}>
      <Choice label="Job" options={taskTypeOptions} value={type} onChange={setType} />
      <CompatibilityNotice check={checkTaskCompatibility(parcel.tillageSystem, type)} />
      <SheetInput label="Title (optional)" value={title} onChangeText={setTitle} />
      <DuePicker value={due} onChange={setDue} />
      <Choice label="Priority" options={priorityOptions} value={priority} onChange={setPriority} />
      <Choice
        label="Responsible"
        options={(team.data ?? []).map((member) => ({ value: member.userId, label: member.fullName }))}
        value={assigneeId}
        onChange={setAssigneeId}
      />
      <Button mode="contained" icon="calendar-check" disabled={!type || !parseRoDate(due) || save.isPending} loading={save.isPending} onPress={() => save.mutate()}>
        Schedule
      </Button>
    </View>
  );
}

const styles = StyleSheet.create({
  body: { gap: space.md },
  flex: { flex: 1 },
  buttons: { flexDirection: 'row', gap: space.sm },
  group: { gap: space.sm, paddingTop: space.sm, borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: colors.line },
  statusLine: { flexDirection: 'row', alignItems: 'center', gap: space.sm },
  pick: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.md,
    minHeight: 60,
    paddingHorizontal: space.md,
    paddingVertical: space.sm,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.furrow,
  },
  pickOn: { borderColor: colors.wheat, backgroundColor: `${colors.wheat}1A` },
  tag: { paddingHorizontal: space.sm, paddingVertical: 2, borderRadius: 10, maxWidth: 140 },
  tagText: { fontFamily: fonts.semibold, fontSize: 11, lineHeight: 15 },
  task: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.md,
    minHeight: 56,
    paddingHorizontal: space.md,
    paddingVertical: space.sm,
    borderRadius: 8,
    borderLeftWidth: 4,
    backgroundColor: colors.furrow,
  },
  doneText: { textDecorationLine: 'line-through', color: colors.straw },
  inline: { minHeight: 44, justifyContent: 'center', paddingHorizontal: space.xs },
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  tile: {
    width: '31.5%',
    minHeight: 84,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 2,
    padding: space.sm,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.furrow,
  },
  tileLabel: { fontFamily: fonts.semibold, fontSize: 12, lineHeight: 15, textAlign: 'center' },
  tileDetail: { fontSize: 10.5, lineHeight: 13 },
  tileMark: { position: 'absolute', top: 6, right: 6 },
  indicators: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  indicator: { width: '48.5%', gap: 2, padding: space.md, borderRadius: 10, backgroundColor: colors.furrow },
});
