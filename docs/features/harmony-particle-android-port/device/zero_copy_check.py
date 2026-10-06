"""飘散零拷贝快照的真机核对（R5CW20BLNKL，真实首页点新建、按返回放弃，记事数前后不变）。

打开调试键 debug_drift_verify_zero_copy 后，每次零拷贝都把导入的纹理整张读回，与老路那份 `Bitmap.copy(ARGB_8888)` 的字节
逐字节比，GPU 取来的内容样本也逐个比（ParticleDriftHardwareImport.verify）。跑完把偏好恢复成跑之前的原文并读回核对。

用法（Bash 工具里跑）：python zero_copy_check.py --run z1 [--rounds 3]
"""
import argparse
import re
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import drift_driver as d  # noqa: E402
import perf_compare as pc  # noqa: E402

KEY = 'debug_drift_verify_zero_copy'


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--run', required=True)
    p.add_argument('--rounds', type=int, default=3)
    a = p.parse_args()
    folder = d.OUT / a.run
    folder.mkdir(parents=True, exist_ok=True)
    original = pc.run_as('cat %s' % pc.PREFS)
    assert original.strip().startswith('<?xml'), original
    body = re.sub(r'\s*<(string|boolean) name="(particle_style|%s)"[^>]*(/>|>[^<]*</string>)' % KEY, '', original)
    body = body.replace('<map />', '<map>\n</map>')
    body = body.replace('</map>', '    <string name="particle_style">drift</string>\n'
                                  '    <boolean name="%s" value="true" />\n</map>' % KEY)
    d.soft('logcat', '-c')
    log = open(folder / 'logcat.txt', 'wb')
    cat = subprocess.Popen([d.ADB, '-s', d.SERIAL, 'logcat', '-v', 'time', '-s', 'ParticleDrift:I',
                            'ParticlePacing:I', 'ParticleMicroflake:I'], stdout=log, stderr=subprocess.DEVNULL)
    try:
        pc.write_prefs(body)
        assert ('name="%s" value="true"' % KEY) in pc.run_as('cat %s' % pc.PREFS)
        d.launch()
        time.sleep(1.5)
        for _ in range(a.rounds):
            pc.creation_round()
        d.adb('shell', 'am', 'force-stop', d.PACKAGE)
    finally:
        cat.terminate()
        log.close()
        pc.write_prefs(original)
        restored = pc.run_as('cat %s' % pc.PREFS) == original
    text = (folder / 'logcat.txt').read_bytes().decode('utf-8', 'replace')
    for line in re.findall(r'(飘散零拷贝[^\n]*|抓图路径：[^\n]*|飘散首帧上屏[^\n]*)', text):
        print(line[:260])
    print('prefs restored', restored)


if __name__ == '__main__':
    main()
