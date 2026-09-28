"""文件夹的分享与传输（决策 2026-09-28）真机核对。分阶段跑，每一步都截图、读数据库核对。

阶段（子命令）：
  receive   打开碰一碰模拟，样例是两个文件夹（「大」形态、带缩略图 / 「正常」形态、通栏，里面第一条置顶，
            最后是一个带两条记事的子文件夹）加一条散的记事；下拉接收，录下文件夹落进列表的过程，拉回数据库
            核对层级、先后、置顶、私密与外观。
  select    选择模式里选中「大」文件夹与那条散的记事一起分享（叠放、标题「分享记事」），再只选「大」文件夹
            分享（标题「分享文件夹」），点「记事文件」，拉回生成的文件核对清单（版本 2、层级、先后、置顶）。
  overflow  进入「正常」文件夹，右上角「分享此文件夹」（卡片从页面里升起、标题用文件夹颜色），同样生成文件核对；
            打开碰一碰模拟时录一段模拟发送。
  import    把 select 阶段生成的文件放进应用的外部目录，用导入页打开：以文件夹为单位勾选、展开看内容，导入后
            核对数据库里新建的那一份与原来的一样。
  cleanup   把样例文件夹与记事移入回收站、删掉调试开关、会话记录与测试文件。

启动应用一律 am start。用法：SERIAL=<序列号> python folder_transfer_check.py <输出目录> <阶段>
"""
import hashlib
import json
import os
import re
import sqlite3
import subprocess
import sys
import time
import zipfile

import xml.etree.ElementTree as ET

import dev

OUT = os.path.abspath(sys.argv[1])
STAGE = sys.argv[2]
os.makedirs(OUT, exist_ok=True)
dev.OUT = OUT

SAMPLES = os.environ.get('SAMPLES', 'bigfolder/3,folder/2!,plain')
PREFS = 'shared_prefs/tap_transfer_debug.xml'
FILES = '/sdcard/Android/data/%s/files' % dev.PKG
FIXTURES = FILES + '/tap-fixtures'
FOLDER_TITLE = '碰一碰模拟 · 样例文件夹 %d'
THING_TITLE = '碰一碰模拟 · 样例记事 %d'
STATE = os.path.join(OUT, 'state.json')


def log(*a):
    print(*a, flush=True)


def load_state():
    if os.path.exists(STATE):
        with open(STATE, encoding='utf-8') as f:
            return json.load(f)
    return {}


def save_state(state):
    with open(STATE, 'w', encoding='utf-8') as f:
        json.dump(state, f, ensure_ascii=False, indent=1)


# ---------------------------------------------------------------- 应用与界面

def top_activity():
    lines = [l for l in dev.shell('dumpsys activity activities').splitlines() if 'ResumedActivity' in l]
    return lines[0] if lines else ''


def wait_activity(name, tries=30):
    for _ in range(tries):
        time.sleep(0.3)
        if name in top_activity():
            return True
    return False


def rotation():
    return dev.shell('settings get system accelerometer_rotation').strip()


def launch():
    dev.shell('am start -n %s/.activities.ThingsActivity' % dev.PKG)
    if not wait_activity('ThingsActivity'):
        raise SystemExit('首页没有起来：%s' % top_activity())
    time.sleep(2.0)
    tree = dev.dump()
    if dev.find(tree, text='现在反馈') is not None:
        cancel = dev.find(tree, text='取消')
        if cancel is not None:
            dev.tap(*dev.center(cancel))
            time.sleep(1.0)


def write_prefs(simulate):
    dev.shell('am force-stop ' + dev.PKG)
    time.sleep(0.8)
    if not simulate:
        dev.shell('run-as %s rm -f %s' % (dev.PKG, PREFS))
        return
    text = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
            '    <boolean name="simulate" value="true" />\n'
            '    <boolean name="dump_fixtures" value="true" />\n'
            '    <string name="simulate_samples">%s</string>\n</map>\n' % SAMPLES)
    subprocess.run([dev.ADB, '-s', dev.SERIAL, 'shell', 'run-as', dev.PKG, 'sh', '-c',
                    "'cat > %s.tmp && mv %s.tmp %s'" % (PREFS, PREFS, PREFS)], input=text.encode('utf-8'), check=True)
    head = dev.shell('run-as %s od -An -tx1 -N 4 %s' % (dev.PKG, PREFS)).split()
    assert head[0] == '3c', head


def screen_size(tree):
    return (max(dev.bounds(n)[2] for n in tree.iter('node')), max(dev.bounds(n)[3] for n in tree.iter('node')))


def node_by_rid(tree, rid):
    for n in tree.iter('node'):
        if (n.get('resource-id') or '').endswith('/' + rid):
            return n
    return None


def parents(tree):
    return {c: p for p in tree.iter() for c in p}


def card_of(tree, text):
    """标题正好是 [text] 的那张首页卡片（cv_thing）的 bounds；没有时返回 None。"""
    up = parents(tree)
    for n in tree.iter('node'):
        if (n.get('text') or '') != text:
            continue
        p = n
        while p is not None and not (p.get('resource-id') or '').endswith('/cv_thing'):
            p = up.get(p)
        if p is not None:
            return dev.bounds(p), dev.bounds(n)
    return None


def swipe(x, y1, y2):
    dev.shell('input swipe %d %d %d %d 900' % (x, y1, x, y2))
    time.sleep(0.9)


def at_top(tree):
    title = node_by_rid(tree, 'tv_header_title')
    bar = node_by_rid(tree, 'actionbar')
    return title is not None and bar is not None and dev.bounds(title)[1] > dev.bounds(bar)[3]


