import tempfile
import unittest
from pathlib import Path
from app import Conversation

def event(rid, status="completed", eid="a"*64):
    return {"requestId":rid,"runId":"run-1","status":status,"terminal":status not in ("running","queued","awaiting_approval"),
            "answer":"완료" if status=="completed" else None,"error":None,
            "approvalRequired":status=="awaiting_approval","tools":[],"eventId":eid}

class PluginSDKTests(unittest.TestCase):
    def test_restart_and_duplicate_terminal_ack(self):
        with tempfile.TemporaryDirectory() as root:
            path=Path(root)/"state.json"
            c=Conversation(path);rid=c.submit("홈택스 검색","browser")
            self.assertEqual(c.v2_request()["kind"],"task")
            c=Conversation(path);self.assertEqual(c.v2_request()["requestId"],rid)
            c.v2_event(event(rid,"running","b"*64))
            self.assertEqual(c.status()["progress"]["status"],"running")
            with self.assertRaises(ValueError): c.discard(rid)
            e=event(rid);ack=c.v2_event(e)
            c=Conversation(path)
            self.assertEqual(c.v2_event(e),ack)
            self.assertIsNone(c.v2_request())
            self.assertEqual(c.answer(rid),"완료")
            bad=dict(e,answer="changed")
            with self.assertRaises(ValueError): c.v2_event(bad)
    def test_failure_clears_request_without_fake_success(self):
        c=Conversation();rid=c.submit("work","browser")
        e=event(rid,"failed")
        c.v2_event(e)
        self.assertIsNone(c.v2_request())
        self.assertIsNone(c.answer(rid))
        self.assertEqual(c.status()["last"]["status"],"failed")
        self.assertNotEqual(c.submit("next","browser"),rid)
    def test_invalid_event_cannot_settle_another_request(self):
        c=Conversation();rid=c.submit("work","browser")
        with self.assertRaises(ValueError): c.v2_event(event("00000000-0000-4000-8000-000000000000"))
        self.assertEqual(c.v2_request()["requestId"],rid)
