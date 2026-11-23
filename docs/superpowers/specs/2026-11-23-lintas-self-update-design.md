# Lintas — Pembaruan aplikasi sendiri

Tanggal: 2026-11-23
Status: diimplementasikan; verifikasi perangkat tertunda (Task 9)
Target rilis: 1.0.7 (versionCode 17)

## Tujuan

Lintas bisa memeriksa rilis baru, mengunduh APK-nya, memverifikasi bahwa APK
itu benar-benar dari Lintas, lalu menyerahkannya ke installer Android. Pengguna
tidak perlu membuka browser atau mencari file APK sendiri.

## Keputusan yang sudah diambil

- **Sumber pembaruan: GitHub Releases di repo `hafizhlf/lintas-vpn`.** Repo
  akan dijadikan **publik** sebelum rilis 1.0.7. Tidak ada token di APK; feed
  dibaca tanpa autentikasi.
- **Hanya rilis stabil.** Rilis `prerelease` dan `draft` diabaikan. GitHub
  `/releases/latest` memang sudah menyaring keduanya.
- **Pemeriksaan otomatis saat aplikasi dibuka, maksimal sekali per 24 jam**,
  ditambah tombol **Cek pembaruan** manual di Setelan. Tidak ada pemeriksaan
  latar belakang, WorkManager, atau notifikasi.
- **Unduhan dan pemasangan di dalam aplikasi.** APK diunduh ke cache aplikasi,
  diverifikasi, lalu diserahkan ke activity installer Android lewat
  `FileProvider`. Pengguna tetap melihat dialog konfirmasi installer Android;
  tidak ada pemasangan senyap.
- **Verifikasi wajib sebelum memasang:** nama package, `versionCode` lebih
  tinggi, `versionName` cocok dengan tag rilis, dan sertifikat penanda tangan
  sama dengan aplikasi yang terpasang. Checksum SHA-256 dari metadata aset
  GitHub ikut diverifikasi bila tersedia.
- **Tidak ada downgrade.** APK dengan `versionCode` sama atau lebih rendah
  ditolak.
- **VPN tidak diputus otomatis sebelum pemasangan.** Pengguna diberi tahu
  bahwa koneksi akan terputus saat aplikasi diperbarui. Jika pengguna membatalkan
  installer, VPN tetap seperti semula.
- **Tanpa izin `QUERY_ALL_PACKAGES`.** Izin baru hanya
  `REQUEST_INSTALL_PACKAGES`.

## Non-goals

- Pemasangan senyap atau pemasangan tanpa konfirmasi pengguna. Android tidak
  mengizinkannya untuk aplikasi biasa.
- Memperbarui tanpa interaksi pengguna (misalnya lewat root/Shizuku).
- Update otomatis di latar belakang atau notifikasi "pembaruan tersedia".
- Saluran beta/nightly, rollback, atau downgrade.
- Rotasi kunci penanda tangan. Kunci rilis yang sekarang dipakai terus.
- Memperbarui komponen Rust atau aset terpisah dari APK.
- Menjual atau mengirim data penggunaan ke server pihak ketiga. Satu-satunya
  koneksi baru adalah `api.github.com` dan `github.com`.

## Kendala

- Repo saat ini **private**. GitHub Releases pada repo private tidak bisa
  dibaca tanpa token. Token di APK akan bocor, jadi repo harus publik. Ini
  konsisten dengan lisensi GPL-3.0 dan README yang menyebut proyek open source.
- APK rilis ditandatangani dengan kunci rilis yang sama seperti 1.0.5 dan 1.0.6
  (SHA-256 sertifikat
  `0fa1eb301523054e861ea58e2a6dd681539a904163f28d61ceff0f110bc411d5`).
  Pembaruan lintas kunci akan ditolak oleh Android dan oleh pemeriksaan aplikasi.
