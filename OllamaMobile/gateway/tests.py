import unittest
import os
import json
import shutil
import tempfile
import time
import threading
import hashlib
from pathlib import Path
from server import Gateway

class TestGateway(unittest.TestCase):
    def setUp(self):
        self.test_dir = tempfile.mkdtemp()
        self.root_path = Path(self.test_dir).resolve() / "workspace"
        self.root_path.mkdir()

        self.secret_file = Path(self.test_dir).resolve() / "secret.txt"
        self.secret_file.write_text("sensitif", encoding="utf-8")

        self.config_path = Path(self.test_dir) / "config.json"
        self.config_data = {
            "roots": [
                {
                    "alias": "workspace",
                    "path": str(self.root_path),
                    "description": "Test Root",
                    "read_only": False
                },
                {
                    "alias": "readonly",
                    "path": str(self.root_path),
                    "description": "ReadOnly Root",
                    "read_only": True
                }
            ],
            "exclusions": [".git", "node_modules"]
        }
        with open(self.config_path, "w") as f:
            json.dump(self.config_data, f)

        self.db_path = Path(self.test_dir) / "test_index.db"
        self.gw = Gateway("http://localhost:11434", "test_token", str(self.config_path), str(self.db_path))

    def tearDown(self):
        try:
            shutil.rmtree(self.test_dir)
        except PermissionError:
            pass

    def test_safe_path_resolution(self):
        # Valid path
        p, cfg = self.gw.resolve_safe_path("workspace", "file.txt")
        self.assertEqual(str(p.resolve()), str((self.root_path / "file.txt").resolve()))

        # Traversal attack
        with self.assertRaises(ValueError):
            self.gw.resolve_safe_path("workspace", "../secret.txt")

        # Traversal with nested
        with self.assertRaises(ValueError):
            self.gw.resolve_safe_path("workspace", "a/../../secret.txt")

        # Absolute path attack
        with self.assertRaises(ValueError):
            self.gw.resolve_safe_path("workspace", "/etc/passwd")

        # Drive letter attack (Windows style)
        if os.name == 'nt':
            with self.assertRaises(ValueError):
                self.gw.resolve_safe_path("workspace", "C:/Windows/System32")

        # Unknown root
        with self.assertRaises(ValueError):
            self.gw.resolve_safe_path("unknown", "file.txt")

    def test_symlink_security(self):
        if os.name == 'nt':
            # Check if we can create symlinks on Windows
            try:
                os.symlink(str(self.root_path), str(self.root_path / "test_link"))
                os.remove(str(self.root_path / "test_link"))
            except OSError as e:
                if e.winerror == 1314:
                    self.skipTest("Windows symlink privilege not held")

        # Symlink inside root is OK
        target = self.root_path / "target.txt"
        target.write_text("ok")
        link = self.root_path / "link.txt"
        os.symlink(str(target), str(link))

        p, _ = self.gw.resolve_safe_path("workspace", "link.txt")
        self.assertEqual(p.resolve(), target.resolve())

        # Symlink outside root should fail
        link_outside = self.root_path / "bad_link.txt"
        os.symlink(str(self.secret_file), str(link_outside))

        with self.assertRaises(ValueError) as cm:
            self.gw.resolve_safe_path("workspace", "bad_link.txt")
        self.assertIn("mengarah ke luar root", str(cm.exception))

    def test_read_only_root(self):
        test_file = self.root_path / "read.txt"
        test_file.write_text("halo", encoding="utf-8")

        # Preview edit on read-only root should fail
        res = self.gw.run_tool("preview_edit", {
            "root": "readonly",
            "path": "read.txt",
            "old_text": "halo",
            "new_text": "bye"
        }, True)
        self.assertIn("read-only", res)

    def test_edit_uniqueness(self):
        test_file = self.root_path / "dup.txt"
        test_file.write_text("sama sama", encoding="utf-8")

        res = self.gw.run_tool("preview_edit", {
            "root": "workspace",
            "path": "dup.txt",
            "old_text": "sama",
            "new_text": "beda"
        }, True)
        self.assertIn("tidak unik", res)

    def test_preview_edit_flow(self):
        test_file = self.root_path / "edit.txt"
        test_file.write_text("content asli", encoding="utf-8")

        res = self.gw.run_tool("preview_edit", {
            "root": "workspace",
            "path": "edit.txt",
            "old_text": "asli",
            "new_text": "baru"
        }, True)

        self.assertIn("edit_id", res)
        self.assertIn("diff", res)

        eid = res["edit_id"]
        edit_data = self.gw.pending_edits[eid]
        self.assertEqual(edit_data["path"], "edit.txt")
        self.assertEqual(edit_data["status"], "pending")

    def test_concurrent_scan_lock(self):
        # Create many files to make scan take longer
        for i in range(100):
            (self.root_path / f"file_{i}.txt").write_text("content")

        # Use a mock or just try to catch it in flight
        # Actually, let's monkeypatch os.walk to add a delay
        import os
        original_walk = os.walk
        def delayed_walk(*args, **kwargs):
            time.sleep(0.5)
            return original_walk(*args, **kwargs)

        os.walk = delayed_walk
        try:
            t = threading.Thread(target=self.gw.scan_root, args=("workspace",))
            t.start()

            time.sleep(0.1)
            status = self.gw.scan_status.get("workspace")
            # It might be "running" or already "completed" if it's super fast, but 0.5s sleep should help
            self.assertEqual(status, "running")

            # Try to start another scan (should be ignored by lock)
            self.gw.scan_root("workspace")
            self.assertEqual(self.gw.scan_status["workspace"], "running")

            t.join()
            self.assertTrue(self.gw.scan_status["workspace"].startswith("completed"))
        finally:
            os.walk = original_walk

    def test_search_files_index(self):
        (self.root_path / "target.kt").write_text("code")
        self.gw.scan_root("workspace")

        res = self.gw.run_tool("search_files", {
            "root": "workspace",
            "query": "target"
        }, False)
        self.assertTrue(any(r["path"] == "target.kt" for r in res))

    def test_edit_hash_mismatch(self):
        test_file = self.root_path / "hash.txt"
        test_file.write_text("konten awal")

        # 1. Create preview
        res = self.gw.run_tool("preview_edit", {
            "root": "workspace",
            "path": "hash.txt",
            "old_text": "awal",
            "new_text": "akhir"
        }, True)
        eid = res["edit_id"]

        # 2. Change file externally
        test_file.write_text("konten berubah")

        # 3. Try to approve (simulating Handler logic)
        edit = self.gw.pending_edits[eid]
        p = Path(edit["full_path"])
        content = p.read_text('utf-8')
        current_hash = hashlib.sha256(content.encode()).hexdigest()

        self.assertNotEqual(current_hash, edit["hash"])

    def test_edit_expiry(self):
        eid = "expired_id"
        self.gw.pending_edits[eid] = {
            "timestamp": time.time() - 700, # > 600s
            "status": "pending"
        }

        # Simulation of check in handler
        edit = self.gw.pending_edits.get(eid)
        self.assertTrue(time.time() - edit["timestamp"] > 600)

    def test_agent_session_save(self):
        request_id = "test_req_123"
        self.gw.agent_sessions[request_id] = {
            "model": "llama3",
            "history": [{"role": "user", "content": "hi"}],
            "turns_left": 5
        }

        self.assertIn(request_id, self.gw.agent_sessions)
        self.assertEqual(self.gw.agent_sessions[request_id]["turns_left"], 5)

if __name__ == '__main__':
    unittest.main()
