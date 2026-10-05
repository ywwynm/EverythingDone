"""按颜色追踪材料去向：把源控件的颜色聚成几类，逐帧统计离开原位的颗粒中每类颜色的质心与范围。

颗粒没有身份，逐颗追踪与 PIV 在颗粒云上都不可靠（见 huawei-match 文档第 3 节）；但多色素材的每一类颜色
来自控件上固定的一片区域，它们的质心轨迹就是“那片材料去了哪里”。原片与模型用同一套分类与阈值。
与背景颜色接近的类别不参与统计。只用于离线分析，不进入运行模型。

用法：python analysis/material_tracks.py --scenes kobe ironman [--rules <json>] [--label <标签>]
"""
from pathlib import Path
import argparse,json,math,sys
import numpy as np,cv2

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE));sys.path.insert(0,str(HERE/'analysis'))
OUT=HERE/'analysis/match-cache/material-tracks'


def lab(rgb):
    a=np.asarray(rgb,np.float32).reshape(-1,1,3)/255.
    return cv2.cvtColor(a,cv2.COLOR_RGB2Lab).reshape(-1,3)


class Palette:
    """源控件颜色聚类（k-means，Lab），去掉与背景接近的类别。"""
    def __init__(self,scene,k=7,seed=3):
        s=scene;m=s.card&s.roi;px=s.source[m][::7]
        L=lab(px);crit=(cv2.TERM_CRITERIA_EPS+cv2.TERM_CRITERIA_MAX_ITER,60,.2)
        cv2.setRNGSeed(seed)
        _,lab_ids,centers=cv2.kmeans(L.astype(np.float32),k,None,crit,4,cv2.KMEANS_PP_CENTERS)
        share=np.bincount(lab_ids.ravel(),minlength=k)/len(lab_ids)
        bgL=lab(s.bg[m][::7]);bg_med=np.median(bgL,0)
        # 每类在控件上的原位置（质心），作为轨迹起点。
        ys,xs=np.nonzero(m);ys,xs=ys[::7],xs[::7]
        self.centers=[];self.home=[];self.rgb=[]
        for c in range(k):
            chroma_gap=np.hypot(*(centers[c,1:]-bg_med[1:]));light_gap=abs(centers[c,0]-bg_med[0])
            if share[c]<.04 or (chroma_gap<14 and light_gap<18):continue
            sel=lab_ids.ravel()==c
            self.centers.append(centers[c]);self.home.append((float(xs[sel].mean()),float(ys[sel].mean())))
            self.rgb.append(tuple(int(v) for v in px[sel].mean(0)))
        self.centers=np.array(self.centers,np.float32)

    def assign(self,rgb):
        """颜色分到最近的类别：色度为主、亮度放宽（颗粒会被增亮）；离所有类都远或两类难分的像素不计。"""
        L=lab(rgb);d=np.hypot(L[:,None,1]-self.centers[None,:,1],L[:,None,2]-self.centers[None,:,2])+.35*np.abs(L[:,None,0]-self.centers[None,:,0])
        order=np.argsort(d,1);best=order[:,0];d0=d[np.arange(len(d)),best]
        d1=d[np.arange(len(d)),order[:,1]] if d.shape[1]>1 else d0+99
        ok=(d0<16)&(d1-d0>4)
        return np.where(ok,best,-1)


def track(scene,palette,frame):
    """一帧中每类颜色的颗粒像素：数量、质心、横纵范围（短边为单位，相对控件中心）。"""
    s=scene;o=s.observe(frame);m=(o['occ']&~o['surface'])&s.roi
    ys,xs=np.nonzero(m)
    if len(xs)==0:return [None]*len(palette.centers)
    ids=palette.assign(frame[ys,xs]);x,y,x1,y1=s.rect;cx,cy=(x+x1)/2,(y+y1)/2
    out=[]
    for c in range(len(palette.centers)):
        sel=ids==c
        if sel.sum()<150:out.append(None);continue
        px=(xs[sel]-cx)/s.span;py=(ys[sel]-cy)/s.span
        out.append(dict(n=int(sel.sum()),x=float(px.mean()),y=float(py.mean()),
                        x10=float(np.percentile(px,10)),x90=float(np.percentile(px,90)),y10=float(np.percentile(py,10)),y90=float(np.percentile(py,90))))
    return out


