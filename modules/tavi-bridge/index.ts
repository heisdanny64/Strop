import { requireNativeModule, EventEmitter } from 'expo-modules-core';

type ExecResult = {
  stdout: string;
  stderr: string;
  exitCode: number;
};

type ProotStatus = {
  running: boolean;
  sessions: string[];
};

type StartResult = {
  success: boolean;
  pid: number;
};

type BootstrapOutput = {
  line: string;
};

declare class TaviBridgeModule extends EventEmitter<TaviBridgeEvents> {
  sha256(path: string): string;
  initialize(rootfsPath: string, rootfsSha256: string, rootfsVersion: string): Promise<boolean>;
  bootstrap(): Promise<boolean>;
  isInitialized(): boolean;
  startProot(sessionName?: string | null): Promise<StartResult>;
  stopProot(): boolean;
  getProotStatus(): ProotStatus;
  execCommand(cmd: string, cwd: string): Promise<ExecResult>;
  readFile(path: string): string;
  writeFile(path: string, content: string): boolean;
  listDir(path: string): string[];
}

type TaviBridgeEvents = {
  onBootstrapOutput: (data: { line: string }) => void;
  onProotStarted: (data: { sessionName: string; pid: number }) => void;
  onProotStopped: (data: { sessionName: string; exitCode: number }) => void;
  onProotError: (data: { error: string }) => void;
  onSessionOutput: (data: { sessionName: string; output: string }) => void;
  onSessionExited: (data: { sessionName: string; exitCode: number }) => void;
} & Record<string, (data: any) => void>;

export default requireNativeModule<TaviBridgeModule>('TaviBridge');