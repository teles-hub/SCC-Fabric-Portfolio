#version 150

// Wie Vanillas position_tex_color.fsh, aber ohne "if (color.a < 0.1) discard": am Himmel sind die
// meisten Schichten schwaecher als 0,1 und wurden sonst mit hartem, treppigem Rand abgeschnitten
// (siehe SkyShader.java). Verworfen wird nur, was wirklich nichts beitraegt.

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
    if (color.a <= 0.0) {
        discard;
    }
    fragColor = color * ColorModulator;
}
