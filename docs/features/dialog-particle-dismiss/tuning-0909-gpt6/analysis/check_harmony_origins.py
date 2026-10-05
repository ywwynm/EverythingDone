"""核对显式起点实际进入消蚀着色器，并导出十二段初始条件对照。"""
from pathlib import Path
import argparse,json,math,sys
import moderngl,numpy as np
from PIL import Image,ImageDraw,ImageFont

ROOT=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(ROOT),str(ROOT/'analysis')]
from desktop_inputs import REFS,META,animation_inputs,make_renderer
from harmony_origins import HarmonyOriginsRenderer,OriginRules
from match_refs import RefFrames


def mask(r,p):
    r.draw_mask(r.timeline_seconds(p))
    return np.frombuffer(r.mask_fbo.read(components=4,alignment=1),np.uint8).reshape(r.native_card[1],r.native_card[0],4)[::-1]


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=Path,required=True)
    ap.add_argument('--rules',type=Path,help='仅用于共同传播规则的离线诊断，不更改实时默认参数')
    args=ap.parse_args();args.out.mkdir(parents=True,exist_ok=True)
    ctx=moderngl.create_standalone_context(require=430)
    r=HarmonyOriginsRenderer('ironman',ctx=ctx,origins=[dict(x=.4,y=.4,start=.12)],direction=47.)
    initial=mask(r,0).copy()
    assert np.array_equal(initial,mask(r,.11)),'起点尚未启动就提前消蚀'
    later=mask(r,.21)
    h,w=later.shape[:2]
    assert later[int(.4*h),int(.4*w),3]<30,'指定位置没有消蚀'
    assert later[int(.8*h),int(.8*w),3]==initial[int(.8*h),int(.8*w),3],'产生了额外远处起点'
    r.close()
    # 首帧已经消散的录像必须真正预演，并且回到零进度时能重现相同粒子状态。
    r=HarmonyOriginsRenderer('ironman',ctx=ctx,origins=[dict(x=.4,y=.4,start=-.05)],direction=47.)
    first=r.render(0);first_state=r.state.read()
    assert r.diagnostics()['active_count']>0,'负启动时刻没有产生开场颗粒'
    assert mask(r,0)[int(.4*r.native_card[1]),int(.4*r.native_card[0]),3]<initial[int(.4*h),int(.4*w),3]
    r.render(.3)
    assert np.array_equal(first,r.render(0)) and first_state==r.state.read(),'开场预演不能重复'
    r.close()
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15)
    report=dict(renderer=ctx.info['GL_RENDERER'],single_origin_causality=True,preroll_replay=True,
                visual_acceptance=False,scenes={})
    for name in REFS:
        inputs=animation_inputs(name,'ref',META[name]['seed'])
        if args.rules:
            rules=OriginRules(**json.loads(args.rules.read_text('utf-8'))['rules'])
            r=HarmonyOriginsRenderer(name,ctx=ctx,seed=inputs['seed'],direction=inputs['direction'],
                                     origins=inputs['origins'],origin_rules=rules)
        else:r=make_renderer(inputs,'harmony-origins',ctx)
        actual=math.degrees(math.atan2(r.native_wind[1],r.native_wind[0]))%360
        assert abs((actual-inputs['direction']+180)%360-180)<1e-5,(name,'风向未采用参考输入')
        assert r.mask_program['origin_count'].value==len(inputs['origins'])
        positions=np.asarray(r.mask_program['origin_sites'].value)[:len(inputs['origins']),:3]
        assert np.allclose(positions,[[o['x'],o['y'],o['start']] for o in inputs['origins']],atol=1e-7)
        wx,wy=r.compute['wind'].value
        shader_angle=math.degrees(math.atan2(-wy,wx))%360
        assert abs((shader_angle-inputs['direction']+180)%360-180)<1e-5,'计算着色器的风向未对齐'
        ref=RefFrames(name);x,y,x1,y1=r.meta['rect']
        box=(max(0,x-30),max(0,y-40),min(r.w,x1+30),min(r.h,y1+35))
        rows=[];states=[];middle=None
        for p in [.04,.10,.18,.26,.38,.54]:
            frame=r.render(p);state=r.diagnostics();assert state['finite'],name
            states.append(dict(progress=p,**state))
            if p==.18:middle=(frame.copy(),r.state.read())
            tiles=[]
            for label,image in [('华为原片',ref.nearest(p)[1]),('起点输入＋华为核心',frame)]:
                im=Image.fromarray(image).crop(box);im.thumbnail((300,330))
                tile=Image.new('RGB',(300,im.height+24),'#10151d');tile.paste(im,(0,24))
                ImageDraw.Draw(tile).text((4,2),f'{label} · {p:.2f}',font=font,fill='white')
                tiles.append(tile)
            row=Image.new('RGB',(600,max(t.height for t in tiles)),'#10151d')
            for j,tile in enumerate(tiles):row.paste(tile,(j*300,0))
            rows.append(row)
        assert np.array_equal(middle[0],r.render(.18)) and middle[1]==r.state.read(),name+' 回退重放不一致'
        for half,selected in [('early',rows[:3]),('later',rows[3:])]:
            sheet=Image.new('RGB',(600,sum(row.height for row in selected)),'#10151d');yy=0
            for row in selected:sheet.paste(row,(0,yy));yy+=row.height
            sheet.save(args.out/f'{name}-{half}.jpg',quality=94)
        report['scenes'][name]=dict(inputs=inputs,actual_direction=actual,core=r.describe(),states=states,
                                  replay_equal=True)
        (args.out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
        r.close();print(name,'起点数量、风向、状态及重放检查通过；早段画面待逐一核对',flush=True)
    ctx.release()


if __name__=='__main__':main()
