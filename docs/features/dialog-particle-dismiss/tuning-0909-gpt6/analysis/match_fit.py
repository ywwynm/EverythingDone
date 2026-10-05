"""用原片的同口径观测量联合拟合一套 V2Rules（所有素材共用）。

损失由三部分组成，逐帧在每段原片的独立画面上计算：
1. 位置与密度：8／32 像素块的 RGB 均值差、占用率差、原位表面占比差；
2. 时序：每个 8 像素块“颗粒第一次到达”与“最后一次离开”的进度差（描述云团去了哪里、何时散尽）；
3. 结构：中尺度密度起伏的能量（团块、细缕）之比。逐像素误差会惩罚任何与原片不同相位的卷曲，
   只用它会把卷流压成零；结构项只要求“有同样多的结构”，不要求结构出现在同一位置。
只拟合全局规则；起点、方向、种子是记录的输入，不随拟合改变。留出素材只报告、不参与选择。
"""
from pathlib import Path
import argparse,dataclasses,json,math,os,sys,time
import cv2
import numpy as np

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE));sys.path.insert(0,str(HERE/'analysis'))
from match_compare import Comparison
from match_observe import BLOCK
from dissolve_v2 import V2Renderer,V2Rules,reference_inputs,RULES

OUT=HERE/'analysis/match-cache/fit'
# 录制链路（设备约 1.7 倍分辨率 → 录屏或相机 → 缩到 720 宽 → H.264）对细小颗粒的低通；
# 模型帧先经同样程度的模糊再测量。数值按颗粒连通块与颗粒清晰度两项在 4 段参考上标定（约 0.5～0.8）。
CAPTURE_BLUR=.65
# 科比展示了其它参考没有的左向长流（左缘起点的剥离流持续很久），2026-09-27 起并入训练；
# 向上钢铁侠继续留出，参考 2、3 没有可靠背景，只作目视留出。
TRAIN=['ironman','reference-0926-5','spiderman-original','reference-0926-6','reference-0926-7','reference-0926-1','thanos','reference-0926-4','kobe']
HOLDOUT=['ironman-up-reference']

# 参数、下限、上限。
# 颗粒网格由颗粒尺度的同口径测量确定（3.0 像素），不参与拟合。
SPACE=[('duration',.45,1.6),('exponent',.7,1.6),('rate_variation',0.,.5),('front_noise',0.,.06),('edge_speed',0.,4.),('edge_depth',.02,.3),
       ('jitter',0.,.04),('surface_lead',0.,.04),('surface_tail',.02,.10),
       ('settle',.01,.12),('shrink_late',0.,.5),
       ('light',0.,.6),('light_early',0.,1.),('light_early_tau',.03,.4),('glint',0.,.8),('glint_power',4.,24.),('glint_rate',4.,30.),
       ('particle_follow',0.,1.),('vanish_alpha',0.,1.),
       ('spread',0.,1.2),('spread_tau',.03,1.),('peel',0.,3.),('peel_along',0.,1.),('peel_tau',.02,.6),
       ('wind0',0.,.4),('wind1',0.,1.2),('wind_tau',.05,.7),('wind_global',0.,.9),('wind_global_start',.1,.7),('wind_global_span',.1,.6),
       ('wind_var',0.,.4),('var_tau',.01,.4),('turb_tau',.01,.4),('inflate',0.,2.),('inflate_tau',.02,.3),('fan',0.,1.),('fan_distance',.2,3.),('template',.9,1.8),
       ('turb',0.,1.),('turb_scale',.2,1.2),('turb_rate',0.,2.5),('diffuse',0.,.12),('diffuse_tau',.05,.5),('inertia',.01,.15),
       ('separate',0.,.12),('separate_tau',.01,.15),
       ('touch_default',.3,1.),('converge',0.,.2),('target_near',.05,1.5),('target_far',.5,4.),('gain_near',.3,1.2),('gain_far',1.,3.),
       ('meeting',0.,.15),('meeting_width',.01,.15),('round_front',0.,.15),
       ('life0',.1,.6),('life1',.05,.6),('fade',.15,.8)]