- Perangkat uji saat ini memakai debug build. Debug build dan release build
  tidak bisa saling menimpa. Uji updater akan memakai debug build 1.0.7 di atas
  debug build yang sudah ada agar data WARP tetap. Peralihan ke APK rilis tetap
  butuh uninstall satu kali dan akan menghapus identitas WARP lokal.
- Ukuran APK saat ini sekitar 56 MB. Cache harus punya ruang yang cukup.
- Pemeriksaan memakai kuota GitHub API tanpa autentikasi (60 permintaan per jam
  per IP). Satu pemeriksaan = satu permintaan. Interval 24 jam membuat ini tidak
  menjadi masalah untuk pemakaian normal.

## Pendekatan yang dipertimbangkan

1. **Updater di dalam aplikasi + GitHub Releases publik + intent installer
   Android** (dipilih). Paling sesuai dengan permintaan "update sendiri". Tidak
   perlu server, token, atau aplikasi tambahan. Pengguna hanya melihat dialog
   installer Android.
2. **Cek versi di aplikasi, lalu buka halaman rilis di browser.** Lebih
   sederhana dan tidak butuh izin pemasangan, tetapi pengguna masih harus
   mengunduh dan memasang APK manual. Tidak memenuhi "update sendiri".
3. **Repo rilis publik terpisah, source tetap private.** Bisa dilakukan, tetapi
   distribusi APK GPL tanpa source menimbulkan masalah lisensi dan menambah
   satu repo lagi. Ditolak.
4. **Repo private + token GitHub di APK.** Token akan terbaca siapa pun yang
   memegang APK. Ditolak.

Pilihan pengguna: **opsi 1**. Tanggal keputusan: 2026-11-23.

## Desain

### Feed rilis

Endpoint tetap, ditanam di kode rilis:

```text
GET https://api.github.com/repos/hafizhlf/lintas-vpn/releases/latest
Accept: application/vnd.github+json
User-Agent: Lintas/<versionName> (Android)
X-GitHub-Api-Version: 2022-11-28
```

Field yang dipakai:

- `tag_name` — misalnya `v1.0.8`.
- `name` — judul rilis.
- `body` — catatan rilis (Markdown, ditampilkan sebagai teks biasa).
- `html_url` — untuk tautan "Lihat di GitHub".
- `prerelease`, `draft` — harus `false`; kalau tidak, rilis diabaikan.
- `assets[]`:
  - `name` — misalnya `lintas-1.0.8.apk`.
  - `state` — harus `uploaded`.
  - `size` — ukuran byte.
  - `browser_download_url` — URL unduhan.
  - `digest` — `sha256:<hex>` bila tersedia.

Pemilihan aset:

1. Buang aset yang bukan `.apk`, buang `.apk.idsig`, dan buang aset yang
   `state`-nya bukan `uploaded`.
2. Utamakan nama persis `lintas-<versi>.apk`.
3. Jika tidak ada, ambil aset `.apk` pertama.
4. Jika tidak ada APK, tampilkan error "Rilis terbaru tidak menyertakan APK".

Ukuran aset harus lebih dari 0 dan tidak lebih dari 150 MB. Batas ini mencegah
unduhan tak masuk akal.

### Versi

- Tag harus berbentuk `v<major>.<minor>.<patch>` atau
  `<major>.<minor>.<patch>`. Sufiks seperti `-beta` ditolak sebagai bukan
  rilis stabil.
- Versi rilis dibandingkan secara numerik dengan `BuildConfig.VERSION_NAME`.
  Rilis dianggap pembaruan hanya jika lebih tinggi.
- Setelah APK diunduh, `versionCode` di APK harus lebih tinggi daripada
  `versionCode` aplikasi terpasang. Ini adalah keputusan akhir; tag yang lebih
  tinggi tetapi APK-nya sama/lebih rendah ditolak.
- `versionName` di APK harus sama dengan versi dari tag.

### Verifikasi keamanan

Sebelum APK diserahkan ke installer, `UpdateVerifier` memeriksa:

1. **Checksum** — bila `digest` ada, SHA-256 file harus sama. Bila `digest`
   tidak ada, lanjut dengan pemeriksaan berikutnya.
