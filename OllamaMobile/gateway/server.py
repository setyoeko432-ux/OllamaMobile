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
        self.agent_sessions = {} # request_id -> data
        self.scan_status = {} # alias -> status
        self.scan_locks = {} # alias -> Lock
        self.script_dir = Path(__file__).parent
        self.lock = threading.Lock()

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
        if os.path.isabs(rel_path):
             raise ValueError("Path absolut tidak diperbolehkan.")

        rel = Path(rel_path)
        if ".." in rel.parts or rel_path.startswith(("\\", "//")):
            raise ValueError("Path tidak aman atau mencoba keluar dari root.")

        if any(p.endswith(":") for p in rel.parts):
             raise ValueError("Drive letter tidak diperbolehkan dalam relative path.")

        final = (base / rel).resolve()

        # Pastikan tetap di dalam root
        try:
            final.relative_to(base)
        except ValueError:
            raise ValueError("Akses di luar approved root ditolak.")

        # Cek symbolic link
        if final.exists() and final.is_symlink():
            target = final.readlink()
            if not target.is_absolute():
                target = (final.parent / target).resolve()
            try:
                target.relative_to(base)
            except ValueError:
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

        lock = self.scan_locks.setdefault(alias, threading.Lock())
        if not lock.acquire(blocking=False):
            return # Scan already running

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
                    try:
                        d_path = Path(root) / d
                        self.index.update_file(alias, str(Path(rel_dir)/d).replace("\\","/"), d, "", 0, d_path.stat().st_mtime, "directory")
                    except (PermissionError, OSError): pass

                for f in files:
                    try:
                        f_path = Path(root) / f
                        if self.is_excluded(f_path): continue
                        stat = f_path.stat()
                        rel_f = str(Path(rel_dir)/f).replace("\\","/")
                        self.index.update_file(alias, rel_f, f, f_path.suffix.lower(), stat.st_size, stat.st_mtime, "file")
                        count += 1
                        if count > 50000: break # Safety limit
                    except (PermissionError, OSError): pass
                if count > 50000: break
            self.scan_status[alias] = f"completed ({count} files)"
        except Exception as e:
            self.scan_status[alias] = f"error: {str(e)}"
        finally:
            lock.release()

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
                    "edit_id": edit_id,
                    "root": root,
                    "path": path,
                    "full_path": str(full_path),
                    "old_text": old,
                    "new_text": new,
                    "hash": hashlib.sha256(content.encode()).hexdigest(),
                    "timestamp": time.time(),
                    "status": "pending"
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

# --- ADVANCED PERFORMANCE LAYER & CONTEXT POLICIES ---

MODEL_METADATA_CACHE = {}
CONVERSATION_CONTEXT_CACHE = {}

def get_available_memory():
    """Returns available memory in bytes."""
    try:
        if os.name == 'nt':
            class MEMORYSTATUSEX(ctypes.Structure):
                _fields_ = [
                    ("dwLength", ctypes.c_ulong),
                    ("dwMemoryLoad", ctypes.c_ulong),
                    ("ullTotalPhys", ctypes.c_ulonglong),
                    ("ullAvailPhys", ctypes.c_ulonglong),
                    ("ullTotalPageFile", ctypes.c_ulonglong),
                    ("ullAvailPageFile", ctypes.c_ulonglong),
                    ("ullTotalVirtual", ctypes.c_ulonglong),
                    ("ullAvailVirtual", ctypes.c_ulonglong),
                    ("sllAvailExtendedVirtual", ctypes.c_ulonglong),
                ]
            stat = MEMORYSTATUSEX()
            stat.dwLength = ctypes.sizeof(MEMORYSTATUSEX)
            if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(stat)):
                return stat.ullAvailPhys
        elif os.path.exists('/proc/meminfo'):
            with open('/proc/meminfo', 'r') as f:
                for line in f:
                    if line.startswith('MemAvailable:'):
                        return int(line.split()[1]) * 1024
    except Exception:
        pass
    return 8 * 1024 * 1024 * 1024

def get_memory_pressure():
    avail = get_available_memory()
    avail_gb = avail / (1024 * 1024 * 1024)
    if avail_gb < 4.0: return "LOW"
    if avail_gb <= 7.0: return "MEDIUM"
    return "HIGH"

