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
    parser.add_argument('--business-artifact', type=Path, help='Optional retained VOC-01~06 PostgreSQL reproduction artifact')
    parser.add_argument('--report-dir', type=Path, required=True)
    args = parser.parse_args()
    project, output = args.compose_dir.resolve(), args.report_dir.resolve()
    # Preserve failed and successful attempts separately; never replace earlier evidence.
    output.mkdir(parents=True, exist_ok=False)
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
    business_cases = []
    if args.business_artifact:
        business = json.loads(args.business_artifact.read_text())
        if business.get('status') != 'PASSED' or business.get('mode') != 'commerce-http-postgresql' or business.get('buildId') != artifact['buildId']:
            parser.error('Business and inventory artifacts must pass with the same real commerce buildId')
        for number in range(1, 7):
            case_id = f'VOC-{number:02d}'
            retained = next((item for item in business['results'] if item['id'] == case_id and item['status'] == 'PASSED'), None)
            if retained is None or not retained['prefix'].startswith(f'jdd-v{number:02d}-'):
                parser.error('Each business case needs retained synthetic identifiers')
            prefix = retained['prefix']
            business_cases.append(retained)
        identifiers = [{'caseId': item['id'], 'buildId': business['buildId'],
                        'customerId': item['prefix'] + '-customer', 'productId': item['prefix'] + '-product',
                        'checkoutKeys': sorted({row['event']['checkoutKey'] for row in item['evidence']['logs']
                                               if row['event'].get('checkoutKey')})} for item in business_cases]
        business_input = output / 'business-input.json'
        business_input.write_text(json.dumps(identifiers, indent=2) + '\n')
        env['JDD_HANDOFF_BUSINESS_INPUT_PATH'] = str(business_input)
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
    # Expected DB/log values are read only here, outside the test model's process/input.
    # Compare all captured orders, payments/refunds and coupon state, including normal controls.
    if result.returncode == 0 and business_cases:
        comparison = {'status': 'FAILED', 'actualModelQualityValidated': False, 'cases': []}
        try:
            for case in business_cases:
                observed = json.loads((output / 'business' / (case['id'] + '.json')).read_text())
                count = compare_retained_business(case, observed)
                comparison['cases'].append({'caseId': case['id'], 'status': 'PASSED',
                                            'comparedFields': count, 'investigationId': observed['investigation']['investigationId']})
            comparison['status'] = 'PASSED'
        finally:
            (output / 'business-comparison.json').write_text(json.dumps(comparison, indent=2) + '\n')
    print(json.dumps({'exitCode': result.returncode, 'reportDirectory': str(output), 'paidModelCalls': 0}), flush=True)
    return result.returncode


def compare_retained_business(expected, actual):
    tables = {'orders': ('orders', ('id', 'customer_id', 'checkout_key', 'request_id', 'status', 'subtotal', 'discount_amount', 'total_amount', 'customer_coupon_id')),
              'payments': ('payments', ('id', 'order_id', 'request_key', 'method', 'status', 'amount', 'provider_reference')),
              'refunds': ('refunds', ('id', 'order_id', 'payment_id', 'request_key', 'status', 'amount', 'failure_code')),
              'customer_coupons': ('coupons', ('id', 'customer_id', 'coupon_id', 'status')),
              'coupons': ('policies', ('id', 'discount_type', 'min_order_amount', 'fixed_discount_amount', 'discount_rate', 'max_discount_amount')),
              'coupon_usages': ('usages', ('id', 'customer_coupon_id', 'order_id', 'status', 'discount_amount'))}
    count = 0
    for table, (key, columns) in tables.items():
        rows = {}
        for evidence in actual['evidence']:
            if evidence['type'] == 'DATA' and evidence['source'].get('table') == table:
                for row in evidence['content']['rows']:
                    projected = {column: row[column] for column in columns}
                    if row['id'] in rows:
                        assert rows[row['id']] == projected, (expected['id'], table, 'contradictory stored rows')
                    rows[row['id']] = projected
        wanted = {row['id']: {column: row[column] for column in columns} for row in expected['database'][key]}
        assert rows == wanted, (expected['id'], table, 'stored Agent evidence differs from retained provider data')
        count += len(wanted) * len(columns)
    events = {}
    for evidence in actual['evidence']:
        if evidence['type'] == 'LOG':
            entry = evidence['content']['entry']
            assert json.loads(evidence['content']['raw']) == entry, (expected['id'], 'stored raw log differs from parsed entry')
            if entry['eventId'] in events:
                assert events[entry['eventId']] == entry, (expected['id'], 'conflicting stored event')
            events[entry['eventId']] = entry
    for row in expected['evidence']['logs']:
        entry = row['event']
        assert events.get(entry['eventId']) == entry, (expected['id'], 'missing or changed retained business log event')
    return count


if __name__ == '__main__':
    raise SystemExit(main())
