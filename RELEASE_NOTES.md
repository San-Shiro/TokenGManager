# TokenG v7.1.0 Release Notes

**Release Date:** September 19, 2026  
**Package:** `org.tokeng.android`  
**Version Name:** `7.1.0`  
**Version Code:** `255035001`  
**Target SDK:** Android 14+ (API 29 Target, API 36 Compile, API 24 Min)

---

## 🌟 Major Release Overview

TokenG **v7.1.0** is a critical synchronization, reliability, and data integrity release. It introduces a **durable tombstone architecture** to eradicate ghost/zombie account resurrection, enforces **strict Local Mode & unauthenticated isolation** to eliminate spurious HTTP 401 errors, guarantees **instance ID reuse across re-logins** to prevent duplicate cloud rows, and ensures all **account status changes (such as sign-outs) are properly queued and synchronized**.

---

## 🚀 Key Fixes & Architecture Enhancements

### 1. 🧟 Durable Tombstone Deletion Architecture (Zombie Resurrection Fix)
- **Problem**: Previously, deleting an account locally only removed rows from the device SQLite database without notifying the cloud backend. When the user later re-synced, logged in on another device, or triggered a pull, the deleted account was resurrected from PostgreSQL.
- **Solution**:
  - Upgraded SQLite schema to **Version 4** (`TokenDatabase.kt`), adding a durable `deleted_instances` tombstone table.
  - Account deletion via `UnifiedDashboardFragment` and `AccountsFragment` calls `markAccountDeleted(email)`, which records the `instance_id` into `deleted_instances` before dropping local rows.
  - Sync push batches transmit `{ "instance_id": id, "deleted": true }` to `POST /api/sync/push`.
  - Tombstones are purged locally only after confirmed HTTP 200 server acknowledgment.
  - Re-registering an account immediately cancels any pending deletion tombstones for that email.

### 2. 🚫 Local Mode & Unauthenticated Request Isolation (Eliminated Spurious 401 & "FAILED" Flags)
- **Problem**: In Local Mode or when signed out of cloud sync, adding or editing accounts or triggering background syncs sent unauthenticated requests to `/api/sync/push` and `/api/sync/pull`. The server responded with HTTP 401 Unauthorized, erroneously marking purely local accounts with a red `"FAILED"` sync status.
- **Solution**:
  - Enforced early return guards in `syncAccountsBatch()` and `pullDelta()` when `isLocalMode(context) || !isLoggedIn(context)`.
  - Suppressed error status writes so local accounts never transition to `FAILED` when operating offline or locally.

### 3. 🔄 Instance ID Reuse & Cloud Orphan Prevention on Re-Login
- **Problem**: When a user re-authenticated an already-existing email in `LoginActivity.java`, a brand new random UUID `instanceId` was minted. Because PostgreSQL uses `PRIMARY KEY (user_id, instance_id)`, this orphaned old instance records in the cloud, resulting in duplicate account cards during multi-device pull operations.
- **Solution**:
  - `LoginActivity.java` now checks the local database for existing credentials by email (`getIntent().getStringExtra("email")` and `rtResponse.email`).
  - Reuses the existing persistent `instanceId` across re-logins.
  - Added an explicit constructor to `TokenAccount.kt` accepting a predefined `instanceId`.

### 4. 📤 Account Status Mutations Queued for Sync
- **Problem**: When `markSignedOut()` or `updateAccountStatus()` updated an account status in SQLite, `sync_status` remained `"SYNCED"`. As a result, the `account_status = "SIGNED_OUT"` state was never queued into delta push and never reflected on other linked devices.
- **Solution**:
  - `markSignedOut()` and `updateAccountStatus()` now transition non-`LOCAL_ONLY` accounts to `COL_SYNC_STATUS = 'PENDING'`, ensuring auth state updates are immediately pushed to the cloud upon the next auto-sync cycle.

### 5. 🌐 Backend Server URL Switch Cursor Reset
- **Problem**: Switching backend server URLs preserved the cached timestamp cursor (`lastServerTime`), causing `pullDelta` on the new server to query for records using an incompatible or non-existent timestamp.
- **Solution**:
  - `BackendSyncManager.setBackendUrl()` automatically resets `lastServerTime` to `"1970-01-01T00:00:00Z"` whenever the backend URL changes, forcing a clean re-sync against the newly selected server.

### 6. 🌐 Backend Gateway Alignment (`tokeng-server` v7.1.0)
- Synchronized Go API gateway service (`tokeng-server`) to `v7.1.0` with version-aligned `/health` metadata and Docker Compose configurations.

---

## 📦 Release Artifacts

| Artifact | File Name | Size / Hash | Target Branch |
|---|---|---|---|
| **Android Release APK** | `tokeng-release-7.1.0.apk` (`app-release.apk`) | 11.41 MB (11,963,104 bytes) | `release-apk` |
| **SHA-256 Checksum** | `F7293F522FA7261D982B44E56D02A4C5355CBA8966021CA56915A0127126B071` | Verified SHA-256 | — |
| **Source Code** | Git Repository (`origin/deploy`, `origin/master`) | Tag: `v7.1.0` | `deploy`, `master` |

### Download Links
- **Direct GitHub Raw Download**:  
  [https://github.com/San-Shiro/TokenG/raw/release-apk/app-release.apk](https://github.com/San-Shiro/TokenG/raw/release-apk/app-release.apk)
- **Local Network Server**:  
  `http://192.168.1.4:8888/app-release.apk`  
  `http://192.168.1.4:8888/tokeng-release-7.1.0.apk`

---

## 🛠️ Verification & Build Commands

To build the release APK from source:
```bash
./gradlew :play-services-core-tokenG:assembleDefaultRelease
```
To install on a connected device via ADB:
```bash
adb install -r play-services-core-tokenG/build/outputs/apk/default/release/tokeng-release-7.1.0.apk
```
