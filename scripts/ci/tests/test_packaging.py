import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
spec=importlib.util.spec_from_file_location('frontend_package',ROOT/'frontend/package.py')
package=importlib.util.module_from_spec(spec);spec.loader.exec_module(package)

class LayeredPackagingTests(unittest.TestCase):
    def test_layered_public_files_and_gzip_are_byte_identical(self):
        with tempfile.TemporaryDirectory() as d:
            plain=Path(d)/'plain';layered=Path(d)/'layered'
            manifest=package.build(plain);self.assertEqual(manifest,package.build(layered,True))
            merged={}
            for layer in ['stable','frequent']:
                for p in (layered/layer).rglob('*'):
                    if p.is_file():
                        name=str(p.relative_to(layered/layer));self.assertNotIn(name,merged);merged[name]=p.read_bytes()
            self.assertEqual({str(p.relative_to(plain)):p.read_bytes() for p in plain.rglob('*') if p.is_file()},merged)
