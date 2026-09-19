from pathlib import Path
import xml.etree.ElementTree as ET
import json

for p in Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    root=ET.parse(p).getroot()
    print(root.attrib['name'], 'tests='+root.attrib['tests'], 'failures='+root.attrib['failures'], 'errors='+root.attrib['errors'])
for p in [Path('app/build/outputs/apk/debug/app-debug.apk'),Path('app/build/outputs/bundle/release/app-release.aab')]:
    if p.exists(): print(p, p.stat().st_size, 'bytes')
report=Path('app/build/reports/lint-results-debug.xml')
if report.exists():
    root=ET.parse(report).getroot()
    issues=[(i.attrib.get('severity'),i.attrib.get('id')) for i in root.findall('issue')]
    print('Lint',json.dumps(issues))
