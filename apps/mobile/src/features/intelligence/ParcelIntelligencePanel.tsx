import { MaterialCommunityIcons } from '@expo/vector-icons';
import {
  BottomSheetBackdrop,
  BottomSheetModal,
  BottomSheetScrollView,
  type BottomSheetBackdropProps,
} from '@gorhom/bottom-sheet';
import * as Sharing from 'expo-sharing';
import { useCallback, useEffect, useMemo, useRef, useState, type ComponentRef } from 'react';
import { Alert, Linking, Pressable, StyleSheet, useWindowDimensions, View } from 'react-native';
import { apiDownload } from '../../api/client';
import { Empty, QueryState, Txt, type IconName } from '../../components/ui';
import { errorMessage } from '../../lib/hooks';
import { canManage, canOperate, canPlan, useAuth } from '../../store/auth';
import { CatalogSection } from '../catalog/CatalogSection';
import { PlanSection } from '../plan/PlanSection';
import { EconomicSummarySection } from '../economics/EconomicSummarySection';
import { FieldStatusSection } from '../fieldStatus';
import { OperationHistorySection } from '../operations/OperationHistorySection';
import { TillageSection, useParcelById } from '../tillage';
import { SatelliteSummarySection } from '../satellite/SatelliteSummarySection';
import { usesFirebase } from '../../firebase/config';
import { colors, fonts, space } from '../../theme';
import { useParcelIntelligence, type ActiveWork, type ParcelIntelligence } from './api';
import { PhotosSection, usePhotoUpload } from './PhotosSection';
import {
  ActiveIrrigationSection,
  ActiveWorkSection,
  CropSection,
  HeaderSection,
  IrrigationCoverageSection,
  NextActionSection,
  RecommendationsSection,
  RotationSection,
  SatelliteSection,
  SoilSection,
  TasksSection,
  WeatherSection,
} from './sections';

export interface PanelActions {
  onStartWork: (parcelId: string) => void;
  onStartIrrigation: (parcelId: string) => void;
  onAddTask: (parcelId: string) => void;
  onFullHistory: (parcelId: string, name: string) => void;
  onTrackWork: (work: ActiveWork) => void;
  onConfigurePlan: (parcelId: string) => void;
  onOpenSatellite: (parcelId: string, name: string) => void;
}

interface Props extends PanelActions {
  parcelId: string | null;
  selectionToken: number;
  onDismiss: () => void;
}

const SNAP_POINTS = ['80%'];
const TABLET_WIDTH = 768;
const TABLET_SHEET = 720;

function QuickAction({ icon, label, onPress, busy }: { icon: IconName; label: string; onPress: () => void; busy?: boolean }) {
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={label} disabled={busy} onPress={onPress} style={[styles.quick, busy && { opacity: 0.5 }]}>
      <MaterialCommunityIcons name={icon} size={22} color={colors.wheat} />
      <Txt variant="small" color={colors.chaff} style={styles.quickLabel} numberOfLines={2}>{label}</Txt>
    </Pressable>
  );
}

function PanelContent({ data, actions, dismiss }: { data: ParcelIntelligence; actions: PanelActions; dismiss: () => void }) {
  const role = useAuth((state) => state.role);
  const parcelId = data.header.id;
  const photos = usePhotoUpload(parcelId);
  const parcel = useParcelById(parcelId);
  const scroll = useRef<ComponentRef<typeof BottomSheetScrollView>>(null);
  const notesY = useRef(0);
  const [reporting, setReporting] = useState(false);

  const leave = (action: () => void) => {
    dismiss();
    action();
  };

  async function generateReport() {
    setReporting(true);
    try {
      const uri = await apiDownload(`/parcels/${parcelId}/report`, `parcel-${data.header.code}.pdf`);
      await Sharing.shareAsync(uri);
    } catch (error) {
      Alert.alert('The report was not generated', errorMessage(error));
    } finally {
      setReporting(false);
    }
  }

  const navigateToParcel = () => {
    const { lat, lng } = data.header;
    void Linking.openURL(`https://www.google.com/maps/dir/?api=1&destination=${lat},${lng}`);
  };

  return (
    <BottomSheetScrollView ref={scroll} contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
      <HeaderSection data={data} />

      <View style={styles.quickGrid}>
        {canOperate(role) ? <QuickAction icon="tractor-variant" label="Start operation" onPress={() => leave(() => actions.onStartWork(parcelId))} /> : null}
        {canOperate(role) ? <QuickAction icon="water-outline" label="Start irrigation" onPress={() => leave(() => actions.onStartIrrigation(parcelId))} /> : null}
        {canPlan(role) ? <QuickAction icon="checkbox-marked-circle-plus-outline" label="Add task" onPress={() => leave(() => actions.onAddTask(parcelId))} /> : null}
        <QuickAction icon="note-plus-outline" label="Add observation" onPress={() => scroll.current?.scrollTo({ y: notesY.current, animated: true })} />
        <QuickAction icon="camera-plus-outline" label="Upload photo" busy={photos.uploading} onPress={() => void photos.pickPhoto()} />
        <QuickAction icon="file-chart-outline" label="Generate report" busy={reporting} onPress={() => void generateReport()} />
        {usesFirebase ? <QuickAction icon="satellite-variant" label="Satellite" onPress={() => leave(() => actions.onOpenSatellite(parcelId, data.header.name))} /> : null}
        <QuickAction icon="history" label="Full history" onPress={() => leave(() => actions.onFullHistory(parcelId, data.header.name))} />
        <QuickAction icon="navigation-variant-outline" label="Navigate" onPress={navigateToParcel} />
      </View>

      <FieldStatusSection parcelId={parcelId} />
      <CropSection data={data} />
      {parcel ? <TillageSection parcel={parcel} canEdit={canManage(role)} crop={data.crop?.code} /> : null}
      <NextActionSection data={data} />
      <CatalogSection data={data} canEdit={canPlan(role)} />
      <PlanSection data={data} canEdit={canPlan(role)} onConfigure={() => leave(() => actions.onConfigurePlan(parcelId))} />
      <ActiveWorkSection data={data} onTrack={(work) => leave(() => actions.onTrackWork(work))} />
      <ActiveIrrigationSection data={data} />
      <OperationHistorySection parcelId={parcelId} />
      <RotationSection data={data} />
      <IrrigationCoverageSection data={data} />
      <TasksSection data={data} />
      <WeatherSection data={data} />
      <SoilSection data={data} />
      <RecommendationsSection parcelId={parcelId} />
      {canPlan(role) || canManage(role) ? (
        <EconomicSummarySection parcelId={parcelId} onOpen={() => leave(() => actions.onFullHistory(parcelId, data.header.name))} />
      ) : null}
      {usesFirebase ? (
        <SatelliteSummarySection parcelId={parcelId} onOpen={() => leave(() => actions.onOpenSatellite(parcelId, data.header.name))} />
      ) : (
        <SatelliteSection data={data} canRefresh={canPlan(role)} />
      )}
      <View onLayout={(event) => { notesY.current = event.nativeEvent.layout.y; }} style={{ gap: space.lg }}>
        <PhotosSection data={data} photos={photos} />
      </View>
    </BottomSheetScrollView>
  );
}

