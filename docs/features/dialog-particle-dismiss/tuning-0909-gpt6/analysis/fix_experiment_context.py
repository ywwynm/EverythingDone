"""全部同屏候选显式共用一个 GL 上下文，避免当前上下文随构造器切换。"""
from pathlib import Path
root=Path(__file__).resolve().parent
for name in ['experiment_coherence.py','experiment_material_depth.py','experiment_roll_transport.py','experiment_flow_detail.py']:
    p=root/name;s=p.read_text(encoding='utf-8')
    s=s.replace('from PIL import Image,ImageDraw,ImageFont','import moderngl\nfrom PIL import Image,ImageDraw,ImageFont')
    s=s.replace('    renderers=[]','    ctx=moderngl.create_standalone_context(require=430)\n    renderers=[]')
    s=s.replace('    rr=[]','    ctx=moderngl.create_standalone_context(require=430)\n    rr=[]')
    s=s.replace('mod.Renderer(name,quality=2)','mod.Renderer(name,quality=2,ctx=ctx)').replace('mod.Renderer(name)','mod.Renderer(name,ctx=ctx)').replace('r=Renderer(name)','r=Renderer(name,ctx=ctx)')
    s=s.replace("    print(name,flush=True)","    ctx.release()\n    print(name,flush=True)")
    p.write_text(s,encoding='utf-8')
