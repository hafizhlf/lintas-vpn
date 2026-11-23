# Changelog

## 1.0.5 - 2026-11-23

- Add an optional persistent diagnostic log, including native transport errors,
  network changes, reconnect reasons, handshakes, probe results, and periodic
  traffic statistics.
- Add a Cloudflare account status check and show the WARP+ expiry when the API
  provides it.
- Fix WARP+ detection: use `account_type` instead of the misleading
  `warp_plus` field, which Cloudflare also returns for free accounts.
- Allow replacing a license key while WARP+ is displayed as active.
- Add `warp_account_check.py` for repeatable registration and entitlement checks.

## 1.0.4 - 2026-11-22

- Count inbound and outbound traffic in the `Data` counter.
- Show the traffic split on the Activity screen.
- Add real feedback to `Uji koneksi`: latency on success, timeout or error on
  failure, with a running state on the button.
- Send the connection probe through the active tunnel.

## 1.0.3 - 2026-11-22

- Keep the MASQUE QUIC path alive with a PING every 15 seconds while idle.
- Set a 30-second QUIC idle timeout and reconnect automatically when the
  transport stops receiving responses for 45 seconds.
- Bind the tunnel UDP socket to the selected underlying network so it follows
  Wi-Fi/cellular handovers.

## 1.0.2 - 2026-11-22

- Keep the TUN/VPN interface open during a network handover and restart only
  the transport socket and handshake, so existing TCP sessions survive.
- Track the selected active network path with hysteresis instead of reacting to
  every validated secondary network.
- Preserve session duration across a transport restart.
- Add network selection and reconnect diagnostics.

## 1.0.1 - 2026-11-22

- Restart WireGuard or MASQUE automatically when the underlying network changes.
- Detect a stopped native transport thread and reconnect.
- Re-resolve the endpoint and recreate the transport socket after a handover.

## 1.0.0 - 2026-11-22

- WireGuard WARP and MASQUE (HTTP/3 / CONNECT-IP) transports.
- One WARP identity shared by both transports, with protocol switching.
- Protocol selector in Settings; Home connect uses the selected protocol.
- WARP+ license activation via Settings.
- Encrypted identity and license storage with Android Keystore.
- Polished Home, Route, Activity, and Settings screens.

Known limitations: no split tunneling, kill switch, or automatic reconnect
yet. A valid WARP+ license key is required to activate WARP+.
