#!/usr/bin/env python3
"""Check fixture resets refuse cross-prefix orders before any mutation; never contacts a model."""
from datetime import datetime, timezone
import json
from pathlib import Path
import uuid
from reproduce_commerce import CommerceReproduction, ROOT


def refuse_unchanged(app, scenario, prefix, other, results):
    before = [app.capture(prefix), app.capture(other)]
    record = {'scenario': scenario, 'prefix': prefix, 'otherPrefix': other, 'before': before, 'status': 'FAILED'}
    results.append(record)
    try:
        app.sql((ROOT / 'fixtures/commerce' / scenario / 'reset.sql').read_text(), {'fixture_prefix': prefix})
    except RuntimeError as rejected:
        assert 'Refusing reset' in str(rejected), str(rejected)
    else:
        raise AssertionError('Reset accepted a cross-prefix order')
    after = [app.capture(prefix), app.capture(other)]
    record['after'] = after
    assert after == before, 'Rejected reset changed business data'
    record['status'] = 'PASSED'
    return record


def main():
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')
    path = ROOT / 'runtime/submission/commerce-reproductions' / (stamp + '-fixture-isolation.json')
    report = {'startedAt': stamp, 'status': 'FAILED', 'modelCalls': 0, 'results': []}
    try:
        app = CommerceReproduction()
        report['buildId'] = app.build
        run = uuid.uuid4().hex[:10]
        # Each reset script must fail closed even when a valid HTTP order spans two fixture products.
        for number in range(1, 8):
            scenario = 'VOC-' + str(number).zfill(2)
            prefix = 'jdd-v' + str(number).zfill(2) + '-scope-' + run
            other = 'jdd-v01-other-' + str(number) + '-' + run
            if number == 7: app.seed(prefix, 10)
            else: app.seed_scenario(scenario, prefix)
            app.seed_scenario('VOC-01', other)
            code, order = app.http('POST', '/api/orders', {'customerId': prefix + '-customer', 'checkoutKey': prefix + '-mixed',
                'items': [{'productId': prefix+'-product', 'quantity': 1}, {'productId': other+'-product', 'quantity': 1}]}, prefix+'-request')
            assert code == 201, order
            record = refuse_unchanged(app, scenario, prefix, other, report['results'])
            record['orderId'] = order['id']
        # A coupon from another prefix is also an external dependency even with only one product.
        prefix, other = 'jdd-v01-coupon-scope-'+run, 'jdd-v03-coupon-other-'+run
        app.seed_scenario('VOC-01', prefix); app.seed_scenario('VOC-03', other)
        app.sql("UPDATE commerce.products SET price=60000 WHERE id=:'p'||'-product';", {'p': prefix})
        code, order = app.http('POST', '/api/orders', {'customerId': other+'-customer', 'checkoutKey': prefix+'-mixed-coupon',
            'items': [{'productId': prefix+'-product', 'quantity': 1}], 'customerCouponId': other+'-cc-fixed'}, prefix+'-request')
        assert code == 201, order
        refuse_unchanged(app, 'VOC-01', prefix, other, report['results'])
        # Ordinary same-prefix reset and reseed still works; it must preserve another fixture untouched.
        normal = 'jdd-v03-normal-reset-'+run
        app.seed_scenario('VOC-03', normal)
        assert app.create(normal, 'used', coupon='fixed')[0] == 201
        other_before = app.capture(other)
        app.seed_scenario('VOC-03', normal)
        actual = app.capture(normal)
        assert not actual['orders'] and not actual['usages'] and actual['stocks'][0]['quantity'] == 10
        assert app.capture(other) == other_before
        report['results'].append({'scenario': 'NORMAL_RESET', 'prefix': normal, 'database': actual, 'status': 'PASSED'})
        report['status'] = 'PASSED'
    except Exception as error:
        report['error'] = str(error)
        raise
    finally:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
        print(report['status'], path, flush=True)


if __name__ == '__main__':
    main()
