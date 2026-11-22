# Changelog

## 1.0.0 - 2026-11-22

- WireGuard WARP and MASQUE (HTTP/3 / CONNECT-IP) transports.
- One WARP identity shared by both transports, with protocol switching.
- Protocol selector in Settings; Home connect uses the selected protocol.
- WARP+ license activation via Settings.
- Encrypted identity and license storage with Android Keystore.
- Polished Home, Route, Activity, and Settings screens.

Known limitations: no split tunneling, kill switch, or automatic reconnect
yet. A valid WARP+ license key is required to activate WARP+.
