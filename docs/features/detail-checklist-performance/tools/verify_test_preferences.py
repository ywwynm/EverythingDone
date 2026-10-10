"""只核对本专项曾修改的非敏感偏好键，不显示、比较或修改认证数据。"""
import json
import re
import tarfile
import xml.etree.ElementTree as ET
from device_regression import adb, DEVICES, OUTPUT, PACKAGE, ROOT

identifiers = ("KEY_AUTO_LINK", "KEY_AUTO_SAVE_EDITS", "KEY_AUTOPLAY_DETAIL_DYNAMIC",
               "KEY_FOLLOW_SYSTEM_DARK_MODE", "KEY_FORCE_DARK_MODE", "KEY_ONGOING_THING_ID")
source = (ROOT / "app/src/main/java/com/ywwynm/everythingdone/Def.kt").read_text(encoding="utf-8")
keys = [re.search(rf"const val {key}: String\s*=\s*\"([^\"]+)\"", source).group(1) for key in identifiers]
keys += ["audio_recording_input_mode", "audio_recording_input_mode_confirmed"]
preferences_file = "shared_prefs/EverythingDone_preferences.xml"


def subset(xml):
    return {node.attrib["name"]: node.attrib.get("value", node.text)
            for node in ET.fromstring(xml) if node.attrib.get("name") in keys}


for serial in DEVICES:
    directory = OUTPUT / serial.replace(":", "_")
    with tarfile.open(directory / "before-migration.tar") as archive:
        before = subset(archive.extractfile(preferences_file).read())
    current = adb(serial, "exec-out", "run-as", PACKAGE, "cat", preferences_file)
    after = subset(current)
    differences = {key: {"before": before.get(key), "after": after.get(key)}
                   for key in keys if before.get(key) != after.get(key)}
    print(json.dumps({"device": serial, "differences": differences}, ensure_ascii=False))
