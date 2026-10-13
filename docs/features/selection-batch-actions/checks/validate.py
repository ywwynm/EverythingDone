import json, sys, math
from pathlib import Path

root=next(p for p in Path(__file__).resolve().parents if (p / 'gradlew.bat').is_file()) / 'tmp/selection-transition-20261013'
summary=[]
for arg in sys.argv[1:]:
    p=root/arg
    d=json.loads((p/'result.json').read_text(encoding='utf-8'))
    fs=d.get('frames',[]); failures=[]
    if not d.get('ok'): failures.append('business_failed')
    for item in d.get('beforeColors', []):
        if len(set(item['colors'])) == 1 and item['backgroundPixel'] != item['colors'][0]:
            failures.append('native_background_color_mismatch')
        if item['drawColorPixel'] != item['colors'][0]:
            failures.append('canvas_color_rewritten')
    exit_frames=[f for f in fs if f['mode']==0]
    if not exit_frames: failures.append('no_exit')
    if any(f['overlays'] for f in exit_frames): failures.append('exit_before_particle_release')
    moving=[f for f in exit_frames if f['chromeVisibility']==0 and f['chromeY']<-.5]
    if len(moving)<5: failures.append('toolbar_animation_not_visible')
    if any(f['menuSize']==0 for f in moving): failures.append('menu_cleared_early')
    if fs and any(f.get('darkIcons')!=fs[0].get('darkIcons') for f in exit_frames if f['chromeVisibility']==0):
        failures.append('system_icons_switched_early')
    if any(not f['removal'] and (f['chromeVisibility']==0 or f['itemAnimator']) for f in exit_frames):
        failures.append('input_unlocked_early')
    # 同一保留项的位置连续性；根据总路程和实际帧间隔区分快速补位与瞬移。
    tracks={}
    for f in fs:
        for item in f['items']:
            if item['p'] != 0 and item['id'] not in d['selectedIds'] and (item['id']>1000 or item['id']<0 and item['id']!=-9223372036854775808):
                tracks.setdefault(item['id'],[]).append((f['t'],item,f['mode']))
    jumps=[]; color_intermediates=[]
    for id,rows in tracks.items():
        for axis in ['x','y']:
            values=[row[1][axis] for row in rows]
            extent=max(values)-min(values)
            if extent<20: continue
            for old,new in zip(rows,rows[1:]):
                dt=(new[0]-old[0])/1000
                if dt<=0 or old[1]['view']!=new[1]['view']: continue
                delta=abs(new[1][axis]-old[1][axis])
                if delta>max(12,extent*(12*dt+.015)):
                    jumps.append([new[0],id,axis,round(delta,2),round(extent,2)])
        before=next((r[1] for r in rows if r[2]==2),None)
        if before:
            alphas={c['id']:c['alpha'] for c in before['children'] if 0<c['alpha']<.999}
            for child,initial in alphas.items():
                values={round(c['alpha'],4) for r in rows if r[2]==0 for c in r[1]['children'] if c['id']==child and initial+.01<c['alpha']<.99}
                if values: color_intermediates.append(len(values))
    if jumps: failures.append('position_discontinuity')
    initial_dim=any(0<c['alpha']<.99 for f in fs[:1] for i in f['items'] if i['p'] != 0 and i['id'] not in d['selectedIds'] for c in i['children'])
    if initial_dim and not color_intermediates: failures.append('selection_color_snapped')
    gaps=[b['t']-a['t'] for a,b in zip(moving,moving[1:])]
    row=dict(run=arg,pass_=not failures,failures=failures,toolbarFrames=len(moving),
        colorIntermediateFrames=max(color_intermediates,default=0),maxToolbarFrameGap=max(gaps,default=0),jumps=jumps[:20])
    summary.append(row)
    print(json.dumps(row,ensure_ascii=False))
(root/'validation-latest.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
