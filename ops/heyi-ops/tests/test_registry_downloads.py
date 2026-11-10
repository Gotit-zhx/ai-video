import io
import sys
import ssl
import tempfile
import time
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from heyi_ops import oci
from heyi_ops.timing import TimingRecorder
from test_transfer import fixture


class RegistryDownloadTest(unittest.TestCase):
    def registry(self,opener):
        image,_,_=fixture()
        with patch.object(oci.urllib.request,'build_opener',return_value=opener):return oci.Registry(image,'synthetic')
    def test_transient_disconnect_restarts_owned_partial_content(self):
        class Interrupted(io.BytesIO):
            def read(self,n=-1):
                if self.tell():raise urllib.error.URLError('synthetic disconnect')
                return super().read(1)
        class Opener:
            calls=0
            def open(self,request,timeout):
                if '/token?' in request.full_url:return io.BytesIO(b'{"token":"synthetic"}')
                self.calls+=1
                return Interrupted(b'abc') if self.calls==1 else io.BytesIO(b'abc')
        opener=Opener();registry=self.registry(opener);output=io.BytesIO()
        registry.download(oci.digest(b'abc'),3,output)
        self.assertEqual(b'abc',output.getvalue());self.assertEqual(2,opener.calls)
    def test_integrity_auth_and_unsafe_redirect_are_never_retried(self):
        for error in (ValueError('Insecure registry redirect'),urllib.error.HTTPError('redacted',401,'no',{},None),urllib.error.URLError(ssl.SSLCertVerificationError('synthetic')),None):
            class Opener:
                calls=0
                def open(self,request,timeout):
                    if '/token?' in request.full_url:return io.BytesIO(b'{"token":"synthetic"}')
                    self.calls+=1
                    if error:raise error
                    return io.BytesIO(b'bad')
            opener=Opener();registry=self.registry(opener);output=io.BytesIO()
            with self.assertRaises((ValueError,urllib.error.URLError)):registry.download(oci.digest(b'abc'),3,output)
            self.assertEqual(1,opener.calls);self.assertEqual(b'',output.getvalue())
    def test_trickle_cannot_exceed_attempt_or_file_wall_budget(self):
        class Trickle(io.BytesIO):
            def read(self,n=-1):time.sleep(.025);return b'x'
        class Opener:
            calls=0
            def open(self,request,timeout):
                if '/token?' in request.full_url:return io.BytesIO(b'{"token":"synthetic"}')
                self.calls+=1;return Trickle()
        opener=Opener();opener.timeouts=[];original_open=opener.open
        def timed_open(request,timeout):
            if '/token?' not in request.full_url:opener.timeouts.append(timeout)
            return original_open(request,timeout)
        opener.open=timed_open;registry=self.registry(opener);output=io.BytesIO();started=time.monotonic()
        with patch.object(oci,'ATTEMPT_SECONDS',.04),patch.object(oci,'FILE_SECONDS',.1):
            with self.assertRaises(TimeoutError):registry.download(oci.digest(b'x'*100),100,output)
        self.assertLess(time.monotonic()-started,.3);self.assertEqual(3,opener.calls);self.assertEqual(b'',output.getvalue())
        self.assertLess(opener.timeouts[-1],.04)
    def test_full_export_attributes_registry_and_tar_work_separately(self):
        image,metadata,blobs=fixture()
        class Opener:
            def open(self,request,timeout):
                if '/token?' in request.full_url:return io.BytesIO(b'{"token":"synthetic"}')
                return io.BytesIO(blobs[request.full_url.rsplit('/',1)[1]])
        recorder=TimingRecorder()
        with tempfile.TemporaryDirectory() as temp,patch.object(oci.urllib.request,'build_opener',return_value=Opener()):
            path=Path(temp)/'image.tar';oci.export_archive(image,path,'synthetic',timings=recorder);oci.validate_archive(path,image)
        phases={p['phase']:p for p in recorder.snapshot()['phases']}
        for name in ('registryResolve','registryDownload','ociExport'):self.assertEqual('passed',phases[name]['status'])
        self.assertEqual(sum(map(len,blobs.values())),phases['registryDownload']['bytes'])

    def test_fast_recoverable_failures_stop_after_three_attempts(self):
        class Opener:
            calls=0
            def open(self,request,timeout):
                if '/token?' in request.full_url:return io.BytesIO(b'{"token":"synthetic"}')
                self.calls+=1;raise urllib.error.URLError('synthetic disconnect')
        opener=Opener();registry=self.registry(opener)
        with self.assertRaises(urllib.error.URLError):registry.download(oci.digest(b'abc'),3,io.BytesIO())
        self.assertEqual(3,opener.calls)
    def test_existing_partial_file_is_not_owned_by_new_attempt(self):
        image,_,_=fixture();item={'digest':oci.digest(b'abc'),'size':3}
        class Opener:
            def open(self,request,timeout):return io.BytesIO(b'{"token":"synthetic"}')
        with tempfile.TemporaryDirectory() as temp,patch.object(oci.urllib.request,'build_opener',return_value=Opener()):
            partial=Path(temp)/(item['digest'][7:]+'.part');partial.write_bytes(b'other operation')
            with self.assertRaises(FileExistsError):oci.export_blobs(image,[item],temp,'synthetic')
            self.assertEqual(b'other operation',partial.read_bytes())

    def test_actual_http_response_premature_eof_retries_to_complete_file(self):
        import http.client
        class Socket:
            def __init__(self,body):self.body=body
            def makefile(self,*args):return io.BytesIO(b'HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\n'+self.body)
        class Opener:
            calls=0
            def open(self,request,timeout):
                self.calls+=1
                response=http.client.HTTPResponse(Socket(b'a' if self.calls==1 else b'abc'))
                response.begin();return response
        opener=Opener();output=io.BytesIO()
        oci.download_file(opener,None,output,3,size=3,expected=oci.digest(b'abc'))
        self.assertEqual(2,opener.calls);self.assertEqual(b'abc',output.getvalue())

    def test_actual_http_truncation_exhausts_three_attempts_and_cleans_partial(self):
        import http.client
        class Socket:
            def makefile(self,*args):return io.BytesIO(b'HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\na')
        class Opener:
            calls=0
            def open(self,request,timeout):
                self.calls+=1;response=http.client.HTTPResponse(Socket());response.begin();return response
        opener=Opener();output=io.BytesIO()
        with self.assertRaises(http.client.IncompleteRead):oci.download_file(opener,None,output,3,size=3,expected=oci.digest(b'abc'))
        self.assertEqual(3,opener.calls);self.assertEqual(b'',output.getvalue())

    def test_actual_fully_received_wrong_size_or_digest_is_not_retried(self):
        import http.client
        for length,body in [(1,b'a'),(4,b'abcd'),(3,b'bad')]:
            class Socket:
                def makefile(self,*args):return io.BytesIO(b'HTTP/1.1 200 OK\r\nContent-Length: '+str(length).encode()+b'\r\n\r\n'+body)
            class Opener:
                calls=0
                def open(self,request,timeout):
                    self.calls+=1;response=http.client.HTTPResponse(Socket());response.begin();return response
            opener=Opener();output=io.BytesIO()
            with self.subTest(length=length,body=body),self.assertRaises(ValueError):
                oci.download_file(opener,None,output,3,size=3,expected=oci.digest(b'abc'))
            self.assertEqual(1,opener.calls);self.assertEqual(b'',output.getvalue())
