"""尝试统一 ART 编译条件；Success 不代表生效，本次实为 verify，不能作为 AOT 对照。"""
from verify_optimization_20261009 import r, environment

name = 'meizu'
s = r.DEVICES[name]
installed = 'candidate'
try:
    for phase, apk in [('before', r.OUT / name / 'baseline.apk'), ('after', r.OUT / 'candidate.apk')]:
        r.sh(s, 'am force-stop ' + r.PKG)
        print(phase, r.adb(s, 'install', '-r', apk, timeout=180).decode().strip(), flush=True)
        installed = 'baseline' if phase == 'before' else 'candidate'
        status = r.sh(s, 'cmd package compile -f -m speed ' + r.PKG, timeout=180)
        r.save(r.OUT / name / ('compiled-' + phase + '-status.txt'), status)
        assert 'Success' in status, status
        environment(name, 'compiled-' + phase)
        r.collect(name, 'drift', run='compiled-' + phase, count=4)
finally:
    if installed != 'candidate':
        print(r.adb(s, 'install', '-r', r.OUT / 'candidate.apk', timeout=180).decode(), flush=True)
