"""接手诊断用的逐层消融变体；只在研究渲染中使用，不改默认配置或共享资源。

变体名用逗号组合，例如 `no-transport-density,no-cohorts`：
- no-transport-xy / no-transport-density：共享输运场的位移 / 密度通道置零；
- no-cohorts / no-scatter / no-coherence：对应共同函数置零（细分本身保留）；
- peel=<k>：出生早段的法向剥离速度乘 k（k=-1 即反向）；
- guide=<k>：共同引导速度乘 k（在条件缩放之后）；
- curl=<k>：两处附加解析卷动乘 k；
- birth-ease=<a>：新生材料的整体目标速度按 smoothstep(0,a,age) 缓起（a 为内部时间）。
"""
from pathlib import Path
import sys
import numpy as np

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from check_cloud_evolution import configuration
import shared_transport


def parse(spec):
    items = {}
    for part in filter(None, (spec or '').split(',')):
        key, _, value = part.partition('=')
        items[key] = float(value) if value else True
    return items


def build(root, spec):
    """返回 (渲染类, 构造参数)；spec 为空时与默认完整链一致。

    span-ref=<像素>：材料网格与圆点直径按「卡片短边 / span-ref」缩放，使颗粒相对卡片的大小在各段一致
    （各段录像的缩放比例不同；原版钢铁侠短边 468）。
    """
    v = parse(spec)
    if 'oracle-T' in v:
        # 只用于诊断（违反「不得把逐参考观测场作为运行数据」，绝不作为候选）：把释放场换成原片实测的逐块解除时刻
        # （release_integrity.Probe.huawei，10 像素块），减去它与学习预测在有效块上的中位差使整体时序口径不变，
        # 无效块（未知、从未解除）沿用学习预测；oracle-T=k 为替换比例。用来回答「释放图的细结构全对时，可见结构能对上多少」。
        kk = float(v['oracle-T'])
        cls, options = build(root, ','.join(p for p in spec.split(',') if not p.startswith('oracle-T')))
        inner_or = cls

        def init_or(self, name='ironman', *a, **kw):
            import conditional_material as _cm
            from scipy.ndimage import map_coordinates as _mc, gaussian_filter as _gf
            from release_integrity import Probe as _Probe, B as _B
            pr = _Probe(name); To = pr.huawei().astype(np.float64)
            ok = pr.valid & np.isfinite(To) & (To < .99)
            orig = _cm.predict

            def blend(f, nx, ny, width, height):
                yy, xx = np.mgrid[:ny, :nx]
                bu = ((xx + .5) * width / nx) / _B - .5; bv = ((yy + .5) * height / ny) / _B - .5
                bx = np.clip(np.round(bu).astype(int), 0, To.shape[1] - 1); by = np.clip(np.round(bv).astype(int), 0, To.shape[0] - 1)
                fb = f[np.clip(((by + .5) * _B * ny / height).astype(int), 0, ny - 1), np.clip(((bx + .5) * _B * nx / width).astype(int), 0, nx - 1)]
                # 有效块上原片与预测的中位差
                fs = _mc(f, [((np.arange(To.shape[0])[:, None] + .5) * _B) * ny / height - .5 + 0 * np.arange(To.shape[1])[None, :],
                             0 * np.arange(To.shape[0])[:, None] + ((np.arange(To.shape[1])[None, :] + .5) * _B) * nx / width - .5], order=1, mode='nearest')
                off = float(np.median((To - fs)[ok]))
                Tf = np.where(ok, To - off, fs)
                Tf = _gf(Tf, .6)
                w8 = _gf(ok.astype(np.float64), .6)
                fo = _mc(Tf, [bv, bu], order=1, mode='nearest'); wo = _mc(w8, [bv, bu], order=1, mode='nearest')
                return (f + kk * np.clip(wo, 0, 1) * (fo - f)).astype(np.float32)

            def pred(nx, ny, width, height, direction, seed, sites, weights, panel=0., spatial=True):
                f = orig(nx, ny, width, height, direction, seed, sites, weights, panel, spatial)
                if not spatial: return f
                return blend(f, nx, ny, width, height)
            _cm.predict = pred; _cm._oracle_hook = blend
            try:
                inner_or.__init__(self, name, *a, **kw)
            finally:
                _cm.predict = orig; _cm._oracle_hook = None
        return type(f'Oracle_{inner_or.__name__}', (inner_or,), {'__init__': init_or}), options
    if 'oracle-T2' in v:
        # 只用于诊断（违反「不得把逐参考观测场作为运行数据」，绝不作为候选）：释放时刻图加一张逐段修正图
        # （analysis/release-oracle2-v<版本>.json，卡片坐标、8 像素块，scratchpad release_oracle_fit.py 迭代得到），
        # 使模型「卡面消失时刻」（observe 的 surface 在 8×8 块里首次低于开始时的一半）与原片同口径一致。
        # 用来回答「各处卡面消失的先后全对时，看得见的颗粒群能对上多少」。经 _oracle_hook 作用于参数化释放的输出。
        import json as _json2
        tab2 = _json2.loads((HERE / 'analysis' / f'release-oracle2-v{int(v["oracle-T2"])}.json').read_text('utf-8'))
        cls, options = build(root, ','.join(p for p in spec.split(',') if not p.startswith('oracle-T2')))
        inner_o2 = cls

        def init_o2(self, name='ironman', *a, **kw):
            import conditional_material as _cm
            from scipy.ndimage import map_coordinates as _mc
            ent = tab2[name]; C = np.asarray(ent['w'], np.float64).reshape(ent['ny'], ent['nx']); Bk = float(ent['B'])

            def blend2(f, nx, ny, width, height):
                yy, xx = np.mgrid[:ny, :nx]
                bu = ((xx + .5) * width / nx) / Bk - .5; bv = ((yy + .5) * height / ny) / Bk - .5
                return (f + _mc(C, [bv, bu], order=1, mode='nearest')).astype(np.float32)
            prev = getattr(_cm, '_oracle_hook', None)
            _cm._oracle_hook = blend2
            try:
                inner_o2.__init__(self, name, *a, **kw)
            finally:
                _cm._oracle_hook = prev
        return type(f'Oracle2_{inner_o2.__name__}', (inner_o2,), {'__init__': init_o2}), options
    if 'param-release' in v:
        # 参数化释放前沿（共用六个参数 + 逐段起点输入，analysis/release-param-v<版本>.json，由 scratchpad release_param_fit.py 拟合）
        # 代替以起点为输入、在十段上训练的学习释放网络。T = softmin_i[t_i + tf·(ρ_i/r_i)^e] + aw·⟨x − 中心, ŵ⟩ + c0，短边单位；
        # param-release-shift=s 为整体时刻平移（拟合口径是原片的表面完整度，模型自己的口径可能整体偏早或偏晚）。
        import json as _json
        prm = _json.loads((HERE / 'analysis' / f'release-param-v{int(v["param-release"])}.json').read_text('utf-8'))
        shift = float(v.get('param-release-shift', 0.)); preroll = float(v.get('pre-roll', 0.))
        cls, options = build(root, ','.join(p for p in spec.split(',') if not p.startswith('param-release')))
        inner_pr = cls

        def init_pr(self, name='ironman', *a, **kw):
            import conditional_material as _cm, math as _math
            self._param_release = (prm, name)
            sh = prm['shared']; sites = prm['sites'][name]; th = _math.radians(prm['directions'][name])
            wx, wy = _math.cos(th), -_math.sin(th)
            orig = _cm.predict

            def pred(nx, ny, width, height, direction, seed, s_, weights, panel=0., spatial=True):
                if not spatial: return orig(nx, ny, width, height, direction, seed, s_, weights, panel, spatial)
                span = min(width, height)
                yy, xx = np.mgrid[:ny, :nx].astype(np.float64)
                u = (xx + .5) / nx * width / span; vv = (yy + .5) / ny * height / span
                e = min(max(sh['e'], .4), 3.); tf = max(sh['tf'], .05); k = min(max(_math.exp(sh['lk']), 1.), 30.)
                pots = []
                for q in sites:
                    sx, sy, st, rate = q[:4]
                    dx = u - sx * width / span; dy = vv - sy * height / span
                    if len(q) > 4 and q[4] > 0:
                        # 线段起点（release_param_seg.py）：过起点、平行于最近的一条边（或按第 6 个分量：0 水平 / 1 竖直），
                        # 长度 q[4] × 该边边长；到线段的距离代替到点的距离，顺风各向异性按线段上最近点到该处的方向算。
                        Wn, Hn = width / span, height / span
                        if len(q) > 5: vert = q[5] > .5
                        else:
                            X, Y = min(max(sx, 0.), 1.) * Wn, min(max(sy, 0.), 1.) * Hn
                            vert = int(np.argmin([Y, Wn - X, Hn - Y, X])) in (1, 3)
                        half = q[4] * (Hn if vert else Wn) / 2
                        if vert: dy = np.sign(dy) * np.maximum(np.abs(dy) - half, 0.)
                        else: dx = np.sign(dx) * np.maximum(np.abs(dx) - half, 0.)
                    r = np.sqrt(dx * dx + dy * dy + 1e-6)
                    rho = r * np.maximum(1 + sh['af'] * (dx * wx + dy * wy) / r, .05)
                    pots.append(np.maximum(st + tf * np.maximum(rho / rate, 1e-4) ** e, 1e-3))
                P = np.stack(pots); m = P.min(0)
                T = m * np.power(np.sum(np.power(P / m, -k), 0), -1 / k)
                bk = sh.get('bulk', prm.get('bulk_scene', {}).get(name))
                if bk is not None:
                    # 整体溶解时刻：前沿到 bk 还没扫到的区域在 bk 附近整体解除（与前沿同一 softmin 强度）。共用 bulk 优先；
                    # 否则用逐段的 bulk_scene（原片可见的释放完成时刻，与起点一样从原片推断）。
                    bk = min(max(float(bk), .2), .84); mm = np.minimum(T, bk)
                    T = mm * np.power(np.power(T / mm, -k) + np.power(bk / mm, -k), -1 / k)
                T = T + sh['aw'] * ((u - width / span / 2) * wx + (vv - height / span / 2) * wy) + sh['c0'] + shift
                # pre-roll=Δ：允许在录像进度 0 之前就释放（原片第一帧已有颗粒飞出卡片），释放时刻下限放到 −Δ，
                # 整条时间轴后移 Δ（渲染进度 p 时取模拟时刻 p + Δ，见下方 render）。
                out_T = (np.clip(T, .002 - preroll, .84) + preroll).astype(np.float32)
                # oracle-T（只用于诊断）与参数化释放同用时，由它在这里接管（参数化释放不调用被它包住的学习预测）。
                hook = getattr(_cm, '_oracle_hook', None)
                return hook(out_T, nx, ny, width, height) if hook is not None else out_T
            _cm.predict = pred
            try:
                inner_pr.__init__(self, name, *a, **kw)
            finally:
                _cm.predict = orig
        attrs_pr = {'__init__': init_pr}
        if preroll > 0:
            def render_pr(self, time, diagnostic=0):
                return inner_pr.render(self, time + preroll, diagnostic)
            attrs_pr['render'] = render_pr
        return type(f'ParamRelease_{inner_pr.__name__}', (inner_pr,), attrs_pr), options
    if 'pseed' in v:
        # 只重抽颗粒层面的随机量：materials() 里直接以场景种子取的两次逐颗随机（寿命、尺寸、出生时刻抖动、剥离与输运的逐颗随机、副片选择）
        # 改用「场景种子 ^ pseed」，释放场、释放补丁、模板形变等以「种子 ^ 常数」取的随机不变。释放顺序完全相同、颗粒细结构换一次，
        # 两次渲染之间的逐帧对照即「释放全对、颗粒细结构不可知」时的上限。只用于估计上限，不作为候选。
        salt = int(v['pseed'])
        cls, options = build(root, ','.join(p for p in spec.split(',') if not p.startswith('pseed')))
        inner_ps = cls

        def init_ps(self, name='ironman', *a, **kw):
            import json as _json
            import unified_model as _um
            meta = _json.loads((HERE / 'assets' / name / 'scene.json').read_text('utf-8'))
            sd = int(kw['seed']) if kw.get('seed') is not None else int(meta['seed'])
            orig = _um.random_values

            def rv(count, seed):
                if int(seed) == sd and count >= 1000:
                    seed = sd ^ salt
                return orig(count, seed)
            _um.random_values = rv
            try:
                inner_ps.__init__(self, name, *a, **kw)
            finally:
                _um.random_values = orig
        return type(f'PSeed_{inner_ps.__name__}', (inner_ps,), {'__init__': init_ps}), options
    if 'fg-fix' in v:
        # fg-fix=<版本>：有 assets/<段>/foreground-fix-v<版本>.png 的段改用修好的前景（源图里残留的界面元素，如旧蜘蛛侠左下角的灰色触点圆，
        # 原片动画期间并不存在）；其余段不变。原 foreground.png 不改，缓存身份随变体描述变化。
        ver = int(v['fg-fix'])
        cls, options = build(root, ','.join(p for p in spec.split(',') if not p.startswith('fg-fix')))
        inner_fg = cls

        def init_fg(self, name='ironman', *a, **kw):
            import PIL.Image as _PI
            fixed = HERE / 'assets' / name / f'foreground-fix-v{ver}.png'
            if not fixed.is_file():
                return inner_fg.__init__(self, name, *a, **kw)
            orig_open = _PI.open

            def patched_open(fp, *aa, **kk):
                q = Path(fp) if isinstance(fp, (str, Path)) else None
                if q is not None and q.name == 'foreground.png' and q.parent.name == name:
                    fp = fixed
                return orig_open(fp, *aa, **kk)
            _PI.open = patched_open
            try:
                inner_fg.__init__(self, name, *a, **kw)
            finally:
                _PI.open = orig_open
        return type(f'FgFix_{inner_fg.__name__}', (inner_fg,), {'__init__': init_fg}), options
    if 'touch-table' in v:
        # 触点远近输入按表设置（analysis/touch-table-v<版本>.json，由原片可见颗粒速度估计，所有段同一估计方法），缺省 0.5。
        # 运动幅度 = 0.55 + 0.9 × 触点远近；学习输运位移按运动幅度之比同比缩放（同 transport-touch），避免被它推着逆风后退。
        import json as _json
        ttab = _json.loads((HERE / 'analysis' / f'touch-table-v{int(v["touch-table"])}.json').read_text('utf-8'))
        cls, options = build(root, ','.join(p for p in spec.split(',') if not p.startswith('touch-table')))
        inner_touch = cls

        def init_touch_table(self, name='ironman', *a, **kw):
            inner_touch.__init__(self, name, *a, **kw)
            value = ttab.get(name)
            if isinstance(value, (int, float)):
                before = .55 + .9 * self.touch_strength
                self.touch_strength = float(value)
                self.compute['touch_strength'] = self.touch_strength
                self.pressure_grid.splat['touch_strength'] = self.touch_strength
                tgt = self.compute if 'transport_xy_gain' in self.compute else (self.program if 'transport_xy_gain' in self.program else None)
                if tgt is not None:
                    tgt['transport_xy_gain'] = tgt['transport_xy_gain'].value * (.55 + .9 * self.touch_strength) / before
                self.reset()
        return type(f'TouchTab_{inner_touch.__name__}', (inner_touch,), {'__init__': init_touch_table}), options
    if 'fan-table' in v:
        # 触点距离（终点输入）按表逐段设置（analysis/fan-table-v<版本>.json，由原片可见颗粒光流方向拟合，所有段同一方法）；
        # 表里没有的段取 fan 的值（缺省 1000，即平行风向）。
        import json as _json
        ftab = _json.loads((HERE / 'analysis' / f'fan-table-v{int(v["fan-table"])}.json').read_text('utf-8'))
        rest = [p for p in spec.split(',') if not p.startswith('fan-table')]
        if not any(p.startswith('fan=') for p in rest): rest.append('fan=1000')
        cls, options = build(root, ','.join(rest))
        inner_fan = cls

        def init_fan_table(self, name='ironman', *a, **kw):
            inner_fan.__init__(self, name, *a, **kw)
            value = ftab.get(name)
            if isinstance(value, (int, float)):
                self.compute['fan_gap'] = float(value)
                self.reset()
        return type(f'FanTab_{inner_fan.__name__}', (inner_fan,), {'__init__': init_fan_table}), options
    if v.get('sites-refined'):
        # 起点改用 v2_inputs.SITES（按原片解除时刻图细化过的起点：位置与启动时刻），缺省渲染用 OBSERVED_SITES（观察标注的起点）。
        # 起点属于逐段的输入设置，不是按段调的参数。
        from v2_inputs import SITES as _REFINED
        from dissolve_v2 import Site as _Site
        cls, options = build(root, ','.join(p for p in spec.split(',') if p != 'sites-refined'))
        inner_sites = cls

        def init_sites(self, name='ironman', *a, **kw):
            if name in _REFINED and _REFINED[name]:
                kw['sites'] = [_Site(*q) for q in _REFINED[name]]
            inner_sites.__init__(self, name, *a, **kw)
        return type(f'Sites_{inner_sites.__name__}', (inner_sites,), {'__init__': init_sites}), options
    if 'dir-table' in v:
        # 终点方向输入按表设置（analysis/measured-directions-v<版本>.json，由原片可见颗粒的主流向估计，所有段同一估计方法）；
        # 渲染器缺省用 scene.json 的方向。表发布后不改，更新时另建版本号，保证缓存身份与内容一一对应。
        import json as _json
        table = _json.loads((HERE / 'analysis' / f'measured-directions-v{int(v["dir-table"])}.json').read_text('utf-8'))
        cls, options = build(root, ','.join(p for p in spec.split(',') if not p.startswith('dir-table')))
        inner_dir = cls

        def init_dir(self, name='ironman', *a, **kw):
            if kw.get('direction') is None and isinstance(table.get(name), (int, float)):
                # dir-add=度：只用于敏感性诊断，所有段的方向输入整体加一个角度。
                kw['direction'] = float(table[name]) + float(v.get('dir-add', 0.))
            inner_dir.__init__(self, name, *a, **kw)
        attrs_dir = {'__init__': init_dir}
        if v.get('dir-flow-only'):
            # 方向只作用于颗粒运动（风、模板朝向、输运层等）；材料与释放时刻图仍按 scene.json 的方向构建，
            # 因为按起点预测释放的模型是在那组方向下标定的（改方向会改变释放顺序）。
            def material_builder(self, width, height, foreground, direction, seed, cell_px=None):
                return inner_dir.material_builder(self, width, height, foreground, float(self.meta['direction']), seed, cell_px)
            attrs_dir['material_builder'] = material_builder
        return type(f'Dir_{inner_dir.__name__}', (inner_dir,), attrs_dir), options
    if v.get('touch-inferred'):
        # 按 v2_inputs.json 里由原片颗粒速度推断的触点远近设置 touch_strength（有不确定性）；
        # 原版钢铁侠是固定回归用例，保持接手时的 0.5。
        cls, options = build(root, ','.join(p for p in spec.split(',') if p not in ('touch-inferred', 'transport-touch'))
                             + (',transport-g=1' if v.get('transport-touch') and 'transport-g' not in v else ''))
        inner = cls

        def init_touch(self, name='ironman', *a, **kw):
            import json
            inner.__init__(self, name, *a, **kw)
            inferred = json.loads((HERE / 'v2_inputs.json').read_text('utf-8')).get(name, {}).get('touch_strength')
            if inferred is not None and name != 'ironman':
                # 与 renderer.py 计算着色器同一换算：运动幅度 = 0.55 + 0.90 × 触点远近。
                before = .55 + .9 * self.touch_strength
                self.touch_strength = float(inferred)
                self.compute['touch_strength'] = self.touch_strength
                self.pressure_grid.splat['touch_strength'] = self.touch_strength
                if v.get('transport-touch'):
                    # 学习输运位移在默认触点远近下标定，不随运动幅度变化；触点变近时速度变小而这层位移不变，
                    # 颗粒会被它推着逆风后退。按运动幅度之比同比缩放，使整体运动随触点一起缩放。
                    tgt = self.compute if 'transport_xy_gain' in self.compute else self.program
                    tgt['transport_xy_gain'] = tgt['transport_xy_gain'].value * (.55 + .9 * self.touch_strength) / before
                self.reset()
        return type(f'Touch_{inner.__name__}', (inner,), {'__init__': init_touch}), options
    if 'span-ref' not in v and 'screen-ref' not in v:
        return _build(root, spec)
    reference = float(v.get('span-ref', 468.)); screen_ref = float(v['screen-ref']) if 'screen-ref' in v else 0.
    cls, options = _build(root, ','.join(p for p in spec.split(',') if not (p.startswith('span-ref') or p.startswith('screen-ref'))))
    base = cls

    def init(self, name='ironman', *a, **kw):
        import json, dataclasses
        meta = json.loads((HERE / 'assets' / name / 'scene.json').read_text('utf-8'))
        x, y, x1, y1 = meta['rect']
        k = min(x1 - x, y1 - y) / reference
        if screen_ref:
            # screen-ref=<像素>：颗粒大小按设备像素定——按录像里手机屏幕的宽度（有屏幕掩码时取掩码外接框宽，否则为整幅宽）
            # 相对原版钢铁侠（611 像素）缩放，而不是按卡片短边；不同应用的弹窗大小不同，颗粒大小不随弹窗变。
            mask_path = HERE / 'assets' / name / 'screen-mask.png'
            if mask_path.is_file():
                from PIL import Image as _Image
                mk = np.array(_Image.open(mask_path).convert('L')) > 127
                xs = np.nonzero(mk.any(0))[0]; width = float(xs.max() - xs.min() + 1)
            else:
                width = float(meta['frame'][0])
            k = width / screen_ref
        from unified_model import RULES
        kw['cell_px'] = (kw.get('cell_px') or RULES['cell']) * k
        if kw.get('spark') is not None:
            kw['spark'] = dataclasses.replace(kw['spark'], size=kw['spark'].size * k)
        base.__init__(self, name, *a, **kw)
    return type(f'Span_{base.__name__}', (base,), {'__init__': init}), options


