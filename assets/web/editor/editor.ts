import { EditorView, basicSetup } from "codemirror";
import { javascript } from "@codemirror/lang-javascript";
import { StreamLanguage } from "@codemirror/language";
import { shell } from "@codemirror/legacy-modes/mode/shell";
import { autocompletion } from "@codemirror/autocomplete";
import { lintGutter } from "@codemirror/lint";
import { oneDark } from "@codemirror/theme-one-dark";
import { EditorState, Compartment } from "@codemirror/state";
import { keymap } from "@codemirror/view";
import { defaultKeymap } from "@codemirror/commands";

const bash = StreamLanguage.define(shell);

const oneLight = EditorView.theme({
  "&": { backgroundColor: "#ffffff", color: "#383a42" },
  ".cm-content": { caretColor: "#526fff" },
  ".cm-cursor": { borderLeftColor: "#526fff" },
  "&.cm-focused .cm-selectionBackground, .cm-selectionBackground": { backgroundColor: "#add6ff" },
  ".cm-activeLine": { backgroundColor: "#f0f0f0" },
  ".cm-gutters": { backgroundColor: "#f0f0f0", color: "#a0a1a7", borderRight: "1px solid #e5e5e6" },
  ".cm-activeLineGutter": { backgroundColor: "#e0e0e0" },
});

const themeCompartment = new Compartment();
const languageCompartment = new Compartment();

function detectLanguage(path: string) {
  const ext = path.split(".").pop()?.toLowerCase() ?? "";
  if (["sh", "bash", "zsh", "fish"].includes(ext) || path.endsWith(".bashrc") || path.endsWith(".profile")) return bash;
  return javascript();
}

function boot() {
  const target = document.getElementById("cm-editor");
  if (!target) throw new Error("missing #cm-editor element");

  let currentPath = "untitled.js";
  let isSaving = false;
  const onChanges: Array<(text: string) => void> = [];

  const doc = (): string => state.doc.toString();

  const state = EditorState.create({
    doc: "",
    extensions: [
      basicSetup,
      languageCompartment.of(javascript()),
      themeCompartment.of(oneDark),
      autocompletion(),
      lintGutter(),
      keymap.of(defaultKeymap),
      EditorView.updateListener.of((update) => {
        if (update.docChanged) {
          const text = update.state.doc.toString();
          for (const cb of onChanges) cb(text);
        }
      }),
      EditorView.updateListener.of((update) => {
        if (update.viewportChanged) {
          window.parent.postMessage(
            { type: "viewport", top: update.view.scrollDOM.scrollTop },
            "*"
          );
        }
      }),
    ],
  });

  const view = new EditorView({
    state,
    parent: target,
  });

  (window as any).StropEditorBridge = {
    openFile(path: string, content: string): void {
      currentPath = path;
      view.dispatch({
        changes: { from: 0, to: view.state.doc.length, insert: content },
        effects: languageCompartment.reconfigure(detectLanguage(path)),
      });
      window.parent.postMessage(
        { type: "openFile", path: currentPath, text: content },
        "*"
      );
    },
    saveFile(): Promise<{ path: string; text: string }> {
      if (isSaving) return Promise.reject(new Error("already saving"));
      isSaving = true;
      return Promise.resolve({ path: currentPath, text: doc() }).finally(() => {
        isSaving = false;
      });
    },
    onContentChange(cb: (text: string) => void): void {
      onChanges.push(cb);
      cb(doc());
    },
    setTheme(name: "dark" | "light"): void {
      view.dispatch({
        effects: themeCompartment.reconfigure(name === "dark" ? oneDark : oneLight),
      });
    },
  };
}

document.addEventListener("DOMContentLoaded", boot);
