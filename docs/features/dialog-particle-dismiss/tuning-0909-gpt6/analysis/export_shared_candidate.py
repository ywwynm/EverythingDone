"""共同条件场桌面候选：原片／接手时／当前同屏，成片全部平铺保存。

只导出研究候选，不修改 Android、原有画廊或已认可的默认模型。
"""
from pathlib import Path
import argparse,dataclasses,hashlib,json,sys,time
import numpy as np,cv2,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from conditional_material import ConditionalRenderer,ConditionalRules
from shared_transport import SharedTransportRenderer,load_transport
from learned_release import load_weights,canonical_sites
from dissolve_v2 import Site,V2Renderer,reference_inputs
from v2_inputs import OBSERVED_SITES
from export_v2_suite import Truth
from match_compare import Comparison
import export_videos as video

SCENES=['ironman','thanos','kobe','spiderman-original','ironman-up-reference']+[f'reference-0926-{i}' for i in range(1,8)]
OUT=HERE/'videos';CACHE=HERE/'analysis/match-cache/shared-candidate'
FONT=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
SMALL=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15)
BG=(14,20,30)


def meta(name):return json.loads((HERE/'assets'/name/'scene.json').read_text('utf-8'))


def signature():
    h=hashlib.sha256()
    for n in ['renderer.py','pressure_grid.py','unified_model.py','coupled_transport.py','learned_release.py','conditional_material.py','shared_transport.py','conditional-release-weights.json','conditional-material-rules.json','conditional-transport-weights.json','dissolve_v2.py','v2_inputs.py','v2_inputs.json','analysis/export_shared_candidate.py']:h.update((HERE/n).read_bytes())
    for n in ['surface_transfer.py','surface-transfer-rules.json','particle_coverage.py','particle-coverage-rules.json','particle_cohorts.py','particle-cohort-weights.json','surface_coherence.py','surface-coherence-weights.json','particle_fission.py','scatter_material.py','particle-scatter-weights.json']:
        if (HERE/n).is_file():h.update((HERE/n).read_bytes())
    if json.loads((HERE/'conditional-transport-weights.json').read_text('utf-8')).get('coupling') is not None:
        h.update((HERE/'coupling_transport.py').read_bytes())
    from unified_model import SHARED
    for name in ['rules.properties','common-release.f32','common-flow.f16','flow-confidence.u8']:h.update((SHARED/name).read_bytes())
    for name in SCENES:
        for file in ['scene.json','foreground.png','background.png']:
            path=HERE/'assets'/name/file
            if path.is_file():h.update(path.read_bytes())
    return h.hexdigest()


def thumbnail(f,width=430,height=800):
    a=Image.fromarray(np.asarray(f));a.thumbnail((width,height),Image.Resampling.LANCZOS)
    return a


