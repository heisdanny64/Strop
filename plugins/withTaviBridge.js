/**
 * Expo config plugin that injects Tavi's native bridge code into the
 * Android project during prebuild (CNG-safe — no manual android/ edits).
 */
const {
  withGradleProperties,
  withAndroidManifest,
  withAppBuildGradle,
  createRunOncePlugin,
} = require('expo/config-plugins');

const withTaviGradleProperties = (config) =>
  withGradleProperties(config, (config) => {
    config.modResults = config.modResults.filter(
      (item) => item.key !== 'android.useAndroidX' && item.key !== 'android.enableJetifier'
    );
    config.modResults.push({ type: 'property', key: 'android.useAndroidX', value: 'true' });
    // Jetifier is legacy (pre-AndroidX library support) and OOMs CI runners on
    // large AAR transforms; RN 0.86 doesn't need it.
    config.modResults.push({ type: 'property', key: 'android.enableJetifier', value: 'false' });
    config.modResults.push({
      type: 'property',
      key: 'org.gradle.jvmargs',
      value: '-Xmx4096m -XX:MaxMetaspaceSize=1024m',
    });
    return config;
  });

const withTaviManifest = (config) =>
  withAndroidManifest(config, (config) => {
    const application = config.modResults.manifest.application[0];
    application.$['android:allowBackup'] = 'false';
    application.$['android:extractNativeLibs'] = 'true';
    return config;
  });

/**
 * Fixed signing config so CI and local builds share one signature (install-over
 * works without uninstalling). Replaces the body of the stock `debug`
 * signingConfig (referenced by both debug and release buildTypes) with
 * env-var-driven values when CI secrets are present, falling back to the
 * stock debug keystore for local development.
 */
const withTaviSigning = (config) =>
  withAppBuildGradle(config, (config) => {
    if (!config.modResults.contents.includes('TAVI_SIGNING')) {
      config.modResults.contents = config.modResults.contents.replace(
        /signingConfigs \{\s*debug \{\s*storeFile file\('debug\.keystore'\)\s*storePassword 'android'\s*keyAlias 'androiddebugkey'\s*keyPassword 'android'\s*\}\s*\}/,
        `// TAVI_SIGNING: shared keystore across CI + local builds
        signingConfigs {
            debug {
                if (System.getenv('TAVI_KEYSTORE_PATH') != null) {
                    storeFile file(System.getenv('TAVI_KEYSTORE_PATH'))
                    storePassword System.getenv('TAVI_KEYSTORE_PASSWORD')
                    keyAlias System.getenv('TAVI_KEY_ALIAS')
                    keyPassword System.getenv('TAVI_KEY_PASSWORD')
                } else {
                    storeFile file('debug.keystore')
                    storePassword 'android'
                    keyAlias 'androiddebugkey'
                    keyPassword 'android'
                }
            }
        }`
      );
    }
    return config;
  });

module.exports = createRunOncePlugin(
  (config) => withTaviSigning(withTaviManifest(withTaviGradleProperties(config))),
  'withTaviBridge',
  '1.0.0'
);
