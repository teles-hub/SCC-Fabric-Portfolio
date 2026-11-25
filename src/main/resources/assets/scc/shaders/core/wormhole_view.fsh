#version 150

// Wurmloch-Vorschau (1.4.1, siehe WormholeViewClient.java): die Flaeche im Portal zeigt das Bild der
// Zielseite, das vorher in ein eigenes, kleines Bild gezeichnet wurde - aus derselben Blickrichtung wie
// die Kamera. Darum wird hier nicht nach den Eck-Koordinaten der Flaeche gelesen, sondern nach der Stelle
// auf dem Bildschirm (gl_FragCoord): so passt das Bild an jeder Stelle genau hinter das Portal, auch
// schraeg von der Seite (Parallaxe wie bei einem echten Fenster).
//
// MaskMode 0 = runde Scheibe im Tunnel (weicher Rand, der Strudel bleibt aussen sichtbar),
// MaskMode 1 = Riss der Remote (Form aus der Deckung des Riss-Bildes in Sampler1).

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

uniform vec4 ColorModulator;
uniform vec2 ScreenSize;
uniform float MaskMode;
uniform float Ripple;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    float mask;
    vec2 wobble = vec2(0.0);
    if (MaskMode < 0.5) {
        vec2 d = texCoord0 * 2.0 - 1.0;
        float r = length(d);
        mask = 1.0 - smoothstep(0.5, 0.97, r);
        // Zum Rand hin kraeuselt sich das Bild ein wenig - der Strudel zieht daran.
        float edge = smoothstep(0.35, 1.0, r);
        // Ripple ist die Zeit in Sekunden, 0 = still (Sternenanimationen aus).
        wobble = d * edge * 0.010 * sin(r * 22.0 - Ripple * 3.0) * step(0.0001, Ripple);
    } else {
        mask = texture(Sampler1, texCoord0).a;
    }
    vec2 uv = gl_FragCoord.xy / ScreenSize + wobble;
    vec3 view = texture(Sampler0, uv).rgb;
    vec4 color = vec4(view, 1.0) * vertexColor * ColorModulator;
    color.a *= mask;
    if (color.a <= 0.002) {
        discard;
    }
    fragColor = color;
}
