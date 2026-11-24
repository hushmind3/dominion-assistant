import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from cloud_link import CloudLink,compact

class CloudLinkTests(unittest.TestCase):
    def test_reject_arbitrary_local_routes(self):
        c=CloudLink()
        for route in ('../status','/','pair','disconnect','admin','../../dominion/v2/request'):
            self.assertEqual(c.local({'route':route,'body':{}})[0],403)
    def test_owner_is_pinned_and_device_survives_restart(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'cloud.json';c=CloudLink(p)
            c.flight={'token':'account-token','accountId':'google:a','until':10**12}
            with patch('cloud_link.cloud',return_value={'id':'device','account_id':'google:a'}):
                c.register('PC',True)
            c=CloudLink(p);self.assertEqual(c.status()['accountId'],'google:a')
            self.assertNotIn('account-token',p.read_text())
            with patch('cloud_link.cloud',return_value={'ok':True}):c.disable()
            c.flight={'token':'other','accountId':'google:b','until':10**12}
            with self.assertRaises(ValueError):c.register('PC',True)
    def test_history_caps_preserve_active(self):
        active={'status':'running','createdAt':0,'requestId':'active'}
        history={'turns':[active]+[{'status':'completed','createdAt':i+1,'answer':'x'*12000} for i in range(200)]}
        status,value=compact('history',history)
        self.assertEqual(status,200);self.assertTrue(value['cloudHistoryLimited'])
        self.assertIn(active,value['turns']);self.assertEqual(value['turns'][-1]['createdAt'],200)

    def test_shared_app_login_contract(self):
        c=CloudLink()
        replies=[{'id':'pair','verification_uri':'https://dominion-cloud.dominion-hushmind.workers.dev/connect?code=pair','expires_in':600},{'status':'approved'},{'account_id':'google:a'}]
        with patch('cloud_link.cloud',side_effect=replies) as call:
            self.assertIn('/connect?code=',c.start()['url'])
            self.assertEqual(c.login_status(),{'status':'done','accountId':'google:a'})
            self.assertEqual(call.call_args_list[0].args[0],'/v1/apps/start')
            self.assertEqual(call.call_args_list[0].args[1]['capability'],'assistant_pc')
            self.assertTrue(call.call_args_list[1].kwargs['auth'].startswith('Bearer dapp_'))
            self.assertEqual(call.call_args_list[2].args[0],'/v1/me')
