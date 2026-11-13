package dev.glasslauncher.dream.viz

/**
 * The visualizer styles as GLSL ES 1.00 fragment shaders (P334): our own, drawn full-bleed on one full-screen quad. They read the audio as a
 * 256 x 3 luminance texture (row 0 the spectrum, row 1 the waveform, row 2 the held peaks) plus a few numbers (bass, mid, high, beat).
 * Loud, saturated and driven hard by the bass; the looks (a mirrored equaliser, a tunnel, a kaleidoscopic warp, ribbons, a synthwave mesh, a neon
 * scope) are the classics of the old media-player visualizers, written from scratch.
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
        // a bright cosine palette: every hue at full saturation
        vec3 pal(float t) { return 0.5 + 0.5 * cos(6.28318 * (t + vec3(0.0, 0.33, 0.67))); }
        vec2 centred(vec2 uv) { return (uv - 0.5) * vec2(uRes.x / uRes.y, 1.0); }
        mat2 rot(float a) { float c = cos(a); float s = sin(a); return mat2(c, -s, s, c); }
    """

    /** Spectrum: a mirrored equaliser, bars growing from the middle line to the edges of the screen, in a rolling rainbow. */
    const val SPECTRUM = HEAD + """
        void main() {
            const float N = 64.0;
            float cell = floor(vUv.x * N);
            float fx = fract(vUv.x * N);
            float x = (cell + 0.5) / N;
            float lv = spec(x);
            float dy = abs(vUv.y - 0.5) * 2.0;
            float h = 0.04 + lv * 0.96;
            float gap = smoothstep(0.0, 0.14, fx) * smoothstep(1.0, 0.86, fx);
            float body = step(dy, h) * gap;
            vec3 c = pal(x * 0.9 + uTime * 0.06 + lv * 0.25);
            vec3 col = c * body * (0.6 + 0.9 * (1.0 - dy / max(h, 0.001)));
            col += c * exp(-abs(dy - h) * 16.0) * gap * 0.7;
            float pk = 0.04 + peak(x) * 0.96;
            col += vec3(1.0) * step(abs(dy - pk), 0.012) * gap;
            col += pal(uTime * 0.04) * 0.10 * (0.5 + uBass) * (1.0 - dy * 0.5);
            col *= 1.0 + 0.7 * uBeat;
            gl_FragColor = vec4(col, 1.0);
        }
    """

    /** Tunnel: rings and spokes rushing at the viewer from a vanishing point, flashing with the spectrum. */
    const val TUNNEL = HEAD + """
        void main() {
            vec2 p = centred(vUv);
            float r = length(p) + 0.001;
            float a = atan(p.y, p.x);
            float z = 0.35 / r + uTime * (0.7 + uBass * 1.6);
            float ang = abs(fract(a / 6.28318 + uTime * 0.02) * 2.0 - 1.0);
            float s = spec(ang * 0.8);
            float rings = pow(abs(sin(z * 3.2)), 5.0);
            float spokes = pow(abs(sin(a * 7.0 + z * 0.8)), 9.0);
            vec3 c = pal(z * 0.07 + ang * 0.5 + uTime * 0.05);
            vec3 col = c * (rings * (0.5 + s * 1.8) + spokes * 0.6 * s) * smoothstep(0.0, 0.45, r);
            col += c * 0.10 * (0.4 + s);
            col += pal(uTime * 0.1) * exp(-r * 5.0) * (0.5 + uBass * 2.2);
            col *= 1.0 + 0.9 * uBeat;
            gl_FragColor = vec4(col, 1.0);
        }
    """

    /** Ribbons: eight thick ribbons across the whole screen, rolling slowly, each swelling with its slice of the spectrum. */
    const val RIBBONS = HEAD + """
        void main() {
            vec2 p = rot(uTime * 0.05 + uBass * 0.08) * centred(vUv);
            vec3 col = vec3(0.0);
            for (int i = 0; i < 8; i++) {
                float f = float(i);
                float band = spec(0.02 + f * 0.11);
                float amp = 0.12 + band * 0.42 + uBass * 0.08;
                float k = 1.6 + f * 0.45;
                float s = 0.4 + f * 0.13;
                float y = amp * sin(p.x * k + uTime * s + f * 1.9) + 0.45 * amp * sin(p.x * k * 2.1 - uTime * s * 1.3 + f * 0.7);
                float w = 0.022 + band * 0.060;
                float d = abs(p.y - y);
                float core = smoothstep(w, 0.0, d);
                float glow = exp(-d * (14.0 - band * 6.0));
                vec3 c = pal(f * 0.13 + uTime * 0.04 + band * 0.2);
                col += c * (core * 1.1 + glow * 0.35);
            }
            col *= 0.8 + 0.8 * uBeat;
            col += pal(uTime * 0.03) * 0.07 * (0.5 + uBass);
            gl_FragColor = vec4(col, 1.0);
        }
    """

    /** Grid: a synthwave mesh terrain under a striped sun, its hills the spectrum, scrolling towards the viewer. */
    const val GRID = HEAD + """
        void main() {
            vec2 uv = vUv;
            float horizon = 0.56;
            vec3 col;
            if (uv.y > horizon) {
                float t = (uv.y - horizon) / (1.0 - horizon);
                col = mix(vec3(0.95, 0.25, 0.45), vec3(0.10, 0.02, 0.28), pow(t, 0.55)) * 0.75;
                vec2 sp = vec2((uv.x - 0.5) * (uRes.x / uRes.y), uv.y - horizon - 0.17);
                float sr = 0.17 + uBass * 0.06;
                float sun = smoothstep(sr, sr - 0.012, length(sp));
                float stripes = step(0.0, sin((sp.y + uTime * 0.02) * 70.0) + 0.55 + sp.y * 4.0);
                col = mix(col, vec3(1.0, 0.85, 0.3) + vec3(0.0, -0.35, 0.1) * (0.5 - sp.y * 2.0), sun * stripes);
            } else {
                col = vec3(0.06, 0.0, 0.12);
                float gz = (horizon - uv.y) + 0.004;
                float vx = (uv.x - 0.5) * (uRes.x / uRes.y) / gz;
                float vline = exp(-abs(fract(vx * 0.35 + 0.5) - 0.5) * 38.0 * (0.4 + 0.6 * gz * 4.0));
                col += vec3(0.1, 0.9, 1.0) * vline * 0.45 * smoothstep(0.0, 0.08, gz);
            }
            // the hills: sixteen rows of spectrum, near rows tall and bright, far rows low and dim
            float lines = 0.0;
            vec3 lc = vec3(0.0);
            for (int i = 0; i < 16; i++) {
                float n = fract(float(i) / 16.0 + uTime * (0.07 + uBass * 0.08));
                float yb = horizon * (1.0 - pow(n, 1.7));
                float h = spec(abs(uv.x - 0.5) * 1.8 + float(i) * 0.002) * 0.34 * (0.25 + 0.75 * n);
                float d = abs(uv.y - (yb + h));
                float l = exp(-d * (260.0 - n * 120.0)) * (0.30 + 0.9 * n);
                lc += pal(n * 0.5 + 0.55 + uTime * 0.03) * l;
            }
            col += lc * (1.0 + 0.8 * uBeat);
            gl_FragColor = vec4(col, 1.0);
        }
    """

    /** Scope: the waveform as a thick neon ring that swells with the bass, in three split colours, with the line across behind it. */
    const val SCOPE = HEAD + """
        void main() {
            vec2 p = centred(vUv);
            float r = length(p);
            float a = atan(p.y, p.x) / 6.28318 + 0.5;
            float base = 0.24 + uBass * 0.14;
            vec3 col = vec3(0.0);
            for (int i = 0; i < 3; i++) {
                float f = float(i);
                float w = wave(fract(a + f * 0.012)) * 0.16;
                float d = abs(r - (base + w));
                vec3 c = f < 0.5 ? vec3(1.0, 0.15, 0.35) : (f < 1.5 ? vec3(0.15, 1.0, 0.55) : vec3(0.25, 0.45, 1.0));
                col += c * (exp(-d * 60.0) * 0.95 + exp(-d * 12.0) * 0.28);
            }
            float line = abs(p.y - wave(vUv.x) * 0.28);
            col += pal(uTime * 0.08) * (exp(-line * 55.0) * 0.8 + exp(-line * 10.0) * 0.15);
            col *= 0.75 + 0.8 * uBeat;
            col += pal(uTime * 0.05) * 0.06 * (0.5 + uBass);
            gl_FragColor = vec4(col, 1.0);
        }
    """

    /** Warp, pass 1: the last frame folded into a six-way kaleidoscope, zoomed, turned and faded, with a spiral of waveform and spectrum rays drawn into it. */
    const val WARP_STEP = HEAD + """
        uniform sampler2D uPrev;
        void main() {
            vec2 p = centred(vUv);
            float r = length(p);
            float a0 = atan(p.y, p.x);
            const float seg = 1.0471976;
            float a = abs(mod(a0 + uTime * 0.05, seg * 2.0) - seg);
            vec2 q = r * vec2(cos(a), sin(a));
            q *= 0.972 - uBass * 0.022;
            q = rot(0.012 + (uMid - 0.3) * 0.025) * q;
            vec3 prev = texture2D(uPrev, q / vec2(uRes.x / uRes.y, 1.0) + 0.5).rgb;
            vec3 col = max(prev * (0.945 + 0.02 * uBeat) - 0.006, 0.0);      // fades to black, never to a grey fog
            col = mix(col, col.gbr, 0.02);                                   // the hue turns slowly as it fades
            float lum = dot(col, vec3(0.333));
            col = max(mix(vec3(lum), col, 1.05), 0.0);                       // and stays saturated
            // a spiral of waveform, and rays of spectrum
            float ring = 0.10 + uBass * 0.14 + wave(fract(a0 / 6.28318 + 0.5)) * 0.06;
            float d = abs(r - ring);
            vec3 ink = pal(uTime * 0.02 + a0 * 0.159);                       // a colour fixed to each direction, so trails stay saturated instead of averaging to grey
            float room = 1.0 - max(col.r, max(col.g, col.b));
            col += ink * exp(-d * 60.0) * (0.75 + 1.4 * uBeat) * (0.4 + room);
            float ra = a / seg;
            col += pal(ra * 0.9 + uTime * 0.03) * spec(ra * 0.7) * smoothstep(0.08, 0.5, r) * 0.10 * (0.3 + room);
            gl_FragColor = vec4(min(col, vec3(1.0)), 1.0);
        }
    """

    /** Warp, pass 2: the accumulated picture to the screen. */
    const val BLIT = """
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D uPrev;
        uniform vec3 uTint;
        void main() { gl_FragColor = vec4(texture2D(uPrev, vUv).rgb + uTint * 0.04 * (1.0 - 0.85 * length(vUv - 0.5)), 1.0); }
    """
}
