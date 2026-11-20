# Panduan pengelolaan repo (untuk AI)

> ⚠ **ATURAN NO. 1 — INI FORK, BUKAN REPO UPSTREAM.**
> Repo ini adalah fork milik owner: `tasirin1/tasirin-cloudstream`,
> branch aktif **`master`** (bukan `main`). Upstream-nya
> `recloudstream/cloudstream` (branch `master`). Push HANYA ke fork
> (`origin` = `tasirin1/tasirin-cloudstream`); JANGAN push ke upstream,
> JANGAN force-push, dan JANGAN menyinkronkan upstream tanpa perintah
> owner — sinkronisasi butuh penggabungan hati-hati, bukan
> rebase/reset buta.
>
> ⚠ **ATURAN NO. 2 — SECRET TIDAK PERNAH MASUK GIT.**
> Keystore rilis/prerelease, kata sandinya, dan API key layanan
> (SIMKL, TRAKT, MDL, MAL, ANILIST) hanya lewat GitHub Secrets /
> environment variable. Jangan pernah commit berkas keystore,
> `keystore_password.txt`, atau API key dalam bentuk apa pun.
>
> ⚠ **ATURAN NO. 3 — JANGAN BUAT EKSTENSI ILEGAL.**
> CloudStream secara bawaan TIDAK menyediakan sumber video; fungsi
> ditambah lewat ekstensi. Jangan membuat/memasukkan ekstensi yang
> menghosting media berhak cipta (sesuai peringatan `README.md`
> upstream soal DMCA/EUCD).

Baca file ini SEBELUM mengubah, memperbaiki, atau mengelola repository
ini. Panduan pemakaian umum ada di `README.md`; panduan migrasi
Compose/KMP ada di `COMPOSE.md`; kebijakan AI upstream ada di
`AI-POLICY.md` (penggunaan AI wajib dinyatakan di PR, kode wajib diuji
sebelum PR, dengarkan manusia di atas komputer).

