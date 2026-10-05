"""致密带（亮带、成股的流）的位置比较：原片与模型同口径。

块级误差对“有没有一条弧形亮带、带在哪里”不敏感；这里把离开原位的颗粒占用做两级平滑，
取局部密度明显高于周围（4 像素尺度密度 > 1.6 × 16 像素尺度密度，且本身足够密）的像素作为“致密带”，
再比较原片与模型的致密带集合：双向平均最近距离（短边为单位，缺带与多带都计入），以及覆盖率。
只用于离线评估与目视，不进入运行模型。

用法：python analysis/structure_bands.py --label <标签> [--rules <json>] [--scenes ...] [--phases .45,.55,.65]
"""
from pathlib import Path
import argparse,dataclasses,json,math,sys
import numpy as np,cv2

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE));sys.path.insert(0,str(HERE/'analysis'))
OUT=HERE/'analysis/match-cache/structure-bands'
SCENES=['ironman','thanos','kobe','spiderman-original','reference-0926-1','reference-0926-4','reference-0926-5',
        'reference-0926-6','reference-0926-7','ironman-up-reference']


def bands(scene,frame):
    """致密带掩码与颗粒占用（都在 roi 内）。"""
    o=scene.observe(frame);m=((o['occ']&~o['surface'])&scene.roi).astype(np.float32)
    s=scene.span/470.            # 尺度随控件大小：短边约 470 像素时 σ≈4／16 像素
    d4=cv2.GaussianBlur(m,(0,0),max(1.5,4*s));d16=cv2.GaussianBlur(m,(0,0),max(6.,16*s))
    band=(d4>1.6*d16)&(d4>.35)&scene.roi
    band=cv2.morphologyEx(band.astype(np.uint8),cv2.MORPH_OPEN,np.ones((3,3),np.uint8))
    # 只保留成带的连通块：面积至少约 (0.03 短边)²、且长宽比明显（主轴长度 ≥ 2.5×次轴），零星斑点不算带。
    n,lab,st,_=cv2.connectedComponentsWithStats(band,connectivity=8);keep=np.zeros(band.shape,bool)
    min_area=(.03*scene.span)**2
    for k in range(1,n):
        if st[k,cv2.CC_STAT_AREA]<min_area:continue
        ys,xs=np.nonzero(lab==k);c=np.cov(np.stack([xs,ys]).astype(np.float64))
        ev=np.linalg.eigvalsh(c);ratio=math.sqrt(max(ev[1],1e-9)/max(ev[0],1e-9))
        if ratio>=2.5:keep|=lab==k
    return keep,m>0


def chamfer(a,b,span):
    """a、b 为布尔掩码；返回 (a 到 b 的平均最近距离, b 到 a 的, 以短边为单位)。空集按 0.5 短边计。"""
    def mean_dist(src,dst):
        if not src.any():return 0.
        if not dst.any():return .5
        dt=cv2.distanceTransform((~dst).astype(np.uint8),cv2.DIST_L2,5)
        return float(np.minimum(dt[src]/span,.5).mean())
    return mean_dist(a,b),mean_dist(b,a)


def run(rules,label,scenes,phases,sites_file=None,old=False):
    import moderngl
    from match_compare import Comparison
    from dissolve_v2 import V2Renderer,reference_inputs
    from release_integrity import site_list
    from PIL import Image,ImageDraw,ImageFont
    ctx=moderngl.create_standalone_context(require=430);F=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',14)
    trial=json.loads(Path(sites_file).read_text('utf-8')).get('inputs',{}) if sites_file else {}
    report={};rows=[]
    for name in scenes:
        C=Comparison(name);s=C.scene;q=reference_inputs(name)
        if name in trial:q=dict(q,sites=site_list(trial[name]))
        if old:
            from renderer import Renderer        # 此前认可的旧模型（默认输入）
            r=Renderer(name,ctx=ctx,quality=2)
        else:r=V2Renderer(name,rules=rules,ctx=ctx,quality=2,**q)
        x,y,x1,y1=s.rect;mg=round(s.span*.45);box=(max(0,x-mg),max(0,y-mg),min(s.W,x1+mg),min(s.H,y1+mg))
        valid=s.meta['reference'].get('valid_progress',1.);tiles=[];vals=[]
        for p in phases:
            if p>valid:continue
            i,f=C.ref.nearest(p);pp=float(C.ref.progress[i])
            hb,hocc=bands(s,np.asarray(f));g=cv2.GaussianBlur(r.render(pp),(0,0),.65);mb,mocc=bands(s,g)
            miss,extra=chamfer(hb,mb,s.span)          # 原片的带离模型多远（缺带）、模型的带离原片多远（多带）
            vals.append(dict(p=pp,miss=miss,extra=extra,huawei=float(hb.sum())/s.card.sum(),model=float(mb.sum())/s.card.sum()))
            vis=np.full(hb.shape+(3,),20,np.uint8);vis[hocc]=[70,70,80];vis[mocc]=np.maximum(vis[mocc],[40,70,70])
            vis[hb&~mb]=[255,80,60];vis[mb&~hb]=[60,220,255];vis[hb&mb]=[255,255,255]
            c=vis[box[1]:box[3],box[0]:box[2]];im=Image.fromarray(c).resize((200,round(200*c.shape[0]/c.shape[1])),Image.Resampling.NEAREST)
            ImageDraw.Draw(im).text((3,2),f'{pp:.2f} 缺{miss:.2f} 多{extra:.2f}',font=F,fill=(255,230,120));tiles.append(im)
        r.close();report[name]=vals
        if tiles:
            row=Image.new('RGB',(sum(t.width+3 for t in tiles)+110,max(t.height for t in tiles)),(14,20,30))
            ImageDraw.Draw(row).text((2,2),name[-13:],font=F,fill='white');xo=110
            for t in tiles:row.paste(t,(xo,0));xo+=t.width+3
            rows.append(row)
        m=np.mean([v['miss']+v['extra'] for v in vals]) if vals else float('nan')
        print(f'{name:22s} 致密带双向距离 {m:.3f}  '+'  '.join(f"{v['p']:.2f}:{v['miss']:.2f}/{v['extra']:.2f}" for v in vals),flush=True)
    OUT.mkdir(parents=True,exist_ok=True)
    W=max(r.width for r in rows);sheet=Image.new('RGB',(W,sum(r.height+4 for r in rows)),(14,20,30));yo=0
    for r in rows:sheet.paste(r,(0,yo));yo+=r.height+4
    sheet.save(OUT/f'{label}.jpg',quality=90)
    (OUT/f'{label}.json').write_text(json.dumps(report,indent=1),'utf-8')
    tot=float(np.mean([v['miss']+v['extra'] for n in report for v in report[n]]))
    print('平均',round(tot,4),OUT/f'{label}.jpg')
    return report


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--label',required=True);ap.add_argument('--rules');ap.add_argument('--over',default='{}')
    ap.add_argument('--sites');ap.add_argument('--scenes',nargs='+',default=SCENES);ap.add_argument('--phases',default='.4,.5,.6,.7')
    ap.add_argument('--old',action='store_true',help='改为评估此前认可的旧模型')
    a=ap.parse_args()
    from release_integrity import load_rules
    rules=dataclasses.replace(load_rules(a.rules),**json.loads(a.over))
    run(rules,a.label,a.scenes,[float(v) for v in a.phases.split(',')],a.sites,a.old)


if __name__=='__main__':main()
