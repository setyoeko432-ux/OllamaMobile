# Ollama Mobile — Android + Gateway Lokal (Secure)

Aplikasi Android native (Kotlin + Jetpack Compose) untuk berinteraksi dengan Ollama melalui Wi-Fi. Dilengkapi dengan kemampuan Agent yang aman untuk menelusuri folder dan mengedit file di komputer Anda.

## Arsitektur & Keamanan Baru

`Android → gateway:8765 → Ollama:11434`

Gateway kini menggunakan konsep **Approved Roots** untuk keamanan maksimal:
- **Zero-Trust Access**: AI tidak bisa mengakses folder sistem (C:/Windows, .ssh, dll).
- **Approved Roots**: AI hanya bisa melihat folder yang Anda daftarkan di `gateway/config.json`.
- **Path Resolver**: Proteksi terhadap path traversal (`../`), absolute path, dan link berbahaya.
- **Local Indexing**: Pencarian file cepat menggunakan SQLite tanpa dependensi eksternal.
- **Manual Confirmation**: Semua perubahan file (edit) harus disetujui secara manual oleh Anda melalui UI aplikasi.
- **Auto Backup**: Setiap kali file diedit, backup `.bak` akan dibuat secara otomatis.

## 1. Konfigurasi Folder (Approved Roots)

Buka `gateway/config.json` dan daftarkan folder yang ingin Anda beri akses ke AI:

```json
{
  "roots": [
    {
      "alias": "projects",
      "path": "D:/Projects",
      "description": "Folder proyek coding",
      "read_only": false
    },
    {
      "alias": "documents",
      "path": "C:/Users/USER/Documents",
      "description": "Dokumen pengguna",
      "read_only": true
    }
  ]
}
```

## 2. Jalankan Gateway

Windows: Klik `gateway/start-windows.bat`.
Linux/macOS: `./gateway/start-linux-macos.sh`.

Gateway akan membuat token unik setiap kali dijalankan (atau gunakan env `OLLAMA_MOBILE_TOKEN`).

## 3. Fitur Utama di Aplikasi

- **Mode Chat**: Percakapan biasa.
- **Mode Agent**: AI dapat menggunakan tools:
    - `list_roots`: Melihat daftar folder yang tersedia.
    - `list_dir`: Menelusuri isi folder.
    - `search_files`: Mencari file berdasarkan nama (menggunakan index).
    - `search_text`: Mencari kode atau teks di dalam file.
    - `read_file`: Membaca isi file secara bertahap (chunked).
    - `preview_edit`: Mengajukan perubahan file.
- **Folder AI**: Kelola folder dan jalankan scan indeks langsung dari ponsel.
- **Konfirmasi Edit**: Lihat unified diff sebelum menyetujui perubahan file.

## 4. Troubleshooting & Keamanan

- **IP Komputer**: Pastikan firewall mengizinkan port **8765**.
- **Model**: Gunakan model yang mendukung Tool Calling (misal: Llama 3.1+, Qwen 2.5+).
- **Timeout**: Pencarian teks memiliki timeout 10 detik agar tidak membebani komputer.
- **Batas Ukuran**: File > 1MB akan dilewati untuk efisiensi context window.

## Developer

Gunakan Python 3.10+ tanpa library pihak ketiga. Seluruh logika path resolution berada di `Gateway.resolve_safe_path` di `server.py`.
