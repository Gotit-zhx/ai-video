import base64, io, unittest
from unittest.mock import patch
import numpy as np
from PIL import Image
import garment_foreground as g
class FakeSession:
    def get_inputs(self):return [type('Input',(),{'name':'input'})()]
    def run(self,*args):
        a=np.zeros((1,1,320,320),dtype=np.float32);a[:,:,30:290,60:260]=1;return [a]
class ForegroundTest(unittest.TestCase):
    def test_only_alpha_changes_original_rgb_is_preserved(self):
        im=Image.new('RGB',(160,160),(213,200,198));out=io.BytesIO();im.save(out,format='PNG');raw=out.getvalue()
        with patch.object(g,'SESSION',FakeSession()):result=g.segment({'data':base64.b64encode(raw).decode()})
        cut=Image.open(io.BytesIO(base64.b64decode(result['data'])))
        np.testing.assert_array_equal(np.asarray(cut)[:,:,:3],np.asarray(im))
        self.assertEqual(cut.getpixel((0,0))[3],0);self.assertEqual(cut.getpixel((80,80))[3],255)
        self.assertFalse(result['geometryEvidence'])
    def test_invalid_data_is_rejected(self):
        with self.assertRaises(Exception):g.segment({'data':'!!!!'})

class FabricFallbackTest(unittest.TestCase):
    def test_nested_graphic_does_not_replace_outer_fabric(self):
        from PIL import ImageDraw
        image=Image.new('RGB',(256,256),'white');draw=ImageDraw.Draw(image)
        draw.rectangle((35,20,220,240),fill=(239,179,188))
        draw.rectangle((95,105,160,180),fill=(170,170,170))
        mask=np.zeros((256,256),bool);mask[105:180,95:160]=True
        recovered=g.recover_cloth_region(image,mask)
        self.assertGreater(float(recovered.mean()),.4)
        np.testing.assert_array_equal(np.median(np.asarray(image)[recovered],axis=0),[239,179,188])
        self.assertFalse(recovered[0,0])
    def test_broad_valid_mask_is_preserved(self):
        image=Image.new('RGB',(100,100),'pink');mask=np.zeros((100,100),bool);mask[10:90,10:90]=True
        np.testing.assert_array_equal(g.recover_cloth_region(image,mask),mask)
    def test_border_background_is_not_selected_as_fabric(self):
        from PIL import ImageDraw
        image=Image.new('RGB',(256,256),'white');ImageDraw.Draw(image).rectangle((100,100,155,155),fill='gray')
        mask=np.zeros((256,256),bool);mask[100:155,100:155]=True
        np.testing.assert_array_equal(g.recover_cloth_region(image,mask),mask)
