import json
import threading
import unittest
import urllib.request
import urllib.error
from http.server import ThreadingHTTPServer
import app
from mobile_gateway import MobileAccess

class MobileHTTPTests(unittest.TestCase):
    def setUp(self):
        self.old = app.conversation, app.mobile_access, app.TOKEN
        app.conversation, app.mobile_access, app.TOKEN = app.Conversation(), MobileAccess(), 'host-secret'
        self.server = ThreadingHTTPServer(('127.0.0.1',0),app.Handler)
        self.thread = threading.Thread(target=self.server.serve_forever,daemon=True)
        self.thread.start()
        self.url='http://127.0.0.1:'+str(self.server.server_port)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        app.conversation, app.mobile_access, app.TOKEN = self.old

    def call(self,path,body=None,token=None,extra=None):
        headers=dict(extra or {})
        if body is not None: headers['Content-Type']='application/json'
        if token: headers['Authorization']='Bearer '+token
        req=urllib.request.Request(self.url+path,
            data=json.dumps(body).encode() if body is not None else None,headers=headers)
        try:
            response=urllib.request.urlopen(req,timeout=3)
        except urllib.error.HTTPError as exc:
            response=exc
        with response:
            raw=response.read()
            return response.code,json.loads(raw) if raw else None

    def test_pair_send_pull_and_revoke(self):
        self.assertEqual(self.call('/mobile/v1/history')[0],401)
        _,p=self.call('/mobile-admin/pair',{},extra={'X-Voice-Session':app.UI_TOKEN})
        _,auth=self.call('/mobile/v1/pair',{'code':p['code']})
        token=auth['token']
        self.assertEqual(self.call('/dominion/v2/request',token=token)[0],401)
        self.assertEqual(self.call('/mobile-admin/pair',{},token=token)[0],403)
        status,turn=self.call('/mobile/v1/send',{'clientId':'one','text':'hello','kind':'chat'},token)
        self.assertEqual(status,201)
        _,history=self.call('/mobile/v1/history',token=token)
        self.assertEqual(history['turns'][0]['requestId'],turn['requestId'])
        _,work=self.call('/dominion/v2/request',token='host-secret')
        self.assertEqual(work['requestId'],turn['requestId'])
        self.assertEqual(work['kind'],'chat')
        self.assertEqual(self.call('/dominion/v1/request',token='host-secret')[0],204)
        from test_plugin_sdk import event
        self.assertEqual(self.call('/dominion/v2/event',event(work['requestId']), 'host-secret')[0],200)
        _,history=self.call('/mobile/v1/history',token=token)
        self.assertEqual(history['turns'][0]['answer'],'완료')
        self.assertEqual(self.call('/mobile/v1/disconnect',{},token)[0],200)
        self.assertEqual(self.call('/mobile/v1/history',token=token)[0],401)
