"""Deterministic, reviewed matrix inputs/oracles; never calls application code or a broker."""
import argparse
import itertools
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RESOURCE = ROOT / 'app/src/test/resources/combination-v1.tsv'
CATALOG = ROOT / 'docs/COMBINATION_CASES.md'


def generate():
    cases = []

    def add(family, inputs, expected):
        cases.append((f'C{len(cases)+1:04}', family, *inputs,
                      json.dumps(expected, ensure_ascii=False, separators=(',', ':'))))

    # Published routing policy examples, including the exact 10% boundary.
    intervals = [2, 2, 3, 5, 15, 15, 15, 30, 60, 180]
    for i, ws, direction, scenario in itertools.product(range(10), range(2), [-1, 1], range(5)):
        suspended = scenario >= 3
        interval = 2 if scenario == 2 else intervals[i]
        near = scenario == 2 or i < 4
        add('ROUTE', [i, ws, direction, scenario, 0],
            {'ws': bool(ws and near and not suspended),
             'interval': -1 if suspended else min(interval, 10) if not ws else interval})

    # Each failure combination asserts both dispatch count and durable journal effect.
    for quote, journal, lifecycle, side in itertools.product(range(5), range(5), range(4), range(2)):
        admitted = quote < 2 and journal in [0, 3, 4] and lifecycle in [0, 2]
        add('ORDER', [quote, journal, lifecycle, side, 0],
            {'sent': int(admitted), 'result': 'ACCEPTED' if admitted and lifecycle == 0
             else 'UNKNOWN' if admitted else 'BLOCKED'})

    for quantity, fee, scope, algorithm in itertools.product([1, 2, 3, 7, 20], [0, 1, 3, 10, 99], range(4), range(2)):
        sold = quantity // 2
        total_cost = quantity * 10000 + fee
        disposed_cost = total_cost * sold // quantity
        net_sale = sold * 12000 - (1 if sold else 0)
        add('LEDGER', [quantity, fee, scope, algorithm, 0],
            {'quantity': quantity-sold, 'cost': total_cost-disposed_cost,
             'realized': net_sale-disposed_cost, 'cash': 1000000-total_cost+net_sale})

    for amount, text, format_, dates in itertools.product([-500, 0, 500, 9223372036854775807, -9223372036854775807], range(5), range(4), range(2)):
        row_count = 3 if format_ == 0 else 2 if format_ == 1 or dates == 0 else 1
        add('EXPORT', [amount, text, format_, dates, 0],
            {'total': str(amount*2), 'rows': row_count, 'missing': 1 if format_ == 0 else 0})

    # Explicit calendar fixtures. No reimplementation of businessDay()/occurrence().
    dates = ['2026-03-02', '2026-09-21', '2026-09-22', '2026-09-19', '2026-10-01']
    calendar = [dates[:3]+[None, dates[4]], [dates[0], dates[1], None, None, None],
                ['2026-02', None, None, None, None], [None, '2026-09', None, None, None], [None]*5]
    for schedule, date, time in itertools.product(range(5), range(5), range(4)):
        add('SCHEDULE', [schedule, date, time, 0, 0],
            {'occurrence': calendar[schedule][date] if time in [1, 2] else None})

    for algorithm, owned, cash, variant in itertools.product(range(2), [0, 1, 3, 10, 20], [0, 9999, 10000, 50000, 100000], range(2)):
        expected = {}
        if algorithm == 0 and owned:
            buy_target, sell_target = (9700, 10600) if variant == 0 else (9000, 11000)
            sell = min(owned, 100000 // sell_target)
            buy = cash*100 // (buy_target*101) if variant == 0 else 0
            if sell: expected['SELL'] = [sell, sell_target]
            if buy: expected['BUY'] = [buy, buy_target]
        elif algorithm == 1:
            nav = cash + owned*10000
            value = owned*10000
            band = 5 if variant == 0 else 10
            # Compare integer cross products to avoid copying floating-point implementation.
            delta = value*100 - nav*50
            if nav and delta > band*nav:
                qty = min(owned, (value-nav//2)//10000, 10)
                if qty: expected['SELL'] = [qty, None]
            elif nav and delta < -band*nav:
                qty = min(nav//2-value, cash, 100000)*100//1010000
                if qty: expected['BUY'] = [qty, None]
        add('ALGORITHM', [algorithm, owned, cash, variant, 0], expected)

    assert len(cases) == 1000
    assert len({tuple(row[1:-1]) for row in cases}) == 1000
    tsv = 'id\tfamily\ta\tb\tc\td\te\texpected\n' + ''.join('\t'.join(map(str, r))+'\n' for r in cases)
    doc = '''# 조합 테스트 1,000개 목록

자동 생성 파일. 수정은 `scripts/generate_combination_cases.py`에서 하고 `--check`로 동기화를 검증한다.
차원 설명·기대값 산출·실행 범위는 [COMBINATION_TESTING.md](COMBINATION_TESTING.md).
각 행은 JUnit의 독립된 테스트 한 개이며 반복 assertion 개수를 테스트 수로 세지 않는다.

| ID | 분야 | 입력 a,b,c,d,e | 기대 결과 |
|---|---|---|---|
'''
    doc += ''.join(f'| {r[0]} | {r[1]} | {", ".join(map(str, r[2:7]))} | `{r[7]}` |\n' for r in cases)
    return {RESOURCE: tsv, CATALOG: doc}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    for path, content in generate().items():
        if args.check:
            assert path.exists() and path.read_text(encoding='utf-8') == content, f'Outdated: {path}'
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8', newline='\n')
    print('PASS: 1000 unique deterministic cases; resource/catalog synchronized.')
