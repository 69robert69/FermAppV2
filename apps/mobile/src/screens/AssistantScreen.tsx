import { useMutation } from '@tanstack/react-query';
import { useRef, useState, type ComponentRef } from 'react';
import { KeyboardAvoidingView, Platform, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { ActivityIndicator } from 'react-native-paper';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { apiSend, isQueued } from '../api/client';
import { ParcelPicker } from '../components/pickers';
import { Field, IconButton, OfflineBanner, Txt } from '../components/ui';
import { CopilotScreen } from '../features/copilot/CopilotScreen';
import { usesFirebase } from '../firebase/config';
import { errorMessage } from '../lib/hooks';
import { colors, space } from '../theme';

interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  note?: string;
}

const SUGGESTIONS = [
  'Which crop do you recommend for next season?',
  'Are conditions good for spraying herbicide this week?',
  'What are the main risks on this parcel?',
];

/**
 * The AI tab. In Firebase mode the copilot reads the farm data on the phone and plans tasks;
 * the server mode keeps its own assistant, which answers from the server database.
 */
export function AssistantScreen() {
  return usesFirebase ? <CopilotScreen /> : <ServerAssistantScreen />;
}

function ServerAssistantScreen() {
  const insets = useSafeAreaInsets();
  const scroll = useRef<ComponentRef<typeof ScrollView>>(null);
  const [parcelId, setParcelId] = useState<string | null>(null);
  const [conversationId, setConversationId] = useState<string>();
  const [messages, setMessages] = useState<Message[]>([]);
  const [draft, setDraft] = useState('');

  const ask = useMutation({
    mutationFn: async (message: string) => {
      const result = await apiSend<{ conversationId: string; message: { id: string; content: string; source: string } }>(
        'POST',
        '/ai/chat',
        { message, conversationId, parcelId: parcelId ?? undefined },
        { offline: false },
      );
      if (isQueued(result)) throw new Error('offline');
      return result;
    },
    onSuccess: (result) => {
      setConversationId(result.conversationId);
      setMessages((current) => [
        ...current,
        {
          id: result.message.id,
          role: 'assistant',
          content: result.message.content,
          note: result.message.source === 'rules' ? 'Summary calculated from the parcel data' : undefined,
        },
      ]);
    },
    onError: (error) => {
      setMessages((current) => [...current, { id: `error-${Date.now()}`, role: 'assistant', content: errorMessage(error) }]);
    },
  });

  function send(text: string) {
    const message = text.trim();
    if (!message || ask.isPending) return;
    setMessages((current) => [...current, { id: `user-${Date.now()}`, role: 'user', content: message }]);
    setDraft('');
    ask.mutate(message);
  }

  return (
    <KeyboardAvoidingView style={styles.screen} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <View style={{ paddingTop: insets.top }}>
        <OfflineBanner />
        <View style={styles.head}>
          <Txt variant="display">Assistant</Txt>
          <ParcelPicker value={parcelId} onChange={setParcelId} label="Analyse parcel" />
        </View>
      </View>

      <ScrollView
        ref={scroll}
        style={{ flex: 1 }}
        contentContainerStyle={styles.thread}
        keyboardShouldPersistTaps="handled"
        onContentSizeChange={() => scroll.current?.scrollToEnd({ animated: true })}
      >
        {messages.length === 0 ? (
          <View style={{ gap: space.sm }}>
            <Txt color={colors.straw}>Choose a parcel and ask about rotation, soil, weather or field work.</Txt>
            {SUGGESTIONS.map((suggestion) => (
              <Pressable key={suggestion} accessibilityRole="button" style={styles.suggestion} onPress={() => send(suggestion)}>
                <Txt>{suggestion}</Txt>
              </Pressable>
            ))}
          </View>
        ) : null}
        {messages.map((message) => (
          <View key={message.id} style={[styles.bubble, message.role === 'user' ? styles.mine : styles.theirs]}>
            <Txt color={message.role === 'user' ? colors.wheatInk : colors.chaff}>{message.content}</Txt>
            {message.note ? <Txt variant="small">{message.note}</Txt> : null}
          </View>
        ))}
        {ask.isPending ? <ActivityIndicator color={colors.wheat} style={{ alignSelf: 'flex-start' }} accessibilityLabel="The assistant is answering" /> : null}
      </ScrollView>

      <View style={styles.composer}>
        <Field style={{ flex: 1 }} label="Your question" value={draft} onChangeText={setDraft} multiline maxLength={4000} />
        <IconButton icon="send" label="Send question" color={colors.wheat} onPress={() => send(draft)} />
      </View>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.loam },
  head: { padding: space.lg, gap: space.sm },
  thread: { paddingHorizontal: space.lg, paddingBottom: space.lg, gap: space.md },
  suggestion: { backgroundColor: colors.furrow, borderRadius: 6, padding: space.md, minHeight: 44, justifyContent: 'center' },
  bubble: { padding: space.md, borderRadius: 8, maxWidth: '90%', gap: space.xs },
  mine: { backgroundColor: colors.wheat, alignSelf: 'flex-end' },
  theirs: { backgroundColor: colors.furrow, alignSelf: 'flex-start' },
  composer: { flexDirection: 'row', alignItems: 'center', gap: space.xs, padding: space.sm, backgroundColor: colors.furrow },
});
