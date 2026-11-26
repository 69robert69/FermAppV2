import { collection, getDocs, limit, query, where } from 'firebase/firestore';
import { manipulateAsync, SaveFormat } from 'expo-image-manipulator';
import { ApiError } from '../api/client';
import { firestore } from './app';
import { pendingSnapshot, queueWrites, readSnapshot, writeSnapshot } from './outbox';
import { farmFields, newId, session } from './services';
import type { PhotoDoc } from './types';

/** A Firestore document has at most 1 MB, and the encoded image must fit in it. */
const MAX_ENCODED_LENGTH = 700_000;
const ATTEMPTS = [
  { width: 1280, compress: 0.6 },
  { width: 960, compress: 0.45 },
  { width: 640, compress: 0.35 },
];
const SHOWN_PHOTOS = 12;
const READ_TIMEOUT_MS = 8000;

/**
 * The free Firebase plan does not include Storage, so the photo is shrunk and saved in Firestore.
 * This way the whole team sees it, and without a connection it waits in the sync queue.
 */
export async function addPhoto(parcelId: string, sourceUri: string, notes?: string) {
  for (const { width, compress } of ATTEMPTS) {
    const image = await manipulateAsync(sourceUri, [{ resize: { width } }], { compress, format: SaveFormat.JPEG, base64: true });
    if (!image.base64 || image.base64.length > MAX_ENCODED_LENGTH) continue;
    const id = newId('photos');
    await queueWrites([
      {
        kind: 'set',
        collection: 'photos',
        docId: id,
        data: { ...farmFields(), parcelId, authorId: session().uid, imageBase64: image.base64, notes: notes ?? null },
      },
    ]);
    return { id };
  }
  throw new ApiError(400, 'PHOTO_TOO_LARGE', 'The photo is too large. Choose another image.');
}

/** Photos are read only when the parcel is opened, so they do not consume the free quota. */
export async function parcelPhotos(parcelId: string) {
  const { farmId } = session();
  const key = `photos:${parcelId}`;
  const source = query(collection(firestore, 'photos'), where('farmId', '==', farmId), where('parcelId', '==', parcelId), limit(SHOWN_PHOTOS));
  let photos: PhotoDoc[];
  try {
    const snapshot = await Promise.race([
      getDocs(source),
      new Promise<never>((_, reject) => setTimeout(() => reject(new Error('TIMEOUT')), READ_TIMEOUT_MS)),
    ]);
    photos = snapshot.docs.map((item) => ({ ...(item.data() as PhotoDoc), id: item.id }));
    await writeSnapshot(key, photos);
  } catch {
    photos = (await readSnapshot<PhotoDoc[]>(key)) ?? [];
  }
  const known = new Set(photos.map((photo) => photo.id));
  const waiting = pendingSnapshot()
    .filter((write) => write.collection === 'photos' && write.data?.parcelId === parcelId && !known.has(write.docId))
    .map((write) => ({ ...(write.data as unknown as PhotoDoc), id: write.docId }));
  return [...waiting, ...photos]
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    .map((photo) => ({ id: photo.id, notes: photo.notes, createdAt: photo.createdAt, imageUri: `data:image/jpeg;base64,${photo.imageBase64}` }));
}
