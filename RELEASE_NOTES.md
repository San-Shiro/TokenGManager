# TokenG v6.2.0 Release Notes

**Release Date:** September 18, 2026  
**Package:** `org.tokeng.android`  
**Version Name:** `6.2.0`  
**Version Code:** `255034001`  
**Target SDK:** Android 14+ (API 29 Target, API 36 Compile, API 24 Min)

---

## 🌟 Release Overview

TokenG **v6.2.0** is a major feature and reliability milestone bringing a completely redesigned, compact inline theme selector directly embedded into the Settings interface, eliminating modal dialogs, sliders, and header overlay bugs. This release also incorporates comprehensive synchronization and security hardening identified during full adversarial audits, ensuring sessions survive app updates seamlessly and preventing account corruption during cloud synchronization.

---

## 🚀 Key Highlights & New Features

### 1. 🎨 Compact Inline Segmented Theme Selector
- **Zero Dialogs & Zero Sliders**: Replaced the disruptive modal `MaterialAlertDialog` with a sleek, compact inline panel directly on the Settings screen.
- **Material 3 Segmented Toggle Group**: Integrated a 3-way `MaterialButtonToggleGroup` featuring equal-width options:
  - 📱 **System Default** (`ic_system_mode.xml`)
  - ☀️ **Light Mode** (`ic_light_mode.xml`)
  - 🌙 **Dark Mode** (`ic_dark_mode.xml`)
- **Instant Theming**: Switching modes updates the app theme instantly without page flicker or header overlay overlap.
- **Redundancy Guard**: Added state checks in `ThemeManager` preventing duplicate activity recreations when tapping an already active theme.

### 2. 🔄 App Update Session Persistence
- **Fixed Update Lockout**: Resolved an issue where updating the app or killing the process zeroed volatile RAM cryptographic keys, falsely marking the user as locked out and redirecting them to `AuthGateActivity`.
- **Decoupled Key State**: Decoupled `BackendSyncManager.isUnlocked()` from volatile RAM state to rely on persistent local credentials (`isLoggedIn() || isLocalMode()`), ensuring seamless session survival across APK updates.

### 3. ⚡ Cloud Sync Engine Hardening
- **Push-First Sync Sequence**: Restructured `triggerAutoSync()` to upload new local accounts to `POST /api/sync/push` *before* querying delta updates from the server, eliminating race conditions.
- **Eliminated False Status Demotion**: Removed the destructive loop in `pullDelta()` that erroneously demoted accounts not returned in partial delta responses to `LOCAL_ONLY`.
- **Graceful Local Mode**: Offline and local-only modes bypass network calls smoothly without surfacing false connection error banners.

### 4. 🛡️ Database Integrity & Migration
- **Per-Email Account Deduplication**: Enforced email-based uniqueness inside `TokenDatabase.insertOrUpdate()`, automatically removing stale or duplicate records.
- **Signed-Out Reason Mapping**: Added `signedOutReason` column extraction in SQLite `cursorToAccount()`, ensuring reason messages (e.g. password changed, token revoked) persist and display clearly in the UI.
- **Upgrade Backfill**: Added automatic migration query in `TokenDatabase.onUpgrade()` to backfill missing or null `instance_id` values on legacy databases.

### 5. 🔒 Security & Protection
- **Disabled ADB Backup**: Set `android:allowBackup="false"` in `AndroidManifest.xml` to prevent unauthorized extraction of Master Tokens, database files, and encrypted shared preferences via USB debugging (`adb backup`).

### 6. 📐 Pixel-Perfect Card Alignment
- **Status Badge & Pill Centering**: Added `android:baselineAligned="false"`, balanced vertical padding, and minimum height to prevent typography baseline distortion between the caution circle icon and status pills.
- **Centered Button Icons**: Added `app:iconGravity="textStart"` to action buttons (`Generate Token`, `Manage Account`, `Re-login`), keeping icons and text optically centered together.

### 7. 🌐 Backend Gateway (`tokeng-server` v6.2.0)
- Synchronized Go API gateway service (`tokeng-server`) to `v6.2.0` with version-stamped `/health` probe endpoints and Docker Compose configurations.

---

## 📦 Release Artifacts

| Artifact | File Name | Size / Hash | Target Branch |
|---|---|---|---|
| **Android Release APK** | `tokeng-release-6.2.0.apk` (`app-release.apk`) | 11,816,816 bytes | `release-apk` |
| **SHA-256 Checksum** | `0e27aedc6922b3003563eece4ff1ff56eba2bb4f2f8b55d118c647f4110daef4` | — | — |
| **Source Code** | Git Repository (`origin/deploy`, `origin/master`) | — | `deploy`, `master` |

### Download Links
- **Direct GitHub Raw Download**:  
  [https://github.com/San-Shiro/TokenG/raw/release-apk/app-release.apk](https://github.com/San-Shiro/TokenG/raw/release-apk/app-release.apk)
- **Local Network Server**:  
  `http://192.168.1.4:8888/app-release.apk`

---

## 🛠️ Verification & Build Commands

To build the release APK from source:
```bash
./gradlew :play-services-core-tokenG:assembleDefaultRelease
```
To install on a connected device via ADB:
```bash
adb install -r play-services-core-tokenG/build/outputs/apk/default/release/tokeng-release-6.2.0.apk
```