2. **Ukuran** — jumlah byte yang diunduh harus sama dengan `Content-Length`
   (bila ada) dan dengan `size` aset.
3. **Package** — `packageName` di APK harus `app.lintas`.
4. **Versi** — `versionCode` lebih tinggi dan `versionName` cocok dengan tag.
5. **Tanda tangan** — himpunan SHA-256 sertifikat penanda tangan APK harus sama
   dengan himpunan sertifikat aplikasi terpasang. Ambil dari
   `PackageManager.getPackageArchiveInfo(... GET_SIGNING_CERTIFICATES)` dan
   `getPackageInfo(packageName, GET_SIGNING_CERTIFICATES)`.
6. **Bukan APK rusak** — `getPackageArchiveInfo` harus mengembalikan data.

Jika salah satu gagal, file dihapus, pemasangan tidak dimulai, dan pengguna
melihat pesan yang spesifik. Pemeriksaan tanda tangan adalah batas keamanan
utama; checksum hanya menangkap unduhan rusak.

### Status dan alur

`UpdateController` adalah objek dengan `StateFlow<UpdateState>`, mengikuti pola
`WarpController`.

```kotlin
enum class UpdateStatus {
    IDLE, CHECKING, UP_TO_DATE, AVAILABLE,
    DOWNLOADING, READY, ERROR,
}

data class ReleaseInfo(
    val versionName: String,
    val tag: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String,
    val apkSize: Long,
    val apkSha256: String?,
)

data class UpdateState(
    val status: UpdateStatus = UpdateStatus.IDLE,
    val currentVersion: String = ...,
    val release: ReleaseInfo? = null,
    val progress: Int = 0,
    val error: String? = null,
    val justUpdatedTo: String? = null,
)
```

Alur:

1. `initialize(context)` menghapus file `.part`, mendeteksi pembaruan yang sudah
   terpasang, lalu memulihkan status dari preferensi `lintas_update`.
2. Deteksi pembaruan terpasang: bandingkan `versionCode` sekarang dengan
   `last_seen_version_code`. Jika lebih tinggi, isi `justUpdatedTo` dengan versi
   sekarang. Simpan `versionCode` sekarang.
3. Pemulihan `READY`:
   - Jika `ready_file` tersimpan dan filenya ada, verifikasi ulang file itu
     (package, versi, sertifikat). Jika valid dan `versionCode`-nya lebih tinggi
     dari aplikasi sekarang, status `READY` dan `release` diambil dari metadata
     yang tersimpan. Ini memulihkan unduhan setelah proses mati, termasuk saat
     pengguna sedang di layar izin.
   - Jika file tidak ada, tidak valid, atau `versionCode`-nya sudah tidak lebih
     tinggi (pembaruan berhasil dipasang), hapus file dan metadata `ready_*`.
4. Jika tidak ada `READY`, gunakan hasil pemeriksaan tersimpan: versi tersimpan
   lebih baru → `AVAILABLE`; sudah pernah diperiksa dan tidak lebih baru →
   `UP_TO_DATE`; belum pernah → `IDLE`.
5. Jika belum ada percobaan dalam 24 jam dan status bukan `READY`, jalankan
   `check(manual = false)`.
6. `check` memanggil API, memilih aset, menyimpan hasil, lalu menetapkan
   `AVAILABLE` atau `UP_TO_DATE`. Kegagalan pada mode otomatis hanya dicatat ke
   `DiagnosticLog`, tidak menampilkan error.
7. `download` hanya boleh dari `AVAILABLE`. Set `DOWNLOADING`, unduh ke
   `cacheDir/updates/`, perbarui progres, verifikasi, lalu rename dan simpan
   metadata `ready_*`. Sukses → `READY`; gagal → `ERROR` dan file dihapus.
8. `cancelDownload` membatalkan panggilan OkHttp, menghapus file parsial, dan
   kembali ke `AVAILABLE`.
