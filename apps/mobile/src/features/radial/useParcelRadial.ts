import { useNavigation } from '@react-navigation/native';
import { useQuery } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useRef, type ComponentRef } from 'react';
import { BackHandler, Platform, useWindowDimensions, View, type GestureResponderEvent } from 'react-native';
import type { LatLng, LongPressEvent } from 'react-native-maps';
import { useSharedValue } from 'react-native-reanimated';
import { apiGet } from '../../api/client';
import type { Parcel, Task } from '../../api/types';
import { useAuth } from '../../store/auth';
import { useToast } from '../../store/toast';
import type { MapParcelInfo } from '../map/api';
import { radialLayout, sectorAt } from './geometry';
import { haptics } from './haptics';
import { useRadialActions } from './registry';
import { useFavoriteParcels, useRadialStore } from './store';
import type { ParcelSnapshot, RadialAction, RadialContext } from './types';

/** Between a tap (opens the parcel sheet) and a map pan. */
export const LONG_PRESS_MS = 550;
/** Finger travel allowed before the press counts as a pan instead. */
const LONG_PRESS_SLOP = 12;
/** A map tap right after the wheel was used belongs to the same gesture. */
const SWALLOW_TAP_MS = 450;
/** The native long press of the map arrives close to our own timer; only one of them opens the wheel. */
const DUPLICATE_OPEN_MS = 900;

interface Point {
  x: number;
  y: number;
}

interface Options {
  parcels: Parcel[];
  infoByParcel: Map<string, MapParcelInfo>;
  /** Finds the parcel under a point of the map view, in view coordinates. */
  resolveAt: (x: number, y: number) => Promise<Parcel | undefined>;
  /** Finds the parcel under a map coordinate. */
  parcelAtCoordinate: (coordinate: LatLng) => Parcel | undefined;
  /** Converts a map coordinate to a point of the map view. */
  pointFor: (coordinate: LatLng) => Promise<Point | undefined>;
  openParcel: (parcel: Parcel) => void;
}

/**
 * Long press detection for the parcel wheel.
 *
 * Gesture-handler gestures cannot be used over the map: on Android the native
 * map view asks its parents not to intercept touches, and gesture-handler then
 * cancels every gesture. React Native's own touch events still arrive, so the
 * press is timed from `onTouchStart/Move/End` on the view around the map. The
 * map's native `onLongPress` is kept as a fallback trigger.
 */
