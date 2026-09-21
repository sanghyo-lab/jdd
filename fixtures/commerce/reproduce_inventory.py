#!/usr/bin/env python3
"""Actual HTTP/PostgreSQL reproduction. No model calls, report stubs, or automatic DONE records."""
import argparse
import concurrent.futures
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess
import sys
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from jdd import Repository


class InventoryReproduction:
    def __init__(self):
        self.repo = Repository(ROOT)
        self.env = self.repo.read_environment()
        self.base = 'http://127.0.0.1:' + self.env.get('COMMERCE_PORT', '8080')
        self.build = self.http('GET', '/internal/runtime')[1]['buildId']
        self.results = []

    def http(self, method, path, body=None, request_id=None):
        headers = {'Content-Type': 'application/json'}
        if request_id:
            headers['X-Request-Id'] = request_id
        request = Request(self.base + path, data=None if body is None else json.dumps(body).encode(),
                          headers=headers, method=method)
        try:
            with urlopen(request, timeout=15) as response:
                if request_id:
                    assert response.headers.get('X-Request-Id') == request_id
                return response.status, json.load(response)
        except HTTPError as error:
            return error.code, json.load(error)

    def sql(self, sql, variables=None):
        args = [*self.repo.compose_command(), '--env-file', '.env', '-f', 'compose.yaml', 'exec', '-T', 'db',
                'psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-U', 'jdd_commerce',
                '-d', self.env.get('POSTGRES_DB', 'jdd')]
        for key, value in (variables or {}).items():
            args.extend(['-v', key + '=' + str(value)])
        result = subprocess.run(args, input=sql, text=True, cwd=ROOT, env=self.env, capture_output=True)
        if result.returncode:
            raise RuntimeError('Fixture SQL failed: ' + result.stderr.strip())
        return result.stdout.strip()

    def seed(self, prefix, stock):
        if not re.fullmatch(r'jdd-v07-[a-z0-9-]{1,60}', prefix):
            raise ValueError('Only the synthetic jdd-v07 namespace may be reset')
        variables = {'fixture_prefix': prefix, 'stock_quantity': stock}
        for name in ('reset.sql', 'seed.sql'):
            self.sql((ROOT / 'fixtures/commerce/VOC-07' / name).read_text(), variables)

    def permissions(self):
        args = [*self.repo.compose_command(), '--env-file', '.env', '-f', 'compose.yaml', 'exec', '-T', 'db',
                'psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-U', 'jdd_evidence',
                '-d', self.env.get('POSTGRES_DB', 'jdd')]
        tables = ('products', 'product_stock', 'orders', 'order_items', 'payments', 'refunds',
                  'coupons', 'customer_coupons', 'coupon_usages', 'inventory_movements')
        for table in tables:
            result = subprocess.run(args, input='SELECT count(*) FROM commerce.' + table + ';',
                                    text=True, cwd=ROOT, env=self.env, capture_output=True)
            assert result.returncode == 0, 'Evidence SELECT rejected on ' + table
        for sql in ('UPDATE commerce.products SET name=name WHERE false;',
                    'CREATE TABLE commerce.jdd_permission_probe (id integer);'):
            # Roll back even if permissions regress; no durable test table or data modification remains.
            result = subprocess.run(args, input='BEGIN READ WRITE; ' + sql + ' ROLLBACK;',
                                    text=True, cwd=ROOT, env=self.env, capture_output=True)
            assert result.returncode != 0 and 'permission denied' in result.stderr, result.stderr
        self.results.append({'id': 'EVIDENCE_SELECT_ONLY', 'status': 'PASSED', 'tables': tables,
                             'updateDenied': True, 'ddlDenied': True})

    def order(self, prefix, key, quantity=1):
        return self.http('POST', '/api/orders', {'customerId': prefix + '-customer-' + key[-1],
                'checkoutKey': key, 'items': [{'productId': prefix + '-product', 'quantity': quantity}],
                'customerCouponId': None}, key + '-request')

    def observation(self, prefix):
        # Prefix is generated and validated above; psql variables remain quoted even for synthetic inputs.
        return json.loads(self.sql("""
SELECT json_build_object(
 'stock', (SELECT quantity FROM commerce.product_stock WHERE product_id = :'p' || '-product'),
 'orderedQuantity', (SELECT COALESCE(sum(quantity),0) FROM commerce.order_items WHERE product_id = :'p' || '-product'),
 'orders', (SELECT COALESCE(json_agg(o ORDER BY o.checkout_key),'[]'::json) FROM commerce.orders o
            WHERE id IN (SELECT order_id FROM commerce.order_items WHERE product_id = :'p' || '-product')),
 'movements', (SELECT COALESCE(json_agg(m ORDER BY m.occurred_at,m.id),'[]'::json) FROM commerce.inventory_movements m
               WHERE product_id = :'p' || '-product'));
""", {'p': prefix}))

    def evidence(self, prefix, order_ids):
        file = ROOT / 'runtime/evidence/logs/commerce' / self.build / 'business.jsonl'
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            lines = file.read_text().splitlines() if file.exists() else []
            rows = []
            for number, line in enumerate(lines, 1):
                try:
                    event = json.loads(line)
                except json.JSONDecodeError:
                    continue  # A concurrently appended trailing line is retried.
                if (event.get('productId') == prefix + '-product' or event.get('orderId') in order_ids):
                    rows.append({'line': number, 'event': event})
            created = {r['event']['orderId'] for r in rows if r['event']['event'] == 'ORDER_CREATED'}
            if created == set(order_ids):
                break
            time.sleep(0.1)
        else:
            raise AssertionError('Committed order logs not exported before deadline')
        assert all(r['event']['buildId'] == self.build for r in rows)
        manifest = json.loads((ROOT / 'runtime/evidence/source' / self.build / 'manifest.json').read_text())
        assert manifest['buildId'] == self.build
        assert not any('/reproduction/' in name or 'fixtures/' in name or '/test/' in name for name in manifest['files'])
        return {'logPath': str(file.relative_to(ROOT)), 'logs': rows, 'sourceManifest': manifest}

    def concurrent(self, prefix, stock=1):
        self.seed(prefix, stock)
        keys = [prefix + '-a', prefix + '-b']
        status, barrier = self.http('POST', '/internal/reproduction/inventory-barrier',
                    {'productId': prefix + '-product', 'checkoutKeys': keys, 'timeoutMs': 8000})
        assert status == 200, ('Enable COMMERCE_REPRODUCTION_ENABLED=true for this local test', status, barrier)
        try:
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                futures = [pool.submit(self.order, prefix, key) for key in keys]
                responses = [future.result(timeout=15) for future in futures]
            _, view = self.http('GET', '/internal/reproduction/inventory-barrier/' + barrier['id'])
            actual = self.observation(prefix)
            record = {'prefix': prefix, 'responses': responses, 'barrier': view, 'database': actual}
            self.results.append(record)
            assert [code for code, _ in responses] == [201, 201], record
            assert view['status'] == 'COMPLETED', record
            assert len({a['backendPid'] for a in view['arrivals']}) == 2, record
            assert len({a['transactionId'] for a in view['arrivals']}) == 2, record
            assert [a['observedQuantity'] for a in view['arrivals']] == [stock, stock], record
            assert actual['stock'] == stock - 2 and actual['orderedQuantity'] == 2, record
            assert len(actual['orders']) == 2, record
            reserves = [m for m in actual['movements'] if m['movement_type'] == 'RESERVE']
            assert len(reserves) == 2 and sorted(m['quantity_after'] for m in reserves) == [stock - 2, stock - 1], record
            assert sum(m['quantity_delta'] for m in actual['movements']) == actual['stock'], record
            evidence = self.evidence(prefix, [order['id'] for _, order in responses])
            record['evidence'] = evidence
            reads = [r['event'] for r in evidence['logs'] if r['event']['event'] == 'INVENTORY_READ']
            assert len(reads) == 2 and all(r['details']['observedQuantity'] == stock for r in reads), record
            record['status'] = 'PASSED'
        finally:
            self.http('DELETE', '/internal/reproduction/inventory-barrier/' + barrier['id'])

    def controls(self, prefix):
        self.seed(prefix, 1)
        first = self.order(prefix, prefix + '-a')
        second = self.order(prefix, prefix + '-b')
        assert first[0] == 201 and second[0] == 422 and second[1]['code'] == 'INSUFFICIENT_STOCK'
        actual = self.observation(prefix)
        assert actual['stock'] == 0 and actual['orderedQuantity'] == 1
        self.results.append({'id': 'SEQUENTIAL', 'status': 'PASSED', 'database': actual})
        self.concurrent(prefix + '-enough', 2)
        timeout_prefix = prefix + '-timeout'
        self.seed(timeout_prefix, 1)
        status, barrier = self.http('POST', '/internal/reproduction/inventory-barrier', {
            'productId': timeout_prefix + '-product', 'checkoutKeys': [timeout_prefix + '-a', timeout_prefix + '-b'], 'timeoutMs': 300})
        assert status == 200
        try:
            response = self.order(timeout_prefix, timeout_prefix + '-a')
            actual = self.observation(timeout_prefix)
            assert response[0] == 503 and response[1]['retryable'] is True
            assert actual['stock'] == 1 and not actual['orders']
            self.results.append({'id': 'BARRIER_TIMEOUT_ROLLBACK', 'status': 'PASSED', 'response': response, 'database': actual})
        finally:
            self.http('DELETE', '/internal/reproduction/inventory-barrier/' + barrier['id'])
        assert self.order(timeout_prefix, timeout_prefix + '-a')[0] == 201
        self.results.append({'id': 'BARRIER_RELEASE_RECOVERY', 'status': 'PASSED'})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--runs', type=int, default=20)
    args = parser.parse_args()
    if not 1 <= args.runs <= 100:
        parser.error('--runs must be 1..100')
    run = InventoryReproduction()
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')
    report = ROOT / 'runtime/submission/commerce-reproductions' / (stamp + '-inventory.json')
    report.parent.mkdir(parents=True, exist_ok=True)
    prefix = 'jdd-v07-' + uuid.uuid4().hex[:12]
    result = {'startedAt': stamp, 'buildId': run.build, 'mode': 'commerce-http-postgresql',
              'liveModelCalls': 0, 'plannedConcurrencyRuns': args.runs, 'results': run.results}
    try:
        run.permissions()
        for index in range(args.runs):
            run.concurrent(prefix + '-' + str(index))
            print('VOC-07', index + 1, 'PASSED: 2 independent transactions, 2 orders, final stock -1', flush=True)
        run.controls(prefix + '-controls')
        result['status'] = 'PASSED'
    except Exception as error:
        result['status'] = 'FAILED'
        result['error'] = str(error)
        raise
    finally:
        report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
        print('Actual reproduction artifact:', report, flush=True)


if __name__ == '__main__':
    main()
