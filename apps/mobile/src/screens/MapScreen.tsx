import { useNavigation, type NavigationProp } from '@react-navigation/native';
import * as DocumentPicker from 'expo-document-picker';
import * as Location from 'expo-location';
import * as Sharing from 'expo-sharing';
import { FIELD_STATE_COLORS, pointInRing } from '@fermapp/domain';
import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Alert, StyleSheet, useWindowDimensions, View } from 'react-native';
import MapView, { Polygon, UrlTile, type LatLng, type MapPressEvent, type Region } from 'react-native-maps';
import { Button, FAB, Menu } from 'react-native-paper';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { apiDownload, apiUpload } from '../api/client';
import type { Parcel } from '../api/types';
import { IconButton, OfflineBanner, Panel, Txt } from '../components/ui';
import { useMachineLocation } from '../features/intelligence/api';
import { ParcelIntelligencePanel } from '../features/intelligence/ParcelIntelligencePanel';
import { useMapOverview } from '../features/map/api';
import { IrrigationOverlay, ParcelInfoOverlay, ParcelTapTarget } from '../features/map/MapOverlays';
import { useFieldStatuses } from '../features/fieldStatus';
import { isRunActive, LiveOperationsPanel, useOperations, WorkOperationOverlay } from '../features/operations';
import { ParcelRadialMenu, RadialSheetHost, useFavoriteParcels, useParcelRadial } from '../features/radial';
import { MAP_LAYERS } from '../features/satellite/planetary';
import { SatelliteLegend } from '../features/satellite/SatelliteLegend';
import { TILE_CACHE_SECONDS, useMapSatelliteLayer } from '../features/satellite/useMapLayer';
import { useWeatherPrefs, useWeatherWidgetEnabled, WeatherWidget } from '../features/weather';
import { clock, shortDate, WORK_TYPES } from '../lib/format';
import { errorMessage, useAction, useNow, useParcels } from '../lib/hooks';
import type { RootStackParams } from '../navigation/RootNavigator';
import { canManage, useAuth } from '../store/auth';
import { useParcelPanel } from '../store/parcelPanel';
import { colors, fonts, space } from '../theme';

const ROMANIA: Region = { latitude: 45.9, longitude: 25, latitudeDelta: 5.5, longitudeDelta: 6 };
const FORMATS = { geojson: 'GeoJSON', kml: 'KML', kmz: 'KMZ', shp: 'Shapefile', lpis: 'APIA LPIS' } as const;
type Format = keyof typeof FORMATS;
/** The panel takes 80% of the screen, so the parcel is centred in the remaining visible area. */
const SHEET_RATIO = 0.8;
/** Details appear only when the map is zoomed in enough to read them. */
const DETAIL_LATITUDE_DELTA = 0.09;
const LIVE_TICK_MS = 5000;
/** Minimum height of the area the selected parcel is fitted into. */
const MIN_VISIBLE_STRIP = 64;
/** One tap can arrive both as an overlay press and as a map press; the second one is ignored. */
const TAP_DEDUPE_MS = 400;
/** From this width (tablets, desktop windows) the weather widget starts open. */
const WIDE_LAYOUT_MIN_WIDTH = 768;

const toLatLng = (ring: [number, number][]): LatLng[] => ring.map(([longitude, latitude]) => ({ latitude, longitude }));

