"""Outbound, scoped DOMINION Cloud relay. No inbound port or host/admin proxy."""
import json
import re
import base64
import os
import secrets
import tempfile
import threading
import time
import urllib.request
import urllib.error
from pathlib import Path
from attachments import NoRedirect

BASE='https://dominion-cloud.dominion-hushmind.workers.dev'
READ={'history','phone'}
WRITE={'send','cancel','profile','attachment','phone/start','phone/stop','voice/start','voice/stop'}
MAX_RESULT=800000

def request(url,method='POST',data=None,auth=None,timeout=15,extra=None):
    headers={'Accept':'application/json',**(extra or {})}
    if auth:headers['Authorization']=auth
    raw=None if data is None else json.dumps(data,ensure_ascii=False,separators=(',',':')).encode()
    if raw is not None:headers['Content-Type']='application/json'
    req=urllib.request.Request(url,data=raw,headers=headers,method=method)
    try:r=urllib.request.build_opener(NoRedirect()).open(req,timeout=timeout)
    except urllib.error.HTTPError as e:r=e
    with r:
        b=r.read(16*1024*1024+1)
        if len(b)>16*1024*1024:raise ValueError('Response too large')
        try:obj=json.loads(b)
        except (ValueError,UnicodeError):obj={'error':'Invalid service response'}
        return r.status,obj

def cloud(path,data=None,auth=None,method='POST'):
    status,value=request(BASE+'/v1/assistant/'+path,method,data,auth)
    if not 200<=status<300:raise CloudError(status,value.get('error','Cloud connection failed'))
    return value

class CloudError(ValueError):
    def __init__(self,status,message):
        super().__init__(message);self.status=status

def compact(route,obj):
    if route=='history' and isinstance(obj,dict):
        obj=dict(obj); turns=obj.get('turns',[])
        # Preserve active work and newest completed messages within D1's row cap.
        active=[t for t in turns if t.get('status') in ('queued','running','awaiting_approval')]
        recent=[t for t in turns if t not in active]
        while recent and len(json.dumps({**obj,'turns':active+recent},ensure_ascii=False).encode())>MAX_RESULT:
            recent.pop(0)
        obj['turns']=sorted(active+recent,key=lambda t:t.get('createdAt',0))
        obj['cloudHistoryLimited']=len(obj['turns'])<len(turns)
    if route=='phone' and isinstance(obj,dict):
        obj=dict(obj);calls=list(obj.get('calls',[]))
        while len(calls)>1 and len(json.dumps({**obj,'calls':calls},ensure_ascii=False).encode())>MAX_RESULT:calls.pop(0)
        obj['calls']=calls
    if len(json.dumps(obj,ensure_ascii=False).encode())>MAX_RESULT:
        return 413,{'error':'Cloud response exceeds current relay capacity'}
    return 200,obj

