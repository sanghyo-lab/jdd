"""Networkless workflow tests. Synthetic reports stay in temporary repositories, never project DONE."""
import copy
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from jdd import MVP_CASES, Repository, WorkflowError


class PreparedRepository(Repository):
    def __init__(self, root):
        super().__init__(root)
        self.commit = 'a' * 40
        self.content = 'b' * 64
        self.build = self.commit[:12] + '-' + self.content[:12]
        self.environment = {'JDD_MVP_LIVE': 'true', 'APP_RUNTIME': 'local', 'LLM_PROVIDER': 'codex_oauth',
                            'CODEX_MODEL': 'synthetic-configured-model', 'PATH': '/synthetic/bin',
                            'OPENAI_API_KEY': 'must-not-inherit-api-key', 'CODEX_AUTH_FILE': '/synthetic/auth.json',
                            'CODEX_ACCESS_TOKEN': 'must-not-inherit-token', 'DB_PASSWORD': 'must-not-inherit-db-secret',
                            'JDD_UNKNOWN_SECRET': 'must-not-inherit-unknown-secret', 'VOC_PORT': '18082'}
        self.observation = {'buildId': self.build, 'services': {
            role: {'service': role + '-app', 'buildId': self.build, 'commitSha': self.commit, 'businessReady': True}
            for role in ('commerce', 'agent', 'voc')}}
        self.observation['services']['agent'].update(workerEnabled=True, investigationModel='CODEX_OAUTH',
                llm={'runtime': 'local', 'provider': 'codex_oauth', 'configuredModel': 'synthetic-configured-model'})
        self.result = {'buildId': self.build, 'mode': 'live', 'model': 'synthetic-observed-model',
                       'cases': [{'id': case, 'status': 'PASSED', 'mocked': False} for case in sorted(MVP_CASES)]}
        self.checks = []
        self.commands = []
        self.after_check = lambda: None
        self.after_run = lambda: None
        self.runner_failure = False
        self.write_result = True
        self.preparation_failure = False
        self.after_preparation = lambda: None
        self.preparation_settings = lambda values: values
        self.preparation_closed = 0

    def read_environment(self): return dict(self.environment)
    def build_identity(self): return self.commit, self.content
    def smoke(self): return copy.deepcopy(self.observation)
    def verify(self): raise AssertionError('Live MVP must not call the ordinary stack-rebuilding verify path')
    def up(self): raise AssertionError('Live MVP must not activate or replace a provider')
    def check(self, env=None):
        self.checks.append(env)
        self.after_check()
    @contextmanager
    def prepare_mvp(self, directory):
        try:
            if self.preparation_failure:
                raise WorkflowError('synthetic preparation failure')
            manifest = directory / 'prepared-cases.json'
            manifest.write_text('{"synthetic":"preparation boundary test only"}\n')
            values = {'JDD_SCENARIO_MANIFEST': str(manifest.resolve()),
                      'JDD_SCENARIO_MANIFEST_SHA256': hashlib.sha256(manifest.read_bytes()).hexdigest(),
                      'JDD_SCENARIO_COORDINATOR': 'http://127.0.0.1:19999',
                      'JDD_SCENARIO_CAPABILITY': 'synthetic-single-use-capability-only'}
            self.after_preparation()
            yield types.SimpleNamespace(runner_environment=self.preparation_settings(values))
        finally:
            self.preparation_closed += 1
    def run(self, args, **kwargs):
        self.commands.append((args, kwargs))
        assert ':scenario-runner:run' in args, args
        report = next(arg.split('--report ', 1)[1] for arg in args if arg.startswith('--args='))
        if self.write_result:
            path = self.root / report
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(self.result if isinstance(self.result, str) else json.dumps(self.result))
        self.after_run()
        if self.runner_failure:
            raise WorkflowError('synthetic runner failure')
        return types.SimpleNamespace(returncode=0)


class LiveMvpTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='jdd-live-mvp-')
        self.addCleanup(self.temp.cleanup)
        self.repo = PreparedRepository(self.temp.name)
        self.latest = self.repo.root / 'runtime/scenarios.json'
        self.latest.parent.mkdir()
        self.previous = b'{"previous":"retained historical report"}\n'
        self.latest.write_bytes(self.previous)

    def assert_no_model_secrets(self, env):
        self.assertIsInstance(env, dict)
        for key in ('OPENAI_API_KEY', 'CODEX_AUTH_FILE', 'CODEX_ACCESS_TOKEN', 'DB_PASSWORD', 'JDD_UNKNOWN_SECRET'):
            self.assertNotIn(key, env)
        self.assertEqual(env['VOC_PORT'], '18082')

    def test_opt_in_and_pair_are_checked_before_any_mutation(self):
        for changes in ({'JDD_MVP_LIVE': ''}, {'APP_RUNTIME': 'test', 'LLM_PROVIDER': 'mock'},
                        {'LLM_PROVIDER': 'openai_api'}, {'CODEX_MODEL': 'replace-model'}):
            with self.subTest(changes=changes):
                before = dict(self.repo.environment)
                self.repo.environment.update(changes)
                with self.assertRaises(WorkflowError): self.repo.verify_mvp()
                self.assertEqual(self.repo.checks, [])
                self.assertEqual(self.repo.commands, [])
                self.assertEqual(self.latest.read_bytes(), self.previous)
                self.repo.environment = before

    def test_prepared_local_runtime_is_preserved_and_reports_are_archived(self):
        self.assertEqual(self.repo.verify_mvp(), self.repo.result)
        self.assertEqual(len(self.repo.checks), 1)
        self.assert_no_model_secrets(self.repo.checks[0])
        self.assertEqual(self.repo.checks[0]['APP_RUNTIME'], 'test')
        self.assertEqual(self.repo.checks[0]['LLM_PROVIDER'], 'mock')
        args, kwargs = self.repo.commands[0]
        self.assertEqual(args[0], 'gradlew.bat' if os.name == 'nt' else './gradlew')
        self.assert_no_model_secrets(kwargs['env'])
        self.assertEqual(self.repo.preparation_closed, 1)
        self.assertTrue(Path(kwargs['env']['JDD_SCENARIO_MANIFEST']).is_file())
        self.assertNotIn('JDD_SCENARIO_CAPABILITY', self.repo.checks[0])
        self.assertEqual(json.loads(self.latest.read_text()), self.repo.result)
        archived = list((self.repo.root / 'runtime/mvp').glob('*/previous-scenarios.json'))
        self.assertEqual(len(archived), 1)
        self.assertEqual(archived[0].read_bytes(), self.previous)

    def test_missing_stale_or_mock_services_fail_before_check_and_runner(self):
        initial = copy.deepcopy(self.repo.observation)
        changes = [('buildId', 'old'), ('commitSha', 'old'), ('businessReady', False), ('businessReady', 'true'),
                   ('workerEnabled', False), ('investigationModel', 'MOCK'), ('llm', None),
                   ('llm', {'runtime': 'local', 'provider': 'codex_oauth', 'configuredModel': 'different'})]
        for key, value in changes:
            with self.subTest(key=key, value=value):
                self.repo.observation = copy.deepcopy(initial)
                self.repo.observation['services']['agent'][key] = value
                with self.assertRaises(WorkflowError): self.repo.verify_mvp()
                self.assertEqual(self.repo.commands, [])
                self.assertEqual(self.repo.checks, [])
        self.repo.observation = copy.deepcopy(initial)
        del self.repo.observation['services']['voc']
        with self.assertRaises(WorkflowError): self.repo.verify_mvp()

    def test_build_change_during_check_stops_before_runner(self):
        self.repo.after_check = lambda: setattr(self.repo, 'content', 'c' * 64)
        with self.assertRaises(WorkflowError): self.repo.verify_mvp()
        self.assertEqual(self.repo.commands, [])
        self.assertEqual(self.latest.read_bytes(), self.previous)

    def test_runtime_change_after_runner_rejects_result_and_keeps_artifact(self):
        self.repo.after_run = lambda: self.repo.observation['services']['agent'].update(investigationModel='MOCK')
        with self.assertRaises(WorkflowError): self.repo.verify_mvp()
        results = list((self.repo.root / 'runtime/mvp').glob('*/scenarios.json'))
        self.assertEqual(len(results), 1)
        self.assertEqual(json.loads(results[0].read_text()), self.repo.result)
        self.assertEqual(self.latest.read_bytes(), self.previous)

    def test_runner_failure_keeps_previous_and_new_failure_reports(self):
        self.repo.runner_failure = True
        self.repo.result['cases'][0]['status'] = 'FAILED'
        with self.assertRaisesRegex(WorkflowError, 'synthetic runner failure'): self.repo.verify_mvp()
        self.assertEqual(self.latest.read_bytes(), self.previous)
        self.assertEqual(len(list((self.repo.root / 'runtime/mvp').glob('*/scenarios.json'))), 1)
        self.assertEqual(self.repo.preparation_closed, 1)
        failure = next((self.repo.root / 'runtime/mvp').glob('*/workflow-failure.json')).read_text()
        self.assertNotIn('synthetic-single-use-capability-only', failure)

    def test_preparation_failure_never_starts_a_model_or_replaces_previous_report(self):
        self.repo.preparation_failure = True
        with self.assertRaisesRegex(WorkflowError, 'preparation failure'):
            self.repo.verify_mvp()
        self.assertEqual(self.repo.commands, [])
        self.assertEqual(self.repo.preparation_closed, 1)
        self.assertEqual(self.latest.read_bytes(), self.previous)

    def test_configuration_changes_during_preparation_stop_before_runner(self):
        self.repo.after_preparation = lambda: self.repo.observation['services']['agent'].update(investigationModel='MOCK')
        with self.assertRaises(WorkflowError): self.repo.verify_mvp()
        self.assertEqual(self.repo.commands, [])
        self.assertEqual(self.repo.preparation_closed, 1)
        self.assertEqual(self.latest.read_bytes(), self.previous)

    def test_preparation_cannot_inject_secrets_or_external_recovery_destinations(self):
        mutations = [lambda values: dict(values, OPENAI_API_KEY='must-not-leak'),
                     lambda values: dict(values, JDD_SCENARIO_COORDINATOR='https://example.org'),
                     lambda values: dict(values, JDD_SCENARIO_COORDINATOR='http://127.0.0.1:65536'),
                     lambda values: dict(values, JDD_SCENARIO_MANIFEST_SHA256='a' * 64),
                     lambda values: dict(values, JDD_SCENARIO_MANIFEST=str(self.latest.resolve()))]
        for mutation in mutations:
            with self.subTest(mutation=mutations.index(mutation)):
                self.repo.preparation_settings = mutation
                with self.assertRaises(WorkflowError): self.repo.verify_mvp()
                self.assertEqual(self.repo.commands, [])
                self.assertEqual(self.latest.read_bytes(), self.previous)
        self.assertEqual(self.repo.preparation_closed, len(mutations))

    def test_missing_malformed_or_mocked_output_cannot_reuse_a_previous_success(self):
        for kind in ('missing', 'malformed', 'mocked'):
            with self.subTest(kind=kind):
                self.repo.write_result = kind != 'missing'
                if kind == 'malformed': self.repo.result = '{invalid json'
                if kind == 'mocked': self.repo.result = {'buildId': self.repo.build, 'mode': 'mock', 'model': 'mock', 'cases': []}
                with self.assertRaises((WorkflowError, ValueError, OSError)): self.repo.verify_mvp()
                self.assertEqual(self.latest.read_bytes(), self.previous)

    def test_deployed_selection_uses_windows_wrapper_without_oauth_or_api_secret(self):
        self.repo.environment.update(APP_RUNTIME='deployed', LLM_PROVIDER='openai_api', OPENAI_MODEL='synthetic-api')
        self.repo.observation['services']['agent'].update(investigationModel='OPENAI',
                llm={'runtime': 'deployed', 'provider': 'openai_api', 'configuredModel': 'synthetic-api'})
        # Replace only jdd's OS reference; no Windows filesystem is simulated or claimed.
        with patch('jdd.os', types.SimpleNamespace(name='nt')):
            self.repo.verify_mvp()
        self.assertEqual(self.repo.commands[0][0][0], 'gradlew.bat')
        self.assert_no_model_secrets(self.repo.commands[0][1]['env'])


if __name__ == '__main__': unittest.main()
