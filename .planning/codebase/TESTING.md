# TESTING
Last updated: 2026-04-23

## Testing Framework
- **JUnit**: Used for basic unit tests.
- **AndroidX Test / Espresso**: Used for UI testing and integration tests within the Android environment.

## Coverage & Structure
- Due to the nature of the project (reverse engineering and system modification), standard unit test coverage is low.
- Most testing is done via manual verification or specific integration tests that assert the generated tokens (AES / ya9) match expected signatures.
- Tests are located in `src/androidTest/` for instrumented tests and `src/test/` for local unit tests.

## Mocking
- Mocks are occasionally used to simulate Google Play Services endpoints during testing, but the primary purpose of the software *is* to act as a mock provider itself.
