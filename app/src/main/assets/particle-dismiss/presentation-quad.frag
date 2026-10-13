#version 300 es
precision highp float;
uniform sampler2D sourceImage;
uniform float opacity;
in vec2 imageUv;
out vec4 color;
void main() {
    color = texture(sourceImage, imageUv) * opacity;
}
