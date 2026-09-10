"""读取真实顶点着色器的材料中心，验证固定身份粒群是否往返回弹。"""
import argparse,json
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
from renderer import Renderer,VERTEX,HERE
from export_videos import VERSION,code_hash
from unified_model import model_fingerprint

def trace(r,times):
    shader=VERTEX.replace('out vec2 uv,local_uv;','out vec2 uv,local_uv;\nout vec2 trace_center;')
    shader=shader.replace('vec2 world=offset+vertex;','vec2 world=offset+vertex;\ntrace_center=offset+move;')
    prog=r.ctx.program(vertex_shader=shader,varyings=['trace_center'])
    vao=r.ctx.vertex_array(prog,[]);buf=r.ctx.buffer(reserve=r.n*2*4)
    paths=[];simulation=[]
    for t in times:
        r.render(t)
        for key in prog:
            if key in r.program and hasattr(prog[key],'value'):prog[key].value=r.program[key].value
        vao.transform(buf,mode=moderngl.POINTS,vertices=1,instances=r.n)
        paths.append(np.frombuffer(buf.read(),dtype='float32').reshape(r.n,2).copy())
        simulation.append(np.frombuffer(r.state.read(),dtype='float32').reshape(r.n,8)[:,:2].copy())
    vao.release();prog.release();buf.release()
    return np.array(paths),np.array(simulation)

def cohort(r):
    # 固定材料身份，避免新生/消失改变统计人群造成视觉质心错觉。
    x=r.base[:,0]/r.cw;y=r.base[:,1]/r.ch
    sel=(x<.24)&(y<.23)&(r.base[:,2]<.25)&(r.base[:,2]+r.base[:,6]>.65)
    if r.meta['name']!='ironman':sel=(r.base[:,2]<.22)&(r.base[:,2]+r.base[:,6]>.65)
    return sel

def metrics(r,positions,simulation,times,sel):
    src=r.base[sel,:2];offset=np.array(r.meta['rect'][:2]);wind=np.array(r.wind)
    q=(positions[:,sel]-src-offset)@wind
    simulated=(simulation[:,sel]-src)@wind
    mean=q.mean(axis=1);physical=simulated.mean(axis=1)
    # 测量 0.24~0.62 的固定群体，包含用户指出的中途反向段。
    window=(times>=.24)&(times<=.62)
    peak=np.maximum.accumulate(mean[window]);backtrack=float(np.max(peak-mean[window]))
    per_peak=np.maximum.accumulate(q[window],axis=0)
    reverse_fraction=float(np.mean(np.max(per_peak-q[window],axis=0)>4))
    velocity=np.diff(mean)/np.diff(times)
    return {'scene':r.meta['name'],'cohort_size':int(sel.sum()),'max_cohort_backtrack_px':backtrack,'fraction_individual_backtrack_over_4px':reverse_fraction,'min_cohort_projected_velocity':float(velocity[(times[:-1]>=.24)&(times[:-1]<=.62)].min()),'times':times.tolist(),'rendered_projection':mean.tolist(),'simulation_projection':physical.tolist()}

