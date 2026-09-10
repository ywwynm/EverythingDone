"""固定 r28 归档，对共享运动项做可复现消融；不改交付缓存和视频。"""
import argparse,json,types
import numpy as np
from PIL import Image,ImageDraw,ImageFont
from renderer import HERE
# 无论交付版本如何继续改动，消融始终从 r28 的模拟与材质开始。
renderer=types.ModuleType('experiment_r28')
renderer.__file__=str(HERE/'renderer.py')
exec(compile((HERE/'archive/r28/renderer.py').read_text(encoding='utf-8'),'archive/r28/renderer.py','exec'),renderer.__dict__)
Renderer=renderer.Renderer
from review import reference,crop
from compare_metrics import feature

VARIANTS={
    'r28':{},
    'low_scatter':{'scatter':.24},
    'coherent_curl':{'scatter':.24,'curl':2.4},
    'no_turn':{'scatter':.24,'turn':0.},
    'reverse_turn':{'scatter':.24,'turn':-.65},
    'calm_entrain':{'scatter':.32,'entrain':.08,'curl':1.6},
    'material_coverage':{'coverage':1.},
    'material_light':{'material_light':1.},
    'material_both':{'coverage':1.,'material_light':1.},
    'fold_normal':{'scatter':.32,'normal_fold':1.,'coverage':1.,'material_light':1.},
    'fold_soft':{'scatter':.42,'normal_fold':.55,'coverage':.6,'material_light':.6},
    'life_narrow':{'life_curve':(.22,.14,.08)},
    'life_short':{'life_curve':(.18,.14,.08)},
    'life_late_short':{'life_curve':(.29,.14,-.14)},
    'life_narrow_fold':{'life_curve':(.22,.14,.08),'normal_fold':.55,'scatter':.32},
    'life_smooth':{'life_curve':(.24,.10,-.04),'scatter':.32,'entrain':.08},
    'side_peel':{'side_peel':2.4,'turn':0.,'scatter':.32,'entrain':.08},
    'side_peel_strong':{'side_peel':4.0,'turn':0.,'scatter':.32,'entrain':.08},
    'side_peel_mid':{'side_peel':3.2,'turn':0.,'scatter':.42,'entrain':.20},
    'side_peel_life':{'side_peel':3.2,'turn':0.,'scatter':.32,'entrain':.08,'life_curve':(.24,.10,-.04)},
    'side_peel_curl':{'side_peel':3.2,'turn':0.,'scatter':.32,'entrain':.08,'curl':2.},
    'early_life':{'early_life':.12},
    'early_front':{'early_front':-.06},
    'early_both':{'early_front':-.06,'early_life':.12},
    'early_spread':{'early_front':-.06,'early_life':.12,'scatter':.40,'side_peel':2.4,'turn':0.,'entrain':.08},
    'inward_fold':{'inward_fold':1.,'scatter':.32,'turn':0.,'entrain':.08},
    'inward_soft':{'inward_fold':.55,'scatter':.40,'turn':0.,'entrain':.16},
    'inward_late':{'inward_fold':.80,'scatter':.32,'turn':0.,'entrain':.08,'fold_late':1.},
    'inward_noise':{'inward_fold':.70,'scatter':.48,'turn':.16,'entrain':.16,'curl':1.4},
    'lanes_soft':{'lanes':.20,'scatter':.42,'entrain':.24},
    'lanes_mid':{'lanes':.45,'scatter':.32,'entrain':.24},
    'lanes_fine':{'lanes':.45,'lane_scale':.14,'scatter':.32,'entrain':.24},
    'lanes_broad':{'lanes':.60,'lane_scale':.32,'scatter':.32,'entrain':.24},
    'compact_wind':{'aligned_peel':.75},
    'compact_flow':{'aligned_peel':.75,'scatter':.48,'entrain':.24},
    'compact_grain':{'aligned_peel':.75,'scatter':.48,'entrain':.24,'grain':1.},
    'compact_material':{'aligned_peel':.75,'scatter':.48,'entrain':.24,'grain':1.,'pose':1.},
    'compact_turn':{'aligned_peel':.75,'scatter':.48,'entrain':.24,'grain':1.,'pose':1.,'turn':.32},
    'compact_no_turn':{'aligned_peel':.75,'scatter':.48,'entrain':.24,'grain':1.,'pose':1.,'turn':0.},
    'compact_front':{'aligned_peel':.75,'scatter':.48,'entrain':.24,'grain':1.,'pose':1.,'turn':.32,'coverage':.18},
}
ORIGINAL=renderer.COMPUTE
VERTEX=renderer.VERTEX
FRAGMENT=renderer.FRAGMENT
FONT=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',19)
TIMES=[.16,.32,.48,.64,.80,.94]

