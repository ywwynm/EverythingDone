"""会话画面的 GPU 图层：贴图精灵、罩层（带空洞）、边框底下那圈光、边框粒子、扩散圈、光点、卡片投影。

一律按手机的做法合成：sRGB 值直接混合（ONE, ONE_MINUS_SRC_ALPHA，预乘），与 Canvas / SurfaceFlinger 相同。
坐标一律是设备像素（DecorView 坐标），输出分辨率只影响出图大小。各个过程化图形与 Kotlin 端的
位图精灵逐项对应（`TapShareCardStage` 的 GLOW_SPRITE / RING_SPRITE、`ParticleSpriteBatch` 的 SPRITE）。
"""
from __future__ import annotations

import math

import moderngl
import numpy as np
from scipy.ndimage import gaussian_filter

SCREEN_VS = r'''#version 430
uniform vec2 screen;
uniform mat3 xform;      // 本地像素 → 屏幕像素
uniform vec2 size;       // 本地四边形的尺寸（像素）
uniform vec4 src;        // 贴图坐标的范围 u0 v0 u1 v1
out vec2 uv;
out vec2 local;
const vec2 c[4]=vec2[4](vec2(0,0),vec2(1,0),vec2(0,1),vec2(1,1));
void main(){
    vec2 q=c[gl_VertexID];
    local=q*size;
    vec3 p=xform*vec3(local,1.);
    uv=mix(src.xy,src.zw,q);
    gl_Position=vec4(p.x/screen.x*2.-1.,1.-p.y/screen.y*2.,0,1);
}
'''

SPRITE_FS = r'''#version 430
uniform sampler2D tex;
uniform float alpha;
in vec2 uv;
out vec4 frag;
// 截图是直通 alpha 的 PNG，按预乘叠上去
void main(){vec4 c=texture(tex,uv);frag=vec4(c.rgb*c.a,c.a)*alpha;}
'''

SOLID_FS = r'''#version 430
uniform vec4 color;   // 预乘
out vec4 frag;
void main(){frag=color;}
'''

FULL_VS = r'''#version 430
uniform vec2 screen;
out vec2 p;
const vec2 v[3]=vec2[3](vec2(0,0),vec2(2,0),vec2(0,2));
void main(){vec2 q=v[gl_VertexID];p=q*screen;gl_Position=vec4(q.x*2.-1.,1.-q.y*2.,0,1);}
'''

ROUND = r'''
float sd_round(vec2 p,vec4 r,float rad){
    vec2 c=(r.xy+r.zw)*.5;vec2 h=(r.zw-r.xy)*.5;
    rad=min(rad,min(h.x,h.y));
    vec2 q=abs(p-c)-h+rad;
    return length(max(q,0.))+min(max(q.x,q.y),0.)-rad;
}
'''

DIM_FS = r'''#version 430
uniform vec3 color;
uniform float alpha,hole_alpha,radius;
uniform int holes;
uniform vec4 rects[8];
in vec2 p;
out vec4 frag;
''' + ROUND + r'''
// TapReceiveStageView.drawDim：整屏减去圆角空洞（EVEN_ODD，不抗锯齿），空洞合上时按 1 - holeOpen 补回
void main(){
    float a=alpha;
    for(int k=0;k<holes;k++) if(sd_round(p,rects[k],radius)<0.) a=hole_alpha;
    frag=vec4(color*a,a);
}
'''

EDGE_GLOW_FS = r'''#version 430
uniform vec2 screen;
uniform vec3 palette[12];
uniform int count,rings;
uniform float rotation,ring,corner;
uniform float ring_alpha[64];
in vec2 p;
out vec4 frag;
''' + ROUND + r'''
// TapReceiveStageView.drawEdgeGlow：rings 圈描边，每圈宽 ring，由外向内按平方衰减；
// 颜色是绕屏幕中心的 SweepGradient（调色板首尾相接），随时间转动。
void main(){
    vec2 c=screen*.5;
    float turn=atan(p.y-c.y,p.x-c.x)/6.2831853;
    if(turn<0.)turn+=1.;
    float pos=fract(turn-rotation);
    float f=pos*float(count);
    int k=min(int(f),count-1);
    vec3 col=mix(palette[k],palette[(k+1)%count],f-float(k));
    float acc=0.;
    for(int i=0;i<rings;i++){
        float inset=ring*(float(i)+.5);
        float r=max(corner-inset,0.);
        float sd=sd_round(p,vec4(inset,inset,screen.x-inset,screen.y-inset),r);
        float cov=clamp(ring*.5-abs(sd)+.5,0.,1.);
        float a=ring_alpha[i]*cov;
        acc=acc+a*(1.-acc);
    }
    frag=vec4(col*acc,acc);
}
'''

