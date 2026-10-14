import { useColorScheme } from 'react-native';

import { EditorWebView } from '@/components/web/EditorWebView';

const SAMPLE = `// Welcome to Strop\nfunction hello() {\n  console.log("Hello, world!");\n}\nhello();\n`;

export default function HomeScreen() {
  const scheme = useColorScheme() === 'dark' ? 'dark' : 'light';

  return (
    <EditorWebView
      path="test.js"
      initialContent={SAMPLE}
      theme={scheme}
      onChange={(text) => {
        // content change handler — wire up in Phase 2 step 2
      }}
      onSave={(result) => {
        // save handler — wire up in Phase 2 step 2
      }}
    />
  );
}
