# Strop — Implementation Plan

**Product:** Mobile-first development environment combining a full IDE, integrated Linux terminal, built-in preview, and configurable AI coding agent in a single native-feel Android app.

---

## 1. Context & Architecture

### High-Level Architecture
- **Framework:** Expo SDK with custom Android development builds (`expo-dev-client`, custom native modules).
- **Frontend / Shell:** React Native (Expo Router) for all app navigation, headers, tabs, settings, and modal controls.
- **Editor & Terminal (Local WebViews):** Clean, chrome-less local WebViews loading local pre-bundled assets:
  - **Editor:** CodeMirror 6 running locally inside an offline WebView, communicating via a two-way postMessage / JSI bridge.
  - **Terminal:** xterm.js running locally in a WebView, bridged over WebSockets / local UNIX sockets to a local shell daemon.
- **Linux Execution Environment:** PRoot running Ubuntu 24.04 (arm64/aarch64) in Android app-private storage, supported by an Android Foreground Service.
- **Session Management:** `tmux` runs inside PRoot to maintain persistent shell sessions across view transitions.
- **AI Agent Runtime:** `@anthropic-ai/claude-agent-sdk` executing inside PRoot's Node.js environment, using bundled Linux arm64 binaries or custom tool-bridge fallback. Operates directly on `/workspace`.
- **Project Storage:** Local PRoot filesystem at `/workspace/<project-slug>`.
- **Preview:** Built-in WebView locked strictly to `localhost:<port>`, allowing external fonts, images, and APIs while preventing general web browsing.

---

## 2. Technical Stack & Dependencies

### React Native / Expo
- `expo`: Latest SDK
- `expo-router`: File-based routing
- `react-native-webview`: Chrome-free local rendering for CodeMirror, xterm.js, and web preview
- `react-native-keychain` / `expo-secure-store`: Encrypted storage for API keys and GitHub OAuth tokens
- `expo-file-system`: Handling asset bundles, downloads, and cache extraction
- Custom Android Native Module (`StropBridge`): Managing PRoot background process, Foreground Service notification, and socket bridging

### PRoot & Linux Environment
- `proot`: Emulated chroot execution on unrooted Android
- Base rootfs: Ubuntu 24.04 LTS (arm64 rootfs tarball)
- Core Pack (pre-bundled/extracted on first launch):
  - Node.js (LTS), npm, git, curl, tar, gzip, tmux, coreutils, bash
- Onboarding Toolchains (downloaded on demand):
  - Python (`python3`, `pip`, `venv`)
  - C/C++ (`build-essential`, `gcc`, `g++`, `make`, `gdb`)
  - PHP (`php-cli`, `composer`)
  - Android Tools (`openjdk-17-jdk`, commandline-tools)

### Web Assets (Bundled inside app)
- `assets/web/editor/`: CodeMirror 6 bundle + language extensions + theme synchronization
- `assets/web/terminal/`: xterm.js + fit-addon + web-links + canvas renderer
- `assets/web/preview/`: Fallback error/loading pages for localhost preview

---

## 3. Phased Implementation Roadmap

### Phase 1: Native Foundation & PRoot Integration
- [x] Configure custom Expo dev client with Android native permissions:
  - `FOREGROUND_SERVICE`, `INTERNET`, `POST_NOTIFICATIONS`, `READ/WRITE_EXTERNAL_STORAGE` (scoped). *(Note: Scoped storage makes explicit READ/WRITE permissions unnecessary.)*
- [x] Implement `StropForegroundService` in Android to keep PRoot alive when navigating or backgrounded.
- [x] Integrate PRoot binary and initial rootfs extraction scripts to app-internal storage (`context.getFilesDir()`).
- [x] Setup socket communication between React Native and the PRoot bash environment using per‑session tmux sockets and execCommand handling.
- [x] Test command execution and verify `tmux` session persistence.

### Phase 2: WebViews & Editor/Terminal Infrastructure
- [ ] Create pre-bundled, offline CodeMirror 6 build:
  - File buffer management, syntax highlighting, bracket matching, autocomplete.
  - Bridge methods: `openFile(path, content)`, `saveFile()`, `onContentChange()`, `setTheme()`.
- [ ] Build the mobile **Coding Key Strip**:
  - Horizontal scrollable accessory bar: `Tab`, `Ctrl`, `Esc`, `{`, `}`, `[`, `]`, `(`, `)`, `;`, `"`, `'`, `/`, `\`, `|`, `~`, arrow keys.
  - Keyboard focus and insertion event dispatch to CodeMirror.
- [ ] Create pre-bundled, offline xterm.js build:
  - Two‑way WebSocket/bridge connection to PRoot's `tmux` instance.
  - Support project-specific terminal sessions and the Global Terminal session.
- [ ] Implement **Preview WebView**:
  - URL enforcement restricted to `http://localhost:*` and `http://127.0.0.1:*`.
  - External resource loading enabled (CORS/asset fetching).

