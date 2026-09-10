"""保存公开来源正文和访问记录；只负责取证，不自动把抓取成功视为已审阅。"""
from pathlib import Path
from html.parser import HTMLParser
import concurrent.futures, hashlib, json, re, sys, time
import requests

HERE = Path(__file__).resolve().parent

class TextParser(HTMLParser):
    def __init__(self):
        super().__init__(); self.parts=[]; self.skip=0
    def handle_starttag(self, tag, attrs):
        if tag in ('script','style','noscript','svg'): self.skip+=1
        if not self.skip and tag in ('p','li','h1','h2','h3','h4','dt','dd','div','tr','br'): self.parts.append('\n')
    def handle_endtag(self, tag):
        if tag in ('script','style','noscript','svg') and self.skip: self.skip-=1
        if not self.skip and tag in ('p','li','h1','h2','h3','h4','dt','dd','div','tr'): self.parts.append('\n')
    def handle_data(self, s):
        if not self.skip: self.parts.append(s)

def fetch(row):
    path=HERE/'bodies'/f'{row["id"]}.txt'; path.parent.mkdir(exist_ok=True)
    try:
        r=requests.get(row['url'], timeout=28, headers={'User-Agent':'Mozilla/5.0 (compatible; research reading)'})
        r.raise_for_status()
        if r.content.startswith(b'%PDF'):
            from pypdf import PdfReader
            from io import BytesIO
            doc=PdfReader(BytesIO(r.content)); content='\n'.join(p.extract_text() or '' for p in doc.pages)
            (HERE/'bodies'/f'{row["id"]}.pdf').write_bytes(r.content)
        else:
            raw=r.content.decode('utf-8',errors='replace')
            if any(d in row['url'] for d in ('sidefx.com/docs/','docs.unity3d.com','docs.blender.org','pbr-book.org','help.maxon.net')):
                start=raw.find('<h1'); raw=raw[start:] if start>=0 else raw
            p=TextParser(); p.feed(raw)
            content='\n'.join(s for s in (re.sub(r'\s+',' ',x).strip() for x in ''.join(p.parts).splitlines()) if s)
            if 'help.maxon.net' in row['url']:
                start=content.rfind('\nParticular\n')
                if start>=0: content=content[start:]
        path.write_text(content,encoding='utf-8')
        meta={**row,'status':'retrieved','final_url':r.url,'retrieved_at':time.strftime('%Y-%m-%d %H:%M:%S%z'),'chars':len(content),'sha256':hashlib.sha256(content.encode()).hexdigest()}
    except Exception as e:
        meta={**row,'status':'failed','error':str(e)}
    return meta

def main():
    rows=json.loads((HERE/sys.argv[1]).read_text(encoding='utf-8'))
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool: results=list(pool.map(fetch,rows))
    (HERE/(Path(sys.argv[1]).stem+'-retrieval.json')).write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
    for r in results:
        print(r['id'],r['status'],r.get('chars',r.get('error')))

if __name__=='__main__': main()
