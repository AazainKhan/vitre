package dev.glasslauncher.dream.viz

/**
 * The visualizer styles as GLSL ES 1.00 fragment shaders (P334): our own, drawn on one full-screen quad. They read the audio as a
 * 256 x 3 luminance texture (row 0 the spectrum, row 1 the waveform, row 2 the held peaks) plus a few numbers (bass, mid, high, beat).
 */
internal object VizShaders {
    const val VERTEX = """
        attribute vec2 aPos;
        varying vec2 vUv;
        void main() { vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }
    """

    /** Shared by every style. */
    private const val HEAD = """
        precision mediump float;
        varying vec2 vUv;
        uniform vec2 uRes;
        uniform float uTime;
        uniform vec3 uTint;
        uniform float uBass;
        uniform float uMid;
        uniform float uHigh;
        uniform float uBeat;
        uniform sampler2D uAudio;
        float spec(float x) { return texture2D(uAudio, vec2(clamp(x, 0.0, 1.0), 0.1667)).r; }
        float wave(float x) { return texture2D(uAudio, vec2(clamp(x, 0.0, 1.0), 0.5)).r * 2.0 - 1.0; }
        float peak(float x) { return texture2D(uAudio, vec2(clamp(x, 0.0, 1.0), 0.8333)).r; }
        vec3 hue(float h) { return clamp(abs(mod(h * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0); }
        // the cover's colour as a dim backdrop, and a calm patch in the middle where the cover and its text sit
        vec3 backdrop(vec2 uv) { return uTint * 0.11 * (1.0 - 0.85 * length(uv - 0.5)); }
        float calm(vec2 uv) { return mix(0.40, 1.0, smoothstep(0.10, 0.52, length((uv - vec2(0.5, 0.47)) * vec2(1.35, 1.0)))); }
    """

    /** Bars: bold, tall, glowing columns with peak caps and a faint reflection. */
    const val BARS = HEAD + """
        void main() {
            const float N = 40.0;
            float cell = floor(vUv.x * N);
            float fx = fract(vUv.x * N);
            float x = (cell + 0.5) / N;
            float level = spec(x);
            float base = 0.16;
            float h = base + level * 0.62;
            float y = vUv.y;
            float gap = smoothstep(0.0, 0.10, fx) * smoothstep(1.0, 0.90, fx);
            float body = step(base, y) * step(y, h) * gap;
            float t = clamp((y - base) / max(h - base, 0.001), 0.0, 1.0);
            vec3 col = mix(uTint * 0.9, vec3(1.0), t * t) * body;
            // peak cap
            float pk = base + peak(x) * 0.62;
            col += vec3(1.0) * step(abs(y - pk - 0.012), 0.007) * gap;
            // glow around the bar's top edge
            col += uTint * exp(-abs(y - h) * 28.0) * gap * 0.35 * step(base, y);
            // reflection under the base
            float refl = step(y, base) * step(base - 0.16, y) * gap * smoothstep(base - 0.16, base, y);
            float rt = clamp((base - y) / 0.16, 0.0, 1.0);
            col += mix(uTint, vec3(1.0), 0.3) * refl * (1.0 - rt) * 0.18 * clamp(level * 1.6, 0.0, 1.0);
            gl_FragColor = vec4(col * calm(vUv) + backdrop(vUv), 1.0);
        }
    """

    /** Scope: one bright, glowing waveform line across the middle. */
    const val SCOPE = HEAD + """
        void main() {
            float x = vUv.x;
            float w = wave(x) * (0.20 + uBass * 0.18);
            float y = vUv.y - 0.5 - w;
            // slope-corrected distance to the line
            float dx = 1.0 / 256.0;
            float slope = (wave(x + dx) - wave(x - dx)) * (0.20 + uBass * 0.18) * 128.0 * (uRes.x / uRes.y);
            float d = abs(y) / sqrt(1.0 + slope * slope * 0.02);
            float line = exp(-d * 110.0) + 0.35 * exp(-d * 22.0);
            vec3 col = mix(uTint, vec3(1.0), clamp(exp(-d * 260.0), 0.0, 1.0)) * line;
            col += uTint * 0.05 * (1.0 - abs(vUv.y - 0.5) * 2.0);          // a faint axis haze
            col *= 0.6 + 0.8 * uBeat;
            gl_FragColor = vec4(col * calm(vUv) + backdrop(vUv), 1.0);
        }
    """

