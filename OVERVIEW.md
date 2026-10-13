
## Architecture & Code Structure

### Overview
Tavi is a mobile-first development environment combining an IDE, integrated terminal, built-in preview, and AI agent built with Expo and React Native.

- **Routing & Screens (`src/app/`)**: File-based routing using Expo Router. All screens live here; `_layout.tsx` files define navigators. Do not place business logic or helper components here.
- **Components (`src/components/`)**: Shared UI components and platform-specific implementations (`.web.tsx` for web overrides).
- **Hooks & Theming (`src/hooks/`, `src/constants/theme.ts`)**: Custom hooks for color schemes, theme colors, and responsive device adaptations.
- **Config & CNG (`app.json`)**: Continuous Native Generation (CNG) is used. Do not manually create or edit native directories (`android/` or `ios/`); configure native features via `app.json` and Expo config plugins. Custom Android dev builds are used instead of Expo Go.
- **Core Architecture Plan (`PLAN.md`)**: Defines the target architecture for Jasper:
  - Local chrome-free WebViews for CodeMirror 6 (editor) and xterm.js (terminal).
  - Background PRoot Linux (Ubuntu 24.04 aarch64) execution environment managed by an Android Foreground Service.
  - Persistent terminal sessions managed via `tmux`.
  - `@anthropic-ai/claude-agent-sdk` executing inside PRoot's Node.js runtime operating on `/workspace`.

### Guidelines
- When adding packages with native code, always use `npx expo install <package>` and verify compatibility with Expo SDK 57 / React 19.
- Use path aliases configured in `tsconfig.json` (e.g., `@/components/...`).
- Keep components responsive and mobile-first, supporting dark and light themes via `useTheme` and `useColorScheme`.
