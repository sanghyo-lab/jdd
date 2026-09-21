"""Read-only integrity checks for commerce reproduction evidence, never Agent input."""
import hashlib
import json
from pathlib import PurePosixPath
import re


SOURCE_PREFIXES = (
    'commerce-app/src/main/java/', 'commerce-core/src/main/java/',
    'commerce-infra/src/main/java/', 'commerce-infra/src/main/resources/db/migration/',
)


def business_log(path, build_id):
    """Reject corrupt completed records; expose an in-flight trailing append separately."""
    data = path.read_bytes() if path.exists() else b''
    complete = data.split(b'\n')
    trailing = complete.pop()
    rows = []
    for number, line in enumerate(complete, 1):
        try:
            event = json.loads(line.decode('utf-8'))
        except (ValueError, UnicodeError) as failure:
            raise AssertionError('Invalid completed business log record: ' + str(path) + ':' + str(number)) from failure
        if not isinstance(event, dict) or event.get('buildId') != build_id:
            raise AssertionError('Business log record/build mismatch: ' + str(path) + ':' + str(number))
        rows.append({'line': number, 'event': event})
    return rows, {'completeRecords': len(rows), 'incompleteTrailingLine': bool(trailing)}


def checked_file(directory, name, expected_hash):
    if not isinstance(name, str) or '\\' in name:
        raise AssertionError('Snapshot path must be a relative POSIX path')
    relative = PurePosixPath(name)
    if relative.is_absolute() or '..' in relative.parts or str(relative) != name:
        raise AssertionError('Invalid snapshot path: ' + name)
    if not isinstance(expected_hash, str) or not re.fullmatch(r'[0-9a-f]{64}', expected_hash):
        raise AssertionError('Invalid snapshot checksum: ' + name)
    path = directory
    if path.is_symlink():
        raise AssertionError('Snapshot directory must not be a symlink')
    for component in relative.parts:
        path = path / component
        if path.is_symlink():
            raise AssertionError('Snapshot path must not follow symlinks: ' + name)
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected_hash:
        raise AssertionError('Snapshot file/checksum mismatch: ' + name)


def source_manifest(root, build_id):
    if not isinstance(build_id, str) or not re.fullmatch(r'[A-Za-z0-9._-]+', build_id) or build_id in ('.', '..'):
        raise AssertionError('Invalid evidence build ID')
    directory = root / 'runtime/evidence/source' / build_id
    path = directory / 'manifest.json'
    if directory.is_symlink() or path.is_symlink():
        raise AssertionError('Snapshot manifest must not follow symlinks')
    manifest = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(manifest, dict) or manifest.get('schemaVersion') != '1.0' or manifest.get('buildId') != build_id:
        raise AssertionError('Source manifest/build mismatch')
    files = manifest.get('files')
    if not isinstance(files, dict) or not files:
        raise AssertionError('Source manifest must contain runtime files')
    for name, checksum in files.items():
        if not name.startswith(SOURCE_PREFIXES) or any(marker in name for marker in ('/test/', '/reproduction/', 'fixtures/')):
            raise AssertionError('Non-runtime source in manifest: ' + name)
        checked_file(directory, name, checksum)
    if 'policy' in manifest:
        policy = manifest['policy']
        if (not isinstance(policy, dict) or policy.get('path') != 'policy/business-policy.md'
                or not isinstance(policy.get('version'), str) or not policy['version']
                or policy['version'] != manifest.get('policyVersion')):
            raise AssertionError('Invalid archived policy metadata')
        checked_file(directory, policy['path'], policy.get('sha256'))
    return manifest
