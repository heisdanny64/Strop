/**
 * Expo config plugin that injects Tavi's native bridge code into the
 * Android project during prebuild (CNG-safe — no manual android/ edits).
 */
const { withGradleProperties, withAndroidManifest, createRunOncePlugin } = require('expo/config-plugins');

const withTaviGradleProperties = (config) =>
  withGradleProperties(config, (config) => {
    config.modResults = config.modResults.filter(
      (item) => item.key !== 'android.useAndroidX' && item.key !== 'android.enableJetifier'
    );
    config.modResults.push({ type: 'property', key: 'android.useAndroidX', value: 'true' });
    config.modResults.push({ type: 'property', key: 'android.enableJetifier', value: 'true' });
    return config;
  });

const withTaviManifest = (config) =>
  withAndroidManifest(config, (config) => {
    const application = config.modResults.manifest.application[0];
    application.$['android:allowBackup'] = 'false';
    application.$['android:extractNativeLibs'] = 'true';
    return config;
  });

module.exports = createRunOncePlugin(
  (config) => withTaviManifest(withTaviGradleProperties(config)),
  'withTaviBridge',
  '1.0.0'
);