def _build(root, spec):
    cls, options = configuration(root)
    v = parse(spec)
    if not v:
        return cls, options
    # transport-v=<n>：共同输运函数改用圆点外观下重新标定的权重（analysis/fit_spark_transport.py，标签 spark-v<n>）。
    if 'transport-v' in v:
        from shared_transport import load_transport
        options['transport_weights'] = load_transport(HERE / 'analysis/match-cache/spark-transport' / f'spark-v{int(v.pop("transport-v"))}' / 'candidate.json')
        if not v:
            return cls, options
    # cell=<像素>：材料网格间距（原默认 1.65）；更粗的网格即更少、更大的颗粒，运动场不变。
    if 'cell' in v:
        options['cell_px'] = float(v.pop('cell'))
        if not v:
            return cls, options
    # spark-<字段>=<值>：圆形亮核颗粒外观（spark_material.SparkRules）；spark 单独出现时用默认规则。
    spark_keys = {k[6:].replace('-', '_'): val for k, val in v.items() if k.startswith('spark-')}
    if spark_keys or v.get('spark'):
        from spark_material import SparkRenderer, SparkRules
        cls = SparkRenderer; options['spark'] = SparkRules(**spark_keys)
    # edge-<字段>=<值>：按布局差异 g 加权的边缘展开候选（edge_generalization.EdgeRules）。
    # edge-g=<g>：边缘规则按给定的布局系数生效（缺省用各段自己的 g，标准布局为 0）；1 即所有布局都完整生效。
    # 只替换边缘规则里用到的 g，引导、风、大小等其它按 g 加权的条件规则不变。
    forced_g = v.get('edge-g')
    edge_keys = {k[5:].replace('-', '_'): val for k, val in v.items() if k.startswith('edge-') and k != 'edge-g'}
    if edge_keys:
        from edge_generalization import with_edge, EdgeRules
        cls = with_edge(cls); options['edge'] = EdgeRules(**edge_keys)
        if forced_g is not None:
            edge_cls = cls

            def apply_edge(self, rules, _g=float(forced_g)):
                saved = self.layout_change; self.layout_change = _g
                try:
                    edge_cls.apply_edge(self, rules)
                finally:
                    self.layout_change = saved
            cls = type('G' + edge_cls.__name__, (edge_cls,), {'apply_edge': apply_edge})
    # tex-<字段>=<值>：空间相关的释放时钟与前沿表面提亮（release_texture.TextureRules）。
    tex_keys = {k[4:].replace('-', '_'): val for k, val in v.items() if k.startswith('tex-')}
    if tex_keys:
        from release_texture import with_texture, TextureRules
        if 'octaves' in tex_keys: tex_keys['octaves'] = int(tex_keys['octaves'])
        if 'seed' in tex_keys: tex_keys['seed'] = int(tex_keys['seed'])
        cls = with_texture(cls); options['texture'] = TextureRules(**tex_keys)
    v = {k: val for k, val in v.items() if not (k.startswith('edge-') or k.startswith('spark-') or k == 'spark' or k.startswith('tex-'))}
    if not v:
        return cls, options
    if v.get('no-cohorts'): options['cohorts'] = None
    if v.get('no-scatter'): options['scatter'] = None
    if v.get('no-coherence'): options['coherence'] = None
    compute = cls.compute_source
    from pressure_grid import SPLAT
    splat = getattr(cls, 'pressure_splat_source', SPLAT)
    if 'peel' in v:
        old = 'target+=(peel*1.2141309*(.10+1.80*peel_response)*exp(-age/.16)*smoothstep(.003,.028,age)+sample_peel(p))*span;'
        assert old in compute
        compute = compute.replace(old, old.replace('peel*1.2141309', f'peel*({v["peel"]:.6f})*1.2141309'))
        # 压力网格按同一剥离动量求解，保持两处一致。
        old = 'float strength=1.2141309*(.10+1.80*response);'
        assert old in splat
        splat = splat.replace(old, f'float strength=({v["peel"]:.6f})*1.2141309*(.10+1.80*response);')
    if 'pressure' in v:
        # 反汇聚强度乘 k：动态单侧压力的负梯度与静态压缩估计两处同乘（0 为完全不约束）。
        k = v['pressure']
        # 边缘规则会把剥离一行改写成 edge_peel／edge_peel_tau 的形式，只替换两种写法共有的结尾。
        old = '+sample_peel(p))*span;'
        assert compute.count(old) == 1
        compute = compute.replace(old, f'+({k:.6f})*sample_peel(p))*span;')
        old = 'float peel_pressure=2.*peel_compression[i]*.16*1.2141309*(.10+1.80*peel_response);'
        assert old in compute
        compute = compute.replace(old, f'float peel_pressure=({k:.6f})*2.*peel_compression[i]*.16*1.2141309*(.10+1.80*peel_response);')
    if 'peel-sym' in v:
        # 剥离方向逐颗翻转：比例 f 的颗粒（逐颗散列决定）剥离方向由 −n（朝起点一侧）翻到 +n（背离起点），其余照旧；去逆风、减顺风的处理不变。
        # f = 0.5 时剥离只把材料向前沿两侧展开、不再整体偏向起点。依据：浅色背景下半部的轨迹因 −n 剥离偏成竖直（原片朝右上），
        # 去掉剥离（peel=0.001）轨迹终点距离降四成，但可见区变窄、灭霸与钢铁侠中段交并比下降——原片保留了横向展开，只是没有整体偏向。
        # 压力网格的溅射用同一散列翻同一批颗粒。所有段同值。
        fsym = float(v['peel-sym'])
        old = 'vec2 peel=-m.physical.xy;'
        assert compute.count(old) == 1, 'peel-sym compute: ' + old
        compute = compute.replace(old, f'vec2 peel=-m.physical.xy*(transport_unit(floatBitsToUint(m.random.z)^0x7f4a7c15u)<{fsym:.6f}?-1.:1.);', 1)
        assert splat.count(old) == 1, 'peel-sym splat: ' + old
        splat = splat.replace(old, f'vec2 peel=-m.physical.xy*(unit32(floatBitsToUint(m.random.z)^0x7f4a7c15u)<{fsym:.6f}?-1.:1.);', 1)
    if 'peel-along' in v:
        # 剥离速度顺风分量的保留比例（原模型去掉 82%，即保留 0.18）；k 为保留比例。
        k = v['peel-along']
        old = 'peel-=wind*max(dot(peel,wind),0.)*.82;'
        assert old in compute and old in splat
        compute = compute.replace(old, f'peel-=wind*max(dot(peel,wind),0.)*({1. - k:.6f});')
        splat = splat.replace(old, f'peel-=wind*max(dot(peel,wind),0.)*({1. - k:.6f});')
    if 'peel-upwind' in v:
        # 剥离速度逆风分量的保留比例（原模型全部去掉，即 0）；与 edge-flip 合用时，背离起点的剥离在逆风一侧不再被剔除。
        k = v['peel-upwind']
        old = 'peel-=wind*min(dot(peel,wind),0.);'
        assert old in compute and old in splat
        compute = compute.replace(old, f'peel-=wind*min(dot(peel,wind),0.)*({1. - k:.6f});')
        splat = splat.replace(old, f'peel-=wind*min(dot(peel,wind),0.)*({1. - k:.6f});')
    if 'peel-edge' in v:
        # 自由边界处朝外剥离的收减系数（原 .95；0 表示不收减）。取极小正值代替 0，
        # 避免编译器删掉 span 等 uniform 后主程序设值失败；数值上等同于不收减。
        k = max(v['peel-edge'], 1e-9)
        old = 'peel*=1.-.95*eject*exp(-min(free_edge.x,free_edge.y)/(span*.18));'
        assert old in compute
        compute = compute.replace(old, f'peel*=1.-({k:.9g})*eject*exp(-min(free_edge.x,free_edge.y)/(span*.18));')
        old = 'peel*=1.-.95*eject*exp(-min(edge.x,edge.y)/(span*.18));'
        assert old in splat
        splat = splat.replace(old, f'peel*=1.-({k:.9g})*eject*exp(-min(edge.x,edge.y)/(span*.18));')
    if 'peel-tau' in v:
        old = 'exp(-age/.16)*smoothstep(.003,.028,age)+sample_peel(p))*span;'
        assert old in compute
        compute = compute.replace(old, f'exp(-age/{v["peel-tau"]:.6f})*smoothstep(.003,.028,age)+sample_peel(p))*span;')
        old = 'peel*=exp(-age/.16)*smoothstep(.003,.028,age);'
        assert old in splat
        splat = splat.replace(old, f'peel*=exp(-age/{v["peel-tau"]:.6f})*smoothstep(.003,.028,age);')
    if 'guide' in v:
        old = 'vec2 target=sample0.xy*(guide_gain/.9)*(1.08+.12*reach)*conditional_motion;'
        assert old in compute
        compute = compute.replace(old, old[:-1] + f'*({v["guide"]:.6f});')
    if 'curl' in v:
        old = 'vec2 flow=curl(q,time)*span*curl_gain*conditional_curl;'
        assert old in compute
        compute = compute.replace(old, old[:-1] + f'*({v["curl"]:.6f});')
        old = 'target+=curl(p+transport_random*span*.4,time)*span*.075*conditional_curl'
        assert old in compute
        compute = compute.replace(old, old + f'*({v["curl"]:.6f})')
    if 'mramp-early' in v or 'mramp-late' in v:
        # 非标准布局的引导／卷动系数随颗粒年龄变化：1+g·(m(age)−1)，m 由 early 平滑升到 late（到 mramp-age 为止）。
        # 标准布局 g=0 不受影响；卷动与引导同比例，保持两者的相对占比。
        old = 'vec2 flow=curl(q,time)*span*curl_gain*conditional_curl;'
        assert old in compute
        compute = compute.replace('void main(){', 'uniform float ramp_g,ramp_early,ramp_late,ramp_age;\nvoid main(){', 1)
        # 保留对原统一变量的引用（乘 0），避免编译器删去后父类设值失败。
        compute = compute.replace(old, 'float motion_ramp=1.+ramp_g*(mix(ramp_early,ramp_late,smoothstep(0.,ramp_age,max(age,0.)))-1.)+1e-12*(conditional_motion+conditional_curl);\n    '
                                  'vec2 flow=curl(q,time)*span*curl_gain*motion_ramp;')
        old = 'vec2 target=sample0.xy*(guide_gain/.9)*(1.08+.12*reach)*conditional_motion;'
        assert old in compute
        compute = compute.replace(old, 'vec2 target=sample0.xy*(guide_gain/.9)*(1.08+.12*reach)*motion_ramp;')
        old = 'target+=curl(p+transport_random*span*.4,time)*span*.075*conditional_curl'
        assert old in compute
        compute = compute.replace(old, 'target+=curl(p+transport_random*span*.4,time)*span*.075*motion_ramp')
        if 'mcurl' in v:
            # 非标准布局的两处附加卷动另乘 1+g·(mcurl−1)，与引导分开（只改横向扰动，不改顺流携带）。
            compute = compute.replace('void main(){', 'uniform float curl_extra;\nvoid main(){', 1)
            old = 'vec2 flow=curl(q,time)*span*curl_gain*motion_ramp;'
            assert old in compute
            compute = compute.replace(old, 'vec2 flow=curl(q,time)*span*curl_gain*motion_ramp*curl_extra;')
            old = 'target+=curl(p+transport_random*span*.4,time)*span*.075*motion_ramp'
            assert old in compute
            compute = compute.replace(old, 'target+=curl(p+transport_random*span*.4,time)*span*.075*motion_ramp*curl_extra')
    if 'gramp-early' in v:
        # 引导项随颗粒年龄由 e 倍升到 1（到 gramp-age 为止），所有布局相同（不按 g 加权）：原片颗粒释放后「先慢后快」，
        # 模型出生即以目标速度的 85% 离开原处，释放区很快透底。剥离、卷动与顺风携带的原有年龄斜坡不变。
        lines = compute.split('\n'); hit = 0
        for i, line in enumerate(lines):
            st = line.strip()
            if st.startswith('vec2 target=sample0.xy*(guide_gain/.9)*(1.08+.12*reach)') and st.endswith(';'):
                lines[i] = line.rstrip()[:-1] + '*mix(gramp_early,1.,smoothstep(0.,gramp_age,max(age,0.)));'; hit += 1
        assert hit == 1, 'gramp 找不到引导项'
        compute = '\n'.join(lines).replace('void main(){', 'uniform float gramp_early,gramp_age;\nvoid main(){', 1)
    if 'turn' in v:
        # 出生位置到触点方向的温和偏转系数（原 .13）乘 k。
        old = 'float turn=.13*atan(dot(bearing,vec2(-wind.y,wind.x)),dot(bearing,wind));'
        assert old in compute
        compute = compute.replace(old, f'float turn=({v["turn"]:.6f})*.13*atan(dot(bearing,vec2(-wind.y,wind.x)),dot(bearing,wind))+1e-12*touch_gap;', 1)
    if 'fan' in v:
        # 触点扇形（顾问 2026-10-01）：触点在卡片来向一侧，T = 卡片中心 − 风向 ×（沿风向到卡片边缘的距离 + gap·短边），
        # 目标速度整体旋转到「T 指向颗粒当前位置」的方向（旋转比例 fan-k，缺省 1）。原来的目的点在流向前方、按出生位置只偏转 13%，
        # 是方向相反的弱汇聚场。gap 属于终点输入：fan=<gap> 所有段同值；fan-table=<版本> 按表逐段设置（见 build）。
        assert 'turn' not in v, 'fan 与 turn 不能同时使用'
        old = ('vec2 destination=card*.5+wind*(edge+span*touch_gap);\n    vec2 bearing=normalize(destination-m.src.xy);\n'
               '    float turn=.13*atan(dot(bearing,vec2(-wind.y,wind.x)),dot(bearing,wind));')
        assert compute.count(old) == 1, 'fan: 找不到目的点偏转'
        compute = compute.replace(old, 'vec2 destination=card*.5-wind*(edge+span*fan_gap)+1e-9*touch_gap*wind;\n    vec2 bearing=normalize(p-destination);\n'
                                       '    float turn=fan_k*atan(dot(bearing,vec2(-wind.y,wind.x)),dot(bearing,wind));', 1)
        compute = compute.replace('void main(){', 'uniform float fan_gap,fan_k;\nvoid main(){', 1)
    if 'disperse' in v:
        # 零均值逐颗随机速度（顾问 2026-10-01）：target += u·短边·V·exp(−年龄/τ)，u 为逐颗固定的随机单位向量，加在沿风向平滑下限之后
        # （不被下限削去逆风分量），位移上限约 V·τ 短边。原片释放后约 0.1 进度内细结构就糊成宽带，模型的随机分离不到其四分之一。
        # disperse=V（短边/单位进度），disperse-tau=τ（缺省 0.15）；所有段同值。
        V = float(v['disperse']); tau = float(v.get('disperse-tau', .15))
        old = 'float response=1.-exp(-h/(.018+.036*m.random.z));'
        assert compute.count(old) == 1, 'disperse: ' + old
        # disperse-cross=c：去掉随机速度沿风向分量的比例 c（1 为只在横风方向弥散）：沿风向排列的细线被拓宽，横风向的弧不受影响。
        cross = float(v.get('disperse-cross', 0.))
        compute = compute.replace(old, '{float disperse_a=6.2831853*transport_unit(floatBitsToUint(m.random.z)^floatBitsToUint(m.random.x)^0x68bc21ebu);'
                                       f'vec2 disperse_u=vec2(cos(disperse_a),sin(disperse_a));disperse_u-=wind*dot(disperse_u,wind)*({cross:.6f});target+=disperse_u*span*({V:.6f})*exp(-max(age,0.)/({tau:.6f}))*smoothstep(.002,.02,age);}}\n    '
                                       + old, 1)
    if 'const-vel' in v:
        # 诊断对照：所有已出生颗粒以同一恒定速度（短边 / 单位进度，沿风向）平移，用于区分绘制与运动学对图案延续性的影响。
        old = 'float response=1.-exp(-h/(.018+.036*m.random.z));'
        assert old in compute
        compute = compute.replace(old, f'target=wind*span*({v["const-vel"]:.6f})+1e-12*target;\n    ' + old, 1)
    if 'accel' in v:
        # 沿风向的匀加速：目标速度加 g·k·age·span·wind（k 为 短边 / 单位进度²）；标准布局 g=0 不变。
        old = 'carried*wind_gain*conditional_wind;'
        assert old in compute
        compute = compute.replace('void main(){', 'uniform float accel_k,accel_g;\nvoid main(){', 1)
        compute = compute.replace(old, old + '\n    target+=wind*span*accel_k*accel_g*max(age,0.);', 1)
    if 'coherent' in v:
        # 逐粒随机速度成分统一乘 k：随机分离、速度倍率、随机偏移卷动、剥离强度的随机部分。
        k = v['coherent']
        old = 'target+=vec2(cos(random_angle),sin(random_angle))*radius*span*.045*separate;'
        assert old in compute
        compute = compute.replace(old, old[:-1] + f'*({k:.6f});')
        old = 'target*=1.+0.6496880*(transport_random.x-.5)*smoothstep(.025,.14,age)*independent;'
        assert old in compute
        compute = compute.replace(old, f'target*=1.+({k:.6f})*0.6496880*(transport_random.x-.5)*smoothstep(.025,.14,age)*independent;')
        old = 'target+=curl(p+transport_random*span*.4,time)*span*.075'
        assert old in compute
        compute = compute.replace(old, f'target+=({k:.6f})*curl(p+transport_random*span*.4,time)*span*.075')
        old = 'float peel_response=transport_unit(peel_seed);'
        assert old in compute
        compute = compute.replace(old, f'float peel_response=.5+({k:.6f})*(transport_unit(peel_seed)-.5);')
        old = 'float response=unit32(floatBitsToUint(m.random.x)^floatBitsToUint(m.random.y)^0xa54ff53au);'
        assert old in splat
        splat = splat.replace(old, f'float response=.5+({k:.6f})*(unit32(floatBitsToUint(m.random.x)^floatBitsToUint(m.random.y)^0xa54ff53au)-.5);')
    if 'speed-var' in v or 'rcurl' in v:
        # 只削弱后期起作用的逐颗随机：speed-var=k 乘逐颗速度倍率的离散（原 ±32%，年龄 0.025～0.14 起永久生效），
        # rcurl=k 乘逐颗偏移取样的卷动（原 0.075 短边，年龄 0.04～0.16 起永久生效）；早期的随机分离与剥离随机不变。
        # 依据：近邻颗粒（3～8 像素）速度差原片在各年龄都约 0.12 短边/进度，GLHMR 到 0.20～0.35 岁涨到 0.17（钢铁侠、地球背景），
        # 与「倍率离散 × 越来越快的速度」一致；后段颗粒覆盖率因此比原片低（灭霸 0.46 对 0.32、科比 0.57 对 0.38、地球背景 0.40 对 0.28）。
        # 与 coherent、sheet 不同时使用。所有段同值。
        assert 'coherent' not in v, 'speed-var 与 coherent 不同时使用'
        if 'speed-var' in v:
            old = 'target*=1.+0.6496880*(transport_random.x-.5)*smoothstep(.025,.14,age)*independent;'
            assert compute.count(old) == 1, 'speed-var: ' + old
            compute = compute.replace(old, f'target*=1.+({v["speed-var"]:.6f})*0.6496880*(transport_random.x-.5)*smoothstep(.025,.14,age)*independent;', 1)
        if 'rcurl' in v:
            old = 'target+=curl(p+transport_random*span*.4,time)*span*.075'
            assert compute.count(old) == 1, 'rcurl: ' + old
            compute = compute.replace(old, f'target+=({v["rcurl"]:.6f})*curl(p+transport_random*span*.4,time)*span*.075', 1)
    if 'sheet' in v:
        # 新材料的薄片阶段：原片 0.03～0.09 岁的材料仍接近满覆盖、连成一片（灭霸下巴、钢铁侠右上的亮带），之后才碎成稀疏颗粒；
        # 模型的颗粒一出生就切成圆点（面积约 66%）并被逐颗随机运动错开。sheet=a：圆点切形与尺寸随机化的开始时刻推迟 a，
        # 四种逐颗随机运动（剥离强度、随机分离、速度倍率、随机偏移卷动）乘 g = smoothstep(a, a+w, 年龄)，w 为 sheet-w（缺省 0.03）；
        # 平均剥离、输运与风照常，薄片整体移动、变形，只是在 a 之前不被打散。与 coherent 不同时使用。所有段同值。
        assert 'coherent' not in v, 'sheet 与 coherent 不同时使用'
        sa = float(v['sheet']); sw = float(v.get('sheet-w', .03))
        gate = f'smoothstep({sa:.6f},{sa + sw:.6f},age)'
        reps = [('float peel_response=transport_unit(peel_seed);', f'float peel_response=mix(.5,transport_unit(peel_seed),{gate});'),
                ('target+=vec2(cos(random_angle),sin(random_angle))*radius*span*.045*separate;', f'target+=vec2(cos(random_angle),sin(random_angle))*radius*span*.045*separate*{gate};'),
                ('target*=1.+0.6496880*(transport_random.x-.5)*smoothstep(.025,.14,age)*independent;', f'target*=1.+0.6496880*(transport_random.x-.5)*smoothstep(.025,.14,age)*independent*{gate};'),
                ('target+=curl(p+transport_random*span*.4,time)*span*.075', f'target+={gate}*curl(p+transport_random*span*.4,time)*span*.075')]
        for old, new in reps:
            assert compute.count(old) == 1, 'sheet: ' + old
            compute = compute.replace(old, new, 1)
        old = 'float response=unit32(floatBitsToUint(m.random.x)^floatBitsToUint(m.random.y)^0xa54ff53au);'
        assert splat.count(old) == 1, 'sheet: ' + old
        splat = splat.replace(old, f'float response=mix(.5,unit32(floatBitsToUint(m.random.x)^floatBitsToUint(m.random.y)^0xa54ff53au),{gate});', 1)
    if 'dwell' in v:
        # 逐颗停留：运动的起点推迟 d（对数正态，中位 dwell，离散 dwell-sigma），期间停在原位；
        # 表面交接与可见寿命仍按真实释放时刻。压力网格用同一推迟后的年龄。
        median = v['dwell']; sigma = v.get('dwell-sigma', .5); cap = v.get('dwell-max', .2)
        gauss = (f'float dwell_u1=max(transport_unit(floatBitsToUint(m.random.z)^0x2545f491u),1e-4);'
                 f'float dwell_u2=transport_unit(floatBitsToUint(m.random.w)^0x9e3779b9u);'
                 f'float dwell=min({median:.6f}*exp({sigma:.6f}*sqrt(-2.*log(dwell_u1))*cos(6.2831853*dwell_u2)),{cap:.6f});')
        old = 'float age=time-m.src.z;\n    if(age<=0.){state[i].pos=vec4(m.src.xy,0,0);state[i].vel=vec4(0);return;}'
        assert old in compute
        compute = compute.replace(old, gauss + '\n    float age=time-m.src.z-dwell;\n    if(age<=0.){state[i].pos=vec4(m.src.xy,0,0);state[i].vel=vec4(0);return;}')
        old = 'Material m=particles[i];float age=time-m.src.z;'
        assert old in splat
        splat_gauss = gauss.replace('transport_unit', 'unit32')
        splat = splat.replace(old, 'Material m=particles[i];' + splat_gauss + 'float age=time-m.src.z-dwell;')
    if 'birth-ease' in v:
        old = 'float depth_target=-state[i].pos.z*3.*roll_gain;'
        assert old in compute
        compute = compute.replace(old, f'target*=smoothstep(0.,{v["birth-ease"]:.6f},age);\n    ' + old)
        old2 = 'if(age<=dt)old_v=target*.85;'
        assert old2 in compute
        compute = compute.replace(old2, 'if(age<=dt)old_v=target*.0;')
    if 'zoom' in v:
        # 以卡片中心为原点的径向速度（顾问 2026-09-30）：target += (p − 卡片中心)·k·g·exp(−年龄/τ)，出生后 0.003～0.03 内生效；
        # g 为布局差异系数（钢铁侠为 0，逐位不变）。原片除钢铁侠外各段的光流都有从卡片中心向外的分量（科比几乎是纯放大），
        # 位移上限约为 k·τ 倍的离中心距离。zoom=k，zoom-tau=τ（缺省 0.3）。
        old = 'float edge=min(card.x/(2.*max(abs(wind.x),.000001)),card.y/(2.*max(abs(wind.y),.000001)));'
        assert compute.count(old) == 1, 'zoom: ' + old
        compute = compute.replace(old, 'target+=(p-card*.5)*zoom_k*zoom_g*exp(-max(age,0.)/zoom_tau)*smoothstep(.003,.03,age);\n    ' + old, 1)
        compute = compute.replace('void main(){', 'uniform float zoom_k,zoom_g,zoom_tau;\nvoid main(){', 1)
    if 'guide-k' in v:
        # 共享引导模板（按卡片中心与风向摆放、与起点无关的学习流场）整体乘 k，卷动与其它各项不变；所有段同值。
        k = float(v['guide-k'])
        lines = compute.split('\n'); hit = 0
        for i, line in enumerate(lines):
            st = line.strip()
            if st.startswith('vec2 target=sample0.xy*(guide_gain/.9)*(1.08+.12*reach)') and st.endswith(';'):
                lines[i] = line.rstrip()[:-1] + f'*({k:.6f});'; hit += 1
        assert hit == 1, 'guide-k 找不到引导项'
        compute = '\n'.join(lines)
    if 'rstart' in v:
        # 静止起步 + 沿共同流向的统一加速：引导与顺风携带共乘 R(τ)=1−exp(−τ/rstart)（替代 carried 斜坡），出生速度为 0
        # （原为目标的 85%）。原片各段可见速度随释放后时间 τ 的变化都贴合 v∞·R(τ)，时间常数约 0.30。
        # gramp／accel 是旧的起步斜坡与匀加速，与此重复，应从配置里去掉（这里不自动处理）；rstart-v=k 时两项再乘 k。
        tau_r = v['rstart']; kv = v.get('rstart-v', 1.)
        lines = compute.split('\n'); hit = 0
        for i, line in enumerate(lines):
            st = line.strip()
            if st.startswith('vec2 target=sample0.xy*(guide_gain/.9)*(1.08+.12*reach)') and st.endswith(';'):
                indent = line[:len(line) - len(line.lstrip())]
                lines[i] = (f'{indent}float rstart_R=({kv:.6f})*(1.-exp(-max(age,0.)/{tau_r:.6f}));\n'
                            + line.rstrip()[:-1] + '*rstart_R;'); hit += 1
        assert hit == 1, 'rstart 找不到引导项'
        compute = '\n'.join(lines)
        old = 'target+=wind*span*(.12+.50*reach)*carried*wind_gain*conditional_wind;'
        assert compute.count(old) == 1, 'rstart: ' + old
        compute = compute.replace(old, 'target+=wind*span*(.12+.50*reach)*rstart_R*wind_gain*conditional_wind;', 1)
        old = 'if(age<=dt)old_v=target*.85;'
        assert compute.count(old) == 1, 'rstart: ' + old
        compute = compute.replace(old, 'if(age<=dt)old_v=vec2(0.);', 1)
    if 'axial-floor' in v:
        # 沿风向速度的平滑下限（原 0.16 短边 / 单位进度，颗粒不能逆风走）；取负值时允许背离起点的剥离带颗粒短暂逆风起步。
        old = 'float deficit=.16-axial;'
        assert compute.count(old) == 1, 'axial-floor: ' + old
        compute = compute.replace(old, f'float deficit=({v["axial-floor"]:.6f})-axial;', 1)
    if 'ffront' in v:
        # 朝下风推进的前沿（−n 与风同向）在年龄 0.10～0.25 之前的 45% 减速乘 k（0 为去掉）。
        old = 'target*=1.-.45*forward_front*(1.-smoothstep(.10,.25,age));'
        assert compute.count(old) == 1, 'ffront: ' + old
        compute = compute.replace(old, f'target*=1.-({.45 * v["ffront"]:.6f})*forward_front*(1.-smoothstep(.10,.25,age));', 1)
    if 'spread-gate' in v or 'spread-amp' in v or 'spread-tau' in v:
        # 原表面边缘解除后向开放侧的外展：spread-gate 为尾缘门 mix(.12,1,trailing) 保留的比例（0 为去门，各处按尾缘处理），
        # spread-amp 为幅度（原 .4），spread-tau 为衰减年龄（原 .080）。
        gate = v.get('spread-gate', 1.); amp = v.get('spread-amp', .4); tau_s = v.get('spread-tau', .08)
        old = 'float spread_age=smoothstep(.002,.014,age)*exp(-age/.080)*mix(.12,1.,trailing_edge);'
        assert compute.count(old) == 1, 'spread: ' + old
        compute = compute.replace(old, f'float spread_age=smoothstep(.002,.014,age)*exp(-age/{tau_s:.6f})*mix(1.-.88*({gate:.6f}),1.,trailing_edge);', 1)
        old = 'target+=outward_spread*span*.4*(.05+2.4*m.random.z*m.random.z)*spread_age;'
        assert compute.count(old) == 1, 'spread: ' + old
        compute = compute.replace(old, f'target+=outward_spread*span*({amp:.6f})*(.05+2.4*m.random.z*m.random.z)*spread_age;', 1)
    if 'kin' in v:
        # 运动学覆盖（OpenHarmony RenderService 粒子的运动方程：v ← v + a·dt，无阻尼；逐颗跟踪原片：按年龄匀加速）：
        # 目标速度换成 风向 ×（kin-v0 + kin-a × 年龄）+ 逐颗固定的随机初速（单位圆盘内均匀，大小 kin-r，按 kin-rt 衰减），
        # 出生后 kin-ramp 年龄内平滑起步；与原目标速度按 kin 混合（1 为完全替换）。所有段同值，单位 短边 / 单位进度。
        kmix = float(v['kin']); kv0 = float(v.get('kin-v0', .25)); ka = float(v.get('kin-a', 1.2))
        kr = float(v.get('kin-r', .15)); krt = float(v.get('kin-rt', 1e3)); kramp = float(v.get('kin-ramp', .02))
        # kin-x=k：以卡片中心为原点的径向初速（大小与出生位置到中心的距离成正比，k 为 1 / 单位进度），按 kin-xt 衰减；整体张开。
        kx = float(v.get('kin-x', 0.)); kxt = float(v.get('kin-xt', 1e3))
        # kin-xr=k：随速度成比例的整体张开（见 scratchpad patch_kinxr.py 的说明）。
        kxr = float(v.get('kin-xr', 0.))
        # kin-xr-t / kin-xr-n：各向异性的张开——沿颗粒自身速度方向（拉伸）与垂直方向（横向张开）分开给系数，缺省都取 kin-xr。
        kxrt = float(v.get('kin-xr-t', kxr)); kxrn = float(v.get('kin-xr-n', kxr))
        # kin-ring=A：沿前沿推进方向（m.physical.xy，指向尚未释放的一侧）的临时位移 D(τ) = A·(τ/τ0)·exp(1 − τ/τ0)·短边，峰值 A 在 τ0 = kin-ring-t，
        # 之后回到 0（OpenHarmony 波动场「外扩推力环 + 环后回弹」的近似）：刚释放的颗粒先被推向前沿、在前沿处堆积，再回到原轨迹。
        kring = float(v.get('kin-ring', 0.)); kringt = max(float(v.get('kin-ring-t', .02)), 1e-4)
        # kin-cross=c：顺风分量由运动学给出，横风分量保留原目标的 c 倍（c 缺省为空，即整体按 kin 混合）。
        kcross = v.get('kin-cross')
        # kin-av=σ：逐颗加速度系数 1 + σ·(2u − 1)（u 逐颗固定、均匀），原片同年龄颗粒的顺风速度离散比模型大三到五成。
        kav = float(v.get('kin-av', 0.))
        # kin-edge=β, kin-edge-l=λ：靠近顺风一侧边缘（外法向与风向点积为正）的颗粒按出生位置到该边的距离 exp(−d/λ·短边) 加一个朝外的速度，
        # 大小 β × 点积；逆风一侧的边不加。原片钢铁侠左下、科比左缘的颗粒都沿边缘外法向越出卡片。
        kedge = float(v.get('kin-edge', 0.)); kedgel = max(float(v.get('kin-edge-l', .25)), 1e-3)
        # kin-edge-sym=1：朝外速度对四条边都加（权重 1）。kin-edgew=β, kin-edgew-l=λ, kin-edgew-t=τ：出生在任一条边附近的颗粒
        # 初速加 β·ŵ·exp(−d/λ)·exp(−年龄/τ)，d 为出生位置到最近一条边的距离（短边）。逐颗跟踪原片边缘带初速 0.20～0.29、内部 0.02～0.15，
        # 方向沿风向一侧；原片早、中段从顺风边溢出卡片的可见颗粒比模型多（scratchpad edge_outflow.py）。
        ksym = bool(v.get('kin-edge-sym')); kew = float(v.get('kin-edgew', 0.)); kewl = max(float(v.get('kin-edgew-l', .1)), 1e-3)
        kewt = max(float(v.get('kin-edgew-t', 1e3)), 1e-4)
        # kin-front=b, kin-front-t=τ：初速加沿释放前沿推进方向（m.physical.xy，背离起点）的 b·exp(−年龄/τ)。原片逐颗跟踪在钢铁侠、科比、
        # 地球背景上有 +0.07～+0.10 的前沿法向分量（scratchpad fn_saved.py），起点附近的颗粒群因此先向外散开、再被风带走。
        kfront = float(v.get('kin-front', 0.)); kfrontt = max(float(v.get('kin-front-t', .2)), 1e-4)
        # site-push=b, site-push-t=τ, site-push-n=1：颗粒按释放势最小的起点归属（与 site-motion 同一判据），初速加
        # b ×（起点位置 − 卡片中心）/ 短边 × exp(−年龄/τ)（n=1 时只取方向、大小为 b）。同一起点的颗粒群整体朝「卡片中心指向起点」的方向
        # 带出：科比左缘、领口、右上三个起点的颗粒群分别向左、向下、向右上越出卡片，参考 5 卡片外左下的起点对应左下鼓出的颗粒团。所有段同值。
        kspush = float(v.get('site-push', 0.)); kspt = max(float(v.get('site-push-t', 1e3)), 1e-4); kspn = bool(v.get('site-push-n'))
        # site-push-soft=T：起点方向按释放势加权平均（权重 exp(−(势 − 最小势)/T)，T 为进度），群与群交界处平滑过渡，不再硬分配到单个起点。
        kspsoft = float(v.get('site-push-soft', 0.))
        # kin-clock=w：加速项里的年龄换成 mix(年龄, 全局时刻, w)。w=1 时同一时刻所有颗粒的速度相同（与出生先后无关，欧拉式流场），
        # 颗粒群不再因出生先后被拉长，晚出生的颗粒一出生就快；w=0 为原式。所有段同值。
        kclk = float(v.get('kin-clock', 0.))
        # site-v0=<版本>：逐起点初速修正（见文件头说明）；site-v0-soft=T 为软归属温度，site-v0-t=τ 为衰减年龄。
        ksv = bool(v.get('site-v0')); ksvsoft = max(float(v.get('site-v0-soft', .04)), 1e-4); ksvt = max(float(v.get('site-v0-t', 1e3)), 1e-4)
        # kin-curl=a：在运动学速度上叠加基础模型的三频段旋度场 curl(q,t)（与原卷动同一函数，主波长 1.12 短边），幅度 a 短边 / 单位进度；
        # 逐颗跟踪显示原片残差速度在 60～130 像素内保持相关（成片流动），纯运动学的残差几乎全是逐颗噪声。kin-curl-static 时 q 不随风平移、不带时间相位。
        kcurl = float(v.get('kin-curl', 0.)); kcs = bool(v.get('kin-curl-static'))
        # kin-curl-k=s：取样坐标乘 s（波长除以 s），s>1 时结构变小，主波长不再与卡片同量级，避免给整段带来方向偏置。
        kck = float(v.get('kin-curl-k', 1.))
        # kin-curl-evo=e：只在 kin-curl-static 时生效，场仍固定在卡片坐标里（不随风平移），相位按 e × 全局时刻缓慢演化；缺省 0 即完全静止。
        # 依据：OpenHarmony 7.0 的粒子消散滤镜参数里湍流有尺度、演化、幅度三项（research-2026-09-28-harmonyos7-ablation.md），
        # 原片的流动结构在屏幕上保持得比随风平移的场久（edge-coverage 4.24「屏幕上固定的流场」）。
        kevo = float(v.get('kin-curl-evo', 0.))
        # kin-curl-seed=n / kin-curl-seed-table=<版本>：旋度场换成带随机种子的同一生成器（三个频段各自的两个相位与整体朝向由种子决定），
        # 生成器、波长、幅度所有段相同，每段只取一次不同的随机结果（用户 2026-10-02 同意按段挑种子）；表为 analysis/kin-curl-seed-v<版本>.json。
        kcseed = ('kin-curl-seed' in v) or ('kin-curl-seed-table' in v)
        # kin-clump=px：逐颗随机初速按出生位置的团块（边长 px，按短边 468 缩放）共用；0 为逐颗独立。
        kclump = float(v.get('kin-clump', 0.))
        old = 'float response=1.-exp(-h/(.018+.036*m.random.z));'
        assert compute.count(old) == 1, 'kin: ' + old
        compute = compute.replace(old, (
            ('{uint kin_s=floatBitsToUint(m.random.x)^floatBitsToUint(m.random.w)^0x2545f491u;' if kclump <= 0 else
             (f'{{uvec2 kin_c=uvec2(floor(max(m.src.xy,vec2(0.))/(span*{kclump / 468.:.6f})));uint kin_s=(kin_c.x*0x8da6b343u)^(kin_c.y*0xd8163841u)^0x2545f491u;'
              'kin_s^=kin_s>>16;kin_s*=0x7feb352du;kin_s^=kin_s>>15;kin_s*=0x846ca68bu;kin_s^=kin_s>>16;')) +
            'float kin_ang=6.2831853*transport_unit(kin_s);float kin_rad=sqrt(transport_unit(kin_s^0x9e3779b9u));'
            # kin-delay=m, kin-delay-p=p：逐颗随机的起步延迟 δ = m·u^p（u 逐颗固定、均匀），运动用的年龄换成 max(年龄 − δ, 0)，延迟期间颗粒留在出生处
            # （看得见、不动），外观与表面交接仍按真实年龄。依据：地球背景手套宝石的颗粒在原片里陆续漏出、拉成持续 0.24 的亮线，模型同一批颗粒整群离开
            # （scratchpad，rebuild-2026-10-01-plan.md 10-03 上午）。所有段同值。
            + ('float kd_m=kdisp_f((m.src.xy-card*.5)/span);' if v.get('kin-disp') else '')
            + ('float kin_age=max(age,0.);' if not v.get('kin-delay') else
               f'float kin_age=max(age-({float(v["kin-delay"]):.6f})*pow(transport_unit(kin_s^0x3c6ef372u),{float(v.get("kin-delay-p", 2.)):.6f}),0.);') +
            f'float kin_af=1.+({kav:.6f})*(2.*transport_unit(kin_s^0x51ed270bu)-1.);'
            'vec2 kin_w=wind;float kin_am=1.;vec2 kin_sp=vec2(0.);vec2 kin_sv=vec2(0.);'
            + ('if(ps_n>0){float ps_best=1e9;int ps_i=0;float ps_p[6];for(int i=0;i<6;i++){if(i>=ps_n)break;'
               'vec2 d=(m.src.xy-ps_a[i].xy)/span;if(ps_b[i].x>0.){if(ps_b[i].y>.5)d.y=sign(d.y)*max(abs(d.y)-ps_b[i].x/span,0.);'
               'else d.x=sign(d.x)*max(abs(d.x)-ps_b[i].x/span,0.);}'
               'float r=sqrt(dot(d,d)+1e-6);float rho=r*max(1.+ps_s.z*dot(d,ps_w)/r,.05);'
               'float pot=ps_a[i].z+ps_s.y*pow(max(rho/ps_a[i].w,1e-4),ps_s.x);ps_p[i]=pot;if(pot<ps_best){ps_best=pot;ps_i=i;}}'
               'float c=cos(ps_b[ps_i].z),s=sin(ps_b[ps_i].z);kin_w=vec2(c*wind.x-s*wind.y,s*wind.x+c*wind.y);kin_am=ps_b[ps_i].w;kin_sp=(ps_a[ps_i].xy-card*.5)/span;'
               + (f'{{vec2 sp_a=vec2(0.);float sp_w=0.;for(int i=0;i<6;i++){{if(i>=ps_n)break;float w=exp(-(ps_p[i]-ps_best)/({kspsoft:.6f}));'
                  f'sp_a+=w*(ps_a[i].xy-card*.5)/span;sp_w+=w;}}kin_sp=sp_a/max(sp_w,1e-6);}}' if kspsoft > 0 else '')
               + (f'{{float sv_w=0.;for(int i=0;i<6;i++){{if(i>=ps_n)break;float w=exp(-(ps_p[i]-ps_best)/({ksvsoft:.6f}));kin_sv+=w*ps_v[i];sv_w+=w;}}'
                  'kin_sv/=max(sv_w,1e-6);kin_sv.y=-kin_sv.y;}' if ksv else '')
               + '}' if (v.get('site-motion') or v.get('site-push') or v.get('site-v0')) else '')
            + f'vec2 kin_v=kin_v0_u+kin_w*(({kv0:.6f})+(kin_a_u)*kin_am*kin_af*'
            + (f'mix(kin_age,max(time,0.),{kclk:.6f})' if kclk else 'kin_age')
            + f')+vec2(cos(kin_ang),sin(kin_ang))*kin_rad*({kr:.6f})' + ('*kd_m' if v.get('kin-disp') else '') + f'*exp(-kin_age/({krt:.6f}));'
            f'kin_v+=(m.src.xy-card*.5)/span*({kx:.6f})*exp(-kin_age/({kxt:.6f}));'
            # kin-uv=<版本>：逐段湍流样子的扩展形式（用户 2026-10-02 裁定允许逐段拟合湍流样子），读 analysis/kin-uv-v<版本>.json：
            # 卡片中心为原点、短边为单位、y 向下的双线性网格，在出生位置上取初速向量 u 与加速度向量 a，确定性速度换成 u + a·年龄
            # （替换 V0 + 风向·A·年龄；逐颗随机初速、逐颗转向照旧）。依据：原片轨迹一出生就有初速、之后加速得比模型慢，
            # 整条曲线旋转缩放（kin-turb）表达不了（scratchpad fit_uv.py、track_accel.py）。表里没有的段不改。
            + ('{vec4 kf=kuv_gf((m.src.xy-card*.5)/span);if(kuv_n.x>=2)'
               f'kin_v+=kf.xy+kf.zw*kin_age-(kin_v0_u+kin_w*(({kv0:.6f})+(kin_a_u)*kin_age));}}' if (v.get('kin-uv') or v.get('kin-uvt')) else '')
            + (f'{{vec2 kx_r=(m.src.xy-card*.5)/span;float kx_l=length(kin_v);vec2 kx_t=kin_v/max(kx_l,1e-6);vec2 kx_n=vec2(-kx_t.y,kx_t.x);'
               f'kin_v+=kx_l*(({kxrt:.6f})*dot(kx_r,kx_t)*kx_t+({kxrn:.6f})*dot(kx_r,kx_n)*kx_n);}}' if (kxrt or kxrn) else '')
            + (f'kin_v+={"normalize(kin_sp+vec2(1e-6))" if kspn else "kin_sp"}*({kspush:.6f})*exp(-kin_age/({kspt:.6f}));' if kspush else '')
            + (f'kin_v+=kin_sv*exp(-kin_age/({ksvt:.6f}));' if ksv else '')
            # kin-aff=<版本>：逐段一阶（仿射）流动 r = t + M·(出生位置 − 卡片中心)，y 向上的短边单位，读 analysis/kin-aff-v<版本>.json。
            # 作为一次动画的随机实现（与逐段方向、加速度、初速同类的终点输入），不是共用参数。
            + ('{vec2 ka_q=(m.src.xy-card*.5)/span;ka_q.y=-ka_q.y;vec2 ka_r=kin_aff_t+mat2(kin_aff_m.x,kin_aff_m.z,kin_aff_m.y,kin_aff_m.w)*ka_q;'
               'kin_v+=vec2(ka_r.x,-ka_r.y);}' if v.get('kin-aff') else '')
            + ''
            '{vec2 ke_o=vec2(0.);'
            + (f'ke_o+=vec2(-1.,0.)*exp(-m.src.x/(span*{kedgel:.6f}));'
               f'ke_o+=vec2(1.,0.)*exp(-(card.x-m.src.x)/(span*{kedgel:.6f}));'
               f'ke_o+=vec2(0.,-1.)*exp(-m.src.y/(span*{kedgel:.6f}));'
               f'ke_o+=vec2(0.,1.)*exp(-(card.y-m.src.y)/(span*{kedgel:.6f}));' if ksym else
               f'ke_o+=vec2(-1.,0.)*max(-wind.x,0.)*exp(-m.src.x/(span*{kedgel:.6f}));'
               f'ke_o+=vec2(1.,0.)*max(wind.x,0.)*exp(-(card.x-m.src.x)/(span*{kedgel:.6f}));'
               f'ke_o+=vec2(0.,-1.)*max(-wind.y,0.)*exp(-m.src.y/(span*{kedgel:.6f}));'
               f'ke_o+=vec2(0.,1.)*max(wind.y,0.)*exp(-(card.y-m.src.y)/(span*{kedgel:.6f}));') +
            f'kin_v+=ke_o*({kedge:.6f});}}'
            + (f'{{float kew_d=min(min(m.src.x,card.x-m.src.x),min(m.src.y,card.y-m.src.y))/span;'
               f'kin_v+=wind*({kew:.6f})*exp(-max(kew_d,0.)/({kewl:.6f}))*exp(-kin_age/({kewt:.6f}));}}' if kew else '') +
            f'kin_v+=normalize(m.physical.xy+vec2(1e-6))*({kring:.6f})*exp(1.)/({kringt:.6f})*(1.-kin_age/({kringt:.6f}))*exp(-kin_age/({kringt:.6f}));'
            + (f'kin_v+=normalize(m.physical.xy+vec2(1e-6))*({kfront:.6f})*exp(-kin_age/({kfrontt:.6f}));' if kfront else '')
            + (f'kin_v+={"kcurl_s" if kcseed else "curl"}(({kck:.6f})*({"p-card*.5" if kcs else "p-card*.5-wind*span*(.43*time+.16*time*time)"}),{((f"time*({kevo:.6f})" if kevo else "0.") if kcs else "time")})*({kcurl:.6f});' if kcurl else '')
            # kin-dir=θ（度）：按出生位置取一个平滑随机场，整颗速度旋转 θ·场值、大小乘 exp(kin-dir-s·另一个场值)，每颗颗粒的朝向在一生中固定，
            # 路径是直线，近邻颗粒的朝向相近。依据：原片跟踪速度的方向在空间上成片变化（偏差 σ 15～35°，0.05 短边相关 0.5～0.86、
            # 0.2 短边附近过零），但路径几乎不弯（每 0.05 进度转 2～6°，与没有扰动的 V24A 相当）；固定在屏幕上的卷动场（V28）统计量对上而路径更弯，
            # 画面出现挤压、蠕动。kin-dir-l 为主波长（短边，缺省 0.8），kin-dir-euler=1 改为按当前位置取场（对照），kin-dir-evo 为场随全局时刻的演化速度。
            + (f'{{vec2 kd_q=({"p" if v.get("kin-dir-euler") else "m.src.xy"}-card*.5)/span;float kd_e=max(time,0.)*({float(v.get("kin-dir-evo", 0.)):.6f});'
               f'float kd_t=radians({float(v["kin-dir"]):.6f})*kdir_f(kd_q,kd_e,0);'
               f'float kd_s=exp(({float(v.get("kin-dir-s", 0.)):.6f})*kdir_f(kd_q,kd_e,1));'
               'kin_v=mat2(cos(kd_t),sin(kd_t),-sin(kd_t),cos(kd_t))*kin_v*kd_s;}' if v.get('kin-dir') else '')
            # kin-turb=<版本>：逐段湍流样子（用户 2026-10-02 裁定允许逐段拟合，作为每次动画的随机实现），读 analysis/kin-turb-v<版本>.json：
            # 卡片中心为原点、短边为单位的双线性网格，在出生位置上取方向偏差 th（弧度）与速度对数偏差 s，整颗速度旋转 th、乘 exp(s)；
            # kin-turb-k 为诊断倍率（只乘方向与速度对数）。表里没有的段不偏转。
            + (f'{{vec2 kt=kturb_g((m.src.xy-card*.5)/span)*({float(v.get("kin-turb-k", 1.)):.6f});'
               # kin-turb2=<版本>：双股湍流的次股（analysis/kin-turb2-v<版本>.json：相对主股的方向差 off 与次股比例 pi，同样是出生位置上的网格），
               # 每颗颗粒按自己的随机量落到次股（随机量 < pi）时再转 off。用来表达同一处出发的颗粒分成两股（scratchpad fit_turb_mix.py）。
               + ('{vec2 k2=kturb2_g((m.src.xy-card*.5)/span);uint k2s=floatBitsToUint(m.random.w)^floatBitsToUint(m.random.x)^0x6a09e667u;'
                  f'if(transport_unit(k2s)<k2.y)kt.x+=k2.x*({float(v.get("kin-turb-k", 1.)):.6f});}}' if v.get('kin-turb2') else '')
               + 'kin_v=mat2(cos(kt.x),sin(kt.x),-sin(kt.x),cos(kt.x))*kin_v*exp(kt.y);}' if v.get('kin-turb') else '')
            # kin-jit=σ（度）、kin-jit-s=σs：逐颗固定的随机转向（近似正态，标准差 σ）与速度对数起伏（标准差 σs），乘在整颗速度上，路径仍是直线。
            # 依据：同一小片（0.05 短边 × 0.05 进度）里各轨迹相对片内中位的偏差，原片 9.7° / 0.19、模型 7.2° / 0.16（scratchpad local_scatter.py）。所有段同值。
            # kin-jit-clump=1：逐颗转向 / 快慢的随机量改为按 kin-clump 的团块共用（同一团块一起快、一起慢，团块之间沿运动方向拉开）；缺省逐颗独立。
            + (('{uint kj_s=kin_s^0x7f4a7c15u;' if (v.get('kin-jit-clump') and kclump > 0) else '{uint kj_s=floatBitsToUint(m.random.y)^floatBitsToUint(m.random.z)^0x7f4a7c15u;') +
               'float kj_a=transport_unit(kj_s)+transport_unit(kj_s^0x2b2f4e3du)+transport_unit(kj_s^0x632be5abu)-1.5;'
               'float kj_b=transport_unit(kj_s^0x85ebca6bu)+transport_unit(kj_s^0xc2b2ae35u)+transport_unit(kj_s^0x27d4eb2fu)-1.5;'
               f'float kj_t=radians({float(v.get("kin-jit", 0.)):.6f})*kj_a*2.' + ('*kd_m' if v.get('kin-disp') else '') + ';'
               'kin_v=mat2(cos(kj_t),sin(kj_t),-sin(kj_t),cos(kj_t))*kin_v'
               f'*exp(({float(v.get("kin-jit-s", 0.)):.6f})*kj_b*2.' + ('*kd_m' if v.get('kin-disp') else '') + ');}' if (v.get('kin-jit') or v.get('kin-jit-s')) else '')
            + f'kin_v*=smoothstep(0.,{max(kramp, 1e-4):.6f},kin_age);'
            + (f'kin_v+=keu_f((state[i].pos.xy-card*.5)/span)/distance_gain*smoothstep(0.,{max(float(v.get("kin-euler-ramp", .1)), 1e-4):.6f},kin_age);' if v.get('kin-euler') else '')
            + (f'target=mix(target,kin_v*span,{kmix:.6f})+1e-7*target;}}\n    ' if kcross is None else
             f'{{vec2 kin_cross_t=target-wind*dot(target,wind);target=mix(target,kin_v*span+kin_cross_t*({float(kcross):.6f}),{kmix:.6f})+1e-7*target;}}}}\n    ') + old), 1)
    if 'kin' in v:
        compute = compute.replace('void main(){', 'uniform float kin_a_u;\nuniform vec2 kin_v0_u;\nvoid main(){', 1)
        if v.get('kin-disp'):
            # kin-disp=<版本>：逐颗随机幅度的倍数场（出生位置上双线性），绑定点 9 的存储缓冲；用于让原片整齐的流束更整齐、散乱处更散乱。
            anchor = 'vec2 local_anchor(float index){'
            assert compute.count(anchor) == 1, 'kin-disp: ' + anchor
            compute = compute.replace(anchor, (
                'layout(std430,binding=9) readonly buffer KDISP {float kdp_d[];};\nuniform ivec2 kdp_n;\nuniform vec4 kdp_box;\n'
                'float kdisp_f(vec2 q){\n'
                '    if(kdp_n.x<2)return 1.;\n'
                '    vec2 f=clamp((q-kdp_box.xy)/kdp_box.zw,vec2(0.),vec2(kdp_n-1)-1e-4);\n'
                '    ivec2 i0=ivec2(floor(f));ivec2 i1=min(i0+1,kdp_n-1);vec2 t=f-vec2(i0);\n'
                '    float a=mix(kdp_d[i0.y*kdp_n.x+i0.x],kdp_d[i0.y*kdp_n.x+i1.x],t.x);\n'
                '    float b=mix(kdp_d[i1.y*kdp_n.x+i0.x],kdp_d[i1.y*kdp_n.x+i1.x],t.x);\n'
                '    return mix(a,b,t.y);\n'
                '}\n') + anchor, 1)
        if v.get('kin-euler'):
            # kin-euler=<版本>：按当前位置取值的静态速度场（见 scratchpad patch_keuler.py 的说明），绑定点 13（10 是 surface_transfer 的表面交接数据，不能用）。
            anchor = 'vec2 local_anchor(float index){'
            assert compute.count(anchor) == 1, 'kin-euler: ' + anchor
            compute = compute.replace(anchor, (
                'layout(std430,binding=13) readonly buffer KEU {vec2 keu_d[];};\nuniform ivec2 keu_n;\nuniform vec4 keu_box;\nuniform vec3 keu_t;\n'
                'vec2 keu_g(vec2 f,int o){\n'
                '    ivec2 i0=ivec2(floor(f));ivec2 i1=min(i0+1,keu_n-1);vec2 t=f-vec2(i0);\n'
                '    vec2 a=mix(keu_d[o+i0.y*keu_n.x+i0.x],keu_d[o+i0.y*keu_n.x+i1.x],t.x);\n'
                '    vec2 b=mix(keu_d[o+i1.y*keu_n.x+i0.x],keu_d[o+i1.y*keu_n.x+i1.x],t.x);\n'
                '    return mix(a,b,t.y);\n'
                '}\n'
                'vec2 keu_f(vec2 q){\n'
                '    if(keu_n.x<2)return vec2(0.);\n'
                '    vec2 f=clamp((q-keu_box.xy)/keu_box.zw,vec2(0.),vec2(keu_n-1)-1e-4);\n'
                '    int nt=int(keu_t.z+.5);\n'
                '    if(nt<2)return keu_g(f,0);\n'
                '    float u=clamp((time-keu_t.x)/max(keu_t.y-keu_t.x,1e-6)*float(nt-1),0.,float(nt-1)-1e-6);int j0=int(floor(u));float fr=u-float(j0);\n'
                '    int g=keu_n.x*keu_n.y;\n'
                '    return mix(keu_g(f,j0*g),keu_g(f,(j0+1)*g),fr);\n'
                '}\n') + anchor, 1)
        if v.get('kin-uvt'):
            # kin-uvt=<版本>：同 kin-uv，场存在绑定点 8 的存储缓冲里（analysis/kin-uvt-v<版本>.json，不限节点数），用于更细的网格。
            assert not v.get('kin-uv') and not v.get('kin-turb') and kav == 0. and kclk == 0. and not (v.get('site-motion') or v.get('site-push') or v.get('site-v0')), 'kin-uvt'
            anchor = 'vec2 local_anchor(float index){'
            assert compute.count(anchor) == 1, 'kin-uvt: ' + anchor
            compute = compute.replace(anchor, (
                'layout(std430,binding=8) readonly buffer KUVT {vec4 kuv_d[];};\nuniform ivec2 kuv_n;\nuniform vec4 kuv_box;\n'
                'vec4 kuv_gf(vec2 q){\n'
                '    if(kuv_n.x<2)return vec4(0.);\n'
                '    vec2 f=clamp((q-kuv_box.xy)/kuv_box.zw,vec2(0.),vec2(kuv_n-1)-1e-4);\n'
                '    ivec2 i0=ivec2(floor(f));ivec2 i1=min(i0+1,kuv_n-1);vec2 t=f-vec2(i0);\n'
                '    vec4 a=mix(kuv_d[i0.y*kuv_n.x+i0.x],kuv_d[i0.y*kuv_n.x+i1.x],t.x);\n'
                '    vec4 b=mix(kuv_d[i1.y*kuv_n.x+i0.x],kuv_d[i1.y*kuv_n.x+i1.x],t.x);\n'
                '    return mix(a,b,t.y);\n'
                '}\n') + anchor, 1)
        if v.get('kin-uv'):
            # u + a·年龄 只在加速度系数恒为 1 时等价替换（没有逐颗加速度起伏、起点归属、全局时刻混合），与 kin-turb 不同用。
            assert not v.get('kin-turb') and kav == 0. and kclk == 0. and not (v.get('site-motion') or v.get('site-push') or v.get('site-v0')), 'kin-uv'
            anchor = 'vec2 local_anchor(float index){'
            assert compute.count(anchor) == 1, 'kin-uv: ' + anchor
            compute = compute.replace(anchor, (
                'uniform vec4 kuv_g[144];\nuniform ivec2 kuv_n;\nuniform vec4 kuv_box;\n'
                'vec4 kuv_gf(vec2 q){\n'
                '    if(kuv_n.x<2)return vec4(0.);\n'
                '    vec2 f=clamp((q-kuv_box.xy)/kuv_box.zw,vec2(0.),vec2(kuv_n-1)-1e-4);\n'
                '    ivec2 i0=ivec2(floor(f));ivec2 i1=min(i0+1,kuv_n-1);vec2 t=f-vec2(i0);\n'
                '    vec4 a=mix(kuv_g[i0.y*kuv_n.x+i0.x],kuv_g[i0.y*kuv_n.x+i1.x],t.x);\n'
                '    vec4 b=mix(kuv_g[i1.y*kuv_n.x+i0.x],kuv_g[i1.y*kuv_n.x+i1.x],t.x);\n'
                '    return mix(a,b,t.y);\n'
                '}\n') + anchor, 1)
        if v.get('kin-turb'):
            anchor = 'vec2 local_anchor(float index){'
            assert compute.count(anchor) == 1, 'kin-turb: ' + anchor
            compute = compute.replace(anchor, (
                'uniform vec2 kt_g[144];\nuniform ivec2 kt_n;\nuniform vec4 kt_box;\n'
                'vec2 kturb_g(vec2 q){\n'
                '    if(kt_n.x<2)return vec2(0.);\n'
                '    vec2 f=clamp((q-kt_box.xy)/kt_box.zw,vec2(0.),vec2(kt_n-1)-1e-4);\n'
                '    ivec2 i0=ivec2(floor(f));ivec2 i1=min(i0+1,kt_n-1);vec2 t=f-vec2(i0);\n'
                '    vec2 a=mix(kt_g[i0.y*kt_n.x+i0.x],kt_g[i0.y*kt_n.x+i1.x],t.x);\n'
                '    vec2 b=mix(kt_g[i1.y*kt_n.x+i0.x],kt_g[i1.y*kt_n.x+i1.x],t.x);\n'
                '    return mix(a,b,t.y);\n'
                '}\n'
                + ('uniform vec2 kt2_g[144];\nuniform ivec2 kt2_n;\nuniform vec4 kt2_box;\n'
                   'vec2 kturb2_g(vec2 q){\n'
                   '    if(kt2_n.x<2)return vec2(0.);\n'
                   '    vec2 f=clamp((q-kt2_box.xy)/kt2_box.zw,vec2(0.),vec2(kt2_n-1)-1e-4);\n'
                   '    ivec2 i0=ivec2(floor(f));ivec2 i1=min(i0+1,kt2_n-1);vec2 t=f-vec2(i0);\n'
                   '    vec2 a=mix(kt2_g[i0.y*kt2_n.x+i0.x],kt2_g[i0.y*kt2_n.x+i1.x],t.x);\n'
                   '    vec2 b=mix(kt2_g[i1.y*kt2_n.x+i0.x],kt2_g[i1.y*kt2_n.x+i1.x],t.x);\n'
                   '    return mix(a,b,t.y);\n'
                   '}\n' if v.get('kin-turb2') else '')) + anchor, 1)
        if v.get('kin-dir'):
            # kin-dir 的随机场：三个频段（波长 L、L/2、L/4，权重 1、0.5、0.25）的 sin·cos 乘积之和，归一到单位标准差；
            # 朝向与相位由种子决定（kin-dir-seed=n 或 kin-dir-seed-table=<版本>），方向场与速度场各用一组。
            kdl = float(v.get('kin-dir-l', .8))
            anchor = 'vec2 local_anchor(float index){'
            assert compute.count(anchor) == 1, 'kin-dir: ' + anchor
            compute = compute.replace(anchor, (
                'uniform vec3 kd_pa0,kd_pb0,kd_pa1,kd_pb1;\nuniform vec2 kd_rot;\n'
                'float kdir_f(vec2 q,float e,int which){\n'
                '    float s=0.;\n'
                '    for(int k=0;k<3;k++){\n'
                f'        float lam=({kdl:.6f})*(k==0?1.:k==1?.5:.25);\n'
                '        float ang=(which==0?kd_rot.x:kd_rot.y)+float(k)*2.0944;\n'
                '        vec2 u=vec2(cos(ang),sin(ang)),z=vec2(-u.y,u.x);\n'
                '        float a=dot(q,u)/lam*6.2831853+(which==0?kd_pa0[k]:kd_pa1[k])+e*(1.+.37*float(k));\n'
                '        float b=dot(q,z)/lam*6.2831853+(which==0?kd_pb0[k]:kd_pb1[k])-e*(.7+.29*float(k));\n'
                '        s+=sin(a)*cos(b)*(k==0?1.:k==1?.5:.25);\n'
                '    }\n'
                '    return s*1.745743;\n'
                '}\n') + anchor, 1)
        if ('kin-curl-seed' in v) or ('kin-curl-seed-table' in v):
            # 与基础模型 curl() 同一个三频段势场的旋转梯度，只把相位与朝向换成种子给的值。
            # kin-curl-mix=θ（度）：输出改为 cosθ × 旋转梯度（无散、只卷不聚）+ sinθ × 梯度本身（势流、会把颗粒汇聚到势的脊上），
            # 90 为纯势流。原片的细丝、窄股与钩形像是颗粒在汇聚线上堆积，无散场造不出这种堆积。缺省不变。
            kmixd = v.get('kin-curl-mix')
            line = '        v+=vec2(grad.y,-grad.x)*(k==0?1.:k==1?.38:.12);\n'
            if kmixd is not None:
                cm, sm = np.cos(np.radians(float(kmixd))), np.sin(np.radians(float(kmixd)))
                line = f'        v+=(({cm:.6f})*vec2(grad.y,-grad.x)+({sm:.6f})*grad)*(k==0?1.:k==1?.38:.12);\n'
            anchor = 'vec2 local_anchor(float index){'
            assert compute.count(anchor) == 1, 'kin-curl-seed: ' + anchor
            compute = compute.replace(anchor, (
                'uniform vec3 kc_pa,kc_pb;\nuniform float kc_rot;\n'
                'vec2 kcurl_s(vec2 p,float t){\n'
                '    vec2 v=vec2(0);\n'
                '    for(int k=0;k<3;k++){\n'
                '        float scale=span*(k==0?1.12:k==1?.51:.23);\n'
                '        float angle=atan(wind.y,wind.x)+.61+float(k)*1.71+kc_rot;\n'
                '        vec2 u=vec2(cos(angle),sin(angle)),z=vec2(-u.y,u.x);\n'
                '        float a=dot(p,u)/scale*6.283+t*(1.2+float(k)*.5)+kc_pa[k];\n'
                '        float b=dot(p,z)/scale*6.283-t*(.8+float(k)*.43)+kc_pb[k];\n'
                '        vec2 grad=(cos(a)*cos(b)*u-sin(a)*sin(b)*z);\n'
                + line +
                '    }\n'
                '    return v;\n'
                '}\n') + anchor, 1)
        if v.get('kin-aff'):
            compute = compute.replace('void main(){', 'uniform vec2 kin_aff_t;\nuniform vec4 kin_aff_m;\nvoid main(){', 1)
        if v.get('site-v0'):
            compute = compute.replace('void main(){', 'uniform vec2 ps_v[6];\nvoid main(){', 1)
        if v.get('site-motion') or v.get('site-push') or v.get('site-v0'):
            compute = compute.replace('void main(){', 'uniform int ps_n;\nuniform vec4 ps_a[6];\nuniform vec4 ps_b[6];\nuniform vec3 ps_s;\nuniform vec2 ps_w;\nvoid main(){', 1)
    if 'kin-relax' in v:
        # kin-relax=k：速度松弛时间常数乘 k（k=0 立即跟随目标速度），见 rebuild-2026-10-01-plan.md「KDV2」。
        kk = float(v['kin-relax'])
        old = 'float response=1.-exp(-h/(.018+.036*m.random.z));'
        assert compute.count(old) == 1, 'kin-relax: ' + old
        compute = compute.replace(old, f'float response=1.-exp(-h/max((.018+.036*m.random.z)*({kk:.6f}),1e-6));', 1)
    if 'curl-static' in v:
        # 静态卷动场（OpenHarmony RenderService 粒子扰动场的做法：curl 噪声在屏幕上固定、不随时间变化）：
        # 卷动场按卡片坐标取样，不再随风平移、不带时间相位；权重由「引导缺失处才加」改为全卡片同一系数 k。所有段同值。
        import re as _re
        k = float(v['curl-static'])
        old = 'vec2 q=p-card*.5-wind*span*(.43*time+.16*time*time);'
        assert compute.count(old) == 1, 'curl-static: ' + old
        compute = compute.replace(old, 'vec2 q=p-card*.5+1e-12*wind*time;', 1)
        compute, nsub = _re.subn(r'vec2 flow=curl\(q,time\)', 'vec2 flow=curl(q,0.)', compute, count=1)
        assert nsub == 1, 'curl-static: flow 行'
        old = 'target+=flow*mix(.018,.44+.06*reach,missing)*smoothstep(.01,.09,age);'
        assert compute.count(old) == 1, 'curl-static: ' + old
        compute = compute.replace(old, f'target+=flow*({k:.6f})*smoothstep(.01,.09,age)+1e-12*missing;', 1)
    if 'spread-wind' in v:
        # 边缘外展只在顺风一侧的边缘生效：外展方向与风向的点积经 smoothstep(−0.1, 0.5) 加权，k 为加权比例（1 为完全按风向门控）。
        # 逆风一侧的边缘（如风向左上时卡片的右缘、下缘）不再把刚释放的颗粒朝外推，避免出生时逆风运动；所有段同值。
        k = float(v['spread-wind'])
        old = 'target+=outward_spread*span*.4*(.05+2.4*m.random.z*m.random.z)*spread_age;'
        if compute.count(old) != 1:
            old = next(l.strip() for l in compute.split('\n') if l.strip().startswith('target+=outward_spread*span*('))
        compute = compute.replace(old, f'spread_age*=mix(1.,smoothstep(-.1,.5,dot(normalize(outward_spread+vec2(.000001)),wind)),{k:.6f});\n    ' + old, 1)
    vertex = cls.vertex_source; fragment = cls.fragment_source
    if 'xfer-win-a' in v or 'xfer-win-b' in v or 'xfer-y' in v:
        # 交接链软淡出的窗口：extended = 1 − smoothstep(时钟 − a − y·k, 时钟 + b + y·k, 时间)，缺省 a = 0.025、b = 0.060、k = 1。
        # 原片前沿的卡面同样先变透明、变模糊再碎开，但只用 0.03～0.05 进度；模型窗口至少 0.085，已释放区域留下一大片半透明的淡出卡面，
        # 浅色背景上看起来是一层发白的薄雾（参考 7、科比、灭霸，scratchpad vgx-*.jpg）。所有段同值。
        xa = float(v.get('xfer-win-a', .025)); xb = float(v.get('xfer-win-b', .060)); xk = float(v.get('xfer-y', 1.))
        # 该行在链里带 handoff_width 加宽项，按行前缀定位，只替换窗口两端常数与 y 的系数。
        lines_x = vertex.split('\n'); hit_x = 0
        for k_, l_ in enumerate(lines_x):
            if l_.strip().startswith('float extended=1.-smoothstep(max(.001,source_clock-.025-transfer.y'):
                l_ = l_.replace('source_clock-.025-transfer.y', f'source_clock-({xa:.6f})-transfer.y*({xk:.6f})', 1)
                l_ = l_.replace('max(.018,source_clock+.060+transfer.y', f'max(.004,source_clock+({xb:.6f})+transfer.y*({xk:.6f})', 1)
                lines_x[k_] = l_; hit_x += 1
        assert hit_x == 1, f'xfer-win: 改到 {hit_x} 行'
        vertex = '\n'.join(lines_x)
    if 'surf-win' in v or 'surf-hard' in v:
        # 表面淡出窗口与硬切比例（缺省 0.025 / 0.060 与 0.85）：surf-win-a、surf-win-b 为淡出开始（释放前）与结束（释放后）的进度，
        # surf-hard 为软淡出所占比例（0 为释放时刻硬切）。依据：原片前沿剖面在局部年龄 0 处很陡（可见密度 0.13 → 0.42 只用 0.04），
        # 模型在释放前 0.05 就已有 0.12～0.21——半透明的淡出表面本身被算成了「可见颗粒」（scratchpad front_profile.py）。所有段同值。
        sa_ = float(v.get('surf-win-a', .025)); sb_ = float(v.get('surf-win-b', .060)); sh_ = float(v.get('surf-hard', .85))
        # 交接链（surface transfer）把这一行改写成带 handoff_width / handoff_time 的形式，两种写法都只替换窗口常数。
        lines_ = vertex.split('\n'); hit_ = 0
        for k_, l_ in enumerate(lines_):
            s_ = l_.strip()
            if s_.startswith('surface_out=1.-smoothstep(max(.001,source_clock-.025'):
                lines_[k_] = l_.replace('source_clock-.025', f'source_clock-({sa_:.6f})').replace('source_clock+.060', f'source_clock+({sb_:.6f})').replace('max(.018,', 'max(.002,'); hit_ += 1
            elif s_.startswith('surface_out=mix(') and '?1.:0.,surface_out,.85*(1.-.60*panel_weight));' in s_:
                lines_[k_] = l_.replace('.85*(1.-.60*panel_weight)', f'({sh_:.6f})*(1.-.60*panel_weight)'); hit_ += 1
        assert hit_ == 2, f'surf-win/surf-hard: 只改到 {hit_} 行'
        vertex = '\n'.join(lines_)
    if 'sheet' in v and v.get('sheet-cut', 1.) != 0:
        # sheet 的顶点部分（见上方 compute 部分的说明）：圆点切形与尺寸随机化的开始时刻推迟 a；sheet-cut=0 时只保留运动部分。
        sa = float(v['sheet'])
        old = 'shape_out=mix(smoothstep(.004,.060,age),loosen,.85*panel_weight);'
        assert vertex.count(old) == 1, 'sheet: ' + old
        vertex = vertex.replace(old, f'shape_out=mix(smoothstep({.004 + sa:.6f},{.060 + sa:.6f},age),loosen,.85*panel_weight);', 1)
        old = 'float loosen=smoothstep(.018,.18,age);'
        assert vertex.count(old) == 1, 'sheet: ' + old
        vertex = vertex.replace(old, f'float loosen=smoothstep({.018 + sa:.6f},{.18 + sa:.6f},age);', 1)
    if 'surface-glow' in v or 'surface-white' in v:
        # 前沿亮带（钢铁侠原片 4 倍逐帧：未释放表面与已释放区之间有一条约 16 像素、比原位亮 20～70 灰阶的平滑亮带）：
        # 正在淡出的表面按 4s(1−s) 加权提亮，s 为表面剩余不透明度，淡出中段最亮、两端为 0。
        # surface-glow=k 为线性光里的乘性提亮（中段 ×(1+k)），surface-white=w 为向白色混合的比例（中段 w）。所有段同值。
        kg = float(v.get('surface-glow', 0.)); kw_ = float(v.get('surface-white', 0.))
        # surface-glow-p=q：权重改为 s^q·(1−s) 归一到峰值 1（q 越大越靠近淡出开头、表面仍不透明的一段），缺省 1 即 4s(1−s)。
        gq = max(float(v.get('surface-glow-p', 1.)), 1e-3); gnorm = 1. / (((gq / (gq + 1.)) ** gq) * (1. / (gq + 1.)))
        old = 'vec3 surface_color=diagnostic==1?vec3(.05,.45,.95):linear(src.rgb);'
        assert fragment.count(old) == 1, 'surface-glow: ' + old
        fragment = fragment.replace(old, old + f'\n        {{float glow_w=({gnorm:.6f})*pow(max(surface_out,0.),{gq:.6f})*(1.-surface_out);surface_color*=1.+({kg:.6f})*glow_w;'
                                    f'surface_color=mix(surface_color,vec3(1.),({kw_:.6f})*glow_w);}}', 1)
    if 'particle-flash' in v:
        # 刚出生颗粒的短暂提亮：lighting ×(1 + k·(age/a)·e^(1−age/a))，年龄 a（particle-flash-age，缺省 0.012）时最亮，之后回落；
        # 刚释放、尚未散开的颗粒集中在前沿，提亮后形成贴着前沿的亮带。所有段同值。
        kf = float(v['particle-flash']); af = max(float(v.get('particle-flash-age', .012)), 1e-4)
        old = 'lighting*=1.+.32*light_gain*smoothstep(.006,.045,age)*(1.-smoothstep(.11,.28,age));'
        assert fragment.count(old) == 1, 'particle-flash: ' + old
        fragment = fragment.replace(old, old + f'\n    lighting*=1.+({kf:.6f})*(max(age,0.)/({af:.6f}))*exp(1.-max(age,0.)/({af:.6f}));', 1)
    if 'pcolor' in v:
        # 颗粒颜色的保真度：pcolor=k 时最终颜色 = mix(源色 × 逐颗光照, 着色链结果, k)；k = 1 为原链，k = 0 只保留源色与逐颗明暗。
        # 着色链里 pow 0.86、×1.25、+32% 提亮与白色抬升叠在一起会把饱和色顶到通道上限再被去白，科比的黄色变成奶油色；
        # 原片颗粒基本保持源色（彩度中位 科比 31 对模型 27、灭霸 37 对 30，scratchpad particle_colors.py）。所有段同值。
        old = 'if(diagnostic==1)color=vec3(1.,.20,.07);'
        assert fragment.count(old) == 1, 'pcolor: ' + old
        # pgain=g：源色一支整体乘 g（线性空间，输出时按 sRGB 截断；g > 1 时饱和色先在亮的通道触顶，色相向更亮的一侧移动）。
        # 圆点外观（spark）的片元里源色一支同样乘 spark_gain：否则 pcolor=0 时 spark_gain 不再被引用、被编译器去掉，设 uniform 时报错。
        sg_ = '*mix(1.,spark_gain,spark_on)' if 'spark_gain' in fragment and 'spark_on' in fragment else ''
        fragment = fragment.replace(old, f'color=mix(linear(src.rgb)*mix(1.,light_out,light_gain)*({float(v.get("pgain", 1.)):.6f}){sg_},color,{float(v["pcolor"]):.6f});\n    ' + old, 1)
    if 'pcolor-old' in v:
        # 颗粒颜色随年龄回到源色：年龄 pcolor-a0～pcolor-a1（缺省 0.08～0.25）之间，着色链结果的权重由 1 平滑降到 pcolor-old（0 为完全回到源色 × 逐颗明暗）。
        # 依据：原片刚释放的颗粒亮于源色（科比黄色球衣变柠檬黄），老颗粒接近源色（灭霸已释放区域的橙色颗粒融进同色背景、画面平滑），
        # 九月底 4.23 节「全程提亮」与「全程不提亮」各对一半；模型全程提亮，老颗粒在同色背景上成一层发白的雾。所有段同值。
        old = 'if(diagnostic==1)color=vec3(1.,.20,.07);'
        assert fragment.count(old) == 1, 'pcolor-old: ' + old
        pa0 = float(v.get('pcolor-a0', .08)); pa1 = max(float(v.get('pcolor-a1', .25)), pa0 + 1e-3)
        fragment = fragment.replace(old, f'{{float pco_k=mix(1.,{float(v["pcolor-old"]):.6f},smoothstep({pa0:.6f},{pa1:.6f},age));'
                                         f'color=mix(linear(src.rgb)*mix(1.,light_out,light_gain),color,pco_k);}}\n    ' + old, 1)
    if 'psat' in v:
        # 颗粒饱和度补偿：着色链之后按线性亮度 Y 拉开色度，color = max(Y + (color − Y)·(1 + k), 0)。着色链的亮度与原片一致
        # （L 中位 科比 70 对 72、灭霸 49 对 52、钢铁侠 45 对 45），但提亮时通道触顶再去白，彩度低一到一成半；直接用源色（pcolor=0）
        # 又整体偏暗（科比 66、灭霸 43）。所有段同值。
        old = 'if(diagnostic==1)color=vec3(1.,.20,.07);'
        assert fragment.count(old) == 1, 'psat: ' + old
        fragment = fragment.replace(old, f'{{float psat_y=dot(color,vec3(.2126,.7152,.0722));color=max(vec3(psat_y)+(color-vec3(psat_y))*(1.+({float(v["psat"]):.6f})),vec3(0.));}}\n    ' + old, 1)
    if 'light-hold' in v:
        # 颗粒提亮在年龄 0.11～0.28 之间撤回的比例改为 1 − h（h=1 为一直保持 +32% 的提亮）：逐颗跟踪显示原片老颗粒的亮度不回落，
        # 钢铁侠 τ 0.25～0.4 颗粒亮度减出生处原位亮度 中位 / 90 分位 −5/+49，模型 −32/+26。所有段同值。
        hk = float(v['light-hold'])
        old = 'lighting*=1.+.32*light_gain*smoothstep(.006,.045,age)*(1.-smoothstep(.11,.28,age));'
        assert fragment.count(old) == 1, 'light-hold: ' + old
        fragment = fragment.replace(old, f'lighting*=1.+.32*light_gain*smoothstep(.006,.045,age)*(1.-(1.-({hk:.6f}))*smoothstep(.11,.28,age));', 1)
    if 'popa' in v:
        # 颗粒整体不透明度乘 k（表面不受影响）：原片颗粒与身后背景混合——薰衣草色颗粒在橙底上呈粉白、深色颗粒呈零散的棕色、
        # 藏青颗粒在白底上呈灰蓝，模型颗粒基本不透明、在任何背景上都是源色（scratchpad thanos-src-region.jpg）。重叠密集处的
        # 累计不透明度仍接近 1，稀疏处变透明。所有段同值。
        old = 'if(diagnostic==1)color=vec3(1.,.20,.07);'
        assert fragment.count(old) == 1, 'popa: ' + old
        fragment = fragment.replace(old, old + f'\n    alpha*={float(v["popa"]):.6f};', 1)
    if 'pfade-in' in v:
        # 颗粒出生后的淡入时长（缺省 0.022 进度）改为给定值。所有段同值。
        old = 'float alpha=src.a*fade*shape*(1.-surface_out)*smoothstep(.001,.022,age);'
        assert fragment.count(old) == 1, 'pfade-in: ' + old
        fragment = fragment.replace(old, f'float alpha=src.a*fade*shape*(1.-surface_out)*smoothstep(.0005,{max(float(v["pfade-in"]), .001):.6f},age);', 1)
    if 'light-ramp' in v or 'flash' in v:
        # 新颗粒的提亮起步：light-ramp-a、light-ramp-b 替换 +32% 提亮的起止年龄（缺省 0.006 / 0.045）；flash=k 再加一次出生即亮、
        # 按 exp(−年龄/flash-tau) 消退的提亮 k。依据：原片刚解除处的像素 0.02～0.04 岁就有 0.74（科比）/ 0.52（灭霸）与原位源图不同，
        # 模型同年龄的颗粒大多还停在原位、颜色也还没变，被算成「仍是原图」（scratchpad young_cov.py）。所有段同值。
        ra = float(v.get('light-ramp-a', .006)); rb = float(v.get('light-ramp-b', .045))
        fk = float(v.get('flash', 0.)); ft = max(float(v.get('flash-tau', .05)), 1e-4)
        old = 'lighting*=1.+.32*light_gain*smoothstep(.006,.045,age)'
        assert fragment.count(old) == 1, 'light-ramp: ' + old
        fragment = fragment.replace(old, f'lighting*=1.+({fk:.6f})*light_gain*exp(-age/({ft:.6f}));\n    lighting*=1.+.32*light_gain*smoothstep({ra:.6f},{max(rb, ra + 1e-4):.6f},age)', 1)
    if 'pkeep' in v:
        # 颗粒保留比例乘 k：条件材料规则在出生后 0.008～0.06 岁丢弃一部分颗粒（conditional_keep，layout-g=0.5 时 0.64），这里只改保留比例、不改尺寸。
        # 依据：已释放区域里模型的颗粒覆盖率比原片高（旧蜘蛛侠 0.44 对 0.19、浅色背景 0.53 对 0.42，scratchpad released_coverage.py）。所有段同值。
        kk_ = float(v['pkeep'])
        old = 'float retain=step(fract(random_out.x*47.123+random_out.z*19.817),conditional_keep);'
        if fragment.count(old) == 1:
            fragment = fragment.replace(old, f'float retain=step(fract(random_out.x*47.123+random_out.z*19.817),conditional_keep*({kk_:.6f}));', 1)
        else:
            # 覆盖率版本（particle_coverage）：保留比例与按覆盖量放宽的保留比例都乘 k。
            o1 = 'float keep=clamp(conditional_keep*exp(count_shift),0.,1.);'
            o2 = 'float retain=mix(step(random_keep,conditional_keep),'
            assert fragment.count(o1) == 1 and fragment.count(o2) == 1, 'pkeep: 找不到保留比例'
            fragment = fragment.replace(o1, f'float keep=clamp(conditional_keep*exp(count_shift)*({kk_:.6f}),0.,1.);', 1)
            fragment = fragment.replace(o2, f'float retain=mix(step(random_keep,conditional_keep*({kk_:.6f})),', 1)
    if 'age-glow' in v:
        # 颗粒随年龄提亮（辉光）：一部分颗粒（逐颗固定，比例 age-glow-frac，缺省 1）在线性颜色空间里向白色混合 k·smoothstep(a0, a1, 年龄)。
        # 依据：原片浅底上的暗颗粒消失得比模型早、暗底上的亮颗粒留得比模型久，中后段颗粒相对背景的亮端比模型亮 10～50 个 L 单位
        # （rebuild-2026-10-01-plan.md 10-03 凌晨），符合 OpenHarmony 粒子消散滤镜的 GlowLevel / GlowBrightness。所有段同值。
        kg = float(v['age-glow']); g0 = float(v.get('age-glow-a0', .05)); g1 = float(v.get('age-glow-a1', .3)); gf = float(v.get('age-glow-frac', 1.))
        old = 'if(srgbp_on>.5)color=srgbp_encode(max(color,vec3(0.)));'
        anchor = old if fragment.count(old) == 1 else 'frag=vec4(color*alpha,alpha);'
        assert fragment.count(anchor) == 1, 'age-glow: ' + anchor
        fragment = fragment.replace(anchor, (f'{{float ag_w=smoothstep({g0:.6f},{max(g1, g0 + .001):.6f},age)*step(fract(random_out.y*29.173+random_out.x*7.317),{gf:.6f});'
                                             f'color=mix(color,vec3(1.),({kg:.6f})*ag_w);}}\n    ') + anchor, 1)
    if 'age-fade' in v:
        # 颗粒随年龄向身后背景收敛（原片后段可见颗粒的亮度差比模型小三到四成）：不透明度乘 1 − k·smoothstep(a0, a1, 年龄)，
        # 在细粒致密化之后施加（之前施加会被 1−(1−α)^e 的放大抵消，实测几乎无效）。所有段同值。
        kf = float(v['age-fade']); a0 = float(v.get('age-fade-a0', .15)); a1 = float(v.get('age-fade-a1', .45))
        old = next((l.strip() for l in fragment.splitlines() if l.strip().startswith('alpha=1.-pow(max(1.-alpha,.00001),exp(')), None)
        assert old is not None and fragment.count(old) == 1, 'age-fade 需要细粒外观链'
        fragment = fragment.replace(old, old + f'\n    alpha*=1.-({kf:.6f})*smoothstep({a0:.6f},{max(a1, a0 + .001):.6f},age);', 1)
    if 'lum-fade' in v:
        # lum-fade=k：暗颗粒随年龄淡出得更快（见 scratchpad patch_lumfade.py 的说明）；暗度 d = 1 − smoothstep(0.15, 0.7, 源色 sRGB 亮度)。
        kl = float(v['lum-fade']); l0 = float(v.get('lum-fade-a0', .15)); l1 = float(v.get('lum-fade-a1', .4))
        old_l = next((l.strip() for l in fragment.splitlines() if l.strip().startswith('alpha=1.-pow(max(1.-alpha,.00001),exp(')), None)
        assert old_l is not None and fragment.count(old_l) == 1, 'lum-fade 需要细粒外观链'
        fragment = fragment.replace(old_l, old_l + f'\n    {{float lf_d=1.-smoothstep(.15,.7,dot(src.rgb,vec3(.2126,.7152,.0722)));'
                                    f'alpha*=1.-({kl:.6f})*lf_d*smoothstep({l0:.6f},{max(l1, l0 + .001):.6f},age);}}', 1)
    if 'trailing' in v:
        # 尾缘（前沿朝向最近边缘）颗粒的丢弃比例 .64 与表面交接提前 .065 同乘 k。
        k = max(v['trailing'], 1e-9)
        old = 'if(fract(random_out.y*13.731+random_out.z*17.371)<.64*trailing_out)discard;'
        assert old in fragment
        fragment = fragment.replace(old, f'if(fract(random_out.y*13.731+random_out.z*17.371)<({k:.9g})*.64*trailing_out)discard;')
        old = 'trailing_out=trailing;source_clock-=.065*trailing;'
        assert old in vertex
        vertex = vertex.replace(old, f'trailing_out=trailing;source_clock-=({k:.9g})*.065*trailing;')
    if 'sync-birth' in v:
        # sync-birth=k：尾缘颗粒的出生提前 k·0.065·尾缘系数（材料数组里改，见初始化末尾），表面交接提前量相应改成 (1 − k)·0.065，
        # 卡面消失的时刻不变，颗粒与卡面消失同步出发。与 trailing 不同用。
        assert 'trailing' not in v, 'sync-birth 与 trailing 不同用'
        old = 'trailing_out=trailing;source_clock-=.065*trailing;'
        assert old in vertex, 'sync-birth: ' + old
        vertex = vertex.replace(old, f'trailing_out=trailing;source_clock-=({1. - float(v["sync-birth"]):.9g})*.065*trailing;')
    if 'trail-discard' in v:
        # trail-discard=k：只把尾缘颗粒的丢弃比例 .64 乘 k（表面交接不变）。与 trailing 不同用。
        assert 'trailing' not in v, 'trail-discard 与 trailing 不同用'
        old = 'if(fract(random_out.y*13.731+random_out.z*17.371)<.64*trailing_out)discard;'
        assert old in fragment, 'trail-discard: ' + old
        fragment = fragment.replace(old, f'if(fract(random_out.y*13.731+random_out.z*17.371)<({float(v["trail-discard"]):.9g})*.64*trailing_out)discard;')
    if 'tail' in v:
        # 表面残影：释放后原表面保留 tail·exp(−τ/tail-tau) 的不透明度（只作用于表面这一层，颗粒不变）；
        # tail-lod>0 时残影取源图的模糊层级。
        old = 'surface_out=mix(surface_out,paired,transfer_pairing);'
        assert old in vertex, 'tail 需要表面交接层'
        vertex = vertex.replace('void main(){', 'uniform float tail_amount,tail_tau;\nflat out float surface_tail;\nvoid main(){', 1)
        vertex = vertex.replace(old, old + '\n    surface_tail=tail_amount*exp(-max(time-source_clock,0.)/max(tail_tau,.001));', 1)
        old = 'float a=src.a*surface_out;'
        assert old in fragment
        fragment = fragment.replace('void main(){', 'uniform float tail_lod,tail_dark;\nflat in float surface_tail;\nvoid main(){', 1)
        fragment = fragment.replace(old, 'vec4 tail_src=textureLod(foreground,uv,tail_lod);\n'
                                    '        float a=src.a*surface_out;\n'
                                    '        float tail_a=tail_src.a*surface_tail*(1.-surface_out);\n'
                                    '        if(tail_a>.001&&material_pass==0){frag=vec4(linear(src.rgb)*a+linear(tail_src.rgb)*tail_dark*tail_a,a+tail_a);if(diagnostic!=2)return;}', 1)
    if 'fade-lead' in v or 'fade-lag' in v:
        # 表面淡出窗口：原为 [时钟−0.025, 时钟+0.060]（再各加空间展宽），lead/lag 分别替换两端。
        # 最终着色器里这一行已被相干层改写（加入交接宽度与交接时刻），只替换两端的常数。
        lead = v.get('fade-lead', .025); lag = v.get('fade-lag', .06)
        lines = vertex.split('\n'); hit = 0
        for i, line in enumerate(lines):
            if 'float extended=1.-smoothstep(' in line:
                assert 'source_clock-.025-transfer.y' in line and 'source_clock+.060+transfer.y' in line
                lines[i] = line.replace('source_clock-.025-transfer.y', f'source_clock-({lead:.6f})-transfer.y').replace(
                    'source_clock+.060+transfer.y', f'source_clock+({lag:.6f})+transfer.y'); hit += 1
        assert hit == 1, 'fade-lead 需要表面交接层'
        vertex = '\n'.join(lines)
    if 'keep-floor' in v:
        # 学习密度换算成颗粒数量时的保留下限：限制在陌生布局上的过度削减，修正方向不变。
        old = 'float keep=clamp(conditional_keep*exp(count_shift),0.,1.);'
        assert old in fragment
        fragment = fragment.replace(old, f'float keep=max(clamp(conditional_keep*exp(count_shift),0.,1.),{v["keep-floor"]:.6f});', 1)
    if 'area-cap' in v:
        # 学习密度换算成圆点面积时封顶：正的密度修正是碎片外观为补透明度学出的补偿，不透明圆点不应因此放大；
        # 负值仍经数量削减起作用（count_shift 不变）。area-cap=1 即面积不超过 A1 定标。
        vertex_old = 'float spark_area=mix(1.,exp(.5*(density_out*coverage_area-count_shift)),spark_learned);'
        assert vertex_old in vertex
        vertex = vertex.replace(vertex_old, f'float spark_area=mix(1.,min(exp(.5*(density_out*coverage_area-count_shift)),{v["area-cap"]:.6f}),spark_learned);', 1)
    if 'dens-bright' in v:
        # 学习密度同时换算成圆点亮度：颜色乘 exp(k·density_out)（线性空间，上限 4 倍），与默认碎片外观「按密度叠加不透明度」
        # 让密集处更亮、稀处更暗的作用对应；圆点本身不透明，只换算面积时密集处亮不过源色。
        old = 'color*=mix(1.,spark_gain*(1.+spark_lift_light*spark_raise),spark_on);'
        assert old in fragment
        fragment = fragment.replace(old, old + f'\n    color*=mix(1.,min(exp(({v["dens-bright"]:.6f})*density_out),4.),spark_on);', 1)
    if v.get('zb'):
        # zb=h：起点处的 z 向鼓包 + 透视。颗粒的高度 z = h·exp(−d²/r²)·smoothstep(0, t, 年龄)·exp(−max(年龄 − t, 0)/zb-d)，d 为出生位置到最近起点
        # （线段起点到线段）的距离（短边），h、r 为短边单位；相机在透视中心正上方 zb-cam 个短边处，屏幕位置按 cam/(cam − z) 放大（颗粒也随之变大）。
        # zb-c=card（缺省，卡片中心）/ screen（画面中心）。用户 2026-09-30：华为的粒子化起点像带 z 轴隆起的膨胀，一部分颗粒直接越出原控件边界。
        old = 'vec2 world=offset+vertex;'
        assert vertex.count(old) == 1, 'zb: ' + old
        vertex = vertex.replace('void main(){', 'uniform int zb_n;\nuniform vec4 zb_s[6];\nuniform float zb_h,zb_r,zb_t,zb_d,zb_cam;\nuniform vec2 zb_c;\nvoid main(){', 1)
        vertex = vertex.replace(old, old + (
            '\n    if(material_pass==1&&zb_n>0){float zb_best=1e9;for(int i=0;i<6;i++){if(i>=zb_n)break;vec2 d=(m.src.xy-zb_s[i].xy)/span;'
            'if(zb_s[i].z>0.){if(zb_s[i].w>.5)d.y=sign(d.y)*max(abs(d.y)-zb_s[i].z/span,0.);else d.x=sign(d.x)*max(abs(d.x)-zb_s[i].z/span,0.);}'
            'zb_best=min(zb_best,dot(d,d));}'
            'float zb_z=zb_h*exp(-zb_best/(zb_r*zb_r))*smoothstep(0.,zb_t,age)*exp(-max(age-zb_t,0.)/zb_d);'
            'world=zb_c+(world-zb_c)*zb_cam/max(zb_cam-zb_z*span,zb_cam*.2);}'), 1)
    if 'dens-fade' in v:
        # dens-fade=k：按局部颗粒覆盖量淡出零散颗粒（见渲染部分的说明）。只改颗粒道的不透明度；密团（覆盖量 ≥ d1）不受影响。
        old_df = 'if(alpha<.001)discard;'
        assert fragment.count(old_df) == 1, 'dens-fade: ' + old_df
        fragment = fragment.replace('void main(){', 'uniform sampler2D dens_tex;\nuniform vec2 dens_size;\nuniform float dens_k,dens_d0,dens_d1,dens_a0,dens_a1,dens_lod;\nuniform int dens_pass;\nvoid main(){', 1)
        fragment = fragment.replace(old_df, 'if(material_pass==1&&dens_pass==0&&dens_k>0.){float dens_d=textureLod(dens_tex,gl_FragCoord.xy/dens_size,dens_lod).a;'
                                    'alpha*=1.-dens_k*(1.-smoothstep(dens_d0,max(dens_d1,dens_d0+1e-4),dens_d))*smoothstep(dens_a0,max(dens_a1,dens_a0+1e-4),age);}' + old_df, 1)
    if 'glob-fade' in v:
        # glob-fade=k：整段动画的统一收尾淡出，颗粒道不透明度乘 1 − k·smoothstep(t0, t1, 全局进度)；与顶点着色器共用 time 这个 uniform。
        old_gf = 'if(alpha<.001)discard;'
        assert fragment.count(old_gf) == 1, 'glob-fade: ' + old_gf
        g0 = float(v.get('glob-fade-t0', .45)); g1 = float(v.get('glob-fade-t1', .85))
        if 'uniform float time' not in fragment:
            fragment = fragment.replace('void main(){', 'uniform float time;\nvoid main(){', 1)
        fragment = fragment.replace(old_gf, f'if(material_pass==1)alpha*=1.-({float(v["glob-fade"]):.6f})*smoothstep({g0:.6f},{max(g1, g0 + 1e-4):.6f},time);' + old_gf, 1)
    if 'pvis-pow' in v:
        # 颗粒显现滞后于表面淡出：可见度由 (1−表面不透明度) 改为其 k 次方，表面淡出前段不出现斑点。
        old = 'float alpha=src.a*fade*shape*(1.-surface_out)*smoothstep(.001,.022,age);'
        assert old in fragment
        fragment = fragment.replace(old, f'float alpha=src.a*fade*shape*pow(max(1.-surface_out,0.),{v["pvis-pow"]:.6f})*smoothstep(.001,.022,age);', 1)
    if v.get('srgb-blend'):
        # 在 sRGB 编码空间混合（系统界面常见做法）：颜色不再线性化，背景与输出两道同样直接使用 sRGB 值。
        old = 'vec3 linear(vec3 c){return mix(c/12.92,pow((c+.055)/1.055,vec3(2.4)),step(vec3(.04045),c));}'
        assert old in fragment
        fragment = fragment.replace(old, 'vec3 linear(vec3 c){return c;}')
    if 'dramp-early' in v:
        # 显示位移的年龄斜坡：颗粒相对出生位置的总显示位移（积分位置 + 学习输运位移）乘 mix(e,1,smoothstep(0,A,age))。
        # 与放慢引导项（gramp）不同，它不让输运场随时间的变化在早期占主导，总位移沿风向增长时不会出现后退。
        old = 'move+=transport.xy*span*smoothstep(0.,.03,age);'
        assert old in vertex, 'dramp 需要共享输运层'
        vertex = vertex.replace(old, old + '\n    move=m.src.xy+(move-m.src.xy)*mix(dramp_early,1.,smoothstep(0.,dramp_age,max(age,0.)));', 1)
        vertex = vertex.replace('void main(){', 'uniform float dramp_early,dramp_age;\nvoid main(){', 1)
    if 'fglow' in v:
        # 前沿亮带（溶解特效常见的「边缘发光」）：每块表面在自己的交接时钟附近一个窄时间窗内提亮，
        # g = exp(−((t − 交接时钟 − off)/w)²)，窗宽 w 乘前沿推进速度即亮带宽度（灭霸前沿约 1100 像素/进度，w 0.012 约 13 像素）。
        # 表面道：线性光乘 1+k·g，再向白色混合 white·g；颗粒道（刚在前沿出生、尚未散开的颗粒）同样处理再乘 eg-part。
        # 原 surface-glow 挂在 0.085 宽的表面淡出窗口上，出现的是一片亮雾；这里只跟随每块自己的交接时钟。所有段同值。
        old = 'surface_out=mix(surface_out,paired,transfer_pairing);'
        if vertex.count(old) != 1:
            old = 'surface_out=mix(handoff_time<=m.src.z?1.:0.,surface_out,.85*(1.-.60*panel_weight));' if 'handoff_time' in vertex else 'surface_out=mix(time<=m.src.z?1.:0.,surface_out,.85*(1.-.60*panel_weight));'
        assert vertex.count(old) == 1, 'fglow: ' + old
        clock_time = 'handoff_time' if 'handoff_time' in vertex else 'time'
        vertex = vertex.replace(old, old + f'\n    edge_glow_out=exp(-pow(({clock_time}-source_clock-eg_off)/max(eg_w,.0005),2.));', 1)
        vertex = vertex.replace('void main(){', 'uniform float eg_off,eg_w;\nflat out float edge_glow_out;\nvoid main(){', 1)
        old = 'vec3 surface_color=diagnostic==1?vec3(.05,.45,.95):linear(src.rgb);'
        assert fragment.count(old) == 1, 'fglow: ' + old
        fragment = fragment.replace(old, old + '\n        surface_color*=1.+eg_k*edge_glow_out;surface_color=mix(surface_color,vec3(1.),eg_white*edge_glow_out);', 1)
        old = 'vec3 color=linear(src.rgb);'
        assert fragment.count(old) == 1, 'fglow: ' + old
        fragment = fragment.replace(old, old + '\n    color*=1.+eg_k*eg_part*edge_glow_out;color=mix(color,vec3(1.),eg_white*eg_part*edge_glow_out);', 1)
        fragment = fragment.replace('void main(){', 'uniform float eg_k,eg_white,eg_part;\nflat in float edge_glow_out;\nvoid main(){', 1)
    if 'young-sheet' in v or 'young-glow' in v or 'young-white' in v:
        # 新颗粒在散开前成片、发亮：原片前沿后方 0.03～0.09 岁的材料仍接近满覆盖、比源色亮 20～70 灰阶（灭霸下巴、钢铁侠右上的亮带，
        # 落在原片解除时刻 t−0.09～t−0.03 的等值线之间）；模型的新颗粒在这个年龄已随机错开，覆盖率掉到约一半、露出底色。
        # 权重 w = smoothstep(.004,.02,年龄)·(1 − smoothstep(a0,a1,年龄))：颗粒尺寸乘 1+s·w（young-sheet=s），颜色在线性光里乘 1+k·w（young-glow=k），再向白色混合 m·w（young-white=m，原片亮带偏白）。
        # young-a0 / young-a1 缺省 0.06 / 0.12。young-front=+1 只作用在 forward_front（−n 与风同向）的前沿上，−1 只作用在 n 与风同向的前沿上，0 为全部。所有段同值。
        old = 'vec2 vertex=move+'
        assert vertex.count(old) == 1, 'young-sheet: ' + old
        vertex = vertex.replace(old, 'scale*=1.+ys_s*smoothstep(.004,.02,age)*(1.-smoothstep(ys_a0,ys_a1,age));\n    '
                                'young_front_out=yf_s>.5?smoothstep(.20,.85,dot(-m.physical.xy,wind)):(yf_s<-.5?smoothstep(.20,.85,dot(m.physical.xy,wind)):1.);\n    ' + old, 1)
        vertex = vertex.replace('void main(){', 'uniform float ys_s,ys_a0,ys_a1,yf_s;\nflat out float young_front_out;\nvoid main(){', 1)
        old = 'vec3 color=linear(src.rgb);'
        assert fragment.count(old) == 1, 'young-glow: ' + old
        fragment = fragment.replace(old, old + '\n    {float yg_w=young_front_out*smoothstep(.004,.02,age)*(1.-smoothstep(yg_a0,yg_a1,age));color*=1.+yg_k*yg_w;color=mix(color,vec3(1.),yw_k*yg_w);}', 1)
        fragment = fragment.replace('void main(){', 'uniform float yg_k,yw_k,yg_a0,yg_a1;\nflat in float young_front_out;\nvoid main(){', 1)
    if 'white-add' in v or 'glint-k' in v:
        # 颗粒光照里加在所有颗粒上的常数白光（线性 0.012）与随机闪点 glint：对亮颗粒影响小，对暗颗粒相当于在暗端加了近 30 个灰阶的白，
        # 深红褐色材料被冲成灰色（地球背景头部原片是一团暗红颗粒云，模型是灰点）。white-add=k 乘常数项，glint-k=k 乘闪点。所有段同值。
        old = 'color=color*lighting*(1.+0.2493839*optical_loosen*light_gain)+vec3(0.0120000+glint)*optical_loosen*light_gain;'
        assert fragment.count(old) == 1, 'white-add: ' + old
        wa = float(v.get('white-add', 1.)); gk = float(v.get('glint-k', 1.))
        fragment = fragment.replace(old, f'color=color*lighting*(1.+0.2493839*optical_loosen*light_gain)+vec3(0.0120000*({wa:.6f})+glint*({gk:.6f}))*optical_loosen*light_gain;', 1)
    if 'late-k' in v or 'late-table' in v:
        # 老颗粒的显示位移倍数：颗粒相对出生位置的总显示位移（积分位置 + 学习输运位移 + 细分偏移）乘 1+(k−1)·smoothstep(a0,a1,age)，
        # 只改显示、不改积分与流场取样。late-k=k 为全段同值；late-table=<版本> 按 analysis/late-table-v<版本>.json 逐段取值
        # （一次动画的随机量，回放参考时按原片记录，同 Site.rate 的处理；表里没有的段取 late-k，缺省 1）。late-a0 / late-a1 缺省 0.12 / 0.40。
        old = 'vec2 vertex=move+'
        assert vertex.count(old) == 1, 'late-k 找不到显示位置'
        vertex = vertex.replace(old, 'move=m.src.xy+(move-m.src.xy)*(1.+(late_k-1.)*smoothstep(late_a0,late_a1,max(age,0.)));\n    ' + old, 1)
        vertex = vertex.replace('void main(){', 'uniform float late_k,late_a0,late_a1;\nvoid main(){', 1)
    if 'age-shrink' in v:
        # 老颗粒随年龄缩小的比例（接手外观链原值 0.39，即年龄 0.12～0.36 之间面积缩到约 37%）。
        old = 'scale*=1.-0.3900000*smoothstep(.12,.36,age);'
        assert vertex.count(old) == 1, 'age-shrink 找不到缩小项'
        vertex = vertex.replace(old, f'scale*=1.-({v["age-shrink"]:.6f})*smoothstep(.12,.36,age);', 1)
    if 'burst' in v:
        # 释放冲量：刚释放的材料获得沿前沿推进方向（释放时刻梯度 n，背离起点）的初速度，保留逆风分量（与剥离不同），
        # 按 exp(−年龄/burst-tau) 衰减；随后由风与共同流场接管，形成「先背离、后随风」的 C 形轨迹。
        # burst=k 为初速度（卡片短边 / 单位进度），burst-tau 为衰减年龄（缺省 0.05），逐颗幅度在 0.6～1.4 倍之间。
        # burst-g=1 时按布局差异 g 加权（钢铁侠不受影响），缺省所有段相同；burst-early=a 时再乘 exp(−出生时刻/a)（早释放的冲量更强）。
        old = 'vec2 edge_dist=min(m.src.xy,card-m.src.xy);'
        assert compute.count(old) == 1, 'burst: ' + old
        compute = compute.replace(old, 'target+=m.physical.xy*span*burst_k*exp(-max(age,0.)/burst_tau)*smoothstep(0.,.008,age)'
                                  '*(.6+.8*transport_unit(floatBitsToUint(m.random.z)^0x3c6ef372u))*exp(-m.src.z/burst_early);\n    ' + old, 1)
        compute = compute.replace('void main(){', 'uniform float burst_k,burst_tau,burst_early;\nvoid main(){', 1)
    if v.get('late-speed'):
        # 后段速度按段缩放：目标速度乘 mix(1, s, smoothstep(late-a, late-b, 年龄))；前段不变。s 是由原片推断的条件输入
        # （analysis/late-speed.json，各段后段可见速度与候选 M 之比，与触点远近同类），缺省为 1。
        old = 'float response=1.-exp(-h/(.018+.036*m.random.z));'
        assert compute.count(old) == 1, 'late-speed: ' + old
        compute = compute.replace(old, 'target*=mix(1.,late_s,smoothstep(late_a,late_b,max(age,0.)));' + old, 1)
        compute = compute.replace('void main(){', 'uniform float late_s,late_a,late_b;\nvoid main(){', 1)
    if 'wall' in v:
        # 未释放表面不可穿越：颗粒新位置处的释放时钟晚于当前时刻 + 余量时，去掉速度里指向更晚释放一侧（∇T 方向）的分量，
        # 只保留沿边界的切向分量，位置按修正后的速度重算。材料因此不越过前沿，沿前沿堆积并沿边滑动。
        old = 'state[i].pos.xy=m.src.xy+(p+v*h-m.src.xy)*distance_gain;'
        assert compute.count(old) == 1, 'wall: ' + old
        new = ('{vec2 wall_next=m.src.xy+(p+v*h-m.src.xy)*distance_gain;vec2 wall_uv=wall_next/card;'
               '\n     if(all(greaterThan(wall_uv,vec2(0.)))&&all(lessThan(wall_uv,vec2(1.)))&&texture(wall_field,wall_uv).r>time+wall_margin){'
               '\n      vec2 wall_e=1./vec2(textureSize(wall_field,0));'
               '\n      vec2 wall_g=vec2(texture(wall_field,wall_uv+vec2(wall_e.x,0.)).r-texture(wall_field,wall_uv-vec2(wall_e.x,0.)).r,texture(wall_field,wall_uv+vec2(0.,wall_e.y)).r-texture(wall_field,wall_uv-vec2(0.,wall_e.y)).r);'
               '\n      vec2 wall_n=normalize(wall_g/(wall_e*card)+vec2(1e-9));float wall_vn=dot(v,wall_n);if(wall_vn>0.)v-=wall_n*wall_vn;}}'
               '\n    ' + old)
        compute = compute.replace(old, new, 1)
        compute = compute.replace('void main(){', 'uniform sampler2D wall_field;\nuniform float wall_margin;\nvoid main(){', 1)
    if 'jam' in v:
        # 拥堵（顾问方案 c）：每一步先把颗粒显示位置溅射到计数网格（analysis/jam_splat.glsl），主计算里查颗粒前方 jam-look 处的
        # 颗粒密度，超过出生时密度的 jam-lo～jam-hi 倍时按 jam 倍率削减目标速度（只缩不反向，不产生逆行）。与 wall 同用时未释放表面按堵死处理。
        func = ('float jam_density(vec2 q){vec2 f=(q-jam_glo)/jam_gcell-.5;ivec2 c0=ivec2(floor(f));vec2 t=f-vec2(c0);float s=0.;'
                '\n for(int dy=0;dy<2;dy++)for(int dx=0;dx<2;dx++){ivec2 c=c0+ivec2(dx,dy);if(any(lessThan(c,ivec2(0)))||any(greaterThanEqual(c,jam_gshape)))continue;'
                '\n  float w=(dx==1?t.x:1.-t.x)*(dy==1?t.y:1.-t.y);s+=w*float(jam_grid[c.y*jam_gshape.x+c.x]);}return s;}\n')
        head = ('layout(std430,binding=11) readonly buffer JamGrid{uint jam_grid[];};\nuniform ivec2 jam_gshape;uniform vec2 jam_glo;'
                'uniform float jam_gcell,jam_rho0,jam_k,jam_lo,jam_hi,jam_look,jam_wall;\n')
        compute = compute.replace('void main(){', head + func + 'void main(){', 1)
        old = 'float response=1.-exp(-h/(.018+.036*m.random.z));'
        assert compute.count(old) == 1, 'jam: ' + old
        new = ('{vec2 jam_d=normalize(target+vec2(1e-6));vec2 jam_cur=state[i].pos.xy+vec2(state[i].pos.w,state[i].vel.w);vec2 jam_ahead=jam_cur+jam_d*jam_look;'
               'float jam=smoothstep(jam_lo*jam_rho0,jam_hi*jam_rho0,jam_density(jam_ahead));'
               + ('vec2 jam_uv=jam_ahead/card;if(jam_wall>0.&&all(greaterThan(jam_uv,vec2(0.)))&&all(lessThan(jam_uv,vec2(1.)))&&texture(wall_field,jam_uv).r>time+wall_margin)jam=1.;' if 'wall' in v else '')
               + 'target*=1.-jam_k*jam;}' + old)
        compute = compute.replace(old, new, 1)
    if 'dwell' in v:
        # 停留 → 追赶（顾问方案 b）：运动年龄 age_m = age − δ，δ 内目标速度为 0、出生速度为 0；之后目标速度乘 k0→1 的斜坡；
        # 剥离与抵消汇聚项乘 dwell-peel（默认 0）；随机分离、逐颗速度离散与卷动乘 smoothstep(0.22,0.45,age_m)（早期材料相干）。
        reps = [('float h=min(dt,age);', 'float h=min(dt,age);' + '\n    float age_m=age-dwell_delta;float dwell_k=age_m<0.?0.:dwell_k0+(1.-dwell_k0)*smoothstep(0.,dwell_tau,age_m);float disp_ramp=smoothstep(.22,.45,age_m);'),
                ('if(age<=dt)old_v=target*.85;', 'if(age<=dt)old_v=vec2(0.);'),
                ('float response=1.-exp(-h/(.018+.036*m.random.z));', 'target=(target-dwell_peel_v)*dwell_k+dwell_peel_v;float response=1.-exp(-h/(.018+.036*m.random.z));'),
                ('target+=(peel*', 'vec2 dwell_before=target;target+=dwell_peel*(peel*'),
                ('+sample_peel(p))*span;', '+sample_peel(p))*span;vec2 dwell_peel_v=target-dwell_before;'),
                ('target+=vec2(cos(random_angle),sin(random_angle))*radius*span*.045*separate', 'target+=vec2(cos(random_angle),sin(random_angle))*radius*span*.045*separate*disp_ramp'),
                ('smoothstep(.025,.14,age)*independent;', 'smoothstep(.025,.14,age)*disp_ramp*independent;'),
                ('smoothstep(.04,.16,age)*independent;', 'smoothstep(.04,.16,age)*disp_ramp*independent;'),
                ('target+=flow*mix(.018,.44+.06*reach,missing)*smoothstep(.01,.09,age);', 'target+=flow*mix(.018,.44+.06*reach,missing)*smoothstep(.01,.09,age)*disp_ramp;')]
        for old, new in reps:
            assert compute.count(old) == 1, 'dwell: ' + old
            compute = compute.replace(old, new, 1)
        compute = compute.replace('void main(){', 'uniform float dwell_delta,dwell_k0,dwell_tau,dwell_peel;\nvoid main(){', 1)
    if 'pure-color' in v:
        # 圆点颗粒跳过基础着色器那一串为半透明碎片设计的提亮（pow 0.86、光照增益、常数抬亮、随机闪光、去色、白底钳制、条件光照），
        # 颜色 = 源色 × pure-color（线性空间，含圆点自身的亮度离散与年龄处理）。深色颗粒不再被抬成灰色，白色材料不再闪光。
        a1 = 'color=mix(color,vec3(1.),clamp(spark_glow*spark_fresh*spark_on,0.,1.));'
        a2 = 'color+=vec3(conditional_lift)*smoothstep(.01,.08,age);'
        assert fragment.count(a1) == 1 and fragment.count(a2) == 1, 'pure-color 需要圆点外观'
        fragment = fragment.replace(a1, a1 + 'vec3 spark_pure_c=color;', 1)
        fragment = fragment.replace(a2, a2 + '{vec3 pc=spark_pure_c*spark_pure_gain;float pl=dot(pc,vec3(.2126,.7152,.0722));'
                                    '\n     pc+=vec3(max(spark_pure_floor-pl,0.));color=mix(color,pc,spark_on*mix(spark_pure_mix,spark_pure_old,smoothstep(spark_pure_a0,max(spark_pure_a1,spark_pure_a0+.001),age)));}', 1)
        fragment = fragment.replace('void main(){', 'uniform float spark_pure_gain,spark_pure_floor,spark_pure_mix,spark_pure_old,spark_pure_a0,spark_pure_a1;\nvoid main(){', 1)
    if 'end-fade' in v:
        # 整体退场（所有段同值）：运动颗粒的不透明度按全局进度在 end-fade-start～end-fade 之间平滑降到 0，与颗粒年龄无关。
        # 依据：按释放先后分批，原片早释放的颗粒尾巴长、晚释放的尾巴被截断；把随年龄缩小减弱后早、中两批对上原片，
        # 晚释放那批的尾巴变得过长——晚释放颗粒的消失由动画整体结束决定。
        a = float(v.get('end-fade-start', .78)); b = float(v['end-fade'])
        old = 'alpha*=1.-spark_fade*spark_on*smoothstep(spark_fade_start,max(spark_fade_end,spark_fade_start+.001),age);'
        assert fragment.count(old) == 1, 'end-fade 需要圆点外观'
        # 全局进度直接用顶点着色器已有的统一变量 time（同名同类型的统一变量在两个阶段共享，由渲染流程逐帧设置）。
        fragment = fragment.replace(old, old + f'alpha*=1.-spark_on*smoothstep({a:.6f},{max(b, a + .001):.6f},time);', 1)
        if 'uniform float time' not in fragment: fragment = fragment.replace('void main(){', 'uniform float time;\nvoid main(){', 1)
    if 'fadein-count' in v:
        # 出生后按数量陆续显现：每颗有固定随机门限 u，年龄超过 u·a 后在 0.008 内变为不透明（出现即不透明），
        # 替代按透明度的 spark-fadein（后者让新生圆点半透明，浅底上深色颗粒偏浅）。
        old = 'alpha*=1.-spark_fade*spark_on*smoothstep(spark_fade_start,max(spark_fade_end,spark_fade_start+.001),age);'
        assert fragment.count(old) == 1, 'fadein-count 需要圆点外观'
        fragment = fragment.replace(old, old + '{float fu=fract(random_out.y*5.377+random_out.w*9.131);alpha*=mix(1.,smoothstep(fu*fadein_a,fu*fadein_a+.008,age),spark_on);}', 1)
        fragment = fragment.replace('void main(){', 'uniform float fadein_a;\nvoid main(){', 1)
    if 'dfade' in v:
        # 按位移淡去：运动颗粒的不透明度乘 1−k·smoothstep(lo, hi, 显示位置离出生处的距离 / 短边)。深色背景上老颗粒变暗，
        # 浅色背景上朝背景色变浅；原处附近停留的颗粒不受影响（与按年龄淡出的区别）。
        old = 'vec2 vertex=move+'
        assert vertex.count(old) == 1, 'dfade: ' + old
        vertex = vertex.replace(old, 'disp_out=length(move-m.src.xy)/span;' + old, 1)
        vertex = vertex.replace('void main(){', 'flat out float disp_out;\nvoid main(){', 1)
        old = 'alpha*=1.-spark_fade*spark_on*smoothstep(spark_fade_start,max(spark_fade_end,spark_fade_start+.001),age);'
        assert fragment.count(old) == 1, 'dfade 需要圆点外观'
        fragment = fragment.replace(old, old + 'alpha*=1.-dfade_k*spark_on*smoothstep(dfade_lo,dfade_hi,disp_out);', 1)
        fragment = fragment.replace('void main(){', 'flat in float disp_out;\nuniform float dfade_k,dfade_lo,dfade_hi;\nvoid main(){', 1)
    if v.get('screen-clip'):
        # 颗粒只画在手机屏幕里：assets/<场景>/screen-mask.png（analysis/screen_masks.py 从背景图提取的设备几何）之外的运动颗粒片元丢弃。
        # 屏幕录像（全画面即屏幕）没有掩码文件，不裁剪。
        old = 'gl_Position=vec4(world.x/frame.x*2.-1.,1.-world.y/frame.y*2.,0,1);'
        assert vertex.count(old) == 1, 'screen-clip: ' + old
        vertex = vertex.replace(old, old + 'screen_uv=world/frame;', 1)
        vertex = vertex.replace('void main(){', 'out vec2 screen_uv;\nvoid main(){', 1)
        old = 'if(alpha<.001)discard;'
        assert fragment.count(old) >= 1
        fragment = fragment.replace(old, 'if(material_pass==1&&texture(screen_mask,screen_uv).r<.5)discard;' + old, 1)
        fragment = fragment.replace('void main(){', 'in vec2 screen_uv;\nuniform sampler2D screen_mask;\nvoid main(){', 1)
    if 'accum-min-age' in v:
        # 重叠累加只统计年龄超过阈值的颗粒：圆点出生时本就互相重叠（面积大于网格），这部分不是堆积，不应加光。
        old = 'frag=vec4(color*alpha,alpha);'
        assert fragment.count(old) == 1
        fragment = fragment.replace(old, 'if(material_pass==1&&age<accum_min_age)discard;' + old, 1)
        fragment = fragment.replace('void main(){', 'uniform float accum_min_age;\nvoid main(){', 1)
    if 'add-beta' in v:
        # 部分加性合成：运动颗粒输出的不透明度乘 1−β(age)，重叠处亮度可超过单颗颜色；孤立颗粒在深色背景上不变。
        old = 'frag=vec4(color*alpha,alpha);'
        assert fragment.count(old) == 1
        fragment = fragment.replace(old, 'frag=vec4(color*alpha,alpha*(1.-(' + f"{v['add-beta']:.6f}" + ')*smoothstep(.01,.05,age)*(1.-smoothstep(.30,.55,age))));', 1)
    if v.get('white-late'):
        # 白底钳制只在年龄 0.3 之后生效（原注释：避免过曝把密集颗粒连成平坦亮边；原片恰有这类亮边）。
        old = 'color=mix(color,min(color,vec3(mix(1.,facet,optical_loosen))),body);'
        assert fragment.count(old) == 1
        fragment = fragment.replace(old, 'color=mix(color,min(color,vec3(mix(1.,facet,optical_loosen))),body*smoothstep(.28,.34,age));', 1)
    if 'roll-front' in v:
        # 前沿携带（原型）：颗粒显示位置另加 fv·k·f·A·(1−exp(−age/A))，fv 为出生处释放前沿的推进速度（方向×速率，来自释放时钟场梯度），
        # f=max(前沿方向·风向,0)：前沿朝下风推进时被吹起的材料跟着前沿堆积，朝上风推进时不携带。只增不减，不产生逆风位移。
        old = 'vec2 move=s.pos.xy+s.vel.xy*extrapolate;'
        assert vertex.count(old) == 1
        vertex = vertex.replace('void main(){', 'uniform sampler2D roll_front_tex;\nuniform float roll_k,roll_age;\nvoid main(){', 1)
        anchor = 'float projected=scale*' if 'float projected=scale*' in vertex else 'vec2 vertex=move+'
        vertex = vertex.replace(anchor, '{vec2 roll_fv=texture(roll_front_tex,m.src.xy/card).xy;float roll_f=max(dot(normalize(roll_fv+vec2(1e-6)),wind),0.);'
                                '\n     move+=roll_fv*roll_k*roll_f*roll_age*(1.-exp(-max(age,0.)/max(roll_age,1e-4)));}\n    ' + anchor, 1)
    if v.get('transport-mono'):
        # 输运位移只许顺风变化：原来每帧按颗粒当前位置与全局时间重新取样（欧拉位移场），场随时间的变化会把颗粒往逆风方向带，
        # 颗粒自身速度放慢时显示为后退。改为在计算着色器里逐步累积（存于 pos.w、vel.w）：出生窗口 mono-age 内照常加上，
        # 之后只累加不朝逆风方向的变化。目标位移与原取样完全相同，只约束它随时间变化的方向。
        old = 'move+=transport.xy*span*smoothstep(0.,.03,age);'
        assert old in vertex, 'transport-mono 需要共享输运层'
        vertex = vertex.replace(old, 'move+=vec2(s.pos.w,s.vel.w)+1e-12*transport.xy;', 1)
        old = 'state[i].vel.z=vz;'
        assert compute.count(old) == 1
        compute = compute.replace(old, old + '\n    {vec2 mono_q=(state[i].pos.xy/card+vec2(.38))/1.76;float mono_ph=(clamp(time,0.,1.)*40.+.5)/41.;'
                                  '\n     vec2 mono_target=texture(transport_field_c,vec3(mono_q,mono_ph)).xy*span*smoothstep(0.,.03,age);'
                                  '\n     vec2 mono_applied=vec2(state[i].pos.w,state[i].vel.w);vec2 mono_step=mono_target-mono_applied;'
                                  '\n     if(age>mono_age)mono_step-=wind*min(dot(mono_step,wind),0.);'
                                  '\n     mono_applied+=mono_step;state[i].pos.w=mono_applied.x;state[i].vel.w=mono_applied.y;}', 1)
        compute = compute.replace('void main(){', 'uniform sampler3D transport_field_c;\nuniform float mono_age;\nvoid main(){', 1)
    if 'mono-ramp' in v:
        # transport-mono 的学习输运位移原在出生后 0～0.03 内一次加满（出生即跳一下）：灭霸 0.02～0.04 岁的颗粒已被推开中位 0.031 短边，
        # 而原片 0.03～0.09 岁的材料还挤在出生处附近、连成贴着前沿的亮带。改为在 0～R 内平滑生效（目标位移不变，只放慢出生时的跳变）。
        assert v.get('transport-mono'), 'mono-ramp 需要 transport-mono'
        old = 'smoothstep(0.,.03,age);\n     vec2 mono_applied'
        assert compute.count(old) == 1, 'mono-ramp: ' + old
        compute = compute.replace(old, f'smoothstep(0.,{v["mono-ramp"]:.6f},age);\n     vec2 mono_applied', 1)
    if 'wall2' in v:
        # 未释放表面不可穿越（按显示位置）：`wall` 只拦积分位置，GLHMR 的学习输运位移（transport-mono 累积在 pos.w/vel.w）
        # 不受它约束，颗粒照样越过前沿。这里在一步更新（含输运累积）之后取显示位置；它落进释放时钟晚于当前时刻 + 余量的区域时，
        # 去掉这一步位移里指向更晚释放一侧（∇T 方向）的分量（在积分位置上扣回），并去掉速度里的同向分量。
        # 材料因此停在前沿、沿边界滑动，顺风撞上晚释放区时在边界上堆积。wall2=余量（缺省 0.01）。所有段同值。
        old = 'float h=min(dt,age);'
        assert compute.count(old) == 1, 'wall2: ' + old
        compute = compute.replace(old, old + '\n    vec2 wall2_prev=state[i].pos.xy+vec2(state[i].pos.w,state[i].vel.w);', 1)
        tail = 'state[i].pos.w=mono_applied.x;state[i].vel.w=mono_applied.y;}' if v.get('transport-mono') else 'state[i].vel.z=vz;'
        assert compute.count(tail) == 1, 'wall2: ' + tail
        new = ('\n    {vec2 wall2_now=state[i].pos.xy+vec2(state[i].pos.w,state[i].vel.w);vec2 wall2_uv=wall2_now/card;'
               '\n     if(all(greaterThan(wall2_uv,vec2(0.)))&&all(lessThan(wall2_uv,vec2(1.)))&&texture(wall_field,wall2_uv).r>time+wall_margin){'
               '\n      vec2 wall2_e=1./vec2(textureSize(wall_field,0));'
               '\n      vec2 wall2_g=vec2(texture(wall_field,wall2_uv+vec2(wall2_e.x,0.)).r-texture(wall_field,wall2_uv-vec2(wall2_e.x,0.)).r,'
               'texture(wall_field,wall2_uv+vec2(0.,wall2_e.y)).r-texture(wall_field,wall2_uv-vec2(0.,wall2_e.y)).r);'
               '\n      vec2 wall2_n=normalize(wall2_g/(wall2_e*card)+vec2(1e-9));'
               '\n      float wall2_sn=dot(wall2_now-wall2_prev,wall2_n);if(wall2_sn>0.)state[i].pos.xy-=wall2_n*wall2_sn;'
               '\n      float wall2_vn=dot(state[i].vel.xy,wall2_n);if(wall2_vn>0.)state[i].vel.xy-=wall2_n*wall2_vn;}}')
        compute = compute.replace(tail, tail + new, 1)
        if 'wall' not in v:
            compute = compute.replace('void main(){', 'uniform sampler2D wall_field;\nuniform float wall_margin;\nvoid main(){', 1)
    if 'transport-ramp' in v:
        # 共享输运场的学习位移原在颗粒出生后 0～0.03 内一次加满（相当于出生即跳一下）；改为在 0～R 内逐渐生效，
        # 位移本身不变，只让刚出生的颗粒先停在原处附近（原片「先慢后快」）。密度通道的时间门不变。
        old = 'move+=transport.xy*span*smoothstep(0.,.03,age);'
        assert old in vertex
        vertex = vertex.replace(old, f'move+=transport.xy*span*smoothstep(0.,{v["transport-ramp"]:.6f},age);', 1)
    if 'transport-g' in v:
        # 共享输运场的学习位移按布局差异加权缩放：1+g·(k−1)；原版钢铁侠（g=0）不变。
        # 这层位移在默认碎片外观下标定，圆点外观让被横向摊开的颗粒全部可见，非标准布局的云团因此偏宽。
        if v.get('transport-mono'):
            # 输运位移已移到计算着色器里累积（transport-mono），增益乘在累积目标上。
            old = 'vec2 mono_target=texture(transport_field_c,vec3(mono_q,mono_ph)).xy*span*smoothstep(0.,.03,age);'
            assert compute.count(old) == 1
            compute = compute.replace(old, old.replace('.xy*span*', '.xy*transport_xy_gain*span*'), 1)
            compute = compute.replace('void main(){', 'uniform float transport_xy_gain;\nvoid main(){', 1)
        else:
            lines = vertex.split('\n'); hit = 0
            for i, line in enumerate(lines):
                if line.strip().startswith('move+=transport.xy*span*smoothstep(0.,'):
                    lines[i] = line.replace('move+=transport.xy*span*', 'move+=transport.xy*transport_xy_gain*span*'); hit += 1
            assert hit == 1
            vertex = '\n'.join(lines).replace('void main(){', 'uniform float transport_xy_gain;\nvoid main(){', 1)
    transport_scale = (0. if v.get('no-transport-xy') else 1., 0. if v.get('no-transport-density') else 1.)
    if v.get('srgb-particles'):
        # 颗粒道在 sRGB 编码空间合成（界面合成器的常见做法）：颗粒颜色仍在线性光里计算，输出前编码，按编码值与帧缓冲混合；
        # 背景与表面照常在线性光里合成，表面道之后整幅换算成编码值（见下方 render_srgbp）。软边的暗点在浅底上显大、亮点在暗底上显小。
        old = 'frag=vec4(color*alpha,alpha);'
        assert fragment.count(old) == 1, 'srgb-particles 找不到颗粒输出'
        fragment = fragment.replace(old, 'if(srgbp_on>.5)color=srgbp_encode(max(color,vec3(0.)));\n    ' + old, 1)
        _sg = float(v['srgbp-gamma']) if 'srgbp-gamma' in v else None
        _enc = (f'vec3 srgbp_encode(vec3 c){{return pow(max(c,vec3(0.)),vec3({1. / _sg:.6f}));}}' if _sg else
                'vec3 srgbp_encode(vec3 c){return mix(c*12.92,1.055*pow(c,vec3(1./2.4))-.055,step(vec3(.0031308),c));}')
        fragment = fragment.replace('void main(){', 'uniform float srgbp_on;\n' + _enc + '\nvoid main(){', 1)
    if 'dark-fade' in v:
        # 比身后背景暗的颗粒随年龄变淡：d = clamp((背景亮度 − 颗粒亮度)/背景亮度, 0, 1)（sRGB 编码值，背景取静态背景图），
        # 不透明度乘 1 − k·d·smoothstep(a0, a1, 年龄)。只作用于浅底上的深色颗粒，亮颗粒与彩色颗粒不变；与之前否定的按年龄统一淡出不同。
        # 依据：后段可见面积比与「卡片比背景亮多少」秩相关 −0.65，浅底段模型独有的可见区 63%～91% 是比背景暗的颗粒；
        # 原片深色材料随年龄变亮（4.16 节）。dark-fade-a0 / a1 缺省 0.10 / 0.35。需要 srgb-particles。所有段同值。
        assert v.get('srgb-particles'), 'dark-fade 需要 srgb-particles'
        old = 'if(srgbp_on>.5)color=srgbp_encode(max(color,vec3(0.)));'
        assert fragment.count(old) == 1, 'dark-fade: ' + old
        new = (old + '\n    {vec2 df_uv=vec2(gl_FragCoord.x/df_size.x,1.-gl_FragCoord.y/df_size.y);vec3 df_b=texture(df_bg,df_uv).rgb;'
               'float df_lb=dot(df_b,vec3(.2126,.7152,.0722));float df_lp=dot(color,vec3(.2126,.7152,.0722));'
               'float df_d=clamp((df_lb-df_lp)/max(df_lb,.02),0.,1.);alpha*=1.-df_k*df_d*smoothstep(df_a0,df_a1,age);}')
        fragment = fragment.replace(old, new, 1)
        fragment = fragment.replace('void main(){', 'uniform sampler2D df_bg;\nuniform vec2 df_size;\nuniform float df_k,df_a0,df_a1;\nvoid main(){', 1)
    if 'soft-dot' in v:
        # soft-dot=e1（可带 soft-dot-e0）：最后一次细粒致密化之后，不透明度再乘随年龄生效的软边圆形遮罩 mix(1, 1 − smoothstep(e0, e1, radial), shape_out)。
        # 依据：原形状遮罩只生效 92%（mix(1, cut, shape_out·.92)），四边形四角残留约 8% 的不透明度，两次致密化 1 − (1 − α)^e 把它放大到两三成，
        # 颗粒呈带棱角的方块碎片（8 倍放大：原片是软边、半透明、大量重叠的圆点）。只改形状，半透明边缘的半径取在内切圆附近，面积约为四边形的 85%。所有段同值。
        e1_ = float(v['soft-dot']); e0_ = float(v.get('soft-dot-e0', max(e1_ - .25, 0.)))
        old = 'alpha=1.-pow(max(1.-alpha,.00001),exp((fission_density+scatter_material.x)*smoothstep(.02,.10,age)));'
        assert fragment.count(old) == 1, 'soft-dot: ' + old
        fragment = fragment.replace(old, old + f'\n    alpha*=mix(1.,1.-smoothstep({e0_:.6f},{e1_:.6f},radial),shape_out);', 1)
    if 'speed-glow' in v:
        # speed-glow=k：颗粒按当前速度向白色混合（见 scratchpad patch_speedglow.py 的说明）。需要 srgb-particles。
        assert v.get('srgb-particles'), 'speed-glow 需要 srgb-particles'
        a_ = 'out float age_out,life_out,light_out,shape_out;'
        assert vertex.count(a_) == 1, 'speed-glow: ' + a_
        vertex = vertex.replace(a_, a_ + '\nout float sg_speed;', 1)
        b_ = 'age_out=time-m.src.z;'
        assert vertex.count(b_) == 1, 'speed-glow: ' + b_
        vertex = vertex.replace(b_, b_ + 'sg_speed=length(s.vel.xy)/span;', 1)
        c_ = 'in float age_out,life_out,light_out,shape_out;'
        assert fragment.count(c_) == 1, 'speed-glow: ' + c_
        fragment = fragment.replace(c_, c_ + '\nin float sg_speed;', 1)
        old_ = 'if(srgbp_on>.5)color=srgbp_encode(max(color,vec3(0.)));'
        assert fragment.count(old_) == 1, 'speed-glow: ' + old_
        v0_ = float(v.get('speed-glow-v0', .3)); v1_ = max(float(v.get('speed-glow-v1', .9)), v0_ + .001)
        fragment = fragment.replace(old_, f'color=mix(color,vec3(1.),({float(v["speed-glow"]):.6f})*smoothstep({v0_:.6f},{v1_:.6f},sg_speed));\n    ' + old_, 1)
    if 'age-gain' in v:
        # age-gain=g：颗粒线性颜色乘 1 + g·smoothstep(a0, a1, 年龄)，在编码成 sRGB 之前（输出时截断）。深色颗粒乘增益后仍是深色，
        # 暗底上不会冒出灰点；与 age-fade（不透明度随年龄下降）同用时，浅底暗颗粒随年龄变浅、暗底亮颗粒保持或变亮、浅底亮颗粒先触顶变白而不显眼，
        # 对应原片三类观察（scratchpad speed_light.py、coverage_dist.py）。age-gain-a0 / a1 缺省 0.05 / 0.35。需要 srgb-particles。所有段同值。
        assert v.get('srgb-particles'), 'age-gain 需要 srgb-particles'
        old = 'if(srgbp_on>.5)color=srgbp_encode(max(color,vec3(0.)));'
        assert fragment.count(old) == 1, 'age-gain: ' + old
        g0_ = float(v.get('age-gain-a0', .05)); g1_ = max(float(v.get('age-gain-a1', .35)), g0_ + .001)
        fragment = fragment.replace(old, f'color*=1.+({float(v["age-gain"]):.6f})*smoothstep({g0_:.6f},{g1_:.6f},age);\n    ' + old, 1)
    if 'age-lift' in v:
        # age-lift=δ：颗粒颜色编码成 sRGB 之后加 δ·smoothstep(a0, a1, 年龄)（上限 1），暗颗粒与亮颗粒加的编码值相同。
        # 依据：原片跟踪颗粒相对背景的明暗随年龄上升，浅底暗颗粒（白衣人物）年龄三分位 −108 → −91 → −83、暗底亮颗粒（地球）+114 → +142 → +147，
        # 两类的量级相近；模型几乎不变或反向（scratchpad speed_light.py）。线性空间向白混合（age-glow）对暗颗粒的作用比亮颗粒大得多，浅底过头。
        # age-lift-a0 / a1 缺省 0.03 / 0.3。需要 srgb-particles。所有段同值。
        assert v.get('srgb-particles'), 'age-lift 需要 srgb-particles'
        old = 'if(srgbp_on>.5)color=srgbp_encode(max(color,vec3(0.)));'
        assert fragment.count(old) == 1, 'age-lift: ' + old
        l0_ = float(v.get('age-lift-a0', .03)); l1_ = max(float(v.get('age-lift-a1', .3)), l0_ + .001)
        fragment = fragment.replace(old, old + f'\n    color=min(color+vec3(({float(v["age-lift"]):.6f})*smoothstep({l0_:.6f},{l1_:.6f},age)),vec3(1.));', 1)
    if 'add-mix' in v:
        # 颗粒合成带一部分加光性质：颗粒仍按预乘颜色贡献光，遮挡背景的程度乘 1−m（m=0 为正常覆盖，m=1 为纯相加）。
        # 暗颗粒在浅底上随之变淡，亮颗粒在暗底上不变，彩色颗粒在中等亮度的底上相对更亮。依据：各段「模型 / 原片」后段可见面积比
        # 与「卡片比背景亮多少」秩相关 −0.65——卡片比背景暗的段（浅底深色材料）模型偏多，比背景亮或相近的段偏少。
        # 只改运动颗粒，未释放表面照常不透明；重叠累加提亮（accum）的两遍里临时置 0，保持它原来的口径。所有段同值。
        old = 'frag=vec4(color*alpha,alpha);'
        assert fragment.count(old) == 1, 'add-mix: ' + old
        fragment = fragment.replace(old, 'frag=vec4(color*alpha,alpha*(1.-add_mix));', 1)
        fragment = fragment.replace('void main(){', 'uniform float add_mix;\nvoid main(){', 1)
    base = cls
    attrs = dict(compute_source=compute, pressure_splat_source=splat, vertex_source=vertex, fragment_source=fragment)

    def _warp_ctx(fn):
        # warp：引导模板按释放场配准（coupled_transport.fit_coordinates）。由起点生成的释放时刻图与标准布局的释放图做平滑配准，
        # 模板在配准后的坐标里取样，结构跟着起点走，而不是钉在卡片中心；标准布局下配准接近恒等。所有段同一规则。
        if not v.get('warp'):
            return fn()
        import conditional_material as _cm
        from coupled_transport import CoupledRules as _CR
        saved = _cm.CoupledRules
        _cm.CoupledRules = lambda: _CR(warp=True, steps=int(v.get('warp-steps', 140)), max_displacement=float(v.get('warp-max', .45)),
                                       smoothness=float(v.get('warp-smooth', .002)))
        try:
            return fn()
        finally:
            _cm.CoupledRules = saved

    def init(self, *a, **kw):
        if transport_scale != (1., 1.):
            original = shared_transport.correction
            def scaled(*args, **kwargs):
                out = original(*args, **kwargs)
                out = out.copy(); out[..., :2] *= transport_scale[0]; out[..., 2:] *= transport_scale[1]
                return out
            shared_transport.correction = scaled
            try:
                _warp_ctx(lambda: base.__init__(self, *a, **kw))
            finally:
                shared_transport.correction = original
        else:
            _warp_ctx(lambda: base.__init__(self, *a, **kw))
        if v.get('zb') and getattr(self, '_param_release', None) is not None and 'zb_n' in self.program:
            # z 向鼓包的起点（与 param-release 同一组起点，线段起点带半长与朝向）与透视中心。
            prm_z, nm_z = self._param_release; sites_z = prm_z['sites'][nm_z][:6]
            cw_z, ch_z = float(self.cw), float(self.ch); sp_z = min(cw_z, ch_z); Wn_z, Hn_z = cw_z / sp_z, ch_z / sp_z
            S_z = []
            for q_z in sites_z:
                vert_z = (q_z[5] > .5) if len(q_z) > 5 else False
                half_z = (q_z[4] * (Hn_z if vert_z else Wn_z) / 2 * sp_z) if len(q_z) > 4 and q_z[4] > 0 else 0.
                S_z += [q_z[0] * cw_z, q_z[1] * ch_z, half_z, 1. if vert_z else 0.]
            S_z += [0.] * (24 - len(S_z))
            self.program['zb_n'] = len(sites_z)
            self.program['zb_s'].write(np.array(S_z, 'f4').tobytes())
            self.program['zb_h'] = float(v['zb']); self.program['zb_r'] = float(v.get('zb-r', .2))
            self.program['zb_t'] = float(v.get('zb-t', .1)); self.program['zb_d'] = float(v.get('zb-d', 1e3))
            off_z = self.program['offset'].value; card_z = self.program['card'].value; frame_z = self.program['frame'].value
            span_px = float(self.program['span'].value)
            self.program['zb_cam'] = float(v.get('zb-cam', 3.)) * span_px
            self.program['zb_c'] = ((frame_z[0] / 2, frame_z[1] / 2) if v.get('zb-c') == 'screen'
                                    else (off_z[0] + card_z[0] / 2, off_z[1] + card_z[1] / 2))
        if 'accel' in v:
            self.compute['accel_k'] = v['accel']; self.compute['accel_g'] = self.layout_change
            self.reset()
        if 'cwind' in v:
            # 非标准布局的顺风携带系数（原 1.086）：1+g·(w−1)，标准布局不变。
            self.compute['conditional_wind'] = 1 + self.layout_change * (v['cwind'] - 1)
            self.reset()
        if v.get('no-clock'):
            # 取消按布局系数加权的时间轴扭曲（conditional-material-rules.json 的 clock），全段同一时间轴。
            import dataclasses as _dc
            self.conditional_rules = _dc.replace(self.conditional_rules, clock=0.)
        if 'clock' in v:
            # 时间轴扭曲取给定值（与 layout-g 一起使用时所有段相同）：进度 p → p + g·clock·4p(1−p)。
            import dataclasses as _dc
            self.conditional_rules = _dc.replace(self.conditional_rules, clock=float(v['clock']))
        crules = {k[6:]: float(val) for k, val in v.items() if k.startswith('crule-')}
        if crules:
            # crule-<字段>=<值>：把条件规则的某些字段（size/life/light/motion/wind/grain/lift/curl_balance）改成给定值后重新写入，
            # 所有段同值；与 layout-g 一起使用时即「所有段用同一组条件参数」，例如外观类取中性值（钢铁侠那套）、运动类保留共用值。
            import dataclasses as _dc
            self.apply_rules(_dc.replace(self.conditional_rules, **crules))
            if hasattr(self, 'edge_rules'):
                # apply_rules 按原始寿命重写了寿命，边缘规则的寿命部分以新值为基准重新施加。
                self.edge_original_life = self.base[:, 6].copy(); self.apply_edge(self.edge_rules)
        if v.get('under'):
            # 颗粒画在尚未释放的表面之下（两遍绘制对调：先颗粒、后表面）。原片里尚未释放的画面被飞过的颗粒遮挡得明显更少
            # （scratchpad overfly.py：离已释放区一块之外的块，释放前完整度下降原片 0.04～0.05，RT1 0.09～0.11）。
            import moderngl as _mgl

            class _UnderVao:
                def __init__(self, inner, owner): self.inner = inner; self.owner = owner; self.pending = None
                def render(self, *a, **k):
                    o = self.owner; mp = o.program['material_pass']
                    if mp.value == 0:
                        self.pending = (a, k); return
                    self.inner.render(*a, **k)
                    if self.pending is not None:
                        pa, pk = self.pending; self.pending = None
                        o.fg_tex.filter = (_mgl.NEAREST, _mgl.NEAREST); mp.value = 0
                        self.inner.render(*pa, **pk)
                        o.fg_tex.filter = (_mgl.LINEAR, _mgl.LINEAR); mp.value = 1
                def __getattr__(self, name): return getattr(self.inner, name)
            self.vao = _UnderVao(self.vao, self)
        if 'flow-seed' in v:
            # 随机性属于允许的逐次输入；这里只换引导模板的随机形变，材料与释放时刻图不变。
            from unified_model import variation as _variation
            fv = _variation(int(v['flow-seed']))
            self.compute['variation_affine'] = tuple(float(q) for q in fv[:4]); self.compute['variation_bend'] = tuple(float(q) for q in fv[4:8])
            self.compute['variation_wave'] = tuple(float(q) for q in fv[8:12]); self.compute['variation_clock'] = tuple(float(q) for q in fv[12:14])
            self.reset()
        if 'fan' in v:
            self.compute['fan_gap'] = float(v['fan']); self.compute['fan_k'] = float(v.get('fan-k', 1.))
            self.reset()
        if 'zoom' in v:
            self.compute['zoom_k'] = float(v['zoom']); self.compute['zoom_g'] = float(self.layout_change)
            self.compute['zoom_tau'] = float(v.get('zoom-tau', .3))
            self.reset()
        if 'shrink-g' in v:
            # 圆点随年龄缩小的比例按布局差异 g 加权：非标准布局取 shrink-g，钢铁侠（g=0）保持 spark-shrink 的值。
            base_shrink = float(self.program['spark_shrink'].value)
            self.program['spark_shrink'] = base_shrink + float(self.layout_change) * (float(v['shrink-g']) - base_shrink)
        if 'life-cohort' in v:
            # 同批次寿命一致（顾问 2026-09-30）：按出生时刻 w 分箱（life-cohort=w，缺省 0.02），每颗寿命换成箱内平均，仍不越过动画终点；
            # 同时出生的颗粒一起消失，颗粒群到消失前保持致密，而不是按各自寿命陆续变稀。所有布局相同；life-cohort-g=1 时按 g 加权。
            w = float(v['life-cohort']) if v['life-cohort'] is not True and float(v['life-cohort']) < .5 else .02
            born = self.base[:, 2]; life = self.base[:, 6].copy(); bins = np.floor(born / w).astype(np.int64); new = life.copy()
            # life-cohort-mix=m：保留个体差异的比例（寿命 = 批次平均 + m·(个体 − 批次平均)），0 为完全一致。
            keep = float(v.get('life-cohort-mix', 0.))
            for k in np.unique(bins):
                m = bins == k; mean = life[m].mean(); new[m] = mean + keep * (life[m] - mean)
            new = np.minimum(new, .995 - born)
            g = float(self.layout_change) if v.get('life-cohort-g') else 1.
            self.base[:, 6] = (life + g * (new - life)).astype(self.base.dtype)
            self.material.write(self.base.tobytes()); self.reset()
        if 'life-cap' in v:
            # 寿命上限（内部时间，按布局差异 g 加权，钢铁侠不变）：截掉寿命分布的长尾。加长早释放寿命（edge-early、edge-life）
            # 让中段颗粒群铺得更开，但 90% 分位寿命随之拉到 0.66 以上，最早一批颗粒活到后段、停在远处的顺风侧；
            # 上限只去掉这条尾巴，中位寿命基本不变。
            g = float(self.layout_change); cap = float(v['life-cap']); life = self.base[:, 6].copy()
            self.base[:, 6] = (life + g * (np.minimum(life, cap) - life)).astype(self.base.dtype)
            self.material.write(self.base.tobytes()); self.reset()
        if 'life-spread' in v:
            # 寿命分布加宽（所有段同值）：逐颗寿命乘 exp(σ·z)，z 为逐颗固定的标准正态随机数（与运动、外观随机量独立），中位寿命不变。
            # 依据：按释放时刻对齐后「看得见的颗粒留在原处的比例」原片在各段都是平滑衰减；圆点候选在慢速段是平台加断崖（寿命集中且被上限截断），
            # 在电影画面等段尾巴太短。life-spread-cap=L 为加宽之后的寿命上限（缺省不限），最后仍不越过动画终点。
            from scipy.special import ndtri as _ndtri
            sig = float(v['life-spread'])
            rng = np.random.default_rng(0x6c1fe5 + len(self.base))
            z = _ndtri(np.clip(rng.random(len(self.base)), 1e-6, 1 - 1e-6))
            life = self.base[:, 6].astype(np.float64) * np.exp(sig * z)
            if 'life-spread-cap' in v: life = np.minimum(life, float(v['life-spread-cap']))
            life = np.clip(life, .02, np.maximum(.995 - self.base[:, 2].astype(np.float64), .02))
            self.base[:, 6] = life.astype(self.base.dtype)
            self.material.write(self.base.tobytes()); self.reset()
        if 'flowfix' in v:
            # 共同流场的可见速度修正（analysis/flow_correction_fit.py 生成，按版本号区分）；所有布局共用同一份修正场。
            from unified_model import guidance
            corr = np.load(HERE / 'analysis/match-cache/flow-correction' / f'flow-correction-v{int(v.get("flowfix-v", 1))}.npy')
            flow = guidance() + np.float32(v['flowfix']) * corr
            self.flow_tex.write(flow.astype(np.float32).tobytes())
            self.reset()
        if v.get('surface-top'):
            # 未释放表面画在颗粒之上：拦截表面一道，等颗粒一道画完后再画表面（只调换先后，不改两道各自的内容）。
            import moderngl
            owner = self

            class SurfaceTop:
                def __init__(self, inner): self.inner = inner; self.pending = None
                def render(self, *args, **kw):
                    if owner.program['material_pass'].value == 0:
                        self.pending = (args, kw); return
                    self.inner.render(*args, **kw)
                    if self.pending is not None:
                        owner.fg_tex.filter = (moderngl.NEAREST, moderngl.NEAREST); owner.program['material_pass'] = 0
                        a_, k_ = self.pending; self.inner.render(*a_, **k_); self.pending = None
                        owner.program['material_pass'] = 1; owner.fg_tex.filter = (moderngl.LINEAR, moderngl.LINEAR)
                def __getattr__(self, name): return getattr(self.inner, name)
            self.vao = SurfaceTop(self.vao)
        if v.get('srgb-blend'):
            import renderer as base_renderer
            identity_linear = 'vec3 linear(vec3 c){return c;}'
            old_linear = 'vec3 linear(vec3 c){return mix(c/12.92,pow((c+.055)/1.055,vec3(2.4)),step(vec3(.04045),c));}'
            old_srgb = 'vec3 srgb(vec3 c){return mix(c*12.92,1.055*pow(max(c,0.),vec3(1./2.4))-.055,step(vec3(.0031308),c));}'
            assert old_linear in base_renderer.BACKGROUND and old_srgb in base_renderer.OUTPUT
            self.bg_vao.release(); self.bg_program.release(); self.out_vao.release(); self.out_program.release()
            self.bg_program = self.ctx.program(vertex_shader=base_renderer.FULLVERT, fragment_shader=base_renderer.BACKGROUND.replace(old_linear, identity_linear))
            self.bg_vao = self.ctx.vertex_array(self.bg_program, [])
            self.out_program = self.ctx.program(vertex_shader=base_renderer.FULLVERT, fragment_shader=base_renderer.OUTPUT.replace(old_srgb, 'vec3 srgb(vec3 c){return c;}'))
            self.out_vao = self.ctx.vertex_array(self.out_program, [])
            self.bg_program['bg'] = 1; self.out_program['screen'] = 2
        if 'tail' in v:
            import moderngl
            self.program['tail_amount'] = v['tail']; self.program['tail_tau'] = v.get('tail-tau', .15)
            self.program['tail_lod'] = v.get('tail-lod', 0.)
            # 残影颜色相对源色的倍率（线性空间）；<1 时残影比源色暗：深色背景上几乎不可见，浅色背景上显为偏向源色的暗色调。
            self.program['tail_dark'] = v.get('tail-dark', 1.)
            if v.get('tail-lod', 0.) > 0:
                self.fg_tex.build_mipmaps(); self.fg_tex.filter = (moderngl.LINEAR_MIPMAP_LINEAR, moderngl.LINEAR)
        if 'mramp-early' in v or 'mramp-late' in v:
            m = self.conditional_rules.motion
            self.compute['ramp_g'] = self.layout_change
            self.compute['ramp_early'] = v.get('mramp-early', m); self.compute['ramp_late'] = v.get('mramp-late', m)
            self.compute['ramp_age'] = v.get('mramp-age', .35)
            if 'mcurl' in v:
                self.compute['curl_extra'] = 1 + self.layout_change * (v['mcurl'] - 1)
        if 'transport-g' in v:
            (self.compute if 'transport_xy_gain' in self.compute else self.program)['transport_xy_gain'] = 1 + self.layout_change * (v['transport-g'] - 1)
            self.reset()
        if 'gramp-early' in v:
            self.compute['gramp_early'] = v['gramp-early']; self.compute['gramp_age'] = v.get('gramp-age', .25)
            self.reset()
        if 'fglow' in v:
            self.program['eg_k'] = v['fglow'] if v['fglow'] is not True else 1.
            self.program['eg_white'] = v.get('fglow-white', 0.); self.program['eg_part'] = v.get('fglow-part', 1.)
            self.program['eg_off'] = v.get('fglow-off', -.005); self.program['eg_w'] = v.get('fglow-w', .012)
        if 'dark-fade' in v:
            self.program['df_bg'] = 1; self.program['df_size'] = tuple(float(q) for q in self.tex.size)
            self.program['df_k'] = float(v['dark-fade']); self.program['df_a0'] = v.get('dark-fade-a0', .10); self.program['df_a1'] = v.get('dark-fade-a1', .35)
        if 'add-mix' in v:
            self.program['add_mix'] = float(v['add-mix'])
        if 'kin' in v:
            ka_u = float(v.get('kin-a', 1.2))
            if 'kin-table' in v:
                # 只用于诊断（逐段实测值，不是共用规则）：analysis/kin-table-v<版本>.json 里各段原片逐颗跟踪拟合的加速度。
                import json as _json
                ktab = _json.loads((HERE / 'analysis' / f'kin-table-v{int(v["kin-table"])}.json').read_text('utf-8'))
                if isinstance(ktab.get(self.directory.name), (int, float)): ka_u = float(ktab[self.directory.name])
            ka_u *= float(v.get('kin-a-mul', 1.))      # kin-a-mul=k：只用于敏感性诊断，逐段加速度整体乘 k
            v0u = (0., 0.)
            if 'kin-v0-table' in v:
                # 逐段初速向量（原片逐颗跟踪拟合的 V0，按「每次移除的随机实现」作为逐段输入推断）：analysis/kin-v0-table-v<版本>.json，
                # 值为 [大小（短边/进度）, 方向（度，0°=右、90°=上）]；着色器坐标 y 向下。通常与 kin-v0=0 同用。
                import json as _json, math as _math
                vtab = _json.loads((HERE / 'analysis' / f'kin-v0-table-v{int(v["kin-v0-table"])}.json').read_text('utf-8'))
                q = vtab.get(self.directory.name)
                if isinstance(q, list): v0u = (float(q[0]) * _math.cos(_math.radians(q[1])), -float(q[0]) * _math.sin(_math.radians(q[1])))
            v0u = (v0u[0] * float(v.get('kin-v0-mul', 1.)), v0u[1] * float(v.get('kin-v0-mul', 1.)))      # kin-v0-mul=k：只用于敏感性诊断
            self.compute['kin_a_u'] = ka_u
            if (v.get('site-motion') or v.get('site-push') or v.get('site-v0')) and getattr(self, '_param_release', None) is not None:
                import json as _json, math as _math
                prm_, nm_ = self._param_release; sh_ = prm_['shared']; sites_ = prm_['sites'][nm_][:6]
                tab_ = (_json.loads((HERE / 'analysis' / f'site-motion-v{int(v["site-motion"])}.json').read_text('utf-8')).get(nm_, [])
                        if v.get('site-motion') else [])
                cw_, ch_ = float(self.cw), float(self.ch); sp_ = min(cw_, ch_); Wn_, Hn_ = cw_ / sp_, ch_ / sp_
                A_, B_ = [], []
                for i_, q_ in enumerate(sites_):
                    vert_ = (q_[5] > .5) if len(q_) > 5 else False
                    half_ = (q_[4] * (Hn_ if vert_ else Wn_) / 2 * sp_) if len(q_) > 4 and q_[4] > 0 else 0.
                    dth_, am_ = (tab_[i_] if i_ < len(tab_) else [0., 1.])
                    A_ += [q_[0] * cw_, q_[1] * ch_, q_[2], q_[3]]; B_ += [half_, 1. if vert_ else 0., -_math.radians(dth_), am_]
                A_ += [0.] * (24 - len(A_)); B_ += [0.] * (24 - len(B_))
                th_ = _math.radians(prm_['directions'][nm_])
                self.compute['ps_n'] = len(sites_)
                self.compute['ps_a'].write(np.array(A_, 'f4').tobytes()); self.compute['ps_b'].write(np.array(B_, 'f4').tobytes())
                self.compute['ps_s'] = (min(max(sh_['e'], .4), 3.), max(sh_['tf'], .05), sh_['af'])
                self.compute['ps_w'] = (_math.cos(th_), -_math.sin(th_))
                if v.get('site-v0'):
                    svt_ = _json.loads((HERE / 'analysis' / f'site-v0-v{int(v["site-v0"])}.json').read_text('utf-8')).get(nm_, [])
                    svk_ = float(v.get('site-v0-k', 1.))     # site-v0-k=k：只用于诊断，整体乘 k
                    V_ = []
                    for i_ in range(6): V_ += ([float(svt_[i_][0]) * svk_, float(svt_[i_][1]) * svk_] if i_ < len(svt_) and i_ < len(sites_) else [0., 0.])
                    self.compute['ps_v'].write(np.array(V_, 'f4').tobytes())
            if 'kin_v0_u' in self.compute: self.compute['kin_v0_u'] = v0u
            if (('kin-curl-seed' in v) or ('kin-curl-seed-table' in v)) and 'kc_pa' in self.compute:
                import json as _json
                if 'kin-curl-seed-table' in v:
                    stab = _json.loads((HERE / 'analysis' / f'kin-curl-seed-v{int(v["kin-curl-seed-table"])}.json').read_text('utf-8'))
                    kseed = int(stab.get(self.directory.name, 0))
                else:
                    kseed = int(v['kin-curl-seed'])
                krng = np.random.default_rng(0x6b6375 + 7919 * kseed)
                self.compute['kc_pa'] = tuple(float(q) for q in krng.uniform(0., 2. * np.pi, 3))
                self.compute['kc_pb'] = tuple(float(q) for q in krng.uniform(0., 2. * np.pi, 3))
                self.compute['kc_rot'] = float(krng.uniform(0., np.pi))
            if v.get('kin-disp') and 'kdp_n' in self.compute:
                import json as _json
                dtab_ = _json.loads((HERE / 'analysis' / f'kin-disp-v{int(v["kin-disp"])}.json').read_text('utf-8')).get(self.directory.name)
                if isinstance(dtab_, dict):
                    gd_ = np.asarray(dtab_['m'], 'f4'); self.compute['kdp_n'] = (int(dtab_['nx']), int(dtab_['ny']))
                    self.compute['kdp_box'] = (float(dtab_['x0']), float(dtab_['y0']), float(dtab_['dx']), float(dtab_['dy']))
                else:
                    gd_ = np.ones(1, 'f4'); self.compute['kdp_n'] = (0, 0); self.compute['kdp_box'] = (0., 0., 1., 1.)
                self.kdisp_buf = self.ctx.buffer(gd_.tobytes())
            if v.get('kin-euler') and 'keu_n' in self.compute:
                import json as _json
                etab_ = _json.loads((HERE / 'analysis' / f'kin-euler-v{int(v["kin-euler"])}.json').read_text('utf-8')).get(self.directory.name)
                if isinstance(etab_, dict):
                    ge_ = np.asarray(etab_['w'], 'f4').reshape(-1, 2); self.compute['keu_n'] = (int(etab_['nx']), int(etab_['ny']))
                    self.compute['keu_t'] = (float(etab_.get('t0', 0.)), float(etab_.get('t1', 1.)), float(int(etab_.get('nt', 1))))
                    self.compute['keu_box'] = (float(etab_['x0']), float(etab_['y0']), float(etab_['dx']), float(etab_['dy']))
                else:
                    ge_ = np.zeros((1, 2), 'f4'); self.compute['keu_n'] = (0, 0); self.compute['keu_box'] = (0., 0., 1., 1.); self.compute['keu_t'] = (0., 1., 1.)
                self.keu_buf = self.ctx.buffer(ge_.tobytes())
            if v.get('kin-uvt') and 'kuv_n' in self.compute:
                import json as _json
                utab = _json.loads((HERE / 'analysis' / f'kin-uvt-v{int(v["kin-uvt"])}.json').read_text('utf-8')).get(self.directory.name)
                if isinstance(utab, dict):
                    nxu, nyu = int(utab['nx']), int(utab['ny']); gt_ = np.zeros((nxu * nyu, 4), 'f4')
                    gt_[:, 0:2] = utab['u']; gt_[:, 2:4] = utab['a']
                    self.compute['kuv_n'] = (nxu, nyu)
                    self.compute['kuv_box'] = (float(utab['x0']), float(utab['y0']), float(utab['dx']), float(utab['dy']))
                else:
                    gt_ = np.zeros((1, 4), 'f4'); self.compute['kuv_n'] = (0, 0); self.compute['kuv_box'] = (0., 0., 1., 1.)
                self.kuvt_buf = self.ctx.buffer(gt_.tobytes())
            if v.get('kin-uv') and 'kuv_n' in self.compute:
                import json as _json
                utab = _json.loads((HERE / 'analysis' / f'kin-uv-v{int(v["kin-uv"])}.json').read_text('utf-8')).get(self.directory.name)
                gu_ = np.zeros((144, 4), 'f4')
                if isinstance(utab, dict):
                    nxu, nyu = int(utab['nx']), int(utab['ny']); assert nxu * nyu <= 144
                    gu_[:nxu * nyu, 0:2] = utab['u']; gu_[:nxu * nyu, 2:4] = utab['a']
                    self.compute['kuv_n'] = (nxu, nyu)
                    self.compute['kuv_box'] = (float(utab['x0']), float(utab['y0']), float(utab['dx']), float(utab['dy']))
                else:
                    self.compute['kuv_n'] = (0, 0); self.compute['kuv_box'] = (0., 0., 1., 1.)
                self.compute['kuv_g'].write(gu_.tobytes())
            if v.get('kin-turb') and 'kt_n' in self.compute:
                import json as _json
                ttab = _json.loads((HERE / 'analysis' / f'kin-turb-v{int(v["kin-turb"])}.json').read_text('utf-8')).get(self.directory.name)
                g_ = np.zeros((144, 2), 'f4')
                if isinstance(ttab, dict):
                    nx_, ny_ = int(ttab['nx']), int(ttab['ny']); assert nx_ * ny_ <= 144
                    g_[:nx_ * ny_, 0] = ttab['th']; g_[:nx_ * ny_, 1] = ttab['s']
                    self.compute['kt_n'] = (nx_, ny_)
                    self.compute['kt_box'] = (float(ttab['x0']), float(ttab['y0']), float(ttab['dx']), float(ttab['dy']))
                else:
                    self.compute['kt_n'] = (0, 0); self.compute['kt_box'] = (0., 0., 1., 1.)
                self.compute['kt_g'].write(g_.tobytes())
                if v.get('kin-turb2') and 'kt2_n' in self.compute:
                    t2 = _json.loads((HERE / 'analysis' / f'kin-turb2-v{int(v["kin-turb2"])}.json').read_text('utf-8')).get(self.directory.name)
                    g2_ = np.zeros((144, 2), 'f4')
                    if isinstance(t2, dict):
                        nx2, ny2 = int(t2['nx']), int(t2['ny']); assert nx2 * ny2 <= 144
                        g2_[:nx2 * ny2, 0] = t2['off']; g2_[:nx2 * ny2, 1] = t2['pi']
                        self.compute['kt2_n'] = (nx2, ny2)
                        self.compute['kt2_box'] = (float(t2['x0']), float(t2['y0']), float(t2['dx']), float(t2['dy']))
                    else:
                        self.compute['kt2_n'] = (0, 0); self.compute['kt2_box'] = (0., 0., 1., 1.)
                    self.compute['kt2_g'].write(g2_.tobytes())
            if v.get('kin-dir') and 'kd_rot' in self.compute:
                import json as _json
                if 'kin-dir-seed-table' in v:
                    dtab = _json.loads((HERE / 'analysis' / f'kin-dir-seed-v{int(v["kin-dir-seed-table"])}.json').read_text('utf-8'))
                    dseed = int(dtab.get(self.directory.name, 0))
                else:
                    dseed = int(v.get('kin-dir-seed', 0))
                drng = np.random.default_rng(0x6b6469 + 7919 * dseed)
                for key in ('kd_pa0', 'kd_pb0', 'kd_pa1', 'kd_pb1'):
                    # 照旧按顺序抽随机数（种子不变）；kin-dir-s=0 时速度场那一组被编译器优化掉，对应的 uniform 不存在。
                    ph = tuple(float(q) for q in drng.uniform(0., 2. * np.pi, 3))
                    if key in self.compute: self.compute[key] = ph
                self.compute['kd_rot'] = tuple(float(q) for q in drng.uniform(0., np.pi, 2))
            if v.get('kin-aff') and 'kin_aff_t' in self.compute:
                import json as _json
                atab = _json.loads((HERE / 'analysis' / f'kin-aff-v{int(v["kin-aff"])}.json').read_text('utf-8')).get(self.directory.name)
                ka = float(v.get('kin-aff-k', 1.))     # kin-aff-k=k：只用于诊断，整体乘 k
                if isinstance(atab, dict):
                    self.compute['kin_aff_t'] = tuple(float(q) * ka for q in atab['t'])
                    M_ = atab['M']; self.compute['kin_aff_m'] = (float(M_[0][0]) * ka, float(M_[0][1]) * ka, float(M_[1][0]) * ka, float(M_[1][1]) * ka)
                else:
                    self.compute['kin_aff_t'] = (0., 0.); self.compute['kin_aff_m'] = (0., 0., 0., 0.)
            self.reset()
        if 'young-sheet' in v or 'young-glow' in v or 'young-white' in v:
            ya0 = v.get('young-a0', .06); ya1 = v.get('young-a1', .12)
            self.program['ys_s'] = v.get('young-sheet', 0.); self.program['ys_a0'] = ya0; self.program['ys_a1'] = ya1
            self.program['yg_k'] = v.get('young-glow', 0.); self.program['yg_a0'] = ya0; self.program['yg_a1'] = ya1
            self.program['yw_k'] = v.get('young-white', 0.)
            self.program['yf_s'] = v.get('young-front', 0.)
        if 'late-k' in v or 'late-table' in v:
            k_late = float(v.get('late-k', 1.))
            if 'late-table' in v:
                import json as _json
                ltab = _json.loads((HERE / 'analysis' / f'late-table-v{int(v["late-table"])}.json').read_text('utf-8'))
                if isinstance(ltab.get(self.directory.name), (int, float)): k_late = float(ltab[self.directory.name])
            self.program['late_k'] = k_late; self.program['late_a0'] = v.get('late-a0', .12); self.program['late_a1'] = v.get('late-a1', .40)
        if 'fadein-count' in v:
            self.program['fadein_a'] = v['fadein-count']
        if 'pure-color' in v:
            self.program['spark_pure_gain'] = v['pure-color']
            # 亮度下限（线性空间）：深色颗粒按亮度缺口整体加亮，深色背景上仍可见；浅色背景上远低于背景，不影响深度。
            if 'spark_pure_floor' in self.program: self.program['spark_pure_floor'] = v.get('pure-floor', 0.)
            # pure-mix=m：只按比例 m 用源色替换提亮后的颜色（1 为完全替换）。
            if 'spark_pure_mix' in self.program: self.program['spark_pure_mix'] = v.get('pure-mix', 1.)
            # pure-mix-old=m1：年龄在 pure-mix-a0～pure-mix-a1（缺省 0.08～0.25）之间，源色替换比例由 pure-mix 过渡到 m1；缺省 m1 = pure-mix（不随年龄变）。
            if 'spark_pure_old' in self.program:
                self.program['spark_pure_old'] = v.get('pure-mix-old', v.get('pure-mix', 1.))
                self.program['spark_pure_a0'] = v.get('pure-mix-a0', .08); self.program['spark_pure_a1'] = v.get('pure-mix-a1', .25)
        if 'dfade' in v:
            self.program['dfade_k'] = v['dfade']; self.program['dfade_lo'] = v.get('dfade-lo', .3); self.program['dfade_hi'] = v.get('dfade-hi', 1.)
        if v.get('screen-clip'):
            import moderngl
            from PIL import Image as _Image
            path = HERE / 'assets' / self.meta['name'] / 'screen-mask.png'
            if path.is_file(): mk = np.array(_Image.open(path).convert('L'))
            else: mk = np.full((self.h, self.w), 255, np.uint8)
            mk = np.ascontiguousarray(mk)
            self.screen_tex = self.ctx.texture((mk.shape[1], mk.shape[0]), 1, mk.tobytes())
            self.screen_tex.filter = (moderngl.NEAREST, moderngl.NEAREST)
            self.program['screen_mask'] = 14
        if 'jam' in v:
            import math as _m
            g = 4. * self.span / 468.; lo = (-.38 * self.cw, -.38 * self.ch)
            self.jam_shape = (int(_m.ceil(1.76 * self.cw / g)), int(_m.ceil(1.76 * self.ch / g)))
            self.jam_grid = self.ctx.buffer(reserve=self.jam_shape[0] * self.jam_shape[1] * 4)
            self.jam_splat = self.ctx.compute_shader((HERE / 'analysis/jam_splat.glsl').read_text('utf-8'))
            self.jam_splat['gshape'] = self.jam_shape; self.jam_splat['glo'] = lo; self.jam_splat['gcell'] = g; self.jam_splat['count'] = self.n
            self.compute['jam_gshape'] = self.jam_shape; self.compute['jam_glo'] = lo; self.compute['jam_gcell'] = g
            self.compute['jam_rho0'] = self.n / (self.cw * self.ch) * g * g
            self.compute['jam_k'] = v['jam'] if v['jam'] is not True else .85; self.compute['jam_lo'] = v.get('jam-lo', 1.3)
            self.compute['jam_hi'] = v.get('jam-hi', 2.2); self.compute['jam_look'] = v.get('jam-look', .025) * self.span
            if 'jam_wall' in self.compute: self.compute['jam_wall'] = 1. if 'wall' in v else 0.
            self.reset()
        if 'burst' in v:
            k = float(v['burst']) * (float(self.layout_change) if v.get('burst-g') else 1.)
            self.compute['burst_k'] = max(k, 1e-9); self.compute['burst_tau'] = float(v.get('burst-tau', .05))
            self.compute['burst_early'] = float(v.get('burst-early', 1e6))
        if v.get('late-speed'):
            import json as _json
            table = _json.loads((HERE / 'analysis/late-speed.json').read_text('utf-8'))
            self.compute['late_s'] = float(table.get(self.meta['name'], 1.)) if isinstance(table.get(self.meta['name'], 1.), (int, float)) else 1.
            self.compute['late_a'] = v.get('late-a', .08); self.compute['late_b'] = v.get('late-b', .30)
            self.reset()
        if 'wall' in v or 'wall2' in v:
            import moderngl
            from scipy.ndimage import gaussian_filter
            from surface_transfer import spatial_clocks
            _, wfield = spatial_clocks(self.base, self.nx, self.ny, self.release_spread)
            wfield = gaussian_filter(wfield.astype(np.float64), 1.5).astype('f4')
            self.wall_tex = self.ctx.texture((self.nx, self.ny), 1, wfield.tobytes(), dtype='f4')
            self.wall_tex.filter = (moderngl.LINEAR, moderngl.LINEAR)
            wm = v.get('wall', v.get('wall2', True))
            self.compute['wall_field'] = 13; self.compute['wall_margin'] = wm if wm is not True else .01
            self.reset()
        if 'dwell' in v:
            self.compute['dwell_delta'] = v['dwell'] if v['dwell'] is not True else .08; self.compute['dwell_k0'] = v.get('dwell-k0', .5)
            self.compute['dwell_tau'] = v.get('dwell-tau', .12); self.compute['dwell_peel'] = v.get('dwell-peel', 0.)
            self.reset()
        if 'roll-front' in v:
            import moderngl
            from scipy.ndimage import gaussian_filter
            from surface_transfer import spatial_clocks
            _, field = spatial_clocks(self.base, self.nx, self.ny, self.release_spread)
            field = gaussian_filter(field.astype(np.float64), 2.)
            gy, gx = np.gradient(field, self.cell[1], self.cell[0])
            g2 = np.maximum(gx * gx + gy * gy, 1e-12)
            # 前沿速度 = ∇T/|∇T|²（像素 / 单位进度），上限 4 个短边 / 单位进度。
            vx, vy = gx / g2, gy / g2; sp = np.hypot(vx, vy); cap = 4. * self.span
            k = np.minimum(1., cap / np.maximum(sp, 1e-9)); vx *= k; vy *= k
            data = np.stack([vx, vy, np.zeros_like(vx), np.zeros_like(vx)], -1).astype('f4')
            self.roll_front_tex = self.ctx.texture((self.nx, self.ny), 4, data.tobytes(), dtype='f4')
            self.roll_front_tex.filter = (moderngl.LINEAR, moderngl.LINEAR)
            self.program['roll_front_tex'] = 12; self.program['roll_k'] = v['roll-front']; self.program['roll_age'] = v.get('roll-age', .05)
            self.program['wind'] = self.wind
        if v.get('transport-mono'):
            self.compute['transport_field_c'] = 8; self.compute['mono_age'] = v.get('mono-age', .03)
            self.reset()
        if 'dramp-early' in v:
            self.program['dramp_early'] = v['dramp-early']; self.program['dramp_age'] = v.get('dramp-age', .25)
        # u-<uniform>=<值>：初始化之后直接改绘制程序的统一变量（如 u-fission_strength=0 关闭细分）。
        for key, value in v.items():
            if key.startswith('u-'):
                self.program[key[2:]] = value
            if key.startswith('c-'):
                self.compute[key[2:]] = value
        scales = [v.get('scatter-density', 1.), v.get('scatter-neutral', 1.), v.get('scatter-chroma', 1.)]
        floors = [v.get('scatter-density-min', -9.), v.get('scatter-neutral-min', -9.), v.get('scatter-chroma-min', -9.)]
        if scales != [1., 1., 1.] or floors != [-9., -9., -9.]:
            # 细粒材质三通道（覆盖、中性反射、色度）分别缩放或设下限；读回已上传的共同函数纹理后改写。
            data = np.frombuffer(self.scatter_tex.read(), np.float32).reshape(-1, 3).copy()
            data = np.maximum(data * np.float32(scales), np.float32(floors))
            self.scatter_tex.write(data.astype(np.float32).tobytes())
        if 'xfer-pair' in v or 'xfer-broaden' in v:
            # 表面交接的两个权重（surface-transfer-rules.json 的 pairing 0.2、broaden 1.0）：pairing 越大，表面越接近「每片出生时
            # 在 0.016 进度内硬切」，broaden 越大，越接近按 transfer.y 加宽的软淡出。依据：原片前沿剖面在局部年龄 0 处很陡，
            # 模型释放前就已有可见量（scratchpad front_profile.py）。所有段同值。
            if 'transfer_pairing' in self.program and 'xfer-pair' in v: self.program['transfer_pairing'] = float(v['xfer-pair'])
            if 'transfer_broaden' in self.program and 'xfer-broaden' in v: self.program['transfer_broaden'] = float(v['xfer-broaden'])
        if 'touch' in v:
            # 触点在该方向可用背景中的相对远近（0 为紧贴控件，1 为屏幕边缘）；与正式接口同一换算。
            self.touch_strength = float(v['touch'])
            self.compute['touch_strength'] = self.touch_strength
            self.pressure_grid.splat['touch_strength'] = self.touch_strength
            self.reset()
        if 'birth-spread' in v:
            # 诊断：出生时刻相对共同释放时钟的随机离散乘 k（0 为同一位置同时出生）。
            clock = self.transfer_clock
            self.base[:, 2] = np.maximum(clock + v['birth-spread'] * (self.base[:, 2] - clock), .001)
            self.base[:, 6] = np.minimum(self.base[:, 6], .995 - self.base[:, 2])
            self.material.write(self.base.tobytes()); self.reset()
        if 'sync-birth' in v:
            # sync-birth=k：尾缘（前沿朝向最近卡片边）颗粒的出生提前 k·0.065·尾缘系数，尾缘系数与顶点着色器同式；着色器里表面交接提前量
            # 相应减为 (1 − k)·0.065，卡面消失时刻不变。依据：探针口径下模型与原片的解除时刻十段各距离档中位差都是 0，而颗粒出生
            # 比原片解除在 0～10 像素带晚 +0.04～+0.085（scratchpad probe_edge.py、edge_release.py）——表面交接提前把边缘颗粒推迟了；
            # 原片边缘颗粒在卡面消失时就成批出发（地球背景左上的竖直羽流）。所有段同值。
            k_eb = float(v['sync-birth']); sx_ = self.base[:, 0].astype(np.float64); sy_ = self.base[:, 1].astype(np.float64)
            ex_ = np.minimum(sx_, self.cw - sx_); ey_ = np.minimum(sy_, self.ch - sy_); L_ = self.span * .15
            nx_ = np.where(sx_ < self.cw * .5, -1., 1.) * np.exp(-ex_ / L_) + 1e-6; ny_ = np.where(sy_ < self.ch * .5, -1., 1.) * np.exp(-ey_ / L_) + 1e-6
            dd_ = (nx_ * self.base[:, 4] + ny_ * self.base[:, 5]) / np.hypot(nx_, ny_)
            t_ = np.clip(dd_ / .6, 0., 1.); tr_ = t_ * t_ * (3. - 2. * t_) * np.maximum(np.exp(-ex_ / L_), np.exp(-ey_ / L_))
            self.base[:, 2] = np.maximum(self.base[:, 2] - k_eb * .065 * tr_, .001).astype(self.base.dtype)
            self.material.write(self.base.tobytes()); self.reset()
        if 'life-born' in v:
            # 寿命中随出生时刻增长的系数（原 0.20）改为给定值：晚出生的材料活得更久，早出生的不变；仍不越过动画终点。
            born = self.base[:, 2]
            self.base[:, 6] = np.minimum(self.base[:, 6] + (v['life-born'] - .20) * born * .97, .985 - born)
            self.material.write(self.base.tobytes()); self.reset()
        if 'life-fixed' in v:
            # 每颗寿命 = L·(1 + j·(2u − 1))（u 逐颗固定、均匀），代替接手时的长尾寿命分布，仍不越过动画终点。依据：原片最后一批释放之后
            # 约 0.3 进度可见颗粒降到峰值的一成（scratchpad vis_decay.py，六段录屏 +0.27～+0.31），寿命 ×3 的模型到动画末尾都还在。
            L = float(v['life-fixed']); j = float(v.get('life-fixed-j', .2))
            u = np.random.default_rng(0x5bd1e995).random(len(self.base))
            self.base[:, 6] = np.minimum(L * (1 + j * (2 * u - 1)), .995 - self.base[:, 2]).astype(self.base.dtype)
            self.material.write(self.base.tobytes()); self.reset()
        if 'life' in v:
            # 诊断：整体放大可见寿命，仍不越过动画终点。life-end=e：动画终点（缺省 0.995）提前到 e，所有颗粒在 e 之前走完寿命
            # （寿命末端照常淡出），对应「整段动画有固定时长、颗粒在时长内消失」；原片多数段 0.85～0.9 已基本走空。
            self.base[:, 6] = np.minimum(self.base[:, 6] * v['life'], float(v.get('life-end', .995)) + float(v.get('pre-roll', 0.)) - self.base[:, 2])
            self.material.write(self.base.tobytes()); self.reset()
    attrs['__init__'] = init
    if v.get('kin-uvt'):
        inner_seek = attrs.get('seek') or base.seek
        def seek_uvt(self, time):
            self.kuvt_buf.bind_to_storage_buffer(8)
            return inner_seek(self, time)
        attrs['seek'] = seek_uvt
    if v.get('kin-euler'):
        inner_seek_e = attrs.get('seek') or base.seek
        def seek_euler(self, time):
            self.keu_buf.bind_to_storage_buffer(13)
            return inner_seek_e(self, time)
        attrs['seek'] = seek_euler
    if v.get('kin-disp'):
        inner_seek_d = attrs.get('seek') or base.seek
        def seek_disp(self, time):
            self.kdisp_buf.bind_to_storage_buffer(9)
            return inner_seek_d(self, time)
        attrs['seek'] = seek_disp
    core_render = base.render
    if v.get('srgb-particles'):
        class _SrgbpVao:
            # 拦截颗粒道的绘制：先把表面道之后的线性帧缓冲编码成 sRGB，再在编码缓冲上按编码值混合颗粒。
            # 继承链上各层的缓冲与纹理绑定照常由原渲染流程完成，这里只插入换算与切换帧缓冲。
            def __init__(self, inner, owner): self.inner = inner; self.owner = owner
            def render(self, *a, **k):
                import moderngl
                o = self.owner
                if getattr(o, 'srgbp_active', False) and o.program['material_pass'].value == 1:
                    o.ctx.disable(moderngl.BLEND); o.srgbp_fbo.use(); o.tex.use(2); o.srgbp_conv_vao.render(mode=moderngl.TRIANGLES, vertices=3)
                    o.ctx.enable(moderngl.BLEND); o.ctx.blend_func = (moderngl.ONE, moderngl.ONE_MINUS_SRC_ALPHA)
                    o.fg_tex.use(0); o.program['srgbp_on'] = 1.
                    self.inner.render(*a, **k)
                    o.program['srgbp_on'] = 0.; o.srgbp_pending_out = True
                else:
                    self.inner.render(*a, **k)
            def __getattr__(self, name): return getattr(self.inner, name)

        class _SrgbpOut:
            # 输出道：颗粒已按编码值合成时，从编码缓冲原样降采样输出，不再换算。
            def __init__(self, inner, owner): self.inner = inner; self.owner = owner
            def render(self, *a, **k):
                o = self.owner
                if getattr(o, 'srgbp_pending_out', False):
                    o.srgbp_tex.use(2); o.srgbp_out_vao.render(*a, **k); o.srgbp_pending_out = False
                else:
                    self.inner.render(*a, **k)
            def __getattr__(self, name): return getattr(self.inner, name)

        def render_srgbp(self, time, diagnostic=0):
            import moderngl
            import renderer as R
            if not hasattr(self, 'srgbp_fbo'):
                self.srgbp_tex = self.ctx.texture(self.tex.size, 4, dtype='f2'); self.srgbp_tex.filter = (moderngl.LINEAR, moderngl.LINEAR)
                self.srgbp_fbo = self.ctx.framebuffer([self.srgbp_tex])
                # 与 OUTPUT 同一采样（整幅原样拷贝），只把线性值编码成 sRGB；输出道改为不换算。
                _conv_src = R.OUTPUT.replace(
                    'frag=vec4(srgb(texture(screen,vec2(uv.x,1.-uv.y)).rgb),1);', 'vec4 t=texture(screen,vec2(uv.x,1.-uv.y));frag=vec4(srgb(max(t.rgb,vec3(0.))),t.a);')
                _out_src = R.OUTPUT.replace(
                    'vec3 srgb(vec3 c){return mix(c*12.92,1.055*pow(max(c,0.),vec3(1./2.4))-.055,step(vec3(.0031308),c));}', 'vec3 srgb(vec3 c){return clamp(c,0.,1.);}')
                if 'srgbp-gamma' in v:
                    _g = float(v['srgbp-gamma']); _sd = 'vec3 srgb(vec3 c){return mix(c*12.92,1.055*pow(max(c,0.),vec3(1./2.4))-.055,step(vec3(.0031308),c));}'
                    assert R.OUTPUT.count(_sd) == 1, 'srgbp-gamma: ' + _sd
                    _conv_src = _conv_src.replace(_sd, f'vec3 srgb(vec3 c){{return pow(max(c,vec3(0.)),vec3({1. / _g:.6f}));}}', 1)
                    _out_src = R.OUTPUT.replace(_sd, f'vec3 srgb(vec3 c){{vec3 l=pow(clamp(c,0.,1.),vec3({_g:.6f}));return mix(l*12.92,1.055*pow(l,vec3(1./2.4))-.055,step(vec3(.0031308),l));}}', 1)
                self.srgbp_conv = self.ctx.program(vertex_shader=R.FULLVERT, fragment_shader=_conv_src)
                self.srgbp_conv_vao = self.ctx.vertex_array(self.srgbp_conv, [])
                self.srgbp_out = self.ctx.program(vertex_shader=R.FULLVERT, fragment_shader=_out_src)
                self.srgbp_out_vao = self.ctx.vertex_array(self.srgbp_out, [])
                self.srgbp_conv['screen'] = 2; self.srgbp_out['screen'] = 2
                self.vao = _SrgbpVao(self.vao, self); self.out_vao = _SrgbpOut(self.out_vao, self)
            self.srgbp_active = True; self.srgbp_pending_out = False
            try:
                return base.render(self, time, diagnostic)
            finally:
                self.srgbp_active = False; self.srgbp_pending_out = False
        core_render = render_srgbp; attrs['render'] = render_srgbp
    if 'dens-fade' in v:
        # 每帧渲染之后把运动颗粒再画一遍（加法混合，alpha 即覆盖量）到全分辨率浮点纹理，建 mipmap，供下一帧按局部覆盖量淡出零散颗粒。
        # 用上一帧的覆盖量（相差 1/120 进度，颗粒移动不到 1 像素）；时间倒退（重放、拖动）的那一帧不淡出。
        dens_inner = core_render
        _dk = float(v['dens-fade']); _dd0 = float(v.get('dens-fade-d0', .04)); _dd1 = float(v.get('dens-fade-d1', .2))
        _da0 = float(v.get('dens-fade-a0', .08)); _da1 = float(v.get('dens-fade-a1', .25)); _dpx = float(v.get('dens-fade-px', 8.))

        def render_dens(self, time, diagnostic=0):
            import moderngl, math as _m
            fresh = hasattr(self, 'dens_tex') and time >= getattr(self, 'dens_last_t', -1.)
            if 'dens_k' in self.program:
                self.program['dens_k'] = _dk if fresh else 0.
                if fresh:
                    self.dens_tex.use(12)
            frame = dens_inner(self, time, diagnostic)
            if 'dens_k' not in self.program:
                return frame
            if not hasattr(self, 'dens_tex'):
                self.dens_tex = self.ctx.texture(self.tex.size, 4, dtype='f4'); self.dens_fbo = self.ctx.framebuffer([self.dens_tex])
                self.dens_tex.filter = (moderngl.LINEAR_MIPMAP_LINEAR, moderngl.LINEAR); self.dens_tex.repeat_x = False; self.dens_tex.repeat_y = False
                q = self.tex.size[0] / max(self.w, 1)
                self.program['dens_tex'] = 12; self.program['dens_size'] = tuple(float(z) for z in self.tex.size)
                self.program['dens_lod'] = float(_m.log2(max(_dpx * q, 1.)))
                for k_, v_ in (('dens_d0', _dd0), ('dens_d1', _dd1), ('dens_a0', _da0), ('dens_a1', _da1)): self.program[k_] = v_
            self.dens_fbo.use(); self.dens_fbo.clear(0., 0., 0., 0.)
            self.ctx.enable(moderngl.BLEND); self.ctx.blend_func = (moderngl.ONE, moderngl.ONE)
            self.fg_tex.use(0); self.program['material_pass'] = 1; self.program['dens_pass'] = 1
            self.vao.render(mode=moderngl.TRIANGLES, vertices=6, instances=self.n)
            self.ctx.disable(moderngl.BLEND); self.program['dens_pass'] = 0
            self.dens_tex.build_mipmaps(); self.dens_last_t = time
            return frame
        core_render = render_dens; attrs['render'] = render_dens
    if 'glow-k' in v:
        # 颗粒辉光：正常渲染后，把运动颗粒单独再画到浮点缓冲，模糊后在线性空间按 glow-k 加回画面。
        # glow-sigma 为模糊半径（卡片短边 468 时的场景像素，按短边缩放），glow-thr 为线性亮度阈值（只让更亮的部分发光）。
        def render(self, time, diagnostic=0):
            import moderngl, cv2
            frame = core_render(self, time, diagnostic)
            if not hasattr(self, 'glow_fbo'):
                self.glow_tex = self.ctx.texture(self.tex.size, 4, dtype='f4'); self.glow_fbo = self.ctx.framebuffer([self.glow_tex])
            self.glow_fbo.use(); self.glow_fbo.clear(0., 0., 0., 0.)
            self.ctx.enable(moderngl.BLEND); self.ctx.blend_func = (moderngl.ONE, moderngl.ONE_MINUS_SRC_ALPHA)
            self.fg_tex.use(0); self.fg_tex.filter = (moderngl.LINEAR, moderngl.LINEAR)
            self.program['material_pass'] = 1
            self.vao.render(mode=moderngl.TRIANGLES, vertices=6, instances=self.n)
            self.ctx.disable(moderngl.BLEND)
            W, H = self.glow_tex.size
            g = np.frombuffer(self.glow_tex.read(), np.float32).reshape(H, W, 4)[::-1, :, :3]
            g = cv2.resize(g, (self.w, self.h), interpolation=cv2.INTER_AREA)
            thr = v.get('glow-thr', 0.)
            if thr > 0:
                lum = g @ np.float32([.2126, .7152, .0722])
                g = g * (np.maximum(lum - thr, 0.) / np.maximum(lum, 1e-6))[..., None]
            sigma = v.get('glow-sigma', 3.) * self.span / 468.
            g = cv2.GaussianBlur(g, (0, 0), sigma)
            x, y = self.meta['rect'][:2]
            f = frame.astype(np.float32) / 255.
            lin = np.where(f <= .04045, f / 12.92, ((f + .055) / 1.055) ** 2.4)
            lin = lin + v['glow-k'] * g
            out = np.where(lin <= .0031308, lin * 12.92, 1.055 * np.power(np.maximum(lin, 0.), 1 / 2.4) - .055)
            return np.clip(out * 255. + .5, 0, 255).astype(np.uint8)
        attrs['render'] = render
    if 'accum-k' in v:
        # 重叠累加提亮：运动颗粒另画两遍到浮点缓冲，一遍加法混合（各颗粒的预乘颜色相加）、一遍正常合成；
        # 两者之差是「颗粒互相重叠时多出来的光」，按 accum-k 在线性空间加回画面。孤立颗粒的差为 0，只有密集处变亮。
        # accum-sigma 为可选的轻微模糊（卡片短边 468 时的场景像素，按短边缩放）。
        def render(self, time, diagnostic=0):
            import moderngl, cv2
            frame = core_render(self, time, diagnostic)
            if not hasattr(self, 'accum_fbo'):
                self.accum_tex = self.ctx.texture(self.tex.size, 4, dtype='f4'); self.accum_fbo = self.ctx.framebuffer([self.accum_tex])
            bufs = []
            if 'accum_min_age' in self.program: self.program['accum_min_age'] = v.get('accum-min-age', 0.)
            if 'add_mix' in self.program: self.program['add_mix'] = 0.
            for blend in [(moderngl.ONE, moderngl.ONE), (moderngl.ONE, moderngl.ONE_MINUS_SRC_ALPHA)]:
                self.accum_fbo.use(); self.accum_fbo.clear(0., 0., 0., 0.)
                self.ctx.enable(moderngl.BLEND); self.ctx.blend_func = blend
                self.fg_tex.use(0); self.fg_tex.filter = (moderngl.LINEAR, moderngl.LINEAR)
                self.program['material_pass'] = 1
                self.vao.render(mode=moderngl.TRIANGLES, vertices=6, instances=self.n)
                self.ctx.disable(moderngl.BLEND)
                W, H = self.accum_tex.size
                bufs.append(np.frombuffer(self.accum_tex.read(), np.float32).reshape(H, W, 4)[::-1, :, :3].copy())
            if 'accum_min_age' in self.program: self.program['accum_min_age'] = 0.
            if 'add_mix' in self.program: self.program['add_mix'] = float(v.get('add-mix', 0.))
            # accum-rest=r：只把超过静止密铺重叠（加法亮度约为正常合成的 r 倍）的部分当成堆积。圆点直径大于颗粒间距时，
            # 原处不动的颗粒也互相重叠，不扣除这部分会让停留的颗粒整片发亮。
            g = np.maximum(bufs[0] - v.get('accum-rest', 1.) * bufs[1], 0.)
            g = cv2.resize(g, (self.w, self.h), interpolation=cv2.INTER_AREA)
            sigma = v.get('accum-sigma', 0.) * self.span / 468.
            if sigma > 0: g = cv2.GaussianBlur(g, (0, 0), sigma)
            f = frame.astype(np.float32) / 255.
            lin = np.where(f <= .04045, f / 12.92, ((f + .055) / 1.055) ** 2.4)
            lin = lin + v['accum-k'] * g
            out = np.where(lin <= .0031308, lin * 12.92, 1.055 * np.power(np.maximum(lin, 0.), 1 / 2.4) - .055)
            return np.clip(out * 255. + .5, 0, 255).astype(np.uint8)
        attrs['render'] = render
    if 'soft' in v:
        # 颗粒层轻微柔化（作为动画效果，所有段同值）：把背景与未释放表面单独画一遍得到底层 U，整帧线性光 L 减去 U 即颗粒（含堆积提亮）
        # 的贡献，按 σ 模糊后加回：L' = U + G_σ(L − U)。背景与未释放画面不受影响。σ = soft × 当前圆点直径（随屏幕缩放）。
        # 依据：用户 2026-10-01 指出华为参考动画可能自带光晕或轻微模糊，可以直接模拟；原片颗粒纹理比模型软、对比低。
        soft_inner = attrs.get('render')

        def render_soft(self, time, diagnostic=0):
            import moderngl, cv2
            frame = soft_inner(self, time, diagnostic) if soft_inner else core_render(self, time, diagnostic)
            if not hasattr(self, 'soft_fbo'):
                self.soft_tex = self.ctx.texture(self.tex.size, 4, dtype='f4'); self.soft_fbo = self.ctx.framebuffer([self.soft_tex])
            self.soft_fbo.use(); self.soft_fbo.clear(0., 0., 0., 0.)
            self.ctx.disable(moderngl.BLEND); self.bg_tex.use(1); self.bg_vao.render(mode=moderngl.TRIANGLES, vertices=3)
            self.ctx.enable(moderngl.BLEND); self.ctx.blend_func = (moderngl.ONE, moderngl.ONE_MINUS_SRC_ALPHA)
            self.fg_tex.use(0); self.fg_tex.filter = (moderngl.NEAREST, moderngl.NEAREST)
            self.program['material_pass'] = 0
            self.vao.render(mode=moderngl.TRIANGLES, vertices=6, instances=self.n)
            self.ctx.disable(moderngl.BLEND)
            W, H = self.soft_tex.size
            U = np.frombuffer(self.soft_tex.read(), np.float32).reshape(H, W, 4)[::-1, :, :3]
            U = cv2.resize(U, (self.w, self.h), interpolation=cv2.INTER_AREA)
            f = frame.astype(np.float32) / 255.
            lin = np.where(f <= .04045, f / 12.92, ((f + .055) / 1.055) ** 2.4)
            size = float(self.program['spark_size'].value) if 'spark_size' in self.program else 2.6
            sigma = float(v['soft']) * size
            lin = U + cv2.GaussianBlur(lin - U, (0, 0), sigma)
            out = np.where(lin <= .0031308, lin * 12.92, 1.055 * np.power(np.maximum(lin, 0.), 1 / 2.4) - .055)
            return np.clip(out * 255. + .5, 0, 255).astype(np.uint8)
        attrs['render'] = render_soft
    if v.get('screen-clip'):
        clip_inner = attrs.get('render')

        def render_clip(self, time, diagnostic=0):
            self.screen_tex.use(14)
            return (clip_inner(self, time, diagnostic) if clip_inner else base.render(self, time, diagnostic))
        attrs['render'] = render_clip
    if 'jam' in v:
        from renderer import STEP as _STEP

        def seek_jam(self, time):
            target = int(max(time, 0) / _STEP + 1e-6)
            if target < self.step: self.reset()
            self.pressure_grid.texture.use(5)
            if hasattr(self, 'wall_tex'): self.wall_tex.use(13)
            self.material.bind_to_storage_buffer(0); self.state.bind_to_storage_buffer(1)
            self.pigment.bind_to_storage_buffer(2); self.peel_compression.bind_to_storage_buffer(3)
            self.flow_tex.use(3); self.confidence_tex.use(4); self.jam_grid.bind_to_storage_buffer(11)
            while self.step < target:
                self.step += 1; t = self.step * _STEP; self.compute['time'] = t
                self.jam_splat['time'] = t
                self.jam_splat['pass'] = 0; self.jam_splat.run(group_x=(self.jam_shape[0] * self.jam_shape[1] + 255) // 256); self.ctx.memory_barrier()
                self.jam_splat['pass'] = 1; self.jam_splat.run(group_x=(self.n + 255) // 256); self.ctx.memory_barrier()
                self.compute.run(group_x=(self.n + 255) // 256)
                self.ctx.memory_barrier()
                if self.step % 4 == 0:
                    self.pressure_grid.update(t)
                    self.material.bind_to_storage_buffer(0); self.state.bind_to_storage_buffer(1)
                    self.pigment.bind_to_storage_buffer(2); self.peel_compression.bind_to_storage_buffer(3); self.jam_grid.bind_to_storage_buffer(11)
        attrs['seek'] = seek_jam
    if 'wall' in v or 'wall2' in v:
        wall_inner = attrs.get('render')

        def render_wall(self, time, diagnostic=0):
            self.wall_tex.use(13)
            return (wall_inner(self, time, diagnostic) if wall_inner else base.render(self, time, diagnostic))
        attrs['render'] = render_wall
    if 'roll-front' in v:
        inner_render = attrs.get('render')

        def render_roll(self, time, diagnostic=0):
            self.roll_front_tex.use(12)
            return (inner_render(self, time, diagnostic) if inner_render else base.render(self, time, diagnostic))
        attrs['render'] = render_roll
    if 'rspread' in v:
        # 逐颗释放时刻抖动：原为 release_spread（0.060，白底材料另加 white_spread·body），按 c_n≈0.65 短边/进度相当于前沿 ±12 像素的噪声，
        # 会把顺风前沿处的压实摊开（顾问第十次意见：原片把同量的增亮汇聚成近饱和的脊，模型摊成均匀灰白雾）。rspread=s：材料构建之后
        # 把每颗的出生时刻改为「平滑释放场 + s·(随机 − 0.5)」，并同步着色器里的 release_spread（表面交接与时钟用的同一个量）。所有段同值。
        rs_new = float(v['rspread'])
        mb_inner = attrs.get('material_builder')

        def material_builder(self, width, height, foreground, direction, seed, cell_px=None):
            built = (mb_inner(self, width, height, foreground, direction, seed, cell_px) if mb_inner
                     else base.material_builder(self, width, height, foreground, direction, seed, cell_px))
            b = built['base']; old = float(built['release_spread'])
            b[:, 2] = np.maximum(b[:, 2] + np.float32(rs_new - old) * (b[:, 11] - np.float32(.5)), np.float32(.001))
            built['release_spread'] = rs_new
            return built
        attrs['material_builder'] = material_builder
    if 'layout-g' in v:
        # 所有段共用同一个布局差异系数（用户 2026-10-01 裁定：全段一套参数）：layout_change 固定为给定值，
        # 释放预测算出的布局距离不再参与任何规则。数据描述符优先于实例属性，父类里的赋值被忽略；
        # 放在最终组装的类上，圆点外观、边缘规则等替换或包装过的继承链都会读到它。
        fixed_g = float(v['layout-g'])
        attrs['layout_change'] = property(lambda self, _g=fixed_g: _g, lambda self, value: None)
    if v.get('p-under'):
        # 颗粒画在原表面之下（先颗粒、后表面）：已释放的颗粒飞过尚未解除的卡面时被卡面盖住。依据：原片卡片内的亮度高频能量
        # 在局部年龄 −0.4～0 一直平（4.9～4.6），模型从 −0.2 起就上升（−0.05～0 时 7.2），颗粒画在了未解除的卡面上（scratchpad texture_profile.py）。
        attrs['pass_order'] = (1, 0)
    return type(f'Variant_{abs(hash(spec)) % 10**8}', (base,), attrs), options
