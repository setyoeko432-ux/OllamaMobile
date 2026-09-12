#!/usr/bin/env python3
"""LAN gateway for Ollama Mobile with Secure File Access & Indexing. Python 3.10+, no third-party packages."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
import argparse, json, os, secrets, shutil, sqlite3, time, threading, difflib, hashlib
from datetime import datetime

# --- CONFIG & TOOLS DEFINITION ---

TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "list_roots",
            "description": "Menampilkan daftar folder (approved roots) yang diizinkan untuk diakses AI.",
            "parameters": {"type": "object", "properties": {}}
        }
    },
    {
        "type": "function",
        "function": {
            "name": "list_dir",
            "description": "Menampilkan isi folder di dalam sebuah root yang disetujui.",
            "parameters": {
                "type": "object",
                "required": ["root", "path"],
                "properties": {
                    "root": {"type": "string", "description": "Alias root folder (misal: 'workspace')"},
                    "path": {"type": "string", "description": "Path relatif di dalam root (kosongkan untuk folder utama)"},
                    "limit": {"type": "integer", "description": "Maksimal item (default 100)", "default": 100}
                }
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "search_files",
            "description": "Mencari file atau folder berdasarkan nama di dalam sebuah root.",
            "parameters": {
                "type": "object",
                "required": ["root", "query"],
                "properties": {
                    "root": {"type": "string", "description": "Alias root folder"},
                    "query": {"type": "string", "description": "Kata kunci nama file"},
                    "extensions": {"type": "array", "items": {"type": "string"}, "description": "Filter ekstensi, misal ['.kt', '.java']"},
                    "max_results": {"type": "integer", "default": 25}
                }
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "search_text",
            "description": "Mencari potongan teks di dalam file-file di dalam sebuah root.",
            "parameters": {
                "type": "object",
                "required": ["root", "query"],
                "properties": {
                    "root": {"type": "string", "description": "Alias root folder"},
                    "query": {"type": "string", "description": "Teks yang dicari"},
                    "path": {"type": "string", "description": "Sub-folder untuk mempersempit pencarian"},
                    "extensions": {"type": "array", "items": {"type": "string"}},
                    "max_results": {"type": "integer", "default": 20}
                }
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "read_file",
            "description": "Membaca isi file teks dengan rentang baris tertentu.",
            "parameters": {
                "type": "object",
                "required": ["root", "path"],
                "properties": {
                    "root": {"type": "string"},
                    "path": {"type": "string", "description": "Path relatif file"},
                    "start_line": {"type": "integer", "default": 1},
                    "end_line": {"type": "integer", "default": 200}
                }
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "preview_edit",
            "description": "Mengajukan perubahan teks pada file. Perubahan hanya berlaku setelah disetujui pengguna.",
            "parameters": {
                "type": "object",
                "required": ["root", "path", "old_text", "new_text"],
                "properties": {
                    "root": {"type": "string"},
                    "path": {"type": "string"},
                    "old_text": {"type": "string", "description": "Teks asli yang ingin diganti (harus unik dalam file)"},
                    "new_text": {"type": "string", "description": "Teks pengganti"}
                }
            }
        }
    }
]

# --- CORE LOGIC ---

class FileIndex:
    def __init__(self, db_path):
        self.db_path = db_path
        self._init_db()

    def _init_db(self):
        with sqlite3.connect(self.db_path) as conn:
            conn.execute("""
                CREATE TABLE IF NOT EXISTS files (
                    root_alias TEXT,
                    relative_path TEXT,
                    filename TEXT,
                    extension TEXT,
                    size INTEGER,
                    modified_time REAL,
                    type TEXT,
                    indexed_at REAL,
                    PRIMARY KEY (root_alias, relative_path)
                )
            """)
            conn.execute("CREATE INDEX IF NOT EXISTS idx_filename ON files(filename)")

    def update_file(self, root, rel, name, ext, size, mtime, ftype):
        with sqlite3.connect(self.db_path) as conn:
            conn.execute("""
                INSERT OR REPLACE INTO files
                (root_alias, relative_path, filename, extension, size, modified_time, type, indexed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, (root, rel, name, ext, size, mtime, ftype, time.time()))

    def clear_root(self, root):
        with sqlite3.connect(self.db_path) as conn:
            conn.execute("DELETE FROM files WHERE root_alias = ?", (root,))

    def search(self, root, query, extensions=None, limit=25):
        with sqlite3.connect(self.db_path) as conn:
            sql = "SELECT relative_path, type FROM files WHERE root_alias = ? AND filename LIKE ?"
            params = [root, f"%{query}%"]
            if extensions:
                placeholders = ",".join(["?"] * len(extensions))
                sql += f" AND extension IN ({placeholders})"
                params.extend(extensions)
            sql += " LIMIT ?"
            params.append(limit)
            return conn.execute(sql, params).fetchall()