# 旧运动规则（motion_legacy=1）的搜索空间：旧运动各分量的倍数、距离增益、模板汇聚部分、寿命与外观。
SPACE_LEGACY=[('legacy_peel',0.,2.),('legacy_template',.5,1.6),('legacy_separate',0.,2.),('legacy_deficit',0.,.4),('legacy_forward',0.,2.),
       ('legacy_indep',0.,2.),('legacy_touch_gap',0.,2.),('gain_near',.4,1.4),('gain_far',.6,1.8),('template_div',0.,1.),('template_clock',0.,1.),
       ('surface_lead',0.,.04),('surface_tail',.02,.14),('particle_follow',0.,1.),('alpha_in',.005,.08),
       ('size_birth',.8,1.6),('settle',.02,.25),('shrink_late',0.,.6),('vanish_alpha',0.,1.),
       ('light',0.,.8),('light_early',0.,1.),('light_early_tau',.03,.4),('glint',0.,.6),
       ('life0',.1,.6),('life1',.05,.6),('fade',.15,.8),('touch_default',.3,1.),('size_settled',.8,1.8),('life_born',0.,.6),('obstacle',0.,1.),('obstacle_depth',.01,.15),('onset',0.,.2),('shield',0.,1.),('shield_linger',.05,.4),('shield_reach',.04,.3)]
import os as _os
if _os.environ.get('MATCH_FIT_SPACE')=='legacy':SPACE=SPACE_LEGACY
# 致密带（弧形亮带、成股的流）位置项的权重：原片与模型各自取成带的高密度颗粒区，比较双向平均最近距离（见 structure_bands.py）。
BAND_WEIGHT=float(_os.environ.get('MATCH_FIT_BAND','0'))
# 运动活跃度项的权重（activity.py）：窗口内亮度变化面积与颗粒面积之比，原片与模型比较对数差。
ACT_WEIGHT=float(_os.environ.get('MATCH_FIT_ACT','0'))


