"""验证 r30 的材质变化未改变 r29 轨迹，且白色本体没有扩大或延长生命。"""
import types,json
import numpy as np,moderngl
from renderer import Renderer,HERE
from export_videos import VERSION,code_hash

baseline=types.ModuleType('verify_r29');baseline.__file__=str(HERE/'renderer.py')
exec(compile((HERE/'archive/r29/renderer.py').read_text(encoding='utf-8'),'archive/r29/renderer.py','exec'),baseline.__dict__)

def run():
    ctx=moderngl.create_standalone_context(require=430);rows=[]
    names=['ironman','thanos','kobe','language','color','attachment','attachment-image']
    for name in names:
        old=baseline.Renderer(name,ctx=ctx);new=Renderer(name,ctx=ctx)
        assert np.array_equal(old.base[:,:6],new.base[:,:6]),name
        assert np.array_equal(old.base[:,7:],new.base[:,7:]),name
        content=np.frombuffer(new.pigment.read(),dtype='float32')
        plain=content==0
        assert np.array_equal(old.base[plain,6],new.base[plain,6]),name
        assert np.all(new.base[:,6]>=old.base[:,6]-1e-6),name
        assert np.all(new.base[:,6]<=old.base[:,6]*1.30+1e-6),name
        count=0;maximum=0.
        for t in [.08,.16,.32,.48,.64,.80,.96]:
            old.render(t);a=np.frombuffer(old.state.read(),dtype='float32').copy()
            new.render(t);b=np.frombuffer(new.state.read(),dtype='float32').copy()
            assert np.array_equal(a,b),(name,t,float(abs(a-b).max()))
            count+=old.n;maximum=max(maximum,float(abs(a-b).max()))
        row={'scene':name,'material_state_checks':count,'state_max_difference':maximum,'release_and_motion_parameters_identical':True,'unweighted_material_life_identical':True,'weighted_material_fraction':float((content>0).mean()),'mean_life_ratio':float(np.mean(new.base[:,6]/old.base[:,6]))}
        rows.append(row);old.close();new.close();print('r29 轨迹与本体保留检查通过',name,flush=True)
    ctx.release()
    result={'version':VERSION,'baseline':'r29','code_hash':code_hash(),'cases':rows,'scope':'比较相同材料完整模拟状态；生命期和几何面积可以改变，运动轨迹不变。'}
    (HERE/'analysis/material-qa.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')

if __name__=='__main__':run()
