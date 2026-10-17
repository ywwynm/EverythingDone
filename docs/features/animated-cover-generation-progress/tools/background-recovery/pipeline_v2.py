import concurrent.futures
import json
import subprocess
import time
from matrix import run
from run import ROOT, DEVICES, PKG, REMOTE, ADB, adb, dismiss_compatibility

def pipeline(name):
    serial=DEVICES[name]
    print(name,adb(serial,'install','-r',str(ROOT/'wm2105-isolated.apk'),timeout=120).strip(),flush=True)
    for mode in ('death-green','final-switch','cancel-finalizing'):
        run(name,'wm2105-'+mode)
    out=ROOT/('wm2105-regression-'+name);out.mkdir(exist_ok=True)
    with (out/'instrumentation.txt').open('wb') as log:
        proc=subprocess.Popen([ADB,'-s',serial,'shell','am','instrument','-w',PKG+'/com.ywwynm.everythingdone.covergeneration.CoverGenerationProbe'],stdout=log,stderr=subprocess.STDOUT)
        for _ in range(3):
            if proc.poll() is not None:break
            time.sleep(2)
            dismiss_compatibility(serial)
        proc.wait(timeout=240)
    adb(serial,'pull',REMOTE+'/cover-generation-checks',str(out),timeout=60)
    result=json.loads((out/'cover-generation-checks'/'result.json').read_text(encoding='utf-8'))
    print(name,'regression',result.get('ok'),len(result.get('checks',[])),result.get('error',''),flush=True)
    assert result.get('ok')

failed=False
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    futures={pool.submit(pipeline,name):name for name in DEVICES}
    for future in concurrent.futures.as_completed(futures):
        try:future.result()
        except Exception as exc:
            print(futures[future],'FAILED',repr(exc),flush=True)
            failed=True
raise SystemExit(1 if failed else 0)
