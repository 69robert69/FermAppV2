import { useQueryClient } from '@tanstack/react-query';
import * as ImagePicker from 'expo-image-picker';
import { useState } from 'react';
import { Alert, Image, StyleSheet, View } from 'react-native';
import { Button } from 'react-native-paper';
import { API_URL, apiSend, apiUpload } from '../../api/client';
import { Panel, Section, Txt } from '../../components/ui';
import { shortDate } from '../../lib/format';
import { errorMessage, useAction } from '../../lib/hooks';
import { useAuth } from '../../store/auth';
import { colors, space } from '../../theme';
import { intelligenceKey, type ParcelIntelligence } from './api';
import { SheetInput } from './sections';

const QUALITY = 0.7;

/** Fotografia se încarcă împreună cu nota scrisă în formular. */
export function usePhotoUpload(parcelId: string) {
  const client = useQueryClient();
  const [notes, setNotes] = useState('');
  const [uploading, setUploading] = useState(false);

  async function upload(asset: ImagePicker.ImagePickerAsset) {
    setUploading(true);
    try {
      await apiUpload(
        `/parcels/${parcelId}/photos`,
        { uri: asset.uri, name: asset.fileName ?? 'fotografie.jpg', mimeType: asset.mimeType ?? 'image/jpeg' },
        notes.trim() ? { notes: notes.trim() } : undefined,
      );
      setNotes('');
      await client.invalidateQueries({ queryKey: intelligenceKey(parcelId) });
    } catch (error) {
      Alert.alert('Fotografia nu a fost încărcată', errorMessage(error));
    } finally {
      setUploading(false);
    }
  }

  async function takePhoto() {
    const permission = await ImagePicker.requestCameraPermissionsAsync();
    if (!permission.granted) {
      Alert.alert('Camera nu este disponibilă', 'Permite accesul la cameră din setările telefonului.');
      return;
    }
    const result = await ImagePicker.launchCameraAsync({ quality: QUALITY });
    if (!result.canceled && result.assets[0]) await upload(result.assets[0]);
  }

  async function pickPhoto() {
    const result = await ImagePicker.launchImageLibraryAsync({ mediaTypes: ['images'], quality: QUALITY });
    if (!result.canceled && result.assets[0]) await upload(result.assets[0]);
  }

  return { notes, setNotes, uploading, takePhoto, pickPhoto };
}

export function PhotosSection({ data, photos }: { data: ParcelIntelligence; photos: ReturnType<typeof usePhotoUpload> }) {
  const token = useAuth((state) => state.accessToken);
  const parcelId = data.header.id;
  const [text, setText] = useState('');
  const addObservation = useAction<void>(
    async () => {
      await apiSend('POST', `/parcels/${parcelId}/observations`, { text: text.trim() });
    },
    [intelligenceKey(parcelId)],
    () => setText(''),
  );

  return (
    <>
      <Section title="Fotografii">
        <Panel>
          {data.photos.length === 0 ? <Txt variant="small">Nicio fotografie pentru această parcelă.</Txt> : null}
          <View style={styles.gallery}>
            {data.photos.map((photo) => (
              <View key={photo.id} style={styles.tile}>
                <Image
                  accessibilityLabel={photo.notes ?? `Fotografie din ${shortDate(photo.createdAt)}`}
                  source={
                    photo.imageUri
                      ? { uri: photo.imageUri }
                      : {
                          uri: `${API_URL}/parcels/${parcelId}/photos/${photo.id}/file`,
                          headers: token ? { Authorization: `Bearer ${token}` } : undefined,
                        }
                  }
                  style={styles.image}
                />
                <Txt variant="small" numberOfLines={2}>{photo.notes ?? shortDate(photo.createdAt)}</Txt>
              </View>
            ))}
          </View>
          <SheetInput label="Notă pentru fotografie" value={photos.notes} onChangeText={photos.setNotes} multiline />
          <View style={{ flexDirection: 'row', gap: space.sm }}>
            <Button style={{ flex: 1 }} mode="contained" icon="camera-outline" loading={photos.uploading} disabled={photos.uploading} onPress={() => void photos.takePhoto()}>
              Fotografiază
            </Button>
            <Button style={{ flex: 1 }} mode="outlined" icon="image-outline" disabled={photos.uploading} onPress={() => void photos.pickPhoto()}>
              Din galerie
            </Button>
          </View>
        </Panel>
      </Section>

      <Section title="Observații din teren">
        <Panel>
          {data.observations.map((item) => (
            <View key={item.id} style={{ gap: 2 }}>
              <Txt>{item.text}</Txt>
              <Txt variant="small">{`${item.author.fullName}, ${shortDate(item.createdAt)}`}</Txt>
            </View>
          ))}
          <SheetInput label="Observație nouă" value={text} onChangeText={setText} multiline />
          <Button mode="contained" disabled={!text.trim() || addObservation.isPending} loading={addObservation.isPending} onPress={() => addObservation.mutate()}>
            Salvează observația
          </Button>
        </Panel>
      </Section>
    </>
  );
}

const styles = StyleSheet.create({
  gallery: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  tile: { width: '31%', gap: space.xs },
  image: { width: '100%', aspectRatio: 1, borderRadius: 4, backgroundColor: colors.ridge },
});
