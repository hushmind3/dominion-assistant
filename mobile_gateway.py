"""Single-owner mobile authentication; persist only a hash of the bearer."""
import hashlib
import hmac
import json
import os
import secrets
import tempfile
import threading
import time
from pathlib import Path

TOKEN_LIFETIME=30*86400

class MobileAccess:
    def __init__(self,path=None):
        self.lock=threading.RLock()
        self.path=Path(path) if path else None
        self.code=None
        self.token_hash=None
        self.pair_until=self.until=0
        self.attempts=0
        if self.path and self.path.exists():
            saved=json.loads(self.path.read_text(encoding='utf-8'))
            self.token_hash=saved.get('tokenHash')
            self.until=float(saved.get('until',0))
    def save(self):
        if not self.path:return
        self.path.parent.mkdir(parents=True,exist_ok=True)
        fd,tmp=tempfile.mkstemp(prefix='.mobile-',dir=self.path.parent)
        try:
            with os.fdopen(fd,'w') as out:
                json.dump({'tokenHash':self.token_hash,'until':self.until},out);out.flush();os.fsync(out.fileno())
            os.replace(tmp,self.path)
        finally:
            if os.path.exists(tmp):os.unlink(tmp)
    def issue(self):
        with self.lock:
            self.code=secrets.token_urlsafe(24)
            self.pair_until=time.monotonic()+300
            self.attempts=0
            return {'code':self.code,'expiresIn':300}
    def pair(self,code):
        with self.lock:
            if not self.code or time.monotonic()>=self.pair_until or self.attempts>=5:
                raise PermissionError('연결 코드가 만료되었습니다.')
            self.attempts+=1
            if not isinstance(code,str) or not hmac.compare_digest(code.encode(),self.code.encode()):
                raise PermissionError('연결 코드가 올바르지 않습니다.')
            token=secrets.token_urlsafe(32)
            previous=self.token_hash,self.until
            self.token_hash=hashlib.sha256(token.encode()).hexdigest()
            self.until=time.time()+TOKEN_LIFETIME
            try:self.save()
            except OSError:
                self.token_hash,self.until=previous
                raise
            self.code=None
            return {'token':token,'expiresIn':TOKEN_LIFETIME}
    def authorized(self,header):
        with self.lock:
            return bool(self.token_hash and time.time()<self.until and header.startswith('Bearer ') and hmac.compare_digest(hashlib.sha256(header[7:].encode()).hexdigest(),self.token_hash))
    def revoke(self):
        with self.lock:
            self.token_hash=self.code=None
            self.until=self.pair_until=0
            self.save()
