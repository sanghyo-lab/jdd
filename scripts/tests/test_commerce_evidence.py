import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'fixtures/commerce'))
from evidence_files import business_log, source_manifest
import reproduce_commerce as commerce
import reproduce_inventory as inventory


class CommerceEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='jdd-commerce-evidence-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.build = 'synthetic-evidence-build'
        self.prefix = 'jdd-v07-evidence'
        self.order = 'synthetic-order'
        self.log = self.root / 'runtime/evidence/logs/commerce' / self.build / 'business.jsonl'
        self.log.parent.mkdir(parents=True)
        self.event = {'buildId': self.build, 'event': 'ORDER_CREATED', 'orderId': self.order,
                      'productId': self.prefix + '-product'}
        self.log.write_text(json.dumps(self.event) + '\n', encoding='utf-8')
        self.directory = self.root / 'runtime/evidence/source' / self.build
        self.source = 'commerce-core/src/main/java/Example.java'
        self.code = self.directory / self.source
        self.code.parent.mkdir(parents=True)
        self.code.write_bytes(b'class Example {}\n')
        self.manifest = {'schemaVersion': '1.0', 'buildId': self.build, 'policyVersion': 'demo-v1',
                         'files': {self.source: hashlib.sha256(self.code.read_bytes()).hexdigest()}}
        self.save_manifest()

    def save_manifest(self):
        (self.directory / 'manifest.json').write_text(json.dumps(self.manifest), encoding='utf-8')

    def reader(self, kind):
        app = object.__new__(kind)
        app.build = self.build
        with patch.object(inventory, 'ROOT', self.root), patch.object(commerce, 'ROOT', self.root):
            if kind is inventory.InventoryReproduction:
                return app.evidence(self.prefix, [self.order])
            return app.business_evidence(self.prefix, [self.order], [])

    def test_complete_lines_keep_positions_and_partial_utf8_append_is_explicit(self):
        self.log.write_bytes(self.log.read_bytes() + b'{"details":"\xed\x95')
        rows, state = business_log(self.log, self.build)
        self.assertEqual(rows, [{'line': 1, 'event': self.event}])
        self.assertEqual(state, {'completeRecords': 1, 'incompleteTrailingLine': True})
        self.assertEqual(business_log(self.log.parent / 'missing.jsonl', self.build),
                         ([], {'completeRecords': 0, 'incompleteTrailingLine': False}))

    def test_corrupt_completed_records_cannot_be_hidden_between_valid_events(self):
        valid = json.dumps(self.event).encode() + b'\n'
        for bad in (b'{broken}\n', b'\xff\n', b'[]\n', b'null\n', b'{"buildId":"another"}\n'):
            with self.subTest(record=bad):
                self.log.write_bytes(valid + bad + valid)
                with self.assertRaises(AssertionError):
                    business_log(self.log, self.build)

    def test_both_reproduction_readers_reject_completed_log_corruption(self):
        self.log.write_text(json.dumps(self.event) + '\n{broken completed line}\n', encoding='utf-8')
        for kind in (inventory.InventoryReproduction, commerce.CommerceReproduction):
            with self.subTest(reader=kind.__name__), self.assertRaisesRegex(AssertionError, 'completed business log'):
                self.reader(kind)

    def test_both_reproduction_readers_verify_saved_source_bytes(self):
        for kind in (inventory.InventoryReproduction, commerce.CommerceReproduction):
            self.assertEqual(self.reader(kind)['sourceManifest'], self.manifest)
        self.code.write_text('changed after build', encoding='utf-8')
        for kind in (inventory.InventoryReproduction, commerce.CommerceReproduction):
            with self.subTest(reader=kind.__name__), self.assertRaisesRegex(AssertionError, 'checksum mismatch'):
                self.reader(kind)

    def test_manifest_mismatch_missing_files_and_unexpected_paths_fail(self):
        for name in ('fixtures/commerce/expected.md', '../outside.java',
                     'commerce-core/src/main/java/../../test/Answer.java',
                     'commerce-core\\src\\main\\java\\Example.java',
                     'commerce-core/src/main/java/Missing.java'):
            self.manifest['files'] = {name: '0' * 64}
            self.save_manifest()
            with self.subTest(path=name), self.assertRaises(AssertionError):
                source_manifest(self.root, self.build)
        self.manifest['buildId'] = 'another'
        self.save_manifest()
        with self.assertRaisesRegex(AssertionError, 'manifest/build'):
            source_manifest(self.root, self.build)

    def test_symlinked_source_is_not_accepted_even_with_matching_hash(self):
        external = self.root / 'outside.java'
        external.write_bytes(self.code.read_bytes())
        self.code.unlink()
        self.code.symlink_to(external)
        with self.assertRaisesRegex(AssertionError, 'symlinks'):
            source_manifest(self.root, self.build)

    def test_optional_policy_archive_is_checked_without_requiring_it_on_old_builds(self):
        self.assertEqual(source_manifest(self.root, self.build), self.manifest)
        policy = self.directory / 'policy/business-policy.md'
        policy.parent.mkdir()
        policy.write_text('합성 demo-v1 정책', encoding='utf-8')
        self.manifest['policy'] = {'version': 'demo-v1', 'path': 'policy/business-policy.md',
                                   'sha256': hashlib.sha256(policy.read_bytes()).hexdigest()}
        self.save_manifest()
        self.assertEqual(source_manifest(self.root, self.build), self.manifest)
        policy.write_text('changed policy', encoding='utf-8')
        with self.assertRaisesRegex(AssertionError, 'checksum mismatch'):
            source_manifest(self.root, self.build)
        self.manifest['policy']['version'] = 'another'
        self.save_manifest()
        with self.assertRaisesRegex(AssertionError, 'policy metadata'):
            source_manifest(self.root, self.build)


if __name__ == '__main__':
    unittest.main()
