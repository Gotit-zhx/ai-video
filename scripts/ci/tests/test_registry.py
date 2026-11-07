import sys
import unittest
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import build


class RegistryPrivacy(unittest.TestCase):
    def private(self):return {'visibility':'private','repository':{'full_name':'Gotit-zhx/ai-video'}}
    def test_public_package_blocks_application_building(self):
        with patch.object(build,'package_info',return_value={**self.private(),'visibility':'public'}),patch.object(build,'initialize_package') as initialize:
            with self.assertRaises(RuntimeError):build.ensure_private_packages()
            initialize.assert_not_called()
    def test_both_packages_must_be_linked_to_the_same_repository(self):
        with patch.object(build,'package_info',side_effect=[self.private(),{'visibility':'private','repository':{'full_name':'other/repo'}}]):
            with self.assertRaises(RuntimeError):build.ensure_private_packages()
    def test_bootstrap_cannot_include_application_files(self):
        calls=[]
        def execute(args,**_):
            folder=Path(args[-1]);self.assertEqual(['Dockerfile'],[p.name for p in folder.iterdir()])
            self.assertTrue((folder/'Dockerfile').read_text().startswith('FROM scratch\n'))
            self.assertNotIn('COPY',(folder/'Dockerfile').read_text())
            self.assertNotIn('ADD',(folder/'Dockerfile').read_text());calls.append(args)
        with patch.object(build.subprocess,'run',side_effect=execute):build.initialize_package('frontend')
        self.assertEqual(1,len(calls))
