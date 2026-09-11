"""本轮候选：局部解除速度，以及保持共同卷动的附加顺风输运。"""
def frozen_shader():
    import ast
    from pathlib import Path
    path=Path(__file__).resolve().parents[1]/'archive/before-rim-flow/renderer.py'
    return next(ast.literal_eval(n.value) for n in ast.parse(path.read_text('utf-8')).body
                if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='COMPUTE' for t in n.targets))

def candidate(shader,peel_gain=1.5,near=.0,far=.95):
    shader=shader.replace('void main(){','''// 粒子出生后平滑加速的附加位移；其导数同时写入实际状态速度。
float travel_offset(float age){return age-.035*(1.-exp(-age/.035));}
float travel_speed(float age){return 1.-exp(-age/.035);}
void main(){''',1)
    shader=shader.replace('vec2 p=state[i].pos.xy;',f'''float previous_age=max(age-h,0.);
    float transport_gain={near}+({far}-{near})*touch_strength;
    vec2 carrier=wind*span*transport_gain;
    vec2 p=state[i].pos.xy-carrier*travel_offset(previous_age);''')
    shader=shader.replace('float gain=.72+.56*touch_strength;', 'float gain=1.;')
    shader=shader.replace('float depth_target=',f'''// 解除边界的局部法向决定初始展开，随后阻力将微片交给共同流场。
    vec2 peel=-m.physical.xy;
    peel-=wind*min(dot(peel,wind),0.);
    target+=peel*span*{peel_gain}*exp(-age/.16)*smoothstep(.003,.028,age);
    float depth_target=''')
    shader=shader.replace('vec2 old_v=state[i].vel.xy;','vec2 old_v=state[i].vel.xy-carrier*travel_speed(previous_age);')
    shader=shader.replace('state[i].pos.xy+=v*h;\n    state[i].vel.xy=v;', '''state[i].pos.xy=p+v*h+carrier*travel_offset(age);
    state[i].vel.xy=v+carrier*travel_speed(age);''')
    return shader

def material_frame(shader,peel_gain=1.5):
    shader=shader.replace('vec2 p=state[i].pos.xy;', '''float distance_gain=.55+.90*touch_strength;
    vec2 p=m.src.xy+(state[i].pos.xy-m.src.xy)/distance_gain;''')
    shader=shader.replace('float gain=.72+.56*touch_strength;', 'float gain=1.;')
    shader=shader.replace('float depth_target=',f'''vec2 peel=-m.physical.xy;
    peel-=wind*min(dot(peel,wind),0.);
    target+=peel*span*{peel_gain}*exp(-age/.16)*smoothstep(.003,.028,age);
    float depth_target=''')
    shader=shader.replace('vec2 old_v=state[i].vel.xy;','vec2 old_v=state[i].vel.xy/distance_gain;')
    shader=shader.replace('state[i].pos.xy+=v*h;\n    state[i].vel.xy=v;', '''state[i].pos.xy=m.src.xy+(p+v*h-m.src.xy)*distance_gain;
    state[i].vel.xy=v*distance_gain;''')
    return shader
