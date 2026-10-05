"""只用前 0.20 进度复核起点几何，输出供人工检查，不写入运动规则。"""
from pathlib import Path
import json,sys
import numpy as np
from scipy.optimize import least_squares
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from reference_observations_0926 import OBSERVED_SITES
from export_videos import load_meta
OUT=HERE/'analysis/new-references-0926'


def main():
    allrows=[]
    for name,sites in OBSERVED_SITES.items():
        data=np.load(OUT/f'{name}-coverage-measurements.npz');a=data['arrival'];v=data['weight']
        y,x=np.mgrid[:a.shape[0],:a.shape[1]].astype(float);x=(x+.5)/a.shape[1];y=(y+.5)/a.shape[0]
        distances=np.stack([np.hypot(x-p[0],y-p[1]) for p in sites]);nearest=distances.argmin(0)
        rows=[]
        for j,p in enumerate(sites):
            valid=v&(a>.025)&(a<.20)&(nearest==j)
            if valid.sum()<50:continue
            xx,yy,t=x[valid],y[valid],a[valid]
            # 拟合局部椭圆到达二次形，只估计首次区域中心。
            def predict(z):
                cx,cy,start,sx,sy,skew=z;dx=xx-cx;dy=yy-cy
                return start+(dx/sx+dy*skew)**2+(dy/sy)**2
            fit=least_squares(lambda z:predict(z)-t,[p[0],p[1],min(p[2],.15),1,1,0],bounds=([max(-.2,p[0]-.3),max(-.2,p[1]-.3),-.08,.2,.2,-1.5],[min(1.2,p[0]+.3),min(1.2,p[1]+.3),.20,3,3,1.5]),loss='soft_l1',f_scale=.015,max_nfev=200)
            row=dict(original=p,estimated=list(map(float,fit.x[:3])),shape=list(map(float,fit.x[3:])),points=int(valid.sum()),error=float(np.mean(abs(predict(fit.x)-t))))
            rows.append(row)
        allrows.append(dict(scene=name,sites=rows));print(name,rows,flush=True)
    (OUT/'nucleation-audit.json').write_text(json.dumps(allrows,indent=2),'utf-8')


if __name__=='__main__':main()
