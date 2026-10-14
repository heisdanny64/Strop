import React, { forwardRef, useCallback, useEffect, useImperativeHandle, useMemo, useRef, useState } from "react";
import { StyleSheet, View } from "react-native";
import { WebView, type WebViewMessageEvent } from "react-native-webview";

import { Colors } from "@/constants/theme";
import { EDITOR_HTML } from "@/generated/editorHtml";

const EDITOR_SOURCE = { html: EDITOR_HTML };

export type EditorWebViewRef = {
  save(): void;
};

export const EditorWebView = forwardRef(function EditorWebView(
  {
    path = "untitled.js",
    initialContent = "",
    onChange,
    onSave,
    theme = "dark",
  }: {
    path?: string;
    initialContent?: string;
    onChange?: (text: string) => void;
    onSave?: (result: { path: string; text: string }) => void;
    theme?: "dark" | "light";
  },
  ref: React.Ref<EditorWebViewRef>
) {
  const changeCbRef = useRef(onChange);
  const saveCbRef = useRef(onSave);
  const webViewRef = useRef<WebView>(null);
  const [loadKey, setLoadKey] = useState(0);
  const [ready, setReady] = useState(false);
  const latest = useRef({ path, initialContent, theme });

  useEffect(() => {
    latest.current = { path, initialContent, theme };
  }, [path, initialContent, theme]);

  useEffect(() => {
    changeCbRef.current = onChange;
  }, [onChange]);

  useEffect(() => {
    saveCbRef.current = onSave;
  }, [onSave]);

  useImperativeHandle(ref, () => ({
    save() {
      webViewRef.current?.injectJavaScript(
        `(function(){var b=window.StropEditorBridge;if(b)b.saveFile();})();true;`
      );
    },
  }));

  const syncEditor = useCallback((fields: "all" | "theme") => {
    const { path: p, initialContent: c, theme: t } = latest.current;
    const body =
      fields === "all"
        ? `bridge.openFile(${JSON.stringify(p)}, ${JSON.stringify(c)}); bridge.setTheme(${JSON.stringify(t)});`
        : `bridge.setTheme(${JSON.stringify(t)});`;
    webViewRef.current?.injectJavaScript(
      `(function () { var bridge = window.StropEditorBridge; if (bridge) { ${body} } })(); true;`
    );
  }, []);

  const handleLoadEnd = useCallback(() => {
    setReady(true);
    syncEditor("all");
  }, [syncEditor]);

  const handleRenderProcessGone = useCallback(() => {
    setReady(false);
    setLoadKey((k) => k + 1);
  }, []);

  const handleMessage = useCallback((event: WebViewMessageEvent) => {
    try {
      const msg = JSON.parse(event.nativeEvent.data);
      if (msg.type === "contentChange") {
        changeCbRef.current?.(msg.text);
      } else if (msg.type === "saveFile") {
        saveCbRef.current?.({ path: msg.path, text: msg.text });
      }
    } catch {}
  }, []);

  useEffect(() => {
    if (ready) syncEditor("all");
  }, [path, initialContent, ready, syncEditor]);

  useEffect(() => {
    if (ready) syncEditor("theme");
  }, [theme, ready, syncEditor]);

  const containerStyle = useMemo(
    () => [styles.webview, { backgroundColor: theme === "dark" ? Colors.dark.background : Colors.light.background }],
    [theme]
  );

  return (
    <View style={styles.container}>
      <WebView
        key={loadKey}
        ref={webViewRef}
        source={EDITOR_SOURCE}
        onMessage={handleMessage}
        onLoadEnd={handleLoadEnd}
        onRenderProcessGone={handleRenderProcessGone}
        onContentProcessDidTerminate={handleRenderProcessGone}
        style={containerStyle}
        sharedCookiesEnabled={false}
        javaScriptEnabled
        domStorageEnabled
      />
    </View>
  );
});

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  webview: {
    flex: 1,
  },
});