    /** Bars and waves: a spectrum mirrored about the middle with the waveform drawn over it, colours drifting with time. */
    const val BARS_WAVES = HEAD + """
        void main() {
            const float N = 56.0;
            float cell = floor(vUv.x * N);
            float fx = fract(vUv.x * N);
            float x = (cell + 0.5) / N;
            float level = spec(x);
            float dy = abs(vUv.y - 0.5);
            float h = 0.03 + level * 0.42;
            float gap = smoothstep(0.0, 0.12, fx) * smoothstep(1.0, 0.88, fx);
            float body = step(dy, h) * gap;
            vec3 bar = hue(fract(0.55 + x * 0.35 + uTime * 0.03)) * (0.45 + 0.55 * (1.0 - dy / max(h, 0.001)));
            vec3 col = bar * body;
            col += hue(fract(0.55 + x * 0.35 + uTime * 0.03)) * exp(-abs(dy - h) * 30.0) * gap * 0.3;
            float wv = wave(vUv.x) * 0.16;
            float d = abs(vUv.y - 0.5 - wv);
            col += vec3(1.0) * exp(-d * 140.0) * 0.9 + uTint * exp(-d * 28.0) * 0.35;
            gl_FragColor = vec4(col * calm(vUv) + backdrop(vUv), 1.0);
        }
    """

    /** Ribbons: five flowing coloured bands, each swelling with a slice of the spectrum. */
    const val RIBBONS = HEAD + """
        void main() {
            vec2 p = vec2(vUv.x * (uRes.x / uRes.y), vUv.y);
            vec3 col = vec3(0.0);
            for (int i = 0; i < 5; i++) {
                float f = float(i);
                float band = spec(0.05 + f * 0.16);
                float amp = 0.07 + band * 0.20 + uBass * 0.06;
                float k = 2.1 + f * 0.55;
                float s = 0.35 + f * 0.11;
                float y = 0.5 + amp * sin(p.x * k + uTime * s + f * 1.7)
                              + 0.5 * amp * sin(p.x * k * 2.3 - uTime * s * 1.4 + f);
                float w = 0.012 + band * 0.030;
                float d = abs(vUv.y - y);
                float core = smoothstep(w, 0.0, d);
                float glow = exp(-d * (22.0 - band * 10.0));
                vec3 c = mix(hue(fract(0.08 + f * 0.17 + uTime * 0.02)), uTint, 0.35);
                col += c * (core * 0.9 + glow * 0.28);
            }
            col *= 0.75 + 0.5 * uBeat;
            gl_FragColor = vec4(col * calm(vUv) + backdrop(vUv), 1.0);
        }
    """

    /** Feedback, pass 1: the last frame zoomed, turned and faded, with the waveform and a bass ring drawn into it (the Winamp / MilkDrop trick). */
    const val FEEDBACK_STEP = HEAD + """
        uniform sampler2D uPrev;
        void main() {
            vec2 c = vUv - 0.5;
            c.x *= uRes.x / uRes.y;
            float r = length(c);
            float zoom = 0.985 - uBass * 0.012;
            float ang = 0.004 + (uMid - 0.3) * 0.006 + sin(uTime * 0.2) * 0.003;
            float ca = cos(ang), sa = sin(ang);
            vec2 q = mat2(ca, -sa, sa, ca) * c * zoom;
            q.x /= uRes.x / uRes.y;
            vec3 prev = texture2D(uPrev, q + 0.5).rgb;
            // a slow hue drift and a fade
            vec3 col = prev * (0.925 + 0.02 * uBeat);
            col = mix(col, col.gbr, 0.035);              // the hue turns as it fades, so trails change colour rather than go white
            // the waveform, drawn as a ring around the centre
            float a = atan(c.y, c.x) / 6.2831853 + 0.5;
            float ring = 0.16 + uBass * 0.07 + wave(a) * 0.07;
            float d = abs(r - ring);
            vec3 ink = mix(hue(fract(uTime * 0.06 + uMid * 0.3)), uTint, 0.3);
            // new light is added only where there is room: a saturated trail stays coloured instead of burning out to white
            float room = 1.0 - max(col.r, max(col.g, col.b));
            col += ink * exp(-d * 110.0) * (0.30 + 0.45 * uBeat) * (0.35 + room) + ink * exp(-d * 420.0) * 0.30;
            gl_FragColor = vec4(min(col, vec3(1.0)), 1.0);
        }
    """

    /** Feedback, pass 2: the accumulated picture to the screen. */
    const val BLIT = """
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D uPrev;
        uniform vec3 uTint;
        void main() { gl_FragColor = vec4(texture2D(uPrev, vUv).rgb + uTint * 0.10 * (1.0 - 0.85 * length(vUv - 0.5)), 1.0); }
    """
}
