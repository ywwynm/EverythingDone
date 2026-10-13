#version 300 es
precision highp float;
uniform vec2 viewportSize;
uniform vec4 rectangle;
out vec2 imageUv;
void main() {
    vec2 corner = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    vec2 pixel = rectangle.xy + corner * rectangle.zw;
    gl_Position = vec4(pixel.x * 2.0 / viewportSize.x - 1.0,
                       1.0 - pixel.y * 2.0 / viewportSize.y, 0.0, 1.0);
    imageUv = corner;
}