9. `launchInstaller` hanya boleh dari `READY`. UI memeriksa izin
   `canRequestPackageInstalls()`; jika belum, UI membuka pengaturan izin.
   Jika sudah, controller membuat `content://` lewat `FileProvider` dan
   menjalankan intent installer Android. Status tetap `READY` karena installer
   bisa dibatalkan; file tetap disimpan untuk percobaan ulang.
10. `discardReady` menghapus file APK dan metadata `ready_*`, lalu kembali ke
    `AVAILABLE` (atau `UP_TO_DATE` bila ternyata tidak ada rilis lebih baru).
    Ini jalur pemulihan jika installer terus gagal atau pengguna ingin
    membatalkan unduhan yang sudah selesai.

**READY dipatok ke rilis yang sudah diunduh.** Selama `READY`, pemeriksaan
otomatis dan manual tidak dijalankan, sehingga tidak mungkin menampilkan versi
1.0.9 sementara file di disk masih 1.0.8. Pengguna memasang 1.0.8 lebih dulu;
pemeriksaan berikutnya akan menemukan 1.0.9 bila ada.

Status `DOWNLOADING` tidak dipulihkan setelah proses mati. File `.part` dihapus
saat `initialize`, dan pengguna mengunduh ulang. `READY` dipulihkan karena
file-nya utuh dan sudah diverifikasi.

### Unduhan

- Gunakan `OkHttpClient` tanpa autentikasi: connect 15 detik, read 60 detik,
  tanpa `callTimeout` agar unduhan besar tidak diputus.
- Unduh ke `cacheDir/updates/lintas-<versi>.apk.part`, verifikasi, lalu rename
  ke `.apk`. File `.part` tidak pernah diserahkan ke installer.
- Hapus file lama di direktori itu sebelum mulai.
- Periksa `cacheDir.usableSpace` sebelum mulai; sisakan margin 10 MB.
- Tulis dengan buffer 64 KB. Progres dihitung dari `bytesRead / total` dan
  diperbarui maksimal setiap 1% agar UI tidak terlalu sering recompose.
- Jika `Content-Length` tidak ada, gunakan `size` aset sebagai total. Jika
  keduanya tidak ada, tampilkan progres indeterminate.
- Batalkan dan hapus file jika koneksi putus, ukuran tidak cocok, atau
  checksum gagal.
- Unduhan memakai jaringan default. Saat VPN aktif, unduhan ikut melewati
  WARP; tidak ada upaya bypass khusus.
- **Unduhan berjalan selama proses aplikasi hidup.** Tidak ada service atau
  notifikasi unduhan baru. Jika pengguna berpindah aplikasi, unduhan biasanya
  tetap berjalan sampai Android menghentikan proses; jika proses dihentikan,
  file `.part` dibersihkan dan pengguna mengunduh ulang. UI menampilkan
  "Tetap buka Lintas selama mengunduh." Ini batasan yang diterima untuk rilis
  ini; unduhan latar belakang yang tahan proses butuh foreground service dan
  akan menambah scope.

### Pemasangan

- Izin `REQUEST_INSTALL_PACKAGES` ditambahkan ke manifest.
- APK yang sudah diverifikasi dibagikan lewat `FileProvider`
  (`androidx.core.content.FileProvider`) dengan authority
  `${applicationId}.updates`, `exported=false`, dan
  `grantUriPermissions=true`. Path yang dibagikan hanya
  `cacheDir/updates/`.
- UI memanggil intent installer dari Activity yang sedang terlihat:
  `Intent(ACTION_VIEW)` dengan MIME `application/vnd.android.package-archive`,
  URI dari `FileProvider`, dan `FLAG_GRANT_READ_URI_PERMISSION`. Tidak ada
  broadcast receiver atau peluncuran activity dari background.
- Jika `PackageManager.canRequestPackageInstalls()` false, UI menampilkan
  penjelasan dan tombol **Buka pengaturan** yang membuka
  `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` untuk package `app.lintas`.
  Setelah pengguna kembali dan izin diberikan, UI memanggil
  `launchInstaller` sekali lagi.