def block_stats(scene,frame,roi_box,structure=False):
    """scene 为按 roi_box 裁剪后的场景；frame 为整幅画面。"""
    y0,y1,x0,x1=roi_box
    f=frame[y0:y1,x0:x1];o=scene.observe(f)
    f=f.astype('float32');m=scene.roi
    h=(y1-y0)//BLOCK*BLOCK;w=(x1-x0)//BLOCK*BLOCK
    def pool(a):
        a=a[:h,:w];mm=m[:h,:w].astype('float32')
        if a.ndim==3:return (a*mm[:,:,None]).reshape(h//BLOCK,BLOCK,w//BLOCK,BLOCK,-1).sum((1,3))
        return (a*mm).reshape(h//BLOCK,BLOCK,w//BLOCK,BLOCK).sum((1,3))
    count=pool(np.ones(f.shape[:2],'float32'))
    rgb=pool(f)/np.maximum(count,1)[:,:,None]
    occ=pool(o['occ'].astype('float32'))/np.maximum(count,1)
    sur=pool(o['surface'].astype('float32'))/np.maximum(count,1)
    par=pool((o['occ']&~o['surface']).astype('float32'))/np.maximum(count,1)
    out=dict(rgb=rgb,occ=occ,sur=sur,par=par,weight=(count>BLOCK*BLOCK*.5).astype('float32'))
    # 颗粒总量与卡片外占用（相对卡片面积）：约束“颗粒还在不在”，防止用提前淡出换取位置误差。
    area=float(scene.card.sum());pm=(o['occ']&~o['surface'])&m
    out['par_total']=float(pm.sum())/max(area,1.)
    out['outside']=float(((o['occ']&m)&~scene.card).sum())/max(area,1.)
    # 颗粒清晰度：半覆盖区里亮度相对 5×5 均值的平均偏差，扣除背景在同样像素上的自身纹理。
    # 原片颗粒是实心小点，淡出的半透明颗粒会让这一项明显变小（整片发白发雾）。
    part=((o['occ']&~o['surface'])&m).astype('float32')
    dens=cv2.blur(part,(7,7));sel=(dens>.15)&(dens<.85)
    if sel.sum()>300:
        L=f@np.float32([.299,.587,.114]);Lb=scene.bg_lum
        dev=np.abs(L-cv2.blur(L,(5,5)))[sel].mean();devb=scene.bg_dev[sel].mean()
        out['grain']=float(max(dev-devb,.5))
    else:out['grain']=None
    # 云团位置：非原位表面的占用像素在主方向上的均值与 90 分位、横向均值（短边单位，相对控件中心）。
    cl=(o['occ']&~o['surface'])&m
    ys,xs=np.nonzero(cl)
    if len(xs)>300 and scene.wind_axis is not None:
        w,cx,cy=scene.wind_axis;X,Y,X1,Y1=scene.rect_local
        al=((xs-(X+X1)/2)*w[0]+(ys-(Y+Y1)/2)*w[1])/scene.span;cr=((xs-(X+X1)/2)*(-w[1])+(ys-(Y+Y1)/2)*w[0])/scene.span
        out['drift']=(float(al.mean()),float(np.percentile(al,90)),float(cr.mean()))
    else:out['drift']=None
    # 颗粒亮度：粒子像素亮度的中位数与 90 分位（原片颗粒比源色亮，少数有高光）。
    pl=(f@np.float32([.299,.587,.114]))[o['particle']&m]
    out['lum']=(float(np.percentile(pl,50)),float(np.percentile(pl,90))) if len(pl)>400 else None
    # 颗粒大小：稀疏区里颗粒像素连通块的面积中位数（能分开数的颗粒）。
    pm8=(o['particle']&m).astype(np.uint8);sparse=(dens>.03)&(dens<.45)
    n,lab,st,cen=cv2.connectedComponentsWithStats(pm8,connectivity=8)
    if n>41:
        c=np.clip(cen[1:].astype(int),0,[pm8.shape[1]-1,pm8.shape[0]-1])
        ok=sparse[c[:,1],c[:,0]];areas=st[1:,cv2.CC_STAT_AREA][ok]
        out['blob']=float(np.median(areas)) if len(areas)>=40 else None
    else:out['blob']=None
    if structure:
        pm=((o['occ']&~o['surface'])&m).astype('float32')
        d4=cv2.GaussianBlur(pm,(0,0),4);d16=cv2.GaussianBlur(pm,(0,0),16);cloud=d16>.06
        out['band']=float(np.mean(np.square(d4-d16)[cloud])) if cloud.sum()>400 else None
    return out


def streak_stats(scene,frames,box,mode,wind):
    """拖影叠加图的运动方向：云内高通纹理功率谱的主轴（与拖影垂直）。

    返回 (拖影方向相对主方向的轴向角，度；各向异性 0～1)。整团统计一个方向，不依赖逐块一致性；
    拖影越明显，功率谱越集中在垂直于拖影的方向上。
    """
    y0,y1,x0,x1=box
    stack=np.stack([f[y0:y1,x0:x1].astype('int16') for f in frames])
    comp=(stack.min(0) if mode=='min' else stack.max(0)).astype('float32')
    diff=np.abs(comp-scene.bg).max(2);dsrc=np.abs(comp-scene.source).max(2)
    mask=((diff>40)&(dsrc>40)&scene.roi).astype('float32')
    if mask.sum()<3000:return None
    win=cv2.GaussianBlur(mask,(0,0),6)
    L=comp@np.float32([.299,.587,.114]);hp=(L-cv2.GaussianBlur(L,(0,0),3))*win
    F=np.abs(np.fft.fftshift(np.fft.fft2(hp)))**2
    h,w=F.shape;fy,fx=np.mgrid[:h,:w];fx=(fx-w//2)/w;fy=(fy-h//2)/h;r=np.hypot(fx,fy)
    band=(r>.02)&(r<.25);Fb=F*band
    M=np.array([[(Fb*fx*fx).sum(),(Fb*fx*fy).sum()],[(Fb*fx*fy).sum(),(Fb*fy*fy).sum()]])
    val,vec=np.linalg.eigh(M)
    if val.sum()<=0:return None
    d=vec[:,0]              # 最小特征值方向 = 拖影方向（频谱在它的垂直方向上最集中）
    rel=math.degrees(math.atan2(d[0]*wind[1]-d[1]*wind[0],d[0]*wind[0]+d[1]*wind[1]))
    rel=(rel+90)%180-90
    return rel,float((val[1]-val[0])/(val[1]+val[0]))


def coarse(a,k=4):
    h,w=a.shape[0]//k*k,a.shape[1]//k*k;a=a[:h,:w]
    return a.reshape(h//k,k,w//k,k,*a.shape[2:]).mean((1,3))


def timing_maps(stats,progress):
    """每块颗粒首次到达、最后离开的进度（颗粒占比超过 0.2 视为有云）。"""
    first=np.full(stats[0]['par'].shape,np.nan);last=np.full(stats[0]['par'].shape,np.nan)
    for p,s in zip(progress,stats):
        on=(s['par']>.2)&(s['weight']>0)
        first[on&np.isnan(first)]=p;last[on]=p
    return first,last


def timing_error(a,b):
    both=np.isfinite(a)&np.isfinite(b);either=np.isfinite(a)|np.isfinite(b)
    if either.sum()==0:return 0.
    err=np.abs(a-b)[both].sum()+.35*(either&~both).sum()
    return float(err/either.sum())


class Target:
    def __init__(self,name,ctx,frames=22):
        self.name=name;self.comp=Comparison(name);s=self.comp.scene
        ys,xs=np.nonzero(s.roi);self.box=(ys.min(),ys.max()+1,xs.min(),xs.max()+1)
        s=self.scene=s.cropped(self.box)
        s.bg_lum=s.bg@np.float32([.299,.587,.114]);s.bg_dev=np.abs(s.bg_lum-cv2.blur(s.bg_lum,(5,5)))
        # 主方向（参考重放输入）与控件在裁剪坐标系中的位置，供云团位置统计使用。
        q0=reference_inputs(name);ang=math.radians(q0['direction'])
        s.wind_axis=(np.array([math.cos(ang),-math.sin(ang)]),0,0)
        X,Y,X1,Y1=s.rect;s.rect_local=(X-self.box[2],Y-self.box[0],X1-self.box[2],Y1-self.box[0])
        idx=self.comp.index
        pick=np.unique(np.linspace(0,len(idx)-1,min(frames,len(idx))).round().astype(int))
        self.frames=[idx[i] for i in pick];self.progress=[float(self.comp.ref.progress[i]) for i in self.frames]
        self.ref=[block_stats(s,np.asarray(self.comp.ref.frames[i]),self.box,True) for i in self.frames]
        self.ref_first,self.ref_last=timing_maps(self.ref,self.progress)
        # 拖影窗口：每个窗口取 3 个原片独立帧，模型在相同进度渲染后同样叠加。
        from match_streaks import MODE
        self.mode=MODE.get(name,'min');self.windows=[]
        valid=self.comp.scene.meta['reference'].get('valid_progress',1.)
        ang=math.radians(self.comp.scene.meta['direction']);self.wind=np.array([math.cos(ang),-math.sin(ang)])
        for c in [.22,.36,.50,.64]:
            if c+.08>valid:continue
            ks=[i for i in self.comp.index if c<=self.comp.ref.progress[i]<=c+.08][:8]
            if len(ks)<4:continue
            ps=[float(self.comp.ref.progress[i]) for i in ks]
            st=streak_stats(s,[np.asarray(self.comp.ref.frames[i]) for i in ks],self.box,self.mode,self.wind)
            if st is not None:self.windows.append((ps,st))
        q=reference_inputs(name)
        self.renderer=V2Renderer(name,ctx=ctx,quality=2,**q)
        self.act_ref=[]
        if ACT_WEIGHT>0:
            from activity import activity,window_indices,WINDOWS
            prog=[float(self.comp.ref.progress[i]) for i in self.comp.index]
            for wa,wb in WINDOWS:
                idx=window_indices(prog,wa,wb)
                if len(idx)<3:continue
                ps=[prog[i] for i in idx]
                self.act_ref.append((ps,activity(self.comp.scene,[np.asarray(self.comp.ref.frames[self.comp.index[i]]) for i in idx])[0]))
        self.band_ref={}
        if BAND_WEIGHT>0:
            from structure_bands import bands
            for i,p in zip(self.frames,self.progress):
                if .35<=p<=.72:
                    y0,y1,x0,x1=self.box
                    self.band_ref[p]=bands(self.scene,np.asarray(self.comp.ref.frames[i])[y0:y1,x0:x1])[0]

    def loss(self,rules,detail=False):
        r=self.renderer;r.set_rules(rules);s=self.scene;parts=[];model=[];bands=[];amount=[];grains=[];blobs=[];lums=[];drifts=[]
        # 所有需要的进度按先后渲染一次（仿真只能向前推进）。
        need=sorted(set(self.progress)|{p for ps,_ in self.windows for p in ps}|{p for ps,_ in self.act_ref for p in ps})
        frames={p:cv2.GaussianBlur(r.render(p),(0,0),CAPTURE_BLUR) for p in need}
        for p,a in zip(self.progress,self.ref):
            b=block_stats(s,frames[p],self.box,True);model.append(b);w=a['weight']*b['weight']
            if a.get('grain') and b.get('grain'):grains.append(abs(math.log(b['grain']/a['grain'])))
            elif a.get('grain') or b.get('grain'):grains.append(.7)
            if a.get('blob') and b.get('blob'):blobs.append(abs(math.log(b['blob']/a['blob'])))
            if a.get('lum') and b.get('lum'):lums.append((abs(a['lum'][0]-b['lum'][0])+abs(a['lum'][1]-b['lum'][1]))/40.)
            if a.get('band') and b.get('band'):bands.append(abs(math.log(max(b['band'],1e-6)/a['band'])))
            elif a.get('band') or b.get('band'):bands.append(1.)
            amount.append((abs(a['par_total']-b['par_total']),abs(a['outside']-b['outside'])))
            if a.get('drift') and b.get('drift'):
                drifts.append(abs(a['drift'][0]-b['drift'][0])+abs(a['drift'][1]-b['drift'][1])+.5*abs(a['drift'][2]-b['drift'][2]))
            if w.sum()<1:continue
            rgb=(np.abs(a['rgb']-b['rgb']).mean(2)*w).sum()/w.sum()
            occ=(np.abs(a['occ']-b['occ'])*w).sum()/w.sum()
            sur=(np.abs(a['sur']-b['sur'])*w).sum()/w.sum()
            cw=coarse(w);crgb=(np.abs(coarse(a['rgb']*w[:,:,None])-coarse(b['rgb']*w[:,:,None])).mean(2)).sum()/max(cw.sum(),1e-6)
            cocc=(np.abs(coarse(a['occ']*w)-coarse(b['occ']*w))).sum()/max(cw.sum(),1e-6)
            parts.append((rgb,occ,sur,crgb,cocc))
        P=np.array(parts).mean(0)
        first,last=timing_maps(model,self.progress)
        tf=timing_error(self.ref_first,first);tl=timing_error(self.ref_last,last)
        band=float(np.mean(bands)) if bands else 0.
        A=np.array(amount).mean(0)
        grain=float(np.mean(grains)) if grains else 0.
        blob=float(np.mean(blobs)) if blobs else 0.
        lum=float(np.mean(lums)) if lums else 0.
        drift=float(np.mean(drifts)) if drifts else 0.
        streak=[]
        for ps,st in self.windows:
            m=streak_stats(s,[frames[p] for p in ps],self.box,self.mode,self.wind)
            if m is None:streak.append(1.);continue
            # 方向差按两者中较弱的各向异性加权：都没有明显拖影时方向无意义。
            d=abs((m[0]-st[0]+90)%180-90)/30.*min(1.,3*min(m[1],st[1]))
            streak.append(d+abs(m[1]-st[1])*3)
        sk=float(np.mean(streak)) if streak else 0.
        total=float(P[0]+60*P[1]+60*P[2]+P[3]+80*P[4]+25*tf+25*tl+15*band+60*A[0]+60*A[1]+8*grain+6*sk+6*blob+8*lum+25*drift)
        act=0.
        if self.act_ref:
            from activity import activity
            # 活跃度按整帧计算（与原片同一裁剪）：frames 里是整帧渲染结果。
            act=float(np.mean([abs(np.log(max(activity(self.comp.scene,[frames[p] for p in ps])[0],1e-3)/max(ah,1e-3))) for ps,ah in self.act_ref]))
            total+=ACT_WEIGHT*act
        bd=0.
        if self.band_ref:
            from structure_bands import bands,chamfer
            y0,y1,x0,x1=self.box;vals=[]
            for p,hb in self.band_ref.items():
                mb=bands(s,frames[p][y0:y1,x0:x1])[0];a,b=chamfer(hb,mb,s.span);vals.append(a+b)
            bd=float(np.mean(vals));total+=BAND_WEIGHT*bd
        if detail:return total,dict(zip(['rgb8','occ8','surface','rgb32','occ32'],map(float,P)))|dict(first=tf,last=tl,band=band,amount=float(A[0]),outside=float(A[1]),grain=grain,streak=sk,blob=blob,lum=lum,drift=drift,dense_band=bd,activity=act)
        return total


class DialogTarget:
    """自有白底弹窗没有华为参考；用户已认可此前模型在它上面的整体行为。

    只比较与颗粒位置无关的整体曲线：原表面占比、颗粒总量、颗粒沿主方向的质心位移与横向展宽。
    起点来自随机采样，两种模型的具体布局不同，逐块比较没有意义。暗层关闭，只看控件本身。
    """
    def __init__(self,name,ctx,cases=((135,7),(90,11),(40,5)),count=14):
        from renderer import Renderer
        from match_observe import Scene
        self.name=name;s=Scene(name);s.roi=np.zeros_like(s.roi)
        x,y,x1,y1=s.rect;mg=round(s.span*.8);s.roi[max(0,y-mg):y1+mg,max(0,x-mg):x1+mg]=True
        self.scene=s;self.cases=cases;self.progress=list(np.linspace(.06,.9,count))
        self.ref=[];self.renderers=[]
        for direction,seed in cases:
            old=Renderer(name,ctx=ctx,direction=direction,seed=seed,quality=1);old.meta['dim_alpha']=0.
            self.ref.append([self.stats(old.render(float(p)),direction) for p in self.progress]);old.close()
            r=V2Renderer(name,ctx=ctx,direction=direction,seed=seed,quality=1);r.meta['dim_alpha']=0.
            self.renderers.append((r,direction))

    def stats(self,frame,direction):
        s=self.scene;o=s.observe(frame);area=float(s.card.sum())
        sur=float((o['surface']&s.card).sum())/max(float((s.card&s.distinct).sum()),1.)
        pm=o['occ']&~o['surface']&s.roi;amt=float(pm.sum())/area
        ang=math.radians(direction);w=np.array([math.cos(ang),-math.sin(ang)]);c=np.array([-w[1],w[0]])
        ys,xs=np.nonzero(pm);X,Y,X1,Y1=s.rect;cx,cy=(X+X1)/2,(Y+Y1)/2
        if len(xs)<200:return sur,amt,None,None
        al=((xs-cx)*w[0]+(ys-cy)*w[1])/s.span;cr=((xs-cx)*c[0]+(ys-cy)*c[1])/s.span
        return sur,amt,float(al.mean()),float(cr.std())

    def loss(self,rules,detail=False):
        errs=[]
        for (r,direction),ref in zip(self.renderers,self.ref):
            r.set_rules(rules)
            for p,a in zip(self.progress,ref):
                b=self.stats(r.render(float(p)),direction)
                e=3*abs(a[0]-b[0])+3*abs(a[1]-b[1])
                if a[2] is not None and b[2] is not None:e+=abs(a[2]-b[2])+abs(a[3]-b[3])
                elif (a[2] is None)!=(b[2] is None):e+=.3
                errs.append(e)
        v=float(np.mean(errs))
        return (v,dict(dialog=v)) if detail else v


BASE=RULES
DIALOG_WEIGHT=3.
# 旧钢铁侠是用户已认可、只剩细微差异的序列，权重加倍，避免为新参考牺牲它。
SCENE_WEIGHT={'ironman':2.}


def _bounds(space):
    """环境变量 MATCH_FIT_BOUNDS='diffuse=0:0.03,turb=0:0.3' 收窄个别参数的搜索范围（按目视先定结构层级）。"""
    over={}
    for q in (x for x in os.environ.get('MATCH_FIT_BOUNDS','').split(',') if x):
        k,r=q.split('=');a,b=(float(v) for v in r.split(':'));over[k]=(a,b)
    return [(k,)+over.get(k,(lo,hi)) for k,lo,hi in space]


def weighted(targets,rules):
    w=np.array([SCENE_WEIGHT.get(t.name,1.) for t in targets]);v=np.array([t.loss(rules) for t in targets])
    return float((w*v).sum()/w.sum())


def to_rules(z,base=None):
    base=BASE if base is None else base
    values={}
    for (k,lo,hi),u in zip(SPACE,z):values[k]=float(lo+(hi-lo)*np.clip(u,0,1))
    return dataclasses.replace(base,**values)


def to_unit(rules):
    return np.array([(getattr(rules,k)-lo)/(hi-lo) for k,lo,hi in SPACE])


_WORKER=None
_DIALOGS=[]


def _init(scenes,frames,base=None,dialogs=()):
    """每个进程各自建立 GL 上下文与全部训练场景；numpy 限单线程，避免进程间抢核。"""
    global _WORKER,_DIALOGS,BASE,SPACE
    import os,moderngl
    fixed={k for k in os.environ.get('MATCH_FIT_FIX','').split(',') if k}
    SPACE=_bounds([q for q in SPACE if q[0] not in fixed])
    if base is not None:BASE=dataclasses.replace(RULES,**base)
    for k in ['OMP_NUM_THREADS','MKL_NUM_THREADS','OPENBLAS_NUM_THREADS']:os.environ[k]='1'
    ctx=moderngl.create_standalone_context(require=430)
    _WORKER=[Target(n,ctx,frames) for n in scenes]
    _DIALOGS=[DialogTarget(n,ctx) for n in dialogs]


def _evaluate(z):
    rules=to_rules(z);v=weighted(_WORKER,rules)
    if _DIALOGS:v+=DIALOG_WEIGHT*float(np.mean([d.loss(rules) for d in _DIALOGS]))
    return v


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--generations',type=int,default=40);ap.add_argument('--popsize',type=int,default=16)
    ap.add_argument('--workers',type=int,default=0);ap.add_argument('--dialogs',nargs='*',default=['attachment','color'])
    ap.add_argument('--sigma',type=float,default=.2);ap.add_argument('--label',default='fit2');ap.add_argument('--start')
    ap.add_argument('--frames',type=int,default=18);ap.add_argument('--scenes',nargs='+',default=TRAIN);ap.add_argument('--report',action='store_true')
    ap.add_argument('--fix',default='',help='逗号分隔的参数名：不参与拟合，保持起点值（例如已按解除时刻图单独拟合的前沿参数）')
    a=ap.parse_args()
    global SPACE
    fixed={k for k in a.fix.split(',') if k}
    SPACE=[q for q in SPACE if q[0] not in fixed]
    os.environ['MATCH_FIT_FIX']=a.fix
    SPACE=_bounds(SPACE)
    import cma,moderngl
    OUT.mkdir(parents=True,exist_ok=True);ctx=moderngl.create_standalone_context(require=430)
    global BASE
    start=RULES
    if a.start:start=dataclasses.replace(RULES,**{k:v for k,v in json.loads(Path(a.start).read_text('utf-8'))['best']['rules'].items() if k in dataclasses.asdict(RULES)})
    BASE=start
    t0=time.perf_counter()
    if a.workers>0 and not a.report:
        import multiprocessing as mp
        pool=mp.get_context('spawn').Pool(a.workers,initializer=_init,initargs=(a.scenes,a.frames,dataclasses.asdict(start),tuple(a.dialogs)))
        targets=None
        def evaluate_many(zs):return pool.map(_evaluate,list(zs))
    else:
        targets=[Target(n,ctx,a.frames) for n in a.scenes];dialogs=[DialogTarget(n,ctx) for n in a.dialogs]
        def evaluate_many(zs):
            out=[]
            for z in zs:
                rules=to_rules(z);v=weighted(targets,rules)
                if dialogs:v+=DIALOG_WEIGHT*float(np.mean([d.loss(rules) for d in dialogs]))
                out.append(v)
            return out
    print('准备完成',round(time.perf_counter()-t0,1),'s',flush=True)
    if a.report:
        for t in targets:print(t.name,{k:round(v,4) for k,v in t.loss(start,True)[1].items()},round(t.loss(start),3),flush=True)
        for d in dialogs:print(d.name,'弹窗整体曲线误差',round(d.loss(start),4),'（乘权重',DIALOG_WEIGHT,'）',flush=True)
        return
    x0=np.clip(to_unit(start),.02,.98)
    base=evaluate_many([x0])[0];print('起点损失',round(base,4),flush=True)
    es=cma.CMAEvolutionStrategy(x0,a.sigma,{'bounds':[0,1],'popsize':a.popsize,'seed':927,'verbose':-9})
    best=(base,x0.copy());history=[]
    for g in range(a.generations):
        zs=es.ask();vals=evaluate_many(zs);es.tell(zs,vals)
        i=int(np.argmin(vals))
        if vals[i]<best[0]:best=(vals[i],np.array(zs[i]))
        history.append(dict(generation=g,best=float(min(vals)),median=float(np.median(vals))))
        rules=to_rules(best[1])
        (OUT/f'{a.label}.json').write_text(json.dumps(dict(scenes=a.scenes,loss=best[0],start_loss=base,best=dict(rules=dataclasses.asdict(rules)),history=history),ensure_ascii=False,indent=1),'utf-8')
        print(f'代 {g} 最佳 {best[0]:.4f} 本代中位 {np.median(vals):.4f} 用时 {time.perf_counter()-t0:.0f}s',flush=True)
    rules=to_rules(best[1])
    if targets:
        for t in targets:print(t.name,t.loss(rules,True),flush=True)


if __name__=='__main__':main()
