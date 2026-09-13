import unittest
import os
import json
import shutil
import tempfile
import time
import secrets
from pathlib import Path
from server import Gateway, SensitiveFileClassifier, Sensitivity, FileIndex, Config

class TestGatewayV2(unittest.TestCase):
    def setUp(self):
        self.test_dir = tempfile.mkdtemp()
        self.workspace_path = Path(self.test_dir) / "workspace"
        self.workspace_path.mkdir()

        self.source_path = Path(self.test_dir) / "source"
        self.source_path.mkdir()
        (self.source_path / "normal.txt").write_text("hello world")
        (self.source_path / ".env").write_text("SECRET=123")

        self.config_path = Path(self.test_dir) / "config.json"
        self.config_data = {
            "discovery": {
                "auto_detect_fixed_drives": False,
                "allowed_drives": [str(self.source_path)]
            },
            "workspace": {
                "path": str(self.workspace_path),
                "allow_create": True
            },
            "exclusions": [".git"]
        }
        with open(self.config_path, "w") as f: json.dump(self.config_data, f)

        self.db_path = Path(self.test_dir) / "test_index.db"
        self.gw = Gateway("http://localhost:11434", "test_token", str(self.config_path), str(self.db_path))

    def tearDown(self):
        try: shutil.rmtree(self.test_dir)
        except: pass

    def test_sensitive_classifier(self):
        self.assertEqual(SensitiveFileClassifier.classify(self.source_path / "normal.txt"), Sensitivity.NORMAL)
        self.assertEqual(SensitiveFileClassifier.classify(self.source_path / ".env"), Sensitivity.POTENTIALLY_SENSITIVE)
        self.assertEqual(SensitiveFileClassifier.classify(Path("C:/Windows/System32/config")), Sensitivity.BLOCKED_SYSTEM)
        self.assertEqual(SensitiveFileClassifier.classify(Path("id_rsa")), Sensitivity.HIGHLY_SENSITIVE)

    def test_scan_and_search(self):
        self.gw.scan_source(str(self.source_path))

        # Search normal
        results = self.gw.index.search("normal")
        self.assertEqual(len(results), 1)
        self.assertEqual(results[0][7], Sensitivity.NORMAL) # sensitivity index

        # Search sensitive
        results = self.gw.index.search(".env")
        self.assertEqual(len(results), 1)
        self.assertEqual(results[0][7], Sensitivity.POTENTIALLY_SENSITIVE)

    def test_read_source_file_security(self):
        self.gw.scan_source(str(self.source_path))

        # Get IDs
        normal_id = self.gw.index.search("normal")[0][0]
        env_id = self.gw.index.search(".env")[0][0]

        # Read normal should work (returns dict)
        res_normal = self.gw.run_tool("read_source_file", {"file_id": normal_id}, False)
        self.assertIsInstance(res_normal, dict)
        self.assertIn("hello world", res_normal["content"])

        # Read sensitive should be blocked without approval broker (returns dict with status)
        res_env = self.gw.run_tool("read_source_file", {"file_id": env_id}, False)
        self.assertIsInstance(res_env, dict)
        self.assertEqual(res_env.get("status"), "approval_required")

    def test_exclusion_pruning(self):
        git_dir = self.source_path / ".git"
        git_dir.mkdir()
        (git_dir / "secret").write_text("hidden")

        self.gw.scan_source(str(self.source_path))
        results = self.gw.index.search("secret")
        self.assertEqual(len(results), 0)

    def test_approval_flow_interruption(self):
        self.gw.scan_source(str(self.source_path))
        env_id = self.gw.index.search(".env")[0][0]

        # Mock event emitter
        events = []
        def mock_event(kind, payload, rid):
            events.append((kind, payload))

        # Mock handler-like environment
        class MockHandler:
            def __init__(self, gw): self.server = type('S', (), {'gateway': gw})
            def event(self, k, p, r=None): events.append((k, p))
            def run_ollama_stream(self, *args, **kwargs):
                # Return a tool call to read the sensitive file
                return {"tool_calls": [{"id": "c1", "function": {"name": "read_source_file", "arguments": json.dumps({"file_id": env_id})}}]}
            def run_agent_loop(self, m, h, a, rid, t, tl, o, k):
                from server import Handler
                Handler.run_agent_loop(self, m, h, a, rid, t, tl, o, k)

        h = MockHandler(self.gw)
        h.run_agent_loop("model", [], False, "r1", 5, [], {}, "30m")

        # Verify loop interrupted with approval event
        self.assertTrue(any(e[0] == 'sensitive_read_approval_required' for e in events))
        aid = json.loads([e[1] for e in events if e[0] == 'sensitive_read_approval_required'][0])["approval_id"]

        # Verify approval is in pending
        self.assertIn(aid, self.gw.pending_approvals)
        from server import ApprovalStatus
        self.assertEqual(self.gw.pending_approvals[aid].status, ApprovalStatus.PENDING)

        # Approve manually
        self.gw.pending_approvals[aid].status = ApprovalStatus.APPROVED

        # Resume (Mocking what handle_approval_response does)
        res = self.gw.run_tool("read_source_file", {"file_id": env_id, "approval_id": aid}, False)
        self.assertIsInstance(res, dict, f"Expected dict, got {type(res)}: {res}")
        self.assertIn("SECRET=123", res["content"])

    def test_copy_to_workspace_flow(self):
        self.gw.scan_source(str(self.source_path))
        fid = self.gw.index.search("normal")[0][0]

        # 1. Preview
        res_preview = self.gw.run_tool("preview_copy_to_workspace", {"file_id": fid, "reason": "test"}, False)
        self.assertEqual(res_preview["status"], "approval_required")
        self.assertEqual(res_preview["operation"], "copy_to_workspace")

        # 2. Mock Approval
        aid = secrets.token_hex(8)
        from server import ApprovalRequest, ApprovalStatus
        req = ApprovalRequest(aid, "r2", "r2", "copy_to_workspace", fid, Sensitivity.NORMAL, "test")
        req.metadata = {"destination_relative": res_preview["destination_relative"], "hash": res_preview["hash"]}
        req.status = ApprovalStatus.APPROVED
        self.gw.pending_approvals[aid] = req

        # 3. Execute
        res_exec = self.gw.execute_approved_copy(aid)
        self.assertIsInstance(res_exec, dict)
        self.assertEqual(res_exec["status"], "success")

        # 4. Verify file in workspace
        ws_file = self.workspace_path / res_exec["destination"]
        self.assertTrue(ws_file.exists())
        self.assertEqual(ws_file.read_text(), "hello world")

    def test_workspace_edit_flow(self):
        # Setup file in workspace
        ws_file = self.workspace_path / "test.txt"
        ws_file.write_text("line1\nline2")

        # 1. Preview edit
        res_preview = self.gw.run_tool("preview_workspace_edit", {"path": "test.txt", "old_text": "line1", "new_text": "new_line1"}, False)
        self.assertEqual(res_preview["status"], "approval_required")

        # 2. Mock Approval
        aid = secrets.token_hex(8)
        from server import ApprovalRequest, ApprovalStatus
        req = ApprovalRequest(aid, "r3", "r3", "workspace_edit", None, Sensitivity.NORMAL, "test")
        req.metadata = {"path": "test.txt", "old_text": "line1", "new_text": "new_line1", "hash": res_preview["hash"]}
        req.status = ApprovalStatus.APPROVED
        self.gw.pending_approvals[aid] = req

        # 3. Execute
        res_exec = self.gw.execute_approved_edit(aid)
        self.assertEqual(res_exec["status"], "success")
        self.assertEqual(ws_file.read_text(), "new_line1\nline2")

if __name__ == '__main__':
    unittest.main()
