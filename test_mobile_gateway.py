import tempfile
import unittest
from pathlib import Path
from app import Conversation
from mobile_gateway import MobileAccess
from test_plugin_sdk import event
from lan_relay import ROUTES, private
from ipaddress import ip_address

class MobileTests(unittest.TestCase):
    def test_pair_rotate_and_revoke(self):
        a = MobileAccess()
        code = a.issue()['code']
        with self.assertRaises(PermissionError): a.pair('wrong')
        token = a.pair(code)['token']
        self.assertTrue(a.authorized('Bearer '+token))
        with self.assertRaises(PermissionError): a.pair(code)
        new = a.pair(a.issue()['code'])['token']
        self.assertFalse(a.authorized('Bearer '+token))
        self.assertTrue(a.authorized('Bearer '+new))
        a.revoke()
        self.assertFalse(a.authorized('Bearer '+new))
    def test_queue_durable_and_idempotent(self):
        with tempfile.TemporaryDirectory() as root:
            path=Path(root)/'state.json'
            c=Conversation(path)
            first={'clientId':'one','kind':'task','text':'첫 작업'}
            second={'clientId':'two','kind':'chat','text':'대화'}
            a=c.mobile_submit(first); b=c.mobile_submit(second)
            self.assertEqual(c.mobile_submit(first),a)
            with self.assertRaises(ValueError): c.mobile_submit(dict(first,text='changed'))
            self.assertEqual(c.v2_request()['requestId'],a['requestId'])
            with self.assertRaises(ValueError): c.mobile_cancel(a['requestId'])
            c=Conversation(path)
            self.assertEqual(c.v2_request()['kind'],'task')
            c.v2_event(event(a['requestId'],'running','b'*64))
            c.v2_event(event(a['requestId'],'completed','c'*64))
            c=Conversation(path)
            self.assertEqual(c.mobile_history()['turns'][0]['answer'],'완료')
            self.assertEqual(c.v2_request()['requestId'],b['requestId'])
            self.assertEqual(c.v2_request()['kind'],'chat')
            c.v2_event(event(b['requestId'],'failed','d'*64))
            self.assertEqual(c.mobile_history()['turns'][1]['status'],'failed')
            self.assertIsNone(c.v2_request())
    def test_cancel_unsent(self):
        c=Conversation()
        t=c.mobile_submit({'clientId':'one','text':'hello','kind':'chat'})
        c.mobile_cancel(t['requestId'])
        self.assertEqual(c.mobile_history()['turns'][0]['status'],'cancelled')
        self.assertIsNone(c.v2_request())
    def test_relay_does_not_route_admin_or_sdk(self):
        self.assertNotIn('/',ROUTES['GET'])
        self.assertNotIn('/dominion/v2/request',ROUTES['GET'])
        self.assertNotIn('/mobile-admin/pair',ROUTES['POST'])
        self.assertTrue(private(ip_address('192.168.1.1')))
        self.assertFalse(private(ip_address('127.0.0.1')))
