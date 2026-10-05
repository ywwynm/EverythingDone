"""共同改进候选的状态校验和十二段关键帧对照；不代表视觉通过。"""
from pathlib import Path
import argparse, json, sys
import moderngl
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(ROOT),str(ROOT/'analysis')]
from harmony_core import HarmonyCoreRenderer,DEFAULT
from harmony_adapted import HarmonyAdaptedRenderer,AdaptedRules
from desktop_inputs import REFS
from match_refs import RefFrames


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--out',type=Path,required=True)
    args=ap.parse_args();args.out.mkdir(parents=True,exist_ok=True)
    ctx=moderngl.create_standalone_context(require=430)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18)
    core=HarmonyCoreRenderer('ironman',ctx=ctx)
    off=HarmonyAdaptedRenderer('ironman',ctx=ctx,config=DEFAULT,
                               rules=AdaptedRules(phase_spread=.5,linear_light=False,facet_light=0))
    off.compute['flow_offset']=(0.,0.,0.)
    differences=[]
    for p in [.25,.5,.75]:
        a=core.render(p);b=off.render(p)
        state_a=np.frombuffer(core.state.read(),np.float32)
        state_b=np.frombuffer(off.state.read(),np.float32)
        state_error=float(np.abs(state_a-state_b).max())
        image_error=float(np.abs(a.astype(float)-b).mean())
        assert state_error<.01 and image_error<.03,(p,state_error,image_error)
        differences.append(dict(progress=p,state_max_error=state_error,image_mae=image_error))
    core.close();off.close()
    report=dict(renderer=ctx.info['GL_RENDERER'],disabled_effect_equivalence=differences,
                visual_acceptance=False,scenes={})
    for name in REFS:
        r=HarmonyAdaptedRenderer(name,ctx=ctx)
        core=HarmonyCoreRenderer(name,ctx=ctx)
        ref=RefFrames(name);m=r.meta;x,y,x1,y1=m['rect']
        box=(max(0,x-50),max(0,y-80),min(r.w,x1+50),min(r.h,y1+40))
        rows=[];states=[];middle=None;first=r.render(0)
        for p in [.18,.30,.42,.54,.66,.78,.90]:
            candidate=r.render(p)
            assert r.diagnostics()['finite'],name
            states.append(dict(progress=p,**r.diagnostics()))
            tiles=[]
            for label,frame in zip(['华为原片','原生公式','共同相位／材质候选'],
                                   [ref.nearest(p)[1],core.render(p),candidate]):
                im=Image.fromarray(frame).crop(box);im.thumbnail((340,390))
                tile=Image.new('RGB',(340,im.height+30),'#10151d');tile.paste(im,(0,30))
                ImageDraw.Draw(tile).text((5,3),f'{label} · {p:.2f}',font=font,fill='white')
                if p>m['reference'].get('valid_progress',1):
                    ImageDraw.Draw(tile).text((5,35),'原片已截断，不参与验收',font=font,fill='#ff9090')
                tiles.append(tile)
            row=Image.new('RGB',(1020,max(t.height for t in tiles)),'#10151d')
            for col,tile in enumerate(tiles):row.paste(tile,(col*340,0))
            rows.append(row)
            if p==.54:middle=(candidate.copy(),r.state.read())
        assert np.array_equal(middle[0],r.render(.54)),name+' 倒退重放像素不一致'
        assert middle[1]==r.state.read(),name+' 倒退重放状态不一致'
        assert np.array_equal(first,r.render(0)),name+' 首帧未恢复'
        end=r.render(1)
        bg=np.asarray(Image.open(r.directory/'background.png').convert('RGB'))
        assert np.array_equal(end,bg),name+' 结束背景不一致'
        sheet=Image.new('RGB',(1020,sum(row.height for row in rows)),'#10151d');yy=0
        for row in rows:sheet.paste(row,(0,yy));yy+=row.height
        sheet.save(args.out/f'{name}-sheet.jpg',quality=94)
        rows[3].save(args.out/f'{name}-middle.jpg',quality=94)
        report['scenes'][name]=dict(config=r.describe(),samples=states,
                                  seek_replay_equal=True,reset_equal=True,end_background_equal=True)
        (args.out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
        print(name,'有限状态、重放和结束检查通过；视觉待独立核对',flush=True)
        r.close();core.close()
    ctx.release()


if __name__=='__main__':main()
