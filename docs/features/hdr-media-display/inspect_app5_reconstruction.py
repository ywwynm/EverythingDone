"""离线研究工具：记录 SDK 内核参数和样本参考增益，不执行厂商代码或改写图片。"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import struct


def inspect_kernel(path):
    data = path.read_bytes()
    if not data.startswith(b"CLCC"):
        raise ValueError("不是已检查的 CLCC 容器")

    def string_at(offset):
        assert data[offset:offset + 4] == b"STRI"
        size, = struct.unpack_from("<I", data, offset + 4)
        assert 0 < size < 256 and offset + 8 + size <= len(data)
        return data[offset + 8:offset + 8 + size].split(b"\0")[0].decode("ascii"), offset + 8 + size

    kernels = []
    for match in re.finditer(b"KERN", data):
        offset = match.start()
        if data[offset + 8:offset + 12] != b"STRI":
            continue
        name, cursor = string_at(offset + 8)
        count, = struct.unpack_from("<I", data, cursor)
        assert count < 256
        cursor += 4
        parameters = []
        for index in range(count):
            assert data[cursor:cursor + 4] == b"KPAR"
            size, = struct.unpack_from("<I", data, cursor + 4)
            parameter, next_offset = string_at(cursor + 8)
            identifier, _ = string_at(next_offset)
            assert identifier == f"kernel_param_{index}"
            parameters.append(parameter)
            cursor += 8 + size
        kernels.append(dict(name=name, offset=offset, parameters=parameters))
    return dict(file=path.name, bytes=len(data), sha256=hashlib.sha256(data).hexdigest(), kernels=kernels)


def inspect_photo(path):
    # 仅回放本次已确认的 24 字节、绝对偏移布局；正式边界检查由 Kotlin 适配器负责。
    import numpy as np
    from PIL import Image
    data = path.read_bytes()
    base = Image.open(io.BytesIO(data))
    headers = [v for k, v in base.applist if k == "APP5" and v.startswith(b"ITUT35")]
    assert len(headers) == 1 and len(headers[0]) == 24
    offset, = struct.unpack_from(">I", headers[0], 16)
    assert data[offset - 2:offset + 2] == b"\xff\xd9\xff\xd8"
    gain = Image.open(io.BytesIO(data[offset:]))
    metadata = [v for k, v in gain.applist if k == "APP5" and v.startswith(b"ITUT35")]
    assert len(metadata) == 1 and len(metadata[0]) == 50
    words = struct.unpack_from(">8I", metadata[0], 18)
    assert tuple(words[:2]) == (gain.height, gain.width)
    gain.draft("RGB", (gain.width // 2, gain.height // 2))
    rgb = np.asarray(gain.convert("RGB")).astype(np.int32)
    luma = ((77 * rgb[:, :, 0] + 150 * rgb[:, :, 1] + 29 * rgb[:, :, 2] + 128) >> 8).astype(float)
    ranks = [0, 50, 90, 99, 100]
    gain4 = 4 ** (luma / 255)
    gain8 = 8 ** (luma / 255)
    return dict(sha256=hashlib.sha256(data).hexdigest(), metadataWords=words,
                metadataFloatsUnverified={str(i): struct.unpack(">f", struct.pack(">I", words[i]))[0]
                                          for i in (4, 7)},
                decodedSize=gain.size, percentileRanks=ranks,
                lumaPercentiles=np.percentile(luma, ranks).tolist(),
                gain4Percentiles=np.percentile(gain4, ranks).tolist(),
                gain8Percentiles=np.percentile(gain8, ranks).tolist(),
                ratio8to4Percentiles=np.percentile(gain8 / gain4, ranks).tolist())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kernel", action="append", type=Path, default=[])
    parser.add_argument("--photo", action="append", type=Path, default=[])
    args = parser.parse_args()
    print(json.dumps(dict(kernels=[inspect_kernel(p) for p in args.kernel],
                          photos=[inspect_photo(p) for p in args.photo]), ensure_ascii=False, indent=2))
