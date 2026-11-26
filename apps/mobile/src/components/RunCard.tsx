import { View } from 'react-native';
import { Button } from 'react-native-paper';
import type { TimedRun } from '../api/types';
import { clock, duration } from '../lib/format';
import { useLiveProgress } from '../lib/hooks';
import { colors, space } from '../theme';
import { Panel, ProgressTrack, Txt } from './ui';

interface Props {
  run: TimedRun;
  title: string;
  subtitle: string;
  color?: string;
  canControl?: boolean;
  onAction?: (action: 'pause' | 'resume' | 'complete') => void;
  busy?: boolean;
}

/** Active operation: timer, percent done and ETA, updated every second. */
export function RunCard({ run, title, subtitle, color = colors.wheat, canControl, onAction, busy }: Props) {
  const progress = useLiveProgress(run);
  const paused = run.status === 'PAUSED';
  return (
    <Panel>
      <View style={{ flexDirection: 'row', alignItems: 'flex-start', gap: space.md }}>
        <View style={{ flex: 1 }}>
          <Txt variant="subtitle">{title}</Txt>
          <Txt variant="small">{subtitle}</Txt>
        </View>
        <Txt variant="figure" color={color}>{`${Math.floor(progress.percent)}%`}</Txt>
      </View>
      <ProgressTrack percent={progress.percent} color={paused ? colors.straw : color} />
      <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
        <Txt variant="small">{`Elapsed ${duration(progress.elapsedSeconds)}`}</Txt>
        <Txt variant="small">
          {paused ? 'Paused' : progress.eta ? `Finishes at ${clock(progress.eta)}` : ''}
        </Txt>
      </View>
      {canControl && onAction ? (
        <View style={{ flexDirection: 'row', gap: space.sm }}>
          <Button mode="outlined" disabled={busy} style={{ flex: 1 }} onPress={() => onAction(paused ? 'resume' : 'pause')}>
            {paused ? 'Resume' : 'Pause'}
          </Button>
          <Button mode="contained" disabled={busy} style={{ flex: 1 }} onPress={() => onAction('complete')}>
            Complete
          </Button>
        </View>
      ) : null}
    </Panel>
  );
}
