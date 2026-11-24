"""Independent DOMINION Voice service. No owner API token or filesystem access."""
import hashlib
import hmac
import json
import os
import secrets
import threading
import re
import time
from mobile_gateway import MobileAccess
import uuid
from collections import OrderedDict, deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from plugin_sdk import PluginInbox, CAPABILITIES

MAX_BODY = 512 * 1024
ROOT = Path(__file__).resolve().parent
TOKEN = os.environ.get("DOMINION_BRIDGE_TOKEN", "")
UI_TOKEN = secrets.token_hex(32)


class Conversation:
    def __init__(self, state_path=None):
        self.lock = threading.RLock()
        self.pending = None
        self.answers = OrderedDict()
        self.history = deque(maxlen=3)
        self.last = None
        self.mobile_turns = []
        self.inbox = PluginInbox(state_path)
        saved = self.inbox.state
        if saved:
            self.pending = saved.get('pending')
            self.answers = OrderedDict(saved.get('answers', []))
            self.history = deque(saved.get('history', []), maxlen=3)
            self.last = saved.get('last')
            self.mobile_turns = saved.get('mobileTurns', [])

    def persist(self):
        self.inbox.save({'pending': self.pending, 'answers': list(self.answers.items()), 'history': list(self.history), 'last': self.last, 'mobileTurns': self.mobile_turns})

    def submit(self, text, source, request_id=None, kind='task'):
        if not isinstance(text, str) or not text.strip() or len(text.encode("utf-8")) > 6000:
            raise ValueError("발화를 1~6000 UTF-8 바이트로 입력하세요.")
        with self.lock:
            if self.pending:
                raise ValueError("이전 요청의 답변을 기다리고 있습니다.")
            # History is conversation data, not a new tool or authority grant.
            history = json.dumps(list(self.history), ensure_ascii=False)
            if len(history.encode("utf-8")) > 6500:
                history = "[]"
            instruction = (
                "음성 대화입니다. 이전 대화 자료는 참고만 하세요. "
                "현재 발화에 자연스럽고 이해하기 쉽게 답하세요.\n"
                + "이전 대화: " + history + "\n현재 발화: " + text.strip()
            )
            rid = request_id or str(uuid.uuid4())
            self.pending = {"requestId": rid, "instruction": instruction,
                            "text": text.strip(), "source": source, "kind": kind}
            self.inbox.progress = None
            self.persist()
            return rid

    def reset(self):
        with self.lock:
            self.pending = None
            self.answers.clear()
            self.history.clear()
            self.last = None
            self.inbox.progress = None
            self.persist()

    def request(self):
        with self.lock:
            if not self.pending:
                return None
            return {k: self.pending[k] for k in ("requestId", "instruction")}

    def accept(self, data, persist=True):
        rid, answer, digest = (data.get(k) for k in ("requestId", "answer", "answerDigest"))
        if not isinstance(rid, str) or not isinstance(answer, str) or not isinstance(digest, str):
            raise ValueError("Invalid answer")
        actual = hashlib.sha256(answer.encode("utf-8")).hexdigest()
        if not hmac.compare_digest(actual, digest):
            raise ValueError("Answer digest mismatch")
        with self.lock:
            previous = self.answers.get(rid)
            if previous:
                if not hmac.compare_digest(previous["answerDigest"], digest):
                    raise ValueError("Conflicting duplicate answer")
                if persist: self.persist()
                return {"requestId": rid, "answerDigest": digest}
            if not self.pending or self.pending["requestId"] != rid:
                raise ValueError("No matching pending request")
            result = {"requestId": rid, "answer": answer, "answerDigest": digest,
                      "source": self.pending["source"]}
            self.history.append({"user": self.pending["text"][:1500], "assistant": answer[:1500]})
            self.answers[rid] = result
            while len(self.answers) > 32:
                self.answers.popitem(last=False)
            self.last = result
            self.update_mobile(rid, 'completed', answer=answer)
            self.pending = None
            if persist: self.persist()
            return {"requestId": rid, "answerDigest": digest}

    def answer(self, rid):
        with self.lock:
            result = self.answers.get(rid)
            return result["answer"] if result else None

    def status(self):
        with self.lock:
            return {"pending": self.pending.copy() if self.pending else None,
                    "last": self.last.copy() if self.last else None, "progress": self.inbox.progress}

    def discard(self, rid):
        with self.lock:
            if not self.pending or self.pending["requestId"] != rid:
                raise ValueError("Request changed")
            if self.inbox.progress and not self.inbox.progress.get('terminal'):
                raise ValueError('도미니온 작업이 진행 중입니다. PC 작업 화면에서 취소하세요.')
            self.update_mobile(rid, 'cancelled', error='요청이 닫혔습니다.')
            self.pending = None
            self.persist()

    def mobile_submit(self, data):
        client_id, text, kind = (data.get(k) for k in ('clientId', 'text', 'kind'))
        if not isinstance(client_id, str) or not re.fullmatch(r'[A-Za-z0-9-]{1,80}', client_id):
            raise ValueError('Invalid client request ID')
        if not isinstance(text, str) or not text.strip() or len(text.encode()) > 6000 or kind not in ('chat', 'task'):
            raise ValueError('메시지는 1~6000 UTF-8 바이트로 입력하세요.')
        text = text.strip()
        with self.lock:
            for turn in self.mobile_turns:
                if turn['clientId'] == client_id:
                    if (turn['text'], turn['kind']) != (text, kind):
                        raise ValueError('Conflicting client request ID')
                    self.persist()
                    return dict(turn)
            if sum(t['status'] in ('queued', 'running', 'awaiting_approval') for t in self.mobile_turns) >= 20:
                raise ValueError('대기 요청이 20개입니다. 이전 작업을 먼저 처리하세요.')
            while len(self.mobile_turns) >= 200:
                i = next((i for i,t in enumerate(self.mobile_turns) if t['status'] not in ('queued','running','awaiting_approval')), None)
                if i is None: raise ValueError('대화 저장 한도')
                self.mobile_turns.pop(i)
            turn = {'clientId':client_id,'requestId':str(uuid.uuid4()),'text':text,'kind':kind,'status':'queued','createdAt':int(time.time()*1000)}
            self.mobile_turns.append(turn)
            self.persist()
            return dict(turn)

    def update_mobile(self, rid, status, **fields):
        for turn in self.mobile_turns:
            if turn['requestId'] == rid:
                turn.update(status=status, **fields)
                break

    def mobile_history(self):
        with self.lock:
            return {'turns':[dict(t) for t in self.mobile_turns]}

    def mobile_waiting(self):
        with self.lock:
            return any(t['status'] == 'queued' for t in self.mobile_turns)

    def mobile_cancel(self, rid):
        with self.lock:
            turn = next((t for t in self.mobile_turns if t['requestId'] == rid), None)
            if not turn or turn['status'] != 'queued' or (self.pending and self.pending['requestId'] == rid):
                raise ValueError('이미 전달된 작업은 PC에서 취소하세요.')
            self.update_mobile(rid, 'cancelled')
            self.persist()

    def v2_request(self):
        with self.lock:
            if not self.pending:
                turn = next((t for t in self.mobile_turns if t['status'] == 'queued'), None)
                if turn:
                    self.submit(turn['text'], 'mobile', turn['requestId'], turn['kind'])
            request = self.request()
            return {**request, 'kind': self.pending.get('kind', 'task')} if request else None

    def v2_event(self, data):
        with self.lock:
            ack, duplicate = self.inbox.validate(data, self.pending)
            if duplicate:
                self.persist()
                return ack
            self.inbox.progress = data.copy()
            self.update_mobile(data['requestId'], data['status'], toolCount=len(data['tools']), error=data.get('error'))
            if data['terminal']:
                rid = data['requestId']
                if data['status'] == 'completed':
                    answer = data.get('answer') or ''
                    self.accept({'requestId':rid,'answer':answer,'answerDigest':hashlib.sha256(answer.encode()).hexdigest()}, persist=False)
                else:
                    message = data.get('error') or '작업이 중단되었습니다. 도미니온에서 결과를 확인하세요.'
                    self.last = {'requestId':rid,'answer':message,'source':self.pending['source'],'status':data['status']}
                    self.pending = None
            self.inbox.remember(data)
            self.persist()
            return ack


