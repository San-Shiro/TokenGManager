# ARCHITECTURE
Last updated: 2026-04-23

## High-Level Pattern
The TokenG project is a heavily stripped-down fork of MicroG. It follows a Service-Oriented Architecture (SOA) adapted for the Android OS, acting as a mock provider for Google Play Services APIs.

## Core Layers

1. **API Stubs Layer**
   Provides the exact AIDL and class structures expected by apps linking against Google Play Services. For TokenG, most of these stub endpoints throw `UnsupportedOperationException` or return empty data, except for Auth and Checkin.

2. **Core Services Layer**
   - **Checkin**: Handles device registration (generating Android ID).
   - **Auth**: Handles obtaining and exposing AES / YA9 tokens.

3. **UI Layer**
   - Built on standard Android Activities and Jetpack Navigation fragments.
   - Designed to be extremely minimal. Exposes only the device checkin status and the token generator tool.

## Data Flow (Token Generation)
1. User logs in via the modified `AccountManagerActivity`.
2. `CheckinService` runs in the background, sending device parameters to Google to receive a device registration profile.
3. Upon request, the Auth component utilizes the account credentials and checkin profile to hit Google's auth servers and retrieve the specialized `ya9` tokens or AES tokens.
4. Tokens are exposed to the user via the `TokenGeneratorFragment`.
