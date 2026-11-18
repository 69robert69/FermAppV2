import { useNavigation, type NavigationProp } from '@react-navigation/native';
import * as DocumentPicker from 'expo-document-picker';
import * as Location from 'expo-location';
import * as Sharing from 'expo-sharing';
import { pointInRing } from '@fermapp/domain';
import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Alert, StyleSheet, useWindowDimensions, View } from 'react-native';
import MapView, { Polygon, type LatLng, type MapPressEvent, type Region } from 'react-native-maps';
import { Button, FAB, Menu } from 'react-native-paper';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { apiDownload, apiUpload } from '../api/client';
import type { Parcel } from '../api/types';
import { IconButton, OfflineBanner, Panel, Txt } from '../components/ui';
import { useMachineLocation } from '../features/intelligence/api';
import { ParcelIntelligencePanel } from '../features/intelligence/ParcelIntelligencePanel';
import { useMapOverview } from '../features/map/api';
import { IrrigationOverlay, ParcelInfoOverlay, ParcelTapTarget, WorkOverlay } from '../features/map/MapOverlays';
import { clock, WORK_TYPES } from '../lib/format';
import { errorMessage, useAction, useNow, useParcels } from '../lib/hooks';
import type { RootStackParams } from '../navigation/RootNavigator';
import { canManage, useAuth } from '../store/auth';
import { useParcelPanel } from '../store/parcelPanel';
import { colors, healthColor, space } from '../theme';

const ROMANIA: Region = { latitude: 45.9, longitude: 25, latitudeDelta: 5.5, longitudeDelta: 6 };
const FORMATS = { geojson: 'GeoJSON', kml: 'KML', kmz: 'KMZ', shp: 'Shapefile', lpis: 'APIA LPIS' } as const;
type Format = keyof typeof FORMATS;
/** Panoul ocupă 80% din ecran, deci parcela se centrează în zona rămasă vizibilă. */
const SHEET_RATIO = 0.8;
/** Detaliile apar doar când harta este suficient de apropiată pentru a le citi. */
const DETAIL_LATITUDE_DELTA = 0.09;
const LIVE_TICK_MS = 5000;
/** Înălțimea minimă a zonei în care se încadrează parcela selectată. */
const MIN_VISIBLE_STRIP = 64;

const toLatLng = (ring: [number, number][]): LatLng[] => ring.map(([longitude, latitude]) => ({ latitude, longitude }));

/** Conturul nu se redesenează când harta se mișcă, ca atingerile să nu se piardă. */
const ParcelPolygon = memo(function ParcelPolygon({
  parcel,
  active,
  onOpen,
}: {
  parcel: Parcel;
  active: boolean;
  onOpen: (parcel: Parcel) => void;
}) {
  const coordinates = useMemo(() => toLatLng(parcel.geometry.coordinates[0] ?? []), [parcel.geometry]);
  const holes = useMemo(() => parcel.geometry.coordinates.slice(1).map(toLatLng), [parcel.geometry]);
  const color = healthColor(parcel.satellite?.[0]?.healthClass);
  const base = color === colors.straw ? colors.wheat : color;
  return (
    <Polygon
      coordinates={coordinates}
      holes={holes}
      strokeColor={active ? colors.chaff : base}
      strokeWidth={active ? 4 : 2}
      fillColor={`${base}${active ? 'B0' : '55'}`}
      zIndex={active ? 2 : 1}
      tappable
      onPress={() => onOpen(parcel)}
    />
  );
});

function detectFormat(name: string): Format {
  const lower = name.toLowerCase();
  if (lower.endsWith('.kmz')) return 'kmz';
  if (lower.endsWith('.kml')) return 'kml';
  if (lower.endsWith('.zip')) return 'shp';
  return 'geojson';
}

