import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('model_call_export', ROOT / 'agent-app/scripts/export_model_calls.py')
exporter = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(exporter)


class ModelCallExportTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='jdd-ledger-export-')
        self.addCleanup(self.temporary.cleanup)
        self.project = Path(self.temporary.name).resolve()
        self.output = self.project / 'observation.json'
        self.calls = []
        self.programs = {'docker': '/tools/docker', 'docker-compose': '/tools/docker-compose'}
        self.plugin_works = True
        self.discovery_failure = None
        self.export_failure = None

    def run_command(self, command, **kwargs):
        self.calls.append((command, kwargs))
        if command[-1] == 'version':
            if self.discovery_failure:
                raise self.discovery_failure
            failed_plugin = command[:2] == ['/tools/docker', 'compose'] and not self.plugin_works
            return subprocess.CompletedProcess(command, int(failed_plugin), 'synthetic version', 'private diagnostic')
        if self.export_failure:
            raise self.export_failure
        report = {'transactionReadOnly': 'on', 'transactionIsolation': 'repeatable read',
                  'calls': [], 'installationTotals': {'calls': 0}, 'budget': None}
        return subprocess.CompletedProcess(command, 0, json.dumps(report), '')

    def invoke(self, environment=None):
        with patch.dict(os.environ, environment or {}, clear=True), \
             patch('shutil.which', side_effect=lambda name, **kwargs: self.programs.get(name)), \
             patch.object(exporter.subprocess, 'run', side_effect=self.run_command), \
             patch.object(sys, 'argv', ['export_model_calls.py', '--compose-dir', str(self.project),
                                       '--output', str(self.output)]), \
             contextlib.redirect_stdout(io.StringIO()):
            exporter.main()

    def test_windows_discovery_keeps_os_and_docker_paths_without_provider_secrets(self):
        required = {'PATH': 'synthetic executable path', 'SYSTEMROOT': 'synthetic windows root',
                    'WINDIR': 'synthetic windows root', 'COMSPEC': 'synthetic cmd path',
                    'PATHEXT': '.COM;.EXE;.BAT;.CMD', 'USERPROFILE': 'synthetic profile',
                    'APPDATA': 'synthetic roaming', 'LOCALAPPDATA': 'synthetic local',
                    'PROGRAMDATA': 'synthetic program data', 'PROGRAMFILES': 'synthetic programs',
                    'PROGRAMFILES(X86)': 'synthetic programs x86', 'TEMP': 'synthetic temporary',
                    'TMP': 'synthetic temporary', 'DOCKER_CONFIG': 'synthetic docker config'}
        environment = {key.title(): value for key, value in required.items()}
        environment.update({'OPENAI_API_KEY': 'synthetic-secret', 'AGENT_OPENAI_API_KEY': 'synthetic-secret',
                            'JAVA_TOOL_OPTIONS': 'synthetic-injected-option'})
        self.invoke(environment)
        self.assertGreaterEqual(len(self.calls), 2)
        for _, options in self.calls:
            actual = {key.upper(): value for key, value in options['env'].items()}
            self.assertEqual(actual, required)
            self.assertEqual(options['cwd'], self.project)
            self.assertFalse(options.get('shell', False))
        self.assertTrue(self.output.exists())

    def test_compose_plugin_is_probed_before_read_only_export(self):
        self.invoke({'PATH': '/tools'})
        self.assertEqual(self.calls[0][0], ['/tools/docker', 'compose', 'version'])
        command, options = self.calls[-1]
        self.assertEqual(command[:2], ['/tools/docker', 'compose'])
        self.assertIn('REPEATABLE READ READ ONLY', options['input'])
        self.assertIn("statement_timeout = '5s'", options['input'])
        self.assertEqual(json.loads(self.output.read_text())['installationTotals']['calls'], 0)

    def test_standalone_compose_works_when_plugin_is_unavailable(self):
        for docker_exists in (True, False):
            with self.subTest(docker_exists=docker_exists):
                self.plugin_works = False
                self.calls.clear()
                if not docker_exists:
                    self.programs.pop('docker')
                self.invoke({'PATH': '/tools'})
                self.assertEqual(self.calls[-2][0], ['/tools/docker-compose', 'version'])
                self.assertEqual(self.calls[-1][0][0], '/tools/docker-compose')
                self.output.unlink()

    def test_missing_compose_stops_before_query_and_preserves_no_false_artifact(self):
        self.programs.clear()
        with self.assertRaisesRegex(SystemExit, 'Docker Compose'):
            self.invoke()
        self.assertFalse(self.calls)
        self.assertFalse(self.output.exists())

    def test_discovery_errors_do_not_execute_a_query_or_print_child_diagnostics(self):
        for error in (PermissionError('synthetic-private-path'),
                      subprocess.TimeoutExpired(['synthetic-private-command'], 10,
                                                output='synthetic-secret', stderr='synthetic-secret')):
            with self.subTest(error=type(error).__name__):
                self.discovery_failure = error
                self.calls.clear()
                with self.assertRaisesRegex(SystemExit, 'Docker Compose') as raised:
                    self.invoke({'PATH': '/tools'})
                self.assertNotIn('synthetic', str(raised.exception))
                self.assertEqual(len(self.calls), 2)
                self.assertTrue(all(command[-1] == 'version' for command, _ in self.calls))
                self.assertFalse(self.output.exists())

    def test_child_start_failure_or_timeout_is_sanitized_without_creating_artifact(self):
        for error in (FileNotFoundError('synthetic-private-path'),
                      UnicodeDecodeError('utf-8', b'\xff', 0, 1, 'synthetic-invalid-byte'),
                      subprocess.TimeoutExpired(['synthetic-private-command'], 30,
                                                output='synthetic-secret', stderr='synthetic-secret')):
            with self.subTest(error=type(error).__name__):
                self.export_failure = error
                with self.assertRaises(SystemExit) as raised:
                    self.invoke({'PATH': '/tools'})
                self.assertNotIn('synthetic', str(raised.exception))
                self.assertIn('ledger export', str(raised.exception))
                self.assertFalse(self.output.exists())


if __name__ == '__main__':
    unittest.main()
