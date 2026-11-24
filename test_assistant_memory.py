import tempfile
import unittest
from pathlib import Path
from app import Conversation
from assistant_memory import DEFAULT_PROFILE,context
from mobile_gateway import MobileAccess
from test_plugin_sdk import event

class MemoryTests(unittest.TestCase):
    def test_profile_and_relevant_old_turn(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'state.json';c=Conversation(p)
            c.set_profile({'name':'루나','style':'간결하게','language':'ko','notes':['방콕 여행 선호']})
            for i,question in enumerate(['제 선호 차는 녹차입니다','one','two','three','four','five','six']):
                t=c.mobile_submit({'clientId':str(i),'text':question,'kind':'chat'})
                c.v2_request();c.v2_event(event(t['requestId'],'completed',format(i+1,'064x')))
            c=Conversation(p)
            c.mobile_submit({'clientId':'last','text':'선호 차가 뭐였지?','kind':'chat'})
            s=c.v2_request()['instruction']
            self.assertIn('녹차',s);self.assertIn('루나',s);self.assertIn('방콕 여행',s)
    def test_owner_can_remove_memory(self):
        c=Conversation()
        c.set_profile({**DEFAULT_PROFILE,'notes':['private note']})
        c.set_profile({**DEFAULT_PROFILE,'notes':[]})
        self.assertNotIn('private note',str(c.mobile_history()))
    def test_pair_hash_survives_restart_and_revoke(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'access.json';a=MobileAccess(p)
            token=a.pair(a.issue()['code'])['token']
            self.assertNotIn(token,p.read_text())
            a=MobileAccess(p);self.assertTrue(a.authorized('Bearer '+token))
            a.revoke()
            self.assertFalse(MobileAccess(p).authorized('Bearer '+token))

    def test_live_context_does_not_include_phone_history(self):
        c=Conversation()
        c.phone_begin('call','+821012345678','문의')
        rid=c.submit('private caller utterance','phone',kind='chat',phone_id='call',objective='문의')
        c.v2_event(event(rid,'completed','a'*64))
        rid=c.submit('안녕','live',kind='chat')
        self.assertNotIn('private caller utterance',c.v2_request()['instruction'])