BAND_GLOW_FS = r'''#version 430
uniform vec2 screen;
uniform vec3 palette[12];
uniform int count;
uniform float rotation,corner,center_inset,sigma,peak,rounding;
in vec2 p;
out vec4 frag;
''' + ROUND + r'''
// 光尘带底下的柔光：以带的中线为轴的高斯晕，随带一起向内收拢；颜色是绕屏幕中心转动的调色板
// 圆角与光尘带同一规则：max(corner − 内缩, 0) + rounding × 内缩
void main(){
    vec2 c=screen*.5;
    float turn=atan(p.y-c.y,p.x-c.x)/6.2831853;
    if(turn<0.)turn+=1.;
    float pos=fract(turn-rotation);
    float f=pos*float(count);
    int k=min(int(f),count-1);
    vec3 col=mix(palette[k],palette[(k+1)%count],f-float(k));
    float r=max(corner-center_inset,0.)+rounding*center_inset;
    float sd=sd_round(p,vec4(center_inset,center_inset,screen.x-center_inset,screen.y-center_inset),r);
    float a=peak*exp(-.5*sd*sd/(sigma*sigma));
    frag=vec4(col*a,a);
}
'''

PARTICLE_VS = r'''#version 430
in vec2 head;
in vec2 tail;
in float radius;
in vec4 color;
uniform vec2 screen;
out vec2 uv;
out vec4 vcolor;
// ParticleSpriteBatch.add：沿运动方向拉长的四边形，尾左、头左、头右、尾右；尾部透明度 × 0.12
void main(){
    float half_=radius/.42;
    vec2 a=head-tail;float len=length(a);
    vec2 dir=len>1e-3?a/len:vec2(1,0);
    vec2 n=vec2(-dir.y,dir.x)*half_,e=dir*half_;
    int id=gl_VertexID;   // 三角带顺序：0 尾左，1 头左，2 尾右，3 头右
    vec2 pos;vec2 t;bool isTail;
    if(id==0){pos=tail-e+n;t=vec2(0,0);isTail=true;}
    else if(id==1){pos=head+e+n;t=vec2(1,0);isTail=false;}
    else if(id==2){pos=tail-e-n;t=vec2(0,1);isTail=true;}
    else {pos=head+e-n;t=vec2(1,1);isTail=false;}
    uv=t;
    float alpha=color.a;
    if(isTail)alpha=floor(alpha*255.*.12+.5)/255.;
    vcolor=vec4(color.rgb,alpha);
    gl_Position=vec4(pos.x/screen.x*2.-1.,1.-pos.y/screen.y*2.,0,1);
}
'''

PARTICLE_FS = r'''#version 430
in vec2 uv;
in vec4 vcolor;
out vec4 frag;
// ParticleSpriteBatch.SPRITE：64 像素的白色圆点，实心核 + 淡光晕；与顶点色相乘
void main(){
    vec2 d=(uv*64.-32.)/31.5;
    float r=length(d);
    float core=1.-smoothstep(.37,.46,r);
    float halo=.16*(1.-smoothstep(.3,.95,r));
    float s=floor(max(core,halo)*255.+.5)/255.;
    float a=vcolor.a*s;
    frag=vec4(vcolor.rgb*a,a);
}
'''

RING_FS = r'''#version 430
uniform vec2 center;
uniform float radius,alpha;
uniform vec3 tint;
in vec2 p;
out vec4 frag;
// TapShareCardStage.RING_SPRITE：256 像素的细环，环心半径 124.5、半宽 2.2（精灵像素）；发送画白色，
// 接收在浅色罩层上画中性深灰
void main(){
    float scale=256./(2.*radius);
    vec2 s=(p-(center-radius))*scale;
    float d=abs(length(s-127.5)-124.5);
    float a=floor((1.-clamp(d-2.2+1.,0.,1.))*255.+.5)/255.*alpha;
    frag=vec4(tint*a,a);
}
'''

