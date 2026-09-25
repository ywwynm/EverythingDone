"""在一台手机上抓一套模拟器素材：接收一次 + 详情页发送一次，同时录屏；拉回、核对、清理。

前提：设备已解锁、应用已装上当前的 debug 包（`gradlew :app:assembleDebug` 之后 `adb install -r`）。
流程：写 tap_transfer_debug.xml（simulate + dump_fixtures + simulate_samples）→ 录接收 → 录发送（打开刚
收到的第一条样例）→ 拉回 tap-fixtures（逐个比 md5）→ 把本轮收到的样例移入回收站 → 删掉调试开关文件与
设备上的 tap-fixtures。任何一步对不上就停下，不做猜测性的操作。

用法（在本目录执行）：
  SERIAL=<序列号> python -X utf8 capture.py <拉回的目录> [samples] [--cleanup selection|detail]
samples 缺省为 top,plain；清理方式缺省：三星用 selection（选择模式批量删），其余用 detail（逐条从详情页删）。
"""
import hashlib
import os
import subprocess
import sys
import time

import dev
import final_run

REMOTE = '/sdcard/Android/data/%s/files/tap-fixtures' % dev.PKG
HERE = os.path.dirname(os.path.abspath(__file__))


def python(script, *args):
    subprocess.run([sys.executable, '-X', 'utf8', os.path.join(HERE, script)] + list(args), check=True,
                   env=dict(os.environ, SERIAL=dev.SERIAL))


def adb_path(*args):
    # Git Bash 会把 /sdcard/... 改写成 Windows 路径（见 adb.md），这里直接走 subprocess，不经过 shell
    return subprocess.run([dev.ADB, '-s', dev.SERIAL] + list(args), capture_output=True, check=True).stdout.decode()


def pull(dst):
    os.makedirs(dst, exist_ok=True)
    adb_path('pull', REMOTE + '/', dst)
    root = os.path.join(dst, 'tap-fixtures')
    # 会话目录里还有 flight-<n>/ 子目录：逐个文件递归核对
    for folder, _, files in os.walk(root):
        for f in sorted(files):
            local = os.path.join(folder, f)
            rel = os.path.relpath(local, root).replace(os.sep, '/')
            remote_hash = adb_path('shell', 'md5sum', '%s/%s' % (REMOTE, rel)).split()[0]
            with open(local, 'rb') as fh:
                if hashlib.md5(fh.read()).hexdigest() != remote_hash:
                    raise SystemExit('拉回的 %s 与设备上的不一致' % rel)
    for name in sorted(os.listdir(root)):
        print('素材', os.path.join(root, name))


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    if not args:
        raise SystemExit(__doc__)
    dst = args[0]
    samples = args[1] if len(args) > 1 else 'top,plain'
    cleanup = 'selection' if dev.SERIAL.startswith('R5C') else 'detail'
    if '--cleanup' in sys.argv:
        cleanup = sys.argv[sys.argv.index('--cleanup') + 1]
    locked = [l for l in dev.shell('dumpsys window').splitlines() if 'isKeyguardShowing=true' in l]
    if locked:
        raise SystemExit('设备锁屏中，请先解锁（密码不代输）')
    python('set_debug.py', 'on', samples)
    try:
        final_run.relaunch()
        final_run.tag = 'receive-%s' % dev.SERIAL
        final_run.receive(seconds=17, tap_at=2.2, exit_at=13.8)
        final_run.relaunch()
        final_run.tag = 'send-%s' % dev.SERIAL
        final_run.share_detail(seconds=17, back_at=12.0)
        pull(dst)
    finally:
        # 无论前面哪一步失败，都要把收到的样例清掉、把调试开关撤掉；清理本身出错就停在那里，
        # 调试开关仍然撤掉（它只影响调试包，不碰用户数据）
        try:
            final_run.relaunch()
            python('cleanup_%s.py' % cleanup)
        except (SystemExit, subprocess.CalledProcessError, RuntimeError) as error:
            print('清理测试记事没有完成，需要人工核对：', error)
        python('set_debug.py', 'off')
        subprocess.run([dev.ADB, '-s', dev.SERIAL, 'shell', 'rm', '-rf', REMOTE], check=False)
    print('录屏在', dev.OUT)


if __name__ == '__main__':
    main()
