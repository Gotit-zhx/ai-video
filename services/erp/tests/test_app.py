import os
import sys
import tempfile
import unittest
import io
import json
import http.cookies
from unittest.mock import patch

sandbox=tempfile.TemporaryDirectory()
os.environ['ERP_DATA_DIR']=sandbox.name
os.environ['ERP_ORIGIN']='http://localhost:8137'
os.environ['ERP_COOKIE_SECURE']='false'
os.environ['ERP_ADMIN_PASSWORD']='UnitTestOnly-StrongPassword-123'
sys.path.insert(0,os.path.dirname(os.path.dirname(__file__)))
import server
import connectors

class Client:
    def __init__(self):self.cookie='';self.ip=server.uid()
    def call(self,path,method='GET',data=None,origin=True):
        content=json.dumps(data or {}).encode(); captured={}
        env={'REQUEST_METHOD':method,'PATH_INFO':'/erphoyee'+path,'wsgi.input':io.BytesIO(content),'CONTENT_LENGTH':str(len(content)),'CONTENT_TYPE':'application/json','HTTP_COOKIE':self.cookie,'REMOTE_ADDR':self.ip,'HTTP_ORIGIN':server.ORIGIN if origin else 'https://evil.test','HTTP_X_ERPHOYEE':'1'}
        def start(status,headers):captured.update(status=int(status.split()[0]),headers=dict(headers))
        raw=b''.join(server.application(env,start))
        if 'Set-Cookie' in captured['headers']:self.cookie=captured['headers']['Set-Cookie'].split(';')[0]
        try:result=json.loads(raw)
        except ValueError:result=raw
        return captured['status'],result,captured['headers']
    def demo(self):assert self.call('/api/demo','POST')[0]==200;return self
    def boot(self):return self.call('/api/bootstrap')[1]

