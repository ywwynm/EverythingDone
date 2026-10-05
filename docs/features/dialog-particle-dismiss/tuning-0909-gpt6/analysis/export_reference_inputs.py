"""从现有素材元数据生成参考播放预设，不加载 GPU 或参考观测帧。"""
from pathlib import Path
import json
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from desktop_inputs import REFS, META
from reference_initial_conditions import SITES,NOTES


def main():
    result = {}
    for name in REFS:
        m = META[name]
        result[name] = dict(seed=m['seed'], direction=m['direction'],
                            origins=[dict(x=x,y=y,start=t) for x,y,t in SITES[name]],
                            note=NOTES.get(name,'起点位置与时刻均为原片早段的观测估计。'))
    target = ROOT / 'analysis/match-cache/takeover-20261004/reference-inputs.json'
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', 'utf-8')
    print(f'已导出 {len(result)} 段参考预设；不是已恢复的原片随机种子。')


if __name__ == '__main__':
    main()
