import copy
import io
import json
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from heyi_ops import oci,host


class OCITransport(unittest.TestCase):
    def fixture(self):
        config=json.dumps({'architecture':'amd64','os':'linux','config':{'Labels':{'org.heyi.owner':'oci-fixture'}},'rootfs':{'type':'layers','diff_ids':[]}}).encode()
        manifest=json.dumps({'schemaVersion':2,'mediaType':'application/vnd.oci.image.manifest.v1+json',
            'config':{'mediaType':'application/vnd.oci.image.config.v1+json','digest':oci.digest(config),'size':len(config)},'layers':[]}).encode()
        image={'repository':'ghcr.io/gotit-zhx/ai-video/app','manifestDigest':oci.digest(manifest),'configDigest':oci.digest(config),'compressedBytes':len(config)}
        descriptor={'mediaType':'application/vnd.oci.image.manifest.v1+json','digest':oci.digest(manifest),'size':len(manifest)}
        files={'oci-layout':b'{"imageLayoutVersion":"1.0.0"}','index.json':json.dumps(oci.index_for(image,descriptor)).encode(),
            'blobs/sha256/'+oci.digest(manifest).split(':')[1]:manifest,'blobs/sha256/'+oci.digest(config).split(':')[1]:config}
        return image,descriptor,files
    def validate(self,image,files,duplicate=False):
        with tempfile.TemporaryDirectory() as temporary:
            archive=Path(temporary)/'image.tar'
            with tarfile.open(archive,'w') as tar:
                for name,data in list(files.items())+([('index.json',files['index.json'])] if duplicate else []):
                    entry=tarfile.TarInfo(name);entry.size=len(data);tar.addfile(entry,io.BytesIO(data))
            oci.validate_archive(archive,image)
    def test_original_manifest_config_and_import_reference_are_preserved(self):
        image,_,files=self.fixture();self.validate(image,files)
    def test_blob_corruption_and_unknown_archive_members_are_rejected(self):
        image,_,files=self.fixture()
        for change in ({'blobs/sha256/'+image['configDigest'].split(':')[1]:b'corrupted'},
                       {'../../outside':b'x'},{'blobs/sha256/'+'a'*64:b'unsigned'}):
            with self.subTest(change=list(change)),self.assertRaises(ValueError):self.validate(image,{**files,**change})
        with self.assertRaises(ValueError):self.validate(image,files,duplicate=True)
    def test_archive_cannot_install_other_image_names_or_config(self):
        image,descriptor,files=self.fixture();index=oci.index_for(image,descriptor)
        index['manifests'][0]['annotations']['io.containerd.image.name']='other/image:latest'
        with self.assertRaises(ValueError):self.validate(image,{**files,'index.json':json.dumps(index).encode()})
        with self.assertRaises(ValueError):self.validate({**image,'configDigest':'sha256:'+'a'*64},files)
    def test_classic_and_containerd_image_identifiers_resolve_to_the_same_config(self):
        image,descriptor,files=self.fixture()
        self.assertEqual(image['configDigest'],host.image_config_digest({'Id':image['configDigest']}))
        info={'Id':image['manifestDigest'],'Descriptor':descriptor,'Architecture':'amd64','Os':'linux'}
        raw=files['blobs/sha256/'+image['manifestDigest'].split(':')[1]]
        with patch.object(host,'command',return_value=raw):self.assertEqual(image['configDigest'],host.image_config_digest(info))
        with patch.object(host,'command',return_value=raw+b' '):
            with self.assertRaises(ValueError):host.image_config_digest(info)
    def test_redirect_does_not_send_registry_credentials_to_blob_storage(self):
        import urllib.request
        req=urllib.request.Request('https://ghcr.io/v2/example/blobs/x',headers={'Authorization':'Bearer synthetic'})
        new=oci.RegistryRedirect().redirect_request(req,None,302,'',{},'https://blob.example/asset')
        self.assertFalse(new.has_header('Authorization'))
        with self.assertRaises(ValueError):oci.RegistryRedirect().redirect_request(req,None,302,'',{},'http://blob.example/asset')
    def test_unset_oom_disable_equals_false_but_true_remains_a_drift(self):
        info={'Config':{},'Mounts':[],'NetworkSettings':{'Networks':{}},'HostConfig':{}}
        unset=host.configuration(info)
        info['HostConfig']['OomKillDisable']=False
        self.assertEqual(unset,host.configuration(info))
        info['HostConfig']['OomKillDisable']=True
        self.assertNotEqual(unset,host.configuration(info))

class MetadataOnlyExport(unittest.TestCase):
    def test_metadata_negotiation_does_not_download_layers(self):
        from test_transfer import fixture
        image,metadata,blobs=fixture();requested=[]
        class Response(io.BytesIO):pass
        class Opener:
            def open(self,request,timeout):
                if '/token?' in request.full_url:return Response(b'{"token":"synthetic"}')
                value=request.full_url.rsplit('/',1)[1];requested.append(value)
                if value not in metadata:raise AssertionError('Layer fetched before negotiation')
                return Response(blobs[value])
        with patch.object(oci.urllib.request,'build_opener',return_value=Opener()):
            actual=oci.export_metadata(image,'synthetic')
        self.assertEqual(metadata,actual)
        self.assertEqual(set(metadata),set(requested))
    def test_archive_extra_bytes_or_truncated_end_are_rejected(self):
        image,_,files=OCITransport().fixture()
        for suffix,truncate in [(b'evil',0),(b'',9500)]:
            with tempfile.TemporaryDirectory() as temporary:
                path=Path(temporary)/'image.tar'
                with tarfile.open(path,'w') as tar:
                    for name,data in files.items():
                        entry=tarfile.TarInfo(name);entry.size=len(data);tar.addfile(entry,io.BytesIO(data))
                raw=path.read_bytes();path.write_bytes((raw[:-truncate] if truncate else raw)+suffix)
                with self.assertRaises((ValueError,tarfile.TarError)):oci.validate_archive(path,image)
