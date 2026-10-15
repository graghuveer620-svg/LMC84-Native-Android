precision highp float;
uniform sampler2D uTexSampler;
uniform float uTexelX;
uniform float uTexelY;
uniform float uContrast;
uniform float uSaturation;
uniform float uBrightness;
uniform float uWarmth;
uniform float uVignette;
uniform float uTexture;
uniform float uSharpen;
uniform float uClarity;
varying vec2 vTexSamplingCoord;

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

void main() {
    vec2 uv = vTexSamplingCoord;
    vec4 center = texture2D(uTexSampler, uv);
    vec3 c = center.rgb;
    vec2 tx = vec2(uTexelX, 0.0);
    vec2 ty = vec2(0.0, uTexelY);
    vec3 n = texture2D(uTexSampler, uv - ty).rgb;
    vec3 s = texture2D(uTexSampler, uv + ty).rgb;
    vec3 e = texture2D(uTexSampler, uv + tx).rgb;
    vec3 w = texture2D(uTexSampler, uv - tx).rgb;
    vec3 nw = texture2D(uTexSampler, uv - tx - ty).rgb;
    vec3 ne = texture2D(uTexSampler, uv + tx - ty).rgb;
    vec3 sw = texture2D(uTexSampler, uv - tx + ty).rgb;
    vec3 se = texture2D(uTexSampler, uv + tx + ty).rgb;

    vec3 crossBlur = (n + s + e + w) * 0.25;
    vec3 diagBlur = (nw + ne + sw + se) * 0.25;
    vec3 localMin = min(c, min(min(n,s), min(e,w)));
    vec3 localMax = max(c, max(max(n,s), max(e,w)));
    float edgeGuard = 1.0 - clamp(luma(localMax-localMin) * 1.6, 0.0, 0.85);
    c += (c-crossBlur) * (uSharpen * 1.8) * edgeGuard;
    c += (crossBlur-diagBlur) * (uTexture * 2.6);
    float lum = luma(c);
    float midMask = 4.0 * lum * (1.0-lum);
    c += (c-diagBlur) * uClarity * midMask * 1.5;

    c += uBrightness;
    c = (c - 0.5) * uContrast + 0.5;
    float newLum = luma(c);
    c = mix(vec3(newLum), c, uSaturation);
    c.r += uWarmth * 0.14;
    c.g += uWarmth * 0.04;
    c.b -= uWarmth * 0.14;

    vec2 v = uv * (1.0-uv.yx);
    float vig = max(v.x*v.y*15.0, 0.0001);
    vig = pow(vig, uVignette);
    c *= clamp(vig, 0.0, 1.0);
    gl_FragColor = vec4(clamp(c, 0.0, 1.0), center.a);
}