### Phase 3: Onboarding Flow
- [ ] **Welcome Screen:** Minimal, visually appealing start screen with "Get Started" CTA.
- [ ] **Toolchain Selection:**
  - Mandatory Core Pack (Node.js, Git, curl, tmux) disabled and checked.
  - Checkable options: Python, C/C++, PHP, Android Tools.
- [ ] **AI Provider Selection & Configuration:**
  - Providers: Anthropic, OpenAI, DeepSeek, OpenRouter, Custom.
  - API Key & Model inputs; "Find Models" drawer and manual entry.
  - "Skip for now" link leading directly to installation.
- [ ] **Resource Installation Screen:**
  - Extraction of pre‑bundled rootfs and Core Pack.
  - Asynchronous download and installation of selected optional toolchains.
  - Step‑by‑step checklist and visual progress bar.
- [ ] Flag persistence (`onboarding_completed: true`) to bypass onboarding on subsequent launches.

### Phase 4: Navigation Hub & Project Management
- [ ] **Home Screen:**
  - Header with Settings icon.
  - Destination tiles: Projects, Global Terminal, AI Agent Chat.
  - Recent Projects list (top 2‑3 most recently edited).
- [ ] **Projects Screen:**
  - Searchable list of projects located in `/workspace/`.
  - Project Cards displaying name, description, path, and 3‑dot menu (Rename, Export, Delete).
  - Floating Action Button with expandable menu:
    - Create New (modal: name, description, auto-generated editable slug).
    - Import from GitHub (OAuth / Public repo URL clone).
    - Import from Device (tar/zip archive picker and extract).
    - Ask Strop (AI‑guided project scaffolding).

### Phase 5: Workspace IDE
- [ ] **Workspace Layout:**
  - Header: Back button (to Projects), Title, Action icons (Agent, Terminal, Preview).
  - File Explorer drawer (bottom‑left floating trigger or key strip integration).
  - Multi‑tab file manager for CodeMirror editor.
- [ ] **Floating / Minimizable Agent Overlay:**
  - Top‑right collapsible panel.
  - State controls: Minimize (persists task), Maximize (full screen), Close (terminates session).
- [ ] **Full‑Screen Terminal View:**
  - Attached to the active project's `tmux` session (`/workspace/<slug>`).
- [ ] **Localhost Preview Drawer/Modal:**
  - Configurable port input (e.g. 3000, 5173, 8080) with reload and back/forward navigation controls.

### Phase 6: AI Agent Integration
- [ ] Run Node.js agent daemon inside PRoot leveraging `@anthropic-ai/claude-agent-sdk`.
- [ ] Setup IPC bridge between React Native Chat UI and PRoot agent daemon.
- [ ] Implement Agent Modes:
  - **Discuss:** Pure chat, reasoning, and advice (no filesystem or command tools executed).
  - **Build:** Tool execution enabled (read file, edit file, run terminal command, search workspace).
- [ ] Conversation history persistence (SQLite or JSON files per project/global session).
- [ ] Skills and MCP configuration support in chat input bar.

### Phase 7: Settings & Customization
- [ ] **AI Providers & Models:** Manage credentials, switch active provider, configure custom endpoints.
- [ ] **Toolchains:** View installed toolchains, install additional ones, or free up disk space.
- [ ] **Editor Preferences:** Font size, tab size, line wrapping, theme (Dark/Light/OLED).
- [ ] **Terminal Preferences:** Font family, font size, default shell, scrollback buffer size.
- [ ] **Skills & MCP Servers:** Add custom tool endpoints and agent prompt personalizations.

---

## 4. Verification & Testing Strategy
1. **PRoot Cold Boot & Resilience:** Test initial unpack on clean install, verify foreground service maintains processes across app pauses.
2. **Editor Responsiveness:** Benchmark CodeMirror 6 over WebView postMessage bridge with files up to 5,000 lines; verify keyboard accessory key dispatch.
3. **Terminal PTY & tmux:** Confirm ANSI color support, resize events (`fit-addon`), and session reconnection after navigating between tabs.
4. **Agent File & Tool Actions:** Verify agent edits apply cleanly to `/workspace` files and reflect immediately in open editor buffers.
5. **Localhost Preview:** Spin up a Vite/Node.js web server inside PRoot and verify instant rendering in the Preview WebView.

---
