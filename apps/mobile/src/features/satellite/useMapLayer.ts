import { useQuery } from '@tanstack/react-query';
import { Paths } from 'expo-file-system';
import { useMemo } from 'react';
import type { Parcel } from '../../api/types';
import { remember } from './cache';
import { LAYER_SCALES, searchScenes, tileUrlTemplate, type BBox } from './planetary';
import { useSatelliteSettings } from './prefs';

const HOUR = 3_600_000;
/** Map tiles of a scene never change; the phone keeps them for six hours before re-checking. */
export const TILE_CACHE_SECONDS = 6 * 3600;
export const TILE_CACHE_PATH = `${Paths.cache.uri.replace(/\/?$/, '/')}satellite-tiles`;

const isoDay = (ms: number) => new Date(ms).toISOString().slice(0, 10);

/**
 * The overlay chosen for the farm map, drawn from the latest mostly clear
 * Sentinel-2 scene over the farm. Nothing is fetched while the layer is off.
 */
export function useMapSatelliteLayer(parcels: readonly Parcel[]) {
  const settings = useSatelliteSettings();
  const layer = settings.active && settings.mapLayers ? settings.mapLayer : null;
  const bbox = useMemo<BBox | null>(() => {
    const points = parcels.flatMap((parcel) => parcel.geometry.coordinates[0] ?? []);
    if (!points.length) return null;
    const lngs = points.map((point) => point[0]!);
    const lats = points.map((point) => point[1]!);
    const round = (value: number) => Math.round(value * 100) / 100;
    return [round(Math.min(...lngs)), round(Math.min(...lats)), round(Math.max(...lngs)), round(Math.max(...lats))];
  }, [parcels]);
  const scene = useQuery({
    queryKey: ['satellite-map-scene', bbox?.join(',')],
    enabled: layer != null && bbox != null,
    staleTime: 6 * HOUR,
    queryFn: async () => {
      const now = Date.now();
      const scenes = await remember(`sat:map:${bbox!.join(',')}:${isoDay(now)}`, 6 * HOUR, () => searchScenes(bbox!, isoDay(now - 45 * 86_400_000), isoDay(now), 25));
      return scenes[0] ?? null;
    },
  });
  return {
    available: settings.active && settings.mapLayers,
    layer,
    setLayer: (value: typeof settings.mapLayer) => settings.set({ mapLayer: value }),
    scene: scene.data ?? null,
    loading: scene.isFetching,
    urlTemplate: layer && scene.data ? tileUrlTemplate(scene.data.id, layer) : null,
    // The map caches tiles by position only (folder/z/x/y), so each image and layer needs its own folder.
    cachePath: layer && scene.data ? `${TILE_CACHE_PATH}/${scene.data.id}/${layer}` : null,
    scale: layer ? LAYER_SCALES[layer] : null,
  };
}
