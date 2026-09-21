#!/usr/bin/env python3
"""Two-phase real HTTP/PostgreSQL check for durable payment/cancellation replay. No model calls."""
import argparse
import concurrent.futures
from datetime import datetime, timezone
import json
from pathlib import Path
import time
import uuid
from reproduce_commerce import CommerceReproduction


def simultaneous(action):
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        return list(pool.map(lambda _: action(), range(4)))


def after_restart(report):
    # Restart returns before the HTTP server is ready. Retry connection establishment only;
    # never retry business mutations or ignore a different build or an invalid response.
    deadline = time.monotonic() + 30
    attempts = report.setdefault('readinessAttempts', [])
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise RuntimeError('Commerce HTTP did not recover within the readiness wait')
        observed = {'at': datetime.now(timezone.utc).isoformat()}
        try:
            app = CommerceReproduction(request_timeout=min(3, remaining))
            app.request_timeout = 15
            attempts.append({**observed, 'status': 'READY', 'buildId': app.build})
            return app
        except OSError as unavailable:
            attempts.append({**observed, 'status': 'NOT_READY', 'errorType': type(unavailable).__name__})
            if time.monotonic() >= deadline:
                raise RuntimeError('Commerce HTTP did not recover within the readiness wait') from unavailable
            time.sleep(min(0.25, max(0, deadline - time.monotonic())))


def prepare(app, report):
    prefix = 'jdd-v01-recovery-' + uuid.uuid4().hex[:12]
    report.update(prefix=prefix, buildId=app.build, status='PREPARING', responses={})
    app.seed_scenario('VOC-01', prefix)
    for label in ('paid', 'cancelled'):
        created = app.create(prefix, label)
        assert created[0] == 201, created
        order_id = created[1]['id']
        key = prefix + '-pay-' + label
        paid = simultaneous(lambda: app.pay(prefix, order_id, key))
        assert all(value == paid[0] and value[0] == 200 for value in paid), paid
        item = {'orderId': order_id, 'paymentKey': key, 'paymentResponse': paid[0], 'concurrentPayments': paid}
        report['responses'][label] = item
        if label == 'cancelled':
            cancel_key = prefix + '-cancel'
            cancelled = simultaneous(lambda: app.cancel(prefix, order_id, cancel_key))
            assert all(value == cancelled[0] and value[0] == 200 for value in cancelled), cancelled
            item.update(cancelKey=cancel_key, cancelResponse=cancelled[0], concurrentCancellations=cancelled)
    report['beforeRestart'] = app.capture(prefix)
    db = report['beforeRestart']
    assert len(db['payments']) == 2 and len(db['refunds']) == 1 and db['stocks'][0]['quantity'] == 9
    assert len([row for row in db['movements'] if row['movement_type'] == 'RELEASE']) == 1
    ids = [value['orderId'] for value in report['responses'].values()]
    report['evidence'] = app.business_evidence(prefix, ids, [], [('REFUND_COMPLETED', ids[1])])
    report['status'] = 'AWAITING_RESTART'


def verify(app, report):
    assert app.build == report['buildId'], 'Restart the same build before checking durable replay'
    prefix = report['prefix']
    attempt = {'startedAt': datetime.now(timezone.utc).isoformat(), 'status': 'FAILED', 'responses': {}}
    report.setdefault('verificationAttempts', []).append(attempt)
    for label, saved in report['responses'].items():
        actual = app.pay(prefix, saved['orderId'], saved['paymentKey'])
        # JSON persists response tuples as arrays; compare normalized JSON values.
        assert list(actual) == saved['paymentResponse'], actual
        attempt['responses'][label] = {'payment': actual}
        if 'cancelKey' in saved:
            cancelled = app.cancel(prefix, saved['orderId'], saved['cancelKey'])
            assert list(cancelled) == saved['cancelResponse'], cancelled
            repeated = app.cancel(prefix, saved['orderId'], saved['cancelKey'] + '-new')
            assert repeated[0] == 200 and repeated[1]['refund']['id'] == saved['cancelResponse'][1]['refund']['id']
            attempt['responses'][label].update(cancel=cancelled, newKey=repeated)
        else:
            repeated = app.pay(prefix, saved['orderId'], saved['paymentKey'] + '-new')
            assert repeated[0] == 200 and repeated[1]['payment']['id'] == saved['paymentResponse'][1]['payment']['id']
            attempt['responses'][label]['newKey'] = repeated
    after = app.capture(prefix)
    assert after == report['beforeRestart'], 'Replay changed payment/refund/order/stock business rows'
    attempt.update(status='PASSED', database=after)
    report['status'] = 'PASSED'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--phase', choices=['prepare', 'verify'], required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    if args.phase == 'prepare' and args.report.exists():
        parser.error('Choose a new report path; existing execution evidence is preserved')
    report = json.loads(args.report.read_text()) if args.phase == 'verify' else {
        'startedAt': datetime.now(timezone.utc).isoformat(), 'mode': 'commerce-http-postgresql', 'modelCalls': 0}
    try:
        app = after_restart(report) if args.phase == 'verify' else CommerceReproduction()
        if args.phase == 'prepare': prepare(app, report)
        else: verify(app, report)
    except Exception as failure:
        report['status'] = 'FAILED'
        report.setdefault('errors', []).append(str(failure))
        raise
    finally:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
        print(report['status'], args.report, flush=True)


if __name__ == '__main__':
    main()
