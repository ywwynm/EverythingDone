"""真实空白新建页的大弹窗测量；只开关附件、颜色弹窗，不选附件或颜色，不保存记事。"""
import argparse
import json
import re
import sys
import time
import xml.etree.ElementTree as ET

import drift_driver as d
import perf_compare as pc
import creation_launch_perf as observer


def animation_presentations(result):
    """只统计交给粒子会话的缓冲，排除 Surface 预热空帧和收尾清屏；缺失时间不作已呈现。"""
    targets = {texture[2] for overlay in result.get('overlays', []) for texture in overlay['textures']}
    shown = sorted({row[3] for row in result.get('beats', [])
                    if row[0] == 3 and row[1] in targets and row[3] > 0})
    gaps = sorted((b - a) / 1e6 for a, b in zip(shown, shown[1:]))
    if not gaps:
        return None
    return dict(frames=len(shown), fps=round(1e9 * (len(shown) - 1) / (shown[-1] - shown[0]), 1),
                gapP50Ms=round(gaps[len(gaps) // 2], 2),
                gapP90Ms=round(gaps[min(len(gaps) - 1, int(len(gaps) * .9))], 2),
                gapMaxMs=round(gaps[-1], 2))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run', required=True)
    parser.add_argument('--rounds', type=int, default=2)
    parser.add_argument('--trace-system', action='store_true')
    parser.add_argument('--window-mode', choices=('on', 'off'))
    parser.add_argument('--home-panel', action='store_true')
    parser.add_argument('--home-only', action='store_true', help='仍往返空白新建页，只测回到首页后的面板')
    parser.add_argument('--home-color-page', action='store_true', help='展开首页颜色页后再测大面板消散')
    parser.add_argument('--no-system-snapshot', action='store_true')
    parser.add_argument('--style', choices=('drift', 'flow'))
    parser.add_argument('--amount', type=float)
    parser.add_argument('--size', type=float)
    parser.add_argument('--color-precision', choices=('high', 'standard'))
    parser.add_argument('--pressure-after-present', choices=('on', 'off'))
    parser.add_argument('--pressure-input-reuse', choices=('on', 'off'))
    parser.add_argument('--fused-circle-records', choices=('on', 'off'))
    parser.add_argument('--reuse-timeline', action='store_true', help='消散结束后，对同一真实材料交替测量逐帧成本')
    args = parser.parse_args()
    assert re.fullmatch(r'[a-zA-Z0-9_-]{1,50}', args.run)
    folder = d.OUT / args.run
    folder.mkdir(parents=True, exist_ok=True)
    original = {}
    tuning_path = 'files/particle-perf-tuning.json'
    preferences_path = 'shared_prefs/particle_tuning.xml'
    for path in (observer.ENABLED, observer.CONTROL, tuning_path, preferences_path):
        result = d.soft('shell', 'run-as', d.PACKAGE, 'cat', path)
        original[path] = result.stdout if result.returncode == 0 else None
    rotation = d.rotation_state()
    sys.path.insert(0, str(d.REPO / 'docs/features/particle-animation-performance'))
    import analyze_beats
    rows = []

    def measure(name, action, snapshot=False):
        run = args.run + '-' + name
        timeline = args.reuse_timeline and name.endswith('-dismiss')
        observer.control(dict(run_id=run, kind='dialog', beatTrace=True, stallSampling=False,
                              segmentBench=timeline, reuseTimeline=timeline))
        observer.await_json(observer.REMOTE + run + '/progress.json', lambda value: value.get('ready'))
        action()
        if snapshot and not args.no_system_snapshot:
            time.sleep(.3)
            (folder / (name + '-surface.txt')).write_bytes(d.adb('shell', 'dumpsys', 'SurfaceFlinger'))
            (folder / (name + '-display.txt')).write_bytes(d.adb('shell', 'dumpsys', 'display'))
        # 取证期内不导出 UI 层级，避免额外服务和主线程负载影响帧节奏。
        time.sleep(1.5)
        observer.control({})
        result = observer.await_json(observer.REMOTE + run + '/result.json')
        path = folder / (name + '.json')
        path.write_text(json.dumps(result), 'utf-8')
        row = dict(name=name, beats=analyze_beats.report(path), animation=animation_presentations(result))
        rows.append(row)
        print(json.dumps(row, ensure_ascii=False), flush=True)
        if timeline:
            # 等离屏对照结束，再开始下一项 UI 操作，避免同时争用 GPU。
            value = observer.await_json(observer.REMOTE + run + '/reuse-timeline.json', timeout=90)
            (folder / (name + '-timeline.json')).write_text(json.dumps(value), 'utf-8')
            assert 'error' not in value, value.get('error')

    tracing = False
    try:
        d.adb('shell', 'am', 'force-stop', d.PACKAGE)
        if args.style:
            preferences = ET.fromstring(original[preferences_path] or b'<map/>')
            updates = {'particle_style': ('string', args.style)}
            if args.color_precision:
                key = 'drift_color_precision_standard' if args.style == 'drift' else 'color_precision_standard'
                updates[key] = ('boolean', str(args.color_precision == 'standard').lower())
            if args.amount is not None:
                assert .1 <= args.amount <= 1.6
                updates[args.style + '_amount_tenths'] = ('int', str(round(args.amount*10)))
            if args.size is not None:
                assert .5 <= args.size <= 6
                updates[args.style + '_size_hundredths'] = ('int', str(round(args.size*100)))
            for key, (tag, value) in updates.items():
                for node in list(preferences):
                    if node.get('name') == key:
                        preferences.remove(node)
                node = ET.SubElement(preferences, tag, {'name': key})
                if tag == 'string':
                    node.text = value
                else:
                    node.set('value', value)
            observer.write_private(preferences_path, ET.tostring(preferences, encoding='utf-8', xml_declaration=True))
        if any(value is not None for value in (args.window_mode, args.pressure_after_present,
                                              args.pressure_input_reuse, args.fused_circle_records)):
            tuning = json.loads(original[tuning_path]) if original[tuning_path] else {}
            if args.window_mode is not None:
                tuning['driftWindowFrameRate'] = args.window_mode == 'on'
            if args.pressure_after_present is not None:
                tuning['flowPressureAfterPresent'] = args.pressure_after_present == 'on'
            if args.pressure_input_reuse is not None:
                tuning['flowPressureInputReuse'] = args.pressure_input_reuse == 'on'
            if args.fused_circle_records is not None:
                tuning['flowFusedCircleRecords'] = args.fused_circle_records == 'on'
            observer.write_private(tuning_path, json.dumps(tuning).encode())
        observer.write_private(observer.ENABLED, b'1')
        observer.control({})
        d.launch()
        tree = d.dump('fab_create')
        count = d.find(tree, rid='tv_header_subtitle')
        before = count.get('text') if count is not None else None
        (folder / 'home-count-before.txt').write_text(before or '', 'utf-8')
        d.tap(d.find(tree, rid='fab_create'))
        assert pc.wait_resumed('DetailActivity', 6), '未打开空白新建页'
        tree = d.dump('act_add_attachment')
        d.adb('shell', 'input', 'keyevent', '4')
        assert 'DetailActivity' in pc.resumed_activity(), '收起键盘时已离开新建页'
        tree = d.dump('act_add_attachment')
        if args.trace_system:
            d.adb('shell', 'atrace', '--async_start', '-b', '24576', 'gfx', 'view', 'sched', 'freq')
            tracing = True
        dialogs = () if args.home_only else (('attachment', 'act_add_attachment'), ('color', 'act_change_color'))
        for name, rid in dialogs:
            for index in range(args.rounds):
                node = d.find(tree, rid=rid)
                assert node is not None, rid
                measure('%s-%d-appear' % (name, index), lambda: d.tap(node))
                opened = d.dump()
                (folder / ('%s-%d-ui.xml' % (name, index))).write_bytes(
                    __import__('xml.etree.ElementTree', fromlist=['tostring']).tostring(opened))
                assert d.find(opened, rid=rid) is None, '弹窗没有显示'
                if index == 0:
                    d.shot(folder, name)
                measure('%s-%d-dismiss' % (name, index),
                        lambda: d.adb('shell', 'input', 'keyevent', '4'), snapshot=index == 0)
                tree = d.dump(rid)
        # 空记事返回后核对首页计数，测量过程中未输入任何内容。
        d.adb('shell', 'input', 'keyevent', '4')
        assert pc.wait_resumed('ThingsActivity', 5), '未返回首页'
        tree = d.dump('fab_create')
        if args.home_panel or args.home_only:
            # 只选中可见卡片并开关面板，不调整选项、不确认草稿。
            card = d.find(tree, rid='cv_thing')
            assert card is not None, '首页没有可用于只读预览的记事卡片'
            x, y = d.center(card)
            d.adb('shell', 'input', 'swipe', x, y, x, y, 650)
            selected = d.dump('act_customize_card_appearance')
            for index in range(args.rounds):
                button = d.find(selected, rid='act_customize_card_appearance')
                measure('home-color-%d-appear' % index, lambda: d.tap(button))
                opened = d.dump('thing_card_appearance_panel')
                if args.home_color_page:
                    d.tap(d.find(opened, rid='bt_thing_card_appearance_change_color'))
                    opened = d.dump('bt_tca_color_page_back')
                    if index == 0:
                        d.shot(folder, 'home-expanded-color')
                cancel = d.find(opened, rid='bt_cancel_thing_card_appearance')
                assert cancel is not None
                measure('home-color-%d-dismiss' % index, lambda: d.tap(cancel), snapshot=index == 0)
                selected = d.dump('act_customize_card_appearance')
                assert d.find(selected, rid='thing_card_appearance_panel') is None
            d.adb('shell', 'input', 'keyevent', '4')
            tree = d.dump('fab_create')
        count = d.find(tree, rid='tv_header_subtitle')
        after = count.get('text') if count is not None else None
        assert before == after, (before, after)
        (folder / 'idle-window.txt').write_bytes(d.adb('shell', 'dumpsys', 'window', 'windows'))
        (folder / 'idle-surface.txt').write_bytes(d.adb('shell', 'dumpsys', 'SurfaceFlinger'))
        (folder / 'logcat.txt').write_bytes(d.adb('logcat', '-d', '-v', 'time', '-s',
                                               'ParticlePacing:I', 'ParticleDrift:I'))
    finally:
        if tracing:
            (folder / 'system.atrace').write_bytes(d.adb('shell', 'atrace', '--async_stop'))
        d.adb('shell', 'am', 'force-stop', d.PACKAGE)
        for path, value in original.items():
            observer.write_private(path, value)
    result = dict(rows=rows, windowMode=args.window_mode, style=args.style, amount=args.amount, size=args.size,
                  colorPrecision=args.color_precision,
                  rotationBefore=rotation, rotationAfter=d.rotation_state())
    (folder / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), 'utf-8')


if __name__ == '__main__':
    main()
