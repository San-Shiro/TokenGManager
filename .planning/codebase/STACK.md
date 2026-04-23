# STACK
Last updated: 2026-04-23

## Languages
- **Java**: Primary language for legacy MicroG GMS core components and services.
- **Kotlin**: Used for modern UI components, fragments (e.g. `SettingsFragment.kt`), and Jetpack Navigation integration.

## Frameworks & Libraries
- **Android SDK**: Base runtime environment.
- **Jetpack Navigation**: Handles routing within the settings UI (`nav_settings.xml`).
- **AndroidX Preferences**: Used for the UI settings screens (`preferences_start.xml`).
- **MicroG Base**: Core framework simulating Google Play Services.

## Build System
- **Gradle**: Multi-module build system. The primary module of interest is `play-services-core-tokenG`.

## Key Dependencies
- Protobuf (for internal MicroG protocol serialization)
- Volley/OkHttp (for network requests, specifically device registration/checkin)

## Infrastructure
- Configured to build minimal APKs with non-essential features stripped out.
