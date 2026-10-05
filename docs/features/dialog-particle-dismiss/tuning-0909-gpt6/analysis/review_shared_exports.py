"""从已经编码的完整画面视频抽取中、后段，核对最终成片而非离线局部截图。"""
from pathlib import Path
import json
import cv2
from PIL import Image
HERE=Path(__file__).resolve().parents[1]


def main():
    manifest=json.loads((HERE/'videos/shared-field-manifest.json').read_text('utf-8'))
    out=HERE/'analysis/match-cache/export-review';out.mkdir(parents=True,exist_ok=True)
    for v in manifest['videos']:
        if v['kind']!='comparison' or v['rate']!=1:continue
        cap=cv2.VideoCapture(str(HERE/'videos'/v['file']));parts=[]
        for p in [.28,.56,.78]:
            cap.set(cv2.CAP_PROP_POS_MSEC,(.35+p)*1000);ok,frame=cap.read();assert ok,v['file']
            frame=cv2.cvtColor(frame,cv2.COLOR_BGR2RGB);im=Image.fromarray(frame);im.thumbnail((990,690),Image.Resampling.LANCZOS);parts.append(im)
        sheet=Image.new('RGB',(parts[0].width,sum(im.height for im in parts)))
        y=0
        for im in parts:sheet.paste(im,(0,y));y+=im.height
        sheet.save(out/f'{v["scene"]}.jpg',quality=94);cap.release()
    print(out)


if __name__=='__main__':main()
