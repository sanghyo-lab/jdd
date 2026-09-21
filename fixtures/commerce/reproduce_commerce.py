#!/usr/bin/env python3
"""Synthetic commerce HTTP/DB/log checks. Never invokes an LLM or writes DONE records."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import time
import uuid
from reproduce_inventory import InventoryReproduction, ROOT


class CommerceReproduction(InventoryReproduction):
    def seed_scenario(self, scenario, prefix):
        if not re.fullmatch(r'jdd-v0[1-6]-[a-z0-9-]{1,60}', prefix):
            raise ValueError('Synthetic scenario prefix required')
        folder = ROOT / 'fixtures/commerce' / scenario
        for name in ('reset.sql', 'seed.sql'):
            self.sql((folder / name).read_text(), {'fixture_prefix': prefix})

    def create(self, prefix, suffix, product='product', coupon=None, customer=None):
        return self.http('POST', '/api/orders', {
            'customerId': customer or prefix + '-customer', 'checkoutKey': prefix + '-' + suffix,
            'items': [{'productId': prefix + '-' + product, 'quantity': 1}],
            'customerCouponId': prefix + '-cc-' + coupon if coupon else None}, prefix + '-request-' + suffix)

    def capture(self, prefix):
        return json.loads(self.sql("""
SELECT json_build_object(
 'orders', (SELECT coalesce(json_agg(o ORDER BY o.created_at,o.id),'[]'::json) FROM commerce.orders o WHERE o.customer_id = :'p'||'-customer'),
 'coupons', (SELECT coalesce(json_agg(c ORDER BY c.id),'[]'::json) FROM commerce.customer_coupons c WHERE c.customer_id = :'p'||'-customer'),
 'policies', (SELECT coalesce(json_agg(c ORDER BY c.id),'[]'::json) FROM commerce.coupons c WHERE c.id IN
                 (SELECT coupon_id FROM commerce.customer_coupons WHERE customer_id = :'p'||'-customer')),
 'usages', (SELECT coalesce(json_agg(u ORDER BY u.id),'[]'::json) FROM commerce.coupon_usages u WHERE u.customer_coupon_id IN
                 (SELECT id FROM commerce.customer_coupons WHERE customer_id = :'p'||'-customer')),
 'stocks', (SELECT coalesce(json_agg(s ORDER BY s.product_id),'[]'::json) FROM commerce.product_stock s WHERE left(s.product_id,length(:'p')+1)=:'p'||'-'),
 'movements', (SELECT coalesce(json_agg(m ORDER BY m.occurred_at,m.id),'[]'::json) FROM commerce.inventory_movements m WHERE left(m.product_id,length(:'p')+1)=:'p'||'-'));
