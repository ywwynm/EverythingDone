"""状态清理修复后逐帧重算；仅在像素完全相同时复用已有编码视频。"""
from pathlib import Path
import sys,json,time
import numpy as np,moderngl
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from renderer import Renderer
from export_videos import code_hash,load_meta
from unified_model import model_fingerprint
from export_pressure_review import CASES
OUT=HERE/'analysis/edge-flow-support'

def main():
    manifest_path=HERE/'videos/manifest.json'
    manifest=json.loads(manifest_path.read_text('utf-8'))
    previous=manifest['code_hash'];current=code_hash();assert previous!=current
    review_path=HERE/'videos/pressure-flow-videos.json';review=json.loads(review_path.read_text('utf-8'))
    assert review['code_hash']==previous
    ctx=moderngl.create_standalone_context(require=430);checked=[];stamps=[]
    for path in sorted((HERE/'cache').glob('*.json')):
        stamp=json.loads(path.read_text('utf-8'))
        if stamp.get('hash')!=previous:continue
        old=np.load(path.with_suffix('.npy'),mmap_mode='r')
        # 原缓存保存的是解析后的距离；只有键中的 -gap- 表示调用方显式传入触点。
        gap=stamp['touch_gap'] if '-gap-' in path.stem else None
        r=Renderer(stamp['scene'],ctx=ctx,direction=stamp['angle'],seed=stamp['seed'],touch_gap=gap,view_bounds=stamp.get('view_bounds'))
        for i in range(len(old)):
            pixels=r.render(i/stamp['sample_fps'])
            assert np.array_equal(pixels,old[i]),(path.name,i,'前向画面变化，禁止复用视频')
        r.close();checked.append(dict(cache=path.name,frames=len(old),pixel_difference=0));stamps.append((path,stamp))
        print('逐帧相同',path.stem,flush=True)
    for number,(scene,angle,seed) in enumerate(CASES,1):
        meta=load_meta(scene);angle=meta['direction'] if angle is None else angle;seed=meta['seed'] if seed is None else seed
        old=np.load(OUT/f'acceptance-{number:02d}-{scene}.npy',mmap_mode='r')
        r=Renderer(scene,ctx=ctx,direction=angle,seed=seed)
        for i in range(len(old)):assert np.array_equal(r.render(i/120),old[i]),(scene,i,'专项画面变化')
        r.close();checked.append(dict(review=number,scene=scene,frames=len(old),pixel_difference=0))
    ctx.release();assert checked and code_hash()==current
    # 完整验证结束之后再更新身份；图片、视频字节不改变。
    for path,stamp in stamps:
        stamp['verified_from_hash']=previous;stamp['hash']=current
        path.write_text(json.dumps(stamp,ensure_ascii=False,indent=2),'utf-8')
    manifest['videos']=[v for v in manifest['videos'] if v.get('code_hash')==previous]
    for data in [manifest,review]:
        data['code_hash']=current;data['verified_from_hash']=previous;data['verified_local']=time.strftime('%Y-%m-%d %H:%M:%S')
        for item in data['videos']:item['code_hash']=current
    previous_model=review['model_hash'];review['model_hash']=model_fingerprint()
    manifest_path.write_text(json.dumps(manifest,ensure_ascii=False,indent=2),'utf-8')
    review_path.write_text(json.dumps(review,ensure_ascii=False,indent=2),'utf-8')
    page=HERE/'videos/pressure-flow.html'
    page.write_text(page.read_text('utf-8').replace(previous,current).replace(previous_model,review['model_hash']),'utf-8')
    report=dict(previous_code_hash=previous,current_code_hash=current,model_hash=model_fingerprint(),verified=checked,
        note='重算全部本轮缓存及专项视频源帧；逐帧字节相同，允许复用已经编码的视频。重置后的可重复性另由 verify.py 验证。')
    (OUT/'reset-media-validation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    print('全部前向画面相同',len(checked),flush=True)

if __name__=='__main__':main()
