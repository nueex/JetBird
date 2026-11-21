# Build JetBird from Source

This repository contains the complete Kotlin source code, Android resources,
native JNI/SPAKE2 pairing library, bundled fonts, ad-blocker filter lists and
build script for **JetBird** (`com.nueex.jetbird`, version **1.0**). External
compiler and Android SDK tools are installed separately.

## Requirements

- **Python 3.10+**
- **JDK 17+** (`java`, `keytool`)
- **Standalone Kotlin compiler (`kotlinc`)** (2.x)
- **Android SDK** (`ANDROID_HOME` or `ANDROID_SDK_ROOT`) with:
  - Android SDK Platform **36** (`platforms/android-36/android.jar`)
  - Android SDK Build-Tools **36.0.0** (`aapt2`, `zipalign`, `apksigner`)
  - Android **NDK r28** (`ndk/28.*`) to cross-compile `libpsmtp_pair.so` for `arm64-v8a`, `armeabi-v7a`, `x86_64`, and `x86`
- **D8/R8 JAR** (automatically uses Build-Tools `d8.jar`, or pass `--d8 <path>` for R8/D8 9.1.29+ when building with Kotlin 2.4+)

## Build a local APK

From the repository root:

```powershell
python build.py
```

The script compiles the Kotlin sources, runs D8, compiles and links Android
resources, cross-compiles the native SPAKE2 pairing library for all four ABIs,
zipaligns the APK, signs it with a local debug key (`build/local-signing.keystore`),
and verifies the signature.

The output APK is written to `build/JetBird.apk`.

Install it on a connected Android 10+ device or emulator:

```powershell
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r build/JetBird.apk
```

## Build options

| Option | Purpose |
| :--- | :--- |
| `--output <dir>` | Output directory for the built APK (default: `build/`). |
| `--debug` | Enable diagnostic Logcat output and Android debuggable mode (`JetBird-debug.apk`). |
| `--sdk <path>` | Override `ANDROID_HOME` / `ANDROID_SDK_ROOT`. |
| `--kotlinc <path>` | Override `kotlinc` executable path. |
| `--d8 <path>` | Specify a custom D8/R8 JAR path. |
| `--version-code <n>` | Override `android:versionCode` for the build. |
| `--version-name <ver>` | Override `android:versionName` for the build. |
| `--keystore <path>` | Sign with an existing release keystore (requires `--key-alias` and `JETBIRD_KS_PASS` env var; optional `JETBIRD_KEY_PASS`). |
| `--firebase-database-url <url>` | Optional HTTPS Realtime Database URL for in-app update checks (omitted by default in open-source builds). |

## Signing a release build

Never commit keystores, passwords or `google-services.json` to Git. Pass release
signing credentials only via environment variables and CLI flags:

```powershell
$env:JETBIRD_KS_PASS = "<keystore-password>"
python build.py --keystore path\to\release.p12 --key-alias jetbird-release --output build-release
```

## License

Original source code and build scripts are licensed under **GNU GPL v3.0-or-later**
(see [LICENSE](LICENSE)). Third-party libraries, fonts and filter lists retain
their respective upstream terms (see [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)).
