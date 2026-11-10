import hashlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from heyi_ops.artifacts import Artifacts
from heyi_ops.host import Host
from heyi_ops.state import Busy
from heyi_ops.storage import GIB, require_space


class ReadyToImport(Exception):
    pass


class StorageBudgetTest(unittest.TestCase):
    def test_reserve_is_additional_to_temporary_bytes(self):
        for extra in (0, 2 * GIB):
            with patch('heyi_ops.storage.shutil.disk_usage', return_value=SimpleNamespace(free=10 * GIB + extra)):
                require_space('/synthetic', extra)
            with patch('heyi_ops.storage.shutil.disk_usage', return_value=SimpleNamespace(free=10 * GIB + extra - 1)):
                with self.assertRaisesRegex(RuntimeError, '10 GiB reserve'):
                    require_space('/synthetic', extra)

    def test_invalid_budget_cannot_reduce_the_reserve(self):
        for value in (-1, True, 1.5, '0'):
            with self.assertRaises(ValueError):
                require_space('/synthetic', value)

    def test_staging_keeps_transfer_unpack_and_source_budgets(self):
        mib = 1024 ** 2
        source = b'synthetic source'
        target = {'version': 'v1.0.0', 'source': {'commit': 'candidate', 'tree': 'tree', 'ref': 'refs/heads/release/1.0.0'},
                  'sourceArchiveBytes': len(source), 'sourceArchiveSha256': hashlib.sha256(source).hexdigest(),
                  'sourceUnpackedBytes': mib,
                  'images': {'app': {'compressedBytes': 2*mib, 'unpackedBytes': 4*mib},
                             'frontend': {'compressedBytes': mib, 'unpackedBytes': 2*mib}}}
        for name,image in target['images'].items():
            image.update(repository='ghcr.io/gotit-zhx/ai-video/'+name,manifestDigest='sha256:'+'a'*64,sourceCommit='a'*40,sourceRef='refs/heads/release/1.0.0')
        manifest = json.dumps(target).encode()
        sizes = {'release.json': len(manifest), 'source.tar.gz': len(source)}
        api = {'git/ref/heads/main-tree': {'object': {'sha': 'production'}},
               'git/ref/tags/v1.0.0': {'object': {'type': 'commit', 'sha': 'production'}},
               'git/commits/production': {'parents': [{'sha': 'old'}, {'sha': 'candidate'}], 'tree': {'sha': 'tree'}},
               'releases/tags/candidate-candidate': {'assets': [{'name': k, 'size': v} for k, v in sizes.items()]}}

        def command(*args, **kwargs):
            if args[:2] == ('gh', 'api'):
                return json.dumps(api[args[2].split('Gotit-zhx/ai-video/', 1)[1]]).encode()
            return b''

        for cached, archives in [(set(), {'app': 2*mib, 'frontend': mib}), ({'app'}, {}), ({'app', 'frontend'}, {})]:
            budget = sum(image['compressedBytes'] + image['unpackedBytes'] for name, image in target['images'].items() if name not in cached)
            budget += sum(archives.values()) + 2*len(source) + mib
            for missing_byte in (0, 1):
                with self.subTest(cached=cached, missing_byte=missing_byte), tempfile.TemporaryDirectory() as directory:
                    upload = io.BytesIO(manifest + source + bytes(sum(archives.values())))
                    with patch('heyi_ops.artifacts.command', side_effect=command), \
                         patch('heyi_ops.artifacts.validate_manifest', side_effect=lambda value: value), \
                         patch.object(Artifacts, 'cached', side_effect=lambda image: any(image == target['images'][name] for name in cached)), \
                         patch('heyi_ops.oci.validate_archive'), \
                         patch.object(Artifacts, 'verify_import', side_effect=ReadyToImport), \
                         patch('heyi_ops.storage.shutil.disk_usage', return_value=SimpleNamespace(free=10*GIB + budget - missing_byte)):
                        error = RuntimeError if missing_byte else ReadyToImport
                        with self.assertRaises(error):
                            Artifacts(directory).prepare('v1.0.0', 'synthetic', (upload, sizes, archives))

    def test_activation_still_reserves_source_extraction_space(self):
        with tempfile.TemporaryDirectory() as directory:
            host = Host({'root': directory})
            host.marker.parent.mkdir(); host.marker.write_text('{}')
            target = {'source': {'inputs': {'unsupported': 'same'}}, 'sourceUnpackedBytes': 1024**2}
            for missing_byte in (0, 1):
                with patch.object(host, 'compatible'), patch('heyi_ops.storage.shutil.disk_usage', return_value=SimpleNamespace(free=10*GIB + 1024**2 - missing_byte)):
                    if missing_byte:
                        with self.assertRaisesRegex(RuntimeError, '10 GiB reserve'):
                            host.preflight(target, target, restart=True)
                    else:
                        with self.assertRaises(Busy):
                            host.preflight(target, target, restart=True)


if __name__ == '__main__':
    unittest.main()
