# GlassBar SSH

GlassBar SSH is an Android SSH client built with Kotlin and Jetpack Compose. It provides saved server profiles, an interactive terminal, lightweight server monitoring, Material and Miuix themes, and host identity verification.

## Features

- Password and SSH private-key authentication
- Android Keystore-backed credential encryption
- Known-host fingerprint verification with first-use confirmation
- Interactive terminal with ANSI colors, scrollback, wide-character support, copy/paste, and font scaling
- CPU and memory status with lifecycle-aware refresh
- Material 3 and Miuix user interfaces
- English and Simplified Chinese resources

## Requirements

- JDK 21
- Android SDK Platform 37.0
- Android SDK Build Tools 37.0.0
- Android 12 or newer (API 31+)

## Build

```powershell
./gradlew.bat testDebugUnitTest lintDebug lintRelease assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

Release signing reads `sign.properties` from the repository root. Start from `sign.example.properties`; never commit the real file or keystore.

## Security

- Server host keys must be trusted before credentials are sent.
- Saved secrets are encrypted with a non-exportable Android Keystore key.
- SSH credentials are excluded from Android cloud backup and device transfer.
- Saving a password is optional. Public-key authentication is recommended.
- Screenshots and recent-app previews are blocked to protect credentials and terminal output.

If a trusted server changes its host key, verify the new SHA-256 fingerprint through a separate channel before replacing it.

## Testing

The terminal parser, server-stat parser, and connection storage have focused unit tests. GitHub Actions runs unit tests, debug and release lint, and a debug build for pushes and pull requests.

## License

GlassBar SSH is distributed under the GNU General Public License v3.0. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).

Parts of the original interface structure were adapted from the KernelSU Manager project. Third-party libraries remain subject to their own licenses.