def run(name,rules,ctx,phases):
    from match_compare import Comparison
    from dissolve_v2 import V2Renderer,reference_inputs
    C=Comparison(name);s=C.scene;pal=Palette(s)
    r=V2Renderer(name,rules=rules,ctx=ctx,quality=2,**reference_inputs(name))
    rows=[]
    for p in phases:
        if p>s.meta['reference'].get('valid_progress',1):continue
        i,f=C.ref.nearest(p);pp=float(C.ref.progress[i])
        g=cv2.GaussianBlur(r.render(pp),(0,0),.65)
        rows.append(dict(p=pp,huawei=track(s,pal,np.asarray(f)),model=track(s,pal,g)))
    r.close()
    x,y,x1,y1=s.rect;cx,cy=(x+x1)/2,(y+y1)/2
    home=[((hx-cx)/s.span,(hy-cy)/s.span) for hx,hy in pal.home]
    thumb=s.source[y:y1,x:x1].astype(np.uint8)
    return dict(scene=name,colors=pal.rgb,home=home,rows=rows,direction=reference_inputs(name)['direction'],thumb=thumb)


def draw(res,path):
    """原片（实线）与模型（虚点线）的质心轨迹叠在同一张图上，底图为源控件的淡化缩略。"""
    from PIL import Image,ImageDraw,ImageFont
    F=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',16);W,H=760,700;S=260.
    im=Image.new('RGB',(W,H),(20,24,32));d=ImageDraw.Draw(im)
    cx,cy=W/2,H/2+10
    src=res.get('thumb')
    if src is not None:
        th=Image.fromarray(src).resize((round(S*src.shape[1]/min(src.shape[:2])),round(S*src.shape[0]/min(src.shape[:2]))))
        th=Image.blend(Image.new('RGB',th.size,(20,24,32)),th,.35);im.paste(th,(round(cx-th.width/2),round(cy-th.height/2)))
    ang=math.radians(res['direction']);d.line((cx,cy,cx+110*math.cos(ang),cy-110*math.sin(ang)),fill=(150,150,170),width=4)
    d.text((8,6),f"{res['scene']}：实线=华为，点线=模型；大圆=该色材料原位置；灰线=消逝方向",font=F,fill='white')
    for c,col in enumerate(res['colors']):
        hx,hy=res['home'][c]
        for k,key in enumerate(['huawei','model']):
            pts=[(cx+hx*S,cy+hy*S)]+[(cx+row[key][c]['x']*S,cy+row[key][c]['y']*S) for row in res['rows'] if row[key][c]]
            if len(pts)<2:continue
            if k==0:d.line(pts,fill=col,width=4)
            else:
                for a,b in zip(pts[:-1],pts[1:]):
                    for t in np.linspace(0,1,6)[::2]:
                        q=(a[0]+(b[0]-a[0])*t,a[1]+(b[1]-a[1])*t);d.ellipse((q[0]-2,q[1]-2,q[0]+2,q[1]+2),fill=col)
            e=pts[-1];d.ellipse((e[0]-5,e[1]-5,e[0]+5,e[1]+5),outline=col,width=2)
            d.text((e[0]+6,e[1]-8),'华' if k==0 else '模',font=F,fill=col)
        d.ellipse((cx+hx*S-8,cy+hy*S-8,cx+hx*S+8,cy+hy*S+8),fill=col,outline='white',width=2)
    im.save(path)


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',required=True);ap.add_argument('--rules');ap.add_argument('--label',default='current')
    ap.add_argument('--phases',default=','.join(f'{v:.2f}' for v in np.arange(.1,.86,.05)));a=ap.parse_args()
    import moderngl
    from release_integrity import load_rules
    rules=load_rules(a.rules);ctx=moderngl.create_standalone_context(require=430);OUT.mkdir(parents=True,exist_ok=True)
    phases=[float(v) for v in a.phases.split(',')]
    for n in a.scenes:
        res=run(n,rules,ctx,phases)
        (OUT/f'{a.label}-{n}.json').write_text(json.dumps({k:v for k,v in res.items() if k!='thumb'},ensure_ascii=False,indent=1),'utf-8')
        draw(res,OUT/f'{a.label}-{n}.png')
        print(n,len(res['colors']),'类',flush=True)


if __name__=='__main__':main()
