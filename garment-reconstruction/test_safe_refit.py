import json,unittest
import cv2,numpy as np
from safe_refit import ASSET,SEED,fit
from evaluator import evaluate_mesh
from profile_fit import opposite_consistency


class BoundedRefitTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.seed=json.loads(SEED.read_text());cls.refs=json.loads((ASSET/'reference-manifest.json').read_text())
        cls.masks=[cv2.imread(str(ASSET/f'{r["id"]}-mask.png'),0) for r in cls.refs]
        cls.result=fit(cls.seed,cls.refs,cls.masks)

    def test_preserves_topology_and_four_openings(self):
        self.assertEqual(self.seed['faces'],self.result['faces'])
        self.assertEqual(self.seed['openings'],self.result['openings'])
        self.assertTrue(evaluate_mesh(self.result)['implementedStructuralChecksPassed'])

    def test_bounded_positive_deformation_no_seed_mutation(self):
        self.assertEqual(self.seed,json.loads(SEED.read_text()))
        for field in self.result['refit']['fields']:
            self.assertGreaterEqual(min(field['scale']),.75-1e-9)
            self.assertLessEqual(max(field['scale']),1.35+1e-9)
            self.assertLessEqual(max(abs(np.asarray(field['offset']))),.12+1e-9)

    def test_frozen_side_conflict_is_not_reported_as_passed(self):
        report=opposite_consistency(self.refs,self.masks)
        self.assertLess(report[1]['commonSilhouetteMinIoUUpperBound'],.95)
        self.assertGreater(report[0]['commonSilhouetteMinIoUUpperBound'],.95)

    def test_repeated_fit_is_deterministic_from_original(self):
        self.assertEqual(fit(self.seed,self.refs,self.masks)['vertices'],self.result['vertices'])
