# App Vault for Android

App Vault is an Android 11+ app that exports an installed application's APK set and can ask Android's own ADB shell to **uninstall while keeping the app's data/cache directories on the same device**. The ADB client is embedded in this APK; no Root, Shizuku app, computer, or second helper app is required.

> **Critical limitation — this is not a portable full-data backup.** Android's application sandbox prevents an ordinary third-party app (including one running as ADB `shell`) from reading every other app's private `/data/user/...` files. The `.avbackup` archive contains the base APK, split APKs, package/version/signature metadata and hashes. Private data remains in Android's retained app-data area on the original phone after App Vault runs `pm uninstall -k`. It is not inside the archive and cannot be restored to a different phone by this app.

## What it can do

- List user-installed packages visible to the app.
- Export the installed base APK and all split APKs to a `.avbackup` ZIP archive.
- Record package name, version, signer fingerprint, split names, sizes and SHA-256 hashes.
- Pair an embedded ADB client to Android Wireless Debugging using the six-digit pairing code.
- Execute only a small allowlist of package-preservation commands; there is no arbitrary shell terminal.
- Run `pm uninstall -k --user <current-user> <package>` so Android keeps the package's data/cache directories on this phone.
- Reinstall the exported APK set using Android's PackageInstaller UI. Android still validates the APK signature and may ask the user to confirm installation.
- Install a newer same-signer APK set over the existing package through PackageInstaller without first uninstalling it.

## Requirements

- Android 11 (API 30) or newer. Tested target device is expected to be Android 13 or newer.
- Developer options and Wireless debugging enabled for the ADB preservation/uninstall feature.
- Same Wi-Fi network for the app and the device's local ADB endpoint.
- A one-time pairing approval by the phone owner. This is an Android security requirement, not an App Vault setting.
- App Vault must be allowed to install unknown apps to restore an exported APK archive.

## First-time ADB pairing

1. Open Android Settings → About phone and tap Build number until Developer options are enabled.
2. Open Developer options → Wireless debugging and enable it.
3. Open **Pair device with pairing code**. Keep that dialog visible; the pairing code and its port are temporary. Split-screen can help keep Settings and App Vault open together.
4. In App Vault, tap **Pair ADB** and enter the device IP address, the temporary pairing port, and the six-digit code.
5. After pairing, return to the main Wireless debugging screen. Enter its current **connection port** (not the temporary pairing port) in App Vault → **Connect**.
6. App Vault saves its ADB host identity in its private internal storage. Pairing usually does not need to be repeated unless App Vault data is cleared/reinstalled, the phone revokes ADB authorizations, or the host key is lost.

The port shown by Wireless debugging can change after toggling the feature or reconnecting to a network; update it in App Vault when required. This starter implementation uses manual IP/port entry for predictable behavior and does not depend on mDNS discovery.

## Recommended workflow

### To preserve one app before removing it

1. Connect App Vault to Wireless debugging.
2. Find the target app and tap **Export APK bundle**.
3. Save the `.avbackup` file to Documents, Downloads, or an SD card.
4. After export succeeds, confirm **Preserve & uninstall**.
5. App Vault asks Android to run `pm uninstall -k` for the active Android user. Do not use the normal Settings uninstall flow for this operation.
6. To return the app, select **Restore a .avbackup file**. Allow App Vault to install apps if Android asks. Complete Android's installer confirmation.

### To update an app while retaining its data

Prefer a normal in-place update: restore/install a newer APK set with the same package name and compatible signing certificate while the original app is still installed. Android's package installer updates the app in place and retains its app data. An app's own database migration can still change the data format or behavior.

## What cannot be promised

- No generic app can export every other app's private database, preferences, credential files, Android Keystore keys, runtime state, or server-side data without cooperation from that app, elevated device privilege, or a system backup path that the app permits.
- ADB `shell` is not Root. It does not grant unrestricted access to `/data/user/0/<package>`.
- `pm uninstall -k` retains data/cache on the original phone; it does not package the data into `.avbackup`.
- Retained data may be removed by clearing storage, factory reset, storage cleanup, changing Android user/profile, system-specific behavior, or another uninstall/clear-data operation.
- Reinstalling an APK signed by a different key is normally rejected as an update; do not expect retained data to attach to a differently signed package.
- Updating to a new application version may migrate or invalidate its own database. App Vault cannot control that migration.
- The ADB connection and package installer both require explicit system/user authorization.

## Build with GitHub Actions

The repository includes `.github/workflows/build-release-apk.yml` with the workflow name **build release APK**. It runs tests and builds:

- `app-debug.apk` — installable test APK (signed with the standard debug key generated by the runner).
- `app-release-unsigned.apk` — unsigned release output. A stable private signing key must be configured before distributing release updates.

The workflow is designed to run in GitHub Actions with JDK 17, Gradle 8.13, Android SDK Platform 36 and Build Tools 36.0.0. Open the Actions tab → **build release APK** → Run workflow, then download the `AppVault-APKs` artifact.

## Project structure

```text
AppVault/
├── .github/workflows/build-release-apk.yml
├── app/
│   ├── src/main/AndroidManifest.xml
│   ├── src/main/java/com/appvault/android/
│   │   ├── AppVaultApplication.kt
│   │   ├── MainActivity.kt
│   │   ├── adb/KadbGateway.kt
│   │   ├── apps/InstalledApp.kt
│   │   └── backup/
│   │       ├── ApkInstaller.kt
│   │       ├── BackupBundleManager.kt
│   │       └── InstallStatusReceiver.kt
│   └── src/test/java/com/appvault/android/
├── docs/
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## Dependencies and licenses

- [Kadb](https://github.com/flyfishxu/Kadb) 2.1.5 — Apache-2.0; embedded Kotlin ADB client, Wireless Debugging pairing and shell.
- Kotlin Coroutines Android 1.10.2 — Apache-2.0.
- Android SDK / AndroidX-free platform UI — Android platform APIs.

App Vault source is MIT-licensed. Third-party dependency licenses remain applicable.