class AppTests(unittest.TestCase):
    def test_demo_isolation_and_cannot_edit_other_workspace(self):
        a,b=Client().demo(),Client().demo();pa=a.boot()['products'][0]
        self.assertNotEqual(a.boot()['workspace']['id'],b.boot()['workspace']['id'])
        self.assertEqual(b.call('/api/products/'+pa['id'],'PATCH',pa)[0],404)
        self.assertEqual(len(b.boot()['orders']),12)
    def test_csrf_and_session_required(self):
        c=Client();self.assertEqual(c.call('/api/bootstrap')[0],401)
        self.assertEqual(c.call('/api/demo','POST',origin=False)[0],403)
        c.demo();self.assertEqual(c.call('/api/reset','POST',origin=False)[0],403)
    def test_inventory_persists_and_conflicting_edit_rejected(self):
        c=Client().demo();p=c.boot()['products'][0];p['stock']=27
        self.assertEqual(c.call('/api/products/'+p['id'],'PATCH',p)[0],200)
        saved=next(x for x in c.boot()['products'] if x['id']==p['id'])
        self.assertEqual(saved['stock'],27)
        self.assertEqual(c.call('/api/products/'+p['id'],'PATCH',p)[0],409)
        saved['stock']=-1;self.assertEqual(c.call('/api/products/'+p['id'],'PATCH',saved)[0],400)
    def test_concurrent_inventory_updates_only_one_wins(self):
        from concurrent.futures import ThreadPoolExecutor
        c=Client().demo();p=c.boot()['products'][0]
        def change(n):
            payload=dict(p,stock=n)
            return c.call('/api/products/'+p['id'],'PATCH',payload)[0]
        with ThreadPoolExecutor(max_workers=4) as executor:
            statuses=list(executor.map(change,[201,202,203,204]))
        self.assertEqual(statuses.count(200),1)
        self.assertEqual(statuses.count(409),3)
    def test_sku_duplicate_and_cross_tenant_shop_rejected(self):
        a,b=Client().demo(),Client().demo();p=a.boot()['products'][0]
        self.assertEqual(a.call('/api/products','POST',p)[0],409)
        p['sku']='new';p['shop_id']=b.boot()['shops'][0]['id']
        self.assertEqual(a.call('/api/products','POST',p)[0],400)
    def test_order_sequence_and_tracking_required(self):
        c=Client().demo();o=next(x for x in c.boot()['orders'] if x['status']=='pending')
        endpoint='/api/orders/'+o['id']
        self.assertEqual(c.call(endpoint,'PATCH',{'version':1,'status':'completed'})[0],409)
        self.assertEqual(c.call(endpoint,'PATCH',{'version':1,'status':'processing'})[0],200)
        self.assertEqual(c.call(endpoint,'PATCH',{'version':2,'status':'shipped','tracking':''})[0],400)
        self.assertEqual(c.call(endpoint,'PATCH',{'version':2,'status':'shipped','tracking':'DEMO-1'})[0],200)
        self.assertEqual(c.call(endpoint,'PATCH',{'version':3,'status':'completed'})[0],200)
    def test_csv_formula_injection_escaped(self):
        c=Client().demo();p=c.boot()['products'][0];p['name']='=HYPERLINK("evil")'
        self.assertEqual(c.call('/api/products/'+p['id'],'PATCH',p)[0],200)
        status,raw,h=c.call('/api/export/products')
        self.assertEqual(status,200);self.assertIn(b"'=HYPERLINK",raw);self.assertIn('attachment',h['Content-Disposition'])
    def test_demo_cannot_use_live_authorization_or_read_tickets(self):
        c=Client().demo();self.assertEqual(c.call('/api/authorize/shopee','POST')[0],403)
        self.assertEqual(c.boot()['tickets'],[])
        self.assertEqual(c.call('/api/tickets/HY-123','POST',{'status':'closed'})[0],404)
    def test_support_input_and_owner_visibility(self):
        c=Client();self.assertEqual(c.call('/api/support','POST',{'email':'bad','category':'privacy','message':'test','consent':True})[0],400)
        status,r,_=c.call('/api/support','POST',{'email':'review@example.com','category':'privacy','message':'test request','consent':True})
        self.assertEqual(status,200)
        a=Client();self.assertEqual(a.call('/api/login','POST',{'username':'admin','password':os.environ['ERP_ADMIN_PASSWORD']})[0],200)
        self.assertTrue(any(t['id']==r['ticket'] for t in a.boot()['tickets']))
        self.assertEqual(a.call('/api/tickets/'+r['ticket'],'POST',{'status':'closed'})[0],200)
    def test_delete_removes_session_and_records(self):
        c=Client().demo();w=c.boot()['workspace']['id']
        self.assertEqual(c.call('/api/workspace','DELETE',{'confirmation':'bad'})[0],400)
        self.assertEqual(c.call('/api/workspace','DELETE',{'confirmation':'DELETE'})[0],200)
        self.assertEqual(c.call('/api/bootstrap')[0],401)
        with server.connect() as db:self.assertEqual(db.execute('SELECT count(*) FROM records WHERE workspace=?',(w,)).fetchone()[0],0)
    def test_owner_reset_forbidden_and_wrong_password(self):
        a=Client();self.assertEqual(a.call('/api/login','POST',{'username':'admin','password':'incorrect'})[0],401)
        self.assertEqual(a.call('/api/login','POST',{'username':'admin','password':os.environ['ERP_ADMIN_PASSWORD']})[0],200)
        self.assertEqual(a.call('/api/reset','POST')[0],403)
    def test_demo_cleanup(self):
        c=Client().demo();w=c.boot()['workspace']['id']
        with server.connect() as db:
            db.execute('UPDATE workspaces SET created=0 WHERE id=?',(w,));server.cleanup(db)
        self.assertEqual(c.call('/api/bootstrap')[0],401)
    @patch("server.time.time", return_value=2000000000)
    def test_ratelimit(self, clock):
        c=Client()
        for _ in range(8):self.assertEqual(c.call('/api/login','POST',{'username':'nobody','password':'wrong'})[0],401)
        self.assertEqual(c.call('/api/login','POST',{'username':'nobody','password':'wrong'})[0],429)
    def test_path_traversal_and_real_pages(self):
        c=Client()
        self.assertEqual(c.call('/assets/../server.py')[0],404)
        for path in ['/','/app','/login','/privacy','/terms','/support','/data-deletion','/guide','/assets/common.js']:
            self.assertEqual(c.call(path)[0],200,path)
    def test_oauth_state_single_use_and_session_binding(self):
        c=Client();c.call('/api/login','POST',{'username':'admin','password':os.environ['ERP_ADMIN_PASSWORD']})
        with server.connect() as db:
            with patch('connectors.checked',return_value=object()),patch.dict(os.environ,{'ERP_SHOPEE_PARTNER_ID':'123'}):
                url=connectors.authorization_url(db,'owner','session-a','shopee')
                from urllib.parse import urlparse,parse_qs
                state=parse_qs(urlparse(url).query)['state'][0]
                with self.assertRaises(server.Problem):connectors.callback(db,'owner','session-b','shopee',{'state':[state],'code':['anything']})
                self.assertEqual(db.execute('SELECT count(*) FROM oauth_states WHERE state=?',(server.hashed(state),)).fetchone()[0],1)
                with self.assertRaises(server.Problem):connectors.callback(db,'owner','session-a','shopee',{'state':[state],'error':['denied']})
                self.assertEqual(db.execute('SELECT count(*) FROM oauth_states WHERE state=?',(server.hashed(state),)).fetchone()[0],0)
    def test_cookie_and_secret_non_disclosure(self):
        c=Client();_,_,h=c.call('/api/demo','POST')
        self.assertIn('HttpOnly',h['Set-Cookie']);self.assertIn('Path=/erphoyee',h['Set-Cookie'])
        data=json.dumps(c.boot());self.assertNotIn('password',data);self.assertNotIn('encrypted',data)

if __name__=='__main__': unittest.main(verbosity=2)
