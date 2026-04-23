# STRUCTURE
Last updated: 2026-04-23

## Directory Layout
- `play-services-core-tokenG/`: The main module for the TokenG implementation.
  - `src/main/AndroidManifest.xml`: Declares remaining activities and services. Extraneous components have been stripped.
  - `src/main/java/org/microg/gms/`: Core Java source code.
    - `checkin/`: Logic for device checkin.
    - `auth/`: Token retrieval logic.
    - `ui/`: Legacy UI activities. Many files (e.g. `PrivacySettingsActivity.java`, `AboutFragment.java`) have been deleted.
  - `src/main/kotlin/org/microg/gms/ui/`: Kotlin UI source code.
    - `SettingsFragment.kt`: The main settings dashboard UI logic.
  - `src/main/res/`: Resources.
    - `xml/preferences_start.xml`: The root preference screen definition.
    - `navigation/nav_settings.xml`: The Jetpack Navigation graph linking settings fragments.

## Naming Conventions
- standard Android/Java conventions.
- Package names remain `org.microg.gms.*` in code, but the exported app namespace is shifted to `com.google.android.gms.tokeng` for side-by-side installations.
