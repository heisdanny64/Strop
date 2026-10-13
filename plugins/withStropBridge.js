/**
 * Expo config plugin that injects Strop's native bridge code into the
 * Android project during prebuild (CNG-safe — no manual android/ edits).
 */
const {
  withGradleProperties,
  withAndroidManifest,
  withAppBuildGradle,
  createRunOncePlugin,
} = require('expo/config-plugins');

const withStropGradleProperties = (config) =>
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

const withStropManifest = (config) =>
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
const withStropSigning = (config) =>
  withAppBuildGradle(config, (config) => {
    if (!config.modResults.contents.includes('STROP_SIGNING')) {
      const before = config.modResults.contents;
      config.modResults.contents = before.replace(
        /signingConfigs \{\s*debug \{\s*storeFile file\('debug\.keystore'\)\s*storePassword 'android'\s*keyAlias 'androiddebugkey'\s*keyPassword 'android'\s*\}\s*\}/,
        `// STROP_SIGNING: shared keystore across CI + local builds
        signingConfigs {
            debug {
                if (System.getenv('STROP_KEYSTORE_PATH') != null) {
                    storeFile file(System.getenv('STROP_KEYSTORE_PATH'))
                    storePassword System.getenv('STROP_KEYSTORE_PASSWORD')
                    keyAlias System.getenv('STROP_KEY_ALIAS')
                    keyPassword System.getenv('STROP_KEY_PASSWORD')

                } else {
                    storeFile file('debug.keystore')
                    storePassword 'android'
                    keyAlias 'androiddebugkey'
                    keyPassword 'android'
                }
            }
        }`
      );
      if (config.modResults.contents === before) {
        throw new Error(
          'withStropBridge: could not find the stock debug signingConfigs block in app/build.gradle; ' +
            'signing override was not applied. Update the regex in plugins/withStropBridge.js.'
        );
      }
    }
    return config;
  });

module.exports = createRunOncePlugin(
  (config) => withStropSigning(withStropManifest(withStropGradleProperties(config))),
  'withStropBridge',
  '1.0.0'
);
