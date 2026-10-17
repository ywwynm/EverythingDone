"""只读分析本次五张截图；不改写原图，不把重建数值当成面板测量。

示例：python inspect_hdr_screenshots.py --input-dir <原图目录> --output <结果.json>
ISO 字段与重建公式核对 google/libultrahdr 的 gainmapmetadata.cpp、gainmapmath.cpp。
只接受此次实际出现的版本 0、三通道、独立分母布局，不是通用 HDR 解码器。
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
import warnings

import numpy as np
from PIL import Image


SAMPLES = [
    (1, "a4080a13-1bcd-4493-a923-b4ea8a2424f5", True),
    (2, "08a76573-28cf-4591-a3d0-ebed9dad6563", False),
    (3, "56234484-1a74-4130-af0c-ec69cad43689", True),
    (4, "faf7e057-bdd9-404d-890d-1b3e6ed073aa", True),
    (5, "e54c800d-a20d-4dec-b2fe-8e52bb4e716b", False),
]
ISO = b"urn:iso:std:iso:ts:21496:-1\x00"


def iso_parameters(payload):
    raw = payload[len(ISO):]
    if len(raw) != 141 or raw[:5] != b"\x00\x00\x00\x00\x80":
        raise ValueError("不是本次已核对的 ISO 元数据布局")
    values = []
    for offset in range(5, len(raw), 8):
        signed = offset >= 21 and ((offset - 21) // 8) % 5 != 2
        numerator, denominator = struct.unpack_from(">" + ("i" if signed else "I") + "I", raw, offset)
        if denominator == 0:
            raise ValueError("ISO 分母为零")
        values.append(numerator / denominator)
    return {
        "minVersion": 0, "writerVersion": 0, "flags": "0x80",
        "useBaseColorSpace": False,
        "headroomLog2": values[:2],
        "channelFields": ["gainMinLog2", "gainMaxLog2", "gamma", "baseOffset", "alternateOffset"],
        "channels": np.array(values[2:]).reshape(3, 5).tolist(),
    }


def mpf_secondary(data, image):
    """从 MPF 的 TIFF 索引读取第二幅 JPEG，校验长度与图像边界。"""
    packets = [v for k, v in image.applist if k == "APP2" and v.startswith(b"MPF\x00")]
    if not packets:
        return None
    if len(packets) != 1:
        raise ValueError("MPF 不唯一")
    packet = packets[0]
    packet_offset = data.find(b"\xff\xe2" + struct.pack(">H", len(packet) + 2) + packet)
    if packet_offset < 0:
        raise ValueError("MPF 标记不匹配")
    tiff = packet[4:]
    if tiff[:4] != b"MM\x00*":
        raise ValueError("不是本次的大端 TIFF")
    ifd, = struct.unpack_from(">I", tiff, 4)
    count, = struct.unpack_from(">H", tiff, ifd)
    entries = {}
    for i in range(count):
        tag, kind, size, value = struct.unpack_from(">HHII", tiff, ifd + 2 + 12 * i)
        entries[tag] = (kind, size, value)
    if entries.get(0xb001) != (4, 1, 2):
        raise ValueError("不是本次的双 JPEG MPF")
    kind, size, offset = entries[0xb002]
    if kind != 7 or size != 32:
        raise ValueError("MPF 项长度不匹配")
    _, length, relative, _, _ = struct.unpack_from(">IIIHH", tiff, offset + 16)
    absolute = packet_offset + 8 + relative
    end = absolute + length
    if end > len(data) or data[absolute:absolute + 2] != b"\xff\xd8" or data[end - 2:end] != b"\xff\xd9":
        raise ValueError("第二幅 JPEG 边界不匹配")
    return absolute, data[absolute:end]


def mean_rgb(array, rect):
    x0, y0, x1, y1 = rect
    return np.mean(array[y0:y1, x0:x1], axis=(0, 1)).round(7).tolist()


def inspect(path, index, expected_hdr):
    data = path.read_bytes()
    image = Image.open(io.BytesIO(data))
    base = np.asarray(image.convert("RGB"), dtype=np.float64)
    # 选择白色内区，避开按钮红色图形、阴影、抗锯齿和文本。
    rect = [800, 1210, 900, 1260] if index in (2, 3) else [610, 1600, 630, 1660]
    result = {
        "index": index, "file": path.name, "userHdrState": expected_hdr,
        "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
        "size": image.size, "primaryIso": any(k == "APP2" and v.startswith(ISO) for k, v in image.applist),
        "primaryVividApp8": any(k == "APP8" and v.startswith(b"ITUT35") for k, v in image.applist),
        "primaryIccPresent": "icc_profile" in image.info,
        "exifColorSpace": image.getexif().get_ifd(34665).get(40961),
        "whiteRegion": {"xyxy": rect, "baseMeanRgb255": mean_rgb(base, rect)},
    }
    secondary = mpf_secondary(data, image)
    if secondary is None:
        if expected_hdr:
            raise ValueError("HDR 样本没有 MPF")
        result["gainmapPresent"] = False
        return result
    offset, encoded_gain = secondary
    # 附图 MPF 是属性 IFD，Pillow 会提示无法单独将其当成整份 MPO；仍可解码 JPEG 像素。
    with warnings.catch_warnings():
        warnings.filterwarnings("ignore", message="Image appears to be a malformed MPO file.*")
        gain_image = Image.open(io.BytesIO(encoded_gain))
    metadata = iso_parameters(next(v for k, v in gain_image.applist if k == "APP2" and v.startswith(ISO)))
    gain = np.asarray(gain_image.convert("RGB").resize(image.size, Image.Resampling.BILINEAR), dtype=np.float64) / 255
    channel = np.array(metadata["channels"])
    rgb = base / 255
    linear = np.where(rgb <= 0.04045, rgb / 12.92, ((rgb + 0.055) / 1.055) ** 2.4)
    log_gain = channel[:, 0] + (channel[:, 1] - channel[:, 0]) * gain ** (1 / channel[:, 2])
    reconstructed = (linear + channel[:, 3]) * np.exp2(log_gain) - channel[:, 4]
    result.update(gainmapPresent=True, gainmapOffset=offset, gainmapSize=gain_image.size,
                  secondaryVividApp8=any(k == "APP8" and v.startswith(b"ITUT35") for k, v in gain_image.applist),
                  secondaryIccPresent="icc_profile" in gain_image.info, iso=metadata)
    result["whiteRegion"].update(gainMeanRgb255=mean_rgb(gain * 255, rect),
                                reconstructedMeanRgbRelative=mean_rgb(reconstructed, rect))
    if index == 1:
        rect = [450, 545, 550, 575]
        result["neutralBackdropRegion"] = {
            "xyxy": rect, "baseMeanRgb255": mean_rgb(base, rect),
            "gainMeanRgb255": mean_rgb(gain * 255, rect),
            "reconstructedMeanRgbRelative": mean_rgb(reconstructed, rect),
        }
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    result = {
        "purpose": "核对本次系统截图的 HDR 容器及原生控件白色，不验证真实屏幕合成。",
        "deviceValidation": False,
        "reconstructionLimits": "仅统计白色区域；按 sRGB 传递函数、完整 ISO 增益和双线性采样计算。HDR 样本没有 ICC，useBaseColorSpace 为 false；不据此宣称彩色全图等于华为图库渲染。数值相对 SDR 白，不是屏幕 nit。",
        "sources": [
            "https://github.com/google/libultrahdr/blob/main/lib/src/gainmapmetadata.cpp",
            "https://github.com/google/libultrahdr/blob/main/lib/src/gainmapmath.cpp",
        ],
        "samples": [inspect(args.input_dir / f"codex-clipboard-{name}.jpg", index, hdr)
                    for index, name, hdr in SAMPLES],
    }
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"samples": len(result["samples"]), "hdr": [s["index"] for s in result["samples"] if s["gainmapPresent"]], "output": str(args.output)}, ensure_ascii=False))
