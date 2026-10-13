# Phase 1 Audit — Open Items

Audit findings from the Phase 1 review (PRoot integration, bootstrap, device binds, error handling)
that are not yet implemented or not yet verified. Items marked "Not verified" were never confirmed
either way in the working session and need a fresh check before work starts.

## Not yet applied

| ID | Area | Finding | Status |
|----|------|---------|--------|
| M1 | `src/lib/setup.ts` | SHA256 verification runs on the JS thread, blocking the UI on large files. Also verified twice (JS and native). | Not applied. Deliberately skipped in the last pass; needs a decision on moving hashing to native async. |
| M3 | Bootstrap logs | Bootstrap log persistence / `isBootstrapped` status surfacing in the UI. | Partially covered by existing bootstrap events. Remaining scope unclear. |
| M9 | `plugins/withStropBridge.js` | After the signing regex replace, nothing checks that the replacement happened. Should warn (not throw, so debug builds still work). CI should also fail early with a clear message when signing secrets are missing. | Not applied. Plugin file unchanged. |
| M13 | `ProotManager.kt` bootstrap script | `apt` steps lack `|| exit 1`; curl lacks `--retry 3`. | Not present in code (grep found no match). |
| M15 | `src/app/index.tsx` | Notification permission (`POST_NOTIFICATIONS`) is only a TODO. Needs a real runtime request on Android. | Not applied. |
| M16 | Not recorded | Listed in the audit summary but its description was not captured in the working session. | Not verified. Re-derive from the original audit. |
| L1 | `ProotManager.kt` | `buildProotCommand` indentation. | Not verified. |
| L2 | `ProotManager.kt` ~L380 | Redundant `unset PROOT_TMP_DIR PROOT_LOADER ...` in guest command. | Still present. |
| L5 | `StropBridgeModule.kt` | Unused events `onProotError`, `onSessionOutput`, `onSessionExited` still declared in `Events`. | Still present. |
| L7 | Not recorded | Listed in the audit summary but its description was not captured in the working session. | Not verified. Re-derive from the original audit. |
| M10 | Tar extraction | Tar symlink escape check. | Not verified. Not checked in the working session. |
| H3 | `PLAN.md` / checklist | Phase 1 checklist state was edited but content was never inspected. | Not verified. |

## Other deferred work

- **Naming cleanup:** applicationId `dev.tavi.app`, `TAVI_*` secret names in CI, `.tavi-*` marker files, `tavi-setup` cache directory. Product is Strop; code still says tavi.
- **5-session cap:** terminal sessions are per-socket (`/tmp/tmux-<name>.sock`); the 5-session limit is not enforced yet.
- **Phase 2:** CodeMirror and xterm.js WebViews (not started).

## Done in the last pass

- Device binds (`/dev/urandom`, `/dev/fd`, std streams, `/dev/shm`, `/sys/fs/selinux`).
- `-w /root` guest cwd; `includeDefaultShell` split; `ensureDirectories()` before bootstrap and exec.
- Bootstrap idempotency markers; rootfs version tracking.
- Session tmux socket fix (`/tmp/tmux-<name>.sock`), probe fix pending commit.
- Signing env var names aligned (`STROP_*`).
