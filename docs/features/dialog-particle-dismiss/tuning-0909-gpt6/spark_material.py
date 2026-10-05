"""圆形亮核颗粒外观（桌面研究候选）。

运动、释放、表面交接与现有完整继承链完全相同；只改变已释放运动颗粒的绘制：
- 不再细分为 9 个亚像素子片，不再叠加按屏幕位置学习的覆盖、材质与年龄覆盖修正；
- 颗粒画成以材料中心为圆心的小圆点：实心内核 + 柔和边缘，颜色取该材料格点的源色；
- 显示数量与尺寸由共同规则给出，所有布局相同（不随起点布局差异 g 改变）。
依据：原片颗粒对背景的峰值更高、近白颗粒更多（深色背景），在明亮背景上则更暗，
说明原片颗粒接近不透明；当前模型的半透明碎片与背景混成灰雾。
"""
from dataclasses import dataclass, asdict
import json
import numpy as np
from scatter_material import ScatterRenderer


@dataclass(frozen=True)
class SparkRules:
    keep: float = .6        # 显示的运动颗粒份额（按材料随机量抽取，与运动无关）
    size: float = 2.1       # 圆点直径（场景像素）
    core: float = .45       # 实心内核占半径的比例；其外线性渐隐到边缘
    gain: float = 1.0       # 运动颗粒亮度倍率（线性色彩空间）
    grow: float = .35       # 出生后 0～0.06 内由原格点大小过渡到圆点，避免释放瞬间跳变
    variance: float = .18   # 直径的对数正态离散（按材料随机量，固定不闪烁）
    # Z 向抬升（实验）：高度以短边为单位；相机在卡片中心正上方 camera 个短边处做透视投影。
    lift: float = 0.        # 最大抬升高度
    lift_time: float = .12  # 出生后抬升到最大高度所需的内部时间
    lift_radius: float = 0. # >0 时按到最近起点的距离做高斯衰减（短边为单位），形成以起点为中心的鼓包
    camera: float = 3.      # 相机到卡片平面的距离
    lift_light: float = 0.  # 抬升带来的亮度增益（按抬升比例）
    jitter: float = 0.      # 圆点中心相对网格位置的固定随机偏移（高斯，单位为网格间距）；颜色在偏移后的位置取样
    swell: float = 0.       # 出生后短时放大的幅度（swell_age 处达到峰值）
    swell_age: float = .06  # 放大达到峰值的年龄
    shrink: float = 0.      # 之后随年龄缩小的比例（shrink_start～shrink_end 之间平滑过渡）
    shrink_start: float = .12
    shrink_end: float = .36
    # 新生提亮：颜色向白色靠拢 glow×包络；包络在 glow_rise 内升起，此后按 glow_decay 指数衰减。
    glow: float = 0.
    glow_rise: float = .008
    glow_decay: float = .08
    glow_mul: float = 0.    # 同一包络下的乘性提亮（线性色彩空间，保持色相）
    # 随年龄变淡：不透明度在 fade_start～fade_end 之间按 smoothstep 降低 fade 的比例（寿命末端的淡出另算）。
    fade: float = 0.
    fade_start: float = .05
    fade_end: float = .35
    # 1：保留已校准的共同密度修正（输运密度与分年龄覆盖），只把它换算成颗粒数量与圆点面积，不再降低不透明度。
    learned: float = 0.
    # 逐颗亮度差异：亮度乘 1−shade×(1−u)，u 为固定随机量（颗粒朝向不同）；twinkle 为随年龄转动的亮度起伏幅度。
    shade: float = 0.
    twinkle: float = 0.
    twinkle_rate: float = 14.
    # 逐颗亮度的对数正态离散（按颗粒固定、均值保持 1）：少数颗粒明显更亮、少数更暗；0 为关闭。
    sparkle: float = 0.
    # 颗粒颜色朝自身亮度去饱和的比例（线性色彩空间）；0 为保持源色。
    desat: float = 0.
    # 随年龄升起的提亮：在 sRGB 编码值上朝白色混合 screen×smoothstep(0, screen_rise, age)（相当于屏幕叠加），
    # 暗色被抬起的幅度按 sRGB 计，不会像线性空间混合那样把黑色直接推成中灰。
    screen: float = 0.
    screen_rise: float = .12
    # 朝白混合的起始年龄：年龄在 screen_start～screen_rise 之间由 0 升到 screen（新生颗粒先保持源色）。
    screen_start: float = 0.
    # 随年龄压暗颜色（不改不透明度）：颜色乘 1−darken×smoothstep(darken_start, darken_end, 年龄)。
    # 深色背景上与淡出等效，浅色背景上颗粒反而更显眼，用来区分「变暗」与「变透明」。
    darken: float = 0.
    darken_start: float = .12
    darken_end: float = .36
    # 细粒材质场的密度通道（默认碎片外观按它降低光学深度）换算成逐颗剔除：保留比例 exp(cull×min(密度,0)×出生斜坡)，
    # 按每颗固定随机量比较，越过门限时在 ±0.08 内淡出；0 为关闭（此时材质场整张清零，与原圆点外观相同）。
    cull: float = 0.
    # 新生提亮只在释放前沿朝下风推进处生效的比例：提亮乘 mix(1, max(前沿方向·风向, 0), glow_dir)；0 为各方向相同。
    glow_dir: float = 0.
    # 出生后逐渐显现：不透明度乘 smoothstep(0, fadein, 年龄)。表面照常淡出，释放区先露出背景变暗，颗粒随后显出成斑点。0 为关闭。
    fadein: float = 0.


