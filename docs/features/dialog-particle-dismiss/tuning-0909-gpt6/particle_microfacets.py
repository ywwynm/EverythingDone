"""微片细分消融：共享原运动状态，仅改变运动片的绘制分辨率，原表面不参与。"""
from particle_coverage import CoverageRenderer


class _MicroArray:
    def __init__(self,vao,program):self.vao=vao;self.program=program
    def render(self,**kwargs):
        if self.program['material_pass'].value==1:kwargs['instances']*=4
        return self.vao.render(**kwargs)
    def release(self):return self.vao.release()


class MicrofacetRenderer(CoverageRenderer):
    vertex_source=CoverageRenderer.vertex_source.replace('gl_InstanceID','micro_instance')
    vertex_source=vertex_source.replace('void main(){','''void main(){
    int micro_instance=material_pass==1?gl_InstanceID/4:gl_InstanceID;
    int micro_index=material_pass==1?gl_InstanceID%4:0;
''',1)
    vertex_source=vertex_source.replace('uv=source/card;', '''
    if(material_pass==1){
        vec2 sub=vec2(micro_index%2,micro_index/2)-.5;
        source=m.src.xy+(source-m.src.xy)*.5+sub*cell*.5;
    }
    uv=source/card;''',1)
    vertex_source=vertex_source.replace('float loosen=smoothstep(.018,.18,age);', '''
    if(material_pass==1){
        uint micro_key=uint(micro_instance)*0x9e3779b9u+uint(micro_index)*0x85ebca6bu;
        vec4 micro_random=vec4(grid_unit(micro_key),grid_unit(micro_key^0x68bc21ebu),grid_unit(micro_key^0x39b15u),grid_unit(micro_key^0x946aeu));
        m.random=micro_random;random_out=micro_random;
    }
    float loosen=smoothstep(.018,.18,age);''',1)

    def __init__(self,*args,**kwargs):
        super().__init__(*args,**kwargs);self.vao=_MicroArray(self.vao,self.program)
