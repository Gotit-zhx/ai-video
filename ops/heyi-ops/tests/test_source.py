import hashlib
import io
import json
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from heyi_ops.host import Host
from heyi_ops.state import atomic


class SourceSafety(unittest.TestCase):
    def test_archive_cannot_replace_private_receipt_or_escape_source_directory(self):
        for name in ('../escape','./.heyi-source.json','./.heyi-owned.json','/absolute'):
            with self.subTest(name=name),tempfile.TemporaryDirectory() as temp:
                root=Path(temp);release=root/'releases/v1.0.0';release.mkdir(parents=True)
                archive=release/'source.tar.gz'
                with tarfile.open(archive,'w:gz') as tar:
                    member=tarfile.TarInfo(name);member.size=1;tar.addfile(member,io.BytesIO(b'x'))
                target={'version':'v1.0.0','source':{'tree':'a'*40},'sourceArchiveSha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'sourceUnpackedBytes':1024}
                with self.assertRaises(ValueError):Host({'root':str(root),'sourceRoot':str(root/'source')}).sync_source(target)
                self.assertFalse((root/'source/current').exists())
    def test_existing_source_directory_symlinks_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);release=root/'releases/v1.0.0';release.mkdir(parents=True)
            archive=release/'source.tar.gz';archive.write_bytes(b'fixture')
            digest=hashlib.sha256(archive.read_bytes()).hexdigest()
            checkout=root/'source/releases/v1.0.0';checkout.mkdir(parents=True)
            atomic(checkout/'.heyi-source.json',{'archiveSha256':digest,'files':{}})
            other=root/'foreign';other.mkdir();(checkout/'empty-linked-directory').symlink_to(other)
            target={'version':'v1.0.0','sourceArchiveSha256':digest}
            with self.assertRaises(RuntimeError):Host({'root':str(root),'sourceRoot':str(root/'source')}).sync_source(target)
