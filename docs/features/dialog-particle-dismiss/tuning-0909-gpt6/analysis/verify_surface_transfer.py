"""验证共同输入、真实位移和表面渐隐；统计不替代整体观感检查。"""
from pathlib import Path
import sys,json,importlib.util
import numpy as np,moderngl
from PIL import Image,ImageDraw
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from renderer import Renderer
from unified_model import SHARED,model_fingerprint
from export_videos import load_meta,label,BG
from touch_geometry import distance_cases
from evaluate_targeted_release import destination_metrics
OUT=HERE/'analysis/surface-transfer'

def main():
    ctx=moderngl.create_standalone_context(require=430);rows=[]
    for name in ['ironman','attachment','color']:
        for direction in [135,90]:
            group=[];births=[]
            for c in distance_cases(load_meta(name),direction):
                r=Renderer(name,ctx=ctx,quality=1,direction=c['angle'],touch_gap=c['gap'])
                births.append(r.base[:,[2,6]].copy())
                row=dict(scene=name,requested=direction,**c,touch_strength=r.touch_strength,**destination_metrics(r))
                group.append(row);rows.append(row);r.close()
            assert all(np.array_equal(births[0],b) for b in births[1:])
            for key in ['speed_median','displacement_median']:
                values=[c[key] for c in group]
                assert values[0]<values[1]<values[2] and 1.6<values[2]/values[0]<2.7,(name,direction,key,values)
            print('近远实际位移检查',name,direction,flush=True)
    unchanged=[]
    for name in ['common-flow.f16','common-release.f32','flow-confidence.u8']:
        assert (SHARED/name).read_bytes()==(HERE/'archive/before-surface-transfer/shared'/name).read_bytes()
        unchanged.append(name)
    # 使用同一源材质和真实渲染通道检查交接，不根据公式重算预期图。
    r=Renderer('ironman',ctx=ctx);m=r.meta;w=360;h=round(r.h*w/r.w);step_h=h+32
    picture=Image.new('RGB',(w*4,step_h*3),BG);draw=ImageDraw.Draw(picture)
    for col,t in enumerate([.44,.48,.52,.56]):
        for row,(mode,title) in enumerate([(0,'最终合成'),(3,'原表面'),(2,'运动颗粒')]):
            image=r.render(t,diagnostic=mode)
            label(draw,(col*w+8,row*step_h+4),f'{title} · {t:.2f}',20)
            picture.paste(Image.fromarray(image).resize((w,h)),(col*w,row*step_h+32))
    picture.save(OUT/'surface-layers.jpg',quality=95)
    fg=np.array(Image.open(r.directory/'foreground.png').convert('RGBA'));fg[:,:,:3]=255
    r.fg_tex.write(fg.tobytes());r.bg_tex.write(bytes(r.w*r.h*3))
    # 白色源色和黑色背景只用于测量透明覆盖，不参与视频或运行参数。
    x,y,x1,y1=m['rect'];partial=[];coverage=[]
    for t in np.arange(.48,.561,.01):
        s=r.render(float(t),diagnostic=3)[y:y1,x:x+round(r.cw*.12),0]/255.
        s=np.where(s<=.04045,s/12.92,((s+.055)/1.055)**2.4)
        partial.append(float(np.mean((s>.02)&(s<.98))));coverage.append(float(s.mean()))
    assert max(partial)>.03,partial
    assert coverage[-1]<coverage[0],coverage
    r.close()
    # 无硬边约束的早期迎风行程对照；旧渲染器使用提交版采样密度。
    spec=importlib.util.spec_from_file_location('surface_baseline',HERE/'archive/before-surface-transfer/renderer.py')
    old=importlib.util.module_from_spec(spec);spec.loader.exec_module(old);old.HERE=HERE
    travel={}
    for title,cls,cell in [('baseline',old.Renderer,2.35),('current',Renderer,1.85)]:
        r=cls('ironman',ctx=ctx,cell_px=cell);r.seek(.39)
        s=np.frombuffer(r.state.read(),np.float32).reshape(r.n,8)
        mask=(r.base[:,0]<r.cw*.22)&(r.base[:,1]<r.ch*.22)&(r.base[:,2]<.33)
        projection=(s[mask,:2]-r.base[mask,:2])@np.array(r.wind)
        travel[title]=dict(median_px=float(np.median(projection)),p90_px=float(np.quantile(projection,.9)))
        r.close()
    assert travel['current']['p90_px']<travel['baseline']['p90_px']
    ctx.release()
    result=dict(model_hash=model_fingerprint(),distance_cases=rows,unchanged_fields=unchanged,
        distance_birth_and_lifetime_identical=True,source_partial_coverage=partial,source_coverage=coverage,
        upper_corner_travel=travel,scope='轨迹、材质交接及资源一致性检查；不据此宣称与华为视觉一致。')
    (OUT/'metrics.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8')
    print('表面交接、开放侧与共同输入检查完成',travel,flush=True)

if __name__=='__main__':main()
