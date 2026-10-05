"""固定输运与表面交接，单独检验运动材料的分层采样。仅作桌面消融。"""
from surface_coherence import CoherenceRenderer


class SamplingArray:
    def __init__(self,vao,program):
        self.vao=vao;self.program=program

    def render(self,**kwargs):
        if self.program['material_pass'].value==1:
            n=self.program['sampling_side'].value
            kwargs['instances']*=n*n
        self.vao.render(**kwargs)

    def release(self):self.vao.release()


class SamplingRenderer(CoherenceRenderer):
    vertex_source=CoherenceRenderer.vertex_source.replace('gl_InstanceID','parent_instance')
    vertex_source=vertex_source.replace('void main(){','''
uniform int sampling_side;
uniform float sampling_independence,sampling_spread;
void main(){
    int side=material_pass==1?sampling_side:1;
    int parent_instance=gl_InstanceID/(side*side);
    int child=gl_InstanceID%(side*side);
''',1)
    vertex_source=vertex_source.replace('vec2 source=(vertex_grid+j)*cell;','''
    vec2 source=(vertex_grid+j)*cell;
    if(side>1){
        vec2 child_center=(vec2(child%side,child/side)+.5)/float(side)-.5;
        source=m.src.xy+(source-m.src.xy)/float(side)+child_center*cell;
    }
''',1)
    vertex_source=vertex_source.replace('float loosen=smoothstep(.018,.18,age);','''
    if(side>1){
        uint key=uint(parent_instance)*0x9e3779b9u+uint(child)*0x85ebca6bu;
        vec4 child_random=vec4(grid_unit(key),grid_unit(key^0x68bc21ebu),grid_unit(key^0x39b15u),grid_unit(key^0x946aeu));
        m.random=mix(m.random,child_random,sampling_independence);
        random_out=m.random;
    }
    float loosen=smoothstep(.018,.18,age);
''',1)
    vertex_source=vertex_source.replace('vec2 vertex=move+','''
    if(side>1){
        uint scatter_key=uint(parent_instance)*0x4a39b70du+uint(child)*0x12fad5c9u;
        float theta=6.2831853*grid_unit(scatter_key);
        float radius=sqrt(-2.*log(max(grid_unit(scatter_key^0x68bc21ebu),.01)));
        move+=vec2(cos(theta),sin(theta))*radius*span*sampling_spread*smoothstep(.01,.10,age);
    }
    vec2 vertex=move+''',1)

    def __init__(self,*args,sampling=1,independence=0.,**kwargs):
        super().__init__(*args,**kwargs)
        self.program['sampling_side']=sampling
        self.program['sampling_independence']=independence
        self.program['sampling_spread']=0.
        self.vao=SamplingArray(self.vao,self.program)

