"""量一次模拟接收里「数据到齐 → 开始流入」的各段耗时，用来对照拼卡那一层沿用材料前后（决策 2026-09-28）。

打开碰一碰模拟与会话记录（样例由环境变量 SAMPLES 给，缺省 plain），下拉进入接收，等模拟发送方碰上来、拼完，退出接收；
同时把 TapReceive / TapFlight 两个标签的日志流到本地文件。之后拉回这一次的接收会话记录，按事件时刻给出：
第一条写进数据库（insert）、导入完（land）、拼卡那一层提前建（real-early）、交进真实快照（real-supplied）、
流入开始（inflow-start）、拼好（assembled），以及日志里两层各自的准备时长与是否沿用了材料。

样例留在列表里（之后统一用 cleanup_selection.py / cleanup_detail.py 移入回收站）；调试开关文件跑完删掉。
用法：SERIAL=<序列号> SAMPLES=plain python receive_timing.py <输出目录> <标签>
"""
import json
import os
import re
import subprocess
import sys
import time

import dev
import folder_transfer_check as ftc
from folder_transfer_check import FIXTURES, launch, log, node_by_rid, screen_size, write_prefs

LABEL = sys.argv[2]


def receive_sessions():
    out = dev.shell('ls %s' % FIXTURES, check=False)
    return sorted(l.strip() for l in out.splitlines() if l.strip().endswith('-receive-session'))


def pull_session(name):
    for _ in range(30):
        text = dev.shell('cat %s/%s/session.json' % (FIXTURES, name), check=False)
        if text.strip().startswith('{'):
            try:
                return json.loads(text)
            except json.JSONDecodeError:
                pass
        time.sleep(0.4)
    return None


def main():
    if 'isKeyguardShowing=true' in dev.shell('dumpsys window'):
        raise SystemExit('设备锁屏中，请先解锁（密码不代输）')
    rotation = dev.shell('settings get system accelerometer_rotation').strip()
    before = set(receive_sessions())
    write_prefs(True)
    logfile = os.path.join(ftc.OUT, 'logcat-%s.txt' % LABEL)
    with open(logfile, 'wb') as sink:
        # 全量日志：GC 的记录不带我们的标签（环境变量 FULL_LOG=1 时）
        filters = [] if os.environ.get('FULL_LOG') else ['TapReceive:I', 'TapFlight:I', 'TapDustHandoff:I',
                                                         'ParticleMicroflake:I', '*:S']
        logcat = subprocess.Popen([dev.ADB, '-s', dev.SERIAL, 'logcat', '-v', 'threadtime', '-T', '1'] + filters,
                                  stdout=sink, stderr=subprocess.DEVNULL)
        try:
            launch()
            width, height = screen_size(dev.dump())
            tree = dev.dump()
            bar = node_by_rid(tree, 'actionbar')
            start = max(int(height * 0.30), dev.bounds(bar)[3] + 60 if bar is not None else 0)
            end = min(start + int(height * 0.55), height - 200)
            dev.drag(width // 2, start, width // 2, end, steps=26, step_ms=18)
            button = dev.poll(rid='tv_tap_receive')
            x, y = dev.center(button)
            # 环境变量 RECORD=1 时录下整段接收（录制期间不截图）
            recording = dev.record_start('receive-%s' % LABEL, 22) if os.environ.get('RECORD') else None
            dev.tap(x, y)
            log('进入接收，模拟发送方约 3.5 秒后碰上来')
            time.sleep(16)
            dev.tap(x, y)
            time.sleep(2.5)
            if recording:
                log('录屏：', dev.record_finish(*recording, 'receive-%s' % LABEL))
        finally:
            logcat.terminate()
            try:
                logcat.wait(timeout=5)
            except subprocess.TimeoutExpired:
                logcat.kill()
    write_prefs(False)
    launch()
    new = [s for s in receive_sessions() if s not in before]
    if not new:
        raise SystemExit('没有新的接收会话记录')
    session = pull_session(new[-1])
    events = session['events'] if session else []
    first = {}
    for e in events:
        first.setdefault(e['type'], e['t'])
    keys = ['proxy', 'proxy-start', 'insert', 'land', 'real-early', 'real-supplied', 'inflow-start', 'assembled']
    row = {k: first.get(k) for k in keys}
    if row['land'] is not None and row['inflow-start'] is not None:
        row['land_to_inflow_ms'] = row['inflow-start'] - row['land']
    if row['insert'] is not None and row['inflow-start'] is not None:
        row['insert_to_inflow_ms'] = row['inflow-start'] - row['insert']
    with open(logfile, 'rb') as f:
        lines = f.read().decode('utf-8', 'replace').splitlines()
    wanted = re.compile(r'首帧上屏|沿用|提前建|交进真实快照|对不上|重建|光尘接手 颗粒|光尘接手自检')
    row['log'] = [l.split(': ', 1)[-1] for l in lines if wanted.search(l)]
    row['session'] = new[-1]
    log(LABEL, json.dumps(row, ensure_ascii=False, indent=1))
    with open(os.path.join(ftc.OUT, 'timing-%s.json' % LABEL), 'w', encoding='utf-8') as f:
        json.dump(row, f, ensure_ascii=False, indent=1)
    now = dev.shell('settings get system accelerometer_rotation').strip()
    if now != rotation:
        log('注意：自动旋转从 %s 变成了 %s' % (rotation, now))


main()
