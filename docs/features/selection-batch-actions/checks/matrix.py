import subprocess, sys, json
from pathlib import Path
root=next(p for p in Path(__file__).resolve().parents if (p / 'gradlew.bat').is_file()) / 'tmp/selection-transition-20261013'
serial,name=sys.argv[1:3]
cases=[
 ('finish','root','top','visible','DRIFT',True),
 ('restore','folder','middle','mixed','FLOW',True),
 ('delete','root','bottom','mixed','DRIFT',True),
 ('forever','folder','bottom','offscreen','FLOW',True),
 ('untrash','root','middle','visible','FLOW',True),
 ('finish','folder','bottom','visible','FLOW',True),
 ('restore','root','top','mixed','DRIFT',True),
 ('delete','folder','middle','offscreen','DRIFT',True),
 ('forever','root','top','all','FLOW',True),
 ('untrash','folder','top','all','DRIFT',True),
 ('finish','root','middle','offscreen','FLOW',True),
 ('restore','folder','bottom','visible','DRIFT',False),
]
start=int(sys.argv[3]) if len(sys.argv)>3 else 0
end=int(sys.argv[4]) if len(sys.argv)>4 else len(cases)
for i, (action,scope,scroll,selection,style,particles) in enumerate(cases[start:end],start):
    run=f'matrix-{name}-{i:02d}'
    command=[sys.executable,'-X','utf8',str(Path(__file__).resolve().parent/'run_case.py'),serial,run,
        f'action={action}',f'scope={scope}',f'scroll={scroll}',f'selection={selection}',f'style={style}',
        'complex=true',f'particles={str(particles).lower()}']
    subprocess.run(command,check=True,timeout=140)
    path=root/run/'result.json'
    data=json.loads(path.read_text()) if path.exists() else {'ok':False,'error':'no result'}
    (root/run/'case.json').write_text(json.dumps(dict(action=action,scope=scope,scroll=scroll,selection=selection,style=style,particles=particles)))
    print(run,data.get('ok'),flush=True)
    if not data.get('ok'): sys.exit(1)
