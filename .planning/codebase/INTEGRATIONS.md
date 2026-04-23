# INTEGRATIONS
Last updated: 2026-04-23

## Google Play Services Simulation
- **CheckinService**: Communicates with Google's checkin servers to register the device and obtain Android ID / Security Token.
- **AuthService**: Interacts with Google's authentication endpoints to retrieve AES and YA9 access tokens.

## Stripped External Integrations
The following integrations have been deliberately removed to reduce footprint and attack surface:
- Firebase Cloud Messaging (GCM/FCM)
- Google Maps API
- Google Cast
- Wear OS / Fitness APIs
- Nearby Share
- SafetyNet Attestation
- Google Drive & Games

## System Integrations
- Integrates deeply with Android's `AccountManager` to provide the "Google" account type to the system.
- Disguises itself as `com.google.android.gms` or a custom namespace (`com.google.android.gms.tokeng`) to trick apps requiring Google Play Services.
