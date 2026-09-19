"""Enforce package dependency direction. This is a source guard, not a Kotlin type checker."""
from pathlib import Path
import re

ROOT = 'com.sinpie.stocknhplug.'
ALLOWED = {
    'domain': {'domain'},
    'trading': {'trading', 'domain'},
    'application': {'application', 'domain', 'research', 'trading'},
    'research': {'research', 'domain'},
    'research/provider': {'research', 'domain', 'infrastructure'},
    'execution': {'execution', 'domain', 'application', 'infrastructure'},
    'marketdata': {'marketdata', 'research', 'domain', 'infrastructure'},
    'data': {'data', 'domain', 'application'},
    'ui': {'ui', 'application', 'domain', 'research'},
}
PURE = {'domain', 'trading', 'application', 'research'}


def violation(layer, dependency):
    if dependency.startswith(ROOT):
        suffix = dependency[len(ROOT):]
        if layer == 'execution' and suffix == 'BuildConfig':
            return False  # Compile-time live-trading lock stays in the broker adapter.
        if suffix.split('.')[0] not in ALLOWED[layer]:
            return True
        if layer in PURE | {'ui'} and suffix.startswith('research.provider.'):
            return True
    elif layer in PURE and not dependency.startswith(('java.', 'kotlin.', 'kotlinx.coroutines.')):
        return True
    return False


# Guard the guard: direction violations must be detected even when an adapter is imported directly.
assert violation('application', ROOT + 'execution.NhBroker')
assert violation('research', ROOT + 'research.provider.DartClient')
assert violation('domain', 'android.content.Context')
assert violation('ui', ROOT + 'data.SecureVault')
assert not violation('trading', ROOT + 'domain.Broker')
assert not violation('marketdata', ROOT + 'infrastructure.nh.NhTransport')

errors = []
base = Path('app/src/main/java/com/sinpie/stocknhplug')
for source in base.rglob('*.kt'):
    relative = source.relative_to(base)
    layer = 'research/provider' if relative.parts[:2] == ('research', 'provider') else relative.parts[0]
    if layer not in ALLOWED:
        continue  # AppContainer and platform hosts are composition/lifecycle boundaries.
    text = source.read_text(encoding='utf-8')
    imports = re.findall(r'^import\s+(\S+)', text, re.MULTILINE)
    qualified = re.findall(r'com\.sinpie\.stocknhplug\.[\w.]+', re.sub(r'^package.*$', '', text, flags=re.MULTILINE))
    for dependency in set(imports + qualified):
        if violation(layer, dependency):
            errors.append(f'{relative}: forbidden dependency {dependency}')
if errors:
    raise SystemExit('\n'.join(errors))
print('PASS: domain/trading/application/research/UI dependency boundaries.')
