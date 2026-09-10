"""汇总已人工审阅的来源；抓取成功的候选不会自动纳入有效数。"""
from pathlib import Path
from urllib.parse import urlparse
from collections import Counter
import json

HERE=Path(__file__).resolve().parent
ROOT=next(p for p in HERE.parents if (p/'gradlew.bat').is_file())
catalog={}
retrieval={}
for stem in ['houdini','engines','aftereffects','film-animation','render-physics','corrections']:
    for row in json.loads((HERE/(stem+'.json')).read_text(encoding='utf-8')): catalog[row['id']]=row
    for row in json.loads((HERE/(stem+'-retrieval.json')).read_text(encoding='utf-8')): retrieval[row['id']]=row
reviews=json.loads((HERE/'reviewed.json').read_text(encoding='utf-8'))
known_dates={'R01':'2007','R02':'2008','R03':'2008','R04':'2017','R05':'2013','R06':'2022','R07':'2007','R23':'2005','R24':'2007','R26':'2015-03-26'}
publishers={'www.sidefx.com':'SideFX','docs.unity3d.com':'Unity','docs.blender.org':'Blender','help.maxon.net':'Maxon','www.maxon.net':'Maxon','helpx.adobe.com':'Adobe','www.videocopilot.net':'Video Copilot','www.rebelway.net':'Rebelway','www.artofvfx.com':'Art of VFX／Weta 制作主管访谈','www.animationmentor.com':'Animation Mentor','www.cs.ubc.ca':'UBC／论文作者','www.cs.cornell.edu':'Cornell／论文作者','research.nvidia.com':'NVIDIA／论文作者','jcgt.org':'JCGT／论文作者','developer.nvidia.com':'NVIDIA GPU Gems','iquilezles.org':'Inigo Quilez','pbr-book.org':'PBRT 作者','www1.grc.nasa.gov':'NASA Glenn','dragonfly.tam.cornell.edu':'Cornell／论文作者','casual-effects.blogspot.com':'Morgan McGuire'}
rows=[]
for id,fact,use in reviews:
    c=catalog[id]; ev=retrieval.get(id,{})
    source_mode='本地正文' if ev.get('status')=='retrieved' else 'web 正文（抓取器受限）'
    rows.append({**c,'publisher':publishers.get(urlparse(c['url']).netloc,urlparse(c['url']).netloc),'published':known_dates.get(id,'页面未核定发布日期'),'accessed':'2026-09-09','evidence':source_mode,'fact':fact,'application':use,'retrieval':ev})
assert len(rows)>=100
assert len({r['url'] for r in rows})==len(rows)
hashes=Counter(r['retrieval'].get('sha256') for r in rows if r['retrieval'].get('sha256'))
assert not [h for h,n in hashes.items() if n>1], '需核查重复正文'
(HERE/'sources.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf-8')
md=['# 2026-09-09 粒子消散调研来源台账','',f'本轮实际审阅 **{len(rows)} 个不同 URL 的有效来源**，访问日期均为 2026-09-09。这里的来源指独立文档、论文或制作访谈，**不等于 107 家不同机构，也不等于 107 项独立实验**。重复 URL、正文缺失、重定向首页和仅有标题的候选不计数。','',
'以官方文档、论文作者原文和制作主管访谈为主。表中“来源信息”是阅读所得，“本轮价值”是用于本项目的判断；官方软件能力不能证明华为内部采用同一实现。发布日期未核定的网页明确保留此状态，不以抓取日期充当发布日期。','',
'完整正文、网页检索证据、抓取时间与散列保存在 `docs/features/dialog-particle-dismiss/tuning-0909-gpt6/research/`；本目录仅保留轻量总结。','']
names={'H':'Houdini：释放、受力与属性','E':'实时引擎与 Blender：粒子生命周期和渲染','A':'After Effects、Trapcode 与制作教程','F':'影视制作与动画设计','R':'流体、采样、材质及合成研究'}
last=''
for r in rows:
    if r['id'][0]!=last:
        last=r['id'][0];md+=['## '+names[last],'','| 编号与来源 | 来源信息 | 本轮价值 |','| --- | --- | --- |']
    meta=f"{r['publisher']}；{r['published']}"
    md.append(f"| {r['id']} · [{r['title']}]({r['url']})<br>{meta} | {r['fact']} | {r['application']} |")
md+=['','## 未计入的候选','',
'E21、E22：地址无有效正文；A18：未完成正文审阅；F02、F03：获取内容不足以形成有效制作结论；F04：访问受限且未核实正文；R08：跳转到机构首页；R25：文件失效。F11 已纠正页面地址并重新取得正文。','',
'不同文档有相近术语，台账只计页面一次；模型设计按问题归纳，不把同一结论重复出现的次数当成证据强度。']
out=ROOT/'docs/features/dialog-particle-dismiss/research-2026-09-09-sources.md'
out.write_text('\n'.join(md)+'\n',encoding='utf-8')
print(json.dumps({'reviewed':len(rows),'counts':dict(Counter(r['id'][0] for r in rows)),'bytes':out.stat().st_size,'web_evidence':[r['id'] for r in rows if r['evidence'].startswith('web')]},ensure_ascii=False))