def load_spark(path):
    d = json.loads(path.read_text('utf-8'))
    return SparkRules(**d.get('rules', d))


def _replace(source, old, new):
    assert old in source, old
    return source.replace(old, new, 1)


class SparkRenderer(ScatterRenderer):
    # 圆点外观不再细分子片：每个母片只画一次，绘制实例数回到原来的十分之一。
    vertex_source = _replace(ScatterRenderer.vertex_source, 'int parent_instance=material_pass==1?gl_InstanceID/10:gl_InstanceID;', 'int parent_instance=gl_InstanceID;')
    vertex_source = _replace(vertex_source, 'int child=material_pass==1?gl_InstanceID%10:0;',
                             # 子片分支不再执行；保留对 fission_spread 的引用，避免统一变量被编译器删去后父类设值失败。
                             'int child=material_pass==99&&fission_spread<0.?1:0;')
    vertex_source = vertex_source.replace('void main(){',
        'uniform float spark_size,spark_grow,spark_variance,spark_lift,spark_lift_time,spark_lift_radius,spark_camera,spark_jitter;\n'
        'uniform float spark_swell,spark_swell_age,spark_shrink,spark_shrink_start,spark_shrink_end,spark_learned;\n'
        'uniform vec3 spark_sites[6];\nuniform int spark_site_count;\nflat out float spark_on;\nflat out float spark_raise;\nflat out float spark_front;\nvoid main(){', 1)
    vertex_source = _replace(vertex_source, 'vec2 vertex=move+(rot*d)*scale*(1.+z/1000.);', '''vec2 vertex=move+(rot*d)*scale*(1.+z/1000.);
    spark_on=0.;spark_raise=0.;spark_front=max(dot(normalize(m.physical.xy+vec2(1e-6)),wind),0.);
    if(material_pass==1 && child==0){
        // 圆点以材料中心为圆心；出生初段从原格点形状连续过渡，随后保持固定直径。
        float sparkle=exp(spark_variance*sqrt(-2.*log(max(m.random.y,.004)))*cos(m.random.z*6.28318)-.5*spark_variance*spark_variance);
        float settle=smoothstep(0.,max(spark_grow,.001)*.06/.35,age);
        // Z 向抬升：高度随材料年龄上升；可按到最近起点的距离衰减。透视以卡片中心为投影中心。
        float dome=1.;
        if(spark_lift_radius>0.){
            float nearest=1e9;
            for(int k=0;k<spark_site_count;k++)nearest=min(nearest,length((m.src.xy-spark_sites[k].xy*card)/span));
            dome=exp(-nearest*nearest/(2.*spark_lift_radius*spark_lift_radius));
        }
        float height=spark_lift*dome*smoothstep(0.,max(spark_lift_time,.001),age)*span;
        float magnify=spark_camera*span/max(spark_camera*span-height,.25*spark_camera*span);
        // 固定随机偏移：打破规则网格，形成自然的疏密与小簇；运动仍沿用该材料的状态。
        uint jitter_key=uint(parent_instance)*0x27d4eb2du^0x165667b1u;
        vec2 gauss=sqrt(-2.*log(max(grid_unit(jitter_key),1e-4)))*vec2(cos(6.2831853*grid_unit(jitter_key^0x9e3779b9u)),sin(6.2831853*grid_unit(jitter_key^0x9e3779b9u)));
        vec2 offset=gauss*spark_jitter*cell;
        vec2 center=move+offset-card*.5;
        vec2 lifted=card*.5+center*magnify;
        // 年龄尺寸：出生后短时放大，随后缩小；与运动无关，只改变颗粒的可见面积。
        float swell_age=max(spark_swell_age,.001);
        float bump=smoothstep(0.,swell_age,age)*(1.-smoothstep(swell_age,swell_age*3.,age));
        float age_size=(1.+spark_swell*bump)*(1.-spark_shrink*smoothstep(spark_shrink_start,max(spark_shrink_end,spark_shrink_start+.001),age));
        // 保留共同密度修正时，超出数量上限的部分换成圆点面积（与原覆盖模块同一换算）。
        float spark_area=mix(1.,exp(.5*(density_out*coverage_area-count_shift)),spark_learned);
        vec2 dot_vertex=lifted+(c-.5)*spark_size*sparkle*magnify*age_size*spark_area;
        vertex=mix(vertex,dot_vertex,settle);
        spark_on=settle;
        spark_raise=height/max(spark_lift*span,1e-6);
        uv=mix(uv,clamp(m.src.xy+offset,vec2(0.),card)/card,settle);
    }''')
    fragment_source = ScatterRenderer.fragment_source.replace('void main(){',
        'uniform float spark_core,spark_gain,spark_lift_light,spark_glow,spark_glow_rise,spark_glow_decay,spark_glow_mul;\n'
        'uniform float spark_fade,spark_fade_start,spark_fade_end,spark_shade,spark_twinkle,spark_twinkle_rate,spark_sparkle,spark_desat,spark_screen,spark_screen_rise,spark_screen_start,spark_darken,spark_darken_start,spark_darken_end,spark_cull,spark_fadein;\n'
        'vec3 spark_to_srgb(vec3 c){return mix(c*12.92,1.055*pow(max(c,0.),vec3(1./2.4))-.055,step(vec3(.0031308),c));}\n'
        'flat in float spark_on;\nflat in float spark_raise;\nflat in float spark_front;\nuniform float spark_glow_dir;\nvoid main(){', 1)
    # 保留材质场密度通道时，它对圆点只作剔除（见 cull），不再按光学深度改圆点边缘的透明度。
    fragment_source = _replace(fragment_source, '(fission_density+scatter_material.x)*smoothstep', '(fission_density+scatter_material.x*(1.-spark_on))*smoothstep')
    fragment_source = _replace(fragment_source, 'float shape=mix(1.,cut,shape_out*.92);', '''float shape=mix(1.,cut,shape_out*.92);
    float spark_r=length(local_uv-.5)*2.;
    shape=mix(shape,1.-smoothstep(spark_core,1.,spark_r),spark_on);''')
    fragment_source = _replace(fragment_source, 'alpha*=mix(1.,.92,smoothstep(.018,.12,age));',
                               'alpha*=mix(mix(1.,.92,smoothstep(.018,.12,age)),1.,spark_on);'
                               '\n    alpha*=1.-spark_fade*spark_on*smoothstep(spark_fade_start,max(spark_fade_end,spark_fade_start+.001),age);'
                               '\n    alpha*=mix(1.,smoothstep(0.,max(spark_fadein,1e-4),age),spark_on*step(1e-4,spark_fadein));'
                               '\n    float spark_cut=fract(random_out.z*11.317+random_out.x*5.731);'
                               '\n    float spark_keep_level=exp(spark_cull*min(scatter_material.x,0.)*smoothstep(.02,.10,age));'
                               '\n    alpha*=mix(1.,smoothstep(spark_cut-.08,spark_cut+.08,spark_keep_level),spark_on*step(.0001,spark_cull));')
    fragment_source = _replace(fragment_source, 'vec3 color=linear(src.rgb);',
        'vec3 color=linear(src.rgb);\n    color*=mix(1.,spark_gain*(1.+spark_lift_light*spark_raise),spark_on);'
        '\n    float spark_fresh=smoothstep(0.,max(spark_glow_rise,.0005),age)*exp(-age/max(spark_glow_decay,.001));'
        '\n    spark_fresh*=mix(1.,spark_front,spark_glow_dir);'
        '\n    color*=1.+spark_glow_mul*spark_fresh*spark_on;'
        '\n    float spark_turn=.5+.5*sin(age*spark_twinkle_rate+6.2831853*random_out.x);'
        '\n    color*=1.-spark_on*(spark_shade*(1.-fract(random_out.y*7.31+random_out.w*3.17))+spark_twinkle*spark_turn);'
        '\n    float spark_u1=max(fract(random_out.x*9.173+random_out.z*5.317),1e-4),spark_u2=fract(random_out.w*7.411+random_out.y*3.913);'
        '\n    float spark_g=sqrt(-2.*log(spark_u1))*cos(6.2831853*spark_u2);'
        '\n    color*=mix(1.,exp(spark_sparkle*spark_g-.5*spark_sparkle*spark_sparkle),spark_on);'
        '\n    color=mix(color,vec3(dot(color,vec3(.2126,.7152,.0722))),spark_desat*spark_on);'
        '\n    color*=1.-spark_darken*spark_on*smoothstep(spark_darken_start,max(spark_darken_end,spark_darken_start+.001),age);'
        '\n    float spark_w=spark_screen*spark_on*smoothstep(spark_screen_start,max(spark_screen_rise,spark_screen_start+.001),age);'
        '\n    if(spark_w>0.){vec3 s=clamp(spark_to_srgb(max(color,vec3(0.))),0.,1.);s=s+spark_w*(1.-s);color=linear(s);}'
        '\n    color=mix(color,vec3(1.),clamp(spark_glow*spark_fresh*spark_on,0.,1.));')

    def __init__(self, *args, spark=SparkRules(), **kwargs):
        super().__init__(*args, **kwargs)
        # 去掉按屏幕位置学习的覆盖与材质修正；运动（输运位移通道）保持不变。
        # learned=1 时保留输运密度与分年龄覆盖（只作数量与面积），细粒材质的透明度／反射／色度仍去掉。
        self.program['fission_strength'] = 0.
        from particle_fission import FissionArray
        if isinstance(self.vao, FissionArray): self.vao = self.vao.vao
        # cull>0 时保留细粒材质场的密度通道（第 0 通道），只清零中性反射与色度。
        targets = [(self.scatter_tex, (0,) if spark.cull > 0 else None)]
        if spark.learned <= 0: targets += [(self.cohort_tex, None), (self.transport_tex, (0, 1))]
        for tex, keep in targets:
            data = np.frombuffer(tex.read(), np.float32).reshape(-1, 3).copy()
            if keep is None: data[:] = 0.
            elif keep == (0,): data[:, 1:] = 0.
            else: data[:, 2] = 0.
            tex.write(data.tobytes())
        self.program['coverage_footprint'] = 1.
        self.program['conditional_size'] = 1.
        self.program['conditional_light'] = 1.
        self.apply_spark(spark)

    def apply_spark(self, rules):
        values = np.array(list(asdict(rules).values()), np.float64)
        if not np.isfinite(values).all() or not 0 < rules.keep <= 1 or rules.size <= 0 or not 0 <= rules.core < 1 \
                or rules.gain <= 0 or rules.grow < 0 or rules.variance < 0:
            raise ValueError('颗粒外观规则超出范围')
        self.spark_rules = rules
        self.program['conditional_keep'] = rules.keep
        self.program['spark_size'] = rules.size; self.program['spark_core'] = rules.core
        self.program['spark_gain'] = rules.gain; self.program['spark_grow'] = rules.grow
        self.program['spark_variance'] = rules.variance
        # 未启用的实验项仍写入极小正值，避免编译器删去统一变量后设值失败。
        for key in ['lift', 'lift_time', 'lift_radius', 'camera', 'lift_light', 'jitter',
                    'swell', 'swell_age', 'shrink', 'shrink_start', 'shrink_end', 'glow', 'glow_rise', 'glow_decay', 'glow_mul',
                    'fade', 'fade_start', 'fade_end', 'learned', 'shade', 'twinkle', 'twinkle_rate', 'sparkle', 'desat', 'screen', 'screen_rise',
                    'screen_start', 'darken', 'darken_start', 'darken_end', 'cull', 'glow_dir', 'fadein']:
            name = 'spark_' + key
            if name in self.program: self.program[name] = getattr(rules, key)
        from learned_release import canonical_sites
        sites = self.origin_sites if self.origin_sites is not None else canonical_sites(self.cw, self.ch, self.direction)
        if 'spark_sites' in self.program:
            values = [(s.x, s.y, s.start) for s in sites][:6]
            values += [(0., 0., 0.)] * (6 - len(values))
            self.program['spark_sites'] = values
            self.program['spark_site_count'] = min(len(sites), 6)