conversation = Conversation(os.environ.get('DOMINION_PLUGIN_STATE') if __name__ == '__main__' else None)
mobile_access = MobileAccess()
live_lock = threading.Lock()
live = {"state": "stopped"}
live_stop = threading.Event()


def live_start(config, managed=False):
    names = ("url", "room", "token", "sttKey", "ttsKey", "voice", "language")
    from session import prepare
    pairing = None
    if managed:
        config, pairing = prepare(config)
    if not all(isinstance(config.get(k), str) and 0 < len(config[k]) <= 4096 for k in names):
        raise ValueError("LiveKit와 음성 모델 설정을 입력하세요.")
    if not config["url"].startswith("wss://") or config["language"] not in ("ko", "en"):
        raise ValueError("LiveKit은 wss:// 주소와 ko/en 언어를 사용하세요.")
    with live_lock:
        if live["state"] not in ("stopped", "failed"):
            raise ValueError("먼저 기존 음성 세션을 중지하세요.")
        if conversation.request() or conversation.mobile_waiting():
            raise ValueError("대기 중인 대화 요청을 먼저 처리하세요.")
        conversation.reset()
        live.clear()
        live.update(state="starting", mode=config.get("mode", "manual"), room=config["room"])
        live_stop.clear()
    def report(status):
        with live_lock:
            if live["state"] != "stopping":
                live["state"] = status
    def run():
        failed = False
        try:
            import asyncio
            from voice import run_live
            asyncio.run(run_live(conversation, config, live_stop, report))
        except Exception:
            failed = True
        finally:
            with live_lock:
                conversation.reset()
                live["state"] = "failed" if failed else "stopped"
                if failed:
                    live["error"] = "음성 연결 또는 통화 종료 확인 실패. LiveKit 콘솔에서 방과 통화 상태를 확인하세요."
    threading.Thread(target=run, daemon=True).start()
    return {"state":"starting", "pairing":pairing, "room":config["room"]}


