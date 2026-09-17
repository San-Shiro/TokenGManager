# TokenG

![Android](https://img.shields.io/badge/platform-Android-3DDC84)
![Module](https://img.shields.io/badge/module-play--services--core--tokenG-blue)
![Package](https://img.shields.io/badge/package-org.tokeng.android-lightgrey)

Standalone Google OAuth and Master Token (`aas_et`) generator and manager for Android.

## Overview

TokenG manages Google authentication sessions without adding accounts to Android's system `AccountManager`. Each account maintains independent check-in credentials, Android IDs, device profiles, and token state.

## Key Principles

- **Isolated Storage:** Keeps accounts and tokens contained within the application database; prevents system-wide account hijack or unwanted device sync.
- **Per-Account Device Profiles:** Maintains independent check-in credentials, Android IDs, and device spoofing profiles per account.
- **Wire Protocol Fidelity:** Strictly preserves Google Auth protocol contracts (`app="com.google.android.gms"`) on the wire for flawless server compatibility.
- **Automated Token Validation:** Periodically tests master-token validity in the background and automatically flags invalidated or signed-out accounts.
- **Remote Synchronization:** Securely syncs device registrations, master tokens, and service credentials with companion services.

## Core Architecture

| Component | Purpose |
|---|---|
| `play-services-core-tokenG` | Main application module |
| `org.tokeng.android` | Application package ID |
| `org.tokeng.gms` | Internal implementation namespace |
| `TokenManagerProvider` | Content provider exposing token queries (`org.tokeng.gms.tokenmanager`) |
| `MultiDeviceRegistry` | Isolated device profiles and check-in credentials per account |
| `TokenValidationRunner` | Periodic background validation of master token health |
| `BackendSyncManager` | Remote device registration and token synchronization |

## Build

```sh
./gradlew :play-services-core-tokenG:assembleDefaultRelease
```

Output:

```text
play-services-core-tokenG/build/outputs/apk/default/release/tokeng-release-6.1.0.apk
```

## Install

```sh
adb install -r play-services-core-tokenG/build/outputs/apk/default/release/tokeng-release-6.1.0.apk
```