GLOW_FS = r'''#version 430
uniform vec2 center;
uniform float radius,alpha;
in vec2 p;
out vec4 frag;
// TapShareCardStage.GLOW_SPRITE：白色，透明度按高斯衰减，边缘减去常数项归零
void main(){
    float r=min(length(p-center)/radius,1.);
    float floor_=exp(-5.);
    float f=clamp((exp(-5.*r*r)-floor_)/(1.-floor_),0.,1.);
    float a=floor(f*255.+.5)/255.*alpha;
    frag=vec4(a,a,a,a);
}
'''


FLAKE_VS = r'''#version 430
// 光尘的一片：与弹窗微片同一形状——小方片，按转角旋转、按翻转压缩宽度，片元里切成圆角
in vec2 center;
in float size;
in float angle;
in float squash;
in vec3 color;
in float alpha;
in float glint;
uniform vec2 screen;
out vec2 local;
out vec3 v_color;
out float v_alpha;
out float v_glint;
const vec2 corners[4]=vec2[4](vec2(-.5,-.5),vec2(.5,-.5),vec2(-.5,.5),vec2(.5,.5));
void main(){
    vec2 c=corners[gl_VertexID];
    vec2 q=vec2(c.x*squash,c.y)*size;
    float ca=cos(angle),sa=sin(angle);
    vec2 p=center+vec2(ca*q.x-sa*q.y,sa*q.x+ca*q.y);
    gl_Position=vec4(p.x/screen.x*2.-1.,1.-p.y/screen.y*2.,0,1);
    local=c+.5;v_color=color;v_alpha=alpha;v_glint=glint;
}
'''

FLAKE_FS = r'''#version 430
in vec2 local;
in vec3 v_color;
in float v_alpha;
in float v_glint;
out vec4 frag;
// material.frag 的老化微片：radial 截断成圆角，正对时出现白色闪光（弹窗是 0.18 × facing^12）
void main(){
    vec2 q=local-.5;
    float radial=length(q*vec2(.92,.90));
    float cut=1.-smoothstep(.35,.58,radial);
    float a=v_alpha*cut;
    if(a<.002)discard;
    vec3 col=min(v_color*(1.+.30*v_glint)+vec3(.20*v_glint),vec3(1.));
    frag=vec4(col*a,a);
}
'''


def affine(tx=0.0, ty=0.0, sx=1.0, sy=1.0, rot_deg=0.0, pivot=(0.0, 0.0)):
    """本地 → 屏幕：平移 · 旋转 · 缩放，绕 [pivot]（本地坐标）进行。返回 3×3。"""
    a = math.radians(rot_deg)
    c, s = math.cos(a), math.sin(a)
    px, py = pivot
    m = np.array([[c * sx, -s * sy, 0], [s * sx, c * sy, 0], [0, 0, 1]], dtype=np.float64)
    pv = np.array([[1, 0, -px], [0, 1, -py], [0, 0, 1]], dtype=np.float64)
    back = np.array([[1, 0, px + tx], [0, 1, py + ty], [0, 0, 1]], dtype=np.float64)
    return back @ m @ pv