class CloudLink:
    def __init__(self,path=None):
        self.lock=threading.RLock()
        self.path=Path(path) if path else None
        self.config=None
        self.owner=None
        self.flight=None
        self.error=None
        self.online=False
        self.internal=secrets.token_urlsafe(32)
        if self.path and self.path.exists():
            saved=json.loads(self.path.read_text(encoding='utf-8'))
            self.owner=saved.get('owner')
            self.config=saved.get('device')
    def save(self):
        if not self.path:return
        self.path.parent.mkdir(parents=True,exist_ok=True)
        fd,tmp=tempfile.mkstemp(prefix='.cloud-',dir=self.path.parent)
        try:
            with os.fdopen(fd,'w') as f:
                json.dump({'owner':self.owner,'device':self.config},f);f.flush();os.fsync(f.fileno())
            os.replace(tmp,self.path)
        finally:
            if os.path.exists(tmp):os.unlink(tmp)
    def start(self):
        data=cloud('auth/start',{})
        url=data.get('url','')
        if not url.startswith(BASE+'/v1/assistant/auth/open?'):raise ValueError('Invalid login URL')
        with self.lock:
            self.flight={'id':data['id'],'poll_token':data['poll_token'],'until':time.monotonic()+600}
        return {'url':url}
    def login_status(self):
        with self.lock:
            flight=self.flight
            if not flight or time.monotonic()>=flight['until']:raise ValueError('로그인 요청이 만료되었습니다.')
            if 'token' in flight:return {'status':'done','accountId':flight['accountId']}
            p={k:flight[k] for k in ('id','poll_token')}
        r=cloud('auth/status',p)
        with self.lock:
            if self.flight is not flight:raise ValueError('로그인 요청이 변경되었습니다.')
            if r.get('status')=='done':
                flight['token']=r['token'];flight['accountId']=r['account_id']
                return {'status':'done','accountId':flight['accountId']}
            return {'status':'waiting'}
    def register(self,name,confirmed):
        if confirmed is not True or not isinstance(name,str) or not name.strip() or len(name)>80:raise ValueError('기기 등록을 확인하세요.')
        with self.lock:
            flight=self.flight
            if not flight or 'token' not in flight or time.monotonic()>=flight['until']:raise ValueError('먼저 Google 로그인하세요.')
            account=flight['accountId']
            if self.owner and self.owner!=account:raise ValueError('기존 개인 데이터는 다른 계정에 연결할 수 없습니다. 별도 인스턴스를 사용하세요.')
            if self.config:raise ValueError('기존 기기를 먼저 해제하세요.')
            token=flight.setdefault('deviceToken',secrets.token_urlsafe(32))
            result=cloud('register',{'name':name.strip(),'deviceToken':token},'Bearer '+flight['token'])
            if result.get('account_id')!=account:raise ValueError('Account mismatch')
            self.owner=account;self.config={'id':result['id'],'name':name.strip(),'token':token};self.save();self.flight=None;self.error=None
            return self.status()
    def status(self):
        with self.lock:
            return {'enabled':bool(self.config),'online':self.online,'accountId':self.owner,'name':self.config.get('name') if self.config else None,'error':self.error}
    def disable(self):
        with self.lock:
            if self.config:
                try:cloud('device/revoke',{},'Device '+self.config['token'])
                except CloudError as e:
                    if e.status!=401:raise
                self.config=None;self.save()
            self.online=False;self.flight=None;self.error=None
            return self.status()
    def local(self,work):
        route=work.get('route')
        if isinstance(route,str) and re.fullmatch(r'attachment/[a-f0-9]{64}',route):
            if work.get('body') is not None:return 400,{'error':'Invalid read'}
            req=urllib.request.Request('http://127.0.0.1:8080/mobile/v1/'+route,headers={'X-Assistant-Cloud-Local':self.internal})
            try:
                with urllib.request.build_opener(NoRedirect()).open(req,timeout=15) as response:
                    mime=response.headers.get_content_type();raw=response.read(600001)
                if mime not in ('image/jpeg','image/png','image/webp'):return 415,{'error':'Only image previews are supported'}
                if len(raw)>600000:return 413,{'error':'Cloud preview exceeds 600KB'}
                return 200,{'mime':mime,'base64':base64.b64encode(raw).decode()}
            except urllib.error.HTTPError as e:return e.code,{'error':'Attachment unavailable'}
        if not isinstance(route,str) or route not in READ|WRITE:return 403,{'error':'Operation not allowed'}
        data=work.get('body')
        if route in READ and data is not None:return 400,{'error':'Invalid read'}
        if route in WRITE and not isinstance(data,dict):return 400,{'error':'Invalid write'}
        status,obj=request('http://127.0.0.1:8080/mobile/v1/'+route,'GET' if route in READ else 'POST',data,timeout=100,extra={'X-Assistant-Cloud-Local':self.internal})
        if 200<=status<300:
            cap,obj=compact(route,obj)
            if cap!=200:return cap,obj
        return status,obj
    def worker(self):
        while True:
            with self.lock:c=self.config
            if not c:time.sleep(2);continue
            try:
                auth='Device '+c['token']
                result=cloud('device/pull',{},auth)
                with self.lock:
                    if self.config is not c:continue
                    self.online=True;self.error=None
                work=result.get('request')
                if not work:time.sleep(3);continue
                # The cloud claim has no automatic replay. Check local revocation before executing.
                with self.lock:
                    if self.config is not c:continue
                try:status,data=self.local(work)
                except Exception:status,data=503,{'error':'PC operation outcome uncertain; check the local task or call history.'}
                reply={'id':work['id'],'status':status,'data':data}
                for attempt in range(3):
                    try:cloud('device/reply',reply,auth);break
                    except CloudError as e:
                        if e.status in (401,409):break
                        time.sleep(1)
                    except Exception:time.sleep(1)
            except Exception as e:
                with self.lock:
                    if self.config is c:
                        self.online=False;self.error='Cloud connection unavailable'
                time.sleep(6)
