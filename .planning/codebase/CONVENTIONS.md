# CONVENTIONS
Last updated: 2026-04-23

## Code Style
- **Java**: Follows classic Google Java Style. Heavy use of Android standard patterns (Services, Intents, BroadcastReceivers).
- **Kotlin**: Follows idiomatic Kotlin styles. Uses coroutines (`lifecycleScope.launch`) for asynchronous UI updates, such as in `SettingsFragment.kt`.

## UI Patterns
- **Jetpack Navigation**: The app uses a single Activity architecture for settings (`MainSettingsActivity` hosts the `navhost`). Navigation between screens happens via `findNavController().navigate()`.
- **Preferences**: The UI heavily relies on `androidx.preference` libraries to generate lists of settings automatically based on XML definitions.

## Error Handling
- Silently drops or ignores operations that rely on stripped components (e.g. GCM checks, Maps APIs).
- Extensive logging via `Log.d` and `Log.w` is used to trace authentication flows.

## Modification Patterns
- When stripping MicroG features, the pattern is:
  1. Remove the Jetpack Navigation fragment entry from `nav_settings.xml`.
  2. Remove the UI layout from `preferences_start.xml`.
  3. Delete or comment out hardcoded bindings in `SettingsFragment.kt`.
  4. Remove the component from `AndroidManifest.xml`.
  5. Delete the backing `.java` or `.kt` file.
