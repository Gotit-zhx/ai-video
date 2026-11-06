import io
import unittest
from pathlib import Path
from PIL import Image, ImageEnhance
from local_overlay import detect_overlay

FIXTURES=Path('/workspace/output/batch-runs/20260908-pink-cat')

class DetectorRegressionTest(unittest.TestCase):
    """Calibration regressions, not an independent accuracy benchmark."""
    def setUp(self):
        self.source=Image.open(FIXTURES/'source-original.jpg').convert('RGB')

    def test_source_localizes_known_band_without_mutating_image(self):
        before=self.source.tobytes()
        boxes=detect_overlay(self.source)
        self.assertEqual(len(boxes),1)
        self.assertLess(abs(boxes[0]['y']-.4834),.02)
        self.assertGreater(boxes[0]['width'],.9)
        self.assertEqual(before,self.source.tobytes())

    def test_physical_embroidery_and_clean_garment_do_not_match(self):
        for name in ['extract-0.png','mockup_watermark_removed-2.png']:
            with self.subTest(name=name):
                self.assertEqual(detect_overlay(Image.open(FIXTURES/name)),[])

    def test_resize_jpeg_and_brightness(self):
        for image in [self.source.resize((640,640)),ImageEnhance.Brightness(self.source).enhance(.8)]:
            buf=io.BytesIO();image.save(buf,format='JPEG',quality=75)
            self.assertTrue(detect_overlay(Image.open(io.BytesIO(buf.getvalue()))))

    def test_translation_and_scale(self):
        canvas=Image.new('RGB',(1024,1024),'white')
        canvas.paste(self.source.resize((819,819)),(90,140))
        boxes=detect_overlay(canvas)
        self.assertTrue(boxes)
        self.assertLess(abs(boxes[0]['y']-(140+495*.8)/1024),.02)

    def test_one_fragment_is_not_enough(self):
        from PIL import ImageDraw
        copy=self.source.copy()
        ImageDraw.Draw(copy).rectangle((860,485,985,590),fill='white')
        self.assertEqual(detect_overlay(copy),[])

    def test_blank_and_tiny_are_not_matches(self):
        for size in [(1024,1024),(1,1)]:
            self.assertEqual(detect_overlay(Image.new('RGB',size,'white')),[])

if __name__=='__main__':unittest.main()
