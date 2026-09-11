"""全体既有案例只推进 GPU 状态并测量，不画帧、不编码或重导标注视频。"""
from pathlib import Path
import argparse,json,sys,time
import numpy as np,moderngl
from PIL import Image
from scipy.spatial import cKDTree
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from unified_model import guidance
from expand_stalled_edges import visibility
OUT=HERE/'analysis/motion-field-extension'

def metrics(r,c):
    count=r.n;positions=np.empty((61,count,2),np.float32)
    for frame in range(61):
        r.seek(frame/60);positions[frame]=np.frombuffer(r.state.read(),np.float32).reshape(count,8)[:,:2]
    fg=np.asarray(Image.open(r.directory/'foreground.png').convert('RGBA'))
    xy=np.clip(r.base[:,:2].astype(int),[0,0],[r.cw-1,r.ch-1]);alpha=fg[xy[:,1],xy[:,0],3]/255
    visible=np.array([visibility(r.base,alpha,i/60)[0] for i in range(61)])
    step=np.linalg.norm(np.diff(positions,axis=0,prepend=positions[:1]),axis=2)/r.span
    life_path=np.cumsum(step,axis=0)
    final_idx=np.where(visible,np.arange(61)[:,None],-1).max(axis=0)
    considered=(final_idx>=0)&(alpha>.25)
    idx=np.maximum(final_idx,0);ids=np.arange(count)
    total=life_path[idx,ids]
    net=np.linalg.norm(positions[idx,ids]-r.base[:,:2],axis=1)/r.span
    # 排查整片材料的短途淡出，同时保留分位数，避免单一低阈值替代观感。
    grid=np.minimum((r.base[:,:2]/[r.cw,r.ch]*12).astype(int),11)
    binid=grid[:,1]*12+grid[:,0]
    bin_count=np.bincount(binid[considered],minlength=144)
    low=(total<.04)&considered
    bin_low=np.bincount(binid[low],minlength=144)
    fraction=bin_low/np.maximum(bin_count,1)
    low_bins=(bin_count>=32)&(fraction>.35)
    rolling=life_path[8:]-life_path[:-8]
    alive_pair=visible[8:]&visible[:-8]
    sluggish=(rolling<.02)&alive_pair
    row=dict(id=c['id'],scene=c['scene'],seed=c['seed'],angle=c['angle'],materials=int(considered.sum()),
        final_path_quantiles=np.quantile(total[considered],[.01,.05,.1,.25,.5,.75,.9]).tolist(),
        final_displacement_quantiles=np.quantile(net[considered],[.01,.05,.1,.25,.5,.75,.9]).tolist(),
        fraction_lifetime_path_below_4percent=float(np.mean(total[considered]<.04)),
        short_travel_source_regions=int(low_bins.sum()),source_region_fractions=fraction.reshape(12,12).tolist(),
        visible_133ms_windows=int(alive_pair.sum()),low_motion_133ms_windows=int(sluggish.sum()),
        low_motion_window_fraction=float(sluggish.sum()/max(alive_pair.sum(),1)),annotations=[])
    offset=np.array(r.meta['rect'][:2]);times=np.arange(61)/60
    for a in c.get('annotations',[]):
        p=np.asarray(a['points']);length=np.linalg.norm(np.diff(p,axis=0),axis=1).sum()
        if length<r.span*.12:continue
        points=np.concatenate([np.linspace(p0,p1,max(2,round(np.linalg.norm(p1-p0)/3))) for p0,p1 in zip(p[:-1],p[1:])])
        tree=cKDTree(points);i=int(np.argmin(abs(times-a['phase'])));j=max(0,i-8)
        live=visible[i]&visible[j];indices=np.flatnonzero(live)
        d,near=tree.query(positions[i,indices]+offset)
        selected=indices[d<r.span*.025]
        if len(selected):
            travel=(life_path[i,selected]-life_path[j,selected])
            q=positions[i,selected]-positions[j,selected]
            near=near[d<r.span*.025]
            tangent=points[np.minimum(near+1,len(points)-1)]-points[np.maximum(near-1,0)]
            normal=np.column_stack((tangent[:,1],-tangent[:,0]))/np.maximum(np.linalg.norm(tangent,axis=1)[:,None],1e-6)
            normal_move=np.abs(np.sum(q*normal,axis=1))/r.span
            row['annotations'].append(dict(mark=a['id'],kind=a['kind'],phase=a['phase'],length_over_span=float(length/r.span),
                visible_materials=len(selected),median_travel_133ms=float(np.median(travel)),
                median_normal_move_133ms=float(np.median(normal_move)),
                fraction_short_travel=float(np.mean(travel<.02))))
    return row

def main():
    p=argparse.ArgumentParser();p.add_argument('variant',choices=['original','extended']);p.add_argument('--ids',nargs='+');a=p.parse_args()
    out=OUT/f'measure-{a.variant}';out.mkdir(exist_ok=True,parents=True)
    cases=json.loads((HERE/'analysis/stalled-edges-expanded/review-manifest.json').read_text('utf-8'))['cases']
    metas=json.loads((HERE/'assets/scenes.json').read_text('utf-8'))
    cases+= [dict(id='G-'+m['name'],scene=m['name'],seed=m['seed'],angle=m['direction'],annotations=[]) for m in metas if not m.get('holdout')]
    if a.ids:cases=[c for c in cases if c['id'] in a.ids]
    if a.variant!='rejected' and '    // 观测场的静止背景不是固体边界。' in renderer.COMPUTE:
        start=renderer.COMPUTE.index('    // 观测场的静止背景不是固体边界。');end=renderer.COMPUTE.index('    float response=',start)
        renderer.COMPUTE=renderer.COMPUTE[:start]+renderer.COMPUTE[end:]
    if a.variant=='original':
        field=np.load(OUT/'observed-flow.npy')
        renderer.guidance=lambda:field
    if a.variant=='extended':
        field=np.fromfile(OUT/'extended-flow.f16','<f2').astype('float32').reshape(48,64,64,2)
        renderer.guidance=lambda:field
    ctx=moderngl.create_standalone_context(require=430);rows=[];started=time.monotonic()
    for number,c in enumerate(cases):
        path=out/(c['id']+'.json')
        r=renderer.Renderer(c['scene'],direction=c['angle'],seed=c['seed'],quality=1,ctx=ctx)
        row=metrics(r,c);r.close();rows.append(row);path.write_text(json.dumps(row,ensure_ascii=False),'utf-8')
        if number%8==0 or number==len(cases)-1:
            print(a.variant,number+1,'/',len(cases),c['id'],'短途淡出比例',round(row['fraction_lifetime_path_below_4percent'],4),'耗时',round(time.monotonic()-started,1),flush=True)
    ctx.release()
    summary=dict(variant=a.variant,cases=rows,case_count=len(rows),elapsed_seconds=time.monotonic()-started,
        max_short_travel_fraction=max(r['fraction_lifetime_path_below_4percent'] for r in rows),
        total_problem_regions=sum(r['short_travel_source_regions'] for r in rows),
        median_low_motion_fraction=float(np.median([r['low_motion_window_fraction'] for r in rows])))
    (out/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),'utf-8')

if __name__=='__main__':main()