class Config:
    def __init__(self, path):
        self.path = Path(path)
        self.data = {"roots": [], "exclusions": []}
        self.load()

    def load(self):
        if self.path.exists():
            with open(self.path, "r") as f:
                self.data = json.load(f)

    def get_root(self, alias):
        for r in self.data.get("roots", []):
            if r["alias"] == alias:
                return r
        return None

class Gateway:
    def __init__(self, ollama, token, config_path, db_path):
        self.ollama = ollama.rstrip('/')
        self.token = token
        self.config = Config(config_path)
        self.index = FileIndex(db_path)
        self.pending_edits = {} # edit_id -> data
        self.scan_status = {} # alias -> status
        self.script_dir = Path(__file__).parent

    def resolve_safe_path(self, root_alias, rel_path):
        root_cfg = self.config.get_root(root_alias)
        if not root_cfg:
            raise ValueError(f"Root alias '{root_alias}' tidak ditemukan.")

        base = Path(root_cfg["path"])
        if not base.is_absolute():
            base = (self.script_dir / base).resolve()
        else:
            base = base.resolve()

        if not base.exists():
            raise ValueError(f"Folder root '{root_alias}' tidak ada di sistem.")

        # Aturan Keamanan
        rel = Path(rel_path)
        if rel.is_absolute() or ".." in rel.parts or rel_path.startswith(("\\", "//")):
            raise ValueError("Path tidak aman atau mencoba keluar dari root.")

        if any(p.endswith(":") for p in rel.parts):
             raise ValueError("Drive letter tidak diperbolehkan dalam relative path.")

        final = (base / rel).resolve()

        # Pastikan tetap di dalam root
        if final != base and base not in final.parents:
            raise ValueError("Akses di luar approved root ditolak.")

        # Cek symbolic link (opsional: tolak jika menunjuk ke luar)
        if final.is_symlink():
            target = final.readlink()
            if not target.is_absolute():
                target = (final.parent / target).resolve()
            if base not in target.parents and target != base:
                raise ValueError("Symbolic link mengarah ke luar root ditolak.")

        return final, root_cfg

    def is_excluded(self, path):
        name = path.name.lower()
        for exc in self.config.data.get("exclusions", []):
            if name == exc.lower():
                return True
        return False

    def scan_root(self, alias):
        root_cfg = self.config.get_root(alias)
        if not root_cfg: return
        self.scan_status[alias] = "running"
        try:
            base = Path(root_cfg["path"])
            if not base.is_absolute(): base = (self.script_dir / base).resolve()
            self.index.clear_root(alias)
            count = 0
            for root, dirs, files in os.walk(str(base)):
                # Filter exclusions
                dirs[:] = [d for d in dirs if not self.is_excluded(Path(root)/d)]

                rel_dir = os.path.relpath(root, base)
                if rel_dir == ".": rel_dir = ""

                for d in dirs:
                    d_path = Path(root) / d
                    self.index.update_file(alias, str(Path(rel_dir)/d).replace("\\","/"), d, "", 0, d_path.stat().st_mtime, "directory")

                for f in files:
                    f_path = Path(root) / f
                    if self.is_excluded(f_path): continue
                    stat = f_path.stat()
                    rel_f = str(Path(rel_dir)/f).replace("\\","/")
                    self.index.update_file(alias, rel_f, f, f_path.suffix.lower(), stat.st_size, stat.st_mtime, "file")
                    count += 1
                    if count > 50000: break # Safety limit
                if count > 50000: break
            self.scan_status[alias] = f"completed ({count} files)"
        except Exception as e:
            self.scan_status[alias] = f"error: {str(e)}"

    def run_tool(self, name, args, allow_edits):
        try:
            if name == 'list_roots':
                return [{"name": r["alias"], "description": r["description"], "access": "read-only" if r.get("read_only") else "read-write"} for r in self.config.data["roots"]]

            if name == 'list_dir':
                root_alias = args.get('root')
                path_rel = args.get('path', '')
                limit = args.get('limit', 100)
                full_path, _ = self.resolve_safe_path(root_alias, path_rel)

                if not full_path.is_dir(): return f"Error: '{path_rel}' bukan folder."

                items = []
                with os.scandir(str(full_path)) as entries:
                    for entry in entries:
                        if self.is_excluded(Path(entry.path)): continue
                        try:
                            stat = entry.stat(follow_symlinks=False)
                            items.append({
                                "name": entry.name,
                                "relative_path": (Path(path_rel) / entry.name).as_posix(),
                                "type": "directory" if entry.is_dir() else "file",
                                "extension": Path(entry.name).suffix.lower(),
                                "size": stat.st_size if entry.is_file() else 0,
                                "modified_time": datetime.fromtimestamp(stat.st_mtime).isoformat()
                            })
                        except Exception: pass

                items.sort(key=lambda x: (x["type"] != "directory", x["name"].lower()))
                truncated = len(items) > limit
                return {"items": items[:limit], "truncated": truncated}

            if name == 'search_files':
                root = args.get('root')
                query = args.get('query')
                exts = args.get('extensions', [])
                limit = args.get('max_results', 25)
                # Gunakan index jika statusnya completed
                if self.scan_status.get(root, "").startswith("completed"):
                    results = self.index.search(root, query, exts, limit)
                    return [{"path": r[0], "type": r[1]} for r in results]
                else:
                    # Fallback ke filesystem search (sederhana)
                    full_root, _ = self.resolve_safe_path(root, "")
                    matches = []
                    for p in full_root.rglob(f"*{query}*"):
                        if self.is_excluded(p): continue
                        if exts and p.suffix.lower() not in exts: continue
                        rel = p.relative_to(full_root).as_posix()
                        matches.append({"path": rel, "type": "directory" if p.is_dir() else "file"})
                        if len(matches) >= limit: break
                    return matches

            if name == 'search_text':
                root = args.get('root')
                query = args.get('query').lower()
                sub_path = args.get('path', '')
                exts = args.get('extensions', [])
                limit = args.get('max_results', 20)

                full_base, _ = self.resolve_safe_path(root, sub_path)
                matches = []
                start_time = time.time()

                for p in full_base.rglob("*"):
                    if time.time() - start_time > 10: break # Timeout 10s
                    if not p.is_file() or self.is_excluded(p): continue
                    if exts and p.suffix.lower() not in exts: continue
                    if p.stat().st_size > 1_000_000: continue

                    try:
                        with open(p, 'r', encoding='utf-8', errors='ignore') as f:
                            for i, line in enumerate(f, 1):
                                if query in line.lower():
                                    rel = p.relative_to(full_base.parent if sub_path else full_base).as_posix()
                                    matches.append({
                                        "path": rel,
                                        "line": i,
                                        "snippet": line.strip()[:300]
                                    })
                                    if len(matches) >= limit: break
                    except Exception: pass
                    if len(matches) >= limit: break
                return matches

            if name == 'read_file':
                root = args.get('root')
                path = args.get('path')
                start = max(1, args.get('start_line', 1))
                end = min(start + 499, args.get('end_line', start + 199))

                full_path, _ = self.resolve_safe_path(root, path)
                if not full_path.is_file(): return "Error: File tidak ditemukan."
                if full_path.stat().st_size > 1_000_000: return "Error: File terlalu besar (>1MB)."

                lines = []
                total = 0
                try:
                    with open(full_path, 'r', encoding='utf-8', errors='replace') as f:
                        for i, line in enumerate(f, 1):
                            total += 1
                            if start <= i <= end:
                                lines.append(f"{i:4} | {line.rstrip()}")
                except Exception as e: return f"Error: {str(e)}"

                return {
                    "path": path,
                    "start_line": start,
                    "end_line": min(end, total),
                    "total_lines": total,
                    "content": "\n".join(lines),
                    "more_available": total > end
                }

            if name == 'preview_edit':
                root = args.get('root')
                path = args.get('path')
                old = args.get('old_text')
                new = args.get('new_text')

                full_path, root_cfg = self.resolve_safe_path(root, path)
                if root_cfg.get("read_only"): return "Error: Root ini bersifat read-only."
                if not allow_edits: return "Error: Izin edit dinonaktifkan di aplikasi."

                content = full_path.read_text('utf-8')
                count = content.count(old)
                if count == 0: return "Error: 'old_text' tidak ditemukan."
                if count > 1: return "Error: 'old_text' tidak unik (ditemukan lebih dari satu kali)."

                new_content = content.replace(old, new, 1)
                diff = list(difflib.unified_diff(
                    content.splitlines(keepends=True),
                    new_content.splitlines(keepends=True),
                    fromfile=path, tofile=path + " (modified)"
                ))

                edit_id = secrets.token_hex(8)
                self.pending_edits[edit_id] = {
                    "root": root,
                    "path": path,
                    "full_path": str(full_path),
                    "old_text": old,
                    "new_text": new,
                    "hash": hashlib.sha256(content.encode()).hexdigest(),
                    "timestamp": time.time()
                }

                return {
                    "edit_id": edit_id,
                    "diff": "".join(diff),
                    "message": "Perubahan telah disiapkan. Silakan konfirmasi di aplikasi."
                }

            return f"Error: Tool '{name}' tidak dikenal."
        except Exception as e:
            return f"Error: {str(e)}"

    def ollama_json(self, path, payload=None):
        data = None if payload is None else json.dumps(payload).encode()
        req = Request(self.ollama + path, data=data, headers={'Content-Type': 'application/json'})
        with urlopen(req, timeout=180) as r: return json.loads(r.read())

