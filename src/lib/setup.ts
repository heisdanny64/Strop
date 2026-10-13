import { Directory, File, Paths } from 'expo-file-system';

function uriToPath(uri: string): string {
  const withoutPrefix = uri.startsWith('file://') ? uri.slice('file://'.length) : uri;
  try {
    return decodeURIComponent(withoutPrefix);
  } catch {
    return withoutPrefix;
  }
}

/**
 * Pinned download sources for the Strop Linux runtime.
 * EXPERIMENTAL — see docs/provenance.md for verification status and risks.
 */
export const ASSETS = {
  rootfs: {
    url: 'https://cdimages.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz',
    sha256: 'a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2',
    fileName: 'ubuntu-base-24.04.5-base-arm64.tar.gz',
    version: '1',
  },
} as const;

export type SetupStage =
  | 'idle'
  | 'downloading-rootfs'
  | 'verifying'
  | 'extracting'
  | 'complete';

export type SetupProgress = {
  stage: SetupStage;
  message: string;
  bytesWritten?: number;
  totalBytes?: number;
  percent?: number;
};

type ProgressFn = (p: SetupProgress) => void;

async function downloadIfMissing(
  asset: { url: string; fileName: string; sha256: string },
  destDir: Directory,
  onProgress: ProgressFn,
  stage: SetupStage
): Promise<File> {
  const { default: StropBridge } = await import('../../modules/strop-bridge');
  const dest = new File(destDir, asset.fileName);
  if (dest.exists) {
    onProgress({ stage: 'verifying', message: `Verifying ${asset.fileName}` });
    const hash = StropBridge.sha256(uriToPath(dest.uri));
    if (hash.toLowerCase() === asset.sha256) return dest;
    // Corrupt or stale file — delete and re-download
    dest.delete();
  }
  const task = File.createDownloadTask(asset.url, dest, {
    onProgress: ({ bytesWritten, totalBytes }) => {
      onProgress({
        stage,
        message: `Downloading ${asset.fileName}`,
        bytesWritten,
        totalBytes,
        percent: totalBytes > 0 ? Math.round((bytesWritten / totalBytes) * 100) : 0,
      });
    },
  });
  try {
    const result = await task.downloadAsync();
    if (!result) throw new Error(`Download failed or cancelled: ${asset.fileName}`);
    onProgress({ stage: 'verifying', message: `Verifying ${asset.fileName}` });
    const actual = StropBridge.sha256(uriToPath(dest.uri));
    if (actual.toLowerCase() !== asset.sha256) {
      dest.delete();
      throw new Error(
        `Checksum mismatch for ${asset.fileName}: expected ${asset.sha256}, got ${actual}. ` +
        `The download may be corrupted or tampered with. Deleted and aborted.`
      );
    }
    return dest;
  } finally {
    task.release();
  }
}

// In-flight promise to prevent concurrent setup calls
let inflightSetup: Promise<void> | null = null;

/**
 * Downloads and verifies the Ubuntu rootfs, then hands it to the native bridge
 * for extraction. PRoot itself ships in jniLibs.
 * EXPERIMENTAL — runtime not yet validated end-to-end in the dev build.
 */
export async function setupEnvironment(onProgress: ProgressFn = () => {}): Promise<void> {
  // Prevent concurrent calls
  if (inflightSetup) {
    return inflightSetup;
  }

  const promise = (async () => {
    const { default: StropBridge } = await import('../../modules/strop-bridge');

    const cache = new Directory(Paths.cache, 'tavi-setup');
    cache.create({ idempotent: true });

    const rootfsFile = await downloadIfMissing(
      { ...ASSETS.rootfs },
      cache,
      onProgress,
      'downloading-rootfs'
    );

    onProgress({ stage: 'extracting', message: 'Extracting rootfs (may take several minutes)' });
    await StropBridge.initialize(uriToPath(rootfsFile.uri), ASSETS.rootfs.sha256, ASSETS.rootfs.version);
    // Clean up tarball after successful extraction
    try {
      rootfsFile.delete();
    } catch {
      // Ignore cleanup errors
    }
    onProgress({ stage: 'complete', message: 'Environment ready' });
  })();

  inflightSetup = promise;
  try {
    await promise;
  } finally {
    inflightSetup = null;
  }
}

export async function isEnvironmentReady(): Promise<boolean> {
  try {
    const { default: StropBridge } = await import('../../modules/strop-bridge');
    return StropBridge.isInitialized();
  } catch {
    return false;
  }
}
