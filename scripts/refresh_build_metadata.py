"""Refresh the Gradle distribution checksum from the publisher; review the resulting diff."""
from pathlib import Path
from urllib.request import urlopen
import re

path=Path('gradle/wrapper/gradle-wrapper.properties')
text=path.read_text()
url=next(line.split('=',1)[1].replace('\\:',':') for line in text.splitlines() if line.startswith('distributionUrl='))
checksum=urlopen(url+'.sha256',timeout=30).read().decode().strip()
assert re.fullmatch('[0-9a-f]{64}',checksum)
text=re.sub(r'^distributionSha256Sum=.*\n?', '',text, flags=re.M)
path.write_text(text.rstrip()+'\ndistributionSha256Sum='+checksum+'\n',newline='\n')
print('Pinned Gradle distribution SHA-256:',checksum)