def get_model_profile(ollama_url, model_name):
    if model_name in MODEL_METADATA_CACHE:
        return MODEL_METADATA_CACHE[model_name]
    size_class = "UNKNOWN"
    param_size = ""
    quant = ""
    is_warm = False
    try:
        req = Request(ollama_url + '/api/ps')
        with urlopen(req, timeout=3) as r:
            ps_res = json.loads(r.read().decode('utf-8'))
            for m in ps_res.get('models', []):
                if m.get('name') == model_name or m.get('model') == model_name:
                    is_warm = True
    except Exception: pass
    try:
        req = Request(ollama_url + '/api/show', data=json.dumps({'name': model_name}).encode(), headers={'Content-Type': 'application/json'})
        with urlopen(req, timeout=3) as r:
            show_res = json.loads(r.read().decode('utf-8'))
            details = show_res.get('details', {})
            param_size = details.get('parameter_size', '')
            quant = details.get('quantization_level', '')
    except Exception: pass
    p_lower = param_size.lower()
    if '3b' in p_lower or '3.' in p_lower: size_class = "SMALL_3B"
    elif '4b' in p_lower or '4.' in p_lower: size_class = "MEDIUM_4B"
    elif '7b' in p_lower or '7.' in p_lower or '8b' in p_lower: size_class = "LARGE_7B"
    else:
        name_lower = model_name.lower()
        import re
        m = re.search(r'(?:^|[^0-9])([3478])[bb](?:$|[^0-9])', name_lower)
        if m:
            num = m.group(1)
            if num == '3': size_class = "SMALL_3B"
            elif num == '4': size_class = "MEDIUM_4B"
            elif num in ('7', '8'): size_class = "LARGE_7B"
        elif '3b' in name_lower: size_class = "SMALL_3B"
        elif '4b' in name_lower: size_class = "MEDIUM_4B"
        elif '7b' in name_lower or '8b' in name_lower: size_class = "LARGE_7B"
    profile = {"size_class": size_class, "parameter_size": param_size or "Unknown", "quantization_level": quant or "Unknown", "is_warm": is_warm}
    # Cache if we got anything useful
    MODEL_METADATA_CACHE[model_name] = profile
    return profile

def estimate_tokens(text, is_code=False):
    if not text: return 0
    return len(text) / 3.0 if is_code else len(text) / 3.5

def compute_required_context(messages, mode, tools_str=""):
    input_tokens = 0
    for m in messages:
        role = m.get('role', '')
        content = m.get('content', '') or ''
        is_code = (role == 'tool') or ('```' in content) or content.strip().startswith('{') or content.strip().startswith('[')
        input_tokens += estimate_tokens(content, is_code)
    if tools_str:
        input_tokens += estimate_tokens(tools_str, is_code=True)
    reserved_output = 768 if mode == 'agent' else 512
    required = (input_tokens + reserved_output) * 1.22
    return int(input_tokens), reserved_output, int(required)

def select_context_window(model_profile, memory_pressure, required_context, active_ctx=None, user_override=None):
    if user_override and user_override > 0:
        return user_override, "User override"
    if memory_pressure == "LOW":
        return 2048, "LOW memory pressure caps context to 2048"
    size_class = model_profile["size_class"]
    if size_class in ("SMALL_3B", "MEDIUM_4B"):
        max_allowed = 8192 if memory_pressure == "HIGH" else 4096
        default_start = 4096
    elif size_class == "LARGE_7B":
        max_allowed = 4096
        default_start = 2048 if memory_pressure == "MEDIUM" else 4096
    else:
        max_allowed = 4096
        default_start = 2048
    if active_ctx and active_ctx in [2048, 4096, 8192]:
        if required_context <= active_ctx * 0.75:
            return active_ctx, f"Required tokens ({required_context}) within 75% of active context ({active_ctx})"
        else:
            if active_ctx == 2048 and max_allowed >= 4096:
                return 4096, "Input exceeds 75% of 2048, scaling to 4096"
            if active_ctx == 4096 and max_allowed >= 8192:
                return 8192, "Input exceeds 75% of 4096, scaling to 8192"
            return max_allowed, f"Input exceeds 75% of {active_ctx}, capped at {max_allowed}"
    if required_context <= 2048: chosen = 2048
    elif required_context <= 4096: chosen = 4096 if max_allowed >= 4096 else max_allowed
    else: chosen = 8192 if max_allowed >= 8192 else max_allowed
    if chosen < default_start and chosen <= max_allowed:
        chosen = min(default_start, max_allowed)
    return chosen, f"{size_class} auto context selection under {memory_pressure} RAM"

