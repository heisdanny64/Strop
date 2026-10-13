import { useCallback, useEffect, useState } from 'react';
import { Platform } from 'react-native';

import { setupEnvironment, isEnvironmentReady, type SetupProgress } from '@/lib/setup';

export type ProotStatus = {
  running: boolean;
  sessions: string[];
};

export function useProot() {
  const [status, setStatus] = useState<ProotStatus | null>(null);
  const [setupProgress, setSetupProgress] = useState<SetupProgress | null>(null);
  const [bootstrapProgress, setBootstrapProgress] = useState<string[]>([]);
  const [initialized, setInitialized] = useState(false);
  const [bootstrapped, setBootstrapped] = useState(false);
  const [initializing, setInitializing] = useState(false);
  const [bootstrapping, setBootstrapping] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(() => {
    if (Platform.OS !== 'android') return;
    import('../../modules/strop-bridge')
      .then(({ default: StropBridge }) => {
        setStatus(StropBridge.getProotStatus());
      })
      .catch(() => setStatus({ running: false, sessions: [] }));
  }, []);

  const initialize = useCallback(async () => {
    if (Platform.OS !== 'android') return true;
    setInitializing(true);
    setError(null);
    try {
      const alreadyReady = await isEnvironmentReady();
      if (alreadyReady) {
        setSetupProgress({ stage: 'complete', message: 'Environment already initialized' });
      } else {
        await setupEnvironment(setSetupProgress);
      }
      setInitialized(true);
      refresh();
      return true;
    } catch (e: any) {
      setError(e?.message ?? String(e));
      return false;
    } finally {
      setInitializing(false);
    }
  }, [refresh]);

  const bootstrap = useCallback(async () => {
    if (Platform.OS !== 'android') return false;
    setBootstrapping(true);
    setBootstrapProgress([]);
    setError(null); // Clear stale errors
    try {
      const { default: StropBridge } = await import('../../modules/strop-bridge');
      await StropBridge.bootstrap();
      setBootstrapped(true);
      return true;
    } catch (e: any) {
      setError(e?.message ?? String(e));
      return false;
    } finally {
      setBootstrapping(false);
    }
  }, []);

  const start = useCallback(
    async (sessionName?: string) => {
      if (Platform.OS !== 'android') return null;
      try {
        const { default: StropBridge } = await import('../../modules/strop-bridge');
        const result = await StropBridge.startProot(sessionName ?? null);
        refresh();
        return result;
      } catch (e: any) {
        setError(e?.message ?? String(e));
        return null;
      }
    },
    [refresh]
  );

  const stop = useCallback(() => {
    if (Platform.OS !== 'android') return;
    import('../../modules/strop-bridge')
      .then(({ default: StropBridge }) => {
        StropBridge.stopProot();
        refresh();
      })
      .catch((e: any) => {
        setError(e?.message ?? String(e));
      });
  }, [refresh]);

  useEffect(() => {
    const t = setTimeout(refresh, 0);
    isEnvironmentReady().then(setInitialized);
    import('../../modules/strop-bridge')
      .then(({ default: StropBridge }) => {
        setBootstrapped(StropBridge.isBootstrapped());
        setBootstrapProgress(StropBridge.readBootstrapLog());
      })
      .catch(() => {});
    let sub1: { remove: () => void } | null = null;
    let sub2: { remove: () => void } | null = null;
    let sub3: { remove: () => void } | null = null;
    let cancelled = false;
    import('../../modules/strop-bridge')
      .then(({ default: StropBridge }) => {
        if (cancelled) return;
        sub1 = StropBridge.addListener?.('onBootstrapOutput', (evt: { line: string }) => {
          setBootstrapProgress((prev) => [...prev, evt.line]);
        }) ?? null;
        sub2 = StropBridge.addListener?.('onProotStarted', () => {
          refresh();
        }) ?? null;
        sub3 = StropBridge.addListener?.('onProotStopped', () => {
          refresh();
        }) ?? null;
      })
      .catch(() => {});
    return () => {
      cancelled = true;
      sub1?.remove();
      sub2?.remove();
      sub3?.remove();
      clearTimeout(t);
    };
  }, [refresh]);

  return {
    status,
    setupProgress,
    initialized,
    bootstrapped,
    bootstrapProgress,
    initializing,
    bootstrapping,
    error,
    initialize,
    bootstrap,
    start,
    stop,
    refresh,
  };
}
