"""接手候选的三栏成片：华为原片／接手时固定版本／新候选，各 1 倍与 0.5 倍。

- 固定版本使用当前默认配置，导出前核对八份配置与 `archive/handoff-UIJzSD-baseline/` 逐字节一致；
- 候选用 `handoff_variants` 的变体描述（例如 `scatter-density-min=0,edge-trailing=1`）；
- 文件名以 `handoff-<标签>-` 开头，另写清单与审阅页，不覆盖默认画廊、默认配置或 Android 资源；
- `--codec` 再加一栏「新候选 · 录像口径」：候选帧按原片同一片段的码率编码后取回（recording_codec），
  与原片同一录像条件，便于区分模型差别与转码造成的差别（原片颗粒的软边、偏长形状有相当部分来自转码）。
"""
from pathlib import Path
import argparse, hashlib, json, sys, time
import numpy as np

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from export_shared_candidate import compose, detail, meta, SCENES
from export_v2_suite import Truth
from handoff_review import render_all, CONFIG_FILES
from recording_codec import coded
from match_refs import RefFrames
import export_videos as video

OUT = HERE / 'videos'
BASELINE = HERE / 'archive/handoff-UIJzSD-baseline/simulator'


def standalone(html, manifest_name, manifest):
    """把清单写进页面：浏览器直接打开本地文件（file://）时会拦截 fetch，页面读不到清单就没有视频。
    本地打开时也不给视频地址加查询串（部分浏览器对带查询串的 file:// 地址不加载）。"""
    old = f"fetch('{manifest_name}',{{cache:'no-store'}}).then(r=>r.json()).then(m=>{{"
    assert old in html, '页面模板里找不到读取清单的语句'
    html = html.replace(old, 'Promise.resolve(MANIFEST).then(m=>{', 1)
    html = html.replace('<script>', '<script>\nconst MANIFEST=' + json.dumps(manifest, ensure_ascii=False) + ';', 1)
    old = "v.src=selected.file+'?hash='+mHash;v.poster=selected.poster+'?hash='+mHash;"
    assert old in html, '页面模板里找不到视频地址语句'
    return html.replace(old, "const qs=location.protocol==='file:'?'':'?hash='+mHash;v.src=selected.file+qs;v.poster=selected.poster+qs;", 1)


