# Security model

## Permissions requested

- `INTERNET`: local TCP/TLS transport to the phone's own Wireless Debugging ADB endpoint.
- `QUERY_ALL_PACKAGES`: show installed apps in a sideloaded backup utility.
- `REQUEST_INSTALL_PACKAGES`: start Android's own installer session after the user grants the corresponding setting.

No contacts, SMS, call log, accessibility service, notification listener, all-files storage access, location or overlay permission is requested.

## ADB trust boundary

Wireless Debugging grants the paired host ADB `shell` capabilities. This is a powerful authorization. App Vault therefore:

- shows the ADB pairing/connect procedure and requires the user to enter the one-time PIN;
- stores only its private ADB host key in the app-private internal directory;
- never logs or exports the private key or pairing PIN;
- never exposes a generic shell terminal;
- constructs commands only from validated package names and the Android current user ID;
- runs only `echo APPVAULT_CONNECTED`, `am get-current-user`, and `pm uninstall -k --user ...`.

Users can revoke the trust from Developer options → Wireless debugging → Paired devices, or revoke all ADB authorizations.

## Archive safety

- ZIP entries are restricted to a manifest and APK filenames; path separators and directory entries are rejected.
- Duplicate entries, unlisted APKs, missing base APK, large archives, hash mismatches, package mismatch and signer mismatch are rejected.
- Archive files are written to App Vault's private temporary storage before validation.
- APK installation is performed by PackageInstaller; Android verifies signing identity and asks for user approval when required.

## Data and privacy

- App Vault does not upload packages or data to a server.
- `.avbackup` contains the application APK set and metadata only, not the app's private data.
- `allowBackup=false` keeps the ADB host identity out of App Vault's Android backup.
- Temporary bundle files are deleted after export/install when possible.
