"""No actual CLI login, Docker, credentials or model usage in these tests."""
import argparse
import os
from pathlib import Path
import runpy
import tempfile
import unittest
from unittest.mock import patch

LLM = runpy.run_path(str(Path(__file__).resolve().parents[1] / "llm"))


class LlmWorkflowTest(unittest.TestCase):
    def test_pairs_are_explicit_and_test_ignores_all_credentials(self):
        config = LLM["configuration"]({"APP_RUNTIME": "test", "LLM_PROVIDER": "mock", "OPENAI_API_KEY": "secret"})
        self.assertEqual(config, dict(runtime="test", provider="mock", model="mock", authConfigured=False))
        for runtime, provider in [("local", "openai_api"), ("deployed", "codex_oauth"), ("test", ""), ("", "")]:
            with self.assertRaises(ValueError):
                LLM["configuration"]({"APP_RUNTIME": runtime, "LLM_PROVIDER": provider})

    def test_deployed_diagnostic_never_opens_oauth_and_prints_no_key(self):
        with patch("pathlib.Path.is_file", side_effect=AssertionError("OAuth path must not be accessed")):
            result = LLM["configuration"]({"APP_RUNTIME": "deployed", "LLM_PROVIDER": "openai_api",
                                          "OPENAI_MODEL": "synthetic", "OPENAI_API_KEY": "secret", "CODEX_AUTH_FILE": "/forbidden/auth.json"})
        self.assertTrue(result["authConfigured"])
        self.assertNotIn("secret", str(result)); self.assertNotIn("forbidden", str(result))

    def test_login_isolated_child_and_file_auth_not_token_copy(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "project-auth/auth.json"
            env = {"PATH": os.environ.get("PATH", ""), "HOME": str(Path.home()), "CODEX_AUTH_FILE": str(path),
                   "OPENAI_API_KEY": "must-not-inherit", "CODEX_ACCESS_TOKEN": "must-not-inherit"}
            with patch.dict(os.environ, env, clear=True), patch("subprocess.run") as run:
                run.return_value.returncode = 0
                self.assertEqual(LLM["login"](argparse.Namespace(device_auth=False, workspace_id="synthetic-workspace")), 0)
                command = run.call_args.args[0]; child = run.call_args.kwargs["env"]
                self.assertIn('cli_auth_credentials_store="file"', command)
                self.assertIn('forced_login_method="chatgpt"', command)
                self.assertIn('forced_chatgpt_workspace_id="synthetic-workspace"', command)
                self.assertEqual(child["CODEX_HOME"], str(path.parent))
                self.assertNotIn("OPENAI_API_KEY", child); self.assertNotIn("CODEX_ACCESS_TOKEN", child)
                self.assertFalse(path.exists())

    def test_repository_and_existing_developer_homes_are_rejected(self):
        for path in [LLM["ROOT"] / "runtime/auth.json", Path.home() / ".codex/auth.json"]:
            with self.assertRaises(ValueError):
                LLM["auth_path"]({"CODEX_AUTH_FILE": str(path)})
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(ValueError):
                LLM["auth_path"]({"CODEX_HOME": folder, "CODEX_AUTH_FILE": folder + "/auth.json"})

    def test_local_children_strip_paid_and_external_auth(self):
        env = LLM["local_env"]({"OPENAI_API_KEY": "secret", "CODEX_ACCESS_TOKEN": "secret", "APP_RUNTIME": "local",
                                 "CODEX_AUTH_FILE": "/project/auth.json", "CODEX_MODEL": "explicit"})
        self.assertEqual(set(env), {"APP_RUNTIME", "CODEX_AUTH_FILE", "CODEX_MODEL"})

    def test_deployment_smoke_rejects_nonloopback_before_requests(self):
        env = {"APP_RUNTIME": "deployed", "LLM_PROVIDER": "openai_api", "OPENAI_MODEL": "synthetic"}
        with patch.dict(os.environ, env, clear=True):
            for url in ["https://example.com", "http://127.0.0.1:8081@example.com", "http://127.0.0.1:8081/?q=x"]:
                with self.assertRaises(ValueError):
                    LLM["smoke_deployed"](argparse.Namespace(base_url=url))


if __name__ == "__main__":
    unittest.main()
