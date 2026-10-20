"""冻结当前源文件到测试目录；只在副本里加入 A/B 开关，不改产品实现。"""
from pathlib import Path
import hashlib
import json
import shutil

ROOT = next(p for p in Path(__file__).resolve().parents if (p / 'gradlew.bat').exists())
OUT = ROOT / 'tmp/gif-owner-perf-20261020'
PKG = 'com.ywwynm.everythingdone.gifownervalidation'
OUT.mkdir(exist_ok=True)
sources = {}
for variant in ('main', 'debug'):
    source = ROOT / f'app/src/{variant}/java'
    target = OUT / f'{variant}-source'
    shutil.copytree(source, target, dirs_exist_ok=True)
    for path in source.rglob('*'):
        if path.is_file():
            sources[str(path.relative_to(ROOT))] = hashlib.sha256(path.read_bytes()).hexdigest()
    manifest = (ROOT / f'app/src/{variant}/AndroidManifest.xml').read_text(encoding='utf-8')
    manifest = manifest.replace('android:name=".', 'android:name="com.ywwynm.everythingdone.')
    manifest = manifest.replace('android:authorities="com.ywwynm.everythingdone"', 'android:authorities="${applicationId}"')
    manifest = manifest.replace('android:targetPackage="com.ywwynm.everythingdone"', 'android:targetPackage="${applicationId}"')
    if variant == 'debug':
        manifest = manifest.replace('</manifest>', '''<instrumentation
        android:name="com.ywwynm.everythingdone.detail.GifOwnerPerformanceProbe"
        android:targetPackage="${applicationId}" />\n</manifest>''')
    (OUT / f'{variant}-manifest.xml').write_text(manifest, encoding='utf-8')

adapter = OUT / 'main-source/com/ywwynm/everythingdone/adapters/ImageAttachmentAdapter.kt'
text = adapter.read_text(encoding='utf-8')
old = 'val independentPlayback = animatedPlayback && !loop'
assert text.count(old) == 1
adapter.write_text(text.replace(old, old + ' &&\n            com.ywwynm.everythingdone.detail.GifOwnerPerformanceProbe.isolationEnabled'), encoding='utf-8')

for name in ('ComplexMediaDragAudit', 'MediaMemoryAudit', 'ScrollPerformanceAudit'):
    path = OUT / f'debug-source/com/ywwynm/everythingdone/listvalidation/{name}.kt'
    text = path.read_text(encoding='utf-8').replace('com.ywwynm.everythingdone.batchvalidation', PKG)
    if name == 'ScrollPerformanceAudit':
        old = 'if (!quick) {\n                resetTop()\n                // 使用实际长按'
        assert old in text
        text = text.replace(old, 'if (!quick && args.getString("perf_suite") != "owner") {\n                resetTop()\n                // 使用实际长按')
        text = text.replace('.put("playing", gif?.isRunning)', '.put("direct", gif?.buffer?.isDirect).put("playing", gif?.isRunning)')
    path.write_text(text, encoding='utf-8')

assets = OUT / 'assets/gif-owner'
assets.mkdir(parents=True, exist_ok=True)
for name, source in {
    'large.gif': 'tmp/detail-gif-flicker/complex-loop-final-samsung/video.gif',
    'medium.gif': 'tmp/detail-gif-flicker/assets/gif-flicker/real-0.gif',
}.items():
    path = ROOT / source
    assert path.is_file(), f'缺少已有压力夹具：{source}'
    shutil.copy2(path, assets / name)
    sources[source] = hashlib.sha256(path.read_bytes()).hexdigest()
(OUT / 'source-manifest.json').write_text(json.dumps(sources, indent=2), encoding='utf-8')
print('Prepared source snapshot and isolated media:', OUT)