export function useParcelRadial({ parcels, infoByParcel, resolveAt, parcelAtCoordinate, pointFor, openParcel }: Options) {
  const navigation = useNavigation();
  const role = useAuth((state) => state.role);
  const favorites = useFavoriteParcels((state) => state.ids);
  const menu = useRadialStore((state) => state.menu);
  const { width } = useWindowDimensions();
  const lastUse = useRef(0);

  // The same cache as the To Do screen, used for task badges and overdue alerts.
  const tasks = useQuery({ queryKey: ['tasks'], staleTime: 30_000, queryFn: () => apiGet<Task[]>('/tasks') });

  const parcel = menu ? parcels.find((item) => item.id === menu.parcelId) : undefined;
  const snapshot = useMemo<ParcelSnapshot | null>(() => {
    if (!parcel) return null;
    const now = Date.now();
    const openTasks = (tasks.data ?? []).filter((task) => task.parcel?.id === parcel.id && task.status !== 'DONE');
    return {
      parcel,
      info: infoByParcel.get(parcel.id) ?? null,
      openTasks,
      overdueTasks: openTasks.filter((task) => new Date(task.dueDate).getTime() < now).length,
      favorite: favorites.includes(parcel.id),
      role,
    };
  }, [parcel, infoByParcel, tasks.data, favorites, role]);

  const actions = useRadialActions(snapshot);
  const actionsRef = useRef<RadialAction[]>(actions);
  actionsRef.current = actions;
  const snapshotRef = useRef(snapshot);
  snapshotRef.current = snapshot;
  const layout = radialLayout(width, actions.length);
  const layoutRef = useRef(layout);
  layoutRef.current = layout;

  /** The focused slice, read by the wheel on the UI thread. */
  const hovered = useSharedValue(-1);

  useEffect(() => {
    if (!menu) hovered.value = -1;
  }, [menu, hovered]);

  const context = useCallback((): RadialContext | null => {
    const current = snapshotRef.current;
    if (!current) return null;
    const navigate = (screen: string, params?: object) => (navigation.navigate as (screen: string, params?: object) => void)(screen, params);
    return {
      ...current,
      navigate,
      openParcel,
      toast: (message) => useToast.getState().show(message),
      openSheet: (actionId, params) => useRadialStore.getState().openSheet(actionId, current.parcel.id, params),
    };
  }, [navigation, openParcel]);

  const close = useCallback(() => {
    lastUse.current = Date.now();
    useRadialStore.getState().closeMenu();
  }, []);

  const select = useCallback(
    (index: number) => {
      const action = actionsRef.current[index];
      const ctx = context();
      if (!action || !ctx) return;
      haptics.confirm();
      close();
      if (action.sheet) ctx.openSheet(action.id);
      else void action.run?.(ctx);
    },
    [close, context],
  );

  // ------------------------------------------------------------ press tracking

  const container = useRef<ComponentRef<typeof View>>(null);
  const offset = useRef<Point>({ x: 0, y: 0 });
  const press = useRef<{
    start: Point;
    at: number;
    timer: ReturnType<typeof setTimeout> | null;
    /** The press has opened (or is opening) the wheel, so moves pick a slice. */
    armed: boolean;
    down: boolean;
  }>({ start: { x: 0, y: 0 }, at: 0, timer: null, armed: false, down: false });
  const lastOpenAt = useRef(0);

  const measure = useCallback(() => {
    container.current?.measureInWindow((x, y) => {
      offset.current = { x, y };
    });
  }, []);

  const toLocal = (event: GestureResponderEvent): Point => ({
    x: event.nativeEvent.pageX - offset.current.x,
    y: event.nativeEvent.pageY - offset.current.y,
  });

  const cancelTimer = () => {
    if (press.current.timer) clearTimeout(press.current.timer);
    press.current.timer = null;
  };

  const openAt = useCallback(async (point: Point, target?: Parcel) => {
    if (Date.now() - lastOpenAt.current < DUPLICATE_OPEN_MS || useRadialStore.getState().menu) return;
    lastOpenAt.current = Date.now();
    lastUse.current = Date.now();
    const found = target ?? (await resolveAt(point.x, point.y));
    if (!found) {
      lastOpenAt.current = 0;
      press.current.armed = false;
      return;
    }
    haptics.open();
    useRadialStore.getState().openMenu({ parcelId: found.id, originX: point.x, originY: point.y });
  }, [resolveAt]);

  // Releasing over a slice runs it, like a game wheel. Releasing in the middle
  // keeps the wheel open so a slice can be tapped instead.
  const release = useCallback(() => {
    const index = hovered.value;
    hovered.value = -1;
    lastUse.current = Date.now();
    if (index >= 0 && useRadialStore.getState().menu) select(index);
  }, [hovered, select]);

  const touchHandlers = {
    onLayout: measure,
    onTouchStart: (event: GestureResponderEvent) => {
      cancelTimer();
      if (event.nativeEvent.touches.length !== 1 || useRadialStore.getState().menu) {
        press.current.down = false;
        return;
      }
      measure();
      const start = toLocal(event);
      press.current = { start, at: Date.now(), timer: null, armed: false, down: true };
      press.current.timer = setTimeout(() => {
        press.current.timer = null;
        if (!press.current.down) return;
        press.current.armed = true;
        void openAt(start);
      }, LONG_PRESS_MS);
    },
    onTouchMove: (event: GestureResponderEvent) => {
      if (!press.current.down) return;
      const point = toLocal(event);
      const dx = point.x - press.current.start.x;
      const dy = point.y - press.current.start.y;
      if (press.current.timer) {
        // Moving or a second finger before the delay means pan or zoom, not a long press.
        if (dx * dx + dy * dy > LONG_PRESS_SLOP * LONG_PRESS_SLOP || event.nativeEvent.touches.length > 1) {
          cancelTimer();
          press.current.down = false;
        }
        return;
      }
      if (!press.current.armed || !useRadialStore.getState().menu) return;
      const next = sectorAt(dx, dy, actionsRef.current.length, layoutRef.current.dragDeadZone);
      if (next !== hovered.value) {
        hovered.value = next;
        if (next >= 0) haptics.tick();
      }
    },
    onTouchEnd: () => {
      cancelTimer();
      const wasArmed = press.current.armed;
      press.current.down = false;
      press.current.armed = false;
      if (wasArmed) release();
    },
    onTouchCancel: () => {
      cancelTimer();
      press.current.down = false;
      press.current.armed = false;
      hovered.value = -1;
    },
  };

  /** Fallback: the map's own long press, in case the touch timer did not fire. */
  const onMapLongPress = useCallback(
    async (event: LongPressEvent) => {
      const { coordinate } = event.nativeEvent;
      const target = parcelAtCoordinate(coordinate);
      if (!target) return;
      const recent = press.current.down && Date.now() - press.current.at < LONG_PRESS_MS * 3;
      const point = recent ? press.current.start : await pointFor(coordinate);
      if (!point) return;
      cancelTimer();
      if (press.current.down) press.current.armed = true;
      void openAt(point, target);
    },
    [openAt, parcelAtCoordinate, pointFor],
  );

  useEffect(() => () => cancelTimer(), []);

  // Android back and the Esc key of a hardware keyboard close the wheel.
  useEffect(() => {
    if (!menu) return;
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      close();
      return true;
    });
    let removeKey: (() => void) | undefined;
    if (Platform.OS === 'web' && typeof document !== 'undefined') {
      const onKey = (event: KeyboardEvent) => {
        if (event.key === 'Escape') close();
      };
      document.addEventListener('keydown', onKey);
      removeKey = () => document.removeEventListener('keydown', onKey);
    }
    return () => {
      subscription.remove();
      removeKey?.();
    };
  }, [menu, close]);

  /** True when a map tap should be ignored because it belongs to the wheel gesture. */
  const swallowsMapPress = useCallback(() => {
    if (useRadialStore.getState().menu) {
      close();
      return true;
    }
    return Date.now() - lastUse.current < SWALLOW_TAP_MS;
  }, [close]);

  return {
    container,
    touchHandlers,
    onMapLongPress,
    /** While the wheel is open the map does not pan, so dragging to a slice keeps the map still. */
    mapLocked: Boolean(menu),
    menu,
    snapshot,
    actions,
    layout,
    hovered,
    targetParcelId: menu?.parcelId ?? null,
    select,
    close,
    swallowsMapPress,
  };
}

export type ParcelRadialController = ReturnType<typeof useParcelRadial>;
