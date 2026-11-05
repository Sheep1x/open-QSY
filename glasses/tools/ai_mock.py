"""假的 OpenAI 兼容服务，用来验收眼镜端的「拉模型 / 问 AI」两条链路（不需要真 Key）。

起法：
    python glasses/tools/ai_mock.py 9100
    adb -s <设备> reverse tcp:9100 tcp:9100
    # 然后在 AI 配置页把 baseUrl 填 http://127.0.0.1:9100/v1，Key 随便填

每次请求都会打印一行，方便确认「题库命中时到底有没有联网」。
"""
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HITS = []


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # 关掉默认日志，改用 print
        pass

    def _send(self, obj, code=200):
        raw = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        HITS.append(("GET", self.path))
        print(f"[mock] GET {self.path}  auth={self.headers.get('Authorization')}", flush=True)
        if self.path.endswith("/models"):
            self._send({"object": "list", "data": [
                {"id": "mock-gpt-4", "object": "model"},
                {"id": "mock-gpt-3.5", "object": "model"},
            ]})
        else:
            self._send({"error": {"message": "not found"}}, 404)

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n).decode("utf-8", "replace")
        print(f"[mock] POST {self.path}  bytes={n}", flush=True)
        try:
            data = json.loads(body)
        except Exception:
            data = {}
        msgs = data.get("messages") or []
        for m in msgs:
            print(f"[mock]   {m.get('role')}: {str(m.get('content'))[:300]}", flush=True)
        print(f"[mock]   model={data.get('model')} max_tokens={data.get('max_tokens')}", flush=True)
        answer = "B. 这是模拟答案的第一行\n理由：题干里的关键条件与选项 B 相符。"
        self._send({
            "id": "chatcmpl-mock",
            "object": "chat.completion",
            "model": data.get("model", "mock-gpt-4"),
            "choices": [{"index": 0, "message": {"role": "assistant", "content": answer},
                         "finish_reason": "stop"}],
            "usage": {"prompt_tokens": 100, "completion_tokens": 30, "total_tokens": 130},
        })


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9100
    srv = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print(f"[mock] listening on {port}", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
