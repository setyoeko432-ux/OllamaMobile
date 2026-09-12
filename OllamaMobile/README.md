# Ollama Mobile — Android + gateway lokal

Aplikasi Android native (Kotlin + Jetpack Compose) untuk chat dengan Ollama melalui Wi‑Fi/hotspot. Model dibaca otomatis dari `GET /api/tags`. Tool calling memakai `read_file` dan `edit_file` dalam folder sandbox di komputer.

## Arsitektur

`Android → gateway:8765 → Ollama:11434`

Gateway diperlukan karena `localhost` di Android adalah ponsel, dan file komputer tidak bisa dibaca langsung oleh aplikasi. Gateway hanya mengizinkan file di folder workspace, menolak path traversal, membatasi pembacaan 1 MB, mensyaratkan token, dan membuat `.bak` sebelum edit.

## 1. Jalankan Ollama

Instal Ollama, lalu tarik model yang mendukung tools, contoh:

```bash
ollama pull qwen3
ollama serve
```

API resmi lokal Ollama berada di `http://localhost:11434/api`.

## 2. Jalankan gateway

Windows: klik `gateway/start-windows.bat`.

Linux/macOS:

```bash
chmod +x gateway/start-linux-macos.sh
./gateway/start-linux-macos.sh
```

Terminal akan menampilkan token. File yang boleh dibaca/diedit berada di `~/OllamaWorkspace` (atau `%USERPROFILE%\OllamaWorkspace`). Untuk folder lain:

```bash
python gateway/server.py --workspace "D:\proyek-saya"
```

## 3. Temukan IP komputer

- Windows: `ipconfig`, cari **IPv4 Address** pada Wi‑Fi.
- Linux: `hostname -I`
- macOS: `ipconfig getifaddr en0`

Jika ponsel membuat hotspot dan komputer tersambung ke hotspot, gunakan IP komputer pada jaringan hotspot. Pastikan firewall mengizinkan TCP port **8765** untuk jaringan privat.

## 4. Build Android

Buka folder ini di Android Studio (JDK 17), tunggu Gradle sync, lalu Run. Di Pengaturan aplikasi isi:

- Gateway: `http://IP-KOMPUTER:8765`
- Token: token dari gateway
- Model: pilih setelah tersambung
- **Izinkan edit file**: aktifkan hanya saat diperlukan

Untuk APK: **Build → Build APK(s)**.

## Catatan keamanan

- Jangan port-forward port 8765 atau 11434 ke internet.
- Gunakan hanya Wi‑Fi/hotspot tepercaya.
- Token wajib; ganti dengan `OLLAMA_MOBILE_TOKEN` jika ingin token tetap.
- Tool edit hanya mengganti teks yang cocok tepat satu kali dan membuat backup `.bak`.
- Kualitas tool calling bergantung pada model. Jika model tidak mendukung tools, chat biasa mungkin tetap berjalan tetapi pemanggilan file tidak akan dilakukan.

## API yang digunakan

- Ollama `GET /api/tags` untuk daftar model.
- Ollama `POST /api/chat` dengan schema `tools` dan pesan role `tool` untuk agent loop.