""", {'p': prefix}))

    def business_evidence(self, prefix, order_ids):
        path = ROOT / 'runtime/evidence/logs/commerce' / self.build / 'business.jsonl'
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            rows = []
            for number, line in enumerate(path.read_text().splitlines() if path.exists() else [], 1):
                try:
                    event = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if any(isinstance(value, str) and value.startswith(prefix + '-') for value in event.values()):
                    rows.append({'line': number, 'event': event})
            created = {row['event']['orderId'] for row in rows if row['event']['event'] == 'ORDER_CREATED'}
            calculated = {row['event']['orderId'] for row in rows if row['event']['event'] == 'DISCOUNT_CALCULATED'}
            if set(order_ids) <= created and set(order_ids) <= calculated:
                break
            time.sleep(0.1)
        else:
            raise AssertionError('Committed business evidence did not arrive')
        assert all(row['event']['buildId'] == self.build for row in rows)
        manifest = json.loads((ROOT / 'runtime/evidence/source' / self.build / 'manifest.json').read_text())
        assert manifest['buildId'] == self.build
        assert not any('/test/' in name or '/reproduction/' in name or 'fixtures/' in name for name in manifest['files'])
        return {'logPath': str(path.relative_to(ROOT)), 'logs': rows, 'sourceManifest': manifest}

    def run_coupon_boundary(self, prefix, record):
        responses = record['responses'] = {}
        for label, product in [('equal', 'product'), ('below', 'lower')]:
            responses[label] = self.create(prefix, label, product, 'fixed')
            assert responses[label][0] == 422 and responses[label][1]['code'] == 'COUPON_NOT_ELIGIBLE', responses
        after_rejections = self.capture(prefix)
        record['afterRejections'] = after_rejections
        assert not after_rejections['orders'] and not after_rejections['usages']
        assert all(stock['quantity'] == 10 for stock in after_rejections['stocks'])
        responses['above'] = self.create(prefix, 'above', 'upper', 'fixed')
        assert responses['above'][0] == 201, responses
        assert responses['above'][1]['discountAmount'] == 5000 and responses['above'][1]['totalAmount'] == 45001
        for label in ('used', 'expired', 'future'):
            responses[label] = self.create(prefix, label, 'upper', 'fixed' if label == 'used' else label)
            assert responses[label][0] == 422, responses
        responses['wrongOwner'] = self.create(prefix, 'wrong-owner', 'upper', 'other', prefix + '-outsider')
        assert responses['wrongOwner'][0] == 422, responses
        responses['missing'] = self.create(prefix, 'missing', 'upper', 'missing')
        assert responses['missing'][0] == 404, responses
        responses['coupons'] = self.http('GET', '/api/customers/' + prefix + '-customer/coupons')
        assert responses['coupons'][0] == 200 and len(responses['coupons'][1]['items']) == 4
        record['database'] = self.capture(prefix)
        assert len(record['database']['orders']) == 1 and len(record['database']['usages']) == 1
        order_id = responses['above'][1]['id']
        record['evidence'] = self.business_evidence(prefix, [order_id])
        rejects = [row['event'] for row in record['evidence']['logs'] if row['event']['event'] == 'COUPON_REJECTED']
        assert {event['details']['reasonCode'] for event in rejects} >= {'MIN_ORDER_AMOUNT', 'ALREADY_USED', 'EXPIRED', 'NOT_STARTED', 'NOT_OWNED'}
        assert any(event['details']['subtotal'] == event['details']['minOrderAmount'] == 50000 for event in rejects)
        record['observation'] = '50000 rejected; 49999 rejected; 50001 accepted; invalid coupons leave no orders'

    def run_coupon_rate(self, prefix, record):
        responses = record['responses'] = {}
        for label in ('percent', 'fixed', 'capped', 'subtotal'):
            responses[label] = self.create(prefix, label, coupon=label)
            assert responses[label][0] == 201, responses
        assert responses['percent'][1]['discountAmount'] == 0 and responses['percent'][1]['totalAmount'] == 60000
        assert responses['fixed'][1]['discountAmount'] == 5000
        assert responses['capped'][1]['discountAmount'] == 10000
        assert responses['subtotal'][1]['totalAmount'] == 0
        record['database'] = self.capture(prefix)
        assert len(record['database']['usages']) == 4
        assert all(coupon['status'] == 'USED' for coupon in record['database']['coupons'])
        assert record['database']['stocks'][0]['quantity'] == 6
        record['evidence'] = self.business_evidence(prefix, [value[1]['id'] for value in responses.values()])
        calculations = [row['event'] for row in record['evidence']['logs'] if row['event']['event'] == 'DISCOUNT_CALCULATED']
        assert any(event['details']['discountType'] == 'PERCENT' and event['details']['discountRate'] == 10
                   and event['details']['discountAmount'] == 0 for event in calculations)
        record['observation'] = '10% of 60000 stored as zero; fixed, maximum and subtotal caps verified'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--scenario', choices=['VOC-02', 'VOC-03', 'all'], default='all')
    parser.add_argument('--runs', type=int, default=3)
    args = parser.parse_args()
    if not 1 <= args.runs <= 100:
        parser.error('--runs must be between 1 and 100')
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')
    path = ROOT / 'runtime/submission/commerce-reproductions' / (stamp + '-business.json')
    path.parent.mkdir(parents=True, exist_ok=True)
    result = {'schemaVersion': '1.0', 'startedAt': stamp, 'mode': 'commerce-http-postgresql', 'modelCalls': 0, 'status': 'FAILED', 'results': []}
    started = time.monotonic()
    try:
        app = CommerceReproduction()
        result['buildId'] = app.build
        scenarios = ['VOC-02', 'VOC-03'] if args.scenario == 'all' else [args.scenario]
        for scenario in scenarios:
            for index in range(args.runs):
                prefix = 'jdd-v' + scenario[-2:] + '-' + uuid.uuid4().hex[:12] + '-' + str(index)
                record = {'id': scenario, 'iteration': index+1, 'prefix': prefix, 'status': 'FAILED'}
                result['results'].append(record)
                app.seed_scenario(scenario, prefix)
                if scenario == 'VOC-02': app.run_coupon_boundary(prefix, record)
                else: app.run_coupon_rate(prefix, record)
                record['status'] = 'PASSED'
                print(scenario, index+1, 'PASSED:', record['observation'], flush=True)
        result['status'] = 'PASSED'
    except Exception as failure:
        result['error'] = str(failure)
        raise
    finally:
        result['elapsedSeconds'] = round(time.monotonic()-started, 3)
        path.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
        print('Evidence:', path, flush=True)


if __name__ == '__main__':
    main()