class Layers:
    def __init__(self, ctx: moderngl.Context, screen: tuple[int, int], out_size: tuple[int, int]):
        self.ctx = ctx
        self.screen = (float(screen[0]), float(screen[1]))
        self.out_w, self.out_h = out_size
        self.sprite = ctx.program(vertex_shader=SCREEN_VS, fragment_shader=SPRITE_FS)
        self.solid = ctx.program(vertex_shader=SCREEN_VS, fragment_shader=SOLID_FS)
        self.dim_p = ctx.program(vertex_shader=FULL_VS, fragment_shader=DIM_FS)
        self.edge_p = ctx.program(vertex_shader=FULL_VS, fragment_shader=EDGE_GLOW_FS)
        self.ring_p = ctx.program(vertex_shader=FULL_VS, fragment_shader=RING_FS)
        self.glow_p = ctx.program(vertex_shader=FULL_VS, fragment_shader=GLOW_FS)
        self.part_p = ctx.program(vertex_shader=PARTICLE_VS, fragment_shader=PARTICLE_FS)
        self.flake_p = ctx.program(vertex_shader=FLAKE_VS, fragment_shader=FLAKE_FS)
        self.flake_p['screen'] = self.screen
        self.band_p = ctx.program(vertex_shader=FULL_VS, fragment_shader=BAND_GLOW_FS)
        self.band_p['screen'] = self.screen
        self.flake_capacity = 0
        self.flake_buf = None
        self.flake_vao = None
        self.vao = {name: ctx.vertex_array(prog, []) for name, prog in
                    [('sprite', self.sprite), ('solid', self.solid), ('dim', self.dim_p), ('edge', self.edge_p),
                     ('ring', self.ring_p), ('glow', self.glow_p), ('band', self.band_p)]}
        for prog in (self.sprite, self.solid, self.dim_p, self.edge_p, self.ring_p, self.glow_p, self.part_p):
            if 'screen' in prog:
                prog['screen'] = self.screen
        self.part_capacity = 0
        self.part_buf = None
        self.part_vao = None
        self.textures: dict = {}

    # ---------------------------------------------------------------- 资源
    def texture(self, key, rgba: np.ndarray):
        tex = self.textures.get(key)
        if tex is None:
            h, w = rgba.shape[:2]
            tex = self.ctx.texture((w, h), 4, np.ascontiguousarray(rgba).tobytes())
            tex.filter = (moderngl.LINEAR, moderngl.LINEAR)
            tex.repeat_x = tex.repeat_y = False
            self.textures[key] = tex
        return tex

    def release(self):
        for tex in self.textures.values():
            tex.release()
        self.textures.clear()
        for vao in self.vao.values():
            vao.release()
        for prog in (self.sprite, self.solid, self.dim_p, self.edge_p, self.ring_p, self.glow_p, self.part_p):
            prog.release()
        if self.part_buf is not None:
            self.part_vao.release()
            self.part_buf.release()
        if self.flake_buf is not None:
            self.flake_vao.release()
            self.flake_buf.release()
        self.flake_p.release()
        self.band_p.release()

    # ---------------------------------------------------------------- 绘制
    def band_glow(self, palette: list[int], rotation: float, corner: float, center_inset: float, sigma: float,
                  peak: float, rounding: float = 0.0):
        """光尘带底下的柔光（以带中线为轴的高斯晕）。"""
        if peak <= 0.002:
            return
        self.band_p['rounding'] = float(rounding)
        self._blend()
        cols = [(((c >> 16) & 0xFF) / 255.0, ((c >> 8) & 0xFF) / 255.0, (c & 0xFF) / 255.0) for c in palette][:12]
        count = len(cols)
        cols += [(0.0, 0.0, 0.0)] * (12 - count)
        self.band_p['palette'].value = cols
        self.band_p['count'] = count
        self.band_p['rotation'] = float(rotation)
        self.band_p['corner'] = float(corner)
        self.band_p['center_inset'] = float(center_inset)
        self.band_p['sigma'] = float(max(sigma, 1.0))
        self.band_p['peak'] = float(peak)
        self.vao['band'].render(mode=moderngl.TRIANGLES, vertices=3)

    def flakes(self, frame, offset=(0.0, 0.0)):
        """光尘（sim_dust.DustFrame），[offset] 是舞台在屏幕上的左上角。"""
        if frame is None:
            return
        n = len(frame.x)
        if n == 0:
            return
        if n > self.flake_capacity:
            if self.flake_buf is not None:
                self.flake_vao.release()
                self.flake_buf.release()
            self.flake_capacity = max(n, 8192)
            self.flake_buf = self.ctx.buffer(reserve=self.flake_capacity * 11 * 4)
            self.flake_vao = self.ctx.vertex_array(self.flake_p, [(self.flake_buf, '2f 1f 1f 1f 3f 1f 1f/i', 'center',
                                                                   'size', 'angle', 'squash', 'color', 'alpha',
                                                                   'glint')])
        data = np.concatenate([np.stack([frame.x + offset[0], frame.y + offset[1], frame.size, frame.angle,
                                         frame.squash], axis=1), frame.rgb,
                               np.stack([frame.alpha, frame.glint], axis=1)], axis=1).astype('float32')
        self.flake_buf.write(data.tobytes())
        self._blend()
        self.flake_vao.render(mode=moderngl.TRIANGLE_STRIP, vertices=4, instances=n)
    def _blend(self):
        self.ctx.enable(moderngl.BLEND)
        self.ctx.blend_func = (moderngl.ONE, moderngl.ONE_MINUS_SRC_ALPHA)

    def draw_texture(self, tex, size, xform, alpha=1.0, src=(0.0, 0.0, 1.0, 1.0)):
        """把贴图的 [src] 部分画成本地尺寸 [size] 的四边形，经 [xform] 放到屏幕上。"""
        if alpha <= 0.001:
            return
        self._blend()
        tex.use(0)
        self.sprite['tex'] = 0
        self.sprite['alpha'] = float(alpha)
        self.sprite['size'] = (float(size[0]), float(size[1]))
        self.sprite['src'] = tuple(float(v) for v in src)
        self.sprite['xform'] = tuple(float(v) for v in xform.T.ravel())
        self.vao['sprite'].render(mode=moderngl.TRIANGLE_STRIP, vertices=4)

    def draw_image_region(self, tex, img_size, region, dst_xy, alpha=1.0):
        """截图的一块矩形区域 region=(x0,y0,x1,y1)（图像像素）画到屏幕 dst_xy 处，不缩放。"""
        w, h = img_size
        x0, y0, x1, y1 = region
        if x1 <= x0 or y1 <= y0:
            return
        src = (x0 / w, y0 / h, x1 / w, y1 / h)
        self.draw_texture(tex, (x1 - x0, y1 - y0), affine(dst_xy[0], dst_xy[1]), alpha, src)

    def fill(self, rect, rgb, alpha=1.0):
        x0, y0, x1, y1 = rect
        if x1 <= x0 or y1 <= y0 or alpha <= 0.0:
            return
        self._blend()
        self.solid['color'] = (rgb[0] * alpha, rgb[1] * alpha, rgb[2] * alpha, alpha)
        self.solid['size'] = (float(x1 - x0), float(y1 - y0))
        self.solid['xform'] = tuple(float(v) for v in affine(x0, y0).T.ravel())
        self.vao['solid'].render(mode=moderngl.TRIANGLE_STRIP, vertices=4)

    def dim(self, argb: int, alpha: float, holes=(), hole_open=0.0, radius=0.0):
        a = int(alpha * 255) / 255.0
        if a <= 0:
            return
        self._blend()
        rgb = [((argb >> s) & 0xFF) / 255.0 for s in (16, 8, 0)]
        self.dim_p['color'] = tuple(rgb)
        self.dim_p['alpha'] = a
        self.dim_p['hole_alpha'] = int(a * 255 * (1 - hole_open)) / 255.0
        self.dim_p['radius'] = float(radius)
        rects = list(holes)[:8]
        self.dim_p['holes'] = len(rects)
        if rects:
            data = [tuple(float(v) for v in r) for r in rects] + [(0.0, 0.0, 0.0, 0.0)] * (8 - len(rects))
            self.dim_p['rects'].value = data
        self.vao['dim'].render(mode=moderngl.TRIANGLES, vertices=3)

    def edge_glow(self, palette: list[int], rotation: float, ring: float, rings: int, glow_alpha: float,
                  border_alpha: float, corner: float):
        if border_alpha <= 0.01 or rings <= 0:
            return
        self._blend()
        cols = [(((c >> 16) & 0xFF) / 255.0, ((c >> 8) & 0xFF) / 255.0, (c & 0xFF) / 255.0) for c in palette][:12]
        count = len(cols)
        cols += [(0.0, 0.0, 0.0)] * (12 - count)
        self.edge_p['palette'].value = cols
        self.edge_p['count'] = count
        self.edge_p['rings'] = min(rings, 64)
        self.edge_p['rotation'] = float(rotation)
        self.edge_p['ring'] = float(ring)
        self.edge_p['corner'] = float(corner)
        alphas = []
        for k in range(min(rings, 64)):
            falloff = 1 - k / rings
            alphas.append(int(glow_alpha * falloff * falloff * border_alpha * 255) / 255.0)
        alphas += [0.0] * (64 - len(alphas))
        self.edge_p['ring_alpha'].value = alphas
        self.vao['edge'].render(mode=moderngl.TRIANGLES, vertices=3)

    def particles(self, head, tail, radius, rgba):
        n = len(head)
        if n == 0:
            return
        if n > self.part_capacity:
            if self.part_buf is not None:
                self.part_vao.release()
                self.part_buf.release()
            self.part_capacity = max(n, 4096)
            self.part_buf = self.ctx.buffer(reserve=self.part_capacity * 9 * 4)
            self.part_vao = self.ctx.vertex_array(self.part_p, [(self.part_buf, '2f 2f 1f 4f/i', 'head', 'tail',
                                                                  'radius', 'color')])
        data = np.concatenate([head, tail, radius[:, None], rgba], axis=1).astype('float32')
        self.part_buf.write(data.tobytes())
        self._blend()
        self.part_vao.render(mode=moderngl.TRIANGLE_STRIP, vertices=4, instances=n)

    def ring(self, center, radius, alpha, tint=(1.0, 1.0, 1.0)):
        if alpha <= 0.001 or radius <= 0:
            return
        self._blend()
        self.ring_p['tint'] = tuple(float(v) for v in tint)
        self.ring_p['center'] = tuple(float(v) for v in center)
        self.ring_p['radius'] = float(radius)
        self.ring_p['alpha'] = int(min(max(alpha, 0.0), 1.0) * 255) / 255.0
        self.vao['ring'].render(mode=moderngl.TRIANGLES, vertices=3)

    def glow(self, center, radius, alpha):
        if alpha <= 0.001 or radius <= 0:
            return
        self._blend()
        self.glow_p['center'] = tuple(float(v) for v in center)
        self.glow_p['radius'] = float(radius)
        self.glow_p['alpha'] = int(min(max(alpha, 0.0), 1.0) * 255) / 255.0
        self.vao['glow'].render(mode=moderngl.TRIANGLES, vertices=3)


