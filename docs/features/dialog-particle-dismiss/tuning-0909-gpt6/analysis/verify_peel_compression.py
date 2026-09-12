"""追踪已确认细缕的固定材料来源，防止宽区域再次被压成窄线；不重新挑选修复后的粒子。"""
from pathlib import Path
import sys, json, hashlib
import numpy as np
import moderngl
HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
from renderer import Renderer
from unified_model import model_fingerprint

FIXTURE = Path(__file__).with_name('filament-cohort.json')
OUT = HERE / 'analysis/recorded-filament-trace'

def main():
    fixture = json.loads(FIXTURE.read_text('utf-8'))
    ctx = moderngl.create_standalone_context(require=430)
    r = Renderer(fixture['scene'], ctx=ctx, direction=fixture['direction'], seed=fixture['seed'])
    ids = r.base[:, 3].astype('int64')
    mask = np.isin(ids, fixture['ids'])
    assert int(mask.sum()) == len(fixture['ids']), '材料减员不能作为消除细缕的修复'
    assert hashlib.sha256(r.base.tobytes()).hexdigest() == fixture['materials_sha256'], '来源、寿命或法向发生变化，需要重新审查'
    r.seek(fixture['phase'])
    state = np.frombuffer(r.state.read(), 'float32').reshape(-1, 8)
    axis = np.asarray(fixture['transverse_axis'])
    def width(points):
        lo, hi = np.percentile(points @ axis, [10, 90])
        return float(hi - lo)
    source_width = width(r.base[mask, :2])
    width_now = width(state[mask, :2])
    result = dict(model_hash=model_fingerprint(), count=int(mask.sum()),
                  source_width80=source_width, width80=width_now,
                  baseline_width80=fixture['baseline_width80'],
                  compression_ratio=width_now/source_width,
                  speed50=float(np.median(np.linalg.norm(state[mask, 4:6], axis=1))))
    r.close(); ctx.release()
    OUT.mkdir(exist_ok=True)
    (OUT/'cohort-regression.json').write_text(json.dumps(result, indent=2), 'utf-8')
    print(json.dumps(result), flush=True)
    assert width_now >= .5 * source_width, '仍把宽材料区域过度挤压到离群细缕上'

if __name__ == '__main__': main()
