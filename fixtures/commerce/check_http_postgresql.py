#!/usr/bin/env python3
"""Run commerce's existing HTTP contracts against an isolated local PostgreSQL database.

The tests clear only jdd_commerce_http_test. No service restarts, model calls, or
changes to the application database are performed. Requires the local Compose DB.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from jdd import Repository

DATABASE = 'jdd_commerce_http_test'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report-dir', type=Path, help='New output directory; existing results are never replaced')
    args = parser.parse_args()
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')
    output = (args.report_dir or ROOT / 'runtime/submission/commerce-reproductions' / (stamp + '-http-postgresql')).resolve()
    output.mkdir(parents=True, exist_ok=False)
    repo = Repository(ROOT)
    config = repo.read_environment()
    compose = [*repo.compose_command(), '--env-file', '.env', '-f', 'compose.yaml', 'exec', '-T', 'db',
               'psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-U', config.get('POSTGRES_USER', 'jdd_admin'), '-d', 'postgres']
    probe = subprocess.run(compose, input="SELECT 1 FROM pg_database WHERE datname='" + DATABASE + "';",
                           cwd=ROOT, env=config, capture_output=True, text=True, check=True)
    if probe.stdout.strip() != '1':
        subprocess.run(compose, input='CREATE DATABASE ' + DATABASE + ' OWNER jdd_commerce;',
                       cwd=ROOT, env=config, capture_output=True, text=True, check=True)
    # Pass only JVM/OS settings. Provider credentials and model activation flags are excluded.
    allowed = ('PATH', 'HOME', 'USER', 'LOGNAME', 'SHELL', 'TMPDIR', 'LANG', 'LC_ALL', 'JAVA_HOME')
    env = {key: os.environ[key] for key in allowed if key in os.environ}
    env.update(JDD_COMMERCE_HTTP_TEST_DB_URL='jdbc:postgresql://127.0.0.1:' + config.get('POSTGRES_PORT', '5432')
               + '/' + DATABASE + '?currentSchema=commerce',
               JDD_COMMERCE_HTTP_TEST_DB_PASSWORD=config['COMMERCE_DB_PASSWORD'])
    wrapper = str(ROOT / 'gradlew.bat') if os.name == 'nt' else './gradlew'
    command = [wrapper, '--no-daemon', ':commerce-app:test', '--tests', 'com.jdd.commerce.CommerceHttpTest', '--rerun-tasks']
    result = {'startedAt': stamp, 'mode': 'POSTGRESQL_HTTP_CONTRACT', 'database': DATABASE,
              'commitSha': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
              'command': command, 'paidModelCalls': 0, 'actualModelQualityValidated': False}
    result['testBuildId'] = 'http-test'
    result['workingTreeDirty'] = bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip())
    result['verificationSourceSha256'] = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in (Path(__file__), ROOT / 'commerce-app/src/test/java/com/jdd/commerce/CommerceHttpTest.java')}
    started = time.time()
    with (output / 'command.log').open('w') as log:
        run = subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
    result.update(finishedAt=datetime.now(timezone.utc).isoformat(), exitCode=run.returncode)
    source = ROOT / 'commerce-app/build/test-results/test/TEST-com.jdd.commerce.CommerceHttpTest.xml'
    if source.is_file() and source.stat().st_mtime >= started:
        shutil.copyfile(source, output / source.name)
        suite = ET.parse(source).getroot()
        result['junit'] = {key: int(suite.attrib.get(key, 0)) for key in ('tests', 'failures', 'errors', 'skipped')}
    result['status'] = 'PASSED' if run.returncode == 0 and result.get('junit', {}).get('tests', 0) > 0 and all(
        result['junit'][key] == 0 for key in ('failures', 'errors', 'skipped')) else 'FAILED'
    (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({'status': result['status'], 'exitCode': run.returncode, 'junit': result.get('junit'),
                      'reportDirectory': str(output), 'paidModelCalls': 0}), flush=True)
    return 0 if result['status'] == 'PASSED' else (run.returncode or 1)


if __name__ == '__main__':
    raise SystemExit(main())
