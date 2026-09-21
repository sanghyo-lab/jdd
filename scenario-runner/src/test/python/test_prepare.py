"""Offline tests of the parent-owned preparation/lifecycle boundary; no Docker or models."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / 'scripts'))
spec = importlib.util.spec_from_file_location('scenario_prepare_under_test', ROOT / 'scenario-runner/scripts/prepare.py')
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class FakeRepository:
    root = ROOT
    def __init__(self, env=None): self.env = env or {}
    def read_environment(self): return dict(self.env)


class PreparationBoundaryTest(unittest.TestCase):
    def test_import_uses_this_clone_and_opt_in_precedes_dependencies(self):
        self.assertEqual(ROOT, prepare.ROOT)
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(prepare, 'CommerceReproduction') as app:
                with self.assertRaisesRegex(RuntimeError, 'COMMERCE_REPRODUCTION_ENABLED'):
                    with prepare.PreparedRun(FakeRepository(), directory): pass
                app.assert_not_called()

    def test_original_result_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'result.json'
            prepare.write_new(path, {'status': 'FAILED'})
            with self.assertRaises(FileExistsError): prepare.write_new(path, {'status': 'PASSED'})
            self.assertEqual('FAILED', json.loads(path.read_text())['status'])

    def test_coordinator_requires_exact_capability_run_and_one_use(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = prepare.PreparedRun(FakeRepository(), directory)
            instance.server = prepare.ThreadingHTTPServer(('127.0.0.1', 0), instance._handler())
            instance.thread = threading.Thread(target=instance.server.serve_forever, daemon=True)
            instance.thread.start()
            url = 'http://127.0.0.1:%d/restart-voc' % instance.server.server_port
            def send(token, body):
                request = Request(url, data=json.dumps(body).encode(), method='POST',
                                  headers={'X-Jdd-Scenario-Capability': token, 'Content-Type': 'application/json'})
                return urlopen(request, timeout=3)
            try:
                with patch.object(instance, '_restart', return_value={'status': 'PASSED'}) as restart:
                    with self.assertRaises(HTTPError) as failure: send('wrong', {'runId': instance.run_id})
                    self.assertEqual(404, failure.exception.code)
                    with self.assertRaises(HTTPError) as failure: send(instance.token, {'runId': instance.run_id, 'command': 'docker'})
                    self.assertEqual(400, failure.exception.code)
                    restart.assert_not_called()
                    with send(instance.token, {'runId': instance.run_id}) as response: self.assertEqual(200, response.status)
                    with self.assertRaises(HTTPError) as failure: send(instance.token, {'runId': instance.run_id})
                    self.assertEqual(409, failure.exception.code)
                    restart.assert_called_once_with()
            finally: instance._close()

    def test_runtime_change_never_substitutes_a_new_container(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = prepare.PreparedRun(FakeRepository(), directory)
            instance.container, instance.project = 'a' * 64, 'jdd-scenario-test'
            with patch.object(instance, '_docker', side_effect=[
                    json.dumps({'StartedAt': 'start', 'Running': True, 'Pid': 7}), 'different-project', 'voc']):
                with self.assertRaisesRegex(RuntimeError, 'outside'): instance._container_state()


if __name__ == '__main__': unittest.main()
