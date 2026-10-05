"""核心复刻的状态校验；参考视频不参与，覆盖哈希、回退重播及寿命状态。"""
from pathlib import Path
import sys
import json
import numpy as np
import moderngl

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from harmony_core import HarmonyCoreRenderer, RANDOM, index_hash, mask_random


def check():
    ctx = moderngl.create_standalone_context(require=430)
    buffer = ctx.buffer(reserve=4096*4)
    shader = ctx.compute_shader('#version 430\n'+RANDOM+'''
layout(local_size_x=256) in;
layout(std430,binding=0) buffer Out{uint data[];};
void main(){uint i=gl_GlobalInvocationID.x;data[i]=hashIndex(i);}
''')
    buffer.bind_to_storage_buffer(0)
    shader.run(group_x=16)
    ctx.memory_barrier()
    assert np.array_equal(np.frombuffer(buffer.read(),np.uint32),index_hash(np.arange(4096)))
    # MT19937 的已知第一个标准输出。
    assert mask_random(5489) == float(np.float32(3499211612)*np.float32(2**-32))
    buffer.release();shader.release()
    r = HarmonyCoreRenderer('ironman',ctx=ctx)
    start = r.render(0)
    middle = r.render(.5)
    state = r.state.read()
    assert r.diagnostics()['finite']
    assert r.diagnostics()['active_count']>0
    r.render(.85)
    assert np.array_equal(middle,r.render(.5)), '倒退 seek 的像素不一致'
    assert state == r.state.read(), '倒退 seek 的粒子状态不一致'
    r.reset()
    for p in np.linspace(0,.5,31):
        played = r.render(p)
    assert np.array_equal(middle,played), '连续播放与直接 seek 不一致'
    assert np.array_equal(start,r.render(0)), '重置没有恢复首帧'
    end = r.render(1)
    from PIL import Image
    bg = np.asarray(Image.open(ROOT/'assets/ironman/background.png').convert('RGB'))
    assert np.array_equal(end,bg), '强制结束后未完整恢复背景'
    # seed 只影响调用方随机量；不同种子必须能观察到不同释放画面。
    r2 = HarmonyCoreRenderer('ironman',ctx=ctx,seed=129)
    other = r2.render(.5)
    assert np.mean(np.abs(other.astype(float)-middle))>1
    result = dict(renderer=ctx.info['GL_RENDERER'],hash_samples=4096,seek_replay_equal=True,
                  reset_equal=True,end_background_equal=True,seed_changes_output=True,
                  first_visible=r.diagnostics())
    r2.close();r.close();ctx.release()
    return result


if __name__ == '__main__':
    print(json.dumps(check(),ensure_ascii=False,indent=2))