def compose(arrays,titles,phase,rate,subtitle):
    w=440;h=820;cols=len(arrays)
    canvas=Image.new('RGB',(cols*w,920),BG);draw=ImageDraw.Draw(canvas)
    for j,(arr,title) in enumerate(zip(arrays,titles)):
        draw.text((j*w+12,9),title,font=FONT,fill=(235,242,250))
        im=thumbnail(arr,w-10,h);canvas.paste(im,(j*w+(w-im.width)//2,47))
    draw.line((0,877,round(canvas.width*phase),877),fill=(102,212,200),width=3)
    draw.text((12,889),f'{subtitle} · 进度 {phase:.3f} · {rate:g} 倍速',font=SMALL,fill=(175,196,215))
    return np.asarray(canvas)


def matrix(arrays,labels,p,rate,title):
    cols=4;w=310;h=600;rows=(len(arrays)+3)//4
    canvas=Image.new('RGB',(cols*w,70+rows*h+40),BG);d=ImageDraw.Draw(canvas)
    d.text((12,12),f'{title} · 同一套规则 · {rate:g} 倍速 · 进度 {p:.3f}',font=FONT,fill='white')
    for j,(arr,label) in enumerate(zip(arrays,labels)):
        x=j%cols*w;y=70+j//cols*h;d.text((x+9,y+3),label,font=SMALL,fill=(185,206,223))
        im=thumbnail(arr,300,h-35);canvas.paste(im,(x+(w-im.width)//2,y+32))
    return np.asarray(canvas)


def detail(arrays,titles,phase,rate,m):
    x,y,x1,y1=m['rect'];fw,fh=m['frame'];pad=round(min(x1-x,y1-y)*.22)
    box=(max(0,x-pad),max(0,y-pad),min(fw,x1+pad),min(fh,y1+pad))
    w=box[2]-box[0];h=box[3]-box[1];w+=w%2;h+=h%2
    canvas=Image.new('RGB',(w*len(arrays),h+90),BG);draw=ImageDraw.Draw(canvas)
    for j,(f,title) in enumerate(zip(arrays,titles)):
        draw.text((j*w+12,8),title,font=FONT,fill='white')
        canvas.paste(Image.fromarray(f).crop(box),(j*w,40))
    draw.line((0,h+49,round(canvas.width*phase),h+49),fill=(102,212,200),width=3)
    draw.text((12,h+59),f'原尺寸局部 · 进度 {phase:.3f} · {rate:g} 倍速 · {m["title"]}',font=SMALL,fill=(175,196,215))
    return np.asarray(canvas)


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',default=SCENES);ap.add_argument('--extra',action='store_true');ap.add_argument('--details',action='store_true');ap.add_argument('--differences',action='store_true');ap.add_argument('--previous');a=ap.parse_args()
    CACHE.mkdir(parents=True,exist_ok=True);OUT.mkdir(exist_ok=True)
    rules=ConditionalRules(**json.loads((HERE/'conditional-material-rules.json').read_text('utf-8'))['rules'])
    weights=load_weights(HERE/'conditional-release-weights.json');identity=signature();video.VERSION='共同条件场 · 桌面候选';video.code_hash=lambda:identity
    transport=load_transport(HERE/'conditional-transport-weights.json')
    cls=SharedTransportRenderer;previous_cls=SharedTransportRenderer;current_options={};previous_options=None;before_title='接手时的候选'
    if (HERE/'surface-transfer-rules.json').is_file():
        from surface_transfer import TransferRenderer,load_transfer
        cls=TransferRenderer;current_options['transfer']=load_transfer(HERE/'surface-transfer-rules.json')
    if (HERE/'particle-coverage-rules.json').is_file():
        from particle_coverage import CoverageRenderer,load_coverage
        cls=CoverageRenderer;current_options['coverage']=load_coverage(HERE/'particle-coverage-rules.json')
    if (HERE/'particle-cohort-weights.json').is_file():
        from particle_cohorts import CohortRenderer,load_cohorts
        cls=CohortRenderer;current_options['cohorts']=load_cohorts(HERE/'particle-cohort-weights.json')
    if (HERE/'surface-coherence-weights.json').is_file():
        from surface_coherence import CoherenceRenderer,load_coherence
        cls=CoherenceRenderer;current_options['coherence']=load_coherence(HERE/'surface-coherence-weights.json')
    if (HERE/'particle-scatter-weights.json').is_file():
        from scatter_material import ScatterRenderer,load_scatter
        cls=ScatterRenderer;current_options['scatter']=load_scatter(HERE/'particle-scatter-weights.json')
    if a.previous:
        root=Path(a.previous);before_title='本轮调整前'
        previous_options=dict(weights=load_weights(root/'conditional-release-weights.json'),transport_weights=load_transport(root/'conditional-transport-weights.json'),rules=ConditionalRules(**json.loads((root/'conditional-material-rules.json').read_text('utf-8'))['rules']))
        if (root/'surface-transfer-rules.json').is_file():
            from surface_transfer import TransferRenderer,load_transfer
            previous_cls=TransferRenderer;previous_options['transfer']=load_transfer(root/'surface-transfer-rules.json')
        if (root/'particle-coverage-rules.json').is_file():
            from particle_coverage import CoverageRenderer,load_coverage
            previous_cls=CoverageRenderer;previous_options['coverage']=load_coverage(root/'particle-coverage-rules.json')
        if (root/'particle-cohort-weights.json').is_file():
            from particle_cohorts import CohortRenderer,load_cohorts
            previous_cls=CohortRenderer;previous_options['cohorts']=load_cohorts(root/'particle-cohort-weights.json')
        if (root/'surface-coherence-weights.json').is_file():
            from surface_coherence import CoherenceRenderer,load_coherence
            previous_cls=CoherenceRenderer;previous_options['coherence']=load_coherence(root/'surface-coherence-weights.json')
        if (root/'particle-scatter-weights.json').is_file():
            from scatter_material import ScatterRenderer,load_scatter
            previous_cls=ScatterRenderer;previous_options['scatter']=load_scatter(root/'particle-scatter-weights.json')
        h=hashlib.sha256(identity.encode())
        for n in ['conditional-release-weights.json','conditional-transport-weights.json','conditional-material-rules.json']:h.update((root/n).read_bytes())
        if (root/'surface-transfer-rules.json').is_file():h.update((root/'surface-transfer-rules.json').read_bytes())
        if (root/'particle-coverage-rules.json').is_file():h.update((root/'particle-coverage-rules.json').read_bytes())
        if (root/'particle-cohort-weights.json').is_file():h.update((root/'particle-cohort-weights.json').read_bytes())
        if (root/'surface-coherence-weights.json').is_file():h.update((root/'surface-coherence-weights.json').read_bytes())
        if (root/'particle-scatter-weights.json').is_file():h.update((root/'particle-scatter-weights.json').read_bytes())
        identity=h.hexdigest()
    ctx=moderngl.create_standalone_context(require=430);items=[];stats={}
    def page():
        manifest=dict(hash=identity,created=time.strftime('%Y-%m-%d %H:%M:%S'),status='桌面研究候选，尚未通过全部参考验收',before_title=before_title,previous=a.previous,transfer=dataclasses.asdict(current_options['transfer']) if current_options else None,rules=dataclasses.asdict(rules),videos=items,metrics=stats)
        manifest['coverage']=dataclasses.asdict(current_options['coverage']) if 'coverage' in current_options else None
        manifest['cohorts']='particle-cohort-weights.json' if 'cohorts' in current_options else None
        manifest['coherence']='surface-coherence-weights.json' if 'coherence' in current_options else None
        manifest['scatter']='particle-scatter-weights.json' if 'scatter' in current_options else None
        (OUT/'shared-field-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),'utf-8')
        html=(OUT/'new-references.html').read_text('utf-8').replace('new-references-manifest.json','shared-field-manifest.json')
        html=html.replace('新增参考 · 共同模型桌面对照','共同条件场 · 原片对照')
        html=html.replace('七段新参考与旧钢铁侠、科比、灭霸、蜘蛛侠放在一起检查。先看完整运动，再看逐帧像素差；旧视频仍以原始华为录像为参照。','左：华为原片；中：接手时的 Claude 候选；右：本轮共同模型。先看完整运动，再用进度按钮逐帧比较。')
        html=html.replace('当前是桌面候选。新参考的前沿扩张、颗粒覆盖及中后段轮廓仍有明显差距，尚未达到逐像素一致。','十二段共用同一套运动与材质规则。本轮修正引导与附加卷动的比例，减少内部过度侧摆；原片、调整前与当前模型同时比较。局部形状、亮弧和灰细颗粒仍有可见差距，尚未通过最终视觉验收。')
        html=html.replace('独立画面序号时间','独立画面顺序')
        html=html.replace('无法观测的背景和片尾不计像素准确率。','无法观测的背景和片尾不计误差；按独立画面顺序对齐进度，原文件可能已经慢放。')
        html=html.replace("animation:'完整动画'","detail:'原尺寸颗粒对照',animation:'完整动画'")
        html=html.replace("difference:'逐帧 RGB 差'","difference:'结构差异图（固定色阶）'")
        html=html.replace("v.src=selected.file;v.poster=selected.poster;","v.src=selected.file+'?hash='+mHash;v.poster=selected.poster+'?hash='+mHash;")
        html=html.replace('let data,rate=.5,selected;','let data,rate=.5,selected,mHash;')
        html=html.replace('data=m.videos;','mHash=m.hash;data=m.videos;')
        html=html.replace("+' 倍速';", "+' 倍速 · '+(selected.note||'');")
        html=html.replace(' · <a href="shared-field-manifest.json">',' · <a href="shared-field-report.html">逐段测量与限制</a> · <a href="shared-field-manifest.json">')
        if previous_options:html=html.replace('中：接手时的 Claude 候选','中：本轮调整前的共同模型')
        (OUT/'shared-field.html').write_text(html,'utf-8')
    def encode(name,kind,title,make,extra=None):
        for rate in [1.,.5]:
            file=OUT/f'shared-field-{name}-{kind}-{rate:g}x.mp4'
            row=video.encode(file,video.PRE+1+video.POST,rate,lambda t:make(float(np.clip(t-video.PRE,0,1)),rate))
            note=('原片片尾不完整，遮挡背景为重建；不计全背景像素误差。' if name in ['reference-0926-2','reference-0926-3'] else '按独立画面顺序对齐；原片的触点和随机种子未知。')
            row.update(scene=name,title=title,kind=kind,inputs=extra or {},note=note)
            items.append(row);page()
    def render_frames(r):
        frames=[]
        for i in range(121):frames.append(r.render(i/120))
        r.close();return frames
    for name in a.scenes:
        m=meta(name);truth=Truth(name);sites=[Site(*s) for s in OBSERVED_SITES[name]]
        r=cls(name,sites=sites,weights=weights,transport_weights=transport,rules=rules,ctx=ctx,quality=2,**current_options);frames=render_frames(r)
        previous=previous_cls(name,ctx=ctx,quality=2,sites=sites,**previous_options) if previous_options else V2Renderer(name,ctx=ctx,quality=2,**reference_inputs(name))
        old=render_frames(previous)
        comp=Comparison(name);mask=comp.scene.roi
        if mask.any():
            rows=[]
            for i in comp.index:
                p=float(comp.ref.progress[i]);reference=np.asarray(comp.ref.frames[i]).astype('float32');k=round(np.clip(p,0,1)*120)
                errors=[]
                for f in [old[k],frames[k]]:
                    errors.append({f'rgb_{s}':float(np.abs(cv2.GaussianBlur(f.astype('float32'),(0,0),s)-cv2.GaussianBlur(reference,(0,0),s)).mean(2)[mask].mean()) for s in [1,4,12]})
                    grain=lambda img:cv2.GaussianBlur(np.abs(img.astype('float32')-cv2.GaussianBlur(img.astype('float32'),(0,0),1.5)),(0,0),8.)
                    errors[-1]['grain']=float(np.abs(grain(f)-grain(reference))[mask].mean())
                rows.append(dict(phase=p,before=errors[0],candidate=errors[1]))
            stats[name]={which:{key:float(np.mean([r[which][key] for r in rows])) for key in ['rgb_1','rgb_4','rgb_12','grain']} for which in ['before','candidate']}
            stats[name]['frames']=len(rows);(CACHE/f'{name}-metrics.json').write_text(json.dumps(rows),'utf-8')
        encode(name,'comparison',m['title'],lambda p,rate:compose([truth.at(p),old[round(p*120)],frames[round(p*120)]],['华为原片',before_title,'本轮共同模型'],p,rate,m['title']),dict(direction=m['direction'],seed=m['seed'],sites=[dataclasses.asdict(s) for s in sites]))
        if a.details:
            encode(name,'detail',m['title'],lambda p,rate:detail([truth.at(p),old[round(p*120)],frames[round(p*120)]],['华为原片',before_title,'本轮共同模型'],p,rate,m))
        if a.differences and mask.any():
            def difference_frame(p,rate):
                ref=truth.at(p);f=frames[round(p*120)]
                delta=np.abs(cv2.GaussianBlur(ref.astype('float32'),(0,0),4)-cv2.GaussianBlur(f.astype('float32'),(0,0),4)).mean(2)
                heat=cv2.applyColorMap(np.uint8(np.clip(delta/40.,0,1)*255),cv2.COLORMAP_INFERNO)[:,:,::-1].copy();heat[~mask]=(48,52,60)
                if p>truth.valid:heat[:]=(48,52,60)
                return detail([ref,f,heat],['华为原片','本轮共同模型','结构差 · 黑 0 / 黄 40+；灰色不评分'],p,rate,m)
            encode(name,'difference',m['title'],difference_frame)
        for p in [.25,.40,.56,.72]:
            Image.fromarray(compose([truth.at(p),old[round(p*120)],frames[round(p*120)]],['华为原片',before_title,'本轮共同模型'],p,1,m['title'])).save(CACHE/f'{name}-{p:.2f}.jpg',quality=94)
        print('已导出原片对照',name,stats.get(name,{}),flush=True)
        del frames,old
    if a.extra:
        for name in ['ironman','attachment','color']:
            m=meta(name);arrays=[]
            # 一般播放采用标准布局与源模型原有种子变化，任何素材均可使用相同输入。
            for direction,label in video.DIRECTIONS:
                r=cls(name,direction=direction,weights=weights,transport_weights=transport,rules=rules,ctx=ctx,quality=2,**current_options)
                arrays.append([np.asarray(thumbnail(f,300,565)) for f in render_frames(r)])
            encode(name,'directions',m['title'],lambda p,rate:matrix([f[round(p*120)] for f in arrays],[f'{d}° · {s}' for d,s in video.DIRECTIONS],p,rate,m['title']))
            del arrays
        for name in ['attachment','color','holdout-compact-dialog']:
            m=meta(name);arrays=[];cases=[('标准边缘布局',122,None),('内部与边缘起点',47,[Site(*s) for s in OBSERVED_SITES['reference-0926-5']]),('上方单起点',90,[Site(.5,0,0)]),('两侧错时起点',135,[Site(0,.35,0),Site(1,.7,.10)])]
            for label,direction,sites in cases:
                r=cls(name,direction=direction,sites=sites,weights=weights,transport_weights=transport,rules=rules,ctx=ctx,quality=2,**current_options)
                arrays.append([np.asarray(thumbnail(f,300,565)) for f in render_frames(r)])
            encode(name,'transfer',m['title'],lambda p,rate:matrix([f[round(p*120)] for f in arrays],[q[0] for q in cases],p,rate,m['title']))
            Image.fromarray(matrix([f[60] for f in arrays],[q[0] for q in cases],.5,1,m['title'])).save(CACHE/f'{name}-transfer.jpg',quality=94)
            del arrays
    ctx.release();page();print('完成',len(items),'个视频',identity,flush=True)


if __name__=='__main__':main()
