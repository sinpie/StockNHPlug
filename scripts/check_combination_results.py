"""Verify the 1000 catalog IDs really ran, individually and without skip/failure."""
from collections import Counter
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
expected = {line.split('\t')[0] for line in
            (ROOT/'app/src/test/resources/combination-v1.tsv').read_text(encoding='utf-8').splitlines()[1:]}
assert len(expected) == 1000
reports = list((ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*CombinationCaseTest*.xml'))
assert reports, 'No combination test XML; run testDebugUnitTest first.'
ids = []
for path in reports:
    root = ET.parse(path).getroot()
    assert all(int(root.get(key, '0')) == 0 for key in ['failures', 'errors', 'skipped']), path
    for case in root.findall('testcase'):
        match = re.search(r'\[(C\d{4})-', case.get('name', ''))
        assert match is not None, case.attrib
        assert not any(case.find(tag) is not None for tag in ['failure', 'error', 'skipped']), case.attrib
        ids.append(match.group(1))
assert set(ids) == expected and len(ids) == 1000 and all(n == 1 for n in Counter(ids).values())
print('PASS: all 1000 catalog IDs executed exactly once, zero failures/errors/skips.')
