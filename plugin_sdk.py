"""Generic DOMINION plugin v2 event journal; no voice-specific dependency."""
import hashlib
import json
import os
import re
import tempfile
import uuid
from pathlib import Path
from collections import OrderedDict

CAPABILITIES = {"protocol":"dominion.plugin","version":2,"transport":"host-pull","events":True}
ACTIVE = {"queued","running","awaiting_approval"}
TERMINAL = {"completed","failed","cancelled","exhausted","interrupted","in_doubt","needs_review"}
def fingerprint(data):
    return hashlib.sha256(json.dumps(data,ensure_ascii=False,sort_keys=True,separators=(",",":")).encode()).hexdigest()
class PluginInbox:
    """Application-owned durable state and bounded, idempotent event receipts.
    Caller serializes updates and calls save before returning HTTP 200.
    """
    def __init__(self,path=None):
        self.path = Path(path) if path else None
        self.state = {}
        self.progress = None
        self.events = OrderedDict()
        if self.path and self.path.exists():
            data = json.loads(self.path.read_text(encoding="utf-8"))
            if data.get("version") != 1:
                raise ValueError("Unsupported plugin state")
            self.state = data["state"]
            self.progress = data.get("progress")
            self.events = OrderedDict(data.get("events", []))
    def save(self,state):
        if self.path:
            data = {"version":1,"state":state,"progress":self.progress,"events":list(self.events.items())}
            self.path.parent.mkdir(parents=True,exist_ok=True)
            fd,tmp=tempfile.mkstemp(prefix=".plugin-",dir=self.path.parent)
            try:
                with os.fdopen(fd,"w",encoding="utf-8") as f:
                    json.dump(data,f,ensure_ascii=False,separators=(",",":"))
                    f.flush()
                    os.fsync(f.fileno())
                os.replace(tmp,self.path)
            finally:
                if os.path.exists(tmp): os.unlink(tmp)
        self.state=state
    def validate(self,event,pending):
        required={"requestId","runId","status","terminal","answer","error","approvalRequired","tools","eventId"}
        if not isinstance(event,dict) or set(event)!=required:
            raise ValueError("Invalid plugin event schema")
        rid,eid=event["requestId"],event["eventId"]
        if not isinstance(rid,str) or str(uuid.UUID(rid))!=rid or not isinstance(eid,str) or not re.fullmatch("[a-f0-9]{64}",eid):
            raise ValueError("Invalid event identity")
        status=event["status"]
        if status not in ACTIVE|TERMINAL or event["terminal"] is not (status in TERMINAL):
            raise ValueError("Invalid event state")
        if not isinstance(event["runId"],str) or not event["runId"] or len(event["runId"])>160:
            raise ValueError("Invalid run")
        if event["approvalRequired"] is not (status=="awaiting_approval"):
            raise ValueError("Invalid approval state")
        if event["answer"] is not None and (status!="completed" or not isinstance(event["answer"],str) or len(event["answer"])>12000):
            raise ValueError("Invalid answer")
        if event["error"] is not None and (not isinstance(event["error"],str) or len(event["error"])>1000):
            raise ValueError("Invalid error")
        if not isinstance(event["tools"],list) or len(event["tools"])>12:
            raise ValueError("Invalid tool receipts")
        ack={"requestId":rid,"eventId":eid}
        prior=self.events.get(eid)
        if prior is not None:
            if prior!=fingerprint(event): raise ValueError("Event identity conflict")
            return ack,True
        if not pending or pending["requestId"]!=rid:
            raise ValueError("No matching active request")
        return ack,False
    def remember(self,event):
        self.events[event["eventId"]]=fingerprint(event)
        while len(self.events)>128: self.events.popitem(last=False)
