import { useRef } from 'react';
import { Pressable, StyleSheet, Text, ToastAndroid, useColorScheme, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { EditorWebView, type EditorWebViewRef } from '@/components/web/EditorWebView';
import { Colors } from '@/constants/theme';

const SAMPLE = `// Welcome to Strop\nfunction hello() {\n  console.log("Hello, world!");\n}\nhello();\n`;

export default function HomeScreen() {
  const scheme = useColorScheme() === 'dark' ? 'dark' : 'light';
  const editorRef = useRef<EditorWebViewRef>(null);
  const colors = scheme === 'dark' ? Colors.dark : Colors.light;
  const insets = useSafeAreaInsets();

  const handleSave = () => {
    if (!editorRef.current) {
      ToastAndroid.show('Editor not ready', ToastAndroid.SHORT);
      return;
    }
    editorRef.current.save();
  };

  return (
    <View style={[styles.container, { backgroundColor: colors.background, paddingTop: insets.top }]}>
      <View style={styles.header}>
        <Text style={[styles.title, { color: colors.text }]}>test.js</Text>
        <Pressable
          onPress={handleSave}
          style={({ pressed }) => [
            styles.saveButton,
            { backgroundColor: pressed ? colors.textSecondary : colors.text },
          ]}
        >
          <Text style={[styles.saveText, { color: colors.background }]}>Save</Text>
        </Pressable>
      </View>
      <EditorWebView
        ref={editorRef}
        path="test.js"
        initialContent={SAMPLE}
        theme={scheme}
        onChange={(text) => {
          // content change handler
        }}
        onSave={(result) => {
          ToastAndroid.show(`Saved ${result.path} (${result.text.length} chars)`, ToastAndroid.SHORT);
        }}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 10,
  },
  title: {
    fontSize: 16,
    fontWeight: '600',
  },
  saveButton: {
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 6,
  },
  saveText: {
    fontWeight: '600',
    fontSize: 14,
  },
});
