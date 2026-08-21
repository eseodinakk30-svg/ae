package com.autoedit.engine

/** GLSL для всех проходов рендера. */
object Shaders {

    private const val PRECISION = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
"""

    const val VERTEX = """
attribute vec4 aPos;
attribute vec2 aTex;
varying vec2 vUv;
void main() {
    gl_Position = aPos;
    vUv = aTex;
}
"""

    /**
     * Геометрия: матрица [uUvMatrix] переводит точку кадра в координаты исходника
     * (кроп под формат, зум, тряска, наклон, поворот исходного видео),
     * ударная волна считается попиксельно до неё.
     * Если кадр вписывается целиком, поля заполняются размытым фоном [uBg].
     */
    val GEOMETRY = """
#extension GL_OES_EGL_image_external : require
$PRECISION
varying vec2 vUv;
uniform samplerExternalOES uTex;
uniform sampler2D uBg;
uniform mat4 uStMatrix;
uniform mat3 uUvMatrix;
uniform float uOutAspect;
uniform float uMirror;
uniform float uShock;
uniform float uShockPhase;
uniform float uContain;
uniform float uBgDim;
void main() {
    vec2 uv = vUv;
    if (uMirror > 0.5) uv.x = 1.0 - uv.x;
    vec2 p = (uv - 0.5) * vec2(uOutAspect, 1.0);
    if (uShock > 0.001) {
        float r = length(p);
        float w = sin((r - uShockPhase) * 16.0) * exp(-abs(r - uShockPhase) * 8.0);
        p += normalize(p + vec2(1e-5)) * w * uShock * 0.05;
    }
    vec3 s = uUvMatrix * vec3(p, 1.0);
    if (uContain > 0.5 && (s.x < 0.0 || s.x > 1.0 || s.y < 0.0 || s.y > 1.0)) {
        gl_FragColor = vec4(texture2D(uBg, vUv).rgb * uBgDim, 1.0);
    } else {
        vec2 suv = clamp(s.xy, 0.001, 0.999);
        gl_FragColor = texture2D(uTex, (uStMatrix * vec4(suv, 0.0, 1.0)).xy);
    }
}
"""

    /** Простое копирование внешней текстуры — для покадрового разбора видео. */
    val OES_COPY = """
#extension GL_OES_EGL_image_external : require
$PRECISION
varying vec2 vUv;
uniform samplerExternalOES uTex;
uniform mat4 uStMatrix;
void main() {
    gl_FragColor = texture2D(uTex, (uStMatrix * vec4(vUv, 0.0, 1.0)).xy);
}
"""

    /** Копирование с заданной прозрачностью (накопление шлейфов). */
    val COPY = """
$PRECISION
varying vec2 vUv;
uniform sampler2D uTex;
uniform float uAlpha;
void main() {
    gl_FragColor = vec4(texture2D(uTex, vUv).rgb, uAlpha);
}
"""

    /** Отбор ярких участков для свечения. */
    val BRIGHT = """
$PRECISION
varying vec2 vUv;
uniform sampler2D uTex;
uniform float uThreshold;
void main() {
    vec3 c = texture2D(uTex, vUv).rgb;
    float l = dot(c, vec3(0.299, 0.587, 0.114));
    float k = smoothstep(uThreshold, 1.0, l);
    gl_FragColor = vec4(c * k, 1.0);
}
"""

    /** Разделяемое размытие по Гауссу (9 отсчётов). */
    val BLUR = """
$PRECISION
varying vec2 vUv;
uniform sampler2D uTex;
uniform vec2 uStep;
void main() {
    vec3 sum = texture2D(uTex, vUv).rgb * 0.227;
    sum += (texture2D(uTex, vUv + uStep * 1.385).rgb +
            texture2D(uTex, vUv - uStep * 1.385).rgb) * 0.316;
    sum += (texture2D(uTex, vUv + uStep * 3.231).rgb +
            texture2D(uTex, vUv - uStep * 3.231).rgb) * 0.070;
    gl_FragColor = vec4(sum, 1.0);
}
"""

    /** Основной "лук": аберрация, шлейфы, свечение, грейд, виньетка, зерно, вспышки. */
    val LOOK = """
$PRECISION
varying vec2 vUv;
uniform sampler2D uTex;
uniform sampler2D uTrail;
uniform sampler2D uBloom;
uniform float uAspect;
uniform float uRgb;
uniform float uEcho;
uniform float uGlow;
uniform float uSat;
uniform float uContrast;
uniform vec3 uShadowTint;
uniform vec3 uHighTint;
uniform float uGradeAmount;
uniform float uVignette;
uniform float uGrain;
uniform float uFlash;
uniform float uInvert;
uniform float uTime;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    vec2 dir = (vUv - 0.5);
    vec3 col;
    if (uRgb > 0.0005) {
        vec2 off = dir * uRgb * 0.06;
        col.r = texture2D(uTex, clamp(vUv + off, 0.0, 1.0)).r;
        col.g = texture2D(uTex, vUv).g;
        col.b = texture2D(uTex, clamp(vUv - off, 0.0, 1.0)).b;
    } else {
        col = texture2D(uTex, vUv).rgb;
    }

