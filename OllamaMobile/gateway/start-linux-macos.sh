#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
echo "Memulai Ollama Mobile Gateway..."
python3 server.py
