"""临时连续布面原型的本地服务器。

运行：
    python tmp/particle-dismiss-tuning/cloth-motion-prototype/serve.py

只监听 127.0.0.1。`/reference.mp4` 映射到用户提供的参考视频，不复制二进制文件。
所有 mp4 都支持 Range 请求，保证归一化时间滑杆可以逐帧定位。
"""

from __future__ import annotations

import argparse
import shutil
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


HERE = Path(__file__).resolve().parent
MODEL = HERE / "assets" / "android-canonical-left-up.mp4"
MODEL_CONTACT = HERE / "assets" / "android-canonical-left-up-contact-sheet.jpg"
DIAGNOSTIC_MODEL = HERE.parent / "frames-curtain" / "comparison-left-up.mp4"
VALIDATION_ROOT = HERE.parent / "final-lower-envelope-validation"
VALIDATION_VIDEO = VALIDATION_ROOT / "reference-desktop-device-normalized-1s.mp4"
VALIDATION_SLOW_VIDEO = VALIDATION_ROOT / "reference-desktop-device-slow.mp4"
DEVICE_VIDEO = VALIDATION_ROOT / "device-left-up-normalized-1s.mp4"
VALIDATION_CONTACT = VALIDATION_ROOT / "reference-desktop-device-contact.png"
EARLY_EDGE_CONTACT = VALIDATION_ROOT / "early-edge-reference-desktop-device-contact.png"
LOWER_ENVELOPE_CONTACT = VALIDATION_ROOT / "lower-envelope-reference-desktop-device-contact.png"
CURTAIN_SEPARATION = HERE / "assets" / "android-canonical-left-up-t047-separation.png"
REFERENCE = Path(
    r"E:\WeChatFiles\xwechat_files\wxid_yizrz7pph07f22_8943\temp\RWTemp\2026-08"
    r"\b95204e02d0afaf2bf4fb5148d30500c\93292e5c744d4770c4f7ecb686f3dd60.mp4"
)


class PrototypeHandler(SimpleHTTPRequestHandler):
    _range: tuple[int, int] | None = None

    def __init__(self, *args: object, **kwargs: object) -> None:
        super().__init__(*args, directory=str(HERE), **kwargs)

    def translate_path(self, path: str) -> str:
        request_path = path.split("?", 1)[0]
        if request_path == "/reference.mp4":
            return str(REFERENCE)
        if request_path == "/model.mp4":
            return str(MODEL)
        if request_path == "/diagnostic-model.mp4":
            return str(DIAGNOSTIC_MODEL)
        if request_path == "/model-contact.png":
            return str(MODEL_CONTACT)
        if request_path == "/validation-video.mp4":
            return str(VALIDATION_VIDEO)
        if request_path == "/validation-slow-video.mp4":
            return str(VALIDATION_SLOW_VIDEO)
        if request_path == "/device-video.mp4":
            return str(DEVICE_VIDEO)
        if request_path == "/validation-contact.png":
            return str(VALIDATION_CONTACT)
        if request_path == "/early-edge-contact.png":
            return str(EARLY_EDGE_CONTACT)
        if request_path == "/lower-envelope-contact.png":
            return str(LOWER_ENVELOPE_CONTACT)
        if request_path == "/curtain-separation.png":
            return str(CURTAIN_SEPARATION)
        return super().translate_path(path)

    def send_head(self):  # type: ignore[no-untyped-def]
        request_path = self.path.split("?", 1)[0]
        translated = Path(self.translate_path(self.path))
        if request_path not in (
            "/reference.mp4",
            "/model.mp4",
            "/diagnostic-model.mp4",
            "/validation-video.mp4",
            "/validation-slow-video.mp4",
            "/device-video.mp4",
        ) and translated.suffix.lower() != ".mp4":
            self._range = None
            return super().send_head()
        media = REFERENCE if request_path == "/reference.mp4" else MODEL if request_path == "/model.mp4" else translated
        if not media.is_file():
            self.send_error(404, "File not found")
            return None
        file = media.open("rb")
        size = media.stat().st_size
        header = self.headers.get("Range")
        if header and header.startswith("bytes="):
            start_text, end_text = header.removeprefix("bytes=").split("-", 1)
            start = int(start_text or 0)
            end = min(int(end_text) if end_text else size - 1, size - 1)
            if start > end:
                file.close()
                self.send_error(416, "Requested Range Not Satisfiable")
                return None
            self._range = (start, end)
            file.seek(start)
            self.send_response(206)
            self.send_header("Content-Type", "video/mp4")
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.send_header("Content-Length", str(end - start + 1))
            self.end_headers()
            return file
        self._range = None
        self.send_response(200)
        self.send_header("Content-Type", "video/mp4")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(size))
        self.end_headers()
        return file

    def copyfile(self, source, outputfile) -> None:  # type: ignore[no-untyped-def]
        if self._range is None:
            shutil.copyfileobj(source, outputfile)
            return
        remaining = self._range[1] - self._range[0] + 1
        while remaining > 0:
            chunk = source.read(min(64 * 1024, remaining))
            if not chunk:
                break
            outputfile.write(chunk)
            remaining -= len(chunk)

    def end_headers(self) -> None:
        self.send_header("Cache-Control", "no-store")
        super().end_headers()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    if not REFERENCE.is_file():
        raise FileNotFoundError(f"找不到参考视频：{REFERENCE}")
    server = ThreadingHTTPServer(("127.0.0.1", args.port), PrototypeHandler)
    if not MODEL.is_file():
        raise FileNotFoundError(f"请先生成桌面模型视频：{MODEL}")
    print(f"连续布面物理模拟器：http://127.0.0.1:{args.port}/physical.html")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
