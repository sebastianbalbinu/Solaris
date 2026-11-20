import moderngl, numpy as np, re, time, sys
from PIL import Image
PRE = """#version 330
#define float2 vec2
#define float3 vec3
#define float4 vec4
#define half float
#define half2 vec2
#define half3 vec3
#define half4 vec4
out vec4 fragColor;
"""
ctx = moderngl.create_standalone_context(backend='egl')
def prog(src):
    src = src.replace('half4 main(float2 fragCoord)', 'half4 main_(float2 fragCoord)')
    # agsl fragCoord origin top-left
    fs = PRE + src + "\nvoid main(){ vec2 fc = vec2(gl_FragCoord.x, res.y - gl_FragCoord.y); fragColor = main_(fc);}"
    vs = "#version 330\nin vec2 p; void main(){gl_Position=vec4(p,0,1);}"
    return ctx.program(vertex_shader=vs, fragment_shader=fs)
_cache = {}
def render(src, w, h, u, reps=1):
    k = (hash(src), w, h)
    if k not in _cache:
        p = prog(src)
        vbo = ctx.buffer(np.array([-1,-1,1,-1,-1,1,1,1],'f4').tobytes())
        _cache[k] = (p, ctx.simple_vertex_array(p, vbo, 'p'), ctx.simple_framebuffer((w,h)))
    p, vao, fbo = _cache[k]
    fbo.use()
    for k,v in dict(u, res=(w,h)).items():
        if k in p: p[k].value = v
    t0=time.time()
    for _ in range(reps):
        vao.render(moderngl.TRIANGLE_STRIP); ctx.finish()
    dt=(time.time()-t0)/reps
    img = np.frombuffer(fbo.read(components=3), np.uint8).reshape(h,w,3)[::-1]
    return Image.fromarray(img), dt
