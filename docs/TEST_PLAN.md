# Test plan

## Build/CI

- [ ] `testDebugUnitTest` passes.
- [ ] `assembleDebug` produces an installable debug APK.
- [ ] `assembleRelease` produces an unsigned release APK.
- [ ] No signing credentials or API keys are committed.

## Device matrix

Test on physical devices, at minimum:

- [ ] Android 11 (API 30)
- [ ] Android 12/12L (API 31/32)
- [ ] Android 13 (API 33)
- [ ] Android 14 (API 34)
- [ ] Android 15 (API 35)
- [ ] Android 16 (API 36)

OEM coverage should include Pixel/AOSP and at least one Samsung/One UI device. Wireless Debugging UI varies by manufacturer.

## ADB pairing/connection

- [ ] Pair with correct six-digit code and pairing port.
- [ ] Reject wrong/expired code.
- [ ] Reject invalid port and malformed host.
- [ ] Connect using the connection port (not pairing port).
- [ ] Reconnect after force-stop/reopen using the persisted host private key.
- [ ] Detect disabled Wireless Debugging, changed port and revoked authorization.
- [ ] Confirm no Root and no Shizuku are installed/required.

## Export bundle

- [ ] Export a single-APK application.
- [ ] Export an app with ABI/density/language split APKs.
- [ ] Export a package with no readable base APK path; show a clear failure and do not proceed to uninstall.
- [ ] Verify manifest package/version/signer and every SHA-256 hash.
- [ ] Save via internal storage, Downloads, cloud document provider and SD-card document provider.

## Preserve-data uninstall and reinstall

Use a disposable test app with non-sensitive test data, not a production banking or messaging app.

- [ ] Enter a test string and create a local database row in the test app.
- [ ] Export `.avbackup`.
- [ ] Run App Vault preserve-data uninstall; verify shell output is `Success`.
- [ ] Confirm test app disappears from current user's launcher/package list.
- [ ] Reinstall the exact same APK set through PackageInstaller.
- [ ] Open the test app and confirm the test string/database row remain.
- [ ] Repeat with a same-signer newer version; confirm app's own migration behavior.
- [ ] Repeat with a different signer; ensure Android rejects incompatible update and App Vault does not claim restore success.
- [ ] Confirm standard Settings uninstall is not represented as a supported preserve-data path.
- [ ] Confirm clearing data/factory reset destroys the preserved app data, matching the documented limitation.

## Archive security

- [ ] Reject ZIP path traversal (`../`, absolute path, backslashes).
- [ ] Reject duplicate manifest/APK entries.
- [ ] Reject missing manifest/base APK.
- [ ] Reject package-name mismatch.
- [ ] Reject signer fingerprint mismatch.
- [ ] Reject hash and size mismatch.
- [ ] Reject too many entries and archive-size limit overflow.
- [ ] Reject additional unlisted entries.

## Installer

- [ ] Ask for `REQUEST_INSTALL_PACKAGES` user authorization.
- [ ] Start a one-APK PackageInstaller session.
- [ ] Start a multi-split session.
- [ ] Handle Android `STATUS_PENDING_USER_ACTION`.
- [ ] Handle install success, user cancellation, insufficient storage and signature conflict.

## Release gate

Do not advertise "full private-data backup" until a distinct, documented Android capability is available and demonstrated per target app. Keep the UI wording explicit that private data is retained locally, not placed in `.avbackup`.