def compact_history(messages, max_ctx, reserved_output, tools_str=""):
    allowed_input_tokens = (max_ctx / 1.22) - reserved_output - estimate_tokens(tools_str, is_code=True)
    system_msg = None
    other_msgs = []
    for m in messages:
        if m.get('role') == 'system': system_msg = m
        else: other_msgs.append(m)
    sys_tokens = estimate_tokens(system_msg.get('content', '') if system_msg else "")
    available_for_chat = allowed_input_tokens - sys_tokens
    if not other_msgs: return messages
    latest_msg = other_msgs[-1]
    latest_tokens = estimate_tokens(latest_msg.get('content', ''), is_code=(latest_msg.get('role') == 'tool'))
    current_tokens = latest_tokens
    kept_msgs = [latest_msg]
    i = len(other_msgs) - 2
    while i >= 0:
        msg = other_msgs[i]
        group = [msg]
        if msg.get('role') == 'tool':
            j = i - 1
            while j >= 0 and other_msgs[j].get('role') == 'tool':
                group.insert(0, other_msgs[j])
                j -= 1
            if j >= 0 and other_msgs[j].get('role') == 'assistant':
                group.insert(0, other_msgs[j])
                i = j
        group_tokens = sum(estimate_tokens(m.get('content', '') or '', is_code=(m.get('role') == 'tool' or '```' in (m.get('content', '') or ''))) for m in group)
        if current_tokens + group_tokens <= available_for_chat:
            for m in reversed(group): kept_msgs.insert(0, m)
            current_tokens += group_tokens
        else: break
        i -= 1
    final_messages = []
    if system_msg: final_messages.append(system_msg)
    final_messages.extend(kept_msgs)
    return final_messages

