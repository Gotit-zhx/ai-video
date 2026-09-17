import base64,io,unittest
import numpy as np
from PIL import Image
from local_composite import composite

def encoded(image):
    out=io.BytesIO();image.save(out,format='PNG');return base64.b64encode(out.getvalue()).decode()

class CompositeTest(unittest.TestCase):
    def test_native_dimensions_and_exact_outside(self):
        rng=np.random.default_rng(9)
        a=Image.fromarray(rng.integers(0,256,(120,80,3),dtype=np.uint8))
        b=Image.new('RGB',(128,128),(170,130,90))
        out=composite({'source':encoded(a),'candidate':encoded(b),'regions':[dict(x=.1,y=.4,width=.8,height=.2)]})
        pixels=np.asarray(Image.open(io.BytesIO(base64.b64decode(out['data']))))
        self.assertEqual(pixels.shape,(120,80,3))
        mask=np.ones((120,80),bool);mask[48:73,8:72]=False
        np.testing.assert_array_equal(pixels[mask],np.asarray(a)[mask])
    def test_rejects_large_and_nonfinite_mask(self):
        a=encoded(Image.new('RGB',(100,100),'white'))
        for r in [dict(x=0,y=0,width=1,height=1),dict(x=float('nan'),y=0,width=.2,height=.2)]:
            with self.assertRaises(ValueError):composite({'source':a,'candidate':a,'regions':[r]})