def configure(v):
    s=ORIGINAL
    for key,old,new in [
        ('scatter','.60*exp(-age/.11)',f"{v.get('scatter',.60):.4f}*exp(-age/.11)"),
        ('turn','span*.65*sin(turn_phase)',f"span*{v.get('turn',.65):.4f}*sin(turn_phase)"),
        ('entrain','1.+.42*smoothstep',f"1.+{v.get('entrain',.42):.4f}*smoothstep")]:
        if key in v:
            assert old in s,old
            s=s.replace(old,new)
    renderer.COMPUTE=s
    if 'aligned_peel' in v:
        s=s.replace('target+=separation;',f'''float normal_alignment=dot(-m.physical.xy,wind)/max(length(m.physical.xy),.01);
        separation-=wind*max(dot(separation,wind),0.)*{v['aligned_peel']:.4f}*smoothstep(.70,.98,normal_alignment);
        target+=separation;''')
        renderer.COMPUTE=s
    if 'lanes' in v:
        s=s.replace('float response=1.-exp(-h/m.physical.w);',f'''
        vec2 lateral=vec2(-wind.y,wind.x);
        float along=dot(p,wind)/span;
        float across=dot(p,lateral)/span;
        float ridge=across/{v.get('lane_scale',.22):.4f}*6.283+sin(along*12.-time*3.2)*1.4;
        target-=lateral*sin(ridge)*span*{v['lanes']:.4f}*smoothstep(.012,.065,age);
        float response=1.-exp(-h/m.physical.w);''')
        renderer.COMPUTE=s
    if 'side_peel' in v:
        a=v['side_peel']
        s=s.replace('span*1.65*exp(-age/.105)',f'span*{a:.4f}*exp(-age/.105)')
        s=s.replace('separation-=wind*min(dot(separation,wind),0.);','separation-=wind*(dot(separation,wind)-max(dot(separation,wind),0.)*.12);')
        renderer.COMPUTE=s
    if 'inward_fold' in v:
        strength=v['inward_fold']
        wave='(1.-cos(min(age*18.,3.14159)))*exp(-age/.16)' if 'fold_late' in v else 'exp(-age/.12)'
        s=s.replace('target+=separation;',f'''target+=separation*(1.-{strength:.4f});
        vec2 fold_velocity=m.physical.xy*span*3.2*{wave}*smoothstep(.004,.025,age)*{strength:.4f}*roll_gain;
        fold_velocity-=wind*min(dot(fold_velocity,wind),0.);
        target+=fold_velocity;''')
        renderer.COMPUTE=s
    vert=VERTEX;frag=FRAGMENT
    if 'grain' in v:
        vert=vert.replace('scale*=1.-.22*smoothstep(.08,.48,age);','scale*=1.-.36*smoothstep(.12,.36,age);')
        # 近处保留可辨认微片，老化颗粒逐步细化；不拉长或整体增厚。
    if 'pose' in v:
        vert=vert.replace('float tilt=(.5+.5*sin(roll_phase+m.random.w*6.28));','float tilt=(.5+.5*sin(roll_phase+dot(m.src.xy,vec2(.009,.014))+m.random.w*1.2));')
        vert=vert.replace('float angle=(m.random.z-.5)*6.28*smoothstep(.024,.22,age)+sin(age*12.+m.random.w*6.28)*.28*loosen;', '''float facing_angle=atan(m.physical.y,m.physical.x);
        float angle=facing_angle*.35*smoothstep(.02,.08,age)+(m.random.z-.5)*6.28*smoothstep(.08,.28,age)+sin(age*12.+m.random.w*6.28)*.28*loosen;''')
    if 'normal_fold' in v:
        # 法线横向初速由相邻释放点共享；对主风仍保留前向限制。
        a=v['normal_fold']
        renderer.COMPUTE=s.replace('target+=vec2(-wind.y,wind.x)*turn;',f'''target+=vec2(-wind.y,wind.x)*turn*(1.-{a:.4f});
        vec2 transverse=vec2(-wind.y,wind.x);
        float outward_side=dot(-m.physical.xy,transverse);
        float rolling=span*2.4*outward_side*cos(age*13.)*exp(-age/.19)*smoothstep(.003,.024,age);
        target+=transverse*rolling*{a:.4f};''')
    if 'coverage' in v:
        needle='// 解体只在释放之后发生，且从原始密铺几何连续变化。'
        assert needle in vert
        vert=vert.replace(needle,f'''scale*=1.+{v['coverage']:.4f}*.44*smoothstep(.018,.065,age)*(1.-smoothstep(.13,.36,age));
        {needle}''')
    if 'material_light' in v:
        frag=frag.replace('if(diagnostic==1)',f'''float rim=smoothstep(.015,.07,age)*(1.-smoothstep(.16,.34,age));
        color+=vec3(.16)*rim*{v['material_light']:.4f};
        if(diagnostic==1)''')
    renderer.VERTEX=vert;renderer.FRAGMENT=frag