- Jika intent pengaturan tidak tersedia, buka pengaturan keamanan umum; jika
  tetap gagal, tampilkan instruksi manual.
- Jika tidak ada activity yang bisa menangani intent installer, tangkap
  `ActivityNotFoundException` dan tampilkan error; file tetap disimpan.
- Sebelum memulai installer, UI menampilkan konfirmasi berisi peringatan bahwa
  VPN akan terputus. Konfirmasi ini hanya muncul jika tunnel sedang
  `CONNECTED` atau `CONNECTING`.
- Aplikasi tidak mengetahui apakah pengguna menekan **Batal** atau installer
  gagal; keduanya ditangani UI sistem. File yang sudah diverifikasi tetap
  disimpan dengan status `READY` agar pengguna bisa mencoba lagi tanpa
  mengunduh ulang. Jika pembaruan berhasil, proses aplikasi dimatikan dan
  `initialize` berikutnya melihat `versionCode` baru lalu membersihkan file.
- File APK tidak dihapus tepat setelah intent diluncurkan karena installer
  membacanya secara asinkron. Pembersihan terjadi saat `initialize` berikutnya
  atau saat unduhan baru dimulai.

### UI

**Baris di Setelan.** Grup **Aplikasi** diubah menjadi:

- `Versi` — `BuildConfig.VERSION_NAME` (tidak lagi hardcoded), hint
  "Open source · GPL-3.0".
- `Pembaruan` — subjudul sesuai status:
  - "Belum diperiksa"
  - "Memeriksa..."
  - "Versi 1.0.7 adalah yang terbaru"
  - "Versi 1.0.8 tersedia"
  - "Mengunduh 42%"
  - "Siap dipasang"
  - "Gagal: <pesan>"
  - "Diperbarui ke 1.0.7"
- Aksi di kanan: **Cek**, **Lihat**, **Unduh**, **Pasang**, atau **Coba lagi**.
  Saat `READY`, aksi **Pasang**; tidak ada pemeriksaan ulang sampai pembaruan
  dipasang.

**Badge.** Saat status `AVAILABLE`, `DOWNLOADING`, atau `READY`, ikon tab
**Setelan** di bottom navigation memakai `BadgedBox` dengan titik kecil. Badge
hilang saat status kembali `UP_TO_DATE` atau `IDLE`.

**Dialog pembaruan.** Satu dialog dengan isi berbeda per status:

- Memeriksa: spinner + "Memeriksa pembaruan..."
- Tidak ada pembaruan: "Lintas sudah versi terbaru (1.0.7)." + **Tutup**
- Tersedia: judul versi, ukuran, catatan rilis maksimal 8 baris, tautan
  **Lihat di GitHub**, tombol **Nanti** dan **Unduh**
- Mengunduh: progress bar, persen, ukuran terunduh, catatan "Tetap buka Lintas
  selama mengunduh.", tombol **Batal**
- Siap: "Unduhan selesai dan sudah diverifikasi." + **Nanti** /
  **Hapus unduhan** / **Pasang**
- Izin kurang: penjelasan + **Buka pengaturan** / **Nanti**
- Error: pesan + **Coba lagi** / **Tutup**

Menutup dialog saat mengunduh tidak membatalkan unduhan; baris di Setelan tetap
menampilkan progres. Hanya tombol **Batal** yang membatalkan. Menutup dialog
saat `READY` tidak menghapus file; pengguna bisa membuka lagi dan memilih
**Pasang**.

Semua teks UI berbahasa Indonesia, mengikuti gaya yang sudah ada.

### Pemeriksaan otomatis

- `LintasApp` memanggil `UpdateController.initialize(context)` di
  `LaunchedEffect(Unit)`.
- Waktu percobaan terakhir disimpan di `lintas_update` (`last_attempt_at`).
  Pemeriksaan otomatis berjalan jika selisihnya minimal 24 jam.
