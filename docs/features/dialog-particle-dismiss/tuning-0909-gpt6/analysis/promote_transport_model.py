"""将已审阅的共同表示接入正式渲染；记录来源，不重新拟合。"""
from pathlib import Path
import shutil
HERE=Path(__file__).resolve().parents[1]
ROOT=next(p for p in HERE.parents if (p/'gradlew.bat').is_file())
for name in ['common-release.f32','common-flow.f16']:
    shutil.copy2(HERE/'analysis/transport-model/shared'/name,ROOT/'shared/particle-dismiss'/name)
# 以明确的旧段标记替换，避免重复执行时叠加运动。
path=HERE/'renderer.py';s=path.read_text('utf-8')
begin=s.index('    // 源位置决定');end=s.index('    float response=',begin)
s=s[:begin]+'''    // 共同速度表示与释放在同一坐标系中演化；不额外把整片推出或吸向周期线。
    target=guide*(guide_gain/.9)+flow*.02+wind*u*.00001;
    float random_angle=m.random.w*6.28318;
    float radius=sqrt(-2.*log(max(m.random.z,.015)));
    float separate=smoothstep(.008,.045,age)*(1.-smoothstep(.16,.32,age));
    target+=vec2(cos(random_angle),sin(random_angle))*radius*span*.045*separate;
    float depth_target=-state[i].pos.z*3.*roll_gain;
    // 允许侧向卷动；同一颗粒不沿消逝主方向反弹。
    target+=wind*max(0.-dot(target,wind),0.);
'''+s[end:]
s=s.replace(')*span;\n    float entrained',')*card;\n    float entrained')
s=s.replace('from unified_model import RULES, materials, guidance','from unified_model import RULES, materials, guidance, field_rotation')
s=s.replace('uniform int nx,grid_count;','uniform int nx,grid_count;\nuniform float panel_weight;')
s=s.replace('float content=pigmentation[gl_InstanceID];','float content=pigmentation[gl_InstanceID];\n    // 面板色微片更细，实际内容仍保留份额，静态纹理覆盖不变。\n    scale*=1.-.26*panel_weight*(1.-content)*smoothstep(.006,.040,age);')
s=s.replace('self.ctx.texture3d((36,36,32),2,flow_data.tobytes()', 'self.ctx.texture3d(tuple(flow_data.shape[2::-1]),2,flow_data.tobytes()')
s=s.replace('delta=math.radians(self.direction-guide_direction)','delta=field_rotation(self.direction,self.cw,self.ch)')
s=s.replace("self.program['body_weight']=self.body_weight","self.program['body_weight']=self.body_weight\n        self.program['panel_weight']=self.material_info['panel_weight']")
path.write_text(s,'utf-8',newline='\n')
print('正式模型已接入同一释放与输运表示。')
