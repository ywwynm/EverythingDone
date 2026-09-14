"""独立 GPU 回归：原始批量压力核与优化核逐位对照，不改变迭代或输入。"""
import argparse
import json
from pathlib import Path
import moderngl
import numpy as np

p = argparse.ArgumentParser()
p.add_argument('--reference', required=True)
p.add_argument('--candidate', default='app/src/main/assets/particle-playback/pressure-batched.comp')
p.add_argument('--output', required=True)
a = p.parse_args()
ctx = moderngl.create_standalone_context(require=430)
programs = [ctx.compute_shader(Path(path).read_text(encoding='utf-8').replace('#version 310 es', '#version 430'))
            for path in (a.reference, a.candidate)]
rng = np.random.default_rng(9018)
rows = []
for width, height in [(17, 19), (63, 65), (288, 288), (289, 337), (448, 288)]:
    tiles = np.array([(x, y) for y in range((height+15)//16) for x in range((width+15)//16)], dtype='i4')
    work = ctx.buffer(np.array([len(tiles), 1, 1, 0], dtype='u4').tobytes()+tiles.tobytes())
    work.bind_to_storage_buffer(7)
    for occupancy in [0., .08, 1.]:
        velocity = rng.normal(size=(height, width, 4)).astype('f4')
        velocity[:, :, 2] = (rng.random((height, width)) < occupancy).astype('f4')
        pressure = rng.random((height, width), dtype='f4')
        src = ctx.buffer(velocity.tobytes()); src.bind_to_storage_buffer(0)
        old = ctx.buffer(pressure.tobytes()); old.bind_to_storage_buffer(1)
        for first, iterations in [(0, 1), (0, 8), (8, 8), (96, 4)]:
            outputs = []
            for program in programs:
                dest = ctx.buffer(np.full((height, width), -1., dtype='f4').tobytes())
                dest.bind_to_storage_buffer(2)
                program['grid_shape'] = width, height
                program['first_iteration'] = first
                program['iterations'] = iterations
                program.run(len(tiles), 1, 1)
                ctx.memory_barrier(); ctx.finish()
                outputs.append(np.frombuffer(dest.read(), dtype='f4').copy())
                dest.release()
            count = int(np.count_nonzero(outputs[0].view('u4') != outputs[1].view('u4')))
            rows.append(dict(shape=[width, height], occupancy=occupancy, first=first,
                             iterations=iterations, different=count,
                             maxAbs=float(np.max(np.abs(outputs[0]-outputs[1])))))
        src.release(); old.release()
    work.release()
report = dict(renderer=ctx.info['GL_RENDERER'], cases=len(rows),
              passed=all(row['different'] == 0 for row in rows), results=rows)
Path(a.output).write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps({k: v for k, v in report.items() if k != 'results'}))
assert report['passed'], [row for row in rows if row['different']]
