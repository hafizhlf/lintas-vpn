# Lintas

Lintas is an open-source Android VPN client for Cloudflare WARP. It supports
both WireGuard and MASQUE (HTTP/3 / CONNECT-IP) from a single WARP identity.

> Lintas is an independent project and is not affiliated with Cloudflare.

## Features

- WireGuard WARP transport with the WARP `client_id` reservation.
- MASQUE transport over QUIC / HTTP/3 with CONNECT-IP.
- One WARP consumer identity shared by both transports.
- Protocol selector in Settings; the Home connect button follows it.
- WARP+ license activation with a license key.
- Automatic transport restart when the underlying Wi-Fi or cellular network changes.
- Encrypted identity storage using Android Keystore.
- No account, billing, telemetry, or backend in the data path.

## Status

WireGuard and MASQUE have been tested on a Poco X8 Pro Max (Android 16).
The transport reconnects automatically after network changes. Split
tunneling and kill switch are not part of this release yet.

## Requirements

- JDK 21
- Android SDK 36 (build tools 36.0.0)
- Android NDK 27.0.12077973
- Rust stable with `cargo-ndk`
- `ANDROID_NDK_HOME` pointing at the NDK

## Build

Build the Rust core for Android:

```bash
export ANDROID_NDK_HOME=/path/to/android-ndk-r27
cd core
cargo ndk -t arm64-v8a -t x86_64 -P 29 \
  -o ../app/src/main/jniLibs build --release
```

Build the debug APK:

```bash
./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

For a release build, `./gradlew :app:assembleRelease` produces an unsigned
APK; sign it with your own release keystore before distributing it.

## Project layout

```text
app/        Kotlin, Jetpack Compose, VpnService, WARP control plane
core/       Rust data plane, JNI, WireGuard and MASQUE transports
docs/       System design and reverse-engineered WARP notes
mockups/    UI design reference
```

## Privacy

WARP registration and WARP+ activation talk directly to Cloudflare. Lintas
does not run a relay or backend. WARP identity and license data are stored
encrypted on the device. The WARP+ license key is never stored in plaintext.

## License

GPL-3.0. See [LICENSE](LICENSE).
