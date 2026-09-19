# TokenG v7.0.0 Release Notes

**Release Date:** September 19, 2026  
**Package:** `org.tokeng.android`  
**Version Name:** `7.0.0`  
**Version Code:** `255035000`  
**Target SDK:** Android 14+ (API 29 Target, API 36 Compile, API 24 Min)

---

## 🌟 Major Release Overview

TokenG **v7.0.0** is a major milestone release featuring an all-new official **Google-Colored Keyhole-G app icon** across adaptive and legacy mipmap densities, dynamic in-app version visibility, a redesigned compact inline theme switcher, and comprehensive cloud synchronization and security hardening.

---

## 🚀 Key Highlights & New Features

### 1. 🎨 New Google-Colored Keyhole-G App Icon
- **Official Brand Colors**: Beautiful, vibrant vector design featuring Google's signature 4-color palette:
  - 🔴 **Google Red** (`#EA4335`) along the top curved arc
  - 🟡 **Google Yellow** (`#FBBC05`) along the lower-left arc
  - 🟢 **Google Green** (`#34A853`) along the bottom curved arc
  - 🔵 **Google Blue** (`#4285F4`) on the center keyhole core and right-flared wedge
- **Adaptive Icon Architecture**: Clean white background (`#FFFFFF`) with centered 432x432 adaptive foreground graphic (`ic_launcher_foreground_img.png`) respecting Android's safe zone across circles, squircles, and custom launcher masks.
- **Complete Density Pack**: Pre-rendered, antialiased `.webp` assets across all mipmap densities (mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi) for both standard and round icon configurations.

### 2. 📱 Dynamic In-App Version Visibility
- **Settings Screen (SyncStatusFragment)**: Added an **ABOUT** preference card displaying `TokenG Version v7.0.0 (Build 255035000)` with the app emblem.
- **Login / Auth Gate Screen**: Added a subtle, clean dynamic version label at the footer of `AuthGateActivity` that automatically tracks build configurations.
- **Ecosystem Sync**: Synchronized version code `255035000` with the `gauth` CLI tool and companion services.

### 3. 🌓 Compact Inline Segmented Theme Selector
- **Zero Dialogs & Zero Sliders**: Replaced the disruptive modal dialog and eliminated the Expressive header overlay overlap bug.
- **Inline Material 3 Toggle Group**: Features 3 equal-width segmented buttons directly in Settings:
  - 📱 **System Default** (`ic_system_mode.xml`)
  - ☀️ **Light Mode** (`ic_light_mode.xml`)
  - 🌙 **Dark Mode** (`ic_dark_mode.xml`)
- **Instant Re-theming**: Immediate theme transitions with state guards in `ThemeManager` preventing redundant recreations.

### 4. 🔄 App Update Session Persistence
- **Fixed Update Lockout**: Resolved an issue where updating the app or killing background processes erased volatile RAM keys, incorrectly locking out authenticated users.
- **Decoupled Key State**: `BackendSyncManager.isUnlocked()` now relies on persistent local credentials (`isLoggedIn() || isLocalMode()`), ensuring sessions survive APK updates seamlessly.

### 5. ⚡ Cloud Sync Engine Hardening
- **Push-First Architecture**: `triggerAutoSync()` now uploads new local accounts to `POST /api/sync/push` *before* querying delta changes from the server.
- **Eliminated False Status Demotion**: Removed the destructive loop in `pullDelta()` that falsely demoted unchanged accounts to `LOCAL_ONLY`.
- **Graceful Local Mode**: Offline operations bypass network requests smoothly without surfacing false error banners.

### 6. 🛡️ Database Integrity & Migration
- **Per-Email Account Deduplication**: Enforced email-based uniqueness inside `TokenDatabase.insertOrUpdate()`, automatically pruning duplicate cards.
- **Signed-Out Reason Mapping**: Added `signedOutReason` column extraction in SQLite `cursorToAccount()` so sign-out reasons persist and display clearly in the UI.
- **Upgrade Backfill**: Added automatic migration query in `TokenDatabase.onUpgrade()` to backfill missing `instance_id` values on legacy databases.

### 7. 🔒 Security & Protection
- **Disabled ADB Backup**: Set `android:allowBackup="false"` in `AndroidManifest.xml` to prevent token exfiltration via USB debugging (`adb backup`).

### 8. 🌐 Backend Gateway (`tokeng-server` v7.0.0)
- Synchronized Go API gateway service (`tokeng-server`) to `v7.0.0` with version-stamped `/health` probe endpoints and Docker Compose configurations.

### 9. 📴 Permanent Local Mode Access
- **Always Accessible on Auth Gate**: Added a permanently visible **"Use Local Mode"** action directly on the login / registration screen (`AuthGateActivity`), giving users complete flexibility to operate TokenG purely on-device without cloud sync.
- **Zero Data-Loss Vault Flow**: Hardened local vault unlocking so that failed authentication attempts or typos never inadvertently wipe or reinitialize the local security vault.
- **Immediate Offline Handling**: Instant offline feedback guides users directly to Local Mode when network access is unavailable.

---

## 📦 Release Artifacts

| Artifact | File Name | Size / Hash | Target Branch |
|---|---|---|---|
| **Android Release APK** | `tokeng-release-7.0.0.apk` (`app-release.apk`) | 11.26 MB (11,807,536 bytes) | `release-apk` |
| **SHA-256 Checksum** | `C36ED805690FD5B363982557B7E6B7B9A58C8E8ECCB2C6DFDBAB268997906E5E` | Verified SHA-256 | — |
| **Source Code** | Git Repository (`origin/deploy`, `origin/master`) | Tag: `v7.0.0` | `deploy`, `master` |

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
adb install -r play-services-core-tokenG/build/outputs/apk/default/release/tokeng-release-7.0.0.apk
```