def check_baseline():
    for n in CONFIG_FILES:
        a = (HERE / n).read_bytes(); b = (BASELINE / n).read_bytes()
        if a != b: raise SystemExit(f'默认配置已不同于接手快照：{n}；固定版本列需改用快照目录渲染')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--label', required=True)
    ap.add_argument('--spec', required=True)
    ap.add_argument('--title', default='接手候选')
    ap.add_argument('--scenes', nargs='+', default=SCENES)
    ap.add_argument('--details', action='store_true')
    ap.add_argument('--codec', action='store_true', help='加一栏按原片码率编码后的候选')
    ap.add_argument('--root', default='', help='候选使用的配置目录（相对 tuning-0909-gpt6，缺省为根目录的默认配置）')
    ap.add_argument('--blur', action='store_true', help='录像口径一栏在编码前先加各段静态边缘测得的录像模糊（recording_codec.capture_blur）')
    ap.add_argument('--base-root', default='', help='中间一栏改用这个配置目录与 --base-spec 的变体（缺省为接手时固定版本）')
    ap.add_argument('--base-spec', default='')
    ap.add_argument('--base-title', default='接手时固定版本')
    a = ap.parse_args()
    check_baseline()
    stamp = hashlib.sha256()
    for n in ['analysis/handoff_variants.py', 'edge_generalization.py', 'analysis/export_handoff_candidate.py']:
        stamp.update((HERE / n).read_bytes())
    stamp.update(a.spec.encode())
    if a.base_root or a.base_spec: stamp.update(f'base:{a.base_root}::{a.base_spec}'.encode())
    root = HERE / a.root if a.root else HERE
    if a.root:
        for n in CONFIG_FILES:
            if (root / n).is_file(): stamp.update((root / n).read_bytes())
    if a.codec:
        stamp.update((HERE / 'analysis/recording_codec.py').read_bytes()); stamp.update(b'codec-column')
    if a.blur: stamp.update(b'capture-blur')
    identity = stamp.hexdigest()
    video.VERSION = f'{a.title} · {a.spec}'; video.code_hash = lambda: identity
    items = []
    manifest_path = OUT / f'handoff-{a.label}-manifest.json'

    def page():
        manifest = dict(hash=identity, created=time.strftime('%Y-%m-%d %H:%M:%S'), spec=a.spec, title=a.title, root=a.root or '.',
                        baseline=(f'{a.base_title}（{a.base_root or "."}::{a.base_spec}）' if (a.base_root or a.base_spec)
                                  else '接手时固定版本（archive/handoff-UIJzSD-baseline，指纹 67cbbf20…）'), videos=items)
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), 'utf-8')
        html = (OUT / 'shared-field.html').read_text('utf-8').replace('shared-field-manifest.json', manifest_path.name)
        html = html.replace('共同条件场 · 原片对照', f'{a.title} · 原片对照')
        html = html.replace('左：华为原片；中：本轮调整前的共同模型；右：本轮共同模型。',
                            '左起：华为原片；接手时固定版本；新候选；新候选按原片码率编码后的画面（与原片同一录像条件）。' if a.codec
                            else f'左：华为原片；中：{a.base_title}；右：新候选。')
        html = html.replace(html[html.index('<p id="warning">'):html.index('</p>', html.index('<p id="warning">')) + 4],
                            f'<p id="warning">研究候选（{a.spec}），尚未替换默认模型；原版钢铁侠的卷曲、翻折与沿边流动须单独核对。</p>')
        (OUT / f'handoff-{a.label}.html').write_text(standalone(html, manifest_path.name, manifest), 'utf-8')

    for name in a.scenes:
        m = meta(name); truth = Truth(name)
        base = render_all(name, HERE / a.base_root, a.base_title, spec=a.base_spec) if (a.base_root or a.base_spec) else render_all(name, HERE, 'current')
        cand = render_all(name, root, a.label, spec=a.spec)
        titles = ['华为原片', a.base_title, a.title]
        cols = lambda p: [truth.at(p), np.asarray(base[round(p * 120)]), np.asarray(cand[round(p * 120)])]
        if a.codec and truth.frames is not None:
            cf = coded(name, a.label, a.spec, root=root, verbose=False, blur=a.blur)[0]; refs = RefFrames(name)
            titles = titles + [a.title + (' · 录像口径（含录像模糊）' if a.blur else ' · 录像口径')]
            plain = cols
            cols = lambda p, plain=plain, cf=cf, refs=refs: plain(p) + [np.asarray(cf[refs.nearest(p)[0]]) if p <= truth.valid else plain(p)[2]]
        for kind in ['comparison'] + (['detail'] if a.details else []):
            for rate in [1., .5]:
                file = OUT / f'handoff-{a.label}-{name}-{kind}-{rate:g}x.mp4'
                if kind == 'comparison':
                    make = lambda p, rate: compose(cols(p), titles, p, rate, m['title'])
                else:
                    make = lambda p, rate: detail(cols(p), titles, p, rate, m)
                row = video.encode(file, video.PRE + 1 + video.POST, rate, lambda t, make=make, rate=rate: make(float(np.clip(t - video.PRE, 0, 1)), rate))
                row.update(scene=name, title=m['title'], kind=kind, note='按独立画面顺序对齐；原片的触点和随机种子未知。')
                items.append(row); page()
        print('完成', name, flush=True)
    page(); print('全部完成', len(items), '个视频', identity, flush=True)


if __name__ == '__main__':
    main()
