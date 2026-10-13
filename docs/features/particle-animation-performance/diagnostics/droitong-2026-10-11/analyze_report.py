"""离线分析用户导出的诊断 ZIP；统计源缓冲，不能验证最终屏幕。"""

import argparse
import hashlib
import io
import json
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np
from PIL import Image


def span(values):
    return {"min": min(values), "max": max(values)} if values else None


def compare(left, right):
    if left.shape != right.shape:
        return {"same_shape": False}
    diff = np.abs(left.astype(np.int16) - right.astype(np.int16))
    return {
        "same_shape": True,
        "equal_rgba": bool(np.array_equal(left, right)),
        "different_pixel_fraction": float(np.any(diff != 0, axis=2).mean()),
        "max_channel_difference": int(diff.max()),
        "max_alpha_difference": int(diff[:, :, 3].max()),
    }


def analyze_report(report, images):
    events = sorted(report["events"], key=lambda event: event["elapsedMs"])
    counts = Counter(event["event"] for event in events)
    basis = Counter(event["values"].get("basis") for event in events
                    if event["event"] == "particle_presentation_decision")
    sources = defaultdict(Counter)
    hidden = {}
    hidden_durations = []
    problems = []
    submissions = []
    markers = []
    marker_active = None
    window_queued = []
    committed_delays = []
    transactions_pending = []
    dialog_states = []
    snapshots = []
    environments = []
    for event in events:
        name, values, elapsed = event["event"], event["values"], event["elapsedMs"]
        if name.startswith("foreground_") and "view" in values:
            sources[values["view"]][name] += 1
        if name == "foreground_hidden":
            if values["view"] in hidden:
                problems.append({"issue": "重复隐藏", "event": event})
            hidden[values["view"]] = elapsed
            if values.get("transitionAlpha") != 0:
                problems.append({"issue": "隐藏后透明度异常", "event": event})
        elif name == "foreground_restored":
            start = hidden.pop(values["view"], None)
            if start is not None:
                hidden_durations.append(elapsed - start)
            if values.get("transitionAlpha") != 1 or values.get("leases") != 0:
                problems.append({"issue": "恢复状态异常", "event": event})
        elif name == "foreground_buffer_submitted":
            submissions.append(values)
            if values.get("alpha") != 1 or not values.get("valid"):
                problems.append({"issue": "提交状态异常", "event": event})
        elif name == "foreground_closed" and values.get("failed"):
            problems.append({"issue": "前景失败关闭", "event": event})
        elif name == "card_window_transaction":
            window_queued.append(values.get("queuedOnDraw"))
            transactions_pending.append(elapsed)
        elif name == "card_transaction_committed" and transactions_pending:
            committed_delays.append(elapsed - transactions_pending.pop(0))
        elif name == "marker_submitted":
            marker_active = {"submitted_ms": elapsed, **values}
            markers.append(marker_active)
        elif name == "marker_committed" and marker_active is not None:
            marker_active["committed_delay_ms"] = elapsed - marker_active["submitted_ms"]
        elif name == "marker_closed" and marker_active is not None:
            marker_active["lifetime_ms"] = elapsed - marker_active["submitted_ms"]
            marker_active = None
        if name.startswith("dialog_"):
            dialog_states.append(event)
        if name == "appearance_snapshot_ready":
            snapshots.append(event)
        if name == "gl_environment" and values not in environments:
            environments.append(values)
        if ("failed" in name or "unavailable" in name or "watchdog" in name
                or values.get("failure") or values.get("error")):
            problems.append({"issue": "异常或缺失证据事件", "event": event})

    image_metrics = []
    pairs = []
    for filename, pixels in sorted(images.items()):
        alpha = pixels[:, :, 3]
        image_metrics.append({
            "filename": filename, "width": pixels.shape[1], "height": pixels.shape[0],
            "nontransparent_fraction": float((alpha > 0).mean()),
            "alpha_min": int(alpha.min()), "alpha_max": int(alpha.max()),
        })
        if "-cpu-prepared.png" not in filename:
            continue
        hardware_name = filename.replace("-cpu-prepared.png", "-hardware-prepared.png")
        hardware = images.get(hardware_name)
        if hardware is None:
            continue
        comparison = compare(pixels, hardware)
        if "fab_create" in filename:
            height, width = pixels.shape[:2]
            top = (hardware.shape[0] - height) // 2
            left = (hardware.shape[1] - width) // 2
            comparison["hardware_center_body"] = compare(
                pixels, hardware[top:top + height, left:left + width])
            comparison["body_offset_xy"] = [left, top]
        pairs.append({"cpu": filename, "hardware": hardware_name, **comparison})

    return {
        "id": report["id"], "scene": report["scene"], "label": report["label"],
        "observation": report["observation"], "options": report["options"],
        "event_counts": dict(counts), "presentation_basis_counts": dict(basis),
        "positive_driver_present_count": sum(
            (event["values"].get("driverPresentNanos") or 0) > 0
            for event in events if event["event"] == "particle_presentation_decision"),
        "dropped_frame_events": report["droppedFrameEvents"],
        "dropped_lifecycle_events": report["droppedLifecycleEvents"],
        "foreground_sources": dict(sources),
        "foreground_hidden_duration_ms": span(hidden_durations),
        "foreground_unrestored_views": sorted(hidden),
        "foreground_submissions": submissions,
        "card_window_queued": window_queued,
        "card_committed_delays_ms": committed_delays,
        "markers": markers, "dialog_states": dialog_states, "snapshots": snapshots,
        "gl_environments": environments, "problems": problems,
        "image_metrics": image_metrics, "cpu_hardware_comparisons": pairs,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    archive_bytes = args.archive.read_bytes()
    runs = []
    metadata = []
    with zipfile.ZipFile(io.BytesIO(archive_bytes)) as archive:
        names = archive.namelist()
        total_bytes = sum(item.file_size for item in archive.infolist())
        if total_bytes > 100 * 1024 * 1024:
            raise ValueError("解压大小超过此次诊断分析的 100 MiB 上限")
        for name in sorted(names):
            if not name.endswith("/report.json"):
                continue
            report = json.loads(archive.read(name))
            if report["metadata"] not in metadata:
                metadata.append(report["metadata"])
            prefix = name.rsplit("/", 1)[0] + "/"
            images = {}
            for image_name in sorted(names):
                if image_name.startswith(prefix) and image_name.endswith(".png"):
                    with Image.open(io.BytesIO(archive.read(image_name))) as image:
                        images[image_name[len(prefix):]] = np.array(image.convert("RGBA"))
            runs.append(analyze_report(report, images))
    bases = Counter()
    for run in runs:
        bases.update(run["presentation_basis_counts"])
    analysis = {
        "source_filename": args.archive.name,
        "source_bytes": len(archive_bytes),
        "source_sha256": hashlib.sha256(archive_bytes).hexdigest(),
        "archive_entries": len(names), "uncompressed_bytes": total_bytes,
        "run_count": len(runs), "image_count": sum(len(r["image_metrics"]) for r in runs),
        "metadata": metadata,
        "presentation_basis_counts": dict(bases),
        "meaning": "源图像及应用事件统计；不能证明最终屏幕合成或真实呈现帧率",
        "runs": runs,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(analysis, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in analysis.items() if k not in {"runs", "metadata"}}, ensure_ascii=False))
    for run in runs:
        print(run["id"], run["scene"], "图像", len(run["image_metrics"]),
              "异常事件", len(run["problems"]), "呈现依据", run["presentation_basis_counts"])


if __name__ == "__main__":
    main()