def select_tools_for_intent(user_query):
    q = user_query.lower()
    has_read = any(k in q for k in ["baca", "lihat", "tampilkan", "read", "view", "isi file", "show", "open"])
    has_search = any(k in q for k in ["cari", "temukan", "search", "find", "grep"])
    has_edit = any(k in q for k in ["ubah", "edit", "ganti", "replace", "modify", "update", "tulis", "write"])
    has_dir = any(k in q for k in ["folder", "direktori", "list", "ls", "dir", "isi folder"])
    if not (has_read or has_search or has_edit or has_dir): return []
    selected = [TOOLS[0]]
    if has_dir or has_read: selected.append(TOOLS[1])
    if has_search:
        selected.append(TOOLS[2])
        selected.append(TOOLS[3])
    if has_read: selected.append(TOOLS[4])
    if has_edit:
        selected.append(TOOLS[5])
        if TOOLS[4] not in selected: selected.append(TOOLS[4])
    unique_tools = []
    for t in selected:
        if t not in unique_tools: unique_tools.append(t)
    return unique_tools

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
        elif self.path == '/system-info':
            p = get_memory_pressure()
            self.send_json(200, {"memory_pressure": p, "available_bytes": get_available_memory()})
        else: self.send_json(404, {'error': 'Not found'})

    def do_POST(self):
        if not self.auth(): return
        if self.path == '/chat':
            self.handle_chat()
        elif self.path == '/warmup':
            try:
                n = int(self.headers.get('Content-Length', '0'))
                body = json.loads(self.rfile.read(n)) if n > 0 else {}
                model = body.get('model', '')
                if model:
                    self.server.gateway.ollama_json('/api/show', {'name': model})
                self.send_json(200, {"status": "success", "message": f"Model {model} warm-up triggered"})
            except Exception as e: self.send_json(500, {"error": str(e)})
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
        with self.server.gateway.lock:
            edit = self.server.gateway.pending_edits.get(edit_id)
            if not edit: return self.send_json(404, {"error": "Edit ID tidak ditemukan atau sudah kedaluwarsa."})
            if time.time() - edit["timestamp"] > 600:
                del self.server.gateway.pending_edits[edit_id]
                return self.send_json(410, {"error": "Edit ID kedaluwarsa."})
            try:
                n = int(self.headers.get('Content-Length', '0'))
                body = json.loads(self.rfile.read(n)) if n > 0 else {}
                request_id = body.get('request_id', edit.get('request_id', secrets.token_hex(4)))
            except:
                request_id = edit.get('request_id', secrets.token_hex(4))
        self.send_response(200)
        self.send_header('Content-Type', 'application/x-ndjson; charset=utf-8')
        self.end_headers()
        tool_result = ""
        if action == 'reject':
            tool_result = "Pengguna menolak perubahan. File tidak diubah."
            self.event('edit_rejected', tool_result, request_id)
        elif action == 'approve':
            try:
                p = Path(edit["full_path"])
                content = p.read_text('utf-8')
                current_hash = hashlib.sha256(content.encode()).hexdigest()
                if current_hash != edit["hash"]:
                    tool_result = "Error: File telah berubah sejak preview dibuat. Perubahan dibatalkan."
                    self.event('error', tool_result, request_id)
                else:
                    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
                    bak = str(p) + f".{ts}.bak"
                    shutil.copy2(p, bak)
                    new_content = content.replace(edit["old_text"], edit["new_text"], 1)
                    temp = str(p) + ".tmp"
                    with open(temp, 'w', encoding='utf-8') as f: f.write(new_content)
                    os.replace(temp, str(p))
                    tool_result = f"Perubahan disetujui dan berhasil diterapkan. Backup dibuat di {bak}."
                    self.event('edit_applied', tool_result, request_id)
            except Exception as e:
                tool_result = f"Error saat menerapkan perubahan: {str(e)}"
                self.event('error', tool_result, request_id)
        session = self.server.gateway.agent_sessions.get(request_id)
        if session:
            history = session["history"]
            history.append({'role': 'tool', 'tool_name': edit.get('tool_name', 'preview_edit'), 'content': tool_result})
            with self.server.gateway.lock:
                if edit_id in self.server.gateway.pending_edits:
                    del self.server.gateway.pending_edits[edit_id]
            self.run_agent_loop(session["model"], history, session.get("allow_edits", False), request_id, session.get("turns_left", 5) - 1, tools=session.get("tools"), options=session.get("options"))
        else:
            self.event('token', tool_result, request_id)
            self.event('done', '', request_id)
            with self.server.gateway.lock:
                if edit_id in self.server.gateway.pending_edits:
                    del self.server.gateway.pending_edits[edit_id]

    def event(self, kind, payload, request_id):
        obj = {'type': kind, 'request_id': request_id, 'timestamp': int(time.time() * 1000), 'payload': payload}
        self.wfile.write((json.dumps(obj, ensure_ascii=False) + '\n').encode()); self.wfile.flush()

    def handle_chat(self):
        try:
            n = int(self.headers.get('Content-Length', '0'))
            body = json.loads(self.rfile.read(n))
            model = body.get('model', '')
            messages = body.get('messages', [])
            allow = bool(body.get('allow_edits', False))
            mode = body.get('mode', 'chat').lower()
            request_id = body.get('request_id', secrets.token_hex(8))
            perf_mode = body.get('performance_mode', 'AUTO')
            ctx_override = int(body.get('context_override', 0))
            out_limit = int(body.get('output_token_limit', 0))
            thread_mode = body.get('thread_mode', 'AUTO')
            conversation_id = body.get('conversation_id', request_id)

            if not model: return self.send_json(400, {'error': 'Model wajib dipilih'})
            self.send_response(200)
            self.send_header('Content-Type', 'application/x-ndjson; charset=utf-8')
            self.end_headers()

            formatting_instructions = (
                "\nGunakan Markdown standar agar jawaban mudah dibaca pada aplikasi Android.\n"
                "Aturan:\n1. Gunakan **teks** untuk penekanan tebal.\n2. Gunakan *teks* untuk italic.\n"
                "3. Gunakan heading seperlunya.\n4. Gunakan bullet list.\n5. Gunakan numbered list.\n"
                "6. Gunakan inline code.\n7. Gunakan fenced code block dengan nama bahasa.\n"
                "8. Gunakan $...$ untuk LaTeX inline.\n9. Gunakan $$ untuk rumus blok.\n"
                "10. Jangan gunakan HTML.\n11. Pastikan delimiter ditutup.\n"
            )

            mem_p = get_memory_pressure()
            profile = get_model_profile(self.server.gateway.ollama, model)

            active_tools = []
            if mode == 'agent':
                last_user_query = ""
                for m in reversed(messages):
                    if m.get('role') == 'user':
                        last_user_query = m.get('content', '')
                        break
                active_tools = select_tools_for_intent(last_user_query)

            tools_str = json.dumps(active_tools) if active_tools else ""
            in_tk, out_res, req_ctx = compute_required_context(messages, mode, tools_str)

            active_ctx = CONVERSATION_CONTEXT_CACHE.get(conversation_id)
            selected_num_ctx, selection_reason = select_context_window(profile, mem_p, req_ctx, active_ctx, ctx_override)
            CONVERSATION_CONTEXT_CACHE[conversation_id] = selected_num_ctx

            compacted_messages = compact_history(messages, selected_num_ctx, out_res, tools_str)
            if len(compacted_messages) < len(messages):
                selection_reason += " | History compacted instead of increasing context"

            options = {"num_ctx": selected_num_ctx, "temperature": 0.6}
            if out_limit > 0:
                options["num_predict"] = out_limit
            else:
                options["num_predict"] = 768 if mode == 'agent' else 512

            if thread_mode != 'AUTO':
                try: options["num_thread"] = int(thread_mode)
                except: pass

            keep_alive_val = body.get('keep_alive_duration', '30m' if profile["size_class"] != "LARGE_7B" else '15m')

            meta = {
                "request_id": request_id,
                "selected_num_ctx": selected_num_ctx,
                "estimated_input_tokens": in_tk,
                "reserved_output_tokens": options["num_predict"],
                "performance_mode": perf_mode,
                "model_size_class": profile["size_class"],
                "memory_pressure": mem_p,
                "keep_alive": keep_alive_val,
                "selection_reason": selection_reason,
                "is_warm": profile["is_warm"]
            }
            self.event('metadata', json.dumps(meta), request_id)

            if mode == 'chat':
                chat_sys = {'role': 'system', 'content': 'Anda adalah asisten AI lokal.' + formatting_instructions}
                has_sys = any(m.get('role') == 'system' for m in compacted_messages)
                req_messages = compacted_messages if has_sys else [chat_sys] + compacted_messages
                req_payload = {'model': model, 'messages': req_messages, 'stream': True, 'options': options, 'keep_alive': keep_alive_val}
                req = Request(self.server.gateway.ollama + '/api/chat', data=json.dumps(req_payload).encode(), headers={'Content-Type': 'application/json'})
                try:
                    with urlopen(req, timeout=180) as r:
                        for line in r:
                            if line.strip():
                                try:
                                    res = json.loads(line.decode('utf-8'))
                                    tk = res.get('message', {}).get('content', '')
                                    if tk: self.event('token', tk, request_id)
                                except: pass
                    self.event('done', '', request_id)
                except Exception as e: self.event('error', str(e), request_id)
                return

            sys_msg = {
                'role': 'system',
                'content': "Anda adalah asisten lokal dengan akses folder approved. "
                           "Gunakan read_file dengan rentang baris agar context tetap hemat. "
                           "Sebelum mengedit, gunakan preview_edit. "
                           "Perubahan baru berlaku setelah disetujui pengguna." + formatting_instructions
            }
            has_sys = any(m.get('role') == 'system' for m in compacted_messages)
            history = compacted_messages if has_sys else [sys_msg] + compacted_messages
            self.run_agent_loop(model, history, allow, request_id, tools=active_tools, options=options, keep_alive=keep_alive_val)
        except Exception as e:
            try: self.event('error', str(e), request_id)
            except: pass

    def run_agent_loop(self, model, history, allow, request_id, turns_left=5, tools=None, options=None, keep_alive='30m'):
        try:
            executed_tool_calls = set()
            for turn in range(turns_left):
                req_data = {'model': model, 'messages': history, 'stream': True, 'keep_alive': keep_alive}
                if tools: req_data['tools'] = tools
                if options: req_data['options'] = options
                req = Request(self.server.gateway.ollama + '/api/chat', data=json.dumps(req_data).encode(), headers={'Content-Type': 'application/json'})
                full_content = ""
                tool_calls_map = {}
                is_tool_turn = False
                in_thinking = False
                with urlopen(req, timeout=180) as r:
                    for line in r:
                        if line.strip():
                            try:
                                res = json.loads(line.decode('utf-8'))
                                msg = res.get('message', {})
                                tk = msg.get('content', '')
                                if "<think>" in tk: in_thinking = True
                                if "</think>" in tk:
                                    in_thinking = False
                                    tk = tk.split("</think>")[-1]
                                if in_thinking: pass
                                elif tk:
                                    full_content += tk
                                    if not is_tool_turn: self.event('token', tk, request_id)
                                calls = msg.get('tool_calls') or []
                                for call in calls:
                                    is_tool_turn = True
                                    idx = call.get('index', 0)
                                    if idx not in tool_calls_map: tool_calls_map[idx] = call
                                    else:
                                        existing = tool_calls_map[idx]
                                        new_fn = call.get('function', {})
                                        ext_fn = existing.get('function', {})
                                        ext_fn['arguments'] = ext_fn.get('arguments', '') + new_fn.get('arguments', '')
                            except: pass
                tool_calls = [tool_calls_map[i] for i in sorted(tool_calls_map.keys())]
                msg_obj = {'role': 'assistant', 'content': full_content}
                if tool_calls:
                    for tc in tool_calls:
                        a = tc['function']['arguments']
                        if isinstance(a, str):
                            try: tc['function']['arguments'] = json.loads(a)
                            except: pass
                    msg_obj['tool_calls'] = tool_calls
                history.append(msg_obj)
                if not tool_calls:
                    self.event('done', '', request_id)
                    with self.server.gateway.lock:
                        if request_id in self.server.gateway.agent_sessions:
                            del self.server.gateway.agent_sessions[request_id]
                    return
                with self.server.gateway.lock:
                    self.server.gateway.agent_sessions[request_id] = {
                        "model": model, "history": history, "allow_edits": allow, "turns_left": turns_left - turn, "tools": tools, "options": options
                    }
                for call in tool_calls:
                    fn = call['function']
                    name, args = fn['name'], fn['arguments']
                    call_signature = f"{name}:{json.dumps(args, sort_keys=True)}"
                    if call_signature in executed_tool_calls:
                        self.event('error', f"Halt: Terjadi perulangan tool call identik: {name}", request_id)
                        return
                    executed_tool_calls.add(call_signature)
                    display_arg = args.get('path') or args.get('query') or args.get('root') or ""
                    self.event('tool_started', f"Menjalankan {name}: {display_arg}", request_id)
                    if name == 'read_file':
                        if 'end_line' in args and 'start_line' in args:
                            if args['end_line'] - args['start_line'] > 200:
                                args['end_line'] = args['start_line'] + 199
                    result = self.server.gateway.run_tool(name, args, allow)
                    if isinstance(result, str) and len(result) > 5000:
                        result = result[:5000] + "\n... [Hasil dipotong karena terlalu panjang]"
                    elif isinstance(result, dict) and 'content' in result and isinstance(result['content'], str) and len(result['content']) > 5000:
                        result['content'] = result['content'][:5000] + "\n... [Konten dipotong]"
                    if name == 'preview_edit' and isinstance(result, dict) and 'edit_id' in result:
                        eid = result['edit_id']
                        with self.server.gateway.lock:
                            self.server.gateway.pending_edits[eid].update({
                                "request_id": request_id, "tool_name": name
                            })
                        self.event('approval_required', json.dumps(result), request_id)
                        return
                    self.event('tool_completed', f"Selesai {name}", request_id)
                    history.append({'role': 'tool', 'tool_name': name, 'content': json.dumps(result)})
            self.event('error', 'Batas putaran agent tercapai.', request_id)
        except Exception as e:
            try: self.event('error', str(e), request_id)
            except: pass

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
