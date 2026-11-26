import { MaterialCommunityIcons } from '@expo/vector-icons';
import { BottomSheetBackdrop, BottomSheetModal, BottomSheetScrollView, type BottomSheetBackdropProps } from '@gorhom/bottom-sheet';
import { useNavigation } from '@react-navigation/native';
import { useCallback, useEffect, useMemo, useRef } from 'react';
import { Pressable, StyleSheet, useWindowDimensions, View } from 'react-native';
import type { Parcel } from '../../api/types';
import { Txt } from '../../components/ui';
import { colors, space } from '../../theme';
import { getRadialAction } from './registry';
import { useRadialStore } from './store';
import type { SheetParams } from './types';

const TABLET_WIDTH = 768;
const TABLET_SHEET = 640;

/** One bottom sheet for every wheel action that needs a form. */
export function RadialSheetHost({ parcels, openParcel }: { parcels: Parcel[]; openParcel: (parcel: Parcel) => void }) {
  const sheet = useRadialStore((state) => state.sheet);
  const ref = useRef<BottomSheetModal>(null);
  const navigation = useNavigation();
  const { width, height } = useWindowDimensions();
  const action = sheet ? getRadialAction(sheet.actionId) : undefined;
  const parcel = sheet ? parcels.find((item) => item.id === sheet.parcelId) : undefined;

  // Dismissing a sheet that is already closed leaves the library stuck, so it is tracked.
  const presented = useRef(false);
  useEffect(() => {
    if (sheet && action?.sheet && parcel) {
      if (!presented.current) ref.current?.present();
      presented.current = true;
    } else if (presented.current) {
      ref.current?.dismiss();
    }
  }, [sheet, action, parcel]);

  const close = useCallback(() => ref.current?.dismiss(), []);
  const navigate = useCallback(
    (screen: string, params?: object) => (navigation.navigate as (screen: string, params?: object) => void)(screen, params),
    [navigation],
  );
  const openSheet = useCallback(
    (actionId: string, params?: SheetParams) => {
      if (parcel) useRadialStore.getState().openSheet(actionId, parcel.id, params);
    },
    [parcel],
  );

  const backdrop = useCallback(
    (props: BottomSheetBackdropProps) => <BottomSheetBackdrop {...props} appearsOnIndex={0} disappearsOnIndex={-1} opacity={0.45} pressBehavior="close" />,
    [],
  );
  const sideMargin = useMemo(() => (width >= TABLET_WIDTH ? (width - TABLET_SHEET) / 2 : 0), [width]);
  const Content = action?.sheet;

  return (
    <BottomSheetModal
      ref={ref}
      enableDynamicSizing
      maxDynamicContentSize={height * 0.86}
      enablePanDownToClose
      stackBehavior="push"
      onDismiss={() => {
        presented.current = false;
        useRadialStore.getState().closeSheet();
      }}
      backdropComponent={backdrop}
      keyboardBehavior="interactive"
      keyboardBlurBehavior="restore"
      android_keyboardInputMode="adjustResize"
      style={{ marginHorizontal: sideMargin }}
      backgroundStyle={styles.background}
      handleIndicatorStyle={styles.handle}
    >
      <BottomSheetScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        {Content && parcel && sheet ? (
          <>
            <View style={styles.header}>
              <View style={[styles.icon, { borderColor: `${action.accent}66` }]}>
                <MaterialCommunityIcons name={typeof action.icon === 'string' ? action.icon : 'circle-outline'} size={20} color={action.accent} />
              </View>
              <View style={{ flex: 1 }}>
                <Txt variant="title" numberOfLines={1}>{action.sheetTitle ?? action.label}</Txt>
                <Txt variant="small" numberOfLines={1}>{`${parcel.name}, ${parcel.code}`}</Txt>
              </View>
              <Pressable accessibilityRole="button" accessibilityLabel="Close" hitSlop={10} onPress={close} style={styles.close}>
                <MaterialCommunityIcons name="close" size={22} color={colors.straw} />
              </Pressable>
            </View>
            <Content key={sheet.token} parcel={parcel} params={sheet.params} close={close} navigate={navigate} openSheet={openSheet} openParcel={openParcel} />
          </>
        ) : null}
      </BottomSheetScrollView>
    </BottomSheetModal>
  );
}

const styles = StyleSheet.create({
  background: { backgroundColor: colors.loam, borderTopLeftRadius: 20, borderTopRightRadius: 20 },
  handle: { backgroundColor: colors.line, width: 44 },
  content: { padding: space.lg, paddingBottom: space.xxl * 1.5, gap: space.lg },
  header: { flexDirection: 'row', alignItems: 'center', gap: space.md },
  icon: { width: 40, height: 40, borderRadius: 20, borderWidth: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.furrow },
  close: { width: 44, height: 44, alignItems: 'center', justifyContent: 'center' },
});