- Hasil sukses disimpan (`latest_version`, `latest_tag`, `notes`, `page_url`,
  `apk_url`, `apk_size`, `apk_sha256`, `checked_at`), sehingga badge tetap ada
  setelah aplikasi ditutup.
- Pemeriksaan manual selalu berjalan, kecuali sedang `CHECKING`, `DOWNLOADING`,
  atau `READY`. Saat `READY`, tombol yang tersedia adalah **Pasang**.
- Tidak ada retry otomatis. Pengguna bisa menekan **Coba lagi**.

### Penanganan error

Pesan yang ditampilkan:

- Jaringan: "Tidak bisa menghubungi GitHub. Periksa koneksi internet."
- Rate limit: "Batas pemeriksaan GitHub tercapai. Coba lagi setelah HH:mm."
- Rilis tidak ada / 404: "Belum ada rilis publik."
- Tag tidak valid: "Format versi rilis tidak dikenal."
- Tanpa APK: "Rilis terbaru tidak menyertakan APK."
- Ruang kurang: "Ruang penyimpanan tidak cukup. Butuh sekitar 56 MB."
- Unduhan terputus: "Unduhan gagal. Coba lagi."
- Ukuran/checksum: "Unduhan tidak lengkap atau rusak."
- Package/versi: "APK bukan pembaruan yang cocok untuk Lintas."
- Tanda tangan: "APK ditandatangani dengan kunci berbeda. Pembaruan ditolak."
- Izin: "Izinkan Lintas memasang aplikasi, lalu coba lagi."
- Installer tidak tersedia: "Tidak ada aplikasi installer yang bisa membuka
  APK ini."

Semua kegagalan penting dicatat ke `DiagnosticLog` dengan kategori `UPDATE`.
Tidak ada URL yang memuat token, jadi aman dicatat. Path file boleh dicatat.

### Perubahan file

Baru:

- `app/src/main/java/app/lintas/update/UpdateApi.kt` — panggilan GitHub,
  parsing rilis, pemilihan aset.
- `app/src/main/java/app/lintas/update/UpdateVerifier.kt` — checksum,
  package, versi, sertifikat.
- `app/src/main/java/app/lintas/update/UpdateController.kt` — state machine,
  unduhan, peluncuran installer.
- `app/src/main/res/xml/update_paths.xml` — path `cacheDir/updates/` untuk
  `FileProvider`.
- `app/src/main/java/app/lintas/ui/UpdateDialog.kt` — dialog pembaruan.
- `app/src/test/java/app/lintas/update/UpdateApiTest.kt` — parsing tag, aset,
  perbandingan versi.
- `app/src/test/java/app/lintas/update/UpdateVerifierTest.kt` — helper murni
  (format ukuran, parsing digest) bila bisa dipisah dari Android.

Diubah:

- `app/build.gradle.kts` — `versionCode 17`, `versionName 1.0.7`,
  `buildFeatures { buildConfig = true }`.
- `app/src/main/AndroidManifest.xml` — izin `REQUEST_INSTALL_PACKAGES` dan
  deklarasi `FileProvider` (`exported=false`, `grantUriPermissions=true`).
- `app/src/main/java/app/lintas/ui/LintasApp.kt` — state updater, baris
  Setelan, badge, dialog, versi dinamis.
- `CHANGELOG.md`, `README.md`, dokumen ini.
- `dist/SHA256SUMS` saat rilis.

### Proses rilis

1. Pastikan tidak ada secret di riwayat git (keystore, token, `local.properties`).
2. Jadikan repo publik:
   `gh repo edit hafizhlf/lintas-vpn --visibility public --accept-visibility-change-consequences`.
3. Build, test, lint, tanda tangani dengan kunci rilis, zipalign, verifikasi
   `apksigner`, hitung SHA-256, perbarui `dist/SHA256SUMS`.
