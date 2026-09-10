"""用同一提炼模型检查所有输入；不根据名称选择任何运动参数。"""
from pathlib import Path
import sys,json,math,argparse
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import numpy as np,moderngl
from scipy.ndimage import map_coordinates
from PIL import Image,ImageDraw,ImageFont
import renderer,unified_model
from export_videos import Reference

DATA=renderer.HERE/'analysis/transport-model/shared'

def configure():
    timing=np.fromfile(DATA/'common-release.f32',dtype='<f4').reshape(96,96)
    def field(nx,ny,direction,width=None,height=None):
        width=nx if width is None else width;height=ny if height is None else height
        radians=np.deg2rad(direction);a=np.arctan2(np.sin(radians)/height,np.cos(radians)/width)-np.pi/2
        c,s=np.cos(a),np.sin(a);yy,xx=np.mgrid[:ny,:nx].astype(float)
        x=(xx+.5)/nx-.5;y=(yy+.5)/ny-.5
        u=.5+c*x-s*y;v=.5+s*x+c*y
        return map_coordinates(timing,[(v+.45)/1.9*96-.5,(u+.45)/1.9*96-.5],order=1,mode='nearest').astype('float32')
    unified_model.release_field=field
    # 原建材调用尚不带物理宽高；两轴材料格实际尺寸相近，离散比例误差仅为取整误差。
    s=renderer.COMPUTE;start=s.index('    // 源位置');end=s.index('    float response=')
    s=s[:start]+'''    target=guide*(guide_gain/.9)+flow*.02+wind*u*.00001;
    float random_angle=m.random.w*6.28318;
    float radius=sqrt(-2.*log(max(m.random.z,.015)));
    float separate=smoothstep(.008,.045,age)*(1.-smoothstep(.16,.32,age));
    target+=vec2(cos(random_angle),sin(random_angle))*radius*span*.045*separate;
    float depth_target=-state[i].pos.z*3.*roll_gain;
    target+=wind*max(0.-dot(target,wind),0.);
'''+s[end:]
    s=s.replace(')*span;\n    float entrained',')*card;\n    float entrained')
    renderer.COMPUTE=s
    renderer.VERTEX=renderer.VERTEX.replace('uniform int nx,grid_count;','uniform int nx,grid_count;\nuniform float panel_weight;')
    renderer.VERTEX=renderer.VERTEX.replace('float content=pigmentation[gl_InstanceID];','float content=pigmentation[gl_InstanceID];\n    scale*=1.-.26*panel_weight*(1.-content)*smoothstep(.006,.040,age);')
    return field

def install(r):
    flow=np.fromfile(DATA/'common-flow.f16',dtype='<f2').astype('float32')
    r.flow_tex.release();r.flow_tex=r.ctx.texture3d((64,64,48),2,flow.tobytes(),dtype='f4')
    r.flow_tex.filter=(moderngl.LINEAR,moderngl.LINEAR);r.flow_tex.repeat_x=False;r.flow_tex.repeat_y=False;r.flow_tex.repeat_z=False
    a=math.radians(r.direction);angle=math.atan2(math.sin(a)/r.ch,math.cos(a)/r.cw)-math.pi/2
    r.compute['guide_rotation']=(math.cos(angle),math.sin(angle))
    r.program['panel_weight']=r.material_info['panel_weight']

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--scenes',nargs='+',default=['ironman','thanos','kobe','attachment','color']);parser.add_argument('--directions',action='store_true');args=parser.parse_args()
    out=renderer.HERE/'analysis/transport-model/review';out.mkdir(parents=True,exist_ok=True)
    configure();ctx=moderngl.create_standalone_context(require=430);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',17)
    for name in args.scenes:
        r=renderer.Renderer(name,ctx=ctx);install(r);ref=Reference(r.meta)
        x,y,x1,y1=r.meta['rect'];lo=max(0,y-80);hi=min(r.h,y1+80);w=360;h=round((hi-lo)*w/r.w)
        frames=[];sheet=Image.new('RGB',(w*4,2*(h+28)),(16,21,29));d=ImageDraw.Draw(sheet)
        for j,t in enumerate([.31,.43,.55,.65]):
            now=r.render(t);frames.append(now);Image.fromarray(now).save(out/f'{name}-{t:.2f}.png')
            for row,(im,label) in enumerate([(ref.at(t),'参考' if r.meta['reference'] else '源素材'),(now,'共同释放与输运模型')]):
                sheet.paste(Image.fromarray(im[lo:hi]).resize((w,h),Image.Resampling.LANCZOS),(j*w,row*(h+28)+28));d.text((j*w+5,row*(h+28)+3),f'{label} {t:.2f}',font=font,fill='white')
        sheet.save(out/f'{name}.jpg',quality=94)
        if name=='ironman':
            old=np.load(renderer.HERE/'archive/observed-approved/ironman.npy',mmap_mode='r')
            delta=[]
            for t,now in zip([.31,.43,.55,.65],frames):
                control=old[round(t*120)]
                delta.append({'phase':t,'whole_frame_mae':float(np.abs(now.astype(float)-control.astype(float)).mean())})
            (out/'control-difference.json').write_text(json.dumps(delta,indent=2),'utf-8');print(delta,flush=True)
        r.close();print(name,flush=True)
    if args.directions:
        for name in ['ironman','attachment']:
            sheet=Image.new('RGB',(400*4,560*2),(16,21,29));d=ImageDraw.Draw(sheet)
            for j,angle in enumerate(range(0,360,45)):
                r=renderer.Renderer(name,direction=angle,ctx=ctx);install(r)
                im=Image.fromarray(r.render(.55));im.thumbnail((400,530));sheet.paste(im,((j%4)*400+(400-im.width)//2,(j//4)*560+30));d.text(((j%4)*400+8,(j//4)*560+3),f'{angle}°',font=font,fill='white');r.close()
            sheet.save(out/f'{name}-directions.jpg',quality=94)
    ctx.release()

if __name__=='__main__':main()
