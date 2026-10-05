"""单独检验屏幕颜色合成空间，不改变运动、起点或表面时序。"""
from pathlib import Path
import sys
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from front_transport import FrontRenderer


def configure():
    renderer.BACKGROUND=renderer.BACKGROUND.replace('linear(texture(bg,uv).rgb*(1.-dim))','texture(bg,uv).rgb*(1.-dim)')
    renderer.OUTPUT=renderer.OUTPUT.replace('srgb(texture(screen,vec2(uv.x,1.-uv.y)).rgb)','texture(screen,vec2(uv.x,1.-uv.y)).rgb')
    FrontRenderer.fragment_source=FrontRenderer.fragment_source.replace('void main(){',
        'vec3 display(vec3 c){return mix(c*12.92,1.055*pow(max(c,0.),vec3(1./2.4))-.055,step(vec3(.0031308),c));}\nvoid main(){',1)
    FrontRenderer.fragment_source=FrontRenderer.fragment_source.replace('frag=vec4(surface_color*a,a);','frag=vec4(display(surface_color)*a,a);')
    FrontRenderer.fragment_source=FrontRenderer.fragment_source.replace('frag=vec4(color*alpha,alpha);','frag=vec4(display(color)*alpha,alpha);')


if __name__=='__main__':
    configure()
    if '--fit' in sys.argv:
        from calibrate_front_rules_0926 import main
        sys.argv=[sys.argv[0],'--stage','compositing','--trials','24']
    else:
        from review_reference_suite_0926 import main
        if len(sys.argv)==1:
            sys.argv=[sys.argv[0],'--candidate','--tag','display-compositing','--scenes','reference-0926-7','reference-0926-5','reference-0926-1','ironman','thanos']
    main()