class Handler(BaseHTTPRequestHandler):
    def setup(self):
        super().setup()
        import socket
        try: self.request.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except Exception: pass

    def send_json(self, code, obj):
        raw = json.dumps(obj).encode(); self.send_response(code); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(raw))); self.end_headers(); self.wfile.write(raw)

    def auth(self):
        expected = 'Bearer ' + self.server.gateway.token
        if not self.server.gateway.token or self.headers.get('Authorization') != expected:
            self.send_json(401, {'error': 'Token tidak valid'}); return False
        return True

    def do_GET(self):
        if not self.auth(): return
        if self.path == '/models':
            try:
                res = self.server.gateway.ollama_json('/api/tags')
                self.send_json(200, {'models': [{'name': m['name']} for m in res.get('models', [])]})
            except Exception as e: self.send_json(502, {'error': str(e)})
        elif self.path == '/roots':
            self.send_json(200, {
                "roots": self.server.gateway.run_tool('list_roots', {}, False),
                "status": self.server.gateway.scan_status
            })
        elif self.path.startswith('/roots/') and self.path.endswith('/scan-status'):
            alias = self.path.split('/')[2]
            self.send_json(200, {"alias": alias, "status": self.server.gateway.scan_status.get(alias, "idle")})
        else: self.send_json(404, {'error': 'Not found'})

    def do_POST(self):
        if not self.auth(): return
        if self.path == '/chat':
            self.handle_chat()
        elif self.path.startswith('/roots/') and self.path.endswith('/scan'):
            alias = self.path.split('/')[2]
            threading.Thread(target=self.server.gateway.scan_root, args=(alias,)).start()
            self.send_json(202, {"message": "Scan started"})
        elif self.path.startswith('/edits/'):
            parts = self.path.split('/')
            edit_id = parts[2]
            action = parts[3]
            self.handle_edit_action(edit_id, action)
        else: self.send_json(404, {'error': 'Not found'})

    def handle_edit_action(self, edit_id, action):
        edit = self.server.gateway.pending_edits.get(edit_id)
        if not edit: return self.send_json(404, {"error": "Edit ID tidak ditemukan atau sudah kedaluwarsa."})

        if time.time() - edit["timestamp"] > 600:
            del self.server.gateway.pending_edits[edit_id]
            return self.send_json(410, {"error": "Edit ID kedaluwarsa."})

        if action == 'reject':
            del self.server.gateway.pending_edits[edit_id]
            return self.send_json(200, {"message": "Perubahan ditolak."})

        if action == 'approve':
            try:
                p = Path(edit["full_path"])
                content = p.read_text('utf-8')
                current_hash = hashlib.sha256(content.encode()).hexdigest()
                if current_hash != edit["hash"]:
                    return self.send_json(409, {"error": "File telah berubah sejak preview dibuat."})

                # Backup
                bak = str(p) + ".bak"
                shutil.copy2(p, bak)

                # Apply
                new_content = content.replace(edit["old_text"], edit["new_text"], 1)
                temp = str(p) + ".tmp"
                with open(temp, 'w', encoding='utf-8') as f: f.write(new_content)
                os.replace(temp, str(p))

                del self.server.gateway.pending_edits[edit_id]
                self.send_json(200, {"message": "Perubahan berhasil diterapkan.", "backup": bak})
            except Exception as e:
                self.send_json(500, {"error": f"Gagal menerapkan edit: {str(e)}"})

    def event(self, kind, text):
        self.wfile.write((json.dumps({'type': kind, 'text': text}, ensure_ascii=False) + '\n').encode()); self.wfile.flush()

    def handle_chat(self):
        try:
            n = int(self.headers.get('Content-Length', '0'))
            body = json.loads(self.rfile.read(n))
            model = body.get('model', '')
            messages = body.get('messages', [])
            allow = bool(body.get('allow_edits', False))
            mode = body.get('mode', 'chat').lower()

            if not model: return self.send_json(400, {'error': 'Model wajib dipilih'})

            self.send_response(200); self.send_header('Content-Type', 'application/x-ndjson; charset=utf-8'); self.end_headers()

            formatting_instructions = (
                "\nGunakan Markdown standar agar jawaban mudah dibaca pada aplikasi Android.\n"
                "Aturan:\n"
                "1. Gunakan **teks** untuk penekanan tebal.\n"
                "2. Gunakan *teks* untuk italic.\n"
                "3. Gunakan heading seperlunya, jangan berlebihan.\n"
                "4. Gunakan bullet list untuk daftar yang tidak berurutan.\n"
                "5. Gunakan numbered list untuk langkah berurutan.\n"
                "6. Gunakan inline code untuk nama fungsi, file, command singkat, dan variabel.\n"
                "7. Gunakan fenced code block dengan nama bahasa untuk kode panjang.\n"
                "8. Gunakan $...$ untuk rumus matematika inline.\n"
                "9. Gunakan $$ pada baris terpisah untuk rumus blok.\n"
                "10. Jangan membungkus rumus LaTeX dengan backtick.\n"
                "11. Jangan menggunakan HTML.\n"
                "12. Pastikan delimiter Markdown, code fence, dan LaTeX ditutup dengan benar.\n"
                "13. Gunakan tabel hanya ketika benar-benar membantu.\n"
                "14. Hindari format berlebihan pada jawaban singkat."
            )

            if mode == 'chat':
                chat_sys = {'role': 'system', 'content': 'Anda adalah asisten AI lokal.' + formatting_instructions}
                has_sys = any(m.get('role') == 'system' for m in messages)
                req_messages = messages if has_sys else [chat_sys] + messages
                req = Request(self.server.gateway.ollama + '/api/chat', data=json.dumps({'model': model, 'messages': req_messages, 'stream': True}).encode(), headers={'Content-Type': 'application/json'})
                with urlopen(req, timeout=180) as r:
                    for line in r:
                        if line.strip():
                            res = json.loads(line.decode('utf-8'))
                            tk = res.get('message', {}).get('content', '')
                            if tk: self.event('token', tk)
                self.event('done', '')
                return

            # Agent Mode
            sys_msg = {
                'role': 'system',
                'content': "Anda adalah asisten lokal. Anda hanya dapat mengakses folder yang telah disetujui pengguna. "
                           "Mulai dengan list_roots jika belum mengetahui folder yang relevan. "
                           "Gunakan search_files sebelum menelusuri banyak folder. "
                           "Gunakan search_text untuk mencari simbol atau isi kode. "
                           "Gunakan read_file dengan rentang baris agar context tetap hemat. "
                           "Sebelum mengedit, baca bagian terkait terlebih dahulu dan gunakan preview_edit. "
                           "Perubahan hanya diterapkan setelah persetujuan pengguna secara manual di UI." + formatting_instructions
            }
            history = [sys_msg] + messages

            for _ in range(8):
                req_data = {'model': model, 'messages': history, 'tools': TOOLS, 'stream': True}
                req = Request(self.server.gateway.ollama + '/api/chat', data=json.dumps(req_data).encode(), headers={'Content-Type': 'application/json'})

                full_content = ""
                tool_calls = []
                is_tool_turn = False

                with urlopen(req, timeout=180) as r:
                    for line in r:
                        if line.strip():
                            res = json.loads(line.decode('utf-8'))
                            msg = res.get('message', {})
                            tk = msg.get('content', '')
                            if tk:
                                full_content += tk
                                if not is_tool_turn: self.event('token', tk)

                            calls = msg.get('tool_calls') or []
                            if calls: is_tool_turn = True
                            for call in calls:
                                fn = call.get('function', {})
                                name = fn.get('name', '')
                                args = fn.get('arguments', '')
                                found = False
                                for tc in tool_calls:
                                    if name and tc['function']['name'] == name:
                                        found = True
                                        if isinstance(args, dict): tc['function']['arguments'].update(args)
                                        else:
                                            if isinstance(tc['function']['arguments'], str): tc['function']['arguments'] += args
                                            else: tc['function']['arguments'] = args
                                        break
                                if not found: tool_calls.append({"function": {"name": name, "arguments": args}})

                if tool_calls:
                    for tc in tool_calls:
                        a = tc['function']['arguments']
                        if isinstance(a, str):
                            try: tc['function']['arguments'] = json.loads(a)
                            except: pass
                    msg_obj = {'role': 'assistant', 'content': full_content, 'tool_calls': tool_calls}
                else:
                    msg_obj = {'role': 'assistant', 'content': full_content}

                history.append(msg_obj)
                if not tool_calls:
                    self.event('done', '')
                    return

                for call in tool_calls:
                    fn = call['function']
                    name, args = fn['name'], fn['arguments']
                    display_arg = args.get('path') or args.get('query') or ""
                    self.event('tool', f"Menjalankan {name}: {display_arg}")
                    result = self.server.gateway.run_tool(name, args, allow)
                    if name == 'preview_edit' and isinstance(result, dict) and 'edit_id' in result:
                        self.event('edit', json.dumps(result))
                    history.append({'role': 'tool', 'tool_name': name, 'content': json.dumps(result)})

            self.event('error', 'Batas 8 putaran agent tercapai.')
        except Exception as e:
            self.event('error', str(e))