def run(names,variants,tag):
    metrics=[]
    for name in names:
        results={};m=None
        for key in variants:
            v=VARIANTS[key];configure(v)
            r=Renderer(name,settings={'curl_gain':v.get('curl',1.)});m=r.meta
            if 'early_front' in v:
                b=r.base;x=b[:,0]/r.cw;y=b[:,1]/r.ch
                b[:,2]=np.maximum(b[:,2]+v['early_front']*np.exp(-((x-.26)/.24)**2-((y-.68)/.34)**2),.001)
                r.material.write(b.tobytes())
            if 'life_curve' in v:
                low,spread,late=v['life_curve'];b=r.base
                life=low+spread*b[:,8]+late*b[:,2]
                b[:,6]=np.maximum(np.minimum(life,.865+.115*b[:,10]-b[:,2]),.11)
                r.material.write(b.tobytes())
            if 'early_life' in v:
                b=r.base;f=np.clip((b[:,2]-.26)/.34,0,1);f=f*f*(3-2*f)
                b[:,6]=np.minimum(b[:,6]+v['early_life']*(1-f),.865+.115*b[:,10]-b[:,2])
                r.material.write(b.tobytes())
            results[key]=[r.render(t) for t in TIMES];r.close()
        truth=[reference(m,t) for t in TIMES]
        keys=['华为']+variants
        # 每行是一个进度，每列是同源同种子的独立候选。
        W=335;H=385
        canvas=Image.new('RGB',(W*len(keys),H*len(TIMES)),(18,23,31));d=ImageDraw.Draw(canvas)
        for i,t in enumerate(TIMES):
            for j,k in enumerate(keys):
                a=truth[i] if j==0 else results[k][i]
                im=Image.fromarray(crop(a,m));im.thumbnail((W-8,H-34))
                canvas.paste(im,(j*W+(W-im.width)//2,i*H+32))
                d.text((j*W+8,i*H+5),f'{k} {t:.2f}',font=FONT,fill='white')
        canvas.save(HERE/'analysis'/f'{tag}-{name}.jpg',quality=95)
        scores={k:[round(float(abs(feature(a,m)-feature(b,m)).mean()),4) for a,b in zip(results[k],truth)] for k in variants}
        metrics.append({'scene':name,'times':TIMES,'scores':scores})
        print(name,{k:round(float(np.mean(v)),3) for k,v in scores.items()},flush=True)
    (HERE/'analysis'/f'{tag}.json').write_text(json.dumps(metrics,ensure_ascii=False,indent=2),encoding='utf-8')
    renderer.COMPUTE=ORIGINAL

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--scenes',nargs='+',default=['kobe','ironman','thanos']);p.add_argument('--variants',nargs='+',default=list(VARIANTS));p.add_argument('--tag',default='coherence-ablation');a=p.parse_args();run(a.scenes,a.variants,a.tag)
