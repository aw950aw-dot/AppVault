# App Vault — technical architecture

## 1. Product contract

App Vault provides two separate deliverables and never calls them the same thing:

1. **Install package bundle (`.avbackup`)** — a portable ZIP containing all APKs needed to install that package (base + splits), a manifest and integrity hashes.
2. **On-device data preservation** — a Package Manager operation executed through user-authorized Wireless ADB: `pm uninstall -k --user <currentUser> <packageName>`. This requests that Android keep the package's data/cache directories on that device.

The private data directory is not copied, read, encrypted, uploaded, or inserted into the ZIP. `shell` is not Root. A portable byte-for-byte private-data restore is explicitly out of scope for a no-Root third-party app.

## 2. Runtime modules

### `AppVaultApplication`

- Configures Kadb's persistent host identity using `KadbCert` + `OkioFilePrivateKeyStore`.
- Places the private ADB host key under `filesDir/adb/host_private_key.pem`.
- Android backup is disabled to avoid syncing that host identity via app backup.

### `KadbGateway`

Public methods only:

- `pair(host, pairingPort, sixDigitCode)`
- `testConnection(host, connectPort)`
- `currentUserId(host, connectPort)`
- `uninstallKeepingData(host, connectPort, packageName)`

The gateway validates host, port, PIN and package name. It does not expose an arbitrary shell-command method to UI code. The only destructive command is generated internally after validation. ADB uses a local TLS connection to the phone's Wireless Debugging daemon; the user explicitly authorizes the pairing.

### `InstalledApps`

- Uses `PackageManager` with `QUERY_ALL_PACKAGES` for sideloaded distribution.
- Lists non-system user apps by label, package name and version.
- Does not request storage-wide access.

### `BackupBundleManager`

- Reads `ApplicationInfo.sourceDir` plus `splitSourceDirs`.
- Writes a ZIP with `appvault-manifest.json`, `base.apk` and split APK entries.
- Records package/version/signature identity and per-file byte length + SHA-256.
- Extraction rejects path traversal, unexpected entry names, duplicate entries, excessive entry counts and overlarge uncompressed archives.
- Verifies APK hashes, base APK package name and signing-certificate fingerprint before installation.

### `ApkInstaller`

- Uses `PackageInstaller.Session` and writes the complete APK set into one install session.
- Android handles package signature validation and user confirmation.
- Requires the user to grant App Vault the Android "Install unknown apps" capability.
- Does not use ADB to push APK files or install them silently.

### `InstallStatusReceiver`

- Handles success/failure and launches the system confirmation intent when Android returns `STATUS_PENDING_USER_ACTION`.

## 3. State machine

```text
UNPAIRED
  └─ user opens Wireless Debugging pairing dialog and submits host/port/PIN
      └─ ADB_PAIRING → PAIRED
          └─ user enters current Wireless Debugging connection port
              └─ ADB_CONNECT_TEST → CONNECTED

CONNECTED + APP_SELECTED
  └─ EXPORTING_BUNDLE → BUNDLE_SAVED
      └─ user confirms preserve-and-uninstall
          └─ RUN_PM_UNINSTALL_KEEP_DATA
              ├─ Success → APP_REMOVED_DATA_RETAINED_ON_DEVICE
              └─ Failure → ERROR (app remains or status is unknown; verify before retry)

BUNDLE_SELECTED
  └─ EXTRACT_AND_VALIDATE
      └─ CHECK_UNKNOWN_APP_INSTALL_PERMISSION
          ├─ Missing → OPEN_ANDROID_SETTINGS → retry on return
          └─ Granted → CREATE_PACKAGE_INSTALLER_SESSION
              └─ SYSTEM_INSTALL_CONFIRMATION (when required)
                  ├─ Success → APP_INSTALLED
                  └─ Failure → ERROR
```

## 4. Package data preservation sequence

1. Export and save the APK bundle before uninstalling.
2. Confirm in App Vault that data will stay only on this phone.
3. Resolve active Android user via `am get-current-user`.
4. Validate package name against `[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+`.
5. Issue `pm uninstall -k --user <id> <package>` through Kadb.
6. Require shell exit code zero and `Success` in output. Otherwise report failure and do not claim success.
7. Reinstall the matching APK set through PackageInstaller. Android verifies package signature and decides whether the retained data can be reused.

No `pm clear`, `rm`, `restorecon`, direct `/data/user` access, Root command, `run-as`, or arbitrary user-supplied shell string is used.

## 5. Install/restore sequence

1. User selects `.avbackup` through Storage Access Framework.
2. Extract into a private temporary directory; never trust ZIP entry paths.
3. Validate manifest format, APK names, lengths, hashes, package name and signer fingerprint.
4. Check Android's `canRequestPackageInstalls()` status; if needed, open the exact system settings page.
5. Write base/split APKs into one `PackageInstaller.Session`.
6. Commit with an explicit mutable PendingIntent callback on Android 12+.
7. Handle `STATUS_PENDING_USER_ACTION`, success and error status.
8. Delete temporary extracted files after the installer session has copied them.

## 6. Error handling rules

- ADB unavailable, Wireless Debugging disabled, changed port, wrong PIN, pairing timeout or revoked authorization → guide user to reconnect/re-pair; never fake connected state.
- APK path unreadable → export fails and does not offer a successful preservation action.
- Bundle checksum/manifest/signature mismatch → reject before installation.
- PackageInstaller confirmation cancelled or install rejected → report actual system result.
- `pm uninstall -k` output not exactly successful → no success notification.

## 7. Explicit design limits

- Android user/profile-specific: current implementation acts on the active user only.
- No Root, no device-owner privileges, no system UID, no hidden API bypass.
- No private-data export, cross-device app-data restore, Keystore export, cloud data backup, or guaranteed app-process runtime-state snapshot.
- If preserving data is the sole goal and the app remains installed, prefer PackageInstaller in-place update; uninstall is unnecessary.
