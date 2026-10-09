"""解析 ART v3 双时钟采样；仅用于调用归因，不将采样时长作为干净帧性能。"""
import json
import struct
from collections import defaultdict
from recheck_20261009 import OUT, save

d=OUT/'meizu/java-sampling'
b=(d/'methods.trace').read_bytes();end=b.index(b'*end\n')+5
methods={};in_methods=False
for line in b[:end].decode('utf-8','replace').splitlines():
    if line=='*methods': in_methods=True;continue
    if line.startswith('*'): in_methods=False
    if in_methods:
        p=line.split('\t');methods[int(p[0],16)]='.'.join(p[1:3])
magic,version,off,start,record=struct.unpack_from('<4sHHQH',b,end)
assert magic==b'SLOW' and version==3 and record==14
stack=[];prev=None;inclusive=defaultdict(float);exclusive=defaultdict(float);counts=defaultdict(int)
for pos in range(end+off,len(b)-record+1,record):
    tid,mid,cpu,wall=struct.unpack_from('<HIII',b,pos)
    if tid!=1:continue
    if prev is not None and stack:exclusive[stack[-1][0]]+=wall-prev
    prev=wall;act=mid&3;mid&=~3
    if act==0:stack.append((mid,wall));counts[mid]+=1
    elif act in (1,2):
        if stack and stack[-1][0]==mid:
            _,t=stack.pop();inclusive[mid]+=wall-t
        else:
            while stack:
                m,t=stack.pop();inclusive[m]+=wall-t
                if m==mid:break
def rows(m,app=False):
    return [{'name':methods.get(k,hex(k)),'ms':round(v/1000,2),'sampled_entries':counts[k]} for k,v in sorted(m.items(),key=lambda kv:-kv[1]) if not app or 'everythingdone' in methods.get(k,'')][:35]
result={'format_version':version,'start_us':start,'application_inclusive':rows(inclusive,True),'all_exclusive':rows(exclusive)}
save(d/'method-summary.json',json.dumps(result,ensure_ascii=False,indent=2))
print(json.dumps(result,ensure_ascii=False,indent=2))
