"""Small offline regression audit: approved production network hosts and secret handling surfaces."""
from pathlib import Path
import re

approved = {'api.nhplug.com', 'moapi.nhplug.com', 'opendart.fss.or.kr', 'dart.fss.or.kr', 'www.nhplug.com'}
errors = []
for source in Path('app/src/main').rglob('*.kt'):
    text = source.read_text(encoding='utf-8')
    for host in re.findall(r'(?:https|wss)://([^/:"\s]+)', text):
        if host not in approved:
            errors.append(f'{source}: unapproved network host: {host}')
    for pattern in ['HttpLoggingInterceptor', 'Log.d(', 'Log.e(', 'printStackTrace(', 'WebView(', 'HostnameVerifier']:
        if pattern in text:
            errors.append(f'{source}: prohibited surface: {pattern}')
if errors:
    raise SystemExit('\n'.join(errors))
print('PASS: production hosts allowlisted; no raw logging, WebView or TLS bypass surfaces found.')
