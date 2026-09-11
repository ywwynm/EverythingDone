"""演示触点从素材实际可触摸区域生成；不把视频边框或屏幕外当触点。"""
import math
import numpy as np


def distance_cases(meta, requested_direction):
    x,y,x1,y1=meta['rect'];w=x1-x;h=y1-y;span=min(w,h)
    left,top,right,bottom=meta['touch_rect']
    cx,cy=(x+x1)*.5,(y+y1)*.5
    if requested_direction==90:far=np.array([cx,top+3.])
    elif requested_direction==135:far=np.array([left+3.,top+3.])
    else:raise ValueError('距离验收只定义上方和左上方')
    centre=np.array([cx,cy]);vector=far-centre;length=float(np.linalg.norm(vector));unit=vector/length
    edge=min(w/(2*max(abs(unit[0]),1e-9)),h/(2*max(abs(unit[1]),1e-9)))
    free=length-edge
    if free<=4:raise ValueError('该方向没有足够的屏幕内背景空间')
    angle=math.degrees(math.atan2(-unit[1],unit[0]))%360
    rows=[]
    for label,distance in zip(['近','中','远'],[min(span*.025,free*.15),free*.52,free*.94]):
        point=centre+unit*(edge+distance)
        assert left<=point[0]<=right and top<=point[1]<=bottom
        assert not (x<=point[0]<=x1 and y<=point[1]<=y1)
        rows.append(dict(label=label,angle=angle,gap=distance/span,point=point.tolist(),distance_px=distance))
    return rows


def distance_view_bounds(meta, requested_direction=None):
    x,y,x1,y1=meta['rect'];span=min(x1-x,y1-y)
    directions=[135,90] if requested_direction is None else [requested_direction]
    points=np.array([r['point'] for angle in directions for r in distance_cases(meta,angle)])
    # 保留原视频左右边框，裁取的上下范围也始终在原帧内。
    top=max(0,math.floor(min(y-span*.18,points[:,1].min()-span*.12)))
    bottom=min(meta['frame'][1],math.ceil(y1+span*.24))
    bottom-= (bottom-top)%2
    return 0,top,meta['frame'][0],bottom
