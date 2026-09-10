"""输出正文的前段和关键词附近证据，完整正文仍保留在 bodies。"""
from pathlib import Path
import sys,re
root=Path(__file__).resolve().parent/'bodies'
for arg in sys.argv[1:]:
    parts=arg.split(':'); id=parts[0]; p=root/(id+'.txt')
    print('\nSOURCE',id)
    if not p.exists(): print('MISSING'); continue
    s=p.read_text(encoding='utf-8')
    # 排除节点目录索引，保留正文。正文关键词由审阅者指定。
    print(s[:1500])
    for word in parts[1:]:
        m=re.search(re.escape(word),s,re.I)
        if m: print('\nMATCH',word,'\n',s[max(0,m.start()-90):m.start()+850])
