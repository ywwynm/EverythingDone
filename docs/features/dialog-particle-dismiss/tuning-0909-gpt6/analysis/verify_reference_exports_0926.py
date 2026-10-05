"""核对每个导出文件、配对速度、输入快照和可解码性；不把容器通过视作视觉通过。"""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import collections,hashlib,json,subprocess,sys
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from export_reference_suite_0926 import fingerprint
OUT=HERE/'videos';DATA=HERE/'analysis/new-references-0926'
FFMPEG='C:/ffmpeg/bin/ffmpeg.exe';FFPROBE='C:/ffmpeg/bin/ffprobe.exe'


def inspect(item):
    file=OUT/item['file'];issues=[]
    command=[FFPROBE,'-v','error','-select_streams','v:0','-show_entries',
             'stream=codec_name,pix_fmt,width,height,r_frame_rate,nb_frames,duration','-of','json',str(file)]
    result=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',check=True)
    stream=json.loads(result.stdout)['streams'][0]
    for key in ['width','height']:
        if stream[key]!=item[key] or stream[key]%2:issues.append(f'{key} 不匹配')
    if stream['codec_name']!='h264' or stream['pix_fmt']!='yuv420p':issues.append('编码不匹配')
    if stream['r_frame_rate']!='60/1':issues.append('帧率不匹配')
    if int(stream['nb_frames'])!=item['frames']:issues.append('帧数不匹配')
    if abs(float(stream['duration'])-item['duration'])>.001:issues.append('时长不匹配')
    if file.stat().st_size!=item['bytes']:issues.append('文件大小不匹配')
    if not (OUT/item['poster']).is_file():issues.append('缺少封面')
    decoded=subprocess.run([FFMPEG,'-hide_banner','-v','error','-threads','1','-i',str(file),'-f','null','-'],
                           capture_output=True,text=True,encoding='utf-8')
    if decoded.returncode or decoded.stderr:issues.append('解码错误：'+decoded.stderr[-400:])
    return dict(file=item['file'],ok=not issues,issues=issues,sha256=hashlib.sha256(file.read_bytes()).hexdigest(),
                frames=int(stream['nb_frames']),duration=float(stream['duration']))


def main():
    manifest=json.loads((OUT/'new-references-manifest.json').read_text('utf-8'));items=manifest['videos']
    problems=[]
    if manifest['hash']!=fingerprint():problems.append('输出不是当前模型与素材')
    if len({q['file'] for q in items})!=len(items):problems.append('文件名重复')
    if any(q['code_hash']!=manifest['hash'] for q in items):problems.append('存在混合模型版本')
    pairs=collections.defaultdict(list)
    for q in items:pairs[(q['scene'],q['kind'])].append(q)
    for key,group in pairs.items():
        if sorted(q['rate'] for q in group)!=[.5,1.]:problems.append(f'{key} 缺少速度配对')
        if len(group)==2 and group[0].get('inputs')!=group[1].get('inputs'):problems.append(f'{key} 双速度输入不同')
    with ThreadPoolExecutor(max_workers=4) as pool:
        reports=[]
        for i,q in enumerate(pool.map(inspect,items),1):
            reports.append(q)
            if i%12==0:print(f'完整解码 {i}/{len(items)}',flush=True)
    output=dict(ok=not problems and all(q['ok'] for q in reports),problems=problems,
                videos=len(items),scenes=len({q['scene'] for q in items}),kinds=dict(collections.Counter(q['kind'] for q in items)),
                visual_fidelity_passed=False,model_hash=manifest['hash'],files=reports)
    (DATA/'video-checks.json').write_text(json.dumps(output,ensure_ascii=False,indent=2),'utf-8')
    # 本次成片真正使用的输入单独记录，避免与未采用的种子搜索试验混淆。
    used=[dict(scene=q['scene'],inputs=q['inputs']) for q in items if q['kind']=='comparison' and q['rate']==1.]
    (DATA/'active-inputs.json').write_text(json.dumps(dict(model_hash=manifest['hash'],rules=manifest['rules'],inputs=used,
        note='以最终成片清单为准；initial-seeds.json 包含后来未采用的搜索试验，不能当作当前输入。'),ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps({k:v for k,v in output.items() if k!='files'},ensure_ascii=False),flush=True)
    if not output['ok']:raise SystemExit(1)


if __name__=='__main__':main()
