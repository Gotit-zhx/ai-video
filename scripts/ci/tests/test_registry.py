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


class ReusedSmokeImages(unittest.TestCase):
    def images(self):
        return {name:{'repository':'ghcr.io/gotit-zhx/ai-video/'+name,
            'manifestDigest':'sha256:'+digit*64,'configDigest':'sha256:'+digit*64,
            'platform':'linux/amd64','sourceCommit':'a'*40} for name,digit in [('app','1'),('frontend','2')]}

    def test_mixed_release_makes_reused_private_image_available_to_smoke(self):
        images=self.images();available={images['app']['manifestDigest']}
        def pull(repository,digest):
            available.add(digest)
            return next(image for image in images.values() if image['repository']==repository)
        with patch.object(build,'image_metadata',side_effect=pull) as metadata:
            build.prepare_reused_smoke_images(images,['app'])
        self.assertEqual({image['manifestDigest'] for image in images.values()},available)
        metadata.assert_called_once_with(images['frontend']['repository'],images['frontend']['manifestDigest'])

    def test_documentation_only_and_fully_built_images_need_no_extra_pull(self):
        for changed in ([],['app','frontend']):
            with self.subTest(changed=changed),patch.object(build,'image_metadata') as metadata:
                build.prepare_reused_smoke_images(self.images(),changed)
                metadata.assert_not_called()

    def test_reused_identity_mismatch_stops_publication(self):
        images=self.images()
        with patch.object(build,'image_metadata',return_value={**images['frontend'],'configDigest':'sha256:'+'f'*64}):
            with self.assertRaisesRegex(ValueError,'differs from the verified baseline'):
                build.prepare_reused_smoke_images(images,['app'])
