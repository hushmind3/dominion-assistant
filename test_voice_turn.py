import unittest
from voice_turn import VoiceTurnGate
from app import Conversation
from test_plugin_sdk import event

class VoiceTurnTests(unittest.TestCase):
    def test_interruption_invalidates_old_reply(self):
        g=VoiceTurnGate();g.offer('old');epoch,text=g.take()
        g.interrupt();g.offer('new');g.offer('latest')
        self.assertFalse(g.current(epoch));self.assertEqual(g.take()[1],'latest')
    def test_live_conversation_appears_in_mobile_history(self):
        c=Conversation();rid=c.submit('안녕','live',kind='chat')
        c.v2_event(event(rid,'completed','b'*64))
        self.assertEqual(c.mobile_history()['turns'][0]['source'],'voice')
        self.assertEqual(c.mobile_history()['turns'][0]['answer'],'완료')
    def test_phone_not_in_personal_timeline(self):
        c=Conversation();c.phone_begin('call','+821012345678','문의')
        rid=c.submit('안녕하세요','phone',kind='chat',phone_id='call',objective='문의')
        c.v2_event(event(rid,'completed','c'*64))
        self.assertEqual(c.mobile_history()['turns'],[])
