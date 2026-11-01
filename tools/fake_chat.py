# -*- coding: utf-8 -*-
"""假 chat 服务：把 App 实际发出的请求体原样存盘，再回一段合法 SSE。

用途：证明「源码里写了 2.3 的输出规矩」和「包里真的发出去了」是一回事。
**只用来联调，不要外发、不要提交。**

用法：
    python tools/fake_chat.py            # 默认监听 127.0.0.1:8901
    python tools/fake_chat.py 8902       # 换端口

配合：
    gradlew.bat assembleDebug -PchatBaseUrl=http://127.0.0.1:8901
    adb reverse tcp:8901 tcp:8901        # 设备侧 127.0.0.1 打到宿主

⚠️ Android 16 上 10.0.2.2 被「本地网络保护」拦掉了，只有 loopback + adb reverse 能通
   （技能册 08 分册 §78）；所以这里和包里的 base url 都写 127.0.0.1。

产物（写到**本文件所在目录的上一级**，即工程根）：
  * `chat_body.json` —— 最后一次请求体（UTF-8，原样）
  * `chat_requests.jsonl` —— 每次请求一行（含 path / 是否带 Authorization，**不记 Key 内容**）
"""
import http.server
import json
import os
import sys

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8901
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BODY = os.path.join(ROOT, "chat_body.json")
LOG = os.path.join(ROOT, "chat_requests.jsonl")


class H(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0) or 0)
        raw = self.rfile.read(n)
        text = raw.decode("utf-8", "replace")
        with open(BODY, "w", encoding="utf-8") as f:
            f.write(text)
        # 只记「有没有」 Authorization，不记内容 —— 报告里也不许出现 Key
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(json.dumps({
                "path": self.path,
                "bytes": n,
                "has_auth": bool(self.headers.get("Authorization")),
                "keys": sorted(json.loads(text).keys()) if text.startswith("{") else [],
            }, ensure_ascii=False) + "\n")

        # 合法的最小 SSE：先给一个 delta，再给 finish + [DONE]
        chunks = [
            {"choices": [{"delta": {"content": "ok"}}]},
            {"choices": [{"delta": {}, "finish_reason": "stop"}]},
        ]
        payload = "".join(
            "data: " + json.dumps(c, ensure_ascii=False) + "\n\n" for c in chunks
        ) + "data: [DONE]\n\n"
        body = payload.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    print("fake chat listening on http://127.0.0.1:%d" % PORT, flush=True)
    http.server.HTTPServer(("127.0.0.1", PORT), H).serve_forever()
