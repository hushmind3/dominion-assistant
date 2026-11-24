import tempfile
import unittest
from pathlib import Path
from app import Conversation
from test_plugin_sdk import event

class FakeChat:
    def __init__(self):self.on=True;self.calls=[]
    def enabled(self):return self.on
    def complete(self,context,text,evidence=None):
        self.calls.append((context,text,evidence))
        return 'direct: '+text

class DirectLaneTests(unittest.TestCase):
    def test_chat_completes_while_host_task_pending(self):
        model=FakeChat();c=Conversation(chat_client=model)
        t=c.mobile_submit({'clientId':'task','text':'long job','kind':'task'})
        work=c.v2_request()
        a=c.mobile_submit({'clientId':'chat','text':'hello','kind':'chat'})
        self.assertEqual(work['requestId'],t['requestId'])
        self.assertTrue(c.run_direct_once())
        self.assertEqual(c.mobile_result(a['requestId'])['answer'],'direct: hello')
        self.assertEqual(c.v2_request()['requestId'],t['requestId'])
        c.v2_event(event(t['requestId'],'completed','a'*64))
        self.assertEqual(c.mobile_result(t['requestId'])['status'],'completed')
    def test_voice_is_direct_and_never_host_dispatched(self):
        c=Conversation(chat_client=FakeChat())
        rid=c.direct_voice_submit('voice question')
        self.assertIsNone(c.v2_request())
        c.run_direct_once()
        self.assertEqual(c.mobile_result(rid)['source'],'voice')
        self.assertEqual(c.mobile_result(rid)['status'],'completed')
    def test_fallback_and_failure_are_explicit(self):
        class Failing(FakeChat):
            def complete(self,*args):raise RuntimeError('provider failure')
        m=Failing();c=Conversation(chat_client=m)
        a=c.mobile_submit({'clientId':'a','text':'hello','kind':'chat'})
        c.run_direct_once()
        self.assertEqual(c.mobile_result(a['requestId'])['status'],'failed')
        m.on=False
        b=c.mobile_submit({'clientId':'b','text':'fallback','kind':'chat'})
        self.assertEqual(c.v2_request()['requestId'],b['requestId'])
    def test_restart_does_not_repeat_uncertain_direct_call(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'state.json';c=Conversation(path,chat_client=FakeChat())
            a=c.mobile_submit({'clientId':'a','text':'hello','kind':'chat'})
            c.update_mobile(a['requestId'],'running');c.persist()
            c=Conversation(path,chat_client=FakeChat())
            self.assertEqual(c.mobile_result(a['requestId'])['status'],'interrupted')
            self.assertFalse(c.run_direct_once())
