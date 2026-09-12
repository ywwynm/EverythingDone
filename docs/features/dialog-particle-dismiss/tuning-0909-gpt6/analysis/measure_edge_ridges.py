"""在用户标记附近检测双侧均稀疏的窄亮脊；每个版本独立搜索，不能仅检查原像素。"""
from pathlib import Path
import sys,json
import cv2,numpy as np
from scipy.ndimage import gaussian_filter,maximum_filter,label
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from probe_edge_support import OUT
LINES={
 'down':[
   [[371,575],[405,650],[443,732]],
   [[601,560],[537,611],[451,662]],
 ],
 'up':[[[291,960],[330,978],[403,974]]],
 'right':[
   [[192,744],[238,665],[335,542]],
   [[236,999],[297,985],[374,979]],
   [[351,930],[398,970],[484,991],[565,1031]],
 ],
}

def ridge_response(frame):
    lum=frame.astype('float32').mean(2)
    y,x=np.mgrid[:lum.shape[0],:lum.shape[1]].astype('float32');ky,kx=np.mgrid[-35:36,-35:36]
    response=np.zeros_like(lum)
    for angle in np.linspace(0,np.pi,18,endpoint=False):
        nx,ny=np.cos(angle),np.sin(angle);normal=kx*nx+ky*ny;tangent=-kx*ny+ky*nx
        # 沿线平均约 50 像素，避免把独立颗粒或小团误判为用户所指的细流。
        kernel=np.exp(-.5*((normal/2.5)**2+(tangent/12.)**2));kernel=(kernel/kernel.sum()).astype('float32')
        smooth=cv2.filter2D(lum,-1,kernel)
        side0=cv2.remap(smooth,(x+nx*13).astype('float32'),(y+ny*13).astype('float32'),cv2.INTER_LINEAR,borderMode=cv2.BORDER_REFLECT)
        side1=cv2.remap(smooth,(x-nx*13).astype('float32'),(y-ny*13).astype('float32'),cv2.INTER_LINEAR,borderMode=cv2.BORDER_REFLECT)
        response=np.maximum(response,smooth-np.maximum(side0,side1))
    return response

def measures(frame,curves):
    response=ridge_response(frame);rows=[];roi=np.zeros(response.shape,'uint8')
    for curve in curves:
        band=np.zeros(response.shape,'uint8');cv2.polylines(band,[np.array(curve,'int32')],False,1,65)
        keep=band.astype(bool);roi|=band;values=response[keep]
        rows.append(dict(p99=float(np.percentile(values,99)),above_4=int(np.sum(values>4)),above_6=int(np.sum(values>6)),max=float(values.max())))
    return rows,response*roi

def main():
    kinds=['baseline','no-peel','no-guide','no-local-clock','no-inertia','no-curl'];result={}
    for name,curves in LINES.items():
        panels=[];result[name]={}
        for kind in kinds:
            frame=np.load(OUT/name/f'ablate-{kind}.npy',mmap_mode='r')[1]
            data,response=measures(frame,curves);result[name][kind]=data
            im=Image.fromarray(frame).crop((60,490,680,1130));overlay=ImageDraw.Draw(im)
            for curve in curves:overlay.line([(x-60,y-490) for x,y in curve],fill=(255,175,70),width=1)
            heat=np.clip(response/12*255,0,255).astype('uint8');color=cv2.applyColorMap(heat,cv2.COLORMAP_INFERNO)[:,:,::-1]
            color[response<2]=np.asarray(frame)[response<2]*.35
            panels.append((kind,im,Image.fromarray(color).crop((60,490,680,1130))))
        sheet=Image.new('RGB',(len(kinds)*310,680),'#101620');d=ImageDraw.Draw(sheet);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',16)
        for i,(title,im,heat) in enumerate(panels):
            d.text((310*i+4,0),title,font=font,fill='white');sheet.paste(im.resize((310,320)),(310*i,30));sheet.paste(heat.resize((310,320)),(310*i,355))
        sheet.save(OUT/name/'ridge-detection.png')
    (OUT/'ridge-ablation.json').write_text(json.dumps(result,indent=2),'utf-8');print(json.dumps(result),flush=True)
if __name__=='__main__':main()
