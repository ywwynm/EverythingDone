"""比较真实系统主题/记事颜色变化后聚焦编辑器的颜色、选区和 Drawable 像素指纹。"""
import json
from device_regression import OUTPUT, DEVICES

for serial in DEVICES:
    root = OUTPUT / serial.replace(":", "_")
    old = json.loads((root / "theme-baseline-02/result.json").read_text(encoding="utf-8"))
    new = json.loads((root / "theme-candidate-02/result.json").read_text(encoding="utf-8"))
    assert old.get("completed") and new.get("completed")
    differences = []
    for a, b in zip(old["records"], new["records"]):
        assert a["case"] == b["case"]
        for section in ("editor", "title", "pressed_background"):
            if a.get(section) != b.get(section):
                differences.append({"case": a["case"], "section": section,
                    "old": a.get(section), "new": b.get(section)})
    report = {"device": serial, "cases": len(new["records"]), "differences": differences}
    (root / "theme-comparison.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"device": serial, "cases": len(new["records"]), "differences": [
        {"case": d["case"], "section": d["section"], "fields": [key for key in d["old"] if d["old"][key] != d["new"].get(key)]}
        if isinstance(d["old"], dict) else d for d in differences]}, ensure_ascii=False))
