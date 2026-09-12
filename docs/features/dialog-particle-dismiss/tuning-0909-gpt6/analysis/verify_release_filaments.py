"""跨早、后段反例检查移动粒子的细线对比度，固定测试走廊允许轮廓平移。"""
from pathlib import Path
import sys,json,argparse
import numpy as np,cv2,moderngl
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from probe_device_filaments import OUT,configure

def ridge_contrast(image,case):
    # 走廊仅用于回归测量：整段允许 ±14 像素漂移，防止把细线挪开就算通过。
    lum=image.astype('float32')@np.array([.2126,.7152,.0722],dtype='float32')
    # 只量 3 像素以内的窄线；较大半径会把主粒子群的宽密度坡面也算作细缕。
    response=cv2.GaussianBlur(lum,(0,0),1.5)-cv2.GaussianBlur(lum,(0,0),3.)
    if case==3:
        axis=np.arange(705,825);center=419+.0022*(axis-705)**2
        coords=np.array([center[:,None]+np.arange(-14,15),np.broadcast_to(axis[:,None],(len(axis),29))])
    elif case==1:
        axis=np.arange(300,420);center=778+.27*(axis-300)
        coords=np.array([np.broadcast_to(axis[:,None],(len(axis),29)),center[:,None]+np.arange(-14,15)])
    else:raise ValueError(case)
    x,y=np.rint(coords).astype(int);cross=response[y,x]
    # 同一个横向偏移必须沿整段连续成立；逐截面各取最大值会误把无关亮粒串成线。
    return float(np.max(np.mean(cross,axis=0)))

def main():
    p=argparse.ArgumentParser();p.add_argument('--candidate');p.add_argument('--measure',action='store_true');a=p.parse_args()
    if a.candidate:configure(a.candidate)
    ctx=moderngl.create_standalone_context(require=430);rows=[]
    for j in [1,3]:
        r=renderer.Renderer(str(OUT/f'input-{j}'),ctx=ctx);im=r.render(.24,diagnostic=2);r.close()
        score=ridge_contrast(im,j);rows.append(dict(case=j,score=score,limit=2.5))
    ctx.release();result=dict(candidate=a.candidate or 'production',cases=rows,passed=all(r['score']<r['limit'] for r in rows))
    print(json.dumps(result),flush=True)
    (OUT/f'release-regression-{a.candidate or "production"}.json').write_text(json.dumps(result,indent=2),'utf-8')
    if not a.measure:assert result['passed'],'早期孤立窄线仍超过对比度阈值'
if __name__=='__main__':main()
