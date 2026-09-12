import unittest
import os
import json
import shutil
import tempfile
from pathlib import Path
from server import Gateway

class TestGatewaySecurity(unittest.TestCase):
    def setUp(self):
        self.test_dir = tempfile.mkdtemp()
        self.root_path = Path(self.test_dir) / "workspace"
        self.root_path.mkdir()

        self.secret_file = Path(self.test_dir) / "secret.txt"
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
        self.assertEqual(p, self.root_path / "file.txt")

        # Traversal attack
        with self.assertRaises(ValueError):
            self.gw.resolve_safe_path("workspace", "../secret.txt")

        # Absolute path attack
        with self.assertRaises(ValueError):
            self.gw.resolve_safe_path("workspace", "/etc/passwd")

        # Unknown root
        with self.assertRaises(ValueError):
            self.gw.resolve_safe_path("unknown", "file.txt")

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

    def test_list_roots(self):
        res = self.gw.run_tool("list_roots", {}, False)
        self.assertEqual(len(res), 2)
        self.assertEqual(res[0]["name"], "workspace")
        self.assertNotIn("path", res[0])

    def test_search_files_index(self):
        (self.root_path / "target.kt").write_text("code")
        self.gw.scan_root("workspace")

        res = self.gw.run_tool("search_files", {
            "root": "workspace",
            "query": "target"
        }, False)
        self.assertTrue(any(r["path"] == "target.kt" for r in res))

if __name__ == '__main__':
    unittest.main()