/** The outline is not redrawn when the map moves, so taps are not lost. */
const ParcelPolygon = memo(function ParcelPolygon({
  parcel,
  active,
  highlighted,
  stateColor,
  onOpen,
}: {
  parcel: Parcel;
  active: boolean;
  /** The parcel the action wheel is open on. */
  highlighted: boolean;
  /** Colour of the parcel's plan status: late, due today, in progress, scheduled or complete. */
  stateColor: string;
  onOpen: (parcel: Parcel) => void;
}) {
  const coordinates = useMemo(() => toLatLng(parcel.geometry.coordinates[0] ?? []), [parcel.geometry]);
  const holes = useMemo(() => parcel.geometry.coordinates.slice(1).map(toLatLng), [parcel.geometry]);
  const base = stateColor;
  return (
    <Polygon
      coordinates={coordinates}
      holes={holes}
      strokeColor={highlighted ? colors.wheat : active ? colors.chaff : base}
      strokeWidth={highlighted ? 5 : active ? 4 : 2}
      fillColor={`${base}${highlighted ? 'C8' : active ? 'B0' : '55'}`}
      zIndex={highlighted ? 3 : active ? 2 : 1}
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
  const { width, height } = useWindowDimensions();
  const wide = width >= WIDE_LAYOUT_MIN_WIDTH;
  const showWeather = useWeatherWidgetEnabled();
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
  const { live, setLive } = useOperations();
  // Machines that reached 100% are available again: they no longer count as active.
  const activeMachines = (overview.data ?? []).reduce((sum, item) => sum + item.work.filter((work) => isRunActive(work, now)).length, 0);
  const statuses = useFieldStatuses();
  const satellite = useMapSatelliteLayer(list);
  const [satelliteMenu, setSatelliteMenu] = useState(false);

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
  // Following a machine: its GPS is used only when it is on the parcel being worked;
  // otherwise the camera frames the parcel, where the machine is drawn.
  useEffect(() => {
    if (!tracked) return;
    const parcel = listRef.current.find((item) => item.id === tracked.parcelId);
    const ring = parcel?.geometry.coordinates[0] ?? [];
    if (position && pointInRing([position.lng, position.lat], ring)) {
      map.current?.animateCamera({ center: { latitude: position.lat, longitude: position.lng }, zoom: 16 }, { duration: 600 });
    } else if (ring.length) {
      map.current?.fitToCoordinates(toLatLng(ring), { edgePadding: { top: 120, right: 60, bottom: 260, left: 60 }, animated: true });
    }
  }, [position, tracked]);

  /** Live Operations frames every parcel with a running machine. */
  const toggleLive = () => {
    const next = !live;
    setLive(next);
    if (!next) return;
    const working = new Set((overview.data ?? []).filter((item) => item.work.length > 0).map((item) => item.parcelId));
    const points = listRef.current.filter((parcel) => working.has(parcel.id)).flatMap((parcel) => toLatLng(parcel.geometry.coordinates[0] ?? []));
    if (points.length) map.current?.fitToCoordinates(points, { edgePadding: { top: 140, right: 60, bottom: 300, left: 60 }, animated: true });
  };

  const focusParcel = (parcel: Parcel) =>
    map.current?.fitToCoordinates(toLatLng(parcel.geometry.coordinates[0] ?? []), { edgePadding: { top: 140, right: 60, bottom: 320, left: 60 }, animated: true });

  // On deselection, the map returns to the view from before the selection.
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

  const lastOpen = useRef({ parcelId: '', at: 0 });
  const listRef = useRef(list);
  listRef.current = list;

  /** The parcel under a map coordinate. Overlapping parcels resolve to the smallest one. */
  function parcelAt(latitude: number, longitude: number) {
    const point: [number, number] = [longitude, latitude];
    return listRef.current
      .filter((parcel) => {
        const [outer = [], ...holes] = parcel.geometry.coordinates;
        return pointInRing(point, outer) && !holes.some((hole) => pointInRing(point, hole));
      })
      .sort((a, b) => a.areaHa - b.areaHa)[0];
  }

  // Every tap on the map is resolved here too, so a parcel opens even when the
  // polygon or marker press is not delivered by the native map.
  function handleMapPress(event: MapPressEvent) {
    if (event.nativeEvent.action === 'marker-press') return;
    // A tap that ends a long press, or lands while the action wheel is open, is not a parcel tap.
    if (radial.swallowsMapPress()) return;
    // On phones, touching the map folds the weather card back into its pill.
    const weather = useWeatherPrefs.getState();
    if (!wide && weather.expanded) weather.setExpanded(false);
    if (Date.now() - lastOpen.current.at < TAP_DEDUPE_MS) return;
    const { latitude, longitude } = event.nativeEvent.coordinate;
    const parcel = parcelAt(latitude, longitude);
    if (parcel) openParcel(parcel);
    else if (selectedParcelId) clearSelection();
  }

  const openParcel = useCallback(
    (parcel: Parcel) => {
      const now = Date.now();
      if (lastOpen.current.parcelId === parcel.id && now - lastOpen.current.at < TAP_DEDUPE_MS) return;
      lastOpen.current = { parcelId: parcel.id, at: now };
      regionBeforeSelection.current ??= currentRegion.current;
      select(parcel.id);
      // The parcel is fitted into the map strip that remains visible above the panel.
      const top = insets.top + 12;
      const stripBottom = Math.max(top + MIN_VISIBLE_STRIP, height * (1 - SHEET_RATIO) - 8);
      map.current?.fitToCoordinates(toLatLng(parcel.geometry.coordinates[0] ?? []), {
        edgePadding: { top, right: 48, bottom: Math.max(0, mapHeight.current - stripBottom), left: 48 },
        animated: true,
      });
    },
    [height, insets.top, select],
  );

  // Long press on a parcel opens the action wheel; a normal tap still opens the parcel sheet.
  const resolveAt = useCallback(async (x: number, y: number) => {
    const coordinate = await map.current?.coordinateForPoint({ x, y }).catch(() => undefined);
    return coordinate ? parcelAt(coordinate.latitude, coordinate.longitude) : undefined;
    // parcelAt reads the latest parcel list on every call.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  const parcelAtCoordinate = useCallback(
    (coordinate: LatLng) => parcelAt(coordinate.latitude, coordinate.longitude),
    // parcelAt reads the latest parcel list on every call.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );
  const pointFor = useCallback(async (coordinate: LatLng) => map.current?.pointForCoordinate(coordinate).catch(() => undefined), []);
  const radial = useParcelRadial({ parcels: list, infoByParcel, resolveAt, parcelAtCoordinate, pointFor, openParcel });
  const favorites = useFavoriteParcels((state) => state.ids);

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
      const issues = preview.problems.length ? `\n${preview.problems.length} elements will be skipped.` : '';
      await new Promise<void>((resolve, reject) => {
        Alert.alert('Import parcels', `The file contains ${preview.parcels.length} valid parcels.${issues}`, [
          { text: 'Cancel', style: 'cancel', onPress: () => resolve() },
          {
            text: 'Import',
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
      const uri = await apiDownload(`/parcels/export?format=${format}`, `parcels.${extension}`);
      await Sharing.shareAsync(uri);
    } catch (error) {
      Alert.alert('The export failed', errorMessage(error));
    }
  }

  async function locate() {
    const permission = await Location.requestForegroundPermissionsAsync();
    if (!permission.granted) {
      Alert.alert('Location unavailable', 'Allow location access in the phone settings.');
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
      <View ref={radial.container} style={StyleSheet.absoluteFill} {...radial.touchHandlers}>
      <MapView
        ref={map}
        style={StyleSheet.absoluteFill}
        mapType="hybrid"
        initialRegion={ROMANIA}
        showsUserLocation
        // On Android, the default marker press centres the camera and cancels the parcel fit.
        moveOnMarkerPress={false}
        onPress={handleMapPress}
        onLongPress={radial.onMapLongPress}
        scrollEnabled={!radial.mapLocked}
        zoomEnabled={!radial.mapLocked}
        rotateEnabled={!radial.mapLocked}
        pitchEnabled={!radial.mapLocked}
        onRegionChangeComplete={(next) => {
          currentRegion.current = next;
          setRegion(next);
        }}
      >
        {/* Satellite overlay under the parcels; tiles load in the background and are cached on disk. */}
        {satellite.urlTemplate ? (
          <UrlTile key={satellite.urlTemplate} urlTemplate={satellite.urlTemplate} opacity={0.62} zIndex={0} minimumZ={6} maximumZ={17} tileCacheMaxAge={TILE_CACHE_SECONDS} tileCachePath={satellite.cachePath ?? undefined} />
        ) : null}
        {list.map((parcel) => (
          <ParcelPolygon
            key={parcel.id}
            parcel={parcel}
            active={parcel.id === selectedParcelId}
            highlighted={parcel.id === radial.targetParcelId}
            stateColor={statuses.get(parcel.id) ? FIELD_STATE_COLORS[statuses.get(parcel.id)!.state] : colors.wheat}
            onOpen={openParcel}
          />
        ))}
        {list.filter(inView).flatMap((parcel) => {
          const info = infoByParcel.get(parcel.id);
          const showsCard = Boolean(info) && showInfo && detailed;
          return [
            ...(showsCard ? [] : [<ParcelTapTarget key={`tap-${parcel.id}`} parcel={parcel} status={statuses.get(parcel.id)} onPress={() => openParcel(parcel)} />]),
            ...(info && showsCard
              ? [<ParcelInfoOverlay key={`info-${parcel.id}`} parcel={parcel} info={info} now={now} selected={parcel.id === selectedParcelId} favorite={favorites.includes(parcel.id)} status={statuses.get(parcel.id)} onPress={() => openParcel(parcel)} />]
              : []),
            ...(info?.work ?? []).map((work) => (
              <WorkOperationOverlay key={work.id} parcel={parcel} work={work} showCard={live || (showInfo && detailed)} onPress={() => openParcel(parcel)} />
            )),
            ...(info?.irrigation ?? []).map((run) => (
              <IrrigationOverlay
                key={run.id}
                parcel={parcel}
                run={run}
                now={now}
                detailed={showInfo && detailed}
                onPress={() => navigation.navigate('Irrigation', { runId: run.id, label: run.label })}
              />
            )),
          ];
        })}
      </MapView>
      </View>

      <View pointerEvents="box-none" style={[styles.top, { paddingTop: insets.top }]}>
        <OfflineBanner />
        <View pointerEvents="box-none" style={styles.topRow}>
          <View pointerEvents="box-none" style={styles.weatherSlot}>
            {/* Hidden while a parcel panel is open: the parcel is framed in the strip above it. */}
            {showWeather && !selectedParcelId ? <WeatherWidget wide={wide} /> : null}
          </View>
          <View style={styles.tools}>
            <View style={styles.tool}>
              <IconButton icon="crosshairs-gps" label="Go to my location" onPress={() => void locate()} />
            </View>
            <View style={[styles.tool, live && styles.toolLive]}>
              <IconButton
                icon="access-point"
                label={live ? 'Turn off Live Operations' : 'Turn on Live Operations'}
                color={live ? colors.wheatInk : undefined}
                onPress={toggleLive}
              />
              {activeMachines > 0 ? (
                <View pointerEvents="none" style={styles.toolBadge}>
                  <Txt variant="small" color={colors.loam} style={styles.toolBadgeText}>{String(activeMachines)}</Txt>
                </View>
              ) : null}
            </View>
            <View style={styles.tool}>
              <IconButton
                icon={showInfo ? 'layers-outline' : 'layers-off-outline'}
                label={showInfo ? 'Hide information on parcels' : 'Show information on parcels'}
                onPress={() => setShowInfo((value) => !value)}
              />
            </View>
            {satellite.available ? (
              <Menu
                visible={satelliteMenu}
                onDismiss={() => setSatelliteMenu(false)}
                anchor={
                  <View style={[styles.tool, satellite.layer && styles.toolLive]}>
                    <IconButton icon="satellite-variant" label="Satellite layers" color={satellite.layer ? colors.wheatInk : undefined} onPress={() => setSatelliteMenu(true)} />
                  </View>
                }
              >
                <Menu.Item title="No satellite layer" leadingIcon={satellite.layer ? undefined : 'check'} onPress={() => { satellite.setLayer(null); setSatelliteMenu(false); }} />
                {MAP_LAYERS.map((item) => (
                  <Menu.Item key={item.value} title={item.label} leadingIcon={satellite.layer === item.value ? 'check' : undefined} onPress={() => { satellite.setLayer(item.value); setSatelliteMenu(false); }} />
                ))}
                {satellite.layer ? (
                  <Menu.Item disabled title={satellite.loading ? 'Finding the latest image…' : satellite.scene ? `Sentinel-2 image of ${shortDate(satellite.scene.datetime)}` : 'No clear image in 45 days'} />
                ) : null}
              </Menu>
            ) : null}
            <Menu
              visible={menuOpen}
              onDismiss={() => setMenuOpen(false)}
              anchor={
                <View style={styles.tool}>
                  <IconButton icon="swap-vertical" label="Import or export parcels" onPress={() => setMenuOpen(true)} />
                </View>
              }
            >
              {canManage(role) ? (
                <Menu.Item title="Import a file" onPress={() => { setMenuOpen(false); importFile.mutate(); }} />
              ) : null}
              {(Object.keys(FORMATS) as Format[]).map((format) => (
                <Menu.Item key={format} title={`Export ${FORMATS[format]}`} onPress={() => void exportFile(format)} />
              ))}
            </Menu>
          </View>
        </View>
      </View>

      {tracked ? (
        <Panel style={styles.sheet}>
          <Txt variant="subtitle">{tracked.label}</Txt>
          <Txt variant="small">
            {position
              ? `Updated at ${clock(position.recordedAt)}${position.speedKmh != null ? `, ${Math.round(position.speedKmh)} km/h` : ''}`
              : 'Shown on its planned route across the parcel until the operator’s phone sends a position from the field.'}
          </Txt>
          <View style={{ flexDirection: 'row', gap: space.sm }}>
            <Button style={{ flex: 1 }} mode="outlined" onPress={stopTracking}>Close</Button>
            <Button
              style={{ flex: 1 }}
              mode="contained"
              onPress={() => {
                const parcel = list.find((item) => item.id === tracked.parcelId);
                stopTracking();
                if (parcel) openParcel(parcel);
              }}
            >
              Open parcel
            </Button>
          </View>
        </Panel>
      ) : live ? (
        <LiveOperationsPanel parcels={list} overview={overview.data ?? []} bottom={space.md} onFocus={focusParcel} onClose={() => setLive(false)} />
      ) : canManage(role) && !selectedParcelId ? (
        <FAB icon="vector-polygon" label="New parcel" style={styles.fab} color={colors.wheatInk} onPress={() => navigation.navigate('ParcelEditor')} />
      ) : null}

      {satellite.urlTemplate && satellite.scale && !selectedParcelId ? (
        <SatelliteLegend title={MAP_LAYERS.find((item) => item.value === satellite.layer)?.label ?? ''} scale={satellite.scale} date={satellite.scene?.datetime ?? null} />
      ) : null}
      <ParcelRadialMenu controller={radial} />
      <RadialSheetHost parcels={list} openParcel={openParcel} />

      <ParcelIntelligencePanel
        parcelId={selectedParcelId}
        selectionToken={selectionToken}
        onDismiss={clearSelection}
        onStartWork={(parcelId) => openTab('Work', parcelId)}
        onStartIrrigation={(parcelId) => openTab('Irrigation', parcelId)}
        onAddTask={(parcelId) => openTab('To Do', parcelId)}
        onFullHistory={(parcelId, name) => (navigation as NavigationProp<RootStackParams>).navigate('ParcelDetail', { parcelId, name })}
        onConfigurePlan={(parcelId) => (navigation as NavigationProp<RootStackParams>).navigate('ParcelWizard', { parcelId })}
        onOpenSatellite={(parcelId, name) => (navigation as NavigationProp<RootStackParams>).navigate('SatelliteMonitoring', { parcelId, name })}
        onTrackWork={(work) =>
          track({
            workId: work.id,
            parcelId: work.parcel.id,
            label: `${work.machine?.name ?? 'Machine'}, ${WORK_TYPES[work.type]}`,
          })
        }
      />
    </View>
  );
}

const styles = StyleSheet.create({
  top: { position: 'absolute', top: 0, left: 0, right: 0 },
  topRow: { flexDirection: 'row', alignItems: 'flex-start' },
  weatherSlot: { flex: 1, alignItems: 'flex-start', paddingTop: space.md, paddingLeft: space.md },
  tools: { gap: space.sm, padding: space.md },
  tool: { backgroundColor: colors.furrow, borderRadius: 6 },
  toolLive: { backgroundColor: colors.wheat },
  toolBadge: {
    position: 'absolute',
    top: -4,
    right: -4,
    minWidth: 18,
    height: 18,
    paddingHorizontal: 4,
    borderRadius: 9,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.leaf,
    borderWidth: 2,
    borderColor: colors.loam,
  },
  toolBadgeText: { fontSize: 10, lineHeight: 12, fontFamily: fonts.bold },
  sheet: { position: 'absolute', left: space.md, right: space.md, bottom: space.md },
  fab: { position: 'absolute', right: space.lg, bottom: space.lg, backgroundColor: colors.wheat },
});