def card_shadow(card_rgba: np.ndarray, elevation_px: float, card_cy: float, light_z_px: float,
                light_radius_px: float, ambient_alpha=.039, spot_alpha=.19, pad=None):
    """卡片的高度投影（近似 Android 的环境光 + 点光源两层），返回 (RGBA 贴图, 贴图相对卡片左上角的偏移)。

    点光源在屏幕顶部正中、高度 lightZ：投影下移 ≈ 高度 × 卡片中心 y / lightZ，半影随光源半径变宽。
    """
    h, w = card_rgba.shape[:2]
    mask = card_rgba[:, :, 3].astype(np.float32) / 255.0
    spot_dy = elevation_px * card_cy / max(light_z_px - elevation_px, 1.0)
    spot_sigma = max(elevation_px * light_radius_px / light_z_px * 0.5, 0.5)
    amb_sigma = max(elevation_px * 0.5, 0.5)
    if pad is None:
        pad = int(math.ceil(max(spot_sigma, amb_sigma) * 3 + abs(spot_dy))) + 2
    big = np.zeros((h + 2 * pad, w + 2 * pad), dtype=np.float32)
    big[pad:pad + h, pad:pad + w] = mask
    ambient = gaussian_filter(big, amb_sigma) * ambient_alpha
    spot = gaussian_filter(big, spot_sigma) * spot_alpha
    shift = int(round(spot_dy))
    spot = np.roll(spot, shift, axis=0)
    if shift > 0:
        spot[:shift] = 0
    a = np.clip(1 - (1 - ambient) * (1 - spot), 0, 1)
    rgba = np.zeros(big.shape + (4,), dtype=np.uint8)
    rgba[:, :, 3] = np.clip(a * 255 + .5, 0, 255).astype(np.uint8)
    return rgba, (-pad, -pad)
