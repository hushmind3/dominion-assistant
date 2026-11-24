"""Single-owner mobile authentication. Never exposes host SDK credentials."""
import hmac
import secrets
import threading
import time

class MobileAccess:
    def __init__(self):
        self.lock = threading.RLock()
        self.code = None
        self.token = None
        self.pair_until = self.until = 0
        self.attempts = 0

    def issue(self):
        with self.lock:
            self.code = secrets.token_urlsafe(24)
            self.pair_until = time.monotonic() + 300
            self.attempts = 0
            return {"code": self.code, "expiresIn": 300}

    def pair(self, code):
        with self.lock:
            if not self.code or time.monotonic() >= self.pair_until or self.attempts >= 5:
                raise PermissionError("연결 코드가 만료되었습니다.")
            self.attempts += 1
            if not isinstance(code, str) or not hmac.compare_digest(code.encode(), self.code.encode()):
                raise PermissionError("연결 코드가 올바르지 않습니다.")
            self.code = None
            self.token = secrets.token_urlsafe(32)
            self.until = time.monotonic() + 86400
            return {"token": self.token, "expiresIn": 86400}

    def authorized(self, header):
        with self.lock:
            return bool(self.token and time.monotonic() < self.until and
                        hmac.compare_digest(header.encode(), ("Bearer " + self.token).encode()))

    def revoke(self):
        with self.lock:
            self.token = self.code = None
            self.until = self.pair_until = 0
