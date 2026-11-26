import { buildWeatherBrief, type WeatherBrief } from '@fermapp/domain';
import { useQuery } from '@tanstack/react-query';
import * as Location from 'expo-location';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { getForecast, localHour, type Forecast } from '../../firebase/weather';
import { useNow, useParcels } from '../../lib/hooks';
import { useAuth } from '../../store/auth';
import { useWeatherPrefs } from './prefs';

export interface WeatherPlace {
  lat: number;
  lng: number;
  label: string;
}

/** About 1 km: GPS jitter does not start a new download. */
const roundCoord = (value: number) => Math.round(value * 100) / 100;
const placeNames = new Map<string, string>();
/** The brief is recomputed every minute, so "now" and the alerts move on between downloads. */
const CLOCK_TICK_MS = 60_000;
/** The forecast starts at midnight yesterday; eight days cover the 72-hour alerts and the five-day outlook. */
const KEPT_HOURS = 8 * 24;

async function placeName(lat: number, lng: number, fallback: string) {
  const key = `${lat}:${lng}`;
  const known = placeNames.get(key);
  if (known) return known;
  try {
    const [address] = await Location.reverseGeocodeAsync({ latitude: lat, longitude: lng });
    const name = address?.city ?? address?.district ?? address?.subregion ?? address?.region;
    if (name) {
      placeNames.set(key, name);
      return name;
    }
  } catch {
    // The geocoder is optional; the farm name is used instead.
  }
  return fallback;
}

/**
 * The phone's position when location access was already granted (the widget never asks),
 * otherwise the area-weighted centre of the farm's parcels.
 */
function useWeatherPlace(): WeatherPlace | null {
  const parcels = useParcels();
  const farmName = useAuth((state) => state.farm?.name) ?? 'Farm';
  const farmCentre = useMemo(() => {
    const list = parcels.data ?? [];
    const area = list.reduce((sum, parcel) => sum + (parcel.areaHa || 0), 0);
    if (!list.length) return null;
    const weight = (parcel: (typeof list)[number]) => (area > 0 ? parcel.areaHa / area : 1 / list.length);
    return {
      lat: roundCoord(list.reduce((sum, parcel) => sum + parcel.centerLat * weight(parcel), 0)),
      lng: roundCoord(list.reduce((sum, parcel) => sum + parcel.centerLng * weight(parcel), 0)),
    };
  }, [parcels.data]);
  const [device, setDevice] = useState<{ lat: number; lng: number } | null>(null);
  const [granted, setGranted] = useState(false);
  const [place, setPlace] = useState<WeatherPlace | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const permission = await Location.getForegroundPermissionsAsync();
        if (cancelled || !permission.granted) return;
        setGranted(true);
        const last = await Location.getLastKnownPositionAsync({ maxAge: 30 * 60_000 });
        if (!cancelled && last) setDevice({ lat: roundCoord(last.coords.latitude), lng: roundCoord(last.coords.longitude) });
      } catch {
        // Without a position, the farm centre is used.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const point = device ?? farmCentre;
  useEffect(() => {
    if (!point) return;
    let cancelled = false;
    const fallback = device ? 'Your location' : farmName;
    setPlace((current) => (current?.lat === point.lat && current.lng === point.lng ? current : { ...point, label: fallback }));
    if (granted) {
      void placeName(point.lat, point.lng, fallback).then((label) => {
        if (!cancelled) setPlace({ ...point, label });
      });
    }
    return () => {
      cancelled = true;
    };
    // The point is compared by value.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [point?.lat, point?.lng, granted, farmName, Boolean(device)]);

  return place;
}

export interface FarmWeather {
  place: WeatherPlace | null;
  brief: WeatherBrief | null;
  updatedAt: Date | null;
  loading: boolean;
  refreshing: boolean;
  error: boolean;
  refresh: () => void;
}

/** Mounted only while the widget is enabled: when it is off, nothing here runs. */
export function useFarmWeather(): FarmWeather {
  const place = useWeatherPlace();
  const refreshMs = useWeatherPrefs((state) => state.refreshMinutes) * 60_000;
  const force = useRef(false);
  const query = useQuery({
    queryKey: ['weather-widget', place?.lat, place?.lng],
    enabled: place != null,
    queryFn: async (): Promise<Forecast> => {
      const maxAge = force.current ? 0 : refreshMs;
      force.current = false;
      const full = await getForecast(place!.lat, place!.lng, maxAge);
      // The query cache is saved on the phone: keep only yesterday to day +6, which is all the widget reads.
      return { ...full, hours: full.hours.slice(0, KEPT_HOURS), days: full.days.slice(0, KEPT_HOURS / 24) };
    },
    staleTime: refreshMs,
    refetchInterval: refreshMs,
    refetchIntervalInBackground: false,
    retry: 1,
  });
  const now = useNow(CLOCK_TICK_MS, query.data != null);
  const forecast: Forecast | undefined = query.data;
  const brief = useMemo(() => {
    if (!forecast) return null;
    const current = forecast.current;
    // A measured value older than the refresh interval is dropped in favour of the hourly forecast.
    const live = current && current.time.slice(0, 13) === localHour(forecast, now).slice(0, 13) ? current : null;
    return buildWeatherBrief({ hours: forecast.hours, days: forecast.days, current: live, now: localHour(forecast, now) });
  }, [forecast, now]);

  const { refetch } = query;
  const refresh = useCallback(() => {
    force.current = true;
    void refetch();
  }, [refetch]);

  return {
    place,
    brief,
    updatedAt: forecast ? new Date(forecast.fetchedAt ?? query.dataUpdatedAt) : null,
    loading: query.isPending,
    refreshing: query.isFetching && !query.isPending,
    error: query.isError && !query.data,
    refresh,
  };
}
