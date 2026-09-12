"""将整帧验证后的候选写入正式着色器；运行时不依赖实验模块。"""
from pathlib import Path
import ast,sys
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from probe_surface_transfer import shaders

def main():
    chosen=shaders('soft-density-light')
    v=chosen['VERTEX'];f=chosen['FRAGMENT']
    v=v.replace('uniform float panel_weight;', 'uniform float panel_weight,release_spread;')
    v=v.replace('(.060+.080*body_weight)*(m.random.w-.5)', 'release_spread*(m.random.w-.5)')
    v=v.replace('mix(.20,.82,trailing)', 'mix(.20,1.,trailing)')
    v=v.replace('    float source_clock=', '    // 同一释放规则的平均时序用于表面交接；运动仍按每片实际出生时间开始。\n    float source_clock=')
    v=v.replace('    surface_out=mix', '    // 原边缘最后释放的一侧更多采用渐隐，面板内部保留颗粒层次。\n    surface_out=mix')
    v=v.replace('    scale*=1.+.20*(1.-panel_weight)', '    // 少量、宽时间范围的覆盖变化；亮度来自源色和运动颗粒，不添加轮廓线。\n    scale*=1.+.20*(1.-panel_weight)')
    f=f.replace('        if(a<.001)discard;\n        frag=vec4(linear(src.rgb)*a,a);return;', '''        if(a<.001 || diagnostic==2)discard;
        vec3 surface_color=diagnostic==1?vec3(.05,.45,.95):linear(src.rgb);
        frag=vec4(surface_color*a,a);return;''')
    f=f.replace('    if(material_pass==0 && age>0.)discard;\n    if(material_pass==1 && age<=0.)discard;', '    if(age<=0. || diagnostic==3)discard;')
    f=f.replace('    if(diagnostic==1)color=age<=0.?vec3(.05,.45,.95):vec3(1.,.20,.07);\n    if(diagnostic==2 && age<=0.)discard;', '    if(diagnostic==1)color=vec3(1.,.20,.07);')
    f=f.replace('lighting*=1.+.32*smoothstep', 'lighting*=1.+.32*light_gain*smoothstep')
    f=f.replace('    if(fract(random_out.y', '    // 开放侧减少实际颗粒份额；避免保留大量半透明灰片勾出原轮廓。\n    if(fract(random_out.y')
    f=f.replace('    float optical_loosen=', '    // 几何变细与光照变化分开，避免刚出现的颗粒同时发白。\n    float optical_loosen=')
    chosen.update(VERTEX=v,FRAGMENT=f)
    path=HERE/'renderer.py';source=path.read_text('utf-8');lines=source.splitlines(keepends=True)
    tree=ast.parse(source)
    spans=[]
    for n in tree.body:
        if isinstance(n,ast.Assign):
            for target in n.targets:
                if isinstance(target,ast.Name) and target.id in chosen:
                    spans.append((n.lineno-1,n.end_lineno,target.id))
    assert len(spans)==3
    for start,end,name in reversed(spans):lines[start:end]=[name+"=r'''"+chosen[name]+"'''\n"]
    source=''.join(lines)
    old="self.program['body_weight']=self.body_weight"
    if "self.program['release_spread']" not in source:
        source=source.replace(old,old+"\n        if 'release_spread' in self.program:self.program['release_spread']=self.release_spread")
    path.write_text(source,'utf-8',newline='\n')
    print('共同着色器已更新；未采用相干窄窗加色。')

if __name__=='__main__':main()
