# CONCERNS
Last updated: 2026-04-23

## Technical Debt & Fragile Areas
- **Deep Dependencies**: The UI layer was tightly coupled with backend features. For example, `SettingsFragment.kt` had hardcoded logic checking for GCM database counts. Removing GCM meant having to carefully extract or neuter these checks to avoid crashes.
- **Dangling References**: A major risk when stripping Android apps. Leaving a registered `Activity` in `AndroidManifest.xml` without the backing class, or leaving an XML ID reference in Kotlin code, leads to fatal `InflateException` or `ClassNotFoundException` at runtime.

## Security & Privacy
- The goal of TokenG is to increase privacy by reducing Google's footprint. The attack surface has been significantly minimized by stripping Push Notifications, location services, and extraneous APIs.
- The AES / Master tokens must be protected within the device.

## Known Issues
- Currently undergoing an aggressive stripping phase. Any missed references in `SettingsFragment.kt` or `AndroidManifest.xml` will break the compilation or runtime. The "Safest Pruning Tasks" checklist must be strictly adhered to.
