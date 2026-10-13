import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, ToastAndroid } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import * as Clipboard from 'expo-clipboard';

import { ThemedText } from '@/components/themed-text';
import { ThemedView } from '@/components/themed-view';
import { useProot } from '@/hooks/useProot';
import { BottomTabInset, MaxContentWidth, Spacing } from '@/constants/theme';

export default function HomeScreen() {
  const proot = useProot();
  const [execResult, setExecResult] = useState<string | null>(null);
  const [executing, setExecuting] = useState(false);
  const [copied, setCopied] = useState(false);
  const logRef = useRef<ScrollView>(null);

  useEffect(() => {
    if (logRef.current && proot.bootstrapProgress.length > 0) {
      logRef.current.scrollToEnd({ animated: true });
    }
  }, [proot.bootstrapProgress]);

  const runProbe = async (cmd: string, label: string) => {
    setExecuting(true);
    setExecResult(null);
    try {
      const { default: TaviBridge } = await import('../../modules/tavi-bridge');
      const res = await TaviBridge.execCommand(cmd, '/root');
      const out = [res.stdout, res.stderr].filter(Boolean).join('\n').trim();
      setExecResult(
        res.exitCode === 0
          ? out || '(no output)'
          : `${out || '(no output)'}\n\n[exit ${res.exitCode}]`
      );
    } finally {
      setExecuting(false);
    }
  };

  const copyLogs = async (text: string = proot.bootstrapProgress.join('\n')) => {
    await Clipboard.setStringAsync(text);
    setCopied(true);
    ToastAndroid.show('Copied to clipboard', ToastAndroid.SHORT);
    setTimeout(() => setCopied(false), 2000);
  };

  const ready = proot.initialized;

  return (
    <ThemedView style={styles.container}>
      <SafeAreaView style={styles.safeArea}>
        <ScrollView
          style={styles.scroll}
          contentContainerStyle={styles.content}
          showsVerticalScrollIndicator={false}>
          <ThemedText type="subtitle">Tavi Runtime</ThemedText>
          <ThemedText type="small" themeColor="textSecondary">
            EXPERIMENTAL — PRoot on-device test dashboard
          </ThemedText>

          {/* Status card */}
          <ThemedView type="backgroundElement" style={styles.card}>
            <ThemedText type="smallBold">Status</ThemedText>
            <ThemedText type="code">
              initialized: {String(proot.initialized)}
              {'\n'}
              proot running: {proot.status?.running ? 'yes' : 'no'}
              {'\n'}
              sessions: {proot.status?.sessions.length ? proot.status.sessions.join(', ') : 'none'}
            </ThemedText>
            <TestButton
              label="Copy Status"
              onPress={() =>
                copyLogs(
                  [
                    `initialized: ${String(proot.initialized)}`,
                    `proot running: ${proot.status?.running ? 'yes' : 'no'}`,
                    `sessions: ${proot.status?.sessions.length ? proot.status.sessions.join(', ') : 'none'}`,
                  ].join('\n')
                )
              }
            />
            {proot.error && (
              <ThemedText type="small" style={styles.error}>
                {proot.error}
              </ThemedText>
            )}
          </ThemedView>

          {/* Setup buttons */}
          <ThemedView type="backgroundElement" style={styles.card}>
            <ThemedText type="smallBold">1. Setup (download + verify + extract rootfs)</ThemedText>
            <TestButton
              label={proot.initializing ? 'Setting up…' : 'Initialize'}
              disabled={proot.initializing || proot.bootstrapping}
              onPress={() => proot.initialize()}
            />
            {proot.initializing && <ActivityIndicator style={{ marginTop: Spacing.two }} />}
            {proot.setupProgress && (
              <ThemedText type="small">
                {proot.setupProgress.message}
                {proot.setupProgress.percent != null ? ` (${proot.setupProgress.percent}%)` : ''}
              </ThemedText>
            )}
          </ThemedView>

          {/* Bootstrap */}
          <ThemedView type="backgroundElement" style={styles.card}>
            <ThemedText type="smallBold">2. Bootstrap (apt + Node 22)</ThemedText>
            <TestButton
              label={proot.bootstrapping ? 'Bootstrapping…' : 'Bootstrap'}
              disabled={proot.bootstrapping || proot.initializing || !ready}
              onPress={() => proot.bootstrap()}
            />
            {proot.bootstrapProgress.length > 0 && (
              <>
                <ScrollView ref={logRef} style={styles.log} nestedScrollEnabled>
                  {proot.bootstrapProgress.map((line, i) => (
                    <ThemedText key={i} type="code">
                      {line}
                    </ThemedText>
                  ))}
                </ScrollView>
                <TestButton label={copied ? 'Copied!' : 'Copy Logs'} onPress={copyLogs} />
              </>
            )}
          </ThemedView>

          {/* Probe commands */}
          <ThemedView type="backgroundElement" style={styles.card}>
            <ThemedText type="smallBold">3. Probes (run inside rootfs)</ThemedText>
            <TestButton
              label="uname -a"
              disabled={executing || !ready}
              onPress={() => runProbe('uname -a && id && cat /etc/os-release', 'uname -a')}
            />
            <TestButton
              label="node --version"
              disabled={executing || !ready}
              onPress={() => runProbe('node --version && npm --version', 'node --version')}
            />
            <TestButton
              label="tmux list-sessions"
              disabled={executing || !ready}
              onPress={() => runProbe('tmux ls 2>&1 || echo "no sessions"', 'tmux ls')}
            />
            {executing && <ActivityIndicator style={{ marginTop: Spacing.two }} />}
            {execResult && (
              <>
                <ScrollView style={styles.log} horizontal showsHorizontalScrollIndicator={false}>
                  <ThemedText type="code">{execResult}</ThemedText>
                </ScrollView>
                <TestButton
                  label={copied ? 'Copied!' : 'Copy Output'}
                  onPress={() => copyLogs(execResult)}
                />
              </>
            )}
          </ThemedView>

          {/* Session control */}
          <ThemedView type="backgroundElement" style={styles.card}>
            <ThemedText type="smallBold">4. PRoot session (tmux)</ThemedText>
            <TestButton
              label="Start session 'main'"
              disabled={proot.bootstrapping || proot.initializing || !ready}
              onPress={() => proot.start('main')}
            />
            <TestButton
              label="Stop all sessions"
              disabled={proot.bootstrapping || proot.initializing}
              onPress={() => proot.stop()}
            />
            <ThemedText type="small">
              Foreground service starts with the session; notification permission not yet
              requested at runtime.
            </ThemedText>
          </ThemedView>
        </ScrollView>
      </SafeAreaView>
    </ThemedView>
  );
}

