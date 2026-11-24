import json
import unittest
from unittest.mock import patch
from cloud_link import CloudLink

class Socket:
    def __init__(self):self.messages=[]
    def send(self,raw):self.messages.append(json.loads(raw))

class EventTests(unittest.TestCase):
    def test_event_only_dispatch_and_revoked_socket(self):
        link=CloudLink();sock=Socket();config={'token':'secret'}
        link.config=config;link.socket=sock
        with patch.object(link,'local',return_value=(201,{'ok':True})) as local:
            work={'type':'request','id':'request','route':'send','body':{}}
            link.handle(sock,config,work)
            self.assertEqual(sock.messages,[{'type':'reply','id':'request','status':201,'data':{'ok':True}}])
            link.socket=None
            link.handle(sock,config,work)
            local.assert_called_once()
    def test_state_invalidation_has_no_network(self):
        link=CloudLink()
        self.assertFalse(link.change.is_set())
        link.changed();self.assertTrue(link.change.is_set())
