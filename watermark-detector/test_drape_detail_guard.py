import io
import unittest
from unittest.mock import patch

import numpy as np
from PIL import Image

import drape_detail_guard as guard
import drape_reuse


def png(pixels):
    output = io.BytesIO()
    Image.fromarray(pixels).save(output, format="PNG")
    return output.getvalue()


class DrapeDetailGuardTest(unittest.TestCase):
    def setUp(self):
        self.guide = np.full((512, 512, 3), 245, np.uint8)
        self.guide[224:245, 45:66] = 30
        self.guide[398:427, 390:397] = 30
        self.master = np.full((1024, 1024, 3), 245, np.uint8)
        self.garment = np.zeros((1024, 1024), bool)
        self.garment[150:940, 80:830] = True
        self.master[420:460, 80:120] = 30
        self.master[760:820, 815:831] = 30

    def inspect(self):
        with patch.object(drape_reuse, "u2net_mask", return_value=self.garment):
            return guard.inspect(png(self.guide), png(self.master))

    def test_two_attached_details_pass(self):
        result = self.inspect()
        self.assertEqual("PASSED", result["status"])
        self.assertTrue(result["sleevePresent"])
        self.assertTrue(result["sidePresent"])

    def test_missing_side_is_rejected_even_with_dark_print_inside_shirt(self):
        self.master[760:820, 815:831] = 245
        self.master[770:835, 570:630] = 25
        result = self.inspect()
        self.assertEqual("REJECTED", result["status"])
        self.assertFalse(result["sidePresent"])

    def test_side_label_merging_with_dark_chair_keeps_visible_glyph_evidence(self):
        self.master[760:840, 815:900] = 18
        self.master[760:840, 80:815] = 245
        self.garment[760:840, 815:900] = False
        for y in range(775, 825, 8):
            self.master[y:y+3, 824:831] = 230
        result = self.inspect()
        self.assertTrue(result["sidePresent"])
        self.assertEqual("PASSED", result["status"])
        self.master[775:825, 824:831] = 18
        result = self.inspect()
        self.assertFalse(result["sidePresent"])

    def test_background_text_does_not_supply_missing_side_label(self):
        self.master[760:820, 815:831] = 245
        self.master[760:820, 890:908] = 20
        result = self.inspect()
        self.assertEqual("REJECTED", result["status"])
        self.assertFalse(result["sidePresent"])

    def test_missing_sleeve_is_rejected(self):
        self.master[420:460, 80:120] = 245
        result = self.inspect()
        self.assertEqual("REJECTED", result["status"])
        self.assertFalse(result["sleevePresent"])

    def test_template_without_these_details_does_not_retry_forever(self):
        self.guide[224:245, 45:66] = 245
        self.guide[398:427, 390:397] = 245
        result = self.inspect()
        self.assertEqual("PASSED", result["status"])
        self.assertFalse(result["templateSleeveRequired"])
        self.assertFalse(result["templateSideRequired"])
