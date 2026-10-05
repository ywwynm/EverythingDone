"""等期望覆盖下比较整体缩小与微片逐渐退场，保持轨迹和原色不变。"""
from pathlib import Path
import sys
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from front_transport import FrontRenderer


def configure():
    FrontRenderer.vertex_source=FrontRenderer.vertex_source.replace(
        'scale*=1.-0.3900000*smoothstep(.12,.36,age);','// 细化的面积损失改为独立微片退场，由片元阶段执行。')
    FrontRenderer.fragment_source=FrontRenderer.fragment_source.replace('void main(){',
        'uint survivor_hash(uint v){v^=v>>16u;v*=0x7feb352du;v^=v>>15u;v*=0x846ca68bu;return v^(v>>16u);}\nvoid main(){',1)
    FrontRenderer.fragment_source=FrontRenderer.fragment_source.replace('if(alpha<.001)discard;',
        'float area=pow(1.-.39*smoothstep(.12,.36,age),2.);\n'
        'uint key=floatBitsToUint(random_out.x)^floatBitsToUint(random_out.w)^0x1f83d9abu;\n'
        'float rank=float(survivor_hash(key)>>8u)*(1./16777216.);\n'
        'float band=min(.045,(1.-area)*.25+.001);\n'
        'alpha*=1.-smoothstep(area-band,area+band,rank);\n'
        'if(alpha<.001)discard;')


if __name__=='__main__':
    configure()
    from review_reference_suite_0926 import main
    sys.argv=[sys.argv[0],'--candidate','--tag','grain-survival','--scenes','reference-0926-7','reference-0926-5','reference-0926-1','ironman','thanos','kobe']
    main()
