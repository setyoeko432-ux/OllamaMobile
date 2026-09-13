# Pelaksanaan Tahap 1 — Perbaikan Bug Kritis

Rencana ini berfokus pada perbaikan alur persetujuan edit, tombol "Uji Koneksi", penghentian generasi (Stop), dan pengelolaan streaming.

## User Review Required

> [!IMPORTANT]
> - Perubahan dari `HttpURLConnection` ke `OkHttp` akan memerlukan penambahan dependency di `build.gradle.kts`.
> - State machine baru akan mengubah cara UI merespons status asisten (misal: "Thinking", "Waiting for Approval", dll).

## Proposed Changes

### [Android] Dependency & Infrastructure

#### [MODIFY] [build.gradle.kts](file:///D:/OllamaMobile-Android/OllamaMobile/app/build.gradle.kts)
- Tambahkan dependency OkHttp: `com.squareup.okhttp3:okhttp:4.12.0`.

#### [MODIFY] [GatewayClient.kt](file:///D:/OllamaMobile-Android/OllamaMobile/app/src/main/java/com/ibra/ollamamobile/GatewayClient.kt)
- Ganti `HttpURLConnection` dengan `OkHttp`.
- Gunakan `OkHttpClient` dengan `Call` yang bisa dibatalkan secara eksplisit.
- Perbaiki parsing NDJSON agar lebih robust.

### [Android] State Machine & Logic

#### [MODIFY] [Models.kt](file:///D:/OllamaMobile-Android/OllamaMobile/app/src/main/java/com/ibra/ollamamobile/Models.kt)
- Tambahkan enum `GenerationState`: `Idle`, `Connecting`, `Generating`, `RunningTool`, `WaitingForApproval`, `ApplyingEdit`, `ResumingAgent`, `Completed`, `Cancelled`, `Failed`.
- Tambahkan metadata ke `ChatMessage` untuk menyimpan status state machine.

#### [MODIFY] [MainViewModel.kt](file:///D:/OllamaMobile-Android/OllamaMobile/app/src/main/java/com/ibra/ollamamobile/MainViewModel.kt)
- Implementasikan `generationState` StateFlow.
- Gunakan `StringBuilder` untuk akumulasi token.
- Implementasikan throttling (50-100ms) untuk pembaruan StateFlow selama streaming.
- Perbaiki `testConnection` agar dipanggil dengan benar dari UI menggunakan input form sementara.
- Perbaiki logic `respondToEdit` agar tidak menimpa konten yang sudah ada dan mendukung resume streaming.

### [Android] UI

#### [MODIFY] [MainActivity.kt](file:///D:/OllamaMobile-Android/OllamaMobile/app/src/main/java/com/ibra/ollamamobile/MainActivity.kt)
- Hubungkan `SettingsSheet` tombol "Uji Koneksi" ke `vm.testConnection(s)`.
- Perbarui UI asisten untuk menampilkan status dari state machine (misal: "Applying Edit...").

### [Gateway] Agent & NDJSON Consistency

#### [MODIFY] [server.py](file:///D:/OllamaMobile-Android/OllamaMobile/gateway/server.py)
- Pastikan semua endpoint NDJSON mengirimkan event terstruktur.
- Tambahkan validation untuk `edit_id` dan `action`.
- Pastikan agent loop tidak kehilangan konteks saat resume.

## Verification Plan

### Automated Tests
- Jalankan `gateway/tests.py` (jika ada) atau buat script test Python baru untuk memverifikasi endpoint `/edits`.
- Buat unit test sederhana di Android untuk `MainViewModel` (menggunakan mock GatewayClient jika memungkinkan).

### Manual Verification
1.  **Edit Approval:** Jalankan agent, minta edit file, verifikasi dialog muncul, klik Approve, pastikan agent berlanjut dan file benar-benar berubah.
2.  **Connection Test:** Buka settings, ubah URL ke yang salah, klik Uji Koneksi, pastikan status error muncul. Ubah ke URL benar, pastikan sukses.
3.  **Stop Generation:** Mulai streaming panjang, klik Stop, pastikan request berhenti seketika di gateway dan UI.
4.  **Streaming Throttling:** Verifikasi UI tetap responsif saat menerima token cepat.
