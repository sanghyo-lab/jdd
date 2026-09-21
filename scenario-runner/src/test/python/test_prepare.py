"""Offline tests of the parent-owned preparation/lifecycle boundary; no Docker or models."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import threading
import time
import socket
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
            instance.server.daemon_threads = False
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

    def test_close_waits_for_accepted_restart_to_restore_before_returning(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = prepare.PreparedRun(FakeRepository(), directory)
            instance.server = prepare.ThreadingHTTPServer(('127.0.0.1', 0), instance._handler())
            instance.server.daemon_threads = False
            instance.thread = threading.Thread(target=instance.server.serve_forever, daemon=True)
            instance.thread.start()
            started, release, restored, closed = (threading.Event() for _ in range(4))
            def restart():
                started.set()
                self.assertTrue(release.wait(5))
                restored.set()
                return {'status': 'PASSED'}
            def request():
                url = 'http://127.0.0.1:%d/restart-voc' % instance.server.server_port
                body = json.dumps({'runId': instance.run_id}).encode()
                with urlopen(Request(url, data=body, method='POST', headers={
                        'X-Jdd-Scenario-Capability': instance.token}), timeout=5) as response:
                    self.assertEqual(200, response.status)
            def close():
                instance._close()
                closed.set()
            with patch.object(instance, '_restart', side_effect=restart):
                caller = threading.Thread(target=request); caller.start()
                self.assertTrue(started.wait(3))
                closer = threading.Thread(target=close); closer.start()
                self.assertFalse(closed.wait(0.8))
                release.set(); caller.join(5); closer.join(5)
                self.assertTrue(restored.is_set() and closed.is_set())

    def test_partial_request_body_cannot_outlive_coordinator_shutdown(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = prepare.PreparedRun(FakeRepository(), directory)
            instance.server = prepare.ThreadingHTTPServer(('127.0.0.1', 0), instance._handler())
            instance.server.daemon_threads = False
            instance.thread = threading.Thread(target=instance.server.serve_forever, daemon=True)
            instance.thread.start()
            with socket.create_connection(instance.server.server_address, timeout=3) as connection:
                wire = ('POST /restart-voc HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 100\r\n'
                        'X-Jdd-Scenario-Capability: ' + instance.token + '\r\n\r\n{').encode()
                connection.sendall(wire)
                time.sleep(0.05)
                start = time.monotonic()
                instance._close()
                self.assertLess(time.monotonic() - start, 4)
                self.assertFalse(instance.used)


if __name__ == '__main__': unittest.main()