**Memori terpusat:** repo ini terhubung ke repo memori pusat
`tasirin1/memori-agents-ai` (privat —
https://github.com/tasirin1/memori-agents-ai), sumber kebenaran
antar-sesi semua repo Tasirin. Awal sesi: `git pull --ff-only` di clone
lokal repo memori (di mesin Muse: `~/workspace/memori-agents-ai`),
lalu baca `SOUL.md` + `tasirin-cloudstream.md` di sana, plus
`git status --short` + `git log --oneline -5` di repo ini. Akhir sesi:
update `tasirin-cloudstream.md`, jalankan
`python3 scripts/check_memory.py` di repo memori, lalu commit + push
(`memory: tasirin-cloudstream - <ringkasan>`). Aturan lintas repo versi
kanoniknya ada di `SOUL.md` repo memori.

## Identitas repo

- **Apa**: CloudStream — media center / pemutar multimedia berbasis
  ekstensi dengan dukungan tracking, bookmark, Chromecast, HP + TV.
  Tanpa ekstensi, aplikasi tidak punya sumber konten.
- **Bahasa/stack**: Kotlin Multiplatform + Jetpack Compose /
  Compose Multiplatform. Kotlin 2.4.0, AGP 9.1.1, JDK 17.
- **Versi saat ini**: versionName `4.9.1`, versionCode `68`
  (di `gradle/libs.versions.toml` — sumber tunggal versi & SDK:
  minSdk 23, targetSdk 36, compileSdk 37). JANGAN ubah versi/SDK
  manual tanpa perintah owner; versi mengikuti upstream.
- **applicationId**: `com.lagradost.cloudstream3` — jangan diubah.
- **Clone kerja di mesin Muse**: `~/workspace/tasirin-cloudstream`.
- **Issues DIMATIKAN** di repo fork ini — temuan audit dicatat sebagai
  daftar bernomor di `tasirin-cloudstream.md` repo memori (judul,
  level, `file:baris`, dampak, skenario), bukan issue GitHub.

## Struktur repository

```
.
├── app/          # Aplikasi Android (com.lagradost.cloudstream3)
│   └── src/main/java/com/lagradost/cloudstream3/
│       ├── MainActivity.kt / CommonActivity.kt / CloudStreamApp.kt
│       ├── ui/           # Layar & fragment (UI lama + Compose bertahap)
│       ├── network/      # Lapisan jaringan
│       ├── plugins/      # Sistem ekstensi/plugin
│       ├── subtitles/    # Subtitle
│       ├── syncproviders/# Sinkronisasi/tracking (Simkl, Trakt, MAL, AniList, …)
│       ├── services/ receivers/ actions/ mvvm/ utils/ widget/
├── shared/       # Kode bersama KMP (commonMain/androidMain/jvmMain)
├── library/      # Library inti (API ekstensi) — ABI-nya dijaga CI
├── desktopApp/   # Aplikasi desktop (JVM)
├── docs/         # Modul dokumentasi (Dokka)
├── gradle/libs.versions.toml  # Katalog versi (termasuk versi app & SDK)
└── .github/workflows/
    ├── build-fork.yml      # MILIK FORK: build APK prerelease debug tanpa secret (lihat "CI fork")
    ├── pull_request.yml    # Build PR: checkKotlinAbi + assemblePrereleaseDebug + lint + check
    ├── prerelease.yml      # Upstream: push ke master → prerelease (DINONAKTIFKAN di fork)
    ├── build_to_archive.yml, instrumented-tests.yml,
    │   generate_dokka.yml, update_locales.yml
```

## Arsitektur ringkas

- **Sistem ekstensi adalah jantungnya**: penyedia konten adalah
  plugin/ekstensi yang dimuat aplikasi; `library/` memuat API yang
  dipakai ekstensi — perubahan API di sana berdampak ke SEMUA
  ekstensi.
- **Kompatibilitas biner dijaga**: CI menjalankan
  `library:checkKotlinAbi`. Kode publik baru di `library/` yang
  mengubah ABI wajib diberi anotasi `@Prerelease`, kalau tidak CI
  merah (lihat komentar di `pull_request.yml`).
- **Migrasi Compose/KMP sedang berjalan** (lihat `COMPOSE.md`):
  arsitektur tujuan **MVI** (satu state immutable + satu sealed event),
  hanya library yang kompatibel KMP (tanpa Java murni), UI Compose
  tidak boleh menghilangkan fitur UI lama.

## Kustomisasi fork

Belum ada. Fork masih identik dengan upstream per init
2026-11-19 (HEAD `2dcc25c3`). Setiap kustomisasi yang ditambahkan
WAJIB dicatat di tabel ini pada commit yang sama.

| Tanggal | Kustomisasi | Berkas |
|---|---|---|
| 2026-11-19 | `AGENTS.md` ini (panduan pengelolaan fork) | `AGENTS.md` |
| 2026-11-19 | Workflow build fork tanpa secret (APK prerelease debug sebagai artifact) + 3 workflow upstream dinonaktifkan manual (lihat "CI fork") | `.github/workflows/build-fork.yml` |
| 2026-11-19 | Log crash disalin ke `Download/cloudstream_last_error.txt` + penahan boot-loop 30 dtk di ExceptionHandler (audit STB sesi #4) | `app/.../CloudStreamApp.kt` |
| 2026-11-19 | Cast NavHostFragment aman: error inflate layout tidak lagi tertutup crash cast (temuan 1) | `app/.../MainActivity.kt` |
| 2026-11-19 | Deteksi TV cadangan via fitur leanback + tanpa layar sentuh (temuan 3); navigasi first-run menangkap Throwable (temuan 5) | `shared/.../compose/Layout.kt`, `app/.../MainActivity.kt` |
| 2026-11-19 | Hindari `Date.from`/`toInstant` (API 26) di provider sinkronisasi (temuan 6) | `app/.../syncproviders/providers/{Simkl,MAL,Kitsu}Api.kt` |
| 2026-11-19 | ABI splits: APK per-ABI hanya `armeabi-v7a` + `arm64-v8a`, tanpa universal — APK universal 92 MB karena native lib 4 ABI | `app/build.gradle.kts` |
| 2026-11-19 | Penangkap crash dipasang di `attachBaseContext` (sebelum provider/onCreate); log juga ke folder eksternal aplikasi `Android/data/<pkg>/files/cloudstream_last_error.txt` (tanpa izin) karena salinan Download gagal terbit di STB walau izin diberikan | `app/.../CloudStreamApp.kt` |
| 2026-11-19 | Mode STB lite `-PforkLite=true`: splits hanya armeabi-v7a, `resConfigs("en","in")`, buang `libgojni.so` (torrent); Fork Build membangun varian release-nya dan menandatanganinya dengan keystore sekali-pakai buatan CI → APK lite 36 MB, tanda tangan beda dari varian debug (tak bisa menimpa) | `app/build.gradle.kts`, `.github/workflows/build-fork.yml` |
| 2026-11-19 | **Jangan pasang provider Conscrypt bawaan di Android < 24**: BoringSSL-nya SIGABRT (`CRYPTO_once -> RAND_bytes -> abort`) di Android 6 STB B860H — terbukti 10/10 tombstone perangkat; perangkat < 24 pakai provider TLS sistem | `app/.../network/RequestsHelper.kt` (`installConscryptProvider`) |
| 2026-11-19 | Gambar latar pratinjau subtitle dikompres ulang 1920×1080 → 1280×720 q72: 1,66 MB → 0,11 MB (audit ringan sesi #11) | `app/src/main/res/drawable/subtitles_preview_background.jpg` |
| 2026-11-19 | Properti `-PforkLite` DIGANTI flavor produk `lite` (dimensi `edition`: `full`/`lite`): `resConfigs("en","in")` di flavor lite; buang `.so` per-varian via `androidComponents.onVariants` (libgojni + libconscrypt_jni); Fork Build memakai `assemblePrereleaseFullDebug` + `assemblePrereleaseLiteRelease` | `app/build.gradle.kts`, `.github/workflows/build-fork.yml` |
| 2026-11-19 | Dependensi khusus edisi full: nextlib (FFmpeg) + Zipline jadi `fullImplementation`; `FixedNextRenderersFactory` pindah ke `src/full`, edisi lite memakai factory berbasis `DefaultRenderersFactory` dengan nama kelas sama (tanpa FFmpeg — decode lunak nonaktif di lite) | `app/build.gradle.kts`, `app/src/full/.../FixedNextRenderersFactory.kt`, `app/src/lite/.../FixedNextRenderersFactory.kt` |
| 2026-11-19 | Font subtitle edisi lite dipangkas: 10 font pilihan (Lucida, STIX, Ubuntu, Poppins, Verdana, Futura, Consola, Open Sans, Times, Comic Sans) dioverride di `src/lite/res/font` dengan berkas font terkecil (Gotham, 56 KB) — nama tetap valid, hemat ±1,6 MB; yang asli penuh tetap: Trebuchet, Netflix Sans, Google Sans (Product Sans, juga font UI) | `app/src/lite/res/font/*.ttf` |
| 2026-11-19 | Compose `ui-tooling` dikeluarkan dari semua konfigurasi (artefak khusus pengembangan/IDE preview; `ui-tooling-preview` tetap karena memuat anotasi `@Preview` yang dirujuk kode) | `app/build.gradle.kts` |
| 2026-11-19 | **Deteksi update APK dibuang**: `InAppUpdater` dikosongkan jadi rintisan inert — aplikasi fork TIDAK PERNAH lagi mengecek rilis GitHub upstream / mengunduh / menawarkan pasang APK upstream (tanda tangan & versi fork berbeda; APK upstream akan menimpa perbaikan fork). `runAutoUpdate` selalu `false`, `installPreReleaseIfNeeded` no-op; UI pengaturan tetap kompilasi. Updater EKSTENSI (`ApkUpdater`) mekanisme terpisah, TIDAK disentuh | `app/.../utils/InAppUpdater.kt` |
| 2026-11-19 | Nama aplikasi diganti mengikuti pola repo aplikasi owner lainnya (Tasirin Music, Tasirin Cast): `app_name` = **"Tasirin CloudStream"**; override prerelease "CloudStream Beta" dihapus agar mewarisi nama yang sama. Nama folder unduhan (`app_name_download_path` = "Cloudstream") SENGAJA tidak diubah agar unduhan lama tidak yatim. Perintah owner juga: rilis GitHub diterbitkan sebagai rilis penuh, tanpa label pre-release | `app/src/main/res/values/strings.xml`, `app/src/prerelease/res/values/strings.xml` (dihapus) |
| 2026-11-19 | **Maraton audit gelombang 2-4 (sesi #14)**: CancellationException tak lagi ditelan (`safe*`, ParCollections); peta plugin dibaca lewat kunci yang sama dengan tulisannya; update manual plugin tak lagi menghapus berkas lama sebelum pengganti terunduh; hash plugin terverifikasi unduhan dicek ulang saat load (file tertukar ditolak); token OAuth dienkripsi AES-GCM via Android KeyStore (+ migrasi plaintext); refresh token diserialkan per repo; cache akun dimuat ulang saat ganti profil; IV HLS & tanggal & UnshortenUrl diperbaiki; unduhan HLS hanya sukses bila semua segmen tertulis; receiver PiP & looper torrent diatomikkan; 8 berkas mati + blok terkomentar besar dihapus; cek `BuildConfig.FLAVOR` memakai awalan sejak dimensi `edition`. Keputusan tercatat: #12 (timeout/cache klien bawaan diterima — bawaan OkHttp waras) & #48 (gson/fuzzywuzzy dipertahankan demi ekstensi lama) tanpa perubahan kode | banyak berkas di `app/`, `library/`, `shared/` (lihat riwayat git sesi #14) |
| 2026-11-19 | **Maraton audit menyeluruh (temuan sesi #13)**: #1 player menyimpan & memulihkan default TLS proses di `releasePlayer()` (tak lagi buta TLS seumur aplikasi); #3 WebViewResolver hanya menoleransi sertifikat kedaluwarsa/tak-terpercaya di host target, host lain dibatalkan; #5 unduhan berkas + segmen/playlist/kunci HLS diverifikasi TLS-nya; #25 IV HLS di-decode sebagai hex + IV bawaan = nomor urut media (RFC 8216); #42/#44 receiver PiP: action bernama-ruang, `RECEIVER_NOT_EXPORTED`, extra dibatasi rentang enum, instance lama dilepas sebelum diganti. Keputusan tercatat (bukan perubahan kode): #2 `insecureApp` dipertahankan demi ABI ekstensi (kode ekstensi memang sudah tepercaya penuh; tak ada pemakaian internal sensitif); #4 `usesCleartextTraffic` dipertahankan (ekosistem sumber ekstensi banyak yang HTTP murni; mematikannya memutus fungsi inti) | `app/.../ui/player/CS3IPlayer.kt`, `library/.../network/WebViewResolver.android.kt`, `app/.../utils/downloader/DownloadManager.kt`, `library/.../utils/M3u8Helper.kt`, `app/.../ui/player/{PlayerView,IPlayer}.kt` |
| 2026-11-19 | **Maraton audit ke-2 (temuan sesi #15, 21 temuan Kritikal+Tinggi)**: bom waktu `FillerEpisodeCheck.calc()` dibuang (aplikasi membeku 6–10 mnt sesudah dibuka); cek segmen HLS dipindah ke `downloadHLS` yang benar (unduhan file tunggal tak lagi selalu gagal); timeout `withTimeout` di dalam `safeApiCall` jadi kegagalan, bukan exception lolos; relaksasi TLS player dijaga refcount bersama; cek SSL WebView membaca seluruh set error; `loadLinksPrev` benar-benar mundur; jangkar hash plugin disimpan juga oleh jalur unduh UI baru + dikunci + tak lagi fail-open senyap; registrasi plugin di-rollback bila `load()` gagal; job `MultiActiveQuery` di-parent ke job pemanggil; Cancel update-semua benar-benar membatalkan unduhan; WebView resolver selalu menghancurkan WebView di `finally` | banyak berkas di `app/`, `library/`, `shared/` (lihat riwayat git sesi #16) |
| 2026-11-19 | **LITE-ONLY (keputusan owner)**: dimensi flavor `edition` dihapus — varian full + arm64 hilang, tinggal satu build = bekas lite: ABI hanya `armeabi-v7a`, `resConfigs("en","in")` di `defaultConfig`, pengecualian `libgojni.so`/`libconscrypt_jni.so` berlaku semua varian, nextlib (FFmpeg) + Zipline dibuang (FFmpeg dipasang lagi HANYA bila error putar terbukti akibat codec), `FixedNextRenderersFactory` versi lite + 10 font rintisan pindah ke `src/main`, `src/full` & `src/lite` dihapus. Fork Build kini membangun `assemblePrereleaseRelease` saja dan rilis berisi 1 APK (`cloudstream-stb-lite.apk`) | `app/build.gradle.kts`, `app/src/main/...`, `.github/workflows/build-fork.yml` |
| 2026-11-19 | **Maraton audit ke-2 lanjutan (sesi #16, sesudah rilis v4.9.8)**: #22 unduhan file tunggal yang berakhir di bawah total ukuran yang diketahui kini gagal (bukan sukses semu); #23 resume dibatalkan bila berkas remote menyusut (hindari berkas campuran korup); #38 kegagalan di tengah `loadOnlinePlayer` memulihkan default TLS proses; #86 langkah publish rilis Fork Build idempoten (upload `--clobber` bila rilis sudah ada). Sisa antrean sesi #15 diverifikasi satu per satu: 3 diterima tercatat (#24/#48/#119), 90 dibatalkan dengan bukti (klaim sub-agen tidak ada di kode), #87 tertutup oleh LITE-ONLY | `app/.../utils/downloader/DownloadManager.kt`, `app/.../ui/player/CS3IPlayer.kt`, `.github/workflows/build-fork.yml` |
| 2026-11-19 | **Updater Compose dicabut (laporan owner: popup "Pembaruan ditemukan" berisi commit upstream masih muncul)**: jalur update kedua bawaan layar Compose — `GithubViewModel` + `GithubReleases` (menunjuk `recloudstream/cloudstream`, tag `pre-release`) + `ApkUpdater` — luput dari penonaktifan `InAppUpdater` sesi #12 dan tetap otomatis mencari update tiap aplikasi dibuka, menawarkan APK prerelease upstream yang akan menimpa fork. `MainActivityScreen.githubViewModel()` kini selalu mengembalikan `null` (semua pemanggil sudah null-guard): auto-search, dialog update, dan cek manual di layar pengaturan update mati | `app/src/main/java/com/lagradost/cloudstream3/MainActivityScreen.kt` |
| 2026-11-19 | **Google Cast DICABUT TOTAL (perintah owner, proyek pelangsingan)**: dependensi `media3-cast` dibuang dari bundle; berkas khusus Cast dihapus (`CastHelper`, `CastOptionsProvider`, `ControllerActivity`, `MiniControllerFragment`, layar/fragment subtitle Chromecast, menu controller, layout subtitle Chromecast); `MainActivity` tak lagi memasang `SessionManagerListener`/`CastContext`; `isCastApiAvailable()`/`isConnectedToChromecast()` di `AppContextUtils` kini selalu `false` (pemanggil upstream tak berubah, semua jalur cast jadi jalan buntu yang aman dan putar lokal tak tersentuh); dua overload `startChromecast` + cabang aksi episode Chromecast di `ResultViewModel2` dihapus; tombol media-route di layar hasil diganti `View` polos selalu `gone`; entri manifest `ControllerActivity` + meta-data `OPTIONS_PROVIDER_CLASS_NAME` dan preferensi subtitle Chromecast dibuang; ikon notifikasi `SubscriptionWorkManager` diganti ikon sync aplikasi. FCast & Web Video Cast (bukan Google Cast) TIDAK disentuh | 24 berkas di `app/` + `gradle/libs.versions.toml` |
| 2026-11-19 | **R8 dihidupkan (perintah owner, proyek pelangsingan)**: build rilis kini `isMinifyEnabled + isShrinkResources = true`. `proguard-rules.pro` (sebelumnya kosong) diisi aturan jaga: SELURUH `com.lagradost.**` dipertahankan nama + isinya (ekstensi adalah APK terpisah yang menaut ke API aplikasi berdasarkan nama — diobfuscate/dibuang = semua ekstensi patah), metode `native` dipertahankan (JNI QuickJS), extractor Media3 yang diprobe reflektif (midi/flac) dipertahankan, kreator Parcelable + atribut serialisasi dijaga. Yang menyusut penuh = pustaka pihak ketiga (Compose/Media3/OkHttp/Jackson) + resource tak terpakai | `app/build.gradle.kts`, `app/proguard-rules.pro` |
| 2026-11-20 | **FCast + WebVideoCast dicabut (pilihan owner #1 dari inventaris pelangsingan)**: paket aksi `actions/temp/fcast/` (FcastAction/FcastManager/FcastSession/Packets) + `WebVideoCastPackage.kt` dihapus, inisialisasi `FcastManager` di `MainActivity` dibuang, dua entri aksi di `VideoClickAction` dicabut. STB memang menempel di TV sehingga cast dari STB tak berguna | `actions/temp/fcast/`, `actions/temp/WebVideoCastPackage.kt`, `MainActivity.kt`, `actions/VideoClickAction.kt` |
| 2026-11-20 | **Mesin torrent dicabut (pilihan owner #2)**: dependensi `torrentserver` (server Go/libgojni) + `ui/player/Torrent.kt` dihapus; cabang TORRENT/MAGNET di `CS3IPlayer.loadOnlinePlayer` kini jalan buntu sopan (galat `torrent_not_supported`), `loadTorrent`/`torrentEventLooper`/dialog persetujuan torrent dibuang; aksi eksternal LibreTorrent + BiglyBT dicabut dari `VideoClickAction`. Tipe API ekstensi `TvType.Torrent`/`TorrentLoadResponse` DIPERTAHANKAN (ekstensi boleh tetap mengembalikan konten torrent; hanya tak bisa diputar) | `ui/player/Torrent.kt`, `ui/player/CS3IPlayer.kt`, `CommonActivity.kt`, `actions/VideoClickAction.kt`, `app/build.gradle.kts`, `gradle/libs.versions.toml` |
| 2026-11-20 | **Sync akun luar dicabut (pilihan owner #3)**: provider akun `AniListApi`/`MALApi`/`KitsuApi`/`SimklApi` dihapus beserta registrasi di `AccountManager` (`syncApis`/`allApis` tinggal `LocalList`), entri login di `SettingsAccount` + `SettingsAccountScreen`, kunci cache backup-nya, `SyncUtil`, dan pengaitnya di `SyncViewModel` (`addFromUrl` jadi no-op). DIPERTAHANKAN: subtitle online (OpenSubtitles/SubDl/SubSource/Addic7ed — pilihan owner), `LocalList`, objek `Kitsu` (lookup metadata episode tanpa akun; diekstrak utuh ke `providers/Kitsu.kt`), dan prefix id (`mal`/`kitsu`/`anilist`/`simkl`) sebagai literal di `AccountManager.initMainAPI()` karena ekstensi tetap mengirim id itu di `syncData` dan fitur lokal (deteksi duplikat, FillerEpisodeCheck, Kitsu) memakainya | `syncproviders/`, `ui/settings/SettingsAccount*.kt`, `ui/result/SyncViewModel.kt`, `ui/result/ResultViewModel2.kt`, `utils/BackupUtils.kt`, `utils/SyncUtil.kt` |
| 2026-11-20 | **R8 diperbaiki: `-dontobfuscate` + keep permukaan pustaka untuk plugin (regresi v4.9.10/v4.9.11)**: plugin dari repo luar gagal dimuat semua (`Unable to load plugin`) karena R8 mengganti nama kelas pustaka yang ditaut plugin berdasarkan nama (Kotlin stdlib, OkHttp, Jsoup, Jackson, Gson, Rhino, anotasi JetBrains) — terbukti dari diff himpunan kelas yang dirujuk 20 plugin repo nonton-indo vs APK v4.9.8 (utuh) dan v4.9.11 (72 kelas hilang/ganti nama). Aturan sekarang: obfuskasi DIMATIKAN total dan paket-paket itu dijaga dari penyusutan; penyusutan tetap berlaku untuk sisanya | `app/proguard-rules.pro` |
| 2026-11-20 | **Audit-3 #1: jangkar hash plugin disimpan SEBELUM load di jalur Compose** — urutan lama (store sesudah load) membuat loadPlugin membandingkan berkas baru dengan jangkar versi lama, jadi update plugin via layar Ekstensi selalu gagal "Unable to load plugin" dan rusak permanen; sekarang sama seperti jalur lama (store dulu, baru load) | `ui/settings/extensions/AndroidRepositoryManager.kt` |
| 2026-11-20 | **Audit-3 #2: digest plugin yang diberikan repo tapi tak bisa diverifikasi kini MENGGAGALKAN unduhan** — algoritma digest tak dikenal membuat MessageDigest null dan transfer lolos tanpa cek sama sekali; fileHash tak terparse menuruhkan digest jadi null diam-diam. Keduanya sekarang error eksplisit. (Kasus repo tanpa hash sama sekali tetap diizinkan demi kompatibilitas repo lama.) | `ui/settings/ApkUpdater.kt`, `ui/settings/extensions/AndroidRepositoryManager.kt` |
| 2026-11-20 | **Audit-3 #4: pengambilan master playlist M3U8 tidak lagi `verify = false`** — satu-satunya titik tanpa verifikasi sertifikat di library; playlist yang menentukan semua URL varian/segmen (putar + unduh) kini diambil dengan klien terverifikasi | `library/.../utils/M3u8Helper.kt` |
| 2026-11-20 | **Audit-3 #5: backup tidak lagi melaporkan sukses palsu** — PrintWriter menelan IOException; kini `checkError()` diperiksa sesudah tulis dan kegagalan melempar ke jalur toast gagal, bukan toast sukses di atas berkas kosong/korup | `utils/BackupUtils.kt` |
| 2026-11-20 | **Audit-3 #3: relaksasi TLS proses-wide di player DICABUT TOTAL** — blok setDefault HttpsURLConnection trust-all selama pemutaran (residu audit #1/sesi 15 #4) dihapus beserta mesin refcount/restore dan `SSLTrustManager.kt`; jalur online tak pernah memakainya (OkHttp terverifikasi/Cronet), satu-satunya konsumen (fallback HTTP sumber offline) kini memakai klien OkHttp insecure TERSKOP di sumber itu saja | `ui/player/CS3IPlayer.kt`, `ui/player/SSLTrustManager.kt` (dihapus) |

| 2026-11-20 | **Audit-3 #6–#24 (tingkat Sedang) diperbaiki**: unduhan HLS menyimpan progres segmen tiap 10 segmen (bukan tiap segmen) + buffer RAM unduhan 50MB→16MB; BackupWorkManager menunggu job backup; cache PreferenceDelegate diinvalidasi sesudah restore; unduhan gagal sopan bila tak ada profil Download; URL repo http:// ditingkatkan ke https://; tambah repo dari tautan/WebView wajib konfirmasi dialog; plugin lokal dari penyimpanan bersama hanya dimuat sesudah opt-in pengguna (Pengaturan Umum > Ekstensi, bawaan mati); PluginManager: penjaga duplikat di bawah kunci sebelum instansiasi + peta jangkar hash di-cache & berkas tak berubah melewati hash ulang; LibraryFragment melepas mediator/listener tab lama + penjaga indeks halaman; executor Cronet dipakai bersama (tak lagi bocor 1 thread per video); parser HLS menoleransi atribut numerik rusak; Userload/Evoload/Tantifilm diperkeras terhadap perubahan format remote; 4 baris akun mati di settings_account.xml dibuang; mesin updater GitHub Compose mati (~600 baris) dihapus, AppUpdater/DigestPair yang masih hidup dipindah utuh ke berkas sendiri | banyak berkas; rincian per temuan di riwayat commit `Audit-3 #N` |

| 2026-11-20 | **Audit-3 #25–#56 (tingkat Rendah) diperbaiki**: listener gestur dilepas di onPause; cabang Chromecast mati + header torrent yatim dibuang; cast aman ViewPager2; badan probe Range ditutup; paket resume dihapus sesudah unduhan selesai; service unduhan stopSelf; getKey mengembalikan nilai bawaan saat parse gagal; penjaga durasi nol di setViewPosAndResume; filter backup cocok persis/prefix folder (bukan substring); stream restore ditutup; query SAF exists() ganda dibuang; UI ekstensi lama mati + CloudflareKiller dihapus (RepoAdapter TERNYATA masih hidup di SetupFragmentExtensions — dipertahankan); Filegram mati dihapus, DoodLiExtractor didaftarkan, parser label kualitas disatukan, overload AES mati dibuang, 94 regex konstan diangkat ke level berkas (pola terverifikasi identik byte-per-byte); Rabbitstream/Streamlare diperkeras; entri update inert dibuang dari dua layar pengaturan; string/drawables/manifest/catalog yatim dibersihkan (117 entri string torrent di semua locale). DITERIMA tanpa patch (alasan di memori sesi #21): #29 notifyDataSetChanged disengaja, #31 mutex jeda per-instance, #40 token di SharedPreferences polos, #41 toleransi SSL_UNTRUSTED WebViewResolver | banyak berkas; rincian per temuan di riwayat commit `Audit-3 #N` |
| 2026-11-20 | **Audit-4 #1: resume HLS tidak lagi menggandakan segmen sesudah proses mati mendadak** — titik resume kini pasangan (jumlah segmen, panjang byte berkas) yang diganti sebagai satu kesatuan dari dalam kunci tulis unduhan; saat resume, panjang berkas diverifikasi terhadap titik itu — ketidakcocokan (ekor kelebihan segmen karena mati mendadak, atau entri lama format hitungan-saja) membuat berkas dimulai ulang dari nol alih-alih ditambahi segmen dobel di ekornya; jeda/stop normal tetap resume penuh karena `close()` menyimpan titik akhir yang persis | `app/.../utils/downloader/DownloadManager.kt` |
| 2026-11-20 | **Audit-4 #2: collector antrean unduhan kini terikat ke masa hidup service + `popQueue` atomik** — job collector disimpan (field + penunjuk bersama antar-instance), dibatalkan di `onDestroy` dan oleh instance penerus saat `onCreate`; sebelumnya job hanya val lokal yang tak bisa dibatalkan dan satu-satunya remnya flag bersama `isRunning`, sehingga sesudah restart START_STICKY dua collector bisa mem-`pop` episode yang sama dan mengunduhnya dua kali ke berkas yang sama. `popQueue` kini mengklaim item pertama secara atomik (`getAndUpdate`) | `app/.../services/DownloadQueueService.kt`, `app/.../utils/downloader/DownloadQueueManager.kt` |
| 2026-11-20 | **Audit-4 #3: perintah notifikasi unduhan tidak lagi bisa hilang oleh `stopSelf()` (regresi Audit-3 #34)** — service kini berhenti (`stopSelf(startId)`) dari dalam coroutine SESUDAH perintah diteruskan ke `downloadEvent`; sebelumnya `stopSelf()` sinkron + `onDestroy` yang membatalkan scope bisa membatalkan perintah sebelum sempat jalan di perangkat sibuk. `stopSelf()` tanpa coroutine dipertahankan hanya untuk start tanpa perintah | `app/.../services/VideoDownloadService.kt` |
| 2026-11-20 | **Audit-4 #4: cache jangkar hash plugin tidak lagi bisa menimpa jangkar lain (regresi Audit-3 #15)** — inisialisasi malas cache kini di bawah kunci yang sama dengan penulisan, dan `storePluginHash` membangun di atas bacaan prefs SEGAR di dalam kunci (bukan snapshot cache), membatalkan store bila bacaan melempar — persis penjaga pra-Audit-3 #15. Cache tetap dipakai untuk jalur baca (load plugin) | `app/.../plugins/PluginManager.kt` |
| 2026-11-20 | **Audit-4 #5: kebijakan digest plugin diselaraskan — fail-closed dinyatakan eksplisit, KDoc lama "abaikan kegagalan digest" dicabut** — KDoc `transfer()` dulu menyatakan kegagalan digest sengaja diabaikan, bertabrakan dengan Audit-3 #2 yang melempar `DigestException`; kini satu kebijakan tertulis: digest yang diberikan tapi tak bisa diverifikasi (algoritma tak dikenal / komputasi gagal / tak cocok) MENGGAGALKAN transfer, karena nama algoritma berasal dari repo itu sendiri sehingga mengabaikannya = jalan pintas memintas verifikasi. `safe{}` penelan di `md.update`/`md.digest` dibuang; `DigestPair.parse` menoleransi spasi liar di nama algoritma/hex agar repo ceroboh tidak memblokir semua pluginnya | `app/.../ui/settings/ApkUpdater.kt`, `shared/.../viewmodels/AppUpdater.kt` |
| 2026-11-20 | **Audit-4 #6: URL daftar plugin + URL berkas plugin di dalam manifes repo kini ikut naik ke HTTPS** — Audit-3 #11 hanya menaikkan URL entri repo; entri `pluginLists` dan `SitePlugin.url` dipakai apa adanya padahal cleartext diizinkan global, sehingga MITM bisa memalsukan daftar + hash secara konsisten dan verifikasi hash lolos untuk berkas jahat. Helper `upgradeToHttps` kini dipakai di kedua jalur ambil (legacy `RepositoryManager` + Compose `AndroidRepositoryManager`) | `app/.../plugins/RepositoryManager.kt`, `app/.../ui/settings/extensions/AndroidRepositoryManager.kt` |
| 2026-11-20 | **Audit-4 #7: target redirect shortcode repo kini ikut naik ke HTTPS** — cabang kode pendek (cutt.ly/py.md) di `parseRepoUrl` mengembalikan header `Location` mentah dan melewati upgrade HTTPS cabang URL langsung, sehingga repo yang ditambahkan via kode pendek bisa tersimpan sebagai HTTP permanen; kini target redirect melewati `upgradeToHttps` yang sama | `app/.../plugins/RepositoryManager.kt` |
| 2026-11-20 | **Audit-4 #8: ukuran unduhan plugin kini dibatasi + `fileSize` manifes ditegakkan** — sebelumnya `fileSize` hanya dipakai tampilan dan kedua jalur unduh menyalin sampai EOF tanpa batas, sehingga repo jahat/terkompromi (atau MITM) bisa mengalirkan data raksasa ke cacheDir sampai penyimpanan STB penuh. Kini: plafon keras 100 MB (`MAX_PLUGIN_FILE_SIZE_BYTES`) di jalur legacy (cek Content-Length + hitung byte saat menyalin) dan di `ApkUpdater.transfer` (plafon yang sama + ukuran terdeklarasi ditegakkan saat menyalin) | `app/.../plugins/RepositoryManager.kt`, `app/.../ui/settings/ApkUpdater.kt` |
| 2026-11-20 | **Audit-4 #9: backup terjadwal tidak lagi selalu tercatat sukses** — `BackupUtils.backup()` menelan semua kegagalan jadi toast, dan `BackupWorkManager` (yang sejak Audit-3 #8 sudah menunggu job-nya) tetap mengembalikan `Result.success()` tanpa syarat. Inti backup kini `runBackup(): Boolean`; jalur terjadwal memakai `backupAndAwait()` dan mengembalikan `Result.failure()` bila backup gagal; pemanggil UI manual tidak berubah | `app/.../utils/BackupUtils.kt`, `app/.../services/BackupWorkManager.kt` |
| 2026-11-20 | **Audit-4 #10: counter unduhan tidak lagi balapan** — `bytesDownloaded`/`bytesWritten`/`hlsProgress`/`hlsWrittenProgress` kini `@Volatile` (cegah bacaan 64-bit sobek di perangkat 32-bit) dan semua mutasi majemuk (`+=`, pasangan laju `notify()`) dijaga satu `counterLock`; `notify()` mengambil snapshot konsisten di dalam kunci lalu menjalankan callback di luar kunci. Sebelumnya update yang hilang bisa membuat cek kelengkapan akhir memvonis unduhan utuh sebagai gagal (komentar "TODO MUTEX?" di kode kini terjawab) | `app/.../utils/downloader/DownloadManager.kt` |
| 2026-11-20 | **Audit-4 #12: badan respons segmen/kunci HLS kini selalu ditutup** — `bytes()` lalu `close()` tanpa perlindungan membuat koneksi bocor dari pool OkHttp tiap kali `bytes()` melempar (respons terpotong di tengah); kedua titik kini `try/finally` | `library/.../utils/M3u8Helper.kt` |

## Cara kerja (aturan owner, berlaku di semua repo Tasirin)

- **Build penuh hanya lewat CI.** Jangan install SDK/toolchain
  lokal; lokal hanya edit + cek sintaks ringan + `git diff --check`.
  Jangan klaim "pasti jalan" sebelum CI hijau; baca pesan kegagalan
  aktual CI, bukan cuma status merah/hijau. Run `cancelled` karena
  `concurrency` (prerelease memakai cancel-in-progress) BUKAN gagal.
- **"audit / cari bug / cek" = mode baca-saja**: kumpulkan temuan
  dengan `file:baris`, dampak ke user, skenario pemicu, dan level
  (Kritikal/Tinggi/Sedang/Rendah). Karena Issues mati, temuan
  terverifikasi dicatat sebagai daftar bernomor di berkas memori
  proyek — satu temuan satu nomor, sebelum ada patch.
- **"perbaiki / fix / lanjutkan" = maraton**: satu temuan satu
  perbaikan akar masalah (bukan gejala), patch minimal mengikuti gaya
  kode sekitar, satu commit satu tujuan. Build/CI gagal didahulukan
  sebelum temuan berikutnya. Urutan antrean: level tertinggi dulu,
  lalu nomor tertua.
- **Klaim sub-agen wajib diverifikasi sendiri ke kode** sebelum
  diperbaiki; klaim yang tak terbukti dicatat "dibatalkan + bukti".
- **Perubahan besar = dua langkah**: push dulu sebagai uji CI, baru
  langkah rilis sesudah hijau.
- Gaya commit mengikuti upstream (Inggris, ringkas, mis.
  `docs: ...`); commit khusus panduan/memori boleh Indonesia.
  Perubahan hanya dokumen `.md` tambahkan `[skip ci]` bila perlu
  menghindari build yang tidak relevan.

## CI fork

- **Build resmi fork = workflow `Fork Build`**
  (`.github/workflows/build-fork.yml`, kustomisasi fork 2026-11-19):
  jalan pada push ke `master` (selain `*.md`/`*.json`) dan
  `workflow_dispatch`, TANPA secret apa pun. Sejak keputusan
  LITE-ONLY (2026-11-19) hanya menjalankan
  `./gradlew assemblePrereleaseRelease` + tanda tangan kunci
  sekali-pakai CI (artifact `cloudstream-stb-lite`: satu APK
  armeabi-v7a).
- **Aturan rilis (perintah owner 2026-11-19)**: rilis GitHub
  diterbitkan sebagai **rilis penuh** (BUKAN pre-release), dan
  nomornya berjalan menaik dari basis upstream: `v4.9.1`, `v4.9.2`,
  … `v4.9.100`, dst. — komponen versi TIDAK BOLEH menyentuh 1000
  (di 999 naikkan minor: `4.9.999` → `4.10.0`). Skema lama
  `v4.9.1-fork.N` berhenti di fork.5; rilis pertama skema baru =
  `v4.9.6`.
- **Rilis OTOMATIS (perintah owner 2026-11-19)**: cukup push tag
  `v4.9.*` — workflow Fork Build membangun lalu menerbitkan rilis
  penuh berisi 1 APK lite dari build yang sama (langkah "Publish
  GitHub release", hanya jalan di build tag; sebelum LITE-ONLY
  isinya 3 APK). JANGAN lagi unduh artifact dan unggah manual.
  Push branch biasa tetap hanya artifact uji.
- Tiga workflow upstream **DINONAKTIFKAN manual** di fork ini
  (GitHub Actions settings, berkasnya tidak diubah): `Pre-release`,
  `Archive build`, `Dokka`. Ketiganya bergantung pada secrets
  organisasi upstream (`GH_APP_ID`/`GH_APP_KEY` + repo privat
  `recloudstream/secrets` / `recloudstream/dokka` /
  `recloudstream/cloudstream-archive`) yang TIDAK ada di fork, jadi
  pasti gagal di langkah token/keystore. Jangan aktifkan lagi tanpa
  menyediakan pengganti secrets-nya; kegagalan ketiganya adalah
  infrastruktur, BUKAN bukti kode rusak.
- Vonis kualitas kode bersandar pada `Fork Build` dan
  `pull_request.yml` (Artifact Build: `checkKotlinAbi` + assemble +
  lint + check).
- Workflow mengabaikan perubahan `*.md` (`paths-ignore`), jadi
  commit dokumen saja tidak memicu build.

## Verifikasi sebelum push

1. `git diff --check` bersih.
2. Tidak ada secret/keystore ikut ter-stage (`git status` dibaca).
3. Perubahan API `library/` → sadar uji `checkKotlinAbi` CI;
   tambahkan `@Prerelease` bila ABI berubah.
4. Sesudah push: cek run CI yang relevan dan baca pesan kegagalan
   aktualnya bila merah (kecuali kegagalan prerelease karena secrets
   upstream di atas — laporkan apa adanya ke owner).