class Handler(BaseHTTPRequestHandler):
    server_version = "DOMINION-Voice"
    def log_message(self, *args):
        pass

    def local_host(self):
        host = self.headers.get("Host", "").split(":")[0].lower()
        return host in ("127.0.0.1", "localhost")

    def bridge_allowed(self):
        supplied = self.headers.get("Authorization", "")
        return bool(TOKEN) and hmac.compare_digest(supplied, "Bearer " + TOKEN)

    def ui_allowed(self):
        origin = self.headers.get("Origin")
        expected = "http://" + self.headers.get("Host", "")
        return self.local_host() and (origin is None or origin == expected) and hmac.compare_digest(
            self.headers.get("X-Voice-Session", ""), UI_TOKEN)

    def reply(self, code, data=None, content_type="application/json"):
        if data is None:
            raw = b""
        elif isinstance(data, bytes):
            raw = data
        else:
            raw = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        self.end_headers()
        if raw:
            self.wfile.write(raw)

    def body(self):
        if self.headers.get("Content-Type", "").split(";")[0] != "application/json":
            raise ValueError("JSON required")
        n = int(self.headers.get("Content-Length", "0"))
        if not 0 < n <= MAX_BODY:
            raise ValueError("Invalid body length")
        data = json.loads(self.rfile.read(n))
        if not isinstance(data, dict):
            raise ValueError("JSON object required")
        return data

    def mobile_auth(self):
        return mobile_access.authorized(self.headers.get('Authorization', '')) and self.headers.get('Origin') is None

    def do_GET(self):
        if not self.local_host():
            self.reply(403, {"error": "Local host required"})
            return
        if self.path == '/mobile/v1/history':
            if not self.mobile_auth():
                self.reply(401, {'error':'휴대폰 연결이 만료되었습니다.'})
                return
            self.reply(200, conversation.mobile_history())
            return
        if self.path in ("/dominion/v2/capabilities", "/dominion/v2/request"):
            if not self.bridge_allowed():
                self.reply(401, {"error":"Bridge authentication required"})
                return
            if self.path.endswith("/capabilities"):
                self.reply(200, CAPABILITIES)
            else:
                request = conversation.v2_request()
                self.reply(200 if request else 204, request)
        elif self.path == "/dominion/v1/request":
            if not self.bridge_allowed():
                self.reply(401, {"error": "Bridge authentication required"})
                return
            with conversation.lock:
                request = conversation.request() if not (conversation.pending and conversation.pending.get('source') == 'mobile') else None
            self.reply(200 if request else 204, request)
        elif self.path == "/":
            page = (ROOT / "index.html").read_text().replace("__VOICE_SESSION__", UI_TOKEN)
            self.reply(200, page.encode(), "text/html; charset=utf-8")
        elif self.path == "/app.js":
            self.reply(200, (ROOT / "app.js").read_bytes(), "application/javascript")
        elif self.path == "/status" and self.ui_allowed():
            with live_lock:
                state = dict(live)
            self.reply(200, {**conversation.status(), "live": state})
        else:
            self.reply(404, {"error": "Not found"})

    def do_POST(self):
        if not self.local_host():
            self.reply(403, {"error": "Local host required"})
            return
        try:
            if self.path.startswith('/mobile/v1/'):
                if self.headers.get('Origin') is not None:
                    raise PermissionError('Native mobile client required')
                if self.path == '/mobile/v1/pair':
                    self.reply(200, mobile_access.pair(self.body().get('code')))
                    return
                if not self.mobile_auth():
                    raise PermissionError('휴대폰 연결이 만료되었습니다.')
                data = self.body()
                if self.path == '/mobile/v1/send':
                    with live_lock:
                        if live['state'] not in ('stopped','failed'):
                            raise ValueError('실시간 통화를 먼저 종료하세요.')
                        self.reply(201, conversation.mobile_submit(data))
                elif self.path == '/mobile/v1/cancel':
                    conversation.mobile_cancel(data.get('requestId'))
                    self.reply(200, {'ok':True})
                elif self.path == '/mobile/v1/disconnect':
                    mobile_access.revoke()
                    self.reply(200, {'ok':True})
                else:
                    self.reply(404, {'error':'Not found'})
                return
            if self.path == "/dominion/v2/event":
                if not self.bridge_allowed():
                    self.reply(401, {"error":"Bridge authentication required"})
                    return
                self.reply(200, conversation.v2_event(self.body()))
                return
            if self.path == "/dominion/v1/result":
                if not self.bridge_allowed():
                    self.reply(401, {"error": "Bridge authentication required"})
                    return
                self.reply(200, conversation.accept(self.body()))
                return
            if not self.ui_allowed():
                self.reply(403, {"error": "Local session required"})
                return
            data = self.body()
            if self.path == '/mobile-admin/pair':
                self.reply(200, mobile_access.issue())
            elif self.path == '/mobile-admin/revoke':
                mobile_access.revoke()
                self.reply(200, {'ok':True})
            elif self.path == "/submit":
                with live_lock:
                    if live["state"] not in ("stopped", "failed"):
                        raise ValueError("모바일·전화 대화 중에는 브라우저 대화를 사용할 수 없습니다.")
                    rid = conversation.submit(data.get("text"), "browser")
                self.reply(201, {"requestId": rid})
            elif self.path == "/discard":
                conversation.discard(data.get("requestId"))
                self.reply(200, {"ok": True})
            elif self.path == "/live/start":
                self.reply(202, live_start(data))
            elif self.path == "/live/managed":
                if data.get("confirmed") is not True:
                    raise ValueError("음성 연결 및 통화 비용을 확인하세요.")
                self.reply(202, live_start(data, managed=True))
            elif self.path == "/live/stop":
                with live_lock:
                    if live["state"] not in ("stopped", "failed"):
                        live["state"] = "stopping"
                        live_stop.set()
                    status = live["state"]
                self.reply(200, {"state": status})
            else:
                self.reply(404, {"error": "Not found"})
        except PermissionError as exc:
            self.reply(401, {'error':str(exc)[:300]})
        except OSError:
            self.reply(503, {"error":"Plugin state could not be saved"})
        except (ValueError, UnicodeError, json.JSONDecodeError) as exc:
            self.reply(400, {"error": str(exc)[:300]})


if __name__ == "__main__":
    if not TOKEN:
        raise SystemExit("DOMINION bridge credential required")
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
