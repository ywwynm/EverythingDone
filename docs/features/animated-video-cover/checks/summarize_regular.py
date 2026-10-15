"""读取普通滑动采样，区分暂停、已显示帧及离屏返回时的加载圈。"""
from pathlib import Path
import json
import sys

sys.stdout.reconfigure(encoding="utf-8")
root = next(p for p in Path(__file__).resolve().parents if (p / "gradlew.bat").exists())
results = []
for name in sys.argv[1:]:
    directory = root / "tmp/selection-transition-20261013" / name
    summary = json.loads((directory / "regular-summary.json").read_text(encoding="utf-8"))
    visits = json.loads((directory / "regular-returns.json").read_text(encoding="utf-8"))
    waits = []
    for visit in visits:
        observations = {}
        for sample in visit["samples"]:
            for card in sample["cards"]:
                if not card["previouslyReady"]:
                    continue
                observations.setdefault(card["id"], []).append((sample["time"], card))
        for identity, samples in observations.items():
            spinning = [time for time, card in samples if card["spinner"]]
            ready = [time for time, card in samples if card.get("drawable") == "GifDrawable"]
            if spinning:
                waits.append({"position": visit["position"], "id": identity,
                              "spinnerSamples": len(spinning),
                              "waitMs": min((time for time in ready if time >= spinning[0]), default=samples[-1][0]) - spinning[0]})
    result = {"run": name, **summary, "warmSpinnerEvents": waits,
              "maxWarmSpinnerMs": max((item["waitMs"] for item in waits), default=0)}
    results.append(result)
print(json.dumps(results, ensure_ascii=False, indent=2))
