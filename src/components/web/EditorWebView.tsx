import { Asset } from "expo-asset";
import React, { useCallback, useEffect, useRef } from "react";
import { StyleSheet, View } from "react-native";
import { WebView, type WebViewMessageEvent } from "react-native-webview";

import { Colors } from "@/constants/theme";

type EditorBridge = {
  openFile(path: string, content: string): void;
  saveFile(): Promise<{ path: string; text: string }>;
  onContentChange(cb: (text: string) => void): void;
  setTheme(name: "dark" | "light"): void;
};

export function EditorWebView({
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
}) {
  const bridgeRef = useRef<EditorBridge | null>(null);
  const changeCbRef = useRef(onChange);
  const saveCbRef = useRef(onSave);
  const webViewRef = useRef<WebView>(null);

  useEffect(() => {
    changeCbRef.current = onChange;
  }, [onChange]);

  useEffect(() => {
    saveCbRef.current = onSave;
  }, [onSave]);

  const editorUrl = Asset.fromModule(
    require("../../../assets/web/editor/index.html")
  ).uri;

  const handleMessage = useCallback(
    (_event: WebViewMessageEvent) => {
      // Bridge messages handled via onLoadEnd polling
    },
    []
  );

  const handleLoadEnd = useCallback(() => {
    const checkBridge = setInterval(() => {
      if (bridgeRef.current) {
        clearInterval(checkBridge);
        bridgeRef.current.openFile(path, initialContent);
        bridgeRef.current.setTheme(theme);
      }
    }, 50);
    setTimeout(() => clearInterval(checkBridge), 5000);
  }, [path, initialContent, theme]);

  return (
    <View style={styles.container}>
      <WebView
        ref={webViewRef}
        source={{ uri: editorUrl }}
        onMessage={handleMessage}
        onLoadEnd={handleLoadEnd}
        style={[styles.webview, { backgroundColor: theme === "dark" ? Colors.dark.background : Colors.light.background }]}
        sharedCookiesEnabled={false}
        javaScriptEnabled
        domStorageEnabled
        allowFileAccess
        allowUniversalAccessFromFileURLs
        injectedJavaScript={`
          (function() {
            var check = setInterval(function() {
              if (window.StropEditorBridge) {
                clearInterval(check);
                window.ReactNativeWebView.postMessage(JSON.stringify({ type: 'bridgeReady' }));
              }
            }, 50);
          })();
          true;
        `}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  webview: {
    flex: 1,
  },
});
