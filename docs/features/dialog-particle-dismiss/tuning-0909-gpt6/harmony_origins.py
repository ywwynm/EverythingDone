"""华为运动核心的显式起点输入版；先用于参考初始条件核对。

保留原生粒子更新、寿命和绘制，对消蚀阈值加入共同的起点传播规则。
起点是一次动画输入，不从场景名选择公式，不读取参考帧或观测控制图。
这项扩展不是从华为镜像恢复的调用参数，运动相似度仍须独立验收。
"""
from dataclasses import dataclass,asdict
import math
import numpy as np
from harmony_core import HarmonyCoreRenderer,MASK_FRAGMENT,COMPUTE


@dataclass(frozen=True)
class OriginRules:
    duration:float=2.15
    exponent:float=2.0
    edge_speed:float=.65
    edge_depth:float=.20
    travel_limit:float=.72
    surface_tail:float=.075
    growth_progress:float=.035
    noise:float=.06


FRONT = r'''
uniform int origin_count;
uniform vec4 origin_sites[8];
uniform vec4 front_shape;
uniform vec4 front_clock;
uniform float origin_preroll;
float arrival(vec2 uv,vec2 coord){
    float span=min(native_card.x,native_card.y),first=10.;
    float frequency=205./(native_card.x*native_card.y)+.002;
    float variation=1.+front_clock.w*(erosionNoise(coord*frequency)-.5);
    for(int j=0;j<origin_count;j++){
        vec3 site=origin_sites[j].xyz;
        vec2 q=(uv-site.xy)*native_card/span;
        vec4 edges=vec4(site.y*native_card.y,(1.-site.x)*native_card.x,
                       (1.-site.y)*native_card.y,site.x*native_card.x)/span;
        float nearest=min(min(edges.x,edges.y),min(edges.z,edges.w));
        bool horizontal=nearest==edges.x || nearest==edges.z;
        float along=horizontal?q.x:q.y,across=horizontal?q.y:q.x;
        along/=1.+front_shape.z*exp(-max(nearest,0.)/.06)*exp(-abs(across)/front_shape.w);
        float distance=length(vec2(along,across));
        float travel=front_shape.x*pow(distance,front_shape.y)*variation;
        // 饱和只作用于传播时间，各起点的启动时刻不会被 soft-min 提前。
        travel=front_clock.x*tanh(travel/front_clock.x);
        first=min(first,site.z+travel);
    }
    return first;
}
float remaining(vec2 coord){
    float progress=native_progress*rate*front_clock.z-origin_preroll;
    return clamp(1.-(progress-arrival(coord/native_card,coord))/front_clock.y,0.,1.);
}
'''
OLD_REMAINING='''float remaining(vec2 coord){
    float frequency=205./(native_card.x*native_card.y)+.002;
    float threshold=.2+.8*erosionNoise(coord*frequency);
    return clamp((threshold-native_progress*rate)/.15,0.,1.);
}'''


class HarmonyOriginsRenderer(HarmonyCoreRenderer):
    mask_source=MASK_FRAGMENT.replace(OLD_REMAINING,FRONT)
    # 明确起点之后，原生按寿命 20% 缓慢长大的尺寸包络会在表面退出后留下空洞。
    # 仅将初始生长与同一交接窗口配合；粒子位置、原生寿命和后段收缩公式不变。
    compute_source=COMPUTE.replace('uniform float dt;','uniform float dt,origin_growth_seconds;').replace(
        'clamp(life/.2,0.,1.)','clamp(t/origin_growth_seconds,0.,1.)')

    def __init__(self,*args,origins,origin_rules=None,**kwargs):
        if not 1<=len(origins)<=8:raise ValueError('一次动画需要 1–8 个消逝起点')
        self.origins=[]
        for o in origins:
            item=dict(o) if isinstance(o,dict) else dict(x=o.x,y=o.y,start=o.start)
            if not all(math.isfinite(float(item[k])) for k in ('x','y','start')):
                raise ValueError('起点坐标和启动时刻必须为有限数值')
            if not -.05<=item['x']<=1.05 or not -.05<=item['y']<=1.05 or not -.10<=item['start']<=.30:
                raise ValueError('起点须位于控件附近，启动进度须在 -0.10–0.30')
            self.origins.append(dict(x=float(item['x']),y=float(item['y']),
                                     start=float(item['start'])))
        self.origin_rules=origin_rules or OriginRules()
        self.preroll=max(0.,-min(o['start'] for o in self.origins))
        q=self.origin_rules
        if min(q.duration,q.exponent,q.edge_depth,q.travel_limit,q.surface_tail,q.growth_progress)<=0:
            raise ValueError('共同传播参数必须为正')
        # 核心对照只有同时存在 direction 和 touch_gap 才旋转风；此版本方向始终显式生效。
        if kwargs.get('direction') is not None and kwargs.get('touch_gap') is None:
            kwargs['touch_gap']=0.
        super().__init__(*args,**kwargs)
        values=np.zeros((8,4),np.float32)
        for i,o in enumerate(self.origins):
            values[i,:3]=[o['x'],o['y'],o['start']]
        self.mask_program['origin_count']=len(origins)
        self.mask_program['origin_sites'].write(values.tobytes())
        self.mask_program['front_shape']=(q.duration,q.exponent,q.edge_speed,q.edge_depth)
        self.mask_program['front_clock']=(q.travel_limit,q.surface_tail,
                                         self.config.duration/(self.config.stop_time*self.config.rate),q.noise)
        self.compute['origin_growth_seconds']=q.growth_progress*self.config.stop_time
        self.mask_program['origin_preroll']=self.preroll

    def timeline_seconds(self,progress):
        return (progress+self.preroll)*self.config.stop_time

    def describe(self):
        result=super().describe()
        result.update(model='harmony-origins',origins=self.origins,origin_rules=asdict(self.origin_rules),preroll=self.preroll,
                      source='观测起点／启动时刻与显式风向，华为运动核心与共同交接尺寸；初始条件核对版',
                      limitations=['原片起点坐标和启动时刻仍有录像测量误差',
                                   '起点传播为共同扩展规则，并非镜像恢复参数',
                                   '卷曲、材质和完整动画仍未通过视觉验收'])
        return result