class Server(ThreadingHTTPServer):
    def __init__(self, addr, gateway):
        super().__init__(addr, Handler)
        self.gateway = gateway

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--host', default='0.0.0.0')
    ap.add_argument('--port', type=int, default=8765)
    ap.add_argument('--ollama', default='http://127.0.0.1:11434')
    ap.add_argument('--config', default='config.json')
    ap.add_argument('--db', default='file_index.db')
    ap.add_argument('--token', default=os.getenv('OLLAMA_MOBILE_TOKEN', ''))
    ap.add_argument('--workspace', help='Legacy workspace path (shortcut for approved roots)')
    a = ap.parse_args()

    token = a.token or secrets.token_urlsafe(24)
    gw = Gateway(a.ollama, token, a.config, a.db)

    # Legacy support for --workspace: add/update the "workspace" root alias
    if a.workspace:
        w_path = str(Path(a.workspace).resolve())
        found = False
        for r in gw.config.data.get("roots", []):
            if r["alias"] == "workspace":
                r["path"] = w_path
                found = True
                break
        if not found:
            gw.config.data.setdefault("roots", []).append({
                "alias": "workspace",
                "path": w_path,
                "description": "Legacy Workspace",
                "read_only": False
            })

    print(f"Gateway running at http://{a.host}:{a.port}")
    print(f"Token: {token}")
    Server((a.host, a.port), gw).serve_forever()

if __name__ == '__main__': main()
