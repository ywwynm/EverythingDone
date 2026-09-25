"""写 tap_transfer_debug.xml（模拟 + 素材落盘）：先停进程，按 UTF-8 原始字节经 run-as 写入，写完读回核对。

用法：SERIAL=<序列号> python set_debug.py on [samples]
      SERIAL=<序列号> python set_debug.py off      # 删除该文件（设备上原本没有它）
"""
import subprocess
import sys
import time

import dev

path = 'shared_prefs/tap_transfer_debug.xml'
dev.shell('am force-stop ' + dev.PKG)
time.sleep(0.8)
if sys.argv[1] == 'off':
    dev.shell('run-as %s rm -f %s' % (dev.PKG, path))
    print('removed; ls ->', dev.shell('run-as %s ls shared_prefs' % dev.PKG, check=False).split())
    raise SystemExit(0)

samples = sys.argv[2] if len(sys.argv) > 2 else None
lines = [
    "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>",
    '<map>',
    '    <boolean name="simulate" value="true" />',
    '    <boolean name="dump_fixtures" value="true" />',
]
if samples:
    lines.append('    <string name="simulate_samples">%s</string>' % samples)
lines.append('</map>')
text = '\n'.join(lines) + '\n'
subprocess.run([dev.ADB, '-s', dev.SERIAL, 'shell', 'run-as', dev.PKG, 'sh', '-c',
                "'cat > %s.tmp && mv %s.tmp %s'" % (path, path, path)], input=text.encode('utf-8'), check=True)
head = dev.shell('run-as %s od -An -tx1 -N 4 %s' % (dev.PKG, path)).split()
assert head[0] == '3c', head
print(dev.shell('run-as %s cat %s' % (dev.PKG, path)))