function TestButton({
  label,
  disabled,
  onPress,
}: {
  label: string;
  disabled?: boolean;
  onPress: () => void;
}) {
  return (
    <Pressable onPress={onPress} disabled={disabled} hitSlop={4}>
      {({ pressed }) => (
        <ThemedView
          type={disabled ? 'backgroundSelected' : 'backgroundSelected'}
          style={[styles.button, pressed && styles.pressed]}>
          <ThemedText type="smallBold" themeColor={disabled ? 'textSecondary' : 'text'}>
            {label}
          </ThemedText>
        </ThemedView>
      )}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  safeArea: {
    flex: 1,
    alignItems: 'center',
  },
  scroll: {
    flex: 1,
    alignSelf: 'stretch',
  },
  content: {
    paddingHorizontal: Spacing.three,
    paddingTop: Spacing.three,
    paddingBottom: BottomTabInset + Spacing.four,
    gap: Spacing.three,
    maxWidth: MaxContentWidth,
    alignSelf: 'center',
    width: '100%',
  },
  card: {
    gap: Spacing.two,
    padding: Spacing.three,
    borderRadius: Spacing.two,
  },
  log: {
    maxHeight: 160,
    borderRadius: Spacing.one,
    padding: Spacing.two,
    backgroundColor: 'rgba(127,127,127,0.12)',
  },
  button: {
    paddingVertical: Spacing.two,
    paddingHorizontal: Spacing.three,
    borderRadius: Spacing.two,
    alignSelf: 'flex-start',
  },
  pressed: {
    opacity: 0.7,
  },
  error: {
    color: '#e5484d',
  },
});