    if (uEcho > 0.0005) {
        vec3 trail = texture2D(uTrail, vUv).rgb;
        col = max(col, trail * uEcho);
    }
    if (uGlow > 0.0005) {
        col += texture2D(uBloom, vUv).rgb * uGlow * 1.6;
    }

    col = (col - 0.5) * uContrast + 0.5;
    float l = dot(clamp(col, 0.0, 1.0), vec3(0.299, 0.587, 0.114));
    col = mix(vec3(l), col, uSat);
    // Тонируем только тени и только света: иначе середина яркости получает
    // обе тонировки сразу и кадр уезжает в сплошной цвет.
    float ws = (1.0 - l) * (1.0 - l);
    float wh = l * l;
    col += (uShadowTint * ws + uHighTint * wh) * uGradeAmount;

    if (uVignette > 0.0005) {
        vec2 vp = dir * vec2(uAspect, 1.0);
        float d = length(vp) * 1.25;
        col *= 1.0 - uVignette * smoothstep(0.42, 1.15, d);
    }
    if (uGrain > 0.0005) {
        float n = hash(vUv * vec2(1024.0, 1024.0) + uTime) - 0.5;
        col += n * uGrain * 0.22;
    }
    if (uInvert > 0.0005) {
        col = mix(col, vec3(1.0) - col, uInvert);
    }
    col = mix(col, vec3(1.0), uFlash);
    gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
"""

    /** Переходы между шотами. uProgress: 0 — начало перехода, 1 — конец. */
    val TRANSITION = """
$PRECISION
varying vec2 vUv;
uniform sampler2D uPrev;
uniform sampler2D uCur;
uniform int uType;
uniform float uProgress;
uniform float uSeed;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

vec3 dirBlur(sampler2D tex, vec2 uv, vec2 dir, float amount) {
    vec3 sum = vec3(0.0);
    for (int i = 0; i < 6; i++) {
        float t = float(i) / 5.0;
        sum += texture2D(tex, clamp(uv + dir * amount * t, 0.0, 1.0)).rgb;
    }
    return sum / 6.0;
}

vec3 radialBlur(sampler2D tex, vec2 uv, float zoom, float amount) {
    vec3 sum = vec3(0.0);
    for (int i = 0; i < 6; i++) {
        float t = float(i) / 5.0;
        float z = mix(1.0, zoom, t * amount);
        vec2 p = (uv - 0.5) / z + 0.5;
        sum += texture2D(tex, clamp(p, 0.0, 1.0)).rgb;
    }
    return sum / 6.0;
}

void main() {
    float pr = clamp(uProgress, 0.0, 1.0);
    vec3 prev = texture2D(uPrev, vUv).rgb;
    vec3 cur = texture2D(uCur, vUv).rgb;
    vec3 outc;

    if (uType == 1) {
        // Вспышка: белый удар на стыке.
        outc = pr < 0.5 ? prev : cur;
        outc = mix(outc, vec3(1.0), sin(pr * 3.14159) * 0.85);
    } else if (uType == 2 || uType == 3 || uType == 4 || uType == 5) {
        // Вип-пан: смаз в сторону движения.
        vec2 d = vec2(1.0, 0.0);
        if (uType == 3) d = vec2(-1.0, 0.0);
        if (uType == 4) d = vec2(0.0, 1.0);
        if (uType == 5) d = vec2(0.0, -1.0);
        vec3 a = dirBlur(uPrev, vUv + d * pr * 0.65, d, 0.35 * (1.0 - pr) + 0.15);
        vec3 b = dirBlur(uCur, vUv - d * (1.0 - pr) * 0.65, d, 0.35 * pr + 0.15);
        outc = mix(a, b, smoothstep(0.35, 0.65, pr));
    } else if (uType == 6) {
        // Зум-смаз.
        vec3 a = radialBlur(uPrev, vUv, 1.0 + 0.9 * pr, 1.0);
        vec3 b = radialBlur(uCur, vUv, 1.0 - 0.45 * (1.0 - pr), 1.0);
        outc = mix(a, b, smoothstep(0.3, 0.7, pr));
        outc += vec3(0.12) * sin(pr * 3.14159);
    } else if (uType == 7) {
        // Глитч: блочный сдвиг строк + rgb-развал.
        float rows = 14.0;
        float row = floor(vUv.y * rows);
        float shift = (hash(vec2(row, uSeed + floor(pr * 5.0))) - 0.5) * 0.5 * (1.0 - abs(pr - 0.5) * 2.0);
        vec2 guv = clamp(vUv + vec2(shift, 0.0), 0.0, 1.0);
        vec3 src = pr < 0.5 ? texture2D(uPrev, guv).rgb : texture2D(uCur, guv).rgb;
        float ca = 0.02 * (1.0 - abs(pr - 0.5) * 2.0);
        if (pr < 0.5) {
            src.r = texture2D(uPrev, clamp(guv + vec2(ca, 0.0), 0.0, 1.0)).r;
            src.b = texture2D(uPrev, clamp(guv - vec2(ca, 0.0), 0.0, 1.0)).b;
        } else {
            src.r = texture2D(uCur, clamp(guv + vec2(ca, 0.0), 0.0, 1.0)).r;
            src.b = texture2D(uCur, clamp(guv - vec2(ca, 0.0), 0.0, 1.0)).b;
        }
        outc = src;
    } else if (uType == 8) {
        // Плавное растворение.
        outc = mix(prev, cur, smoothstep(0.0, 1.0, pr));
    } else if (uType == 9) {
        // Прокрутка кадра.
        float ang = (1.0 - pr) * 1.2;
        float c = cos(ang);
        float s = sin(ang);
        vec2 p = vUv - 0.5;
        vec2 rp = vec2(p.x * c - p.y * s, p.x * s + p.y * c) / mix(0.6, 1.0, pr) + 0.5;
        vec3 b = texture2D(uCur, clamp(rp, 0.0, 1.0)).rgb;
        outc = mix(prev, b, smoothstep(0.1, 0.5, pr));
    } else if (uType == 10) {
        // Шторки.
        float bands = 8.0;
        float b = fract(vUv.y * bands);
        float edge = step(b, pr);
        outc = mix(prev, cur, edge);
        outc = mix(outc, vec3(1.0), 0.25 * sin(pr * 3.14159));
    } else {
        outc = cur;
    }
    gl_FragColor = vec4(clamp(outc, 0.0, 1.0), 1.0);
}
"""
}