export function ParcelIntelligencePanel({ parcelId, selectionToken, onDismiss, ...actions }: Props) {
  const sheet = useRef<BottomSheetModal>(null);
  const closing = useRef(false);
  const presented = useRef(false);
  const reopenAfterClose = useRef(false);
  const { width } = useWindowDimensions();
  const query = useParcelIntelligence(parcelId);

  useEffect(() => {
    if (!parcelId) {
      reopenAfterClose.current = false;
      // Dismissing a sheet that has already closed leaves the library in a dismissing
      // state, so it is called only while the panel is actually shown.
      if (presented.current) sheet.current?.dismiss();
    } else if (closing.current) {
      // A parcel tapped while the panel is closing opens right after it closes.
      reopenAfterClose.current = true;
    } else {
      presented.current = true;
      sheet.current?.present();
    }
  }, [parcelId, selectionToken]);

  const handleAnimate = useCallback((_from: number, to: number) => {
    closing.current = to === -1;
  }, []);

  const handleDismiss = useCallback(() => {
    closing.current = false;
    if (reopenAfterClose.current) {
      reopenAfterClose.current = false;
      sheet.current?.present();
      return;
    }
    presented.current = false;
    onDismiss();
  }, [onDismiss]);

  // Taps pass through to the map above the panel: another parcel opens directly,
  // and empty map closes the panel. A blocking backdrop swallowed the first tap.
  const backdrop = useCallback(
    (props: BottomSheetBackdropProps) => (
      <BottomSheetBackdrop {...props} appearsOnIndex={0} disappearsOnIndex={-1} opacity={0.15} enableTouchThrough pressBehavior="none" />
    ),
    [],
  );
  const sideMargin = useMemo(() => (width >= TABLET_WIDTH ? (width - TABLET_SHEET) / 2 : 0), [width]);

  return (
    <BottomSheetModal
      ref={sheet}
      snapPoints={SNAP_POINTS}
      enableDynamicSizing={false}
      enablePanDownToClose
      onAnimate={handleAnimate}
      onDismiss={handleDismiss}
      backdropComponent={backdrop}
      keyboardBehavior="interactive"
      keyboardBlurBehavior="restore"
      android_keyboardInputMode="adjustResize"
      style={{ marginHorizontal: sideMargin }}
      backgroundStyle={styles.background}
      handleIndicatorStyle={styles.handle}
    >
      {parcelId ? (
        <QueryState query={query}>
          {(data) => <PanelContent data={data} actions={actions} dismiss={() => sheet.current?.dismiss()} />}
        </QueryState>
      ) : (
        <Empty icon="vector-polygon" title="Choose a parcel on the map" />
      )}
    </BottomSheetModal>
  );
}

const styles = StyleSheet.create({
  background: { backgroundColor: colors.loam },
  handle: { backgroundColor: colors.line, width: 44 },
  content: { padding: space.lg, paddingBottom: space.xxl * 2, gap: space.xl },
  quickGrid: { flexDirection: 'row', flexWrap: 'wrap', rowGap: space.sm },
  quick: { width: '25%', minHeight: 72, alignItems: 'center', gap: space.xs, paddingHorizontal: 2, paddingVertical: space.sm },
  quickLabel: { fontFamily: fonts.medium, fontSize: 11, lineHeight: 14, textAlign: 'center' },
});