4. Push `main`, buat tag `v1.0.7`, buat GitHub Release dengan aset
   `lintas-1.0.7.apk` dan `SHA256SUMS`. Body rilis memuat SHA-256 seperti
   rilis sebelumnya.
5. Verifikasi dari luar:
   `curl -s https://api.github.com/repos/hafizhlf/lintas-vpn/releases/latest`
   mengembalikan `v1.0.7` dan aset APK dengan `digest`.

## Edge case

- **Repo masih private saat aplikasi memeriksa.** GitHub mengembalikan 404.
  Aplikasi menampilkan "Belum ada rilis publik." Ini tidak boleh terjadi pada
  rilis 1.0.7; repo harus sudah publik sebelum APK dibagikan.
- **Rilis lebih baru tetapi APK untuk arsitektur lain.** APK Lintas berisi
  `arm64-v8a` dan `x86_64`; tidak ada pemilihan ABI terpisah. Jika suatu saat
  ada APK per-ABI, updater harus memilih yang cocok. Untuk sekarang tidak.
- **Aset APK ganda.** Nama persis `lintas-<versi>.apk` menang; sisanya
  diabaikan.
- **Checksum tidak tersedia.** Lanjut dengan verifikasi tanda tangan dan versi.
- **Sertifikat berubah karena rotasi kunci.** Ditolak. Rotasi kunci di luar
  lingkup rilis ini.
- **Aplikasi di-uninstall saat unduhan selesai.** File cache ikut terhapus.
- **Pengguna menutup dialog saat mengunduh.** Unduhan berlanjut; progres tetap
  di baris Setelan.
- **Pengguna berpindah aplikasi saat mengunduh.** Unduhan berlanjut selama
  proses hidup. Jika Android menghentikan proses, file `.part` dibersihkan dan
  unduhan dimulai dari awal saat aplikasi dibuka.
- **Proses aplikasi mati saat mengunduh.** File parsial dihapus saat aplikasi
  dibuka lagi; unduhan dimulai dari awal.
- **Proses aplikasi mati saat installer terbuka atau saat pengguna di layar
  izin.** File `READY` utuh; `initialize` memverifikasi ulang dan memulihkan
  status `READY`. Pengguna tinggal menekan **Pasang** lagi.
- **Pengguna membatalkan installer.** UI sistem kembali ke Lintas; file tetap
  `READY` dan bisa dipasang lagi tanpa mengunduh ulang.
- **Installer gagal karena sebab sistem.** Pesan berasal dari UI sistem; file
  tetap `READY`. Pengguna bisa menekan **Hapus unduhan** untuk membuang file
  dan mengunduh ulang, atau memperbaiki penyebabnya (misalnya ruang
  penyimpanan) lalu menekan **Pasang** lagi.
- **VPN aktif saat pemasangan.** Android menghentikan aplikasi dan VPN. Setelah
  pembaruan, pengguna harus menyambung ulang. Peringatan ditampilkan sebelum
  installer dibuka.
- **Pembaruan dipasang saat aplikasi tidak dibuka.** Deteksi `versionCode`
  menampilkan "Diperbarui ke X" pada pembukaan berikutnya.
- **Izin pemasangan dicabut setelah unduhan.** UI meminta izin lagi; file yang
  sudah diunduh tetap dipakai.
- **GitHub rate limit.** Pesan khusus; pemeriksaan manual berikutnya bisa
  dicoba setelah jendela reset.
- **Tag dan `versionCode` tidak sinkron.** Ditolak setelah unduhan; rilis harus
  diperbaiki.
- **APK ditandatangani debug.** Ditolak jika aplikasi terpasang memakai kunci
  rilis; ini yang terjadi saat menguji di perangkat yang masih debug, jadi uji
  memakai debug-ke-debug.
- **Pengguna memasang APK rilis di atas build debug.** Android menolak karena
  tanda tangan berbeda. Ini batasan platform, bukan bug updater; perlu
  uninstall satu kali.

## Verifikasi

### Otomatis