export function MapScreen() {
  const navigation = useNavigation<NavigationProp<Record<string, object | undefined>>>();
  const insets = useSafeAreaInsets();
  const { height } = useWindowDimensions();
  const role = useAuth((state) => state.role);
  const parcels = useParcels();
  const map = useRef<MapView>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const { selectedParcelId, selectionToken, tracked, select, clearSelection, track, stopTracking } = useParcelPanel();
  const mapHeight = useRef(height);
  const machine = useMachineLocation(tracked?.workId ?? null);
  const list = useMemo(() => parcels.data ?? [], [parcels.data]);
  const fitted = useRef(false);
  const overview = useMapOverview();
  const [region, setRegion] = useState<Region>(ROMANIA);
  const [showInfo, setShowInfo] = useState(true);
  const currentRegion = useRef<Region>(ROMANIA);
  const regionBeforeSelection = useRef<Region | null>(null);
  const hadSelection = useRef(false);
  const infoByParcel = useMemo(() => new Map((overview.data ?? []).map((item) => [item.parcelId, item])), [overview.data]);
  const hasLiveRuns = (overview.data ?? []).some((item) => item.work.length > 0 || item.irrigation.length > 0);
  const now = useNow(LIVE_TICK_MS, hasLiveRuns);

  useEffect(() => {
    if (list.length === 0 || fitted.current) return;
    fitted.current = true;
    const points = list.flatMap((parcel) => toLatLng(parcel.geometry.coordinates[0] ?? []));
    const timer = setTimeout(() => {
      map.current?.fitToCoordinates(points, {
        edgePadding: { top: 120, right: 50, bottom: 200, left: 50 },
        animated: false,
      });
    }, 300);
    return () => clearTimeout(timer);
  }, [list]);

  const position = machine.data;
  useEffect(() => {
    if (!position) return;
    map.current?.animateCamera({ center: { latitude: position.lat, longitude: position.lng }, zoom: 16 }, { duration: 600 });
  }, [position]);

  // La deselectare, harta revine la vederea de dinaintea selecției.
  useEffect(() => {
    if (selectedParcelId) {
      hadSelection.current = true;
      return;
    }
    if (!hadSelection.current) return;
    hadSelection.current = false;
    const previous = regionBeforeSelection.current;
    regionBeforeSelection.current = null;
    if (previous && !useParcelPanel.getState().tracked) map.current?.animateToRegion(previous, 400);
  }, [selectedParcelId]);

  function handleMapPress(event: MapPressEvent) {
    if (!selectedParcelId || event.nativeEvent.action === 'marker-press') return;
    const { latitude, longitude } = event.nativeEvent.coordinate;
    const onParcel = list.some((parcel) => pointInRing([longitude, latitude], parcel.geometry.coordinates[0] ?? []));
    if (!onParcel) clearSelection();
  }

  const openParcel = useCallback(
    (parcel: Parcel) => {
      regionBeforeSelection.current ??= currentRegion.current;
      select(parcel.id);
      // Parcela se încadrează în banda de hartă rămasă vizibilă deasupra panoului.
      const top = insets.top + 12;
      const stripBottom = Math.max(top + MIN_VISIBLE_STRIP, height * (1 - SHEET_RATIO) - 8);
      map.current?.fitToCoordinates(toLatLng(parcel.geometry.coordinates[0] ?? []), {
        edgePadding: { top, right: 48, bottom: Math.max(0, mapHeight.current - stripBottom), left: 48 },
        animated: true,
      });
    },
    [height, insets.top, select],
  );

  const importFile = useAction<void>(
    async () => {
      const picked = await DocumentPicker.getDocumentAsync({ copyToCacheDirectory: true, multiple: false });
      const file = picked.assets?.[0];
      if (picked.canceled || !file) return;
      const format = detectFormat(file.name);
      const preview = await apiUpload<{ parcels: unknown[]; problems: { name: string; reason: string }[] }>(
        `/parcels/import?format=${format}&dryRun=true`,
        { uri: file.uri, name: file.name, mimeType: file.mimeType },
      );
      const issues = preview.problems.length ? `\n${preview.problems.length} elemente vor fi ignorate.` : '';
      await new Promise<void>((resolve, reject) => {
        Alert.alert('Importă parcelele', `Fișierul conține ${preview.parcels.length} parcele valide.${issues}`, [
          { text: 'Renunță', style: 'cancel', onPress: () => resolve() },
          {
            text: 'Importă',
            onPress: () => {
              apiUpload(`/parcels/import?format=${format}`, { uri: file.uri, name: file.name, mimeType: file.mimeType })
                .then(() => resolve())
                .catch(reject);
            },
          },
        ]);
      });
    },
    [['parcels'], ['dashboard']],
  );

  async function exportFile(format: Format) {
    setMenuOpen(false);
    try {
      const extension = format === 'shp' || format === 'lpis' ? `${format}.zip` : format;
      const uri = await apiDownload(`/parcels/export?format=${format}`, `parcele.${extension}`);
      await Sharing.shareAsync(uri);
    } catch (error) {
      Alert.alert('Exportul nu a reușit', errorMessage(error));
    }
  }

  async function locate() {
    const permission = await Location.requestForegroundPermissionsAsync();
    if (!permission.granted) {
      Alert.alert('Locație indisponibilă', 'Permite accesul la locație din setările telefonului.');
      return;
    }
    const { coords } = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced });
    map.current?.animateToRegion({ latitude: coords.latitude, longitude: coords.longitude, latitudeDelta: 0.03, longitudeDelta: 0.03 });
  }

  const openTab = (screen: string, parcelId: string) => navigation.navigate(screen, { parcelId });

  const detailed = region.latitudeDelta < DETAIL_LATITUDE_DELTA;
  const inView = (parcel: Parcel) =>
    Math.abs(parcel.centerLat - region.latitude) <= region.latitudeDelta &&
    Math.abs(parcel.centerLng - region.longitude) <= region.longitudeDelta;

  return (
    <View
      style={{ flex: 1, backgroundColor: colors.loam }}
      onLayout={(event) => {
        mapHeight.current = event.nativeEvent.layout.height;
      }}
    >
      <MapView
        ref={map}
        style={StyleSheet.absoluteFill}
        mapType="hybrid"
        initialRegion={ROMANIA}
        showsUserLocation
        onPress={handleMapPress}
        onRegionChangeComplete={(next) => {
          currentRegion.current = next;
          setRegion(next);
        }}
      >
        {list.map((parcel) => (
          <ParcelPolygon key={parcel.id} parcel={parcel} active={parcel.id === selectedParcelId} onOpen={openParcel} />
        ))}
        {list.filter(inView).flatMap((parcel) => {
          const info = infoByParcel.get(parcel.id);
          const showsCard = Boolean(info) && showInfo && detailed;
          return [
            ...(showsCard ? [] : [<ParcelTapTarget key={`tap-${parcel.id}`} parcel={parcel} onPress={() => openParcel(parcel)} />]),
            ...(info && showsCard
              ? [<ParcelInfoOverlay key={`info-${parcel.id}`} parcel={parcel} info={info} now={now} selected={parcel.id === selectedParcelId} onPress={() => openParcel(parcel)} />]
              : []),
            ...(info?.work ?? []).map((work) => (
              <WorkOverlay key={work.id} parcel={parcel} work={work} now={now} detailed={showInfo && detailed} onPress={() => openParcel(parcel)} />
            )),
            ...(info?.irrigation ?? []).map((run) => (
              <IrrigationOverlay
                key={run.id}
                parcel={parcel}
                run={run}
                now={now}
                detailed={showInfo && detailed}
                onPress={() => navigation.navigate('Irigații', { runId: run.id, label: run.label })}
              />
            )),
          ];
        })}
      </MapView>

      <View style={[styles.top, { paddingTop: insets.top }]}>
        <OfflineBanner />
        <View style={styles.tools}>
          <View style={styles.tool}>
            <IconButton icon="crosshairs-gps" label="Mergi la locația mea" onPress={() => void locate()} />
          </View>
          <View style={styles.tool}>
            <IconButton
              icon={showInfo ? 'layers-outline' : 'layers-off-outline'}
              label={showInfo ? 'Ascunde informațiile de pe parcele' : 'Arată informațiile pe parcele'}
              onPress={() => setShowInfo((value) => !value)}
            />
          </View>
          <Menu
            visible={menuOpen}
            onDismiss={() => setMenuOpen(false)}
            anchor={
              <View style={styles.tool}>
                <IconButton icon="swap-vertical" label="Importă sau exportă parcele" onPress={() => setMenuOpen(true)} />
              </View>
            }
          >
            {canManage(role) ? (
              <Menu.Item title="Importă un fișier" onPress={() => { setMenuOpen(false); importFile.mutate(); }} />
            ) : null}
            {(Object.keys(FORMATS) as Format[]).map((format) => (
              <Menu.Item key={format} title={`Exportă ${FORMATS[format]}`} onPress={() => void exportFile(format)} />
            ))}
          </Menu>
        </View>
      </View>

      {tracked ? (
        <Panel style={styles.sheet}>
          <Txt variant="subtitle">{tracked.label}</Txt>
          <Txt variant="small">
            {position
              ? `Actualizat la ${clock(position.recordedAt)}${position.speedKmh != null ? `, ${Math.round(position.speedKmh)} km/h` : ''}`
              : 'Așteptăm prima poziție de la telefonul operatorului.'}
          </Txt>
          <View style={{ flexDirection: 'row', gap: space.sm }}>
            <Button style={{ flex: 1 }} mode="outlined" onPress={stopTracking}>Închide</Button>
            <Button
              style={{ flex: 1 }}
              mode="contained"
              onPress={() => {
                const parcel = list.find((item) => item.id === tracked.parcelId);
                stopTracking();
                if (parcel) openParcel(parcel);
              }}
            >
              Deschide parcela
            </Button>
          </View>
        </Panel>
      ) : canManage(role) && !selectedParcelId ? (
        <FAB icon="vector-polygon" label="Parcelă nouă" style={styles.fab} color={colors.wheatInk} onPress={() => navigation.navigate('ParcelEditor')} />
      ) : null}

      <ParcelIntelligencePanel
        parcelId={selectedParcelId}
        selectionToken={selectionToken}
        onDismiss={clearSelection}
        onStartWork={(parcelId) => openTab('Work', parcelId)}
        onStartIrrigation={(parcelId) => openTab('Irigații', parcelId)}
        onAddTask={(parcelId) => openTab('To Do', parcelId)}
        onFullHistory={(parcelId, name) => (navigation as NavigationProp<RootStackParams>).navigate('ParcelDetail', { parcelId, name })}
        onConfigurePlan={(parcelId) => (navigation as NavigationProp<RootStackParams>).navigate('ParcelWizard', { parcelId })}
        onTrackWork={(work) =>
          track({
            workId: work.id,
            parcelId: work.parcel.id,
            label: `${work.machine?.name ?? 'Utilaj'}, ${WORK_TYPES[work.type]}`,
          })
        }
      />
    </View>
  );
}

const styles = StyleSheet.create({
  top: { position: 'absolute', top: 0, left: 0, right: 0 },
  tools: { alignSelf: 'flex-end', gap: space.sm, padding: space.md },
  tool: { backgroundColor: colors.furrow, borderRadius: 6 },
  sheet: { position: 'absolute', left: space.md, right: space.md, bottom: space.md },
  fab: { position: 'absolute', right: space.lg, bottom: space.lg, backgroundColor: colors.wheat },
});
