"""将已核对的当前分布压力与共同旋流写入正式桌面模型；不累加重复校准。"""
from pathlib import Path
import sys,ast,json
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from probe_projected_peel import configure
from probe_edge_support import OUT
import renderer,unified_model as model
import calibrate_spatial_circulation as calibration

def main():
    marker=OUT/'pressure-promoted.json'
    if marker.exists():raise RuntimeError('本轮已写入；禁止重复累加共同场。')
    config=json.loads((OUT/'pressure-circulation-config.json').read_text('utf-8'))
    calibration.MODE='flip';flow=calibration.apply(config['coefficients'])
    source=(HERE/'analysis/gpu_spatial_pressure.py').read_text('utf-8')
    begin=source.index('SPLAT=');end=source.index('\nclass GpuPressureRenderer')
    (HERE/'pressure_grid.py').write_text('"""当前粒子分布的单侧压力约束；仅抵消剥离造成的空间汇聚。"""\nimport math\nimport numpy as np\nimport moderngl\n\n'+source[begin:end]+'\n','utf-8')
    path=HERE/'renderer.py';source=path.read_text('utf-8');lines=source.splitlines(keepends=True)
    n=next(n for n in ast.parse(source).body if isinstance(n,ast.Assign) and isinstance(n.targets[0],ast.Name) and n.targets[0].id=='COMPUTE')
    lines[n.lineno-1:n.end_lineno]=["COMPUTE=r'''"+renderer.COMPUTE+"'''\n"]
    source=''.join(lines).replace('from unified_model import RULES,','from pressure_grid import PressureGrid\nfrom unified_model import RULES,')
    source=source.replace('        self.reset()\n\n    def reset(self):','        self.reset()\n        self.pressure_grid=PressureGrid(self)\n        self.pressure_grid.update(0.)\n\n    def reset(self):')
    source=source.replace('        if target<self.step:self.reset()','        if target<self.step:\n            self.reset()\n            self.pressure_grid.reset()\n            self.pressure_grid.update(0.)\n        self.pressure_grid.texture.use(5)')
    source=source.replace('            self.ctx.memory_barrier()\n\n    def render', '''            self.ctx.memory_barrier()
            if self.step%4==0:
                self.pressure_grid.update(self.step*STEP)
                self.material.bind_to_storage_buffer(0);self.state.bind_to_storage_buffer(1)
                self.pigment.bind_to_storage_buffer(2);self.peel_compression.bind_to_storage_buffer(3)

    def render''')
    source=source.replace('    def close(self):\n        for key', '    def close(self):\n        self.pressure_grid.close()\n        for key')
    path.write_text(source,'utf-8',newline='\n')
    (model.SHARED/'common-flow.f16').write_bytes(flow.astype('<f2').tobytes())
    path=HERE/'unified_model.py';source=path.read_text('utf-8').replace("[here/'renderer.py',here/'unified_model.py'","[here/'renderer.py',here/'pressure_grid.py',here/'unified_model.py'");path.write_text(source,'utf-8',newline='\n')
    marker.write_text(json.dumps(config,indent=2),'utf-8');print('共同压力模型与旋流已写入，继续验证正式代码与 Android。',flush=True)

if __name__=='__main__':main()
