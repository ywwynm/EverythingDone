"""三星固定输入最终验收；正式 Android 与独立桌面逐帧算法、正式 GLSL 三方比较。"""
import json
import sys
from pathlib import Path
import moderngl
import numpy as np
from PIL import Image
from check_drift_clarity import ROOT, Renderer, montage

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'parity'))
import drift_parity as parity


def main():
    ctx=moderngl.create_standalone_context(require=430)
    summaries=[]
    for name in ('ironman-ref','dialog-light','dialog-dark','ironman-style-dialog','ref5-over'):
        folder=parity.OUT/'clarity-20261008-after'/name
        folder.mkdir(parents=True,exist_ok=True)
        img,spec=parity.case_inputs(name)
        spec=parity.finish_spec(img,spec)
        spec['displayDensity']=3.75
        result=parity.run_device(name,folder,img,spec)
        desktop=parity.run_desktop(folder,spec,result,ctx)
        prod=Renderer(ctx,folder)
        rows=[]
        for i,t in enumerate(spec['times']):
            device=np.asarray(Image.open(folder/'device'/result['frames'][i]).convert('RGB'))
            glsl=prod.render(t)
            for label,frame in (('glsl',glsl),('desktop',desktop[i])):
                diff=np.abs(device.astype(np.int16)-frame.astype(np.int16))
                rows.append(dict(time=t,source=label,mean=float(diff.mean()),p999=float(np.percentile(diff,99.9)),
                                 over8=float(np.mean(diff>8))))
            if i in (8,11,14,17,20):
                before=parity.OUT/'clarity-20261008-before'/name/'device'/result['frames'][i]
                images=[('Android after',device),('Desktop after',desktop[i])]
                if before.exists():images.insert(0,('Android before',np.asarray(Image.open(before).convert('RGB'))))
                montage(images,folder/f'compare-{t:.2f}.png',width=440)
        prod.close()
        (folder/'verification.json').write_text(json.dumps(rows,indent=2),'utf-8')
        summary={label:dict(mean=max(r['mean'] for r in rows if r['source']==label),
                           p999=max(r['p999'] for r in rows if r['source']==label),
                           over8=max(r['over8'] for r in rows if r['source']==label)) for label in ('glsl','desktop')}
        summaries.append(dict(scene=name,**summary))
        print(name,json.dumps(summary),flush=True)
        assert summary['glsl']['p999']<=2 and summary['glsl']['over8']<.0001
        assert summary['desktop']['p999']<=3 and summary['desktop']['over8']<.0001
    (parity.OUT/'clarity-20261008-after'/'summary.json').write_text(json.dumps(summaries,indent=2),'utf-8')


if __name__=='__main__': main()
