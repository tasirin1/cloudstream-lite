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
  `workflow_dispatch`, TANPA secret apa pun, menjalankan
  `./gradlew assemblePrereleaseDebug` dan mengunggah APK prerelease
  debug (tertanda debug key, bisa langsung dipasang untuk uji)
  sebagai artifact `cloudstream-prerelease-debug`.
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
