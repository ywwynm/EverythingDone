"""按投影尺寸连续细分运动片；保留模拟状态与释放时刻。"""
from surface_coherence import CoherenceRenderer


class FissionArray:
    def __init__(self,vao,program):self.vao=vao;self.program=program
    def render(self,**kw):
        if self.program['material_pass'].value==1:kw['instances']*=10
        self.vao.render(**kw)
    def release(self):self.vao.release()


class FissionRenderer(CoherenceRenderer):
    vertex_source=CoherenceRenderer.vertex_source.replace('gl_InstanceID','parent_instance')
    vertex_source=vertex_source.replace('void main(){','''
uniform float fission_strength,fission_threshold,fission_spread;
flat out float fission_weight;
void main(){
    int parent_instance=material_pass==1?gl_InstanceID/10:gl_InstanceID;
    int child=material_pass==1?gl_InstanceID%10:0;
''',1)
    vertex_source=vertex_source.replace('vec2 vertex=move+','''
    // 仅放大后的运动微片逐渐转成九份面积相等的细粒，原始片按互补比例退出。
    float projected=scale*sqrt(cell.x*cell.y)/span;
    float divided=fission_strength*smoothstep(fission_threshold,fission_threshold*1.8,projected)*smoothstep(.015,.08,age);
    fission_weight=child==0?1.-divided:divided;
    if(child>0){
        uint key=uint(parent_instance)*0x9e3779b9u+uint(child)*0x85ebca6bu;
        vec2 location=(vec2((child-1)%3,(child-1)/3)+.5)/3.-.5;
        uint scatter=key^0xa54ff53au;
        float theta=6.2831853*grid_unit(scatter);
        float radius=sqrt(-2.*log(max(grid_unit(scatter^0x68bc21ebu),.02)));
        move+=(rot*(location*cell))*scale+vec2(cos(theta),sin(theta))*radius*span*fission_spread*smoothstep(.02,.10,age);
        d/=3.;
        // 纹理使用已经输出的 uv，修改局部 source 不会更新采样位置。
        uv=(m.src.xy+(uv*card-m.src.xy)/3.+location*cell)/card;
        random_out=vec4(grid_unit(key),grid_unit(key^0x68bc21ebu),grid_unit(key^0x39b15u),grid_unit(key^0x946aeu));
    }
    vec2 vertex=move+''',1)
    fragment_source=CoherenceRenderer.fragment_source.replace('void main(){','''
flat in float fission_weight;
uniform float fission_density,fission_neutral,fission_chroma;
void main(){''',1)
    fragment_source=fragment_source.replace('if(alpha<.001)discard;','''
    alpha*=fission_weight;
    alpha=1.-pow(max(1.-alpha,.00001),exp(fission_density*smoothstep(.02,.10,age)));
    if(alpha<.001)discard;''',1)
    fragment_source=fragment_source.replace('frag=vec4(color*alpha,alpha);','''
    float fission_luma=dot(color,vec3(.2126,.7152,.0722));
    color=max(color+(vec3(fission_neutral)+fission_chroma*(color-fission_luma))*smoothstep(.02,.10,age),vec3(0.));
    frag=vec4(color*alpha,alpha);''',1)
    def __init__(self,*a,**kw):
        super().__init__(*a,**kw)
        for key,value in dict(strength=0.,threshold=.004,spread=.003,density=0.,neutral=0.,chroma=0.).items():self.program['fission_'+key]=value
        self.vao=FissionArray(self.vao,self.program)
