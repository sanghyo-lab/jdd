#!/usr/bin/env python3
"""Read a retained VOC-07 reproduction through real tools; inference is an explicit test double.

Does not seed, reset, restart services, open a tunnel, or call a paid model. Prepare the
provider's reproduction separately, then keep its DB/log/source evidence until this ends.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]


def run(command, *, cwd, env, **kwargs):
    return subprocess.run(command, cwd=cwd, env=env, check=True, text=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compose-dir', type=Path, default=ROOT)
    parser.add_argument('--inventory-artifact', type=Path, required=True)
    parser.add_argument('--report-dir', type=Path, required=True)
    args = parser.parse_args()
    project, output = args.compose_dir.resolve(), args.report_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    config = {}
    for line in (project / '.env').read_text().splitlines():
        if line and not line.startswith('#') and '=' in line:
            key, value = line.split('=', 1)
            config[key] = value.strip().strip('"').strip("'")
    # Do not forward inherited provider keys or unrelated environment to the test process.
    allowed = ('PATH', 'HOME', 'USER', 'LOGNAME', 'SHELL', 'TMPDIR', 'LANG', 'LC_ALL', 'JAVA_HOME',
               'DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_CONFIG', 'SSH_AUTH_SOCK')
    env = {key: os.environ[key] for key in allowed if key in os.environ}
    artifact = json.loads(args.inventory_artifact.read_text())
    if artifact.get('status') != 'PASSED' or artifact.get('mode') != 'commerce-http-postgresql':
        parser.error('A retained, passing real PostgreSQL/HTTP inventory artifact is required')
    case = next((item for item in artifact['results'] if 'prefix' in item and item['database']['stock'] == -1), None)
    if case is None or not case['prefix'].startswith('jdd-v07-'):
        parser.error('No retained synthetic oversold product exists in the artifact')
    order = case['database']['orders'][0]
    request = {'buildId': artifact['buildId'], 'productId': case['prefix'] + '-product',
               'orderId': order['id'], 'customerId': order['customer_id']}
    # Only identifiers reach the test model's tools; expected reports/fixture files are not model inputs.
    input_path = output / 'input.json'
    input_path.write_text(json.dumps(request, indent=2) + '\n')
    compose = ['docker', 'compose', '--env-file', '.env']
    if config.get('COMPOSE_PROJECT_NAME'):
        compose += ['-p', config['COMPOSE_PROJECT_NAME']]
    compose += ['exec', '-T', 'db', 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-U', config.get('POSTGRES_USER', 'jdd_admin'), '-d', 'postgres']
    probe = run(compose + ['-Atc', "SELECT 1 FROM pg_database WHERE datname = 'jdd_agent_handoff_test'"], cwd=project, env=env, capture_output=True)
    if probe.stdout.strip() != '1':
        run(compose + ['-c', 'CREATE DATABASE jdd_agent_handoff_test OWNER jdd_agent'], cwd=project, env=env)
    base = 'jdbc:postgresql://127.0.0.1:' + config.get('POSTGRES_PORT', '5432') + '/'
    env.update(JDD_HANDOFF_AGENT_DB_URL=base+'jdd_agent_handoff_test?currentSchema=agent',
               JDD_HANDOFF_AGENT_DB_PASSWORD=config['AGENT_DB_PASSWORD'],
               JDD_HANDOFF_EVIDENCE_DB_URL=base+config.get('POSTGRES_DB', 'jdd'),
               JDD_HANDOFF_EVIDENCE_DB_PASSWORD=config['EVIDENCE_DB_PASSWORD'],
               JDD_HANDOFF_SOURCE_ROOT=str(project/'runtime/evidence/source'),
               JDD_HANDOFF_LOG_ROOT=str(project/'runtime/evidence/logs/commerce'),
               JDD_HANDOFF_POLICY_PATH=str(project/'docs/business-policy.md'),
               JDD_HANDOFF_INPUT_PATH=str(input_path), JDD_HANDOFF_REPORT_PATH=str(output/'result.json'))
    command = ['./gradlew', '--no-daemon', ':agent-app:test', '--tests', 'com.jdd.agent.CommerceHandoffTest', '--rerun-tasks']
    record = {'startedAt': datetime.now(timezone.utc).isoformat(), 'command': command,
              'mode': 'MOCK_MODEL_REAL_COMMERCE_POSTGRES', 'paidModelCalls': 0,
              'inventoryArtifact': str(args.inventory_artifact.resolve())}
    with (output/'command.log').open('w') as log:
        result = subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
    record.update(finishedAt=datetime.now(timezone.utc).isoformat(), exitCode=result.returncode)
    (output/'command.json').write_text(json.dumps(record, indent=2)+'\n')
    print(json.dumps({'exitCode': result.returncode, 'reportDirectory': str(output), 'paidModelCalls': 0}), flush=True)
    return result.returncode


if __name__ == '__main__':
    raise SystemExit(main())
