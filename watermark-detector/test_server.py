import os,base64,hashlib,io,json,threading,unittest,secrets
from urllib.request import Request,urlopen
from urllib.error import HTTPError
from PIL import Image
os.environ['WATERMARK_DETECTOR_TOKEN']=secrets.token_hex(24)
os.environ['WATERMARK_DETECTOR_ENGINE']='local-overlay'
import server

class ServiceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.http=server.ThreadingHTTPServer(('127.0.0.1',0),server.Handler)
        cls.thread=threading.Thread(target=cls.http.serve_forever,daemon=True);cls.thread.start()
        cls.url='http://127.0.0.1:'+str(cls.http.server_port)
    @classmethod
    def tearDownClass(cls):
        cls.http.shutdown();cls.http.server_close();cls.thread.join()
    def request(self,body,token=True):
        headers={'Content-Type':'application/json'}
        if token:headers['X-Internal-Token']=os.environ['WATERMARK_DETECTOR_TOKEN']
        return urlopen(Request(self.url+'/detect',data=json.dumps(body).encode(),headers=headers),timeout=10)
    def test_absence_of_match_never_authorizes_upload(self):
        buf=io.BytesIO();Image.new('RGB',(32,32),'white').save(buf,format='PNG');raw=buf.getvalue()
        with self.request({'data':base64.b64encode(raw).decode()}) as response:result=json.load(response)
        self.assertEqual(result['sourceSha256'],hashlib.sha256(raw).hexdigest())
        self.assertEqual(result['status'],'NO_CANDIDATE')
        self.assertFalse(result['canAutoPass']);self.assertTrue(result['requiresReview'])
        self.assertFalse(result['regionsAreRemovalMasks'])
    def test_user_confirmed_blue_cat_is_exact_hash_only(self):
        from pathlib import Path
        raw=Path('/workspace/output/batch-runs/20260908-pink-cat/mockup_watermark_removed-2.png').read_bytes()
        result=server.detect({'data':base64.b64encode(raw).decode()})
        self.assertTrue(result['canAutoPass'])
        self.assertEqual(result['confirmedAbsentSourceSha256'],hashlib.sha256(raw).hexdigest())
        copy=io.BytesIO();Image.open(io.BytesIO(raw)).save(copy,format='JPEG',quality=85)
        self.assertFalse(server.detect({'data':base64.b64encode(copy.getvalue()).decode()})['canAutoPass'])
    def test_unauthorized(self):
        with self.assertRaises(HTTPError) as ctx:self.request({'data':''},False)
        self.assertEqual(ctx.exception.code,401)
    def test_invalid_image(self):
        with self.assertRaises(HTTPError) as ctx:self.request({'data':'!!!!'})
        self.assertEqual(ctx.exception.code,422)

if __name__=='__main__':unittest.main()
