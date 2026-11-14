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
        uniform highp float uTime;
        uniform highp float uFlow;   // integrated each frame: quickens with the bass, never steps back; wraps every 20 pi, so use it as k * FP in a hue and k/10 in a sine
        uniform highp float uSpin;   // an angle, 0 to 2 pi, turning faster with the mids
        uniform vec3 uTint;
        uniform float uBass;
        uniform float uMid;
        uniform float uHigh;
        uniform float uBeat;
        uniform float uKick;         // a bass hit: jumps up the frame it lands, falls away in about a quarter of a second
        uniform sampler2D uAudio;
        uniform sampler2D uPrev;     // the last picture (the feedback styles; unused by Grid)
        const float FP = 0.0159155;  // 1 / 20 pi
        float spec(float x) { return texture2D(uAudio, vec2(clamp(x, 0.0, 1.0), 0.1667)).r; }
        float wave(float x) { return texture2D(uAudio, vec2(clamp(x, 0.0, 1.0), 0.5)).r * 2.0 - 1.0; }
        float peak(float x) { return texture2D(uAudio, vec2(clamp(x, 0.0, 1.0), 0.8333)).r; }
        // a bright cosine palette: every hue at full saturation
        vec3 pal(float t) { return 0.5 + 0.5 * cos(6.28318 * (t + vec3(0.0, 0.33, 0.67))); }
        // the same colours without a cosine per channel: a triangle wave, for shaders that colour many things per pixel
        vec3 hue(float t) { return clamp(abs(fract(t + vec3(0.0, 0.3333, 0.6667)) * 6.0 - 3.0) - 1.0, 0.0, 1.0); }
        vec2 centred(vec2 uv) { return (uv - 0.5) * vec2(uRes.x / uRes.y, 1.0); }
        mat2 rot(float a) { float c = cos(a); float s = sin(a); return mat2(c, -s, s, c); }
        // The last picture at a centred point. Past the edge it is mirrored, so a zoom out folds the border back in instead of smearing it.
        vec3 prevAt(vec2 p) {
            vec2 uv = p / vec2(uRes.x / uRes.y, 1.0) + 0.5;
            return texture2D(uPrev, abs(mod(uv + 1.0, 2.0) - 1.0)).rgb;
        }
        // What every feedback frame does to the last picture: fade it towards black, let its hue creep, keep it saturated.
        vec3 settle(vec3 c, float k, float sat) {
            c = max(c * k - 0.006, 0.0);
            c = mix(c, c.gbr, 0.012);
            float l = dot(c, vec3(0.333));
            return max(mix(vec3(l), c, sat), 0.0);
        }
        // Brightness past 1 is scaled down with the hue kept, not clipped channel by channel (which turns every hot spot white).
        // how much headroom a pixel has: 1 where it is dark, 0 where it is already full
        float room(vec3 c) { return 1.0 - max(c.r, max(c.g, c.b)); }
        vec3 fit(vec3 c) { return c / max(1.0, max(c.r, max(c.g, c.b))); }
        // A slow swirl over the whole screen (the per-pixel sines of the old media-player presets), so the picture flows rather than just scales.
        vec2 swirl(vec2 p, float s) {
            return s * vec2(sin(p.y * 7.0 + uFlow * 0.9) + sin(p.y * 3.0 - uFlow * 0.4), cos(p.x * 6.0 - uFlow * 0.7) + cos(p.x * 2.5 + uFlow * 0.3));
        }
        // 0 in the middle of the screen, 1 at its edge, so a style can draw where the edges are
        float edge(vec2 p) { return clamp(1.0 - min(0.5 - abs(p.y), 0.8889 - abs(p.x)) * 2.0, 0.0, 1.0); }
    """

    /** Spectrum: a mirrored equaliser; each frame is pulled out from the middle line, so the bars stream to the top and bottom of the screen as glowing curtains. */
    const val SPECTRUM = HEAD + """
        void main() {
            vec2 p = centred(vUv);
            vec2 q = vec2(p.x, p.y * (0.962 - 0.035 * uKick)) + 0.0035 * vec2(sin(p.y * 7.0 + uFlow * 0.9), cos(p.x * 6.0 - uFlow * 0.7));
            vec3 col = settle(prevAt(q), 0.935, 1.04);
            vec3 add = vec3(0.0);   // what is drawn this frame; it is added at a fraction, as the fade lets it build up many times over
            const float N = 64.0;
            float cell = floor(vUv.x * N);
            float fx = fract(vUv.x * N);
            float x = (cell + 0.5) / N;
            float lv = spec(x);
            float dy = abs(vUv.y - 0.5) * 2.0;
            float h = 0.04 + lv * 0.96;
            float gap = smoothstep(0.0, 0.14, fx) * smoothstep(1.0, 0.86, fx);
            float body = step(dy, h) * gap;
            vec3 c = hue(x * 0.9 + uFlow * FP * 2.0 + lv * 0.25);
            add += c * body * (0.35 + 0.65 * (1.0 - dy / max(h, 0.001))) * (0.7 + 0.5 * uKick);
            add += c * exp(-abs(dy - h) * 14.0) * gap * 0.45;
            float pk = 0.04 + peak(x) * 0.96;
            add += vec3(1.0) * step(abs(dy - pk), 0.012) * gap * 0.8;
            add += hue(uFlow * FP) * 0.05 * (0.4 + uKick) * (1.0 - dy * 0.4);
            gl_FragColor = vec4(fit(col + add * 0.22), 1.0);
        }
    """

    /** Tunnel: the picture falls inward and spins; a frame of spectrum is drawn along the whole border of the screen, so light pours in from the edges. */
    const val TUNNEL = HEAD + """
        void main() {
            vec2 p = centred(vUv);
            vec2 q = rot(0.014 + uKick * 0.02) * p * (1.032 + uKick * 0.045) + swirl(p, 0.005);
            vec3 col = settle(prevAt(q), 0.915, 1.04);
            vec3 add = vec3(0.0);   // what is drawn this frame; it is added at a fraction, as the fade lets it build up many times over
            float e = min(0.5 - abs(p.y), 0.8889 - abs(p.x));         // how far inside the border
            float ang = atan(p.y, p.x) / 6.28318 + 0.5;
            float lv = spec(abs(fract(ang + uSpin / 6.28318) * 2.0 - 1.0) * 0.85);
            float depth = 0.03 + lv * 0.30 + uKick * 0.06;
            vec3 c = pal(ang + uFlow * FP * 2.0);
            float comb = 0.25 + 0.75 * smoothstep(0.15, 0.55, abs(fract(ang * 40.0) - 0.5) * 2.0);   // bars along the border, so the light has structure
            add += c * smoothstep(depth, 0.0, e) * (0.4 + 1.2 * lv) * comb;
            add += pal(ang * 2.0 - uFlow * FP * 3.0) * exp(-abs(e - 0.015 - 0.2 * lv) * 40.0) * 0.5 * lv;
            add += pal(uFlow * FP) * exp(-length(p) * 5.0) * (0.05 + 0.5 * uKick);
            gl_FragColor = vec4(fit(col + add * 0.16), 1.0);
        }
    """

    /** Ribbons: four long ribbons across the whole screen; the picture is swirled and eased outward each frame, so they leave silky, fading wakes. */
    const val RIBBONS = HEAD + """
        void main() {
            vec2 p = centred(vUv);
            vec2 q = rot(0.006 + (uMid - 0.2) * 0.02) * p * (0.985 - uKick * 0.025) + swirl(p, 0.006);
            vec3 col = settle(prevAt(q), 0.925, 1.04);
            vec3 add = vec3(0.0);   // what is drawn this frame; it is added at a fraction, as the fade lets it build up many times over
            vec2 r = rot(uSpin * 0.4) * p;
            for (int i = 0; i < 4; i++) {
                float f = float(i);
                float band = spec(0.02 + f * 0.22);
                float amp = 0.16 + band * 0.44 + uKick * 0.10;
                float k = 1.7 + f * 0.8;
                float y = amp * sin(r.x * k + uFlow * (0.4 + f * 0.1) + f * 1.9) + 0.45 * amp * sin(r.x * k * 2.1 - uFlow * 0.3 + f * 0.7);
                float w = 0.020 + band * 0.050;
                float d = abs(r.y - y);
                vec3 c = hue(f * 0.23 + uFlow * FP * 2.0 + band * 0.2);
                add += c * (smoothstep(w, 0.0, d) * 1.0 + 0.30 / (1.0 + d * d * (260.0 - band * 120.0)));
            }
            gl_FragColor = vec4(fit(col + add * 0.14), 1.0);
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
                float sr = 0.17 + uKick * 0.05;
                float sun = smoothstep(sr, sr - 0.012, length(sp));
                float stripes = step(0.0, sin((sp.y + uFlow * 0.002) * 70.0) + 0.55 + sp.y * 4.0);
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
                float n = fract(float(i) / 16.0 + uFlow * FP * 10.0);
                float yb = horizon * (1.0 - pow(n, 1.7));
                float h = spec(abs(uv.x - 0.5) * 1.8 + float(i) * 0.002) * 0.34 * (0.25 + 0.75 * n);
                float d = abs(uv.y - (yb + h));
                float l = exp(-d * (260.0 - n * 120.0)) * (0.30 + 0.9 * n);
                lc += pal(n * 0.5 + 0.55 + uFlow * FP) * l;
            }
            col += lc * (1.0 + 0.8 * uBeat);
            gl_FragColor = vec4(col, 1.0);
        }
    """

    /** Scope: the waveform as a big neon ring and a line across the whole screen; each frame is turned and pushed outward, so the echoes spiral out to the corners. */
    const val SCOPE = HEAD + """
        void main() {
            vec2 p = centred(vUv);
            vec2 q = rot(0.012 + uBeat * 0.01) * p * (0.972 - uKick * 0.03) + swirl(p, 0.004);
            vec3 col = settle(prevAt(q), 0.925, 1.04);
            vec3 add = vec3(0.0);   // what is drawn this frame; it is added at a fraction, as the fade lets it build up many times over
            float r = length(p);
            float a = atan(p.y, p.x) / 6.28318 + 0.5;
            float base = 0.30 + uKick * 0.16;
            for (int i = 0; i < 3; i++) {
                float f = float(i);
                float w = wave(fract(a + f * 0.012)) * 0.17;
                float d = abs(r - (base + w));
                vec3 c = f < 0.5 ? vec3(1.0, 0.15, 0.35) : (f < 1.5 ? vec3(0.15, 1.0, 0.55) : vec3(0.25, 0.45, 1.0));
                add += c * (exp(-d * 55.0) * 0.8 + exp(-d * 11.0) * 0.20);
            }
            float line = abs(p.y - wave(vUv.x) * 0.42);
            add += pal(uFlow * FP * 2.0 + vUv.x * 0.5) * (exp(-line * 60.0) * 0.8 + exp(-line * 10.0) * 0.12);
            float line2 = abs(p.x - wave(vUv.y) * 0.5);
            add += pal(uFlow * FP * 2.0 + 0.5 + vUv.y * 0.5) * (exp(-line2 * 60.0) * 0.5 + exp(-line2 * 10.0) * 0.08);
            gl_FragColor = vec4(fit(col + add * 0.12), 1.0);
        }
    """

    /** Warp: the picture folded into a six-way kaleidoscope, zoomed, turned and faded each frame, with rings and rays of the music drawn out to the corners. */
    const val WARP_STEP = HEAD + """
        void main() {
            vec2 p = centred(vUv);
            float r = length(p);
            float a0 = atan(p.y, p.x);
            const float seg = 1.0471976;
            float a = abs(mod(a0 + uSpin, seg * 2.0) - seg);
            vec2 q = r * vec2(cos(a), sin(a));
            q *= 0.982 - uKick * 0.03;
            q = rot(0.012 + (uMid - 0.3) * 0.03) * q + swirl(p, 0.004);
            vec3 col = settle(prevAt(q), 0.88, 1.12);
            vec3 add = vec3(0.0);   // what is drawn this frame; it is added at a fraction, as the fade lets it build up many times over
            // two rings are born in the middle and travel out past the corners; their radius wobbles with the angle (a flower, not a bullseye)
            float w = wave(fract(a0 / 6.28318 + 0.5)) * 0.03 + sin(a0 * 3.0 + uFlow * 0.3) * 0.08 - uKick * 0.04;
            float gain = (0.45 + 1.1 * uBeat) * (0.4 + room(col));
            vec3 pos = hue(uFlow * FP * 2.0 + p.x * 0.5 + p.y * 0.35);   // colour by place, so the corners differ from the middle
            for (int k = 0; k < 2; k++) {
                float rr = fract(uFlow * FP * 20.0 + float(k) * 0.5) * 1.15;
                add += hue(uFlow * FP * 2.0 + p.x * 0.5 + p.y * 0.35 + float(k) * 0.5) * exp(-abs(r - rr - w) * 48.0) * gain;
            }
            float ra = a / seg;
            add += hue(ra * 0.9 + r * 1.6 + uFlow * FP) * spec(ra * 0.7 + r * 0.25) * smoothstep(0.2, 0.9, r) * 0.85 * (0.4 + room(col));
            // the edges keep their own colour, and the floor under it means the picture can fade but never go black
            add += pos.brg * (0.05 + smoothstep(0.5, 1.0, r) * (0.18 + 0.5 * uMid) * (0.4 + room(col)));
            add += pos.gbr * exp(-r * 10.0) * (0.06 + 0.35 * uKick);   // a core that flares on the bass
            gl_FragColor = vec4(fit(col + add * 0.34), 1.0);
        }
    """

    /** Warp, pass 2: the accumulated picture to the screen. */
    const val BLIT = """
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D uPrev;
        uniform vec3 uTint;
        uniform vec3 uFloor;   // a drifting colour the CPU computes once a frame: silence or a capture gap is never black
        uniform float uVivid;   // 0 as drawn; 1 lifts saturation and mid-tones (Warp, whose folds blend colours towards mud)
        void main() {
            vec3 c = texture2D(uPrev, vUv).rgb;
            float l = dot(c, vec3(0.333));
            c = pow(max(mix(vec3(l), c, 1.0 + 0.7 * uVivid), 0.0), vec3(1.0 - 0.22 * uVivid));
            float len = length(vUv - 0.5);
            gl_FragColor = vec4(c + uTint * 0.04 * (1.0 - 0.85 * len) + uFloor * (1.0 - 0.4 * len), 1.0);
        }
    """
}
