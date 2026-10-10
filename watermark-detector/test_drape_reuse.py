import base64
import copy
import io
import json
import math
import os
import unittest
from unittest.mock import patch
import cv2
import numpy as np
from PIL import Image, ImageDraw
import drape_reuse as d

def enc(pixels):
    return base64.b64encode(d.png(pixels)).decode()

def pixels(value, mode='RGB'):
    return np.asarray(Image.open(io.BytesIO(base64.b64decode(value))).convert(mode))

def fixture(large=False, source_size=None, target_corners=None):
    garment = np.zeros((512, 512), np.uint8)
    cv2.fillPoly(garment, [np.array([[195, 35], [317, 35], [393, 83], [462, 248], [411, 271],
                                  [411, 466], [102, 466], [102, 271], [49, 248], [119, 83]])], 1)
    image = np.full((512, 512, 3), [115, 101, 84], np.uint8)
    yy, xx = np.indices(garment.shape)
    light = np.clip(236+5*np.sin(xx/80)+4*np.cos(yy/75), 0, 255).astype(np.uint8)
    image[garment > 0] = np.repeat(light[:, :, None], 3, axis=2)[garment > 0]
    art = Image.new('RGBA', (256, 256), (0, 0, 0, 0)); draw = ImageDraw.Draw(art)
    draw.ellipse((17, 17, 238, 238), fill=(245, 192, 207, 255))
    rng = np.random.default_rng(918)
    for _ in range(75):
        x, y = rng.integers(35, 218, 2); r = int(rng.integers(3, 11))
        draw.rectangle((int(x-r), int(y-r), int(x+r), int(y+r)), fill=tuple(map(int, rng.integers(20, 240, 3)))+(255,))
    art = np.asarray(art)
    guide = image.copy(); x, y, size = (174, 150, 164) if large else (260, 142, 76)
    if source_size is not None:
        size = source_size
    small = cv2.resize(art, (size, size), interpolation=cv2.INTER_AREA); alpha = small[:, :, 3:4]/255
    guide[y:y+size, x:x+size] = np.rint(guide[y:y+size, x:x+size]*(1-alpha)+small[:, :, :3]*alpha).astype(np.uint8)
    uv = np.zeros((512, 512, 4), np.uint8)
    for j in range(size):
        for i in range(size):
            uv[y+j, x+i] = (round(i/(size-1)*255), round((1-j/(size-1))*255), 1, 255)
    corners = target_corners if target_corners is not None else ([[168, 154], [337, 149], [341, 321], [166, 326]] if large else [[268, 148], [351, 143], [356, 228], [265, 232]])
    h = cv2.getPerspectiveTransform(np.float32(d.UNIT), np.float32(corners))
    warped = cv2.warpPerspective(art, h@np.diag([1/255, 1/255, 1]), (512, 512))
    a = warped[:, :, 3:4]/255
    master = np.rint(image*(1-a)+warped[:, :, :3]*a).astype(np.uint8)
    body = {'master': enc(master), 'guide': enc(guide), 'uv': enc(uv), 'artwork': enc(art), 'printIds': ['chest']}
    return body, garment > 0, master

class DrapeReuseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.request, cls.mask, cls.master = fixture()
        with patch.object(d, 'u2net_mask', return_value=cls.mask):
            cls.prepared = d.prepare(cls.request)

    def render_request(self):
        return {'master': self.request['master'], 'blank': self.prepared['blank'], 'alpha': self.prepared['alpha'],
                'mapping': copy.deepcopy(self.prepared['mapping']), 'artwork': self.request['artwork'],
                'targetRgb': '#262626', 'originalColor': False}

    def assert_error(self, code, function, body):
        with self.assertRaises(d.DrapeError) as caught:
            function(body)
        self.assertEqual(code, caught.exception.code)

    def test_real_tracking_cleanup_and_recolor_keep_background_exact(self):
        self.assertTrue(self.prepared['report']['passed'])
        self.assertEqual('U2NET_THREE_VIEW_SLEEVE_HEM_V2', self.prepared['report']['maskRevision'])
        self.assertEqual(self.prepared['report']['maskRevision'], self.prepared['mapping']['maskRevision'])
        self.assertGreaterEqual(self.prepared['report']['tracking']['agreeingScales'], 3)
        self.assertLess(self.prepared['report']['tracking']['medianErrorPx'], 2)
        blank = pixels(self.prepared['blank']); removal = pixels(self.prepared['mapping']['cleanupMask'], 'L')
        np.testing.assert_array_equal(blank[removal == 0], self.master[removal == 0])
        result = d.render(self.render_request()); alpha = pixels(self.prepared['alpha'], 'L')
        np.testing.assert_array_equal(pixels(result['data'])[alpha == 0], self.master[alpha == 0])
        self.assertTrue(result['report']['passed']); self.assertEqual(0, result['report']['modelCalls'])

    def test_sleeve_badge_fold_recovers_cloth_without_dyeing_logo_or_background(self):
        rgb = np.full((1024, 1024, 3), (70, 75, 81), np.uint8)
        mask = np.zeros((1024, 1024), np.uint8)
        points = np.array([[390, 40], [610, 40], [820, 220], [985, 480],
                           [820, 610], [800, 950], [220, 950], [210, 615],
                           [45, 498], [200, 220]], np.int32)
        cv2.fillPoly(mask, [points], 1)
        rgb[mask > 0] = (239, 239, 237)
        for x in range(62, 91):
            for y in range(round(.71*x+461)-15, round(.71*x+461)+1):
                rgb[y, x] = (188, 187, 184)
                mask[y, x] = 0
        rgb[472:516, 70:111] = (28, 28, 28)
        mask[472:516, 70:111] = 0
        rgb[485:497, 84:96] = (230, 230, 230)  # White logo on black badge.
        fixed = d.recover_sleeve_fold(rgb, mask > 0)
        self.assertTrue(fixed[519, 80])
        self.assertFalse(fixed[490, 90])
        self.assertFalse(fixed[525, 53])
        self.assertLess(int((fixed & ~(mask > 0)).sum()), 400)

    def test_large_front_artwork_tracks_cleans_and_renders_without_chest_limit(self):
        body, mask, master = fixture(large=True)
        with patch.object(d, 'u2net_mask', return_value=mask):
            prepared = d.prepare(body)
        result = d.render({'master': body['master'], 'blank': prepared['blank'], 'alpha': prepared['alpha'],
                           'mapping': prepared['mapping'], 'artwork': body['artwork'], 'targetRgb': '#262626', 'originalColor': False})
        self.assertTrue(result['report']['passed'])
        alpha = pixels(prepared['alpha'], 'L')
        np.testing.assert_array_equal(pixels(result['data'])[alpha == 0], master[alpha == 0])

    def test_source_uv_over_twelve_percent_runs_real_tracking_cleanup_and_render(self):
        corners = [[163, 144], [361, 142], [365, 342], [161, 346]]
        body, mask, master = fixture(large=True, source_size=200, target_corners=corners)
        uv = pixels(body['uv'], 'RGBA')
        self.assertGreater(float((uv[:, :, 3] > 0).mean()), .12)
        with patch.object(d, 'u2net_mask', return_value=mask):
            prepared = d.prepare(body)
        tracking = prepared['report']['tracking']
        self.assertGreaterEqual(tracking['agreeingScales'], 3)
        self.assertGreaterEqual(tracking['inliers'], 12)
        actual = np.asarray(prepared['mapping']['targetCorners'], np.float64)
        self.assertLess(float(np.linalg.norm(actual-np.asarray(corners), axis=1).max()), 4)
        result = d.render({'master': body['master'], 'blank': prepared['blank'], 'alpha': prepared['alpha'],
                           'mapping': prepared['mapping'], 'artwork': body['artwork'], 'targetRgb': '#262626', 'originalColor': False})
        self.assertTrue(result['report']['passed']); self.assertEqual(0, result['report']['modelCalls'])
        alpha = pixels(prepared['alpha'], 'L'); removal = pixels(prepared['mapping']['cleanupMask'], 'L')
        np.testing.assert_array_equal(pixels(prepared['blank'])[removal == 0], master[removal == 0])
        np.testing.assert_array_equal(pixels(result['data'])[alpha == 0], master[alpha == 0])

    def test_larger_source_uv_still_rejects_real_matching_print_outside_garment(self):
        body, mask, _ = fixture(large=True, source_size=200,
                                target_corners=[[308, 24], [505, 24], [505, 223], [308, 223]])
        with patch.object(d, 'u2net_mask', return_value=mask):
            self.assert_error('PRINT_OUTSIDE_GARMENT', d.prepare, body)

    def test_jpeg_master_keeps_provider_bytes_and_renders_from_its_own_mapping(self):
        out = io.BytesIO(); Image.fromarray(self.master).save(out, format='JPEG', quality=95, subsampling=0)
        raw = out.getvalue(); body = dict(self.request); body['master'] = d.encoded(raw)
        with patch.object(d, 'u2net_mask', return_value=self.mask):
            prepared = d.prepare(body)
        self.assertEqual(d.sha(raw), prepared['sourceSha256'])
        self.assertEqual(d.sha(raw), prepared['mapping']['sourceSha256'])
        request = {'master': body['master'], 'blank': prepared['blank'], 'alpha': prepared['alpha'],
                   'mapping': prepared['mapping'], 'artwork': body['artwork'], 'originalColor': True}
        result = d.render(request)
        self.assertEqual(d.sha(raw), result['report']['sourceSha256'])
        master = pixels(body['master']); alpha = pixels(prepared['alpha'], 'L')
        np.testing.assert_array_equal(pixels(result['data'])[alpha == 0], master[alpha == 0])

    def test_webp_allowed_only_for_master_without_reencoding_raw_hash(self):
        out = io.BytesIO(); Image.fromarray(self.master).save(out, format='WEBP', lossless=True)
        raw = out.getvalue(); value = d.encoded(raw)
        decoded, rgb = d.decode(value, '母图', formats=('PNG', 'JPEG', 'WEBP'))
        self.assertEqual(raw, decoded); np.testing.assert_array_equal(rgb, self.master)
        with self.assertRaises(d.DrapeError) as caught:
            d.decode(value, '定位图')
        self.assertEqual('PNG_REQUIRED', caught.exception.code)

    def test_only_visible_slot_is_selected_from_full_print_id_list(self):
        body = dict(self.request); body['printIds'] = ['hidden-back', 'chest', 'hidden-side']
        uv = pixels(body['uv'], 'RGBA').copy(); uv[:, :, 2][uv[:, :, 3] > 0] = 2; body['uv'] = enc(uv)
        with patch.object(d, 'u2net_mask', return_value=self.mask):
            result = d.prepare(body)
        self.assertEqual('chest', result['mapping']['printId'])
        self.assertEqual(2, result['mapping']['printSlot'])

    def test_multiple_visible_slots_rejected(self):
        body = dict(self.request); body['printIds'] = ['one', 'two']
        uv = pixels(body['uv'], 'RGBA').copy(); uv[150:160, 270:280, 2] = 2; body['uv'] = enc(uv)
        self.assert_error('MULTIPLE_PRINTS_UNSUPPORTED', d.prepare, body)

    def test_visible_slot_cannot_reference_missing_id(self):
        body = dict(self.request)
        uv = pixels(body['uv'], 'RGBA').copy(); uv[:, :, 2][uv[:, :, 3] > 0] = 2; body['uv'] = enc(uv)
        self.assert_error('UV_SLOT_INVALID', d.prepare, body)

    def test_blank_print_cannot_be_guessed_from_garment_pose(self):
        body = dict(self.request); body['guide'] = enc(np.full((512, 512, 3), 240, np.uint8))
        self.assert_error('GUIDE_ARTWORK_MISMATCH', d.prepare, body)

    def test_native_artwork_fallback_remains_bound_to_guide_and_uv(self):
        body = dict(self.request); art = pixels(body['artwork'], 'RGBA'); uv = pixels(body['uv'], 'RGBA')
        # The real photo composer point-samples the native artwork through its
        # quantized UV. A small guide can lose useful features at those samples.
        sampled = cv2.remap(art, uv[:, :, 0].astype(np.float32)/255*(art.shape[1]-1),
                            (1-uv[:, :, 1].astype(np.float32)/255)*(art.shape[0]-1), cv2.INTER_LINEAR)
        alpha = sampled[:, :, 3:4]/255*(uv[:, :, 3:4]/255)
        guide = np.rint(235*(1-alpha)+sampled[:, :, :3]*.96*alpha).astype(np.uint8)
        body['guide'] = enc(guide)
        candidates = d.track_candidates
        # Simulate the low-resolution guide having insufficient matches; the
        # fallback must still find real SIFT evidence in the native artwork.
        def missing_guide(master, source, *args):
            return [] if source.shape[:2] == (76, 76) else candidates(master, source, *args)
        with patch.object(d, 'u2net_mask', return_value=self.mask), patch.object(d, 'track_candidates', side_effect=missing_guide):
            prepared = d.prepare(body)
        tracking = prepared['report']['tracking']
        self.assertEqual('UV_BOUND_NATIVE_ARTWORK', tracking['reference'])
        self.assertGreaterEqual(tracking['inliers'], 12); self.assertGreaterEqual(tracking['agreeingScales'], 3)
        self.assertGreater(tracking['guideArtworkBinding']['correlation'], .97)
        expected = np.float32([[268, 148], [351, 143], [356, 228], [265, 232]])
        actual = np.asarray(prepared['mapping']['targetCorners'], np.float64)
        self.assertLess(float(np.linalg.norm(actual-expected, axis=1).max()), 4)
        wrong = np.flip(art, axis=1).copy()
        with self.assertRaises(d.DrapeError) as caught:
            d.guide_artwork_binding(guide, uv, wrong)
        self.assertEqual('GUIDE_ARTWORK_MISMATCH', caught.exception.code)

    def test_white_background_connected_to_collar_is_not_clothing(self):
        blank = pixels(self.prepared['blank']).copy()
        blank[:42, 236:279] = 238  # White background stripe touches the collar.
        mask, agreement = d.garment_mask(blank, self.mask)
        self.assertGreater(agreement, .92)
        self.assertFalse(mask[0].any())
        alpha = d.edge_alpha(blank, mask).astype(np.float32)/255
        recolored = d.recolor(blank, alpha, [38, 38, 38])
        np.testing.assert_array_equal(blank[:20], recolored[:20])
        # If the independent garment mask also says the clothing reaches the
        # frame, the original crop guard must still reject it.
        cropped = self.mask.copy(); cropped[:42, 236:279] = True
        with self.assertRaises(d.DrapeError) as caught:
            d.garment_mask(blank, cropped)
        self.assertEqual('GARMENT_CROPPED', caught.exception.code)

    def test_two_aligned_views_recover_missing_sleeve_without_growing_into_white_wall(self):
        blank = pixels(self.prepared['blank']).copy()
        blank[:42, 236:279] = 238
        incomplete = self.mask.copy(); incomplete[150:250, 70:145] = False
        missing = self.mask & ~incomplete
        self.assertGreater(int(missing.sum()), 2000)
        rotated = self.mask.copy(); rotated[:42, 236:279] = True  # One model view leaks into a white wall.
        candidate = d.garment_mask_consensus(incomplete, rotated, self.mask)
        self.assertTrue(candidate[missing].all())
        self.assertFalse(candidate[:20].any())
        mask, _ = d.garment_mask(blank, candidate)
        self.assertTrue(mask[missing].all())
        alpha = d.edge_alpha(blank, mask).astype(np.float32)/255
        black = d.recolor(blank, alpha, [38, 38, 38])
        sleeve_core = (cv2.erode(missing.astype(np.uint8), np.ones((7, 7), np.uint8)) > 0)
        self.assertLess(float(black[sleeve_core].mean()), 100)
        np.testing.assert_array_equal(blank[alpha == 0], black[alpha == 0])
        np.testing.assert_array_equal(blank[:20], black[:20])

    def test_large_consensus_expansion_is_rejected_instead_of_dilating_the_background(self):
        much_larger = np.ones_like(self.mask)
        with self.assertRaises(d.DrapeError) as caught:
            d.garment_mask_consensus(self.mask, much_larger, much_larger)
        self.assertEqual('GARMENT_MASK_UNCERTAIN', caught.exception.code)
        # A single overconfident view has no authority to expand either.
        result = d.garment_mask_consensus(self.mask, much_larger, self.mask)
        np.testing.assert_array_equal(self.mask, result)

    def test_u2net_views_are_unrotated_and_unpadded_before_combining(self):
        incomplete = self.mask.copy(); incomplete[150:250, 70:145] = False
        pad = round(min(self.master.shape[:2])*.10)
        contextual = np.pad(self.mask, pad, mode='reflect')
        with patch.object(d, 'u2net_view_mask', side_effect=[incomplete, self.mask[::-1, ::-1].copy(), contextual]) as model:
            result = d.u2net_mask(self.master)
        np.testing.assert_array_equal(self.mask, result)
        self.assertEqual(3, model.call_count)
        np.testing.assert_array_equal(self.master[::-1, ::-1], model.call_args_list[1].args[0])
        self.assertEqual((512+2*pad, 512+2*pad, 3), model.call_args_list[2].args[0].shape)

    def test_context_padding_remains_bounded_for_maximum_input_size(self):
        rgb = np.zeros((d.MAX_SIDE, 1400, 3), np.uint8)
        rgb[180:1300, 180:1200] = 235
        shapes = []
        def model(image):
            shapes.append(image.shape)
            return image[:, :, 0] > 160
        with patch.object(d, 'u2net_view_mask', side_effect=model):
            candidate = d.u2net_mask(rgb)
        self.assertEqual(rgb.shape[:2], candidate.shape)
        self.assertEqual(3, len(shapes))
        self.assertTrue(all(max(shape[:2]) <= d.MAX_SIDE for shape in shapes))
        self.assertGreater(float((candidate == (rgb[:, :, 0] > 160)).mean()), .999)

    def test_altered_source_bytes_cannot_reuse_mapping(self):
        request = self.render_request(); changed = self.master.copy(); changed[0, 0] = [1, 2, 3]; request['master'] = enc(changed)
        self.assert_error('ASSET_BINDING_CHANGED', d.render, request)

    def test_altered_alpha_cannot_reuse_mapping(self):
        request = self.render_request(); a = pixels(request['alpha'], 'L').copy(); a[0, 0] = 255; request['alpha'] = enc(a)
        self.assert_error('ASSET_BINDING_CHANGED', d.render, request)

    def test_accidental_homography_mutation_is_rejected(self):
        request = self.render_request()
        request['mapping']['textureToMasterH'][0][2] = format(float(request['mapping']['textureToMasterH'][0][2])+12, '.17g')
        self.assert_error('MAPPING_CHECKSUM_INVALID', d.render, request)

    def test_even_rechecksummed_wrong_uv_binding_is_rejected(self):
        request = self.render_request()
        request['mapping']['textureToMasterH'][0][2] = format(float(request['mapping']['textureToMasterH'][0][2])+12, '.17g')
        request['mapping']['mappingSha256'] = d.mapping_checksum(request['mapping'])
        self.assert_error('MAPPING_INVALID', d.render, request)

    def legacy_request(self):
        request = self.render_request(); mapping = request['mapping']; mapping.pop('numberEncoding')
        for field in d.COORDINATE_FIELDS:
            mapping[field] = [[float(value) for value in row] for row in mapping[field]]
        mapping['mappingSha256'] = d.mapping_checksum(mapping)
        return request

    def test_valid_legacy_mapping_still_renders_identically(self):
        legacy = self.legacy_request()
        self.assertEqual(d.render(self.render_request())['data'], d.render(legacy)['data'])

    def test_decimal_strings_survive_numeric_json_codec_without_rounding(self):
        request = self.render_request(); mapping = request['mapping']
        self.assertEqual(d.NUMBER_ENCODING, mapping['numberEncoding'])
        # Model the observed DB defect: number-valued doubles drift by 1 ULP,
        # while JSON strings and integers retain their original values.
        def rewrite(value):
            if type(value) is float:
                return math.nextafter(value, math.inf)
            if isinstance(value, list):
                return [rewrite(item) for item in value]
            if isinstance(value, dict):
                return {key: rewrite(item) for key, item in value.items()}
            return value
        restored = rewrite(json.loads(json.dumps(mapping)))
        self.assertEqual(mapping, restored)
        self.assertEqual(mapping['mappingSha256'], d.mapping_checksum(restored))
        for field in d.COORDINATE_FIELDS:
            for before, after in zip(np.asarray(mapping[field]).flat, np.asarray(restored[field]).flat):
                self.assertEqual(float(before).hex(), float(after).hex())
        request['mapping'] = restored
        self.assertTrue(d.render(request)['report']['passed'])
        legacy = self.legacy_request(); legacy['mapping'] = rewrite(legacy['mapping'])
        self.assert_error('MAPPING_CHECKSUM_INVALID', d.render, legacy)

    def test_unknown_encodings_booleans_and_nonfinite_coordinates_are_rejected(self):
        request = self.render_request(); request['mapping']['numberEncoding'] = 'UNKNOWN'
        self.assert_error('MAPPING_INVALID', d.render, request)
        for value in (True, False, 'nan', 'inf', '-inf', '1e999', ' 1', '1.0'):
            with self.subTest(value=value):
                request = self.render_request(); request['mapping']['textureToMasterH'][0][0] = value
                self.assert_error('MAPPING_INVALID', d.render, request)
        legacy = self.legacy_request(); legacy['mapping']['textureToMasterH'][0][0] = True
        self.assert_error('MAPPING_INVALID', d.render, legacy)
        legacy = self.legacy_request(); legacy['mapping']['textureToMasterH'][0][0] = 10**500
        self.assert_error('MAPPING_INVALID', d.render, legacy)
        with self.assertRaises(d.DrapeError):
            d.homography([[True, 0, 0], [0, 1, 0], [0, 0, 1]])

    def test_opaque_artwork_and_missing_sku_color_fail(self):
        request = self.render_request(); request['artwork'] = enc(np.full((64, 64, 4), 255, np.uint8))
        self.assert_error('TRANSPARENT_ARTWORK_REQUIRED', d.render, request)
        request = self.render_request(); request['targetRgb'] = 'black'
        self.assert_error('SKU_COLOR_REQUIRED', d.render, request)

    def test_faint_artwork_does_not_create_nan_shading_or_false_success(self):
        request = self.render_request(); art = pixels(request['artwork'], 'RGBA').copy()
        art[:, :, 3][art[:, :, 3] > 0] = 24; request['artwork'] = enc(art)
        self.assert_error('PRINT_NOT_VISIBLE', d.render, request)

    def test_even_rechecksummed_cleanup_cannot_extend_outside_garment(self):
        request = self.render_request(); removal = pixels(request['mapping']['cleanupMask'], 'L').copy()
        removal[0, 0] = 255; raw = d.png(removal)
        request['mapping']['cleanupMask'] = d.encoded(raw); request['mapping']['cleanupMaskSha256'] = d.sha(raw)
        request['mapping']['mappingSha256'] = d.mapping_checksum(request['mapping'])
        self.assert_error('CLEANUP_MASK_INVALID', d.render, request)

    def test_archived_mapping_survives_token_rotation(self):
        request = self.render_request()
        with patch.dict(os.environ, {'WATERMARK_DETECTOR_TOKEN': 'a'*32}):
            first = d.render(request)['data']
        with patch.dict(os.environ, {'WATERMARK_DETECTOR_TOKEN': 'b'*32}):
            self.assertEqual(first, d.render(request)['data'])

    def test_large_or_mirrored_regions_are_not_accepted(self):
        with self.assertRaises(d.DrapeError):
            d.validate_quad(np.float32([[10, 10], [480, 10], [480, 480], [10, 480]]), (512, 512))
        with self.assertRaises(d.DrapeError):
            d.validate_quad(np.float32([[150, 150], [150, 220], [220, 220], [220, 150]]), (512, 512))

    def test_original_color_needs_no_guessed_rgb(self):
        request = self.render_request(); request['originalColor'] = True; request['targetRgb'] = None
        result = d.render(request)
        self.assertTrue(result['report']['passed']); self.assertIsNone(result['report']['targetRgb'])

if __name__ == '__main__':
    unittest.main()