def scroll_to_top(width, height):
    for _ in range(20):
        tree = dev.dump()
        if at_top(tree):
            return
        swipe(width // 2, int(height * 0.35), int(height * 0.8))


def find_card(text, width, height, tries=10):
    """找标题为 [text] 的卡片：先回到列表顶部，再往下翻。标题要完整落在顶栏之下、手势条之上。"""
    scroll_to_top(width, height)
    for _ in range(tries):
        tree = dev.dump()
        found = card_of(tree, text)
        bar = node_by_rid(tree, 'actionbar')
        top = dev.bounds(bar)[3] + 12 if bar is not None else 200
        if found is not None and found[1][1] >= top and found[1][3] <= height - 110:
            # 等一会儿再读一次：滚动的惯性停下来之后才点
            time.sleep(0.6)
            again = card_of(dev.dump(), text)
            if again == found:
                return found
            continue
        swipe(width // 2, int(height * 0.75), int(height * 0.45))
    raise SystemExit('找不到卡片：%s' % text)


def long_press_select(title_bounds):
    """长按进入的是拖动排序：拖一下再回到原位松手才进入选择模式（见 adb.md），只在三星上用。"""
    cx, cy = (title_bounds[0] + title_bounds[2]) // 2, (title_bounds[1] + title_bounds[3]) // 2
    dev.shell('input motionevent DOWN %d %d' % (cx, cy))
    time.sleep(0.8)
    dev.shell('input motionevent MOVE %d %d' % (cx, cy + 80))
    time.sleep(0.25)
    dev.shell('input motionevent MOVE %d %d' % (cx, cy))
    time.sleep(0.25)
    dev.shell('input motionevent UP %d %d' % (cx, cy))
    time.sleep(1.2)


def selection_counter():
    texts = [n.get('text') or '' for n in dev.dump().iter('node')]
    return next((t.strip() for t in texts if re.fullmatch(r'\d+\s*/\s*\d+', t.strip())), None)


def overflow(text):
    dev.tap(*dev.center(dev.poll(desc='更多选项')))
    dev.tap(*dev.center(dev.poll(text=text)))


# ---------------------------------------------------------------- 数据库

def pull_db(name):
    """数据库连同 -wal / -shm 一起拉回来：刚写进去的行可能还在 WAL 里。"""
    local = os.path.join(OUT, name)
    os.makedirs(local, exist_ok=True)
    for suffix in ('', '-wal', '-shm'):
        r = subprocess.run([dev.ADB, '-s', dev.SERIAL, 'exec-out', 'run-as', dev.PKG, 'cat',
                            'databases/EverythingDoneData.db' + suffix], capture_output=True)
        path = os.path.join(local, 'db' + suffix)
        if suffix and (r.returncode != 0 or not r.stdout):
            if os.path.exists(path):
                os.remove(path)
            continue
        with open(path, 'wb') as f:
            f.write(r.stdout)
    with open(os.path.join(local, 'db'), 'rb') as f:
        assert f.read(15) == b'SQLite format 3', '拉回来的不是 SQLite 文件'
    return sqlite3.connect(os.path.join(local, 'db'))


def tree_of(db, folder_id):
    """文件夹 folder_id 的子树：按列表的排序（置顶在前、location 小的在前；普通 location 大的在前）。"""
    items = []
    for fid, title, loc, private, pres, color in db.execute(
            'select id, title, location, is_private, card_presentation, color from thing_folders '
            'where parent_folder_id=? and state=0', (folder_id,)):
        items.append((loc, -fid, {'folder': title, 'sticky': loc < 0, 'private': private,
                                  'presentation': json.loads(pres) if pres else None,
                                  'children': tree_of(db, fid)}))
    for tid, title, loc, state in db.execute(
            'select id, title, location, state from things where folder_id=? and type between 0 and 3',
            (folder_id,)):
        items.append((loc, tid, {'thing': title, 'sticky': loc < 0, 'state': state}))

    def key(e):
        loc, stable, _ = e
        # compareByLocationAndSticky：置顶（<0）在前、越小越前；普通越大越前；相同按 stableId
        return (0, loc, stable) if loc < 0 else (1, -loc, stable)
    return [e[2] for e in sorted(items, key=key)]


def describe(node, depth=0):
    pad = '  ' * depth
    if 'folder' in node:
        out = ['%s[文件夹] %s%s%s' % (pad, node['folder'], ' (置顶)' if node['sticky'] else '',
                                       ' (私密)' if node['private'] else '')]
        for c in node['children']:
            out += describe(c, depth + 1)
        return out
    return ['%s%s%s' % (pad, node['thing'], ' (置顶)' if node['sticky'] else '')]


# ---------------------------------------------------------------- 阶段：接收

def expected_folder(k, count, full, big):
    """模拟发送方的样例文件夹（SimulatedReceiverTransport.sampleFolder）在列表里应有的样子。"""
    children = [{'thing': THING_TITLE % (i + 1), 'sticky': i == 0} for i in range(count)]
    sub = {'folder': FOLDER_TITLE % ((k + 1) * 10 + 1), 'sticky': False,
           'children': [{'thing': THING_TITLE % (count + i + 1), 'sticky': False} for i in range(2)]}
    return {'folder': FOLDER_TITLE % (k + 1), 'sticky': False, 'big': big, 'full': full,
            'children': children + [sub]}


def matches(actual, expected):
    if 'folder' in expected:
        if actual.get('folder') != expected['folder'] or actual['sticky'] != expected['sticky']:
            return False
        if actual.get('private'):
            return False
        if len(actual['children']) != len(expected['children']):
            return False
        return all(matches(a, e) for a, e in zip(actual['children'], expected['children']))
    return actual.get('thing') == expected['thing'] and actual['sticky'] == expected['sticky']


def check_samples(db, label, min_id):
    """核对样例文件夹（只看 id 大于 min_id 的，即这一轮新建的）：各有一份（导入之后是两份）、结构与发送方一致、
    不是私密的、外观带过来了。"""
    report = {}
    for k, count, full, big in ((0, 3, False, True), (1, 2, True, False)):
        want = expected_folder(k, count, full, big)
        rows = db.execute('select id, location, is_private, card_presentation, parent_folder_id from thing_folders '
                          'where title=? and state=0 and id>? order by id', (want['folder'], min_id)).fetchall()
        found = []
        for fid, loc, private, pres, parent in rows:
            node = {'folder': want['folder'], 'sticky': loc < 0, 'private': private,
                    'children': tree_of(db, fid)}
            p = json.loads(pres) if pres else {}
            ok = matches(node, want) and parent is None and \
                p.get('mode') == (1 if big else 0) and p.get('spanMode') == (1 if full else 0)
            found.append({'id': fid, 'location': loc, 'ok': ok, 'presentation': p,
                          'tree': describe(node)})
        report[want['folder']] = found
    log('[%s] 数据库核对：' % label)
    for title, found in report.items():
        for f in found:
            log('  %s id=%d location=%d 结构一致=%s 外观=%s' % (title, f['id'], f['location'], f['ok'],
                                                        f['presentation']))
            for line in f['tree']:
                log('    ' + line)
    return report


def stage_receive():
    state = load_state()
    state['rotation_before'] = rotation()
    log('自动旋转（开始）', state['rotation_before'])
    state.setdefault('fixtures_before', fixtures())
    before = pull_db('db-before')
    state['min_id'] = max(before.execute('select max(id) from thing_folders').fetchone()[0] or 0,
                          before.execute('select max(id) from things').fetchone()[0] or 0)
    write_prefs(True)
    launch()
    width, height = screen_size(dev.dump())
    state['size'] = [width, height]
    save_state(state)
    scroll_to_top(width, height)
    dev.screencap('receive-0-before.png')
    tree = dev.dump()
    bar = node_by_rid(tree, 'actionbar')
    start = max(int(height * 0.30), dev.bounds(bar)[3] + 60 if bar is not None else 0)
    end = min(start + int(height * 0.55), height - 200)
    dev.drag(width // 2, start, width // 2, end, steps=26, step_ms=18)
    button = dev.poll(rid='tv_tap_receive')
    x, y = dev.center(button)
    p, remote = dev.record_start('receive-folders', 16)
    dev.tap(x, y)
    log('进入接收，模拟发送方约 3.5 秒后碰上来')
    p.wait()
    log('录屏：', dev.record_finish(p, remote, 'receive-folders'))
    dev.screencap('receive-1-landed.png')
    hint = [n.get('text') for n in dev.dump().iter('node') if (n.get('resource-id') or '').endswith('/tv_tap_receive_hint')]
    log('接收提示：', hint)
    dev.tap(x, y)
    time.sleep(2.0)
    dev.screencap('receive-2-exit.png')
    write_prefs(False)
    launch()
    db = pull_db('db-after-receive')
    report = check_samples(db, '接收之后', state['min_id'])
    things = db.execute("select id, location, folder_id from things where title=? and state=0 and folder_id is null "
                        "and id>?", (THING_TITLE % 3, state['min_id'])).fetchall()
    log('根目录的散记事「%s」：%s' % (THING_TITLE % 3, things))
    state['receive'] = {'hint': hint, 'report': report}
    save_state(state)


# ---------------------------------------------------------------- 阶段：分享（选择模式、文件夹页）

def pull_thing_file(name):
    files = [l.strip() for l in dev.shell('ls %s/thing_file' % FILES, check=False).splitlines()
             if l.strip().endswith('.edthing')]
    if not files:
        raise SystemExit('没有生成记事文件')
    remote = '%s/thing_file/%s' % (FILES, files[-1])
    local = os.path.join(OUT, name)
    remote_hash = dev.shell('md5sum "%s"' % remote).split()[0]
    for _ in range(4):
        dev.adb('pull', remote, local)
        with open(local, 'rb') as f:
            if hashlib.md5(f.read()).hexdigest() == remote_hash:
                break
    else:
        raise SystemExit('记事文件 md5 对不上')
    return files[-1], local


def inspect_file(local):
    with zipfile.ZipFile(local) as z:
        manifest = json.loads(z.read('things.json').decode('utf-8'))
        names = z.namelist()
    folders = manifest.get('folders', [])
    things = manifest['things']
    log('清单：version=%s folders=%d things=%d 条目=%d' % (manifest['version'], len(folders), len(things), len(names)))
    by_parent = {}
    for f in folders:
        by_parent.setdefault(f.get('folder'), []).append(('F', f))
    for t in things:
        by_parent.setdefault(t.get('folder'), []).append(('T', t))

    def walk(parent, depth):
        lines = []
        for kind, o in sorted(by_parent.get(parent, []), key=lambda e: e[1].get('order', 0)):
            pad = '  ' * depth
            if kind == 'F':
                lines.append('%s[文件夹 key=%d order=%d%s] %s 外观=%s' % (
                    pad, o['key'], o['order'], ' 置顶' if o.get('sticky') else '', o['title'],
                    o.get('cardPresentation')))
                lines += walk(o['key'], depth + 1)
            else:
                lines.append('%s[记事 order=%s%s] %s' % (pad, o.get('order'), ' 置顶' if o.get('sticky') else '',
                                                         o['title']))
        return lines
    for line in walk(None, 0):
        log('  ' + line)
    private_keys = [f for f in folders if 'isPrivate' in f or 'private' in f]
    log('  清单里带私密字段的文件夹：%d' % len(private_keys))
    return manifest


def last_session(before):
    for _ in range(30):
        out = dev.shell('ls %s' % FIXTURES, check=False)
        new = sorted(l.strip() for l in out.splitlines() if l.strip().endswith('-send-session') and l.strip() not in before)
        if new:
            text = dev.shell('cat %s/%s/session.json' % (FIXTURES, new[-1]), check=False)
            if text.strip().startswith('{'):
                try:
                    return new[-1], json.loads(text)
                except json.JSONDecodeError:
                    pass
        time.sleep(0.4)
    return None, None


def sessions():
    out = dev.shell('ls %s' % FIXTURES, check=False)
    return sorted(l.strip() for l in out.splitlines() if l.strip().endswith('-send-session'))


def fixtures():
    out = dev.shell('ls %s' % FIXTURES, check=False)
    return sorted(l.strip() for l in out.splitlines() if l.strip().endswith('-session'))


def clear_thing_files():
    """「记事文件」生成在应用的外部目录（Def.getAppFileDir）；adb shell 能删（见 adb.md）。"""
    dev.shell('rm -f %s/thing_file/*.edthing' % FILES, check=False)


def remove_new_fixtures(state):
    """只删这一轮生成的会话记录（发送与接收），更早的留着：桌面模拟器可能要用。"""
    before = set(state.get('fixtures_before', []))
    for d in fixtures():
        if d not in before:
            dev.shell('rm -rf %s/%s' % (FIXTURES, d), check=False)


def panel_title():
    return [n.get('text') for n in dev.dump().iter('node') if (n.get('resource-id') or '').endswith('/tv_tap_share_title')]


def share_thing_file_and_back():
    """面板上点「记事文件」，等系统分享面板出来再按返回。"""
    dev.tap(*dev.center(dev.poll(rid='ll_share_thing_file')))
    for _ in range(40):
        time.sleep(0.5)
        if 'ThingsActivity' not in top_activity():
            break
    time.sleep(1.5)
    dev.screencap('chooser.png')
    dev.shell('input keyevent 4')
    wait_activity('ThingsActivity')
    time.sleep(1.0)


def stage_select():
    state = load_state()
    width, height = state['size']
    # 调试开关打开（会话记录要落盘），但不模拟：选择模式里不要自动碰上来
    write_prefs(True)
    dev.shell("run-as %s sed -i 's|<boolean name=\"simulate\" value=\"true\" />|<boolean name=\"simulate\" value=\"false\" />|' %s"
              % (dev.PKG, PREFS))
    launch()
    big = FOLDER_TITLE % 1
    card, title = find_card(big, width, height)
    long_press_select(title)
    if 'DetailActivity' in top_activity():
        raise SystemExit('长按变成了点击')
    # 再选那条散的记事
    _, t2 = find_card(THING_TITLE % 3, width, height)
    dev.tap((t2[0] + t2[2]) // 2, (t2[1] + t2[3]) // 2)
    time.sleep(0.8)
    log('选择模式计数（文件夹 + 记事）：', selection_counter())
    before = sessions()
    overflow('分享')
    time.sleep(2.2)
    dev.screencap('select-mixed-lift.png')
    log('面板标题（混选）：', panel_title())
    dev.shell('input keyevent 4')
    time.sleep(1.6)
    name, session = last_session(before)
    if session:
        start = next(e for e in session['events'] if e['type'] == 'start')
        log('混选抬起的卡片：', [s['size'] for s in start['slots']], 'total', start['total'])
    # 只选「大」文件夹：取消那条散的记事
    _, t2 = find_card(THING_TITLE % 3, width, height)
    dev.tap((t2[0] + t2[2]) // 2, (t2[1] + t2[3]) // 2)
    time.sleep(0.8)
    log('选择模式计数（只剩文件夹）：', selection_counter())
    before = sessions()
    overflow('分享')
    time.sleep(2.2)
    dev.screencap('select-folder-lift.png')
    log('面板标题（只选文件夹）：', panel_title())
    clear_thing_files()
    share_thing_file_and_back()
    fname, local = pull_thing_file('select-folder.edthing')
    log('生成的文件：', fname)
    manifest = inspect_file(local)
    state['select_file'] = local
    state['select_manifest_version'] = manifest['version']
    save_state(state)
    # 选择模式应当已经退出
    log('分享之后的计数（应为空）：', selection_counter())


def stage_overflow():
    state = load_state()
    width, height = state['size']
    record = '--record' in sys.argv
    write_prefs(True)
    if not record:
        dev.shell("run-as %s sed -i 's|<boolean name=\"simulate\" value=\"true\" />|<boolean name=\"simulate\" value=\"false\" />|' %s"
                  % (dev.PKG, PREFS))
    launch()
    name = FOLDER_TITLE % 2
    _, title = find_card(name, width, height)
    dev.tap((title[0] + title[2]) // 2, (title[1] + title[3]) // 2)
    time.sleep(1.6)
    dev.screencap('overflow-0-inside.png')
    before = sessions()
    if record:
        p, remote = dev.record_start('send-folder', 20)
        overflow('分享此文件夹')
        p.wait()
        log('录屏：', dev.record_finish(p, remote, 'send-folder'))
        dev.shell('input keyevent 4')
        time.sleep(1.6)
    else:
        overflow('分享此文件夹')
        time.sleep(2.2)
        dev.screencap('overflow-1-lift.png')
        log('面板标题（文件夹页）：', panel_title())
        clear_thing_files()
        share_thing_file_and_back()
        fname, local = pull_thing_file('overflow-folder.edthing')
        log('生成的文件：', fname)
        inspect_file(local)
        state['overflow_file'] = local
        save_state(state)
    _, session = last_session(before)
    if session:
        start = next(e for e in session['events'] if e['type'] == 'start')
        log('文件夹页抬起的卡片：', [s['size'] for s in start['slots']], '起点', [s.get('origin') for s in start['slots']])
        phases = [e['name'] for e in session['events'] if e['type'] == 'phase']
        log('碰一碰阶段：', phases)
    # 回到根目录
    dev.shell('input keyevent 4')
    time.sleep(1.2)


# ---------------------------------------------------------------- 阶段：导入

def stage_import():
    state = load_state()
    # 选择模式那一段只在三星上跑；魅族上用文件夹页生成的文件
    local = state.get('select_file') or state['overflow_file']
    remote_dir = FILES + '/folder-import-test'
    remote = remote_dir + '/test.edthing'
    dev.shell('mkdir -p %s' % remote_dir)
    dev.adb('push', local, remote)
    launch()
    dev.shell('am start -a android.intent.action.VIEW -d file://%s -n %s/.activities.ThingFileImportActivity'
              % (remote, dev.PKG))
    if not wait_activity('ThingFileImportActivity'):
        raise SystemExit('导入页没有起来：%s' % top_activity())
    time.sleep(2.5)
    dev.screencap('import-0-preview.png')
    tree = dev.dump()
    rows = [(n.get('text'), dev.bounds(n)) for n in tree.iter('node')
            if (n.get('resource-id') or '').endswith('/tv_import_thing_title')]
    infos = [n.get('text') for n in tree.iter('node') if (n.get('resource-id') or '').endswith('/tv_import_thing_info')]
    confirm = [n.get('text') for n in tree.iter('node') if (n.get('resource-id') or '').endswith('/tv_import_confirm')]
    log('预览的行：', rows, infos, '按钮：', confirm)
    expand = node_by_rid(tree, 'iv_import_expand')
    if expand is None:
        raise SystemExit('文件夹那一行没有展开按钮')
    dev.tap(*dev.center(expand))
    time.sleep(1.0)
    dev.screencap('import-1-expanded.png')
    tree = dev.dump()
    rows = [n.get('text') for n in tree.iter('node') if (n.get('resource-id') or '').endswith('/tv_import_thing_title')]
    log('展开之后的行：', rows)
    # 展开子文件夹：除了顶层那一行之外、标题是样例文件夹的那一行
    top_title = rows[0]
    prefix = FOLDER_TITLE.split('%')[0]
    subs = [n for n in tree.iter('node') if (n.get('text') or '').startswith(prefix) and n.get('text') != top_title
            and (n.get('resource-id') or '').endswith('/tv_import_thing_title')]
    if subs:
        dev.tap(*dev.center(subs[0]))
        time.sleep(1.0)
        dev.screencap('import-2-sub-expanded.png')
        log('展开子文件夹之后的行：',
            [n.get('text') for n in dev.dump().iter('node') if (n.get('resource-id') or '').endswith('/tv_import_thing_title')])
    # 文件里的记事与本机的完全相同时文件夹默认不勾（「已有相同内容」）：先勾上再导入
    tree = dev.dump()
    confirm = node_by_rid(tree, 'tv_import_confirm')
    if confirm is not None and '未选中' in (confirm.get('text') or ''):
        top_row = next(n for n in tree.iter('node') if (n.get('text') or '') == top_title)
        dev.tap(*dev.center(top_row))
        time.sleep(0.8)
        dev.screencap('import-2b-checked.png')
        log('勾上文件夹之后的按钮：', [n.get('text') for n in dev.dump().iter('node')
                                    if (n.get('resource-id') or '').endswith('/tv_import_confirm')])
    dev.tap(*dev.center(dev.poll(rid='tv_import_confirm')))
    wait_activity('ThingsActivity', tries=40)
    time.sleep(2.0)
    dev.screencap('import-3-home.png')
    db = pull_db('db-after-import')
    state['import'] = check_samples(db, '导入之后', state['min_id'])
    save_state(state)
    dev.shell('rm -rf %s' % remote_dir, check=False)


# ---------------------------------------------------------------- 阶段：清理

def stage_cleanup():
    state = load_state()
    width, height = state['size']
    write_prefs(False)
    launch()
    removed = 0
    for _ in range(12):
        scroll_to_top(width, height)
        tree = dev.dump()
        targets = [t for t in [FOLDER_TITLE % 1, FOLDER_TITLE % 2, THING_TITLE % 3] if card_of(tree, t)]
        if not targets:
            # 这一屏没有：往下再找几屏
            found = None
            for _ in range(6):
                swipe(width // 2, int(height * 0.75), int(height * 0.45))
                tree = dev.dump()
                found = next((t for t in [FOLDER_TITLE % 1, FOLDER_TITLE % 2, THING_TITLE % 3] if card_of(tree, t)), None)
                if found:
                    break
            if not found:
                break
            targets = [found]
        _, title = find_card(targets[0], width, height)
        long_press_select(title)
        if 'DetailActivity' in top_activity():
            dev.shell('input keyevent 4')
            raise SystemExit('清理：长按变成了点击')
        counter = selection_counter()
        if counter is None or int(counter.split('/')[0]) != 1:
            dev.shell('input keyevent 4')
            raise SystemExit('清理：选中的不是一项 %s' % counter)
        dev.screencap('cleanup-%02d.png' % removed)
        overflow('删除')
        time.sleep(1.0)
        # 确认框「将把所选记事移入回收站…」：核对件数再点确认
        tree = dev.dump()
        confirm = node_by_rid(tree, 'tv_confirm_as_bt_alert')
        if confirm is not None:
            message = [n.get('text') for n in tree.iter('node') if '回收站' in (n.get('text') or '')]
            log('清理：确认框', message)
            dev.tap(*dev.center(confirm))
        time.sleep(2.0)
        removed += 1
        log('清理：已移入回收站', targets[0])
    remove_new_fixtures(state)
    clear_thing_files()
    launch()
    log('清理完成：%d 项；自动旋转（结束）%s（开始时 %s）' % (removed, rotation(), state.get('rotation_before')))


def manifest_tree(manifest):
    """清单还原成嵌套列表：文件夹是 (标题, [子项])，记事是 (标题, 类型, 是否置顶)。"""
    by_parent = {}
    for f in manifest.get('folders', []):
        by_parent.setdefault(f.get('folder'), []).append(('F', f))
    for t in manifest['things']:
        by_parent.setdefault(t.get('folder'), []).append(('T', t))

    def walk(parent):
        out = []
        for kind, o in sorted(by_parent.get(parent, []), key=lambda e: e[1].get('order', 0)):
            if kind == 'F':
                out.append((o['title'], walk(o['key'])))
            else:
                out.append((o['title'], o['type'], bool(o.get('sticky'))))
        return out
    return walk(None)


def open_filtered(mask, folder_id=None):
    """用列表小部件的入口带类型筛选（与文件夹）打开首页。"""
    cmd = 'am start -n %s/.activities.ThingsActivity --ei com.ywwynm.everythingdone.key.type_filter_mask %d' % (
        dev.PKG, mask)
    if folder_id is not None:
        cmd += ' --el com.ywwynm.everythingdone.key.folder_id %d' % folder_id
    dev.shell(cmd)
    wait_activity('ThingsActivity')
    time.sleep(2.0)


def stage_filter():
    """类型筛选下分享文件夹：只带符合筛选的记事，子文件夹只在里面有符合的记事时才带（决策 2026-09-28 第 2 条）。"""
    state = load_state()
    db = pull_db('db-filter')
    folder2 = db.execute('select id from thing_folders where title=? and state=0 and parent_folder_id is null '
                         'and id>? order by id limit 1', (FOLDER_TITLE % 2, state['min_id'])).fetchone()[0]
    write_prefs(True)
    dev.shell("run-as %s sed -i 's|<boolean name=\"simulate\" value=\"true\" />|<boolean name=\"simulate\" value=\"false\" />|' %s"
              % (dev.PKG, PREFS))
    launch()
    REMINDER, NOTE = 1, 0
    expected = {
        # 只看提醒：散的记事与置顶的那条都不带，子文件夹里只剩那条提醒
        2: [(FOLDER_TITLE % 2, [(FOLDER_TITLE % 21, [(THING_TITLE % 4, REMINDER, False)])])],
        # 只看记录：提醒不带，子文件夹里只剩那条记录
        1: [(FOLDER_TITLE % 2, [(THING_TITLE % 1, NOTE, True), (THING_TITLE % 2, NOTE, False),
                                (FOLDER_TITLE % 21, [(THING_TITLE % 3, NOTE, False)])])],
    }
    results = {}
    for mask, label in ((2, '只看提醒'), (1, '只看记录')):
        open_filtered(mask)
        dev.screencap('filter-%d-root.png' % mask)
        open_filtered(mask, folder2)
        dev.screencap('filter-%d-inside.png' % mask)
        header = [n.get('text') for n in dev.dump().iter('node') if (n.get('resource-id') or '').endswith('/tv_header_title')]
        overflow('分享此文件夹')
        time.sleep(2.2)
        dev.screencap('filter-%d-lift.png' % mask)
        clear_thing_files()
        share_thing_file_and_back()
        _, local = pull_thing_file('filter-%d.edthing' % mask)
        tree = manifest_tree(inspect_file(local))
        ok = tree == expected[mask]
        log('[%s] 文件夹页标题 %s；清单与预期一致：%s' % (label, header, ok))
        if not ok:
            log('  实际：', tree)
            log('  预期：', expected[mask])
        results[label] = ok
    # 恢复：根目录、不筛选
    open_filtered(0)
    dev.screencap('filter-reset.png')
    write_prefs(False)
    launch()
    state['filter'] = results
    save_state(state)


def stage_thingsonly():
    """只含记事的分享仍写版本 1、没有文件夹与先后字段（决策 2026-09-28 第 10 条）：用 SAMPLES=plain 模拟接收一条
    纯文字样例，从详情页分享「记事文件」，拉回来核对，最后把样例从详情页删除。"""
    state = load_state()
    state.setdefault('fixtures_before', fixtures())
    save_state(state)
    write_prefs(True)
    launch()
    width, height = screen_size(dev.dump())
    scroll_to_top(width, height)
    tree = dev.dump()
    bar = node_by_rid(tree, 'actionbar')
    start = max(int(height * 0.30), dev.bounds(bar)[3] + 60 if bar is not None else 0)
    end = min(start + int(height * 0.55), height - 200)
    dev.drag(width // 2, start, width // 2, end, steps=26, step_ms=18)
    button = dev.poll(rid='tv_tap_receive')
    x, y = dev.center(button)
    dev.tap(x, y)
    time.sleep(16)
    dev.tap(x, y)
    time.sleep(2.0)
    write_prefs(False)
    launch()
    _, t = find_card(THING_TITLE % 1, width, height)
    dev.tap((t[0] + t[2]) // 2, (t[1] + t[3]) // 2)
    if not wait_activity('DetailActivity'):
        raise SystemExit('没有打开详情页')
    time.sleep(1.0)
    overflow('分享')
    time.sleep(1.9)
    clear_thing_files()
    dev.tap(*dev.center(dev.poll(rid='ll_share_thing_file')))
    for _ in range(40):
        time.sleep(0.5)
        if 'DetailActivity' not in top_activity():
            break
    time.sleep(1.5)
    dev.shell('input keyevent 4')
    wait_activity('DetailActivity')
    time.sleep(1.0)
    _, local = pull_thing_file('things-only.edthing')
    with zipfile.ZipFile(local) as z:
        manifest = json.loads(z.read('things.json').decode('utf-8'))
    keys = sorted(set(k for t in manifest['things'] for k in t))
    log('只含记事：version=%s folders 字段=%s 记事字段=%s' % (manifest['version'], 'folders' in manifest, keys))
    ok = manifest['version'] == 1 and 'folders' not in manifest and not ({'folder', 'order', 'sticky'} & set(keys))
    log('只含记事的文件与以前相同：%s' % ok)
    titles = [n.get('text') for n in dev.dump().iter('node') if (n.get('resource-id') or '').endswith('/et_title')]
    if titles != [THING_TITLE % 1]:
        raise SystemExit('详情页不是样例：%s' % titles)
    overflow('删除')
    wait_activity('ThingsActivity')
    time.sleep(1.2)
    log('样例已移入回收站')
    remove_new_fixtures(state)
    clear_thing_files()
    state['things_only'] = ok
    save_state(state)


PRIV_FOLDER = '碰一碰模拟 · 私密标题 %d'
PRIV_TITLE = '7.6凌晨'
# 私密记事的标记是标题前缀（Thing.PRIVATE_THING_PREFIX = base_signal + "L"）。记事文件导入时不去前缀，
# 带前缀的测试文件导进来就是私密记事；两台测试机都没设私密密码，验证直接放行
PRIVATE_PREFIX = '`启q琼L'


def signed(color):
    return color - (1 << 32) if color >= (1 << 31) else color


def build_private_title_file(path):
    """两个「大」文件夹（普通宽 / 通栏），各带两条普通记事与一条标题带私密前缀的记事（第三张预览）。"""
    now = int(time.time() * 1000)
    folders = []
    things = []
    for key, span, color in ((0, 0, 0xFF8E24AA), (1, 1, 0xFF00897B)):
        folders.append({'key': key, 'order': key, 'title': PRIV_FOLDER % (key + 1),
                        'background': {'mode': 'PURE', 'color': signed(color)},
                        'cardPresentation': {'version': 1, 'mode': 1, 'thumbnailLimit': 4, 'spanMode': span},
                        'createTime': now, 'updateTime': now})
        for i, (title, private) in enumerate(((THING_TITLE % 1, False), (THING_TITLE % 2, False),
                                               (PRIV_TITLE, True))):
            things.append({'type': 0, 'state': 0, 'title': (PRIVATE_PREFIX + title) if private else title,
                           'content': '这是碰一碰的模拟数据，用来预览接收动画，可以直接删除。',
                           'createTime': now, 'updateTime': now, 'finishTime': 0,
                           'background': {'mode': 'PURE', 'color': signed(0xFF3949AB if private else 0xFF43A047)},
                           'attachments': [], 'folder': key, 'order': i})
    manifest = {'format': 'everythingdone.thing-file', 'version': 2, 'appVersionCode': 43, 'exportTime': now,
                'folders': folders, 'things': things}
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('things.json', json.dumps(manifest, ensure_ascii=False))


def zoom_title(shot, name, pad=40, scale=3):
    """按 dump 里标题控件的位置从截图里裁出来放大；返回标题的 bounds（找不到时 None）。

    同名的节点可能不止一个（背后页面上的那张卡片、详情页的标题框）：分享界面挂在内容视图的最后，
    取最后一个匹配的 tv_thing_title。"""
    tree = dev.dump()
    width, height = screen_size(tree)
    nodes = [n for n in tree.iter('node') if (n.get('text') or '') == PRIV_TITLE
             and (n.get('resource-id') or '').endswith('/tv_thing_title')]
    if not nodes:
        log('  %s：界面树里找不到标题「%s」' % (name, PRIV_TITLE))
        return None
    x1, y1, x2, y2 = dev.bounds(nodes[-1])
    left, top = max(0, x1 - pad), max(0, y1 - pad)
    w = min((x2 - x1) + 6 * pad, width - left)
    h = min((y2 - y1) + 2 * pad, height - top)
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', shot, '-vf',
                    'crop=%d:%d:%d:%d,scale=iw*%d:-1:flags=neighbor' % (w, h, left, top, scale),
                    os.path.join(OUT, name)], check=True)
    log('  %s：标题控件 %s（宽 %d）' % (name, (x1, y1, x2, y2), x2 - x1))
    return x1, y1, x2, y2


def enter_folder(title, width, height):
    _, t = find_card(title, width, height)
    dev.tap((t[0] + t[2]) // 2, (t[1] + t[3]) // 2)
    time.sleep(1.6)
    header = [n.get('text') for n in dev.dump().iter('node') if (n.get('resource-id') or '').endswith('/tv_header_title')]
    if header != [title]:
        raise SystemExit('没有进入文件夹 %s（%s）' % (title, header))


def stage_privatetitle():
    """已揭示的私密记事标题（前面有开锁图标）在抬起的卡片与首页缩略图里是否被截断（2026-09-28 用户截图）。"""
    state = load_state()
    width, height = state.get('size') or screen_size(dev.dump())
    state['size'] = [width, height]
    save_state(state)
    tag = sys.argv[3] if len(sys.argv) > 3 and not sys.argv[3].startswith('--') else 'run'
    # 上一轮中途出错可能停在分享界面或详情页上：先退回首页
    for _ in range(3):
        if 'ThingsActivity' in top_activity() and node_by_rid(dev.dump(), 'tv_tap_share_title') is None:
            break
        dev.shell('input keyevent 4')
        time.sleep(1.2)
    write_prefs(False)
    launch()
    capture = '--cleanup-only' not in sys.argv
    if capture and '--no-import' not in sys.argv:
        local = os.path.join(OUT, 'private-title.edthing')
        build_private_title_file(local)
        remote_dir = FILES + '/folder-import-test'
        dev.shell('mkdir -p %s' % remote_dir)
        dev.adb('push', local, remote_dir + '/private.edthing')
        dev.shell('am start -a android.intent.action.VIEW -d file://%s/private.edthing -n %s/.activities.ThingFileImportActivity'
                  % (remote_dir, dev.PKG))
        if not wait_activity('ThingFileImportActivity'):
            raise SystemExit('导入页没有起来')
        time.sleep(2.5)
        dev.tap(*dev.center(dev.poll(rid='tv_import_confirm')))
        wait_activity('ThingsActivity', tries=40)
        time.sleep(2.0)
        dev.shell('rm -rf %s' % remote_dir, check=False)
    if capture:
        for n in (1, 2):
            enter_folder(PRIV_FOLDER % n, width, height)
            overflow('分享此文件夹')
            time.sleep(2.2)
            shot = dev.screencap('privtitle-%s-lift-%d.png' % (tag, n))
            zoom_title(shot, 'privtitle-%s-lift-%d-zoom.png' % (tag, n))
            dev.shell('input keyevent 4')
            time.sleep(1.4)
            if n == 1:
                # 详情页分享这条私密记事：抬起的是记事卡片，标题前同样有开锁图标
                cards = card_of(dev.dump(), PRIV_TITLE)
                if cards is None:
                    log('  文件夹页里找不到私密记事的卡片（遮蔽态不显示标题）')
                    tree = dev.dump()
                    locks = [n2 for n2 in tree.iter('node') if (n2.get('resource-id') or '').endswith('/iv_private_thing')]
                    target = dev.center(locks[0]) if locks else None
                else:
                    target = ((cards[1][0] + cards[1][2]) // 2, (cards[1][1] + cards[1][3]) // 2)
                if target is not None:
                    dev.tap(*target)
                    if wait_activity('DetailActivity'):
                        time.sleep(1.0)
                        overflow('分享')
                        time.sleep(1.9)
                        shot = dev.screencap('privtitle-%s-detail.png' % tag)
                        zoom_title(shot, 'privtitle-%s-detail-zoom.png' % tag)
                        dev.shell('input keyevent 4')
                        time.sleep(1.2)
                        dev.shell('input keyevent 4')
                        wait_activity('ThingsActivity')
                        time.sleep(1.2)
            dev.shell('input keyevent 4')
            time.sleep(1.4)
        # 首页：打开过的私密记事在本次会话里算已解锁，通栏「大」文件夹的缩略图（瀑布流，排之前单独量过）显示标题
        enter_folder(PRIV_FOLDER % 2, width, height)
        tree = dev.dump()
        locks = [n2 for n2 in tree.iter('node') if (n2.get('resource-id') or '').endswith('/iv_private_thing')]
        if locks:
            dev.tap(*dev.center(locks[0]))
            if wait_activity('DetailActivity'):
                time.sleep(1.0)
                dev.shell('input keyevent 4')
                wait_activity('ThingsActivity')
                time.sleep(1.2)
        dev.shell('input keyevent 4')
        time.sleep(1.6)
        _, t = find_card(PRIV_FOLDER % 2, width, height)
        time.sleep(1.0)
        shot = dev.screencap('privtitle-%s-home.png' % tag)
        zoom_title(shot, 'privtitle-%s-home-zoom.png' % tag)
    if '--keep' in sys.argv:
        return
    # 清理：进入文件夹，「删除当前文件夹中所有正在进行的记事」
    for n in (1, 2):
        try:
            enter_folder(PRIV_FOLDER % n, width, height)
        except SystemExit as e:
            log('清理：', e)
            continue
        overflow('删除当前文件夹中所有正在进行的记事')
        time.sleep(1.0)
        confirm = node_by_rid(dev.dump(), 'tv_confirm_as_bt_alert')
        if confirm is not None:
            dev.tap(*dev.center(confirm))
        time.sleep(2.0)
        header = [n2.get('text') for n2 in dev.dump().iter('node') if (n2.get('resource-id') or '').endswith('/tv_header_title')]
        if header == [PRIV_FOLDER % n]:
            dev.shell('input keyevent 4')
            time.sleep(1.2)
        log('清理：已移入回收站', PRIV_FOLDER % n)
    launch()
    log('自动旋转', rotation())


def stage_privatetitle_select():
    """同上，走用户截图里的路径：首页选择模式里选中文件夹分享（只在三星上用，魅族长按会被识屏截走）。"""
    state = load_state()
    width, height = state.get('size') or screen_size(dev.dump())
    tag = sys.argv[3] if len(sys.argv) > 3 and not sys.argv[3].startswith('--') else 'select'
    for _ in range(3):
        if 'ThingsActivity' in top_activity() and node_by_rid(dev.dump(), 'tv_tap_share_title') is None:
            break
        dev.shell('input keyevent 4')
        time.sleep(1.2)
    launch()
    for n in (1, 2):
        _, title = find_card(PRIV_FOLDER % n, width, height)
        long_press_select(title)
        if 'DetailActivity' in top_activity():
            raise SystemExit('长按变成了点击')
        log('选择模式计数：', selection_counter())
        overflow('分享')
        time.sleep(2.4)
        shot = dev.screencap('privtitle-%s-select-%d.png' % (tag, n))
        zoom_title(shot, 'privtitle-%s-select-%d-zoom.png' % (tag, n))
        dev.shell('input keyevent 4')
        time.sleep(1.4)
        dev.shell('input keyevent 4')
        time.sleep(1.2)


def stage_cleanup_flyme():
    """魅族上长按会被识屏截走：进入文件夹用右上角「删除当前文件夹中所有正在进行的记事」，散的记事从详情页删除。"""
    state = load_state()
    width, height = state['size']
    write_prefs(False)
    launch()
    removed = 0
    for _ in range(8):
        # 还有哪个样例文件夹（接收的与导入的可能各有一份）
        title = None
        for candidate in (FOLDER_TITLE % 1, FOLDER_TITLE % 2):
            try:
                find_card(candidate, width, height, tries=6)
                title = candidate
                break
            except SystemExit:
                continue
        if title is None:
            break
        _, t = find_card(title, width, height)
        dev.tap((t[0] + t[2]) // 2, (t[1] + t[3]) // 2)
        time.sleep(1.6)
        header = [n.get('text') for n in dev.dump().iter('node') if (n.get('resource-id') or '').endswith('/tv_header_title')]
        if header != [title]:
            raise SystemExit('清理：没有进入文件夹 %s（%s）' % (title, header))
        overflow('删除当前文件夹中所有正在进行的记事')
        time.sleep(1.0)
        tree = dev.dump()
        confirm = node_by_rid(tree, 'tv_confirm_as_bt_alert')
        if confirm is None:
            raise SystemExit('清理：没有确认框')
        log('清理：确认框', [n.get('text') for n in tree.iter('node') if '回收站' in (n.get('text') or '')])
        dev.tap(*dev.center(confirm))
        time.sleep(2.0)
        header = [n.get('text') for n in dev.dump().iter('node') if (n.get('resource-id') or '').endswith('/tv_header_title')]
        if header == [title]:
            dev.shell('input keyevent 4')
            time.sleep(1.2)
        removed += 1
        log('清理：已移入回收站', title)
    for _ in range(3):
        scroll_to_top(width, height)
        try:
            _, t = find_card(THING_TITLE % 3, width, height, tries=6)
        except SystemExit:
            break
        dev.tap((t[0] + t[2]) // 2, (t[1] + t[3]) // 2)
        if not wait_activity('DetailActivity'):
            raise SystemExit('清理：没有打开详情页')
        time.sleep(1.0)
        titles = [n.get('text') for n in dev.dump().iter('node') if (n.get('resource-id') or '').endswith('/et_title')]
        if titles != [THING_TITLE % 3]:
            dev.shell('input keyevent 4')
            raise SystemExit('清理：打开的详情页不对 %s' % titles)
        overflow('删除')
        wait_activity('ThingsActivity')
        time.sleep(1.2)
        removed += 1
        log('清理：已移入回收站', THING_TITLE % 3)
    remove_new_fixtures(state)
    clear_thing_files()
    launch()
    log('清理完成：%d 项；自动旋转（结束）%s（开始时 %s）' % (removed, rotation(), state.get('rotation_before')))


if __name__ == '__main__':
    if 'isKeyguardShowing=true' in dev.shell('dumpsys window'):
        raise SystemExit('设备锁屏中，请先解锁（密码不代输）')
    {'receive': stage_receive, 'select': stage_select, 'overflow': stage_overflow,
     'import': stage_import, 'filter': stage_filter, 'thingsonly': stage_thingsonly,
     'privatetitle': stage_privatetitle, 'privatetitle-select': stage_privatetitle_select, 'cleanup': stage_cleanup,
     'cleanup-flyme': stage_cleanup_flyme}[STAGE]()
