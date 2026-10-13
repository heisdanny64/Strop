# Strop Runtime Asset Provenance

**Status: EXPERIMENTAL.** Built via GitHub Actions (`.github/workflows/android-build.yml`),
installed on-device, and `initialize` succeeds. Shell command execution and tmux sessions are
still being verified on-device. Do not mark production-ready until the on-device test matrix
below is fully passing.

The source of truth for the PRoot launch flags is `buildProotCommand()` in
`modules/strop-bridge/android/src/main/java/expo/modules/stropbridge/ProotManager.kt`.

## PRoot binary (bundled in jniLibs)

Shipped in `modules/strop-bridge/android/src/main/jniLibs/arm64-v8a/`:
`libproot.so` (sha256 `1545b85b...d717`), `libprootloader.so` (`cbdef0e6...6b551`),
`libtalloc.so.2` (renamed from `libtalloc.so` to match libproot's NEEDED SONAME),
`libandroid-shmem.so`. Source: Termux proot 5.1.107.96 (GPL, proot-me fork with Android patches).
`useLegacyPackaging = true` (`modules/strop-bridge/android/build.gradle`) extracts the libs to
`nativeLibraryDir` as real files.

Rejected: ysdragon/proot-static v5.4.0 (sha256 `45e81182...ce79`). It was killed by SIGSYS
(seccomp) on Android 16 and has no LICENSE or build workflow. Do not reintroduce it.

## Ubuntu Base rootfs

| Field | Value |
|---|---|
| Source | https://cdimages.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz |
| SHA256 | `a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2` (verified against official SHA256SUMS) |
| Size | 30 MB compressed, ~105 MB extracted (grows to ~728 MB after Core Pack) |
| License | Ubuntu; includes binaries under various FOSS licenses |

## Node.js (Core Pack, installed inside rootfs during bootstrap)

| Field | Value |
|---|---|
| Source | https://nodejs.org/dist/v22.23.3/node-v22.23.3-linux-arm64.tar.xz |
| Version | 22.23.3 (LTS) |
| SHA256 | `a44aeb94849a299b22df10b9e622ec2f605c2183501bc40590705131de7c740f` |
| Verify | sha256sum inside guest before extraction |

## Extraction strategy (hardlink handling)

ubuntu-base uses hardlinks (e.g. `usr/bin/perl` and `usr/bin/perl5.38.2`,
`usr/bin/gunzip` and `usr/bin/uncompress`). Android app storage rejects hardlink creation, so a
plain host `tar` exits non-zero with "Cannot hard link ... Permission denied" and leaves files
missing (silent data loss if ignored).

Extraction runs under PRoot with `--link2symlink`, which rewrites hardlinks as symlinks into
`.l2s.*` backing files. `PROOT_TMP_DIR` points at a writable directory outside the rootfs.

## On-device test matrix

| Test | Result |
|---|---|
| Rootfs SHA256 matches official SHA256SUMS | PASS |
| tar extraction outside proot | FAILS on hardlinks (expected) |
| tar extraction under proot `--link2symlink` | PASS |
| Boot Ubuntu 24.04.5 (`uname -a`, `id`, os-release) | PASS |
| DNS resolution (getent) | PASS (after writing resolv.conf) |
| `apt-get update` / `apt-get install` (git, tmux, curl, ca-certificates) | PASS |
| Node.js 22.23.3 install with SHA256 verify | PASS (`node v22.23.3`, `npm 10.9.9`) |
| HTTPS to external APIs (GitHub, nodesource) | PASS |
| tmux session create/capture/kill (in shell) | PASS |
| Termux proot 5.1.107.96 packaged as jniLibs | PASS |
| Strop dev build via GitHub Actions, install, `initialize` | PASS |
| Probe `tmux list-sessions` on UI session `main` | Pending (probe socket fixed to `/tmp/tmux-main.sock`, not yet rebuilt) |
| Custom command execution via `execCommand` | Pending on-device verification |
| `/dev/urandom`, `/dev/shm`, `/dev/fd`, standard streams in guest | Pending on-device verification |
| Foreground service keeps PRoot alive while backgrounded | Pending on-device verification |
