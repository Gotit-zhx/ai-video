import unittest
import numpy as np
from geometry_v3 import DEFAULT_PARAMS
from geometry_offshoulder import build_mesh
from evaluator import evaluate_mesh


class OffShoulderTests(unittest.TestCase):
    def test_high_shoulder_direction_and_open_shell(self):
        for resolution in ('fit','preview'):
            mesh=build_mesh({**DEFAULT_PARAMS,'shoulder_slope':.45,'low_cuff_scale':1.35},resolution)
            v=np.asarray(mesh['vertices']);neck=v[mesh['openings']['neck']]
            self.assertGreater(neck[neck[:,0]>0,1].mean(),neck[neck[:,0]<0,1].mean()+.05)
            self.assertEqual(len(mesh['openings']),4)
            self.assertTrue(evaluate_mesh(mesh)['implementedStructuralChecksPassed'])

    def test_shape_parameters_preserve_gpu_topology(self):
        meshes=[build_mesh({**DEFAULT_PARAMS,'shoulder_slope':s,'low_cuff_scale':c},'fit') for s,c in [(.2,1.1),(.5,1.5),(.7,1.65)]]
        for m in meshes[1:]:
            self.assertEqual(m['faces'],meshes[0]['faces'])
            self.assertEqual(len(m['vertices']),len(meshes[0]['vertices']))
