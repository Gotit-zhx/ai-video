import hashlib
import json
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from heyi_ops import monitor
from heyi_ops.state import Store, atomic


class OwnedCleanup(unittest.TestCase):
    def test_live_references_two_distinct_rollbacks_and_user_source_are_preserved(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);store=Store(root,root/'lock');source=root/'source';manifests={}
            for n in range(8):
                image=min(n,3);version='v1.0.'+str(n)
                manifest={'version':version,'images':{k:{'configDigest':k+str(image),'repository':'ghcr.io/gotit-zhx/ai-video/'+k,'manifestDigest':'sha256:'+str(image)*64} for k in ('app','frontend')}}
                manifests[version]=manifest;directory=root/'releases'/version;directory.mkdir(parents=True)
                atomic(directory/'release.json',manifest);atomic(directory/'verified.json',{'verifiedAt':int(time.time())})
                atomic(directory/'images.json',{});(directory/'source.tar.gz').write_bytes(b'owned archive')
                store.save({'id':format(n,'032x'),'target':manifest,'finishedAt':n},'succeeded')
                checkout=source/'releases'/version;checkout.mkdir(parents=True);(checkout/'file').write_bytes(b'owned source')
                atomic(checkout/'.heyi-source.json',{'files':{'file':hashlib.sha256(b'owned source').hexdigest()}})
            store.set_active(manifests['v1.0.7']);(source/'current').symlink_to(source/'releases/v1.0.7')
            (source/'releases/v1.0.3/user-notes').write_text('preserve manual change')
            (root/'releases/v1.0.4/foreign-file').write_text('not owned')
            calls=[]
            containers=[{'Image':'app0','Config':{'Labels':{'com.docker.compose.project.config_files':str(root/'releases/v1.0.3/images.json')}},'Mounts':[]}]
            def command(*args):
                calls.append(args)
                if args[:3]==('docker','ps','-aq'):return b'container\n'
                if args[:2]==('docker','inspect'):return json.dumps(containers).encode()
                if args[:3]==('docker','image','ls'):return b'\n'.join((k+str(i)).encode() for i in range(4) for k in ('app','frontend'))
                if args[:3]==('docker','image','inspect'):
                    name=args[-1];component=name[:-1];index=name[-1]
                    return json.dumps([{'Id':name,'RepoDigests':['ghcr.io/gotit-zhx/ai-video/'+component+'@sha256:'+index*64]}]).encode()
                if args[:3]==('docker','image','rm'):return b''
                raise AssertionError(args)
            with patch.object(monitor,'command',side_effect=command),patch('heyi_ops.artifacts.Artifacts.load',side_effect=lambda version:manifests[version]):
                result=monitor.cleanup({'sourceRoot':str(source)},store)
            self.assertEqual(['v1.0.1','v1.0.2','v1.0.5','v1.0.6','v1.0.7'],result['retainedVersions'])
            removed=[args[-1] for args in calls if args[:3]==('docker','image','rm')]
            self.assertEqual(['ghcr.io/gotit-zhx/ai-video/frontend@sha256:'+'0'*64],removed)
            self.assertTrue((root/'releases/v1.0.3/images.json').is_file())
            self.assertFalse((root/'releases/v1.0.3/source.tar.gz').exists())
            self.assertTrue((source/'releases/v1.0.3/user-notes').is_file())
            self.assertTrue((root/'releases/v1.0.4/foreign-file').is_file())
            self.assertTrue((root/'cleanup-plan.json').is_file())
