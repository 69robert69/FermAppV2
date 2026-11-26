import { detectConflicts } from '../calendar';
import { CROPS } from '../crops';
import { CROP_CALENDARS } from '../parcelPlan';
import { nitrogenStatus, parcelProfitPerHa, taskIsSpray } from './agronomy';
import { cropName, dayLabel, type Context } from './compose';
import { expectedHarvest, parcelFacts, weatherFacts } from './facts';
import { daysBetween } from './language';
import { COPILOT_TASK_TYPES, propose } from './tasks';
import { EMPTY_MEMORY, type CopilotInsight, type FarmSnapshot, type InsightSeverity } from './types';

const RANK: Record<InsightSeverity, number> = { CRITICAL: 0, WARNING: 1, INFO: 2 };
const MAX_INSIGHTS = 12;

/** What the copilot notices across the farm without being asked. */
export function farmInsights(snapshot: FarmSnapshot): CopilotInsight[] {
  const today = snapshot.now.slice(0, 10);
  const weather = weatherFacts(snapshot);
  const ctx: Context = { text: '', snapshot, today, parcel: null, crop: null, memory: EMPTY_MEMORY, farmScope: true, weather, date: { date: null, shiftDays: null } };
  const insights: CopilotInsight[] = [];
  const add = (insight: Omit<CopilotInsight, 'key'>) => insights.push({ ...insight, key: `${insight.kind}-${insight.parcelId ?? 'farm'}-${insights.length}` });
  const allFacts = snapshot.parcels.map((parcel) => parcelFacts(snapshot, parcel));

  for (const facts of allFacts) {
    const name = facts.parcel.name;
    for (const task of facts.overdue.slice(0, 2)) {
      const late = daysBetween(task.dueDate, today);
      add({ kind: 'OVERDUE', severity: late > 3 ? 'CRITICAL' : 'WARNING', parcelId: facts.parcel.id, message: `${name}: “${task.title}” is ${late} day${late > 1 ? 's' : ''} overdue.`, ask: `What operations are still pending on ${name}?`, proposal: null });
    }
    for (const task of facts.upcoming.filter((item) => daysBetween(today, item.dueDate) <= 3)) {
      const label = COPILOT_TASK_TYPES[task.type]?.label.toLowerCase() ?? task.title;
      const verb = taskIsSpray(task.type) ? 'should be sprayed' : `has ${label} due`;
      add({ kind: 'UPCOMING', severity: 'INFO', parcelId: facts.parcel.id, message: `${name} ${verb} ${dayLabel(task.dueDate, today) === 'today' ? 'today' : `within ${Math.max(1, daysBetween(today, task.dueDate))} days`}.`, ask: null, proposal: null });
      if (weather && (taskIsSpray(task.type) || task.type === 'NITROGEN' || task.type === 'FERTILIZATION') && (weather.rain48h >= 10 || weather.maxWind48h > 25) && daysBetween(today, task.dueDate) <= 2) {
        add({ kind: 'WEATHER', severity: 'WARNING', parcelId: facts.parcel.id, message: weather.rain48h >= 10 ? `Heavy rainfall expected (${weather.rain48h} mm). Consider delaying “${task.title}”.` : `Wind up to ${weather.maxWind48h} km/h: “${task.title}” may drift or spread unevenly.`, ask: taskIsSpray(task.type) ? `Can I spray ${name}?` : `When should I fertilize ${name}?`, proposal: null });
      }
    }

    const nitrogen = nitrogenStatus(facts);
    if (nitrogen?.due && !nitrogen.done && !facts.open.some((task) => task.type === 'NITROGEN' || task.type === 'FERTILIZATION')) {
      const offset = daysBetween(today, nitrogen.due);
      if (offset <= 7) {
        add({
          kind: 'FERTILIZATION',
          severity: offset < 0 ? 'WARNING' : 'INFO',
          parcelId: facts.parcel.id,
          message: offset < 0 ? `${name}: nitrogen top-dressing for ${cropName(facts.crop!)} is ${-offset} days late.` : `${name}: nitrogen window for ${cropName(facts.crop!)} opens ${dayLabel(nitrogen.due, today)}.`,
          ask: `When should I fertilize ${name}?`,
          proposal: propose(snapshot, { type: 'NITROGEN', parcelId: facts.parcel.id, dueDate: offset < 0 ? today : nitrogen.due, priority: offset < 0 ? 'HIGH' : 'MEDIUM', reason: `${CROPS[facts.crop!].name} top-dressing stage.`, windowDays: 5, title: `Apply nitrogen fertilizer – ${name}` }),
        });
      }
    }

    const harvest = expectedHarvest(facts);
    if (harvest && !facts.season?.harvestDate && !facts.open.some((task) => task.type === 'HARVEST')) {
      const offset = daysBetween(today, harvest);
      if (offset <= 10 && offset >= -14) {
        add({
          kind: 'HARVEST',
          severity: offset <= 0 ? 'WARNING' : 'INFO',
          parcelId: facts.parcel.id,
          message: offset <= 0 ? `${name}: ${cropName(facts.crop!)} should be ready to harvest now.` : `${name}: ${cropName(facts.crop!)} harvest window starts ${offset <= 7 ? 'next week' : `in ${offset} days`}.`,
          ask: `When should I harvest ${name}?`,
          proposal: propose(snapshot, { type: 'HARVEST', parcelId: facts.parcel.id, dueDate: offset < 0 ? today : harvest, priority: 'HIGH', reason: `Estimated maturity of ${cropName(facts.crop!)}.`, windowDays: 7, title: `Harvest ${name}` }),
        });
      }
    }

    if (weather && facts.irrigated && facts.progress?.status === 'GROWING' && facts.crop) {
      const month = Number(today.slice(5, 7));
      const [from, to] = CROP_CALENDARS[facts.crop].criticalMonths;
      const deficit = Math.round(weather.et0_7d * 1.1 - weather.rain7d);
      if (deficit > 20 && weather.rain48h < 10 && !facts.activeRuns.some((run) => run.kind === 'IRRIGATION')) {
        add({
          kind: 'IRRIGATION',
          severity: month >= from && month <= to ? 'WARNING' : 'INFO',
          parcelId: facts.parcel.id,
          message: `${name} faces a ${deficit} mm water deficit this week${month >= from && month <= to ? ' in its critical period' : ''}.`,
          ask: `Should I irrigate ${name} this week?`,
          proposal: propose(snapshot, { type: 'IRRIGATION_START', parcelId: facts.parcel.id, dueDate: today, priority: 'HIGH', reason: `${deficit} mm deficit over 7 days.`, windowDays: 2 }),
        });
      }
    }
  }

  if (weather) {
    if ((weather.minTemp7d ?? 10) <= -1) add({ kind: 'WEATHER', severity: 'WARNING', parcelId: null, message: `Frost down to ${Math.round(weather.minTemp7d!)} °C this week: protect sensitive crops and avoid spraying before frost nights.`, ask: null, proposal: null });
    if ((weather.maxTemp7d ?? 0) >= 34) add({ kind: 'WEATHER', severity: 'WARNING', parcelId: null, message: `Heat up to ${Math.round(weather.maxTemp7d!)} °C this week: risk of crop heat stress and higher water use.`, ask: null, proposal: null });
    if (weather.rain48h >= 20) add({ kind: 'WEATHER', severity: 'WARNING', parcelId: null, message: `Heavy rainfall expected (${weather.rain48h} mm in 48 hours). Consider delaying field operations.`, ask: 'What operations are scheduled this week?', proposal: null });
  }

  // Resource conflicts in the next week: the same rules as the calendar.
  const parcelName = (id: string | null) => snapshot.parcels.find((parcel) => parcel.id === id)?.name ?? 'farm-wide';
  const conflicts = detectConflicts({
    tasks: snapshot.tasks
      .filter((task) => daysBetween(today, task.dueDate) >= 0 && daysBetween(today, task.dueDate) <= 7)
      .map((task) => ({ id: task.id, title: task.title, type: task.type, parcelId: task.parcelId, priority: task.priority, status: task.status, day: task.dueDate.slice(0, 10), assigneeId: task.assigneeId, machineId: task.machineId })),
    runs: snapshot.runs.map((run) => ({ id: run.id, kind: run.kind, type: run.type, parcelId: run.parcelId, status: run.status, startDay: run.startDate.slice(0, 10), endDay: run.finishDate?.slice(0, 10) ?? null, machineId: run.machineId, operatorId: run.operatorId })),
    machines: snapshot.machines.map((machine) => ({ id: machine.id, name: machine.name, type: machine.type })),
    members: snapshot.members.map((member) => ({ id: member.id, name: member.name, type: member.role })),
    parcelName,
  });
  for (const conflict of conflicts) {
    add({ kind: 'MACHINERY', severity: 'WARNING', parcelId: null, message: `Resource conflict on ${dayLabel(conflict.day, today)}: ${conflict.reason}`, ask: 'Which tractors are currently idle?', proposal: null });
  }

  // Satellite findings that need a visit or a treatment.
  for (const facts of allFacts) {
    for (const finding of facts.parcel.satellite?.findings ?? []) {
      if (finding.severity === 'INFO') continue;
      add({
        kind: 'SATELLITE',
        severity: finding.severity,
        parcelId: facts.parcel.id,
        message: finding.message,
        ask: `What does the satellite show on ${facts.parcel.name}?`,
        proposal: finding.task
          ? propose(snapshot, { type: finding.task.type, parcelId: facts.parcel.id, dueDate: today, priority: finding.task.priority, reason: finding.task.reason, title: finding.task.title, windowDays: 3 })
          : null,
      });
    }
  }

  if (snapshot.canSeeFinance) {
    const profits = allFacts
      .map((facts) => ({ facts, profit: parcelProfitPerHa(facts, ctx) }))
      .filter((item): item is { facts: (typeof allFacts)[number]; profit: NonNullable<ReturnType<typeof parcelProfitPerHa>> } => item.profit != null && item.profit.basis !== 'estimate');
    const area = profits.reduce((sum, item) => sum + item.facts.parcel.areaHa, 0);
    if (profits.length >= 2 && area > 0) {
      const average = profits.reduce((sum, item) => sum + item.profit.value * item.facts.parcel.areaHa, 0) / area;
      for (const item of profits) {
        const gap = average !== 0 ? ((item.profit.value - average) / Math.abs(average)) * 100 : 0;
        if (gap <= -15) add({ kind: 'PROFIT', severity: 'INFO', parcelId: item.facts.parcel.id, message: `${item.facts.parcel.name} is currently ${Math.abs(Math.round(gap))}% less profitable than the farm average.`, ask: `How can I reduce costs on ${item.facts.parcel.name}?`, proposal: null });
      }
    }
  }

  const noSoil = allFacts.filter((facts) => !facts.parcel.soil);
  if (noSoil.length) add({ kind: 'DATA', severity: 'INFO', parcelId: noSoil.length === 1 ? noSoil[0]!.parcel.id : null, message: `${noSoil.length} parcel${noSoil.length > 1 ? 's have' : ' has'} no soil analysis, so crop and fertilizer advice is less precise.`, ask: null, proposal: null });

  return insights.sort((a, b) => RANK[a.severity] - RANK[b.severity]).slice(0, MAX_INSIGHTS);
}
