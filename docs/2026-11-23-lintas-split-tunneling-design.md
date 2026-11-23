# Lintas — Split tunneling per aplikasi

Tanggal: 2026-11-23
Status: diimplementasikan; verifikasi perangkat tertunda
Target rilis: 1.0.6 (versionCode 16)

## Tujuan

Pengguna bisa memilih aplikasi yang **tidak** melewati tunnel WARP. Aplikasi
lain tetap memakai tunnel seperti biasa. Pilihan disimpan dan dipakai saat TUN
dibuat.

## Keputusan yang sudah diambil

- Mode **pengecualian** (*disallowed list*), bukan mode "hanya aplikasi
  terpilih" (*allow list*). Allow list bisa ditambahkan nanti dengan picker
  yang sama.
- Daftar aplikasi diambil dari aktivitas launcher. Tidak memakai izin
  `QUERY_ALL_PACKAGES`; manifest hanya memakai elemen `<queries>`.
- Perubahan daftar saat VPN aktif **tidak** memutus koneksi. Perubahan berlaku
  setelah pengguna memutuskan lalu menyambung ulang. Tidak ada tombol atau
  reconnect otomatis.

## Non-goals

- Mode allow list.
- Menampilkan aplikasi tanpa ikon launcher.
- Menerapkan perubahan tanpa membangun ulang TUN.
- Filter berdasarkan izin INTERNET atau pemakaian data.
- Dukungan multi-user/work profile di luar profil aplikasi saat ini.

## Pendekatan yang dipertimbangkan

1. **Disallowed list di `VpnService.Builder` + halaman picker penuh**
   (dipilih). Satu titik integrasi di `establishTun()`; daftar panjang lebih
   nyaman dicari di halaman penuh.
2. **Picker berupa dialog/bottom sheet.** Ditolak karena daftar aplikasi bisa
   panjang dan ruang untuk pencarian terbatas.
3. **Allow list.** Ditolak karena permintaan saat ini adalah memilih aplikasi
   yang tidak perlu di-tunnel.
4. **`QUERY_ALL_PACKAGES` + semua aplikasi.** Ditolak pengguna karena izin
   sensitif; aplikasi tanpa launcher tidak diperlukan untuk kasus ini.

## Desain

### Penyimpanan

`VpnPreferences` (SharedPreferences `lintas_settings`) menyimpan
`Set<String>` berisi package name yang dikecualikan dengan key
`split_tunnel_excluded_apps`. Set disalin saat dibaca agar tidak memakai
instance mutable dari SharedPreferences.

### Penemuan aplikasi

Objek baru `SplitTunnelApps`:

- Query `ACTION_MAIN` + `CATEGORY_LAUNCHER` (dan `LEANBACK_LAUNCHER` untuk
  perangkat TV) lewat `PackageManager`.
- Deduplikasi berdasarkan package name, buang package Lintas sendiri.
- Ambil label dari `ApplicationInfo`; tandai `FLAG_SYSTEM`.
- Urutkan berdasarkan label secara case-insensitive.
- Package tersimpan hanya bisa ditampilkan jika masih terlihat lewat query
  launcher. Dengan batasan tanpa `QUERY_ALL_PACKAGES`, package terpasang yang
  kehilangan launcher tidak bisa dibedakan dari package yang sudah di-uninstall;
  package seperti itu dianggap tidak tersedia dan dihapus dari preferensi.
- Package tersimpan yang sudah tidak terpasang dihapus dari preferensi saat
  halaman dibuka.
- Ikon dimuat lazy di background dengan cache LRU kecil; bukan dimuat saat
  daftar dibangun.

### UI

Setelan menampilkan baris **Split tunnel** dengan jumlah aplikasi yang
dikecualikan dan aksi **Kelola**. Saat tunnel aktif, baris menampilkan catatan
"Berlaku setelah putus dan sambung ulang".

Halaman picker (full screen, tanpa bottom navigation):

- Header dengan tombol kembali, judul "Split tunnel", dan subtitle
  "Aplikasi terpilih tidak melewati tunnel."
- Kolom pencarian berdasarkan nama atau package name.
- Toggle "Tampilkan aplikasi sistem". Aplikasi sistem yang sudah dikecualikan
  tetap terlihat walau toggle mati.
- Daftar aplikasi: ikon, label, package name, dan switch. Mengetuk baris juga
  mengubah pilihan.
- Perubahan disimpan langsung per toggle.
- Catatan "Berlaku setelah putus dan sambung ulang" bila tunnel sedang aktif.
- Empty state untuk pencarian kosong dan untuk kegagalan memuat daftar.
- Tombol "Bersihkan" untuk menghapus semua pilihan.

### Integrasi service

`establishTun()` memanggil `applySplitTunnel(builder)` sebelum
`builder.establish()`:

- Muat daftar package dari preferensi.
- Lewati package Lintas sendiri.
- Panggil `addDisallowedApplication(pkg)`; tangkap
  `PackageManager.NameNotFoundException` untuk package yang sudah dihapus dan
  lanjutkan.
- Catat jumlah aplikasi yang diterapkan dan package yang dilewati ke
  `DiagnosticLog`.
- Jika daftar kosong, tidak ada panggilan disallowed dan semua trafik tetap
  masuk tunnel.

Karena aturan hanya dibaca saat `establish()`, TUN yang sedang hidup tidak
berubah sampai pengguna memutuskan dan menyambung ulang.

### Edge case

- Package dikecualikan lalu di-uninstall: dilewati saat connect; tunnel tetap
  hidup.
- Package terpasang yang kehilangan ikon launcher: Android 11+ menyaring
  `getApplicationInfo`, sehingga package dianggap tidak tersedia dan dihapus
  dari preferensi. Ini konsekuensi dari tidak memakai `QUERY_ALL_PACKAGES`.
- Dua package berbagi UID: Android memperlakukan keduanya sebagai satu UID;
  perilaku ini mengikuti sistem dan tidak ditangani khusus.
- Aplikasi Lintas sendiri tidak pernah ditampilkan atau ditambahkan.
- Daftar preferensi berisi package usang: dibersihkan saat halaman picker
  dibuka, dan tetap dilewati secara defensif saat connect.

## Verifikasi

- `lintDebug` dan `assembleRelease` lolos.
- Unit test murni untuk filter/pencarian daftar aplikasi dan pruning package
  tersimpan (tanpa Android runtime).
- Uji perangkat: **tertunda** — perangkat tidak terhubung saat build 1.0.6.
  Rencananya: pilih satu aplikasi, putuskan dan sambung ulang, lalu bandingkan
  `https://www.cloudflare.com/cdn-cgi/trace` di aplikasi tersebut (`warp=off`)
  dan di aplikasi lain (`warp=on`).
- Uji package yang di-uninstall saat terpilih: **tertunda** — reconnect tetap
  berhasil dan log diagnostik mencatat package yang dilewati.
- `apksigner verify` pada APK rilis.

## Rilis

- `versionCode 16`, `versionName 1.0.6`.
- Perbarui `CHANGELOG.md`, `README.md`, dan dokumen ini.
- APK ditandatangani dengan keystore rilis yang sama.
