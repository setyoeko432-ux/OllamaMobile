import unittest
import os
import json
import shutil
import tempfile
import time
import threading
import hashlib
import sys
from pathlib import Path
from server import Gateway, SystemMemoryProvider, select_context_window, compact_history, select_tools_for_intent

class TestGateway(unittest.TestCase):
    def setUp(self):
        self.test_dir = tempfile.mkdtemp()
        self.root_path = Path(self.test_dir).resolve() / "workspace"
        self.root_path.mkdir()

        self.config_path = Path(self.test_dir) / "config.json"
        self.config_data = {
            "roots": [{"alias": "workspace", "path": str(self.root_path), "description": "Test", "read_only": False}],
            "exclusions": [".git", "node_modules"]
        }
        with open(self.config_path, "w") as f: json.dump(self.config_data, f)

        self.db_path = Path(self.test_dir) / "test_index.db"
        self.gw = Gateway("http://localhost:11434", "test_token", str(self.config_path), str(self.db_path))

    def tearDown(self):
        try: shutil.rmtree(self.test_dir)
        except: pass

    def test_safe_path_resolution(self):
        p, _ = self.gw.resolve_safe_path("workspace", "file.txt")
        self.assertEqual(str(p.resolve()), str((self.root_path / "file.txt").resolve()))

    def test_memory_pressure_logic(self):
        original = SystemMemoryProvider.get_available_memory
        try:
            SystemMemoryProvider.get_available_memory = lambda: 2 * 1024**3
            self.assertEqual(SystemMemoryProvider.get_pressure(), "LOW")
            SystemMemoryProvider.get_available_memory = lambda: 10 * 1024**3
            self.assertEqual(SystemMemoryProvider.get_pressure(), "HIGH")
        finally: SystemMemoryProvider.get_available_memory = original

    def test_context_policy_fast(self):
        profile = {"size_class": "SMALL_3B"}
        ctx, _ = select_context_window(profile, "MEDIUM", 1000, mode="FAST")
        self.assertEqual(ctx, 2048)
        ctx, _ = select_context_window(profile, "MEDIUM", 3000, mode="FAST")
        self.assertEqual(ctx, 4096)

    def test_context_policy_balanced(self):
        profile_7b = {"size_class": "LARGE_7B"}
        ctx, _ = select_context_window(profile_7b, "HIGH", 4000, mode="BALANCED")
        self.assertEqual(ctx, 4096)

    def test_hysteresis_logic(self):
        profile = {"size_class": "SMALL_3B"}
        # Active 4096, req 2500 (within 75% of 4096 is 3072)
        ctx, _ = select_context_window(profile, "MEDIUM", 2500, active=4096)
        self.assertEqual(ctx, 4096)
        # Active 2048, req 1600 (exceeds 75% of 2048 is 1536)
        ctx, _ = select_context_window(profile, "MEDIUM", 1600, active=2048)
        self.assertEqual(ctx, 4096)

    def test_history_compaction(self):
        msgs = [{"role": "system", "content": "S"}, {"role": "user", "content": "A" * 2000}, {"role": "user", "content": "L"}]
        # Very small ctx to force prune
        compacted = compact_history(msgs, 512, 128)
        self.assertEqual(compacted[-1]["content"], "L")
        self.assertLess(len(compacted), len(msgs))

    def test_tool_intent(self):
        tools = select_tools_for_intent("perbaiki file ini")
        self.assertTrue(any(t["function"]["name"] == "preview_edit" for t in tools))

    def test_exclusion_search(self):
        git_dir = self.root_path / ".git"; git_dir.mkdir()
        (git_dir / "config").write_text("secret")
        self.gw.scan_root("workspace")
        res = self.gw.run_tool("search_text", {"root": "workspace", "query": "secret"}, False)
        self.assertEqual(len(res), 0)

    def test_concurrent_scan_lock(self):
        self.gw.scan_status["workspace"] = "running"
        # Since it's already "running" in status, scan_root should return early (mocking lock indirectly)
        # Actually let's use the real lock
        self.gw.scan_locks["workspace"] = threading.Lock()
        self.gw.scan_locks["workspace"].acquire()
        try:
            # This should return immediately because lock is held
            self.gw.scan_root("workspace")
            self.assertEqual(self.gw.scan_status.get("workspace"), "running")
        finally:
            self.gw.scan_locks["workspace"].release()

if __name__ == '__main__':
    unittest.main()
