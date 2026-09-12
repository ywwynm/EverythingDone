"""检验寿命尾部与输运随机量的耦合，不按截图区域改变运行模型。"""
from pathlib import Path
import sys,json,copy
import numpy as np,moderngl
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer,unified_model as model
from probe_frame_difference import configure
from filament_probe import OUT,sheet
from frame_difference import blur
from export_videos import Reference,load_meta

ORIGINAL_MATERIALS=model.materials
MIXER='''
uint transport_hash(uint x){x^=x>>16u;x*=0x7feb352du;x^=x>>15u;x*=0x846ca68bu;return x^(x>>16u);}
float transport_unit(uint x){return float(transport_hash(x)>>8u)*(1./16777216.);}
'''

def candidate(base,kind):
    configure(base);renderer.materials=ORIGINAL_MATERIALS
    optical=base.get('optical',{})
    if 'glint_power' in optical:
        renderer.FRAGMENT=renderer.FRAGMENT.replace('pow(facing,12.)',f"pow(facing,{optical['glint_power']:.7f})")
    if 'size_spread' in optical:
        spread=optical['size_spread']
        renderer.VERTEX=renderer.VERTEX.replace('.62+.72*m.random.y',
            f'exp({spread:.7f}*sqrt(-2.*log(max(m.random.y,.004)))*cos(m.random.z*6.28318)-{spread*spread:.7f})')
    cohort=base.get('cohort_model',{})
    if kind in ['independent-motion','combined']:
        renderer.COMPUTE=renderer.COMPUTE.replace('void main(){',MIXER+'\nvoid main(){')
        renderer.COMPUTE=renderer.COMPUTE.replace('target*=1.+.50*(m.random.x-.5)',
            'uint transport_seed=floatBitsToUint(m.random.y)^floatBitsToUint(m.random.w);\n    vec2 transport_random=vec2(transport_unit(transport_seed),transport_unit(transport_seed^0x9e3779b9u));\n    target*=1.+.50*(transport_random.x-.5)')
        renderer.COMPUTE=renderer.COMPUTE.replace('curl(p+vec2(m.random.x,m.random.y)*span*.4','curl(p+transport_random*span*.4')
        renderer.COMPUTE=renderer.COMPUTE.replace('1.+.50*(transport_random.x-.5)',f"1.+{cohort.get('speed_spread',.50):.7f}*(transport_random.x-.5)")
    if kind in ['early-life','independent-life','combined','early-life-gentle']:
        def material(*args,**kwargs):
            built=ORIGINAL_MATERIALS(*args,**kwargs);values=built['base'];rand=values[:,8:];born=values[:,2]
            if kind=='independent-life':
                ids=values[:,3].astype('int64');lr=model.random_values(int(ids.max())+1,int(args[4])^0x1b873593)[ids,0]
            else:lr=rand[:,0]
            early=cohort.get('early_gain',.70);late=cohort.get('late_gain',.94)
            gain=early+(late-early)*model.smooth((born-cohort.get('birth_start',.12))/cohort.get('birth_span',.32)) if kind!='early-life-gentle' else .77+.17*model.smooth((born-.12)/.32)
            if kind=='independent-life':gain=.94
            life=(.10+.22*(-np.log(np.maximum(lr,.004)))**.85+.20*born)*gain
            cap=.865+.115*rand[:,2]-born
            life=np.maximum(np.minimum(life,cap),.11)
            values[:,6]=np.maximum(np.minimum(life*(1+.30*built['pigment']),cap),.11)
            return built
        renderer.materials=material

def filament_energy(im,bg):
    yy,xx=np.mgrid[:im.shape[0],:im.shape[1]]
    centre=490-40*np.clip((yy-420)/200,0,1)
    mask=(abs(xx-centre)<18)&(yy>360)&(yy<620)
    lum=lambda a:a.astype('float32')@np.array([.2126,.7152,.0722],dtype='float32')
    return float(np.maximum(blur(lum(im)-lum(bg),1),0)[mask].sum())

def main():
    base=json.loads((HERE/'analysis/common-shape-calibration.json').read_text('utf-8'))['config']
    ctx=moderngl.create_standalone_context(require=430);times=[.40,.55,2/3,.78];refs=Reference(load_meta('ironman'))
    rows=[('华为参考',[refs.at(t) for t in times])];scores=[]
    from PIL import Image
    bg=np.array(Image.open(HERE/'assets/ironman/background.png').convert('RGB'))
    for kind,title in [('current','当前发布'),('independent-motion','输运随机量独立'),('independent-life','寿命随机量独立'),
            ('early-life','初段材料寿命分布'),('early-life-gentle','较弱寿命调整'),('combined','寿命分布与独立输运')]:
        candidate(base,kind);r=renderer.Renderer('ironman',ctx=ctx);ims=[r.render(t) for t in times];r.close()
        score=dict(kind=kind,energy=filament_energy(ims[2],bg),
            structure=[float(abs(blur(im,6)-blur(ref,6))[150:930,35:685].mean()) for im,ref in zip(ims,rows[0][1])])
        print(json.dumps(score),flush=True);scores.append(score);rows.append((title,ims));np.save(OUT/f'{kind}.npy',ims)
    sheet(rows,'lifetime',times);(OUT/'lifetime.json').write_text(json.dumps(scores,ensure_ascii=False,indent=2),'utf-8');ctx.release()

if __name__=='__main__':main()
