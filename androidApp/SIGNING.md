# Signing the Android release

An Android app can only be updated by a build signed with the same key, so make the key once, keep it safe (a copy offline, not in the repository) and never lose it.

## Make the key

```
keytool -genkeypair -v -keystore shoparchive-release.jks -alias shoparchive -keyalg RSA -keysize 4096 -validity 10000
```

`keytool` comes with the JDK. It asks for a password (the store password) and for the key's password (may be the same).

## Use it

Create `keystore.properties` in the repository root. It is in `.gitignore`, as are `*.jks` and `*.keystore`: never commit either.

```
storeFile=/absolute/or/repo-relative/path/shoparchive-release.jks
storePassword=...
keyAlias=shoparchive
keyPassword=...
```

CI reads the same four values from the environment instead: `SHOPARCHIVE_KEYSTORE_FILE`, `SHOPARCHIVE_KEYSTORE_PASSWORD`, `SHOPARCHIVE_KEY_ALIAS`, `SHOPARCHIVE_KEY_PASSWORD` (the release workflow decodes the key from the secret `ANDROID_KEYSTORE_BASE64`).

```
./gradlew :androidApp:releaseApk :androidApp:bundleRelease
```

- APK: `androidApp/build/release/ShopArchive-<version>-<build>.apk`, already named for the server's `downloads/`: copy it there unchanged
- AAB: `androidApp/build/outputs/bundle/release/androidApp-release.aab`

Without a key the build still succeeds but prints a warning and the files are unsigned (`androidApp-release-unsigned.apk`); Android refuses to install those. The debug key is never used for a release.

R8/minify is off in v1 (it would need keep rules for kotlinx.serialization, Ktor and OkHttp, and a device test).
