@echo off
title Ollama Performance Wrapper for Ollama Mobile
echo =======================================================
echo   Mengonfigurasi Lingkungan Performa Laptop Target
echo   RAM: 16 GB ^| Core i5-12450H ^| Single Inference Mode
echo =======================================================
echo.

:: 1. Memasang pembatasan model agar RAM 16GB tetap stabil
echo [+] Mengunci kapasitas beban model (OLLAMA_MAX_LOADED_MODELS=1)
set OLLAMA_MAX_LOADED_MODELS=1

:: 2. Memastikan hanya ada 1 request paralel yang dilayani CPU hybrid
echo [+] Menolak inferensi paralel konkuren (OLLAMA_NUM_PARALLEL=1)
set OLLAMA_NUM_PARALLEL=1

:: 3. Mengunci model di RAM selama 30 menit agar TTFT pesan berikutnya instan
echo [+] Menyetel waktu stanby model di RAM (OLLAMA_KEEP_ALIVE=30m)
set OLLAMA_KEEP_ALIVE=30m

echo.
echo =======================================================
echo [OK] Konfigurasi performa berhasil diterapkan di sesi ini!
echo Menjalankan layanan Ollama secara otomatis...
echo Hubungkan aplikasi Ollama Mobile Anda ke gateway setelah ini.
echo =======================================================
echo.

:: Menjalankan ollama serve menggunakan konfigurasi di atas
ollama serve
pause
