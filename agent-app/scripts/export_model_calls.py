#!/usr/bin/env python3
"""Export persisted model-call observations without invoking models or changing the ledger.

Reads the existing Compose PostgreSQL as jdd_agent in one REPEATABLE READ READ ONLY
transaction. This local diagnostic is not a new runner API or proof of model quality.
An optional investigation filter never narrows the installation-wide budget totals.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from uuid import UUID

ROOT = Path(__file__).resolve().parents[2]

QUERY = """
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '5s';
WITH selected AS (
 SELECT c.*, c.request_json::jsonb AS request, c.receipt_json::jsonb AS receipt,
        i.view_json::jsonb->>'status' AS investigation_status
 FROM agent.model_calls c JOIN agent.investigations i ON i.investigation_id = c.investigation_id
 WHERE :'investigation_id' = '' OR c.investigation_id = :'investigation_id'
 ORDER BY c.created_at, c.call_id LIMIT 1001
), totals AS (
 SELECT count(*) AS calls,
   count(*) FILTER (WHERE state <> 'CANCELLED') AS counted_calls,
   count(*) FILTER (WHERE state = 'UNKNOWN') AS unknown_calls,
   coalesce(sum(confirmed_usd) FILTER (WHERE state = 'CONFIRMED'), 0) AS confirmed,
   coalesce(sum(reserved_usd) FILTER (WHERE state = 'UNKNOWN'), 0) AS unknown,
   coalesce(sum(reserved_usd) FILTER (WHERE state IN ('RESERVED','DISPATCHED')), 0) AS reserved
 FROM agent.model_calls
)
SELECT json_build_object(
 'schemaVersion', 'agent-local-ledger-export-v1', 'observedAt', transaction_timestamp(),
 'transactionReadOnly', current_setting('transaction_read_only'),
 'transactionIsolation', current_setting('transaction_isolation'),
 'investigationFilter', nullif(:'investigation_id', ''),
 'investigationExists', CASE WHEN :'investigation_id' = '' THEN NULL ELSE
    EXISTS(SELECT 1 FROM agent.investigations WHERE investigation_id = :'investigation_id') END,
 'budget', (SELECT json_build_object('scope', scope, 'limitUsd', limit_usd::text,
    'callsPerInvestigation', calls_per_investigation, 'concurrentCalls', concurrent_calls,
    'maximumCalls', maximum_calls) FROM agent.demo_budget WHERE budget_id = 1),
 'installationTotals', (SELECT json_build_object('calls', calls, 'countedCalls', counted_calls,
    'unknownCalls', unknown_calls, 'confirmedUsd', confirmed::text, 'unknownLiabilityUsd', unknown::text,
    'reservedUsd', reserved::text, 'committedUsd', (confirmed + unknown + reserved)::text) FROM totals),
 'calls', coalesce((SELECT json_agg(json_build_object(
    'callId', call_id, 'investigationId', investigation_id, 'investigationStatus', investigation_status,
    'state', state, 'reservedUsd', reserved_usd::text, 'confirmedUsd', confirmed_usd::text,
    'createdAt', created_at, 'updatedAt', updated_at,
    'attempt', request->'attempt', 'requestedModel', request->'pricing'->'model',
    'pricing', request->'pricing', 'endpoint', request->'endpoint', 'requestedServiceTier', request->'serviceTier',
    'promptVersion', request->'promptVersion', 'promptHash', request->'promptHash',
    'toolSchemaVersion', request->'toolSchemaVersion',
    'inputTokenLimit', request->'inputTokenLimit', 'outputTokenLimit', request->'outputTokenLimit',
    'providerRequestId', receipt->'providerRequestId', 'actualModel', receipt->'actualModel',
    'usage', receipt->'usage', 'outcome', receipt->'outcome', 'finishedAt', receipt->'finishedAt',
    'actualServiceTier', receipt->'actualServiceTier', 'tariffVerified', receipt->'tariffVerified'
 ) ORDER BY created_at, call_id) FROM selected), '[]'::json)
);
COMMIT;
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compose-dir', type=Path, default=ROOT)
    parser.add_argument('--investigation-id', help='UUID; omit to include all local ledger calls')
    parser.add_argument('--database', help='Existing local database; default is the Compose POSTGRES_DB')
    parser.add_argument('--output', type=Path, required=True, help='New ignored local JSON artifact path')
    args = parser.parse_args()
    if args.investigation_id:
        try:
            args.investigation_id = str(UUID(args.investigation_id))
        except ValueError:
            parser.error('investigation-id must be a UUID')
    if args.database and not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]{0,62}', args.database):
        parser.error('database must be a simple PostgreSQL identifier')
    output = args.output.resolve()
    if output.exists():
        parser.error('Use a new output path; previous observations must remain intact')
    project = args.compose_dir.resolve()
    allowed = ('PATH', 'HOME', 'USER', 'LOGNAME', 'TMPDIR', 'LANG', 'LC_ALL',
               'DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_CONFIG')
    environment = {key: os.environ[key] for key in allowed if key in os.environ}
    # The container supplies its own Agent password; no credential is placed in argv or output.
    command = ['docker', 'compose', '--env-file', '.env', '--env-file', 'runtime/build.env',
               '-f', 'compose.yaml', 'exec', '-T', 'db', 'sh', '-c',
               'PGPASSWORD="$AGENT_DB_PASSWORD" exec psql -X -qAt -v ON_ERROR_STOP=1 '
               '-h 127.0.0.1 -U jdd_agent -d "${1:-$POSTGRES_DB}" -v "investigation_id=$2"',
               'export-jdd-model-calls', args.database or '', args.investigation_id or '']
    result = subprocess.run(command, cwd=project, env=environment, input=QUERY,
                            text=True, capture_output=True, timeout=30)
    if result.returncode:
        # Connection/configuration errors can contain local paths or environment values.
        raise SystemExit('Read-only ledger export failed; check the local Compose DB and Agent migrations')
    report = json.loads(result.stdout)
    if report['transactionReadOnly'] != 'on' or report['transactionIsolation'] != 'repeatable read':
        raise SystemExit('Expected a consistent read-only snapshot')
    if len(report['calls']) > 1000:
        raise SystemExit('More than 1000 calls selected; filter by investigation instead of silently truncating')
    report['exportedAt'] = datetime.now(timezone.utc).isoformat()
    report['interpretation'] = {
        'scope': 'This installation only; not the provider account balance or other PCs',
        'cost': 'USD decimal strings calculated by the recorded price version, not a provider invoice',
        'usage': 'Per HTTP response; null means unobserved. Cache/reasoning are partitions, not additional total tokens',
        'unknownLiability': 'Full reservation retained for unresolved usage; not an observed charge',
        'timestamps': 'Reservation/settlement wall-clock times; not isolated network or model latency',
        'quality': 'No final-model or quality decision is inferred from a ledger state'
    }
    payload = (json.dumps(report, ensure_ascii=False, indent=2) + '\n').encode()
    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'wb') as stream:
        stream.write(payload)
    print(json.dumps({'output': str(output), 'selectedCalls': len(report['calls']),
                      'sha256': hashlib.sha256(payload).hexdigest(), 'modelCallsInvoked': 0}))


if __name__ == '__main__':
    main()
