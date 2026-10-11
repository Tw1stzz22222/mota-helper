# -*- coding: utf-8 -*-
"""
魔塔助手 QQ <-> 游戏 双向转发桥接

方向一（QQ -> 游戏）：
    QQ 群里有人发 "tell xxx"  ->  转发到游戏 mod（127.0.0.1:25588/say），游戏里角色说出 xxx
方向二（游戏 -> QQ）：
    游戏 mod 把收到的消息 POST 到本脚本（127.0.0.1:25590）->  本脚本发到 QQ 群

依赖：pip install websocket-client
"""

import json
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

import websocket

# ================= 配置区（改这里） =================
NAP_WS_URL = "ws://127.0.0.1:3001"              # NapCat 的 WebSocket 地址（对应 onebot11 配置里的 3001 端口）
MOTA_SAY_URL = "http://127.0.0.1:25588/say"     # 游戏 mod 的 /say 接收接口
LISTEN_PORT = 25590                              # 本脚本接收游戏消息的端口
TARGET_GROUP_ID = 1125525356                     # 目标 QQ 群号
SAY_PREFIX = "tell"                              # 触发前缀：群里发 "tell xxx" 才会转发
# ===================================================

ws = None
ws_lock = threading.Lock()


def send_group_msg(group_id, text):
    """通过 NapCat 的 WebSocket 往 QQ 群发消息"""
    global ws
    with ws_lock:
        if ws is None:
            print("[桥接] WebSocket 未连接，无法发群消息")
            return
        payload = {
            "action": "send_group_msg",
            "params": {
                "group_id": group_id,
                "message": [{"type": "text", "data": {"text": text}}],
            },
            "echo": str(int(time.time() * 1000)),
        }
        try:
            ws.send(json.dumps(payload))
        except Exception as e:
            print("[桥接] 发群消息失败:", e)


def on_message(ws, message):
    try:
        data = json.loads(message)
    except Exception:
        return

    # 收到群消息事件
    if data.get("post_type") == "message" and data.get("message_type") == "group":
        group_id = data.get("group_id")
        raw = (data.get("raw_message") or "").strip()

        # 方向一：目标群收到 say xxx
        if group_id == TARGET_GROUP_ID and raw.startswith(SAY_PREFIX):
            content = raw[len(SAY_PREFIX):].strip()
            if not content:
                return
            post_to_mota(content)
         #   send_group_msg(group_id, "已转发到游戏: " + content)


def post_to_mota(text):
    """把文本 POST 到游戏 mod 的 /say 接口"""
    try:
        body = json.dumps({"message": text}).encode("utf-8")
        req = urllib.request.Request(
            MOTA_SAY_URL, data=body, headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            print("[桥接] 已转发到游戏: %s (HTTP %s)" % (text, resp.status))
    except Exception as e:
        print("[桥接] 转发到游戏失败:", e)


def on_error(ws, error):
    print("[桥接] WebSocket 错误:", error)


def on_close(ws, code, msg):
    print("[桥接] WebSocket 断开")


def on_open(ws):
    print("[桥接] 已连接 NapCat WebSocket")


def ws_loop():
    global ws
    while True:
        try:
            ws = websocket.WebSocketApp(
                NAP_WS_URL,
                on_message=on_message,
                on_error=on_error,
                on_close=on_close,
                on_open=on_open,
            )
            ws.run_forever()
        except Exception as e:
            print("[桥接] WebSocket 异常:", e)
        print("[桥接] 5 秒后重连...")
        time.sleep(5)


class GameMsgHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode("utf-8", "ignore")
        try:
            data = json.loads(body)
            msg = data.get("message", "")
        except Exception:
            msg = body
        if msg:
            send_group_msg(TARGET_GROUP_ID, "[游戏] " + msg)
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.end_headers()
        self.wfile.write(b"OK")

    def log_message(self, *args):
        pass


def http_loop():
    server = HTTPServer(("127.0.0.1", LISTEN_PORT), GameMsgHandler)
    print("[桥接] 游戏消息接收端口已开启: %s" % LISTEN_PORT)
    server.serve_forever()


if __name__ == "__main__":
    print("=" * 50)
    print("魔塔助手 QQ <-> 游戏 桥接脚本")
    print("  目标群号: %s" % TARGET_GROUP_ID)
    print("  NapCat: %s" % NAP_WS_URL)
    print("  游戏接收: %s" % MOTA_SAY_URL)
    print("  游戏消息监听: 127.0.0.1:%s" % LISTEN_PORT)
    print("=" * 50)
    threading.Thread(target=ws_loop, daemon=True).start()
    threading.Thread(target=http_loop, daemon=True).start()
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("[桥接] 退出")