- Unit test murni: parsing tag versi, perbandingan versi, pemilihan aset,
  parsing digest, format ukuran. Parsing JSON diuji dengan
  `testImplementation("org.json:json:20240303")` agar tidak bergantung pada
  stub `org.json` Android di JVM test; dependensi ini hanya untuk test.
- `lintDebug`, `testDebugUnitTest`, `assembleRelease`.
- `apksigner verify` dan `zipalign -c` pada APK rilis.

### Perangkat (Poco 2602BPC18G, Android 16)

1. Pasang debug 1.0.7 di atas debug 1.0.6 (kunci debug sama, data WARP tetap).
2. Buat rilis GitHub sementara `v1.0.8` berisi APK **debug** 1.0.8
   (`versionCode 18`) dan `SHA256SUMS`.
3. Buka Setelan → Pembaruan → Cek. Harus muncul "Versi 1.0.8 tersedia".
4. Unduh; pastikan progres berjalan dan checksum/tanda tangan lolos. Tekan
   Home sebentar lalu kembali; unduhan harus tetap berjalan atau, bila proses
   dihentikan, menunjukkan error yang jelas dan bisa diulang.
5. Setelah `READY`, paksa hentikan proses Lintas
   (`adb shell am force-stop app.lintas`), buka lagi. Status harus kembali
   `READY` dan file tidak diunduh ulang.
6. Tekan **Pasang**, lalu **Batal** di installer, kembali ke Lintas. Status
   harus tetap `READY`; tekan **Pasang** lagi harus membuka installer tanpa
   mengunduh ulang.
7. Konfirmasi installer pada percobaan kedua; aplikasi harus terpasang sebagai
   1.0.8 dan data tetap ada. Buka lagi; baris Pembaruan harus menampilkan
   "Diperbarui ke 1.0.8".
8. Uji **Hapus unduhan**: setelah mengunduh rilis uji berikutnya, tekan
   **Hapus unduhan**; status harus kembali `AVAILABLE` dan file hilang.
9. Uji penolakan tanda tangan: rilis sementara `v1.0.9` berisi APK kecil
   `app.lintas` versi 1.0.9 (`versionCode 19`) yang ditandatangani kunci debug
   lain. Aplikasi harus mengunduh, menolak sebelum installer dibuka, dan
   menampilkan pesan kunci berbeda.
10. Uji rate limit/offline dengan mode pesawat; pesan error harus jelas dan
    aplikasi tidak crash.
11. Hapus rilis sementara beserta tag-nya (`gh release delete --cleanup-tag`)
    dan APK uji.
12. Kembalikan perangkat ke build yang diinginkan. Jika ingin APK rilis 1.0.7,
    uninstall debug build lebih dulu; identitas WARP lokal akan hilang dan perlu
    registrasi ulang.

### Rilis

- Repo publik, rilis `v1.0.7` berisi APK rilis dan `SHA256SUMS`.
- `curl` publik mengembalikan rilis dan `digest` yang benar.
- SHA-256 APK dicatat di body rilis dan dijawab ke pengguna.

## Risiko

- **Updater rusak pada rilis pertama.** 1.0.7 adalah rilis pertama dengan
  updater. Jika updater di 1.0.7 tidak bisa memasang 1.0.8, pengguna harus
  memasang manual. Mitigasi: uji debug-ke-debug dengan rilis sementara sebelum
  merilis 1.0.7, dan jangan mengandalkan updater untuk perbaikan darurat pertama.
- **Repo publik memperlihatkan source dan riwayat.** Sudah dipilih pengguna.
  Sebelum publik, scan riwayat untuk keystore/token; tidak ada yang ditemukan
  saat desain ini ditulis.
- **Kuota GitHub.** Kecil untuk pemakaian normal; pesan rate limit disediakan.
- **Ukuran APK 56 MB.** Unduhan bisa lama di jaringan lambat; progres dan
  pembatalan disediakan.
- **Pemasangan memutus VPN.** Diberi peringatan; tidak ada reconnect otomatis.
