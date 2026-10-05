"""把验收产物整理成几张交付用的总图（JPEG），放在 tmp/harmony-particle-android-port/report/。"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[4] / 'tmp/harmony-particle-android-port'
OUT = ROOT / 'report'


def font(size):
    for name in ('C:/Windows/Fonts/msyh.ttc', 'C:/Windows/Fonts/simhei.ttf'):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    return ImageFont.load_default()


def titled(img, title, size=40):
    head = size + 24
    out = Image.new('RGB', (img.width, img.height + head), (255, 255, 255))
    out.paste(img, (0, head))
    ImageDraw.Draw(out).text((16, 10), title, fill=(20, 20, 20), font=font(size))
    return out


def fit(img, width):
    if img.width <= width:
        return img
    return img.resize((width, round(img.height * width / img.width)), Image.LANCZOS)


def side_by_side(paths, titles, width_each=560, gap=24):
    imgs = [fit(Image.open(p).convert('RGB'), width_each) for p in paths]
    imgs = [titled(i, t, 28) for i, t in zip(imgs, titles)]
    h = max(i.height for i in imgs)
    out = Image.new('RGB', (sum(i.width for i in imgs) + gap * (len(imgs) - 1), h), (255, 255, 255))
    x = 0
    for i in imgs:
        out.paste(i, (x, 0))
        x += i.width + gap
    return out


def save(img, name, quality=85):
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / name
    img.save(path, quality=quality, optimize=True)
    print(path, img.size, path.stat().st_size // 1024, 'KB')


def sheet(path, title, width=2400):
    return titled(fit(Image.open(path).convert('RGB'), width), title)


def main():
    save(side_by_side([ROOT / 'final-a/01-page-drift.png', ROOT / 'final-a/07-page-flow.png'],
                      ['选「飘散」：只剩「粒子数量」', '选「流体」：原来四项回来']), '01-settings.jpg')
    items = [
        ('final-a/02-dialog-round-sheet.png', '02-tuning-dialog.jpg', '设置页示例对话框：一轮「消散 → 出现」（浅色背景 L*=90，标准 src-over；消散 61/63 帧）'),
        ('final-a/06-card-swipe-return-sheet.png', '03-tuning-card.jpg', '设置页示例卡片：左滑过阈值 → 散完 → 粒子沿反向轨迹聚回'),
        ('h1/a-create-open-sheet.png', '04-home-create.jpg', '首页点 FAB：新建页从粒子里出现（全屏快照，5 万颗）'),
        ('h1/a-create-save-sheet.png', '05-home-new-card.jpg', '保存返回首页：新卡片入场'),
        ('h1/a-swipe-complete-sheet.png', '06-home-swipe.jpg', '首页左滑完成：从手指按下处开始化开，向左飘走'),
        ('final-b/removal-mixed-run-sheet.png', '07-removal.jpg', '多选删除（探针，虚构列表）：确认弹窗消散（取样已扣除暗层，src-over）+ 四张卡片一起移除（4.3 万颗，61/61 帧）'),
        ('b1/swipe-right-sheet.png', '08-swipe-right-return.jpg', '右滑 + 从右侧聚回（探针；回位前整卡静止约 0.25 秒是探针自身的问题，流体样式同样如此）'),
        ('b3/panel-sheet.png', '09-panel-additive.jpg', '调整外观面板（探针，深石板灰底色 L*=44）：自动选加光'),
        ('b1/alert-appear-sheet.png', '10-alert-appear.jpg', '真实 AlertDialog 出现：从下方汇入、暗层随进度变深'),
    ]
    for src, name, title in items:
        path = ROOT / src
        if path.is_file():
            save(sheet(path, title), name)
        else:
            print('缺', path, file=sys.stderr)
    overview = ROOT / 'styles-overview-final.png'
    if overview.is_file():
        save(sheet(overview, '参考样子总表（每行一段参考，按消散开始后的时刻取帧；方向取设置页里最接近该参考的那一格）', 3000),
             '11-styles.jpg')


if __name__ == '__main__':
    main()
