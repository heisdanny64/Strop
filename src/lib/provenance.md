# Tavi Runtime Asset Provenance

**Status: EXPERIMENTAL.** The PRoot runtime below has passed on-device smoke tests
(Termux environment, Android 16, aarch64) but has NOT been validated through the
Tavi dev-client build on a real device. Do not mark production-ready until the
EAS build passes the on-device test matrix in PLAN.md §4.

## PRoot binary (bundled in jniLibs — current)

Shipped in `modules/tavi-bridge/android/src/main/jniLibs/arm64-v8a/`:
`libproot.so` (sha256 `1545b85b…d717`), `libprootloader.so` (`cbdef0e6…6b551`),
`libtalloc.so.2` (renamed from `libtalloc.so` to match libproot's NEEDED SONAME),
`libandroid-shmem.so`. Source: Termux proot 5.1.107.96. `useLegacyPackaging = true`
is set so the libs are extracted to `nativeLibraryDir` as real files.
The ysdragon download below is no longer used by the app.

## PRoot binary (ysdragon — superseded, do not ship)

| Field | Value |
|---|---|
| Source | https://github.com/ysdragon/proot-static/releases/download/v5.4.0/proot-aarch64-static |
| Upstream | Built from official https://github.com/proot-me/proot (per repo README) |
| Version | 5.4.0 (repo tag v5.4.0; single commit d810872375, 2024-09-01) |
| SHA256 | `45e81182f839a1752d8c8408e7d4569a58ae51d4331326d7f662a105f859ce79` |
| ELF | AArch64, e_machine=0xB7, verified via header inspection |
| Size | 1,560,648 bytes |
| License | Upstream proot is GPL-2.0-or-later. The build repo has **no LICENSE file and no build workflow** — provenance is README-only. Treat as untrusted third-party build. |
| Flags used | `--kill-on-exit`, `--link2symlink`, `-0` (root-id), `--change-id=0:0`, `-L`, `--kernel-release`, `-r`, `-w`, `-b` |

### ⚠️ Known runtime risk: SIGSYS (signal 31)

The ysdragon binary was killed by **SIGSYS (seccomp)** when executed directly on
Android 16 (untrusted_app context) in this session's testing. The Termux-patched
proot 5.1.107.96 (`packages.termux.dev`, proot-me fork + Android patches, GPL,
needs `libtalloc` + `libandroid-shmem`) works perfectly on the same device.

**Decision for implementation:** download flow verifies SHA256 before use, but the
default source should be considered **suspect until proven on-device via the Tavi
dev build**. If it fails, the follow-up is a CI build of
https://github.com/termux/proot (statically linked or with talloc/shmem bundled).
The ysdragon binary may still pass inside the app's own process context (Termux's
app context differs), but do not assume it.

## Ubuntu Base rootfs

| Field | Value |
|---|---|
| Source | https://cdimages.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz |
| SHA256 | `a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2` (verified on-device against official SHA256SUMS) |
| Size | 30 MB compressed, ~105 MB extracted (grows to ~728 MB after Core Pack) |
| License | Ubuntu, includes binaries under various FOSS licenses |

## Node.js (Core Pack, installed inside rootfs during bootstrap)

| Field | Value |
|---|---|
| Source | https://nodejs.org/dist/v22.23.3/node-v22.23.3-linux-arm64.tar.xz |
| Version | 22.23.3 (LTS) |
| SHA256 | `a44aeb94849a299b22df10b9e622ec2f605c2183501bc40590705131de7c740f` |
| Verify | sha256sum inside guest before extraction |

## Extraction strategy (hardlink handling)

ubuntu-base uses hardlinks (e.g. `usr/bin/perl` ↔ `usr/bin/perl5.38.2`,
`usr/bin/gunzip` ↔ `usr/bin/uncompress`). f2fs/Android app storage rejects
hardlink creation → `tar` exits non-zero with "Cannot hard link ... Permission
denied" and leaves files **missing** (silent data loss if ignored).

Solution (tested, works): extract **under proot** with `--link2symlink`, which
rewrites hardlinks as symlinks into `.l2s.*` backing files. Requires:
- Guest tar + gzip available → bind host `$PREFIX/bin/tar` (and bash/gzip/gunzip/sh)
  into the guest at `$PREFIX`-mirrored paths, or use the rootfs's own tar after
  seeding a small toolset.
- `PROOT_TMP_DIR` set to a writable dir **outside** the rootfs (e.g. app
  `getCacheDir()`); otherwise proot's f2fs-bug probe/staging fails.
- Host tar is dynamically linked (`/system/bin/linker64`, libandroid-glob, libacl,
  libiconv) → simplest robust path is copying tar+gzip+bash+linker+libs into a
  staging dir inside the rootfs before extraction (tested recipe below).

## Verified proot launch recipe (on-device, Termux context)

```
env -i PATH=/usr/local/bin:/usr/bin:/bin \
    PROOT_TMP_DIR=<writable-dir> \
    HOME=/root \
  proot --kill-on-exit --link2symlink \
    --kernel-release='\Linux\localhost\6.6.89-android15\4k\aarch64\localdomain\-1\' \
    -0 --change-id=0:0 -L \
    -r <rootfs> \
    -b /dev -b /proc -b /sys \
    -b <rootfs>/tmp:/tmp \
    -w /root \
    /bin/bash ...
```

Notes from proot-distro source (`proot_cmd.py`, Termux 5.3+):
- `-L` fixes lstat symlink warnings for dpkg
- `--change-id=0:0` after `-0` (proot warns; last wins — keep only one)
- Fake `/proc` entries (loadavg, stat, version, overflowuid/gid, cap_last_cap)
  bind-mounted from pre-generated sysdata files improve tool compatibility
- `/dev/shm` → bind rootfs `tmp` (chmod 1777)

## On-device test matrix (executed in this session)

| Test | Result |
|---|---|
| Rootfs SHA256 matches official SHA256SUMS | ✅ PASS |
| tar extraction outside proot | ✅ reproducibly FAILS on 2 hardlinks (expected) |
| tar extraction under proot `--link2symlink` | ✅ PASS (135M, l2s symlinks created) |
| Boot Ubuntu 24.04.5 (`uname -a`, `id`, os-release) | ✅ PASS |
| DNS resolution (getent) | ✅ PASS (after writing resolv.conf) |
| `apt-get update` | ✅ PASS (37.3 MB fetched, ~20 s) |
| `apt-get install` (git/tmux/curl/ca-certificates) | ✅ PASS |
| Node.js 22.23.3 install w/ SHA256 verify | ✅ PASS (`node v22.23.3`, `npm 10.9.9`) |
| HTTPS to external APIs (GitHub, nodesource) | ✅ PASS |
| tmux session create/capture/kill | ✅ PASS |
| Workspace node script execution | ✅ PASS |
| **ysdragon proot v5.4.0 execution** | ❌ **KILLED BY SIGSYS** — do not ship |
| **Termux proot 5.1.107.96 packaged as jniLibs** | ✅ PASS (Termux-patched, Android seccomp compatible) |
| **Tavi app dev-build end-to-end** | ⏳ Pending EAS build + install |
