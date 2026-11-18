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
import { colors, fonts, space } from '../../theme';
import { useParcelIntelligence, type ActiveWork, type ParcelIntelligence } from './api';
import { PhotosSection, usePhotoUpload } from './PhotosSection';
import {
  ActiveIrrigationSection,
  ActiveWorkSection,
  CropSection,
  EconomicsSection,
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
      const uri = await apiDownload(`/parcels/${parcelId}/report`, `parcela-${data.header.code}.pdf`);
      await Sharing.shareAsync(uri);
    } catch (error) {
      Alert.alert('Raportul nu a fost generat', errorMessage(error));
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
        {canOperate(role) ? <QuickAction icon="tractor-variant" label="Pornește lucrare" onPress={() => leave(() => actions.onStartWork(parcelId))} /> : null}
        {canOperate(role) ? <QuickAction icon="water-outline" label="Pornește irigare" onPress={() => leave(() => actions.onStartIrrigation(parcelId))} /> : null}
        {canPlan(role) ? <QuickAction icon="checkbox-marked-circle-plus-outline" label="Adaugă task" onPress={() => leave(() => actions.onAddTask(parcelId))} /> : null}
        <QuickAction icon="note-plus-outline" label="Adaugă observație" onPress={() => scroll.current?.scrollTo({ y: notesY.current, animated: true })} />
        <QuickAction icon="camera-plus-outline" label="Încarcă foto" busy={photos.uploading} onPress={() => void photos.pickPhoto()} />
        <QuickAction icon="file-chart-outline" label="Generează raport" busy={reporting} onPress={() => void generateReport()} />
        <QuickAction icon="history" label="Istoric complet" onPress={() => leave(() => actions.onFullHistory(parcelId, data.header.name))} />
        <QuickAction icon="navigation-variant-outline" label="Navighează" onPress={navigateToParcel} />
      </View>

      <CropSection data={data} />
      <NextActionSection data={data} />
      <CatalogSection data={data} canEdit={canPlan(role)} />
      <PlanSection data={data} canEdit={canPlan(role)} onConfigure={() => leave(() => actions.onConfigurePlan(parcelId))} />
      <ActiveWorkSection data={data} onTrack={(work) => leave(() => actions.onTrackWork(work))} />
      <ActiveIrrigationSection data={data} />
      <RotationSection data={data} />
      <IrrigationCoverageSection data={data} />
      <TasksSection data={data} />
      <WeatherSection data={data} />
      <SoilSection data={data} />
      <RecommendationsSection parcelId={parcelId} />
      <EconomicsSection data={data} canEdit={canPlan(role) || canManage(role)} />
      <SatelliteSection data={data} canRefresh={canPlan(role)} />
      <View onLayout={(event) => { notesY.current = event.nativeEvent.layout.y; }} style={{ gap: space.lg }}>
        <PhotosSection data={data} photos={photos} />
      </View>
    </BottomSheetScrollView>
  );
}

export function ParcelIntelligencePanel({ parcelId, selectionToken, onDismiss, ...actions }: Props) {
  const sheet = useRef<BottomSheetModal>(null);
  const closing = useRef(false);
  const reopenAfterClose = useRef(false);
  const { width } = useWindowDimensions();
  const query = useParcelIntelligence(parcelId);

  useEffect(() => {
    if (!parcelId) {
      reopenAfterClose.current = false;
      sheet.current?.dismiss();
    } else if (closing.current) {
      // O parcelă atinsă cât timp panoul se închide se deschide imediat după închidere.
      reopenAfterClose.current = true;
    } else {
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
    onDismiss();
  }, [onDismiss]);

  const backdrop = useCallback(
    (props: BottomSheetBackdropProps) => <BottomSheetBackdrop {...props} appearsOnIndex={0} disappearsOnIndex={-1} opacity={0.15} />,
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
        <Empty icon="vector-polygon" title="Alege o parcelă de pe hartă" />
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
