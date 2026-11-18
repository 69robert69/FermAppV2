import { computeGeoMetrics, polygonFromPoints, validatePolygon, type Position } from '@fermapp/domain';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { useQuery } from '@tanstack/react-query';
import * as Location from 'expo-location';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Alert, KeyboardAvoidingView, Platform, ScrollView, StyleSheet, View } from 'react-native';
import MapView, { Marker, Polygon, Polyline, type MapPressEvent } from 'react-native-maps';
import { Button, HelperText } from 'react-native-paper';
import { apiGet, apiSend } from '../api/client';
import type { Parcel } from '../api/types';
import { Field, IconButton, Stat, Txt } from '../components/ui';
import { number, parseNumber } from '../lib/format';
import { useAction } from '../lib/hooks';
import type { RootStackParams } from '../navigation/RootNavigator';
import { colors, space } from '../theme';

type Props = NativeStackScreenProps<RootStackParams, 'ParcelEditor'>;

const toCoord = ([longitude, latitude]: Position) => ({ latitude, longitude });

export function ParcelEditorScreen({ route, navigation }: Props) {
  const parcelId = route.params?.parcelId;
  const existing = useQuery({
    queryKey: ['parcel', parcelId],
    queryFn: () => apiGet<Parcel>(`/parcels/${parcelId}`),
    enabled: Boolean(parcelId),
  });
  const map = useRef<MapView>(null);
  const [points, setPoints] = useState<Position[]>([]);
  const [name, setName] = useState('');
  const [code, setCode] = useState('');
  const [locality, setLocality] = useState('');
  const [county, setCounty] = useState('');
  const [notes, setNotes] = useState('');
  const [irrigated, setIrrigated] = useState('');
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    const parcel = existing.data;
    if (!parcel || loaded) return;
    const ring = parcel.geometry.coordinates[0] ?? [];
    const open = ring.slice(0, -1);
    setPoints(open);
    setName(parcel.name);
    setCode(parcel.code);
    setLocality(parcel.locality ?? '');
    setCounty(parcel.county ?? '');
    setNotes(parcel.notes ?? '');
    setIrrigated(parcel.irrigatedAreaHa == null ? '' : String(parcel.irrigatedAreaHa));
    setLoaded(true);
    setTimeout(() => map.current?.fitToCoordinates(open.map(toCoord), {
      edgePadding: { top: 40, right: 40, bottom: 40, left: 40 },
      animated: false,
    }), 300);
  }, [existing.data, loaded]);

  useEffect(() => {
    if (parcelId) return;
    void (async () => {
      const permission = await Location.requestForegroundPermissionsAsync();
      if (!permission.granted) return;
      const { coords } = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced });
      map.current?.animateToRegion({ latitude: coords.latitude, longitude: coords.longitude, latitudeDelta: 0.02, longitudeDelta: 0.02 });
    })().catch(() => undefined);
  }, [parcelId]);

  const polygon = useMemo(() => (points.length >= 3 ? polygonFromPoints(points) : null), [points]);
  const problem = polygon ? validatePolygon(polygon) : null;
  const metrics = polygon && !problem ? computeGeoMetrics(polygon) : null;

  const formValues = () => ({
    name: name.trim(),
    geometry: polygon!,
    locality: locality.trim() || undefined,
    county: county.trim() || undefined,
    notes: notes.trim() || undefined,
    code: code.trim() || undefined,
    irrigatedAreaHa: irrigated.trim() ? parseNumber(irrigated) : null,
  });

  const save = useAction<void>(
    async () => {
      await apiSend('PATCH', `/parcels/${parcelId}`, formValues());
    },
    [['parcels'], ['parcel', parcelId], ['dashboard']],
    () => navigation.goBack(),
  );

  const archive = useAction<void>(
    async () => {
      await apiSend('POST', `/parcels/${parcelId}/archive`);
    },
    [['parcels'], ['dashboard']],
    () => navigation.popToTop(),
  );

  const confirmArchive = () =>
    Alert.alert('Arhivează parcela', 'Parcela dispare din liste, dar istoricul ei se păstrează.', [
      { text: 'Renunță', style: 'cancel' },
      { text: 'Arhivează', style: 'destructive', onPress: () => archive.mutate() },
    ]);

  const movePoint = (index: number, event: { nativeEvent: { coordinate: { latitude: number; longitude: number } } }) => {
    const { latitude, longitude } = event.nativeEvent.coordinate;
    setPoints((current) => current.map((point, i) => (i === index ? [longitude, latitude] : point)));
  };

  const addPoint = (event: MapPressEvent) => {
    const { latitude, longitude } = event.nativeEvent.coordinate;
    setPoints((current) => [...current, [longitude, latitude]]);
  };

  const coords = points.map(toCoord);

  return (
    <KeyboardAvoidingView style={{ flex: 1 }} behavior={Platform.OS === 'ios' ? 'padding' : undefined} keyboardVerticalOffset={90}>
      <View style={styles.mapBox}>
        <MapView
          ref={map}
          style={StyleSheet.absoluteFill}
          mapType="hybrid"
          showsUserLocation
          initialRegion={{ latitude: 45.9, longitude: 25, latitudeDelta: 5.5, longitudeDelta: 6 }}
          onPress={addPoint}
        >
          {points.length >= 3 ? (
            <Polygon coordinates={coords} strokeColor={problem ? colors.rust : colors.wheat} strokeWidth={2} fillColor={problem ? `${colors.rust}44` : `${colors.wheat}44`} />
          ) : points.length === 2 ? (
            <Polyline coordinates={coords} strokeColor={colors.wheat} strokeWidth={2} />
          ) : null}
          {points.map((point, index) => (
            <Marker
              key={`${index}-${points.length}`}
              coordinate={toCoord(point)}
              draggable
              anchor={{ x: 0.5, y: 0.5 }}
              onDragEnd={(event) => movePoint(index, event)}
              tracksViewChanges={false}
            >
              <View style={styles.vertex} />
            </Marker>
          ))}
        </MapView>
        <View style={styles.mapTools}>
          <View style={styles.tool}>
            <IconButton icon="undo" label="Anulează ultimul punct" onPress={() => setPoints((current) => current.slice(0, -1))} />
          </View>
          <View style={styles.tool}>
            <IconButton icon="delete-outline" label="Șterge poligonul" onPress={() => setPoints([])} color={colors.rust} />
          </View>
        </View>
        {points.length < 3 ? (
          <View style={styles.hint}>
            <Txt variant="small" color={colors.chaff}>Atinge harta pentru a marca colțurile parcelei. Ține apăsat pe un punct pentru a-l muta.</Txt>
          </View>
        ) : null}
      </View>

      <ScrollView contentContainerStyle={styles.form} keyboardShouldPersistTaps="handled">
        <View style={{ flexDirection: 'row' }}>
          <Stat value={metrics ? number(metrics.areaHa, 2) : '0'} unit="ha" label="Suprafață" />
          <Stat value={metrics ? number(metrics.perimeterM, 0) : '0'} unit="m" label="Perimetru" />
        </View>
        {metrics ? <Txt variant="small">{`Centru GPS: ${metrics.center.lat.toFixed(5)}, ${metrics.center.lng.toFixed(5)}`}</Txt> : null}
        {problem ? <HelperText type="error" visible>{problem}</HelperText> : null}
        <Field label="Nume parcelă" value={name} onChangeText={setName} />
        <Field label="Cod parcelă (opțional)" value={code} onChangeText={setCode} autoCapitalize="characters" />
        <Field label="Localitate" value={locality} onChangeText={setLocality} />
        <Field label="Județ" value={county} onChangeText={setCounty} />
        <Field label="Suprafață irigată (ha)" value={irrigated} onChangeText={setIrrigated} keyboardType="decimal-pad" />
        <Field label="Observații" value={notes} onChangeText={setNotes} multiline />
        <Button
          mode="contained"
          loading={save.isPending}
          disabled={!metrics || !name.trim() || save.isPending}
          onPress={() => (parcelId ? save.mutate() : navigation.navigate('ParcelWizard', { draft: formValues() }))}
        >
          {parcelId ? 'Salvează parcela' : 'Continuă'}
        </Button>
        {parcelId ? (
          <Button textColor={colors.rust} onPress={confirmArchive}>Arhivează parcela</Button>
        ) : null}
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  mapBox: { height: '48%', backgroundColor: colors.ridge },
  mapTools: { position: 'absolute', top: space.md, right: space.md, gap: space.sm },
  tool: { backgroundColor: colors.furrow, borderRadius: 6 },
  hint: { position: 'absolute', left: space.md, right: 76, bottom: space.md, backgroundColor: `${colors.loam}E6`, borderRadius: 6, padding: space.sm },
  vertex: { width: 18, height: 18, borderRadius: 9, backgroundColor: colors.wheat, borderWidth: 3, borderColor: colors.loam },
  form: { padding: space.lg, gap: space.md, paddingBottom: space.xxl },
});
