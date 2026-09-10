"""仅在本机提供审阅页；不对局域网或公网开放。"""
import json,os,sys,re
from pathlib import Path
from http.server import ThreadingHTTPServer,SimpleHTTPRequestHandler
HERE=Path(__file__).resolve().parent
class Handler(SimpleHTTPRequestHandler):
    def __init__(self,*args,**kwargs):super().__init__(*args,directory=str(HERE/'videos'),**kwargs)
    def log_message(self,*args):pass
    def send_head(self):
        path=Path(self.translate_path(self.path)).resolve()
        if not path.is_relative_to((HERE/'videos').resolve()):
            self.send_error(403);return None
        if not path.is_file():return super().send_head()
        f=path.open('rb');size=path.stat().st_size;start,end=0,size-1
        value=self.headers.get('Range');self.byte_range=None
        if value:
            match=re.fullmatch(r'bytes=(\d*)-(\d*)',value.strip())
            if not match or not any(match.groups()):
                f.close();self.send_error(416);return None
            a,b=match.groups()
            if a:start=int(a);end=min(int(b) if b else size-1,size-1)
            else:start=max(0,size-int(b))
            if start>=size or end<start:
                f.close();self.send_response(416);self.send_header('Content-Range',f'bytes */{size}');self.end_headers();return None
            self.byte_range=(start,end);f.seek(start)
        self.send_response(206 if value else 200)
        self.send_header('Content-Type',self.guess_type(str(path)))
        self.send_header('Accept-Ranges','bytes')
        self.send_header('Content-Length',str(end-start+1))
        if value:self.send_header('Content-Range',f'bytes {start}-{end}/{size}')
        self.send_header('Last-Modified',self.date_time_string(path.stat().st_mtime));self.end_headers()
        return f
    def copyfile(self,source,outputfile):
        remain=self.byte_range[1]-self.byte_range[0]+1 if self.byte_range else None
        try:
            while remain is None or remain>0:
                data=source.read(min(remain,262144) if remain is not None else 262144)
                if not data:break
                outputfile.write(data)
                if remain is not None:remain-=len(data)
        except (BrokenPipeError,ConnectionResetError,ConnectionAbortedError):pass
server=ThreadingHTTPServer(('127.0.0.1',int(sys.argv[1]) if len(sys.argv)>1 else 0),Handler)
info={'pid':os.getpid(),'port':server.server_address[1],'url':f'http://127.0.0.1:{server.server_address[1]}/index.html'}
(HERE/'analysis/gallery-server.json').write_text(json.dumps(info),encoding='utf-8')
server.serve_forever()
