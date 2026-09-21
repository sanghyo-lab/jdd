#!/usr/bin/env python3
"""Explicit, local synthetic preparation. Never calls a model or changes a provider.

The workflow parent owns this module. The Java child receives only a manifest,
loopback coordinator URL and a one-use restart capability, never DB credentials.
"""
from datetime import datetime, timezone
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import secrets
import subprocess
import sys
import threading
import time
from urllib.error import URLError, HTTPError
from urllib.request import urlopen
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'fixtures/commerce'))
from reproduce_commerce import CommerceReproduction
from evidence_files import business_log, source_manifest


def now():
    return datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z')


def write_new(path, value):
    with path.open('x', encoding='utf-8') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    path.chmod(0o600)


class PreparedRun:
    """Context-managed fixed preparation and exactly one VOC restart.

    Usage by scripts/jdd.py: with PreparedRun(repo, run_directory) as prepared:
        child_env.update(prepared.runner_environment)
        run_the_runner(child_env)
    No arbitrary SQL, shell text, paths or container IDs are accepted over HTTP.
    """

    def __init__(self, repository, directory):
        self.repo = repository
        self.directory = Path(directory).resolve()
        self.run_id = str(uuid.uuid4())
        self.env = repository.read_environment()
        self.token = secrets.token_urlsafe(32)
        self.server = None
        self.thread = None
        self.used = False
        self.closing = False
        self.lock = threading.Lock()
        self.app = None
        self.manifest_path = self.directory / 'prepared-cases.json'
        self.runner_environment = {}

    def __enter__(self):
        if self.env.get('COMMERCE_REPRODUCTION_ENABLED') != 'true':
            raise RuntimeError('COMMERCE_REPRODUCTION_ENABLED=true is required for explicit synthetic preparation')
        if self.directory.is_symlink() or self.manifest_path.exists():
            raise RuntimeError('Use a new immutable scenario run directory')
        self.directory.mkdir(parents=True, exist_ok=True)
        try:
            self.app = CommerceReproduction()
            self.build = self.app.build
            self.snapshot = source_manifest(ROOT, self.build)
            if self.snapshot.get('workingTreeDirty') is not False:
                raise RuntimeError('Commit the source before preparing live scenario evidence')
            self.container = self._compose('ps', '-q', 'voc').strip()
            if not re.fullmatch(r'[a-f0-9]{12,64}', self.container):
                raise RuntimeError('Exactly one existing VOC container is required')
            self.project = self.env.get('COMPOSE_PROJECT_NAME', 'jdd')
            if not re.fullmatch(r'[a-z0-9][a-z0-9_-]*', self.project):
                raise RuntimeError('Invalid Compose project')
            self._container_state()
            cases = self._prepare_cases()
            self.server = ThreadingHTTPServer(('127.0.0.1', 0), self._handler())
            # server_close waits for any accepted restart/restoration to finish.
            self.server.daemon_threads = False
            self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
            self.thread.start()
            manifest = {'schemaVersion': '1.0', 'runId': self.run_id, 'buildId': self.build,
                        'preparedAt': now(), 'modelCalls': 0, 'mode': 'actual-http-postgresql',
                        'sourceManifest': self.snapshot, 'cases': cases}
            write_new(self.manifest_path, manifest)
            self.runner_environment = {
                'JDD_SCENARIO_MANIFEST': str(self.manifest_path),
                'JDD_SCENARIO_MANIFEST_SHA256': hashlib.sha256(self.manifest_path.read_bytes()).hexdigest(),
                'JDD_SCENARIO_COORDINATOR': 'http://127.0.0.1:' + str(self.server.server_port),
                'JDD_SCENARIO_CAPABILITY': self.token,
            }
            return self
        except BaseException as failure:
            self._close()
            if not (self.directory / 'preparation-failure.json').exists():
                write_new(self.directory / 'preparation-failure.json', {
                    'runId': self.run_id, 'status': 'FAILED', 'failureType': type(failure).__name__,
                    'modelCalls': 0, 'recordedAt': now()})
            raise

    def __exit__(self, exc_type, exc_value, traceback):
        self._close()
        return False

    def _close(self):
        with self.lock:
            self.closing = True
        if self.server:
            self.server.shutdown()
            self.server.server_close()
        if self.thread:
            self.thread.join(timeout=5)

    def _compose(self, *args):
        return self.repo.run([*self.repo.compose_command(), '--env-file', '.env', '-f', 'compose.yaml', *args],
                             capture=True, env=self.env).stdout

    def _docker(self, *args):
        result = subprocess.run(['docker', *args], cwd=self.repo.root, env=self.env,
                                capture_output=True, text=True,
                                timeout=30 if args[0] in ('stop', 'start') else 10)
        if result.returncode:
            raise RuntimeError('Fixed container lifecycle command failed')
        return result.stdout.strip()

    def _container_state(self):
        # Only selected labels/state, never environment or full inspect output.
        value = json.loads(self._docker('inspect', '--format',
            '{{json .State}}', self.container))
        project = self._docker('inspect', '--format',
            '{{index .Config.Labels "com.docker.compose.project"}}', self.container)
        service = self._docker('inspect', '--format',
            '{{index .Config.Labels "com.docker.compose.service"}}', self.container)
        if project != self.project or service != 'voc':
            raise RuntimeError('Refusing a container outside the selected VOC project')
        return {'containerId': self.container, 'startedAt': value['StartedAt'],
                'running': value['Running'], 'pid': value['Pid']}

    def _runtime(self):
        base = 'http://127.0.0.1:' + self.env.get('VOC_PORT', '8082')
        with urlopen(base + '/internal/runtime', timeout=2) as response:
            value = json.load(response)
        if value.get('buildId') != self.build:
            raise RuntimeError('VOC build changed during recovery')
        return value

    def _restart(self):
        before = self._container_state()
        runtime_before = self._runtime()
        if not before['running']:
            raise RuntimeError('VOC must be running before the recovery probe')
        record = {'schemaVersion': '1.0', 'runId': self.run_id, 'buildId': self.build,
                  'status': 'FAILED', 'startedAt': now(), 'before': before,
                  'runtimeBefore': runtime_before, 'unavailableObserved': False}
        try:
            self._docker('stop', '--time', '10', self.container)
            try:
                self._runtime()
            except (URLError, ConnectionError, TimeoutError):
                record['unavailableObserved'] = True
            if not record['unavailableObserved']:
                raise RuntimeError('HTTP unavailability was not observed while VOC was stopped')
            self._docker('start', self.container)
            deadline = time.monotonic() + 60
            while True:
                try:
                    record['runtimeAfter'] = self._runtime()
                    break
                except (URLError, ConnectionError, TimeoutError):
                    if time.monotonic() >= deadline:
                        raise RuntimeError('VOC did not recover within 60 seconds')
                    time.sleep(0.25)
            record['after'] = self._container_state()
            if (record['after']['startedAt'] == before['startedAt']
                    or not record['after']['running'] or record['runtimeAfter'] != runtime_before):
                raise RuntimeError('VOC restart identity/runtime validation failed')
            record['status'] = 'PASSED'
        finally:
            # The originally running container is restored even if the probe fails.
            try:
                if not self._container_state()['running']:
                    self._docker('start', self.container)
            except Exception as failure:
                record['restorationFailure'] = type(failure).__name__
                record['status'] = 'FAILED'
            record['finishedAt'] = now()
            write_new(self.directory / 'voc-recovery.json', record)
        if record['status'] != 'PASSED':
            raise RuntimeError('VOC recovery failed')
        return record

    def _handler(self):
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def setup(self):
                super().setup()
                self.connection.settimeout(3)

            def log_message(self, *args):
                pass  # Never log the capability or incoming headers.

            def do_POST(self):
                if (self.client_address[0] != '127.0.0.1' or self.path != '/restart-voc'
                        or self.headers.get('X-Jdd-Scenario-Capability') != owner.token):
                    self.send_error(404)
                    return
                try:
                    size = int(self.headers.get('Content-Length', '0'))
                    if not 0 < size <= 1024:
                        raise ValueError()
                    body = json.loads(self.rfile.read(size))
                    if body != {'runId': owner.run_id}:
                        raise ValueError()
                except (ValueError, UnicodeError):
                    self.send_error(400)
                    return
                except (TimeoutError, OSError):
                    self.close_connection = True
                    return
                with owner.lock:
                    if owner.used or owner.closing:
                        self.send_error(409)
                        return
                    owner.used = True
                try:
                    result, status = owner._restart(), 200
                except Exception as failure:
                    result, status = {'status': 'FAILED', 'failureType': type(failure).__name__}, 500
                encoded = json.dumps(result).encode()
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(encoded)))
                self.end_headers()
                self.wfile.write(encoded)

        return Handler

    def _database(self, prefix):
        # Fixed, bounded namespace queries; no SQL or identifiers come from HTTP.
        return json.loads(self.app.sql("""
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT json_build_object(
 'products', (SELECT coalesce(json_agg(t),'[]'::json) FROM commerce.products t WHERE left(id,length(:'p')+1)=:'p'||'-'),
 'product_stock', (SELECT coalesce(json_agg(t),'[]'::json) FROM commerce.product_stock t WHERE left(product_id,length(:'p')+1)=:'p'||'-'),
 'orders', (SELECT coalesce(json_agg(t),'[]'::json) FROM commerce.orders t WHERE left(customer_id,length(:'p')+1)=:'p'||'-'),
 'order_items', (SELECT coalesce(json_agg(t),'[]'::json) FROM commerce.order_items t WHERE left(product_id,length(:'p')+1)=:'p'||'-'),
 'payments', (SELECT coalesce(json_agg(t),'[]'::json) FROM commerce.payments t WHERE order_id IN (SELECT id FROM commerce.orders WHERE left(customer_id,length(:'p')+1)=:'p'||'-')),
 'refunds', (SELECT coalesce(json_agg(t),'[]'::json) FROM commerce.refunds t WHERE order_id IN (SELECT id FROM commerce.orders WHERE left(customer_id,length(:'p')+1)=:'p'||'-')),
 'customer_coupons', (SELECT coalesce(json_agg(t),'[]'::json) FROM commerce.customer_coupons t WHERE left(id,length(:'p')+1)=:'p'||'-'),
 'coupons', (SELECT coalesce(json_agg(t),'[]'::json) FROM commerce.coupons t WHERE left(id,length(:'p')+1)=:'p'||'-'),
 'coupon_usages', (SELECT coalesce(json_agg(t),'[]'::json) FROM commerce.coupon_usages t WHERE left(customer_coupon_id,length(:'p')+1)=:'p'||'-'),
 'inventory_movements', (SELECT coalesce(json_agg(t),'[]'::json) FROM commerce.inventory_movements t WHERE left(product_id,length(:'p')+1)=:'p'||'-'));
COMMIT;
""", {'p': prefix}))

    def _prepare_cases(self):
        result = []
        for case_id in ['VOC-%02d' % n for n in range(1, 8)] + ['NORMAL']:
            number = case_id[-2:] if case_id.startswith('VOC-') else '01'
            prefix = 'jdd-v' + number + '-' + uuid.uuid4().hex[:16]
            record = {'id': case_id, 'prefix': prefix, 'status': 'FAILED', 'startedAt': now()}
            try:
                case = self._prepare_one(case_id, prefix, record)
                case['prefix'] = prefix
                case['database'] = self._database(prefix)
                case['preparation'] = record
                case['preparation']['status'] = 'PASSED'
                result.append(case)
            finally:
                record['finishedAt'] = now()
                write_new(self.directory / ('prepare-' + case_id + '.json'), record)
        result.append({'id': 'NEEDS_INPUT', 'message': '주문 문제가 생겼어요. 어떤 정보가 필요한가요?',
                       'context': {}, 'database': {}, 'preparation': {'status': 'PASSED', 'modelCalls': 0}})
        return result

    def _prepare_one(self, case_id, prefix, record):
        app = self.app
        if case_id == 'VOC-07':
            app.concurrent(prefix)
            actual = app.results[-1]
            record.update(actual)
            return {'id': case_id, 'message': '재고가 1개였는데 서로 다른 두 고객의 주문이 성공했습니다. 확인해 주세요.',
                    'context': {'productId': prefix + '-product', 'occurredAt': record['startedAt']}}
        app.seed_scenario(case_id if case_id != 'NORMAL' else 'VOC-01', prefix)
        responses = record['responses'] = []
        context = {'customerId': prefix + '-customer', 'productId': prefix + '-product',
                   'occurredAt': record['startedAt']}
        messages = {
            'VOC-01': '간편결제는 승인됐는데 주문이 계속 결제 대기입니다. 원인과 조치를 확인해 주세요.',
            'VOC-02': '5만 원 이상이면 되는 쿠폰인데 5만 원 상품 주문에 적용이 안 됩니다. 확인해 주세요.',
            'VOC-03': '10% 쿠폰을 적용해 6만 원 상품을 주문했는데 할인금액이 0원입니다. 확인해 주세요.',
            'VOC-04': '같은 결제 화면에서 주문을 재시도했더니 주문 두 개가 생겼습니다. 확인해 주세요.',
            'VOC-05': '결제한 주문은 취소됐는데 환불 처리가 진행되지 않습니다. 확인해 주세요.',
            'VOC-06': '주문을 취소하고 환불까지 됐는데 쿠폰이 이미 사용됐다고 나옵니다. 확인해 주세요.',
            'NORMAL': '카드 결제한 주문의 결제 상태가 정상인지 확인해 주세요.',
        }
        coupon = {'VOC-02': 'fixed', 'VOC-03': 'percent', 'VOC-06': 'fixed'}.get(case_id)
        created = app.create(prefix, 'checkout', coupon=coupon)
        responses.append(created)
        context['checkoutKey'] = prefix + '-checkout'
        if case_id == 'VOC-02':
            assert created[0] == 422 and created[1]['code'] == 'COUPON_NOT_ELIGIBLE'
            database = self._database(prefix)
            assert not database['orders'] and not database['coupon_usages']
            assert all(c['status'] == 'AVAILABLE' for c in database['customer_coupons'])
            # Do not run the above-boundary control against this investigation's coupon.
            self._await_events(prefix, {'COUPON_REJECTED'})
        else:
            assert created[0] == 201
            order = created[1]
            context['orderId'] = order['id']
            events = {'ORDER_CREATED'}
            if case_id in ('VOC-01', 'VOC-05', 'VOC-06', 'NORMAL'):
                paid = app.pay(prefix, order['id'], prefix + '-payment', 'EASY_PAY' if case_id == 'VOC-01' else 'CARD')
                responses.append(paid)
                assert paid[0] == 200 and paid[1]['payment']['status'] == 'APPROVED'
                assert paid[1]['order']['status'] == ('PAYMENT_PENDING' if case_id == 'VOC-01' else 'PAID')
                events.add('PAYMENT_APPROVED')
            if case_id == 'VOC-03':
                assert order['discountAmount'] == 0 and order['subtotal'] == 60000
                events.add('DISCOUNT_CALCULATED')
            if case_id == 'VOC-04':
                repeated = app.create(prefix, 'checkout')
                responses.append(repeated)
                assert repeated[0] == 201 and repeated[1]['id'] != order['id']
                context.pop('orderId')
            if case_id in ('VOC-05', 'VOC-06'):
                key = prefix + '-cancel'
                control = {'orderId': order['id'], 'requestKey': key}
                if case_id == 'VOC-05':
                    assert app.http('POST', '/internal/reproduction/refund-failure', control)[0] == 200
                try:
                    cancelled = app.cancel(prefix, order['id'], key)
                    responses.append(cancelled)
                    assert cancelled[0] == 200 and cancelled[1]['order']['status'] == 'CANCELLED'
                    if case_id == 'VOC-05':
                        assert cancelled[1]['refund'] is None
                        events.add('REFUND_FAILED')
                    else:
                        assert cancelled[1]['refund']['status'] == 'COMPLETED'
                        coupon_row = next(c for c in self._database(prefix)['customer_coupons'] if c['id'] == prefix + '-cc-fixed')
                        assert coupon_row['status'] == 'USED'
                        events.add('REFUND_COMPLETED')
                finally:
                    if case_id == 'VOC-05':
                        assert app.http('DELETE', '/internal/reproduction/refund-failure', control)[0] == 200
            self._await_events(prefix, events)
        return {'id': case_id, 'message': messages[case_id], 'context': context}

    def _await_events(self, prefix, expected):
        path = ROOT / 'runtime/evidence/logs/commerce' / self.build / 'business.jsonl'
        deadline = time.monotonic() + 10
        while True:
            rows, _ = business_log(path, self.build)
            matching = [row for row in rows if any(isinstance(value, str) and value.startswith(prefix + '-')
                                                  for value in row['event'].values())]
            if expected <= {row['event']['event'] for row in matching}:
                return
            if time.monotonic() >= deadline:
                raise RuntimeError('Committed fixture logs did not arrive within 10 seconds')
            time.sleep(0.1)