def run(tag,settings=None,assert_no_bounce=False):
    r=Renderer('ironman',settings=settings);times=np.linspace(0,.72,87)
    pos,sim=trace(r,times);sel=cohort(r);data=metrics(r,pos,sim,times,sel)
    data['settings']=r.settings
    out=HERE/'analysis';(out/f'{tag}-trajectory.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
    np.savez_compressed(out/f'{tag}-trajectory.npz',times=times,positions=pos[:,sel],simulation=sim[:,sel],base=r.base[sel])
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',21)
    im=Image.new('RGB',(1120,680),(18,25,36));d=ImageDraw.Draw(im)
    d.text((28,16),f'{tag} · 钢铁侠左上角固定材料 · {sel.sum()} 个单元',font=font,fill='white')
    d.text((28,48),f'中途最大倒退 {data["max_cohort_backtrack_px"]:.2f} px · 反向个体占比 {100*data["fraction_individual_backtrack_over_4px"]:.1f}%',font=font,fill='#d9bea1')
    maxv=max(max(data['simulation_projection']),max(data['rendered_projection']))+12
    def points(vals):return [(70+float(t)/.72*1000,595-float(v)/maxv*465) for t,v in zip(times,vals)]
    for j in range(7):
        yy=595-j/6*465;d.line((70,yy,1070,yy),fill='#344155');d.text((12,yy-10),f'{j/6*maxv:.0f}',font=font,fill='#aab9cb')
    for j in range(7):
        xx=70+j/6*1000;d.text((xx-12,608),f'{j*.12:.2f}',font=font,fill='#aab9cb')
    d.line(points(data['rendered_projection']),fill='#63d7cf',width=4)
    d.line(points(data['simulation_projection']),fill='#f8ac70',width=3)
    d.text((620,78),'青色：实际画面位移；橙色：模拟状态位移',font=font,fill='white')
    d.text((790,645),'横轴：归一化时间 / 秒',font=font,fill='#aab9cb')
    im.save(out/f'{tag}-trajectory.jpg',quality=96)
    print(json.dumps({k:v for k,v in data.items() if k not in ['times','rendered_projection','simulation_projection']},ensure_ascii=False))
    r.close()
    if assert_no_bounce:assert data['max_cohort_backtrack_px']<1.0 and data['fraction_individual_backtrack_over_4px']<.02,{k:v for k,v in data.items() if k not in ['times','rendered_projection','simulation_projection']}
    return data

def all_visible_checks():
    """实际顶点中心逐帧检查：仅比较两帧都处于可见生命期的同一材料。"""
    names=[m['name'] for m in json.loads((HERE/'assets/scenes.json').read_text('utf-8'))]
    angles=[0,17,45,90,135,180,225,270,315,359]
    ctx=moderngl.create_standalone_context(require=430);results=[]
    for name in names:
        for angle in angles:
            r=Renderer(name,direction=angle,ctx=ctx)
            shader=VERTEX.replace('out vec2 uv,local_uv;','out vec2 uv,local_uv;\nout vec2 trace_center;')
            shader=shader.replace('vec2 world=offset+vertex;','vec2 world=offset+vertex;\ntrace_center=offset+move;')
            prog=ctx.program(vertex_shader=shader,varyings=['trace_center']);vao=ctx.vertex_array(prog,[])
            buf=ctx.buffer(reserve=r.n*2*4);previous=None;min_step=1e9;violations=0;checks=0
            for t in np.arange(0,121)/120:
                r.render(t)
                for key in prog:
                    if key in r.program and hasattr(prog[key],'value'):prog[key].value=r.program[key].value
                vao.transform(buf,mode=moderngl.POINTS,vertices=1,instances=r.n)
                pos=np.frombuffer(buf.read(),dtype='float32').reshape(r.n,2).copy()
                visible=(t-r.base[:,2]>1/120)&(t-r.base[:,2]<r.base[:,6]-.005)
                if previous is not None and visible.any():
                    step=(pos[visible]-previous[visible])@np.array(r.wind)
                    min_step=min(min_step,float(step.min()));violations+=int((step<-.002).sum());checks+=int(visible.sum())
                previous=pos
            item={'scene':name,'angle':angle,'visible_material_frame_pairs':checks,'min_projected_step_px':min_step,'reverse_steps_over_002px':violations}
            results.append(item);vao.release();prog.release();buf.release();r.close()
            assert violations==0,item
        print('实际渲染轨迹检查通过',name,len(angles),'个方向',flush=True)
    ctx.release()
    data={'version':VERSION,'code_hash':code_hash(),'sample_fps':120,'cases':results,'total_visible_pairs':sum(r['visible_material_frame_pairs'] for r in results),'scope':'实际顶点输出的材料中心；同一材料在相邻可见生命期帧的位移沿指定方向投影。侧向弯曲仍然允许。'}
    data['model_hash']=model_fingerprint()
    (HERE/'analysis/trajectory-qa.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
    return data

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--tag',default=VERSION);p.add_argument('--roll',type=float);p.add_argument('--guide',type=float);p.add_argument('--curl',type=float);p.add_argument('--assert-no-bounce',action='store_true');p.add_argument('--all-visible',action='store_true');a=p.parse_args()
    settings={k:v for k,v in [('roll_gain',a.roll),('guide_gain',a.guide),('curl_gain',a.curl)] if v is not None}
    all_visible_checks() if a.all_visible else run(a.tag,settings,a.assert_no_bounce)
