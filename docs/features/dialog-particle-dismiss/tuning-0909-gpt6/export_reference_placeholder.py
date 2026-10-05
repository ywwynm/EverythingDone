"""桌面预览和导出共同使用的参考缺帧提示。"""
import numpy as np
from PIL import Image,ImageDraw,ImageFont


def ended_reference(frame):
    im=Image.new('RGB',tuple(frame),(14,20,30));d=ImageDraw.Draw(im)
    large=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',23)
    small=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18)
    d.text((30,im.height//2),'原始录像到此结束',font=large,fill=(232,239,248))
    d.text((30,im.height//2+44),'后续没有参考帧，不能判断是否一致',font=small,fill=(170,189,207))
    return np.asarray(im)
