"""复制 APK、验证报告并打包完整源码，不包含 SDK 路径、缓存和签名私钥。"""
from pathlib import Path
import hashlib
import json
import shutil
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZIP_DEFLATED

root = Path(__file__).resolve().parents[1]
out = root.parent / 'WordTrail-delivery'
out.mkdir(exist_ok=True)
reports = out / 'reports'
reports.mkdir(exist_ok=True)
tests = list((root / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
assert tests, '缺少测试结果'
total = 0
for path in tests:
    node = ET.parse(path).getroot()
    assert int(node.get('failures', 0)) == int(node.get('errors', 0)) == int(node.get('skipped', 0)) == 0
    total += int(node.get('tests'))
    shutil.copy2(path, reports / path.name)
lint = root / 'app/build/reports/lint-results-debug.xml'
issues = ET.parse(lint).getroot().findall('issue')
assert not any(i.get('severity') in ('Error', 'Fatal') for i in issues)
shutil.copy2(lint, reports / lint.name)
shutil.copy2(root / 'app/build/reports/lint-results-debug.html', reports / 'lint-results-debug.html')
shutil.copy2(root / 'app/build/outputs/apk/debug/app-debug.apk', out / 'WordTrail-debug.apk')
shutil.copy2(root / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk', out / 'WordTrail-androidTest.apk')
for name in ('README.md', 'VALIDATION.md'):
    shutil.copy2(root / name, out / name)
excluded = {'.gradle', '.kotlin', '.idea', '.git', 'build', '__pycache__'}
archive = out / 'WordTrail-source.zip'
with ZipFile(archive, 'w', ZIP_DEFLATED) as z:
    for file in sorted(root.rglob('*')):
        rel = file.relative_to(root)
        if file.is_file() and not (set(rel.parts) & excluded) and file.name != 'local.properties':
            assert file.suffix not in ('.jks', '.keystore'), '不能打包签名私钥'
            z.write(file, str(Path('WordTrail') / rel))
with ZipFile(archive) as z:
    assert z.testzip() is None
    assert 'WordTrail/gradle/wrapper/gradle-wrapper.jar' in z.namelist()
    assert 'WordTrail/app/src/main/assets/words.json' in z.namelist()
    assert not any(n.endswith('local.properties') for n in z.namelist())
    assert len(json.loads(z.read('WordTrail/app/src/main/assets/words.json'))) == 320
hashes = []
for file in (out / 'WordTrail-debug.apk', out / 'WordTrail-androidTest.apk', archive):
    hashes.append(f'{hashlib.sha256(file.read_bytes()).hexdigest()}  {file.name}')
(out / 'SHA256SUMS.txt').write_text('\n'.join(hashes) + '\n', encoding='utf-8')
print(f'Delivery complete: {out}; tests={total}; lint warnings={len(issues)}')
print('\n'.join(hashes))
