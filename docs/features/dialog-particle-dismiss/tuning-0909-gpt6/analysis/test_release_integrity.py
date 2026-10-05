"""原位表面解除测量的遮挡回归；不需要 GPU 或参考缓存。"""
import unittest
import sys
from pathlib import Path
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parent))
from release_integrity import Probe


class ReleaseIntegrityTests(unittest.TestCase):
    def measure(self, values):
        probe=Probe.__new__(Probe)
        probe.valid=np.ones((1,1),dtype=bool)
        probe.progress=[-.06,-.03,0.,.05,.10,.15,.20,.30,.40,.50]
        return probe.release([np.array([[v]],dtype=float) for v in values])[0,0]

    def test_initial_dialog_does_not_hide_later_visible_surface(self):
        self.assertEqual(self.measure([0,0,0,.9,1,.98,.95,.8,.2,0]),.4)

    def test_partial_initial_occlusion_does_not_delay_release_threshold(self):
        self.assertEqual(self.measure([.35,.35,.35,.95,.95,.9,.6,.4,.1,0]),.3)

    def test_real_early_release_is_preserved(self):
        self.assertEqual(self.measure([1,1,.8,.3,.1,0,0,0,0,0]),.05)

    def test_moving_occluder_is_not_a_release(self):
        self.assertEqual(self.measure([1,1,1,.1,.9,1,.95,.9,.3,0]),.4)

    def test_never_identifiable_surface_stays_unknown(self):
        self.assertTrue(np.isnan(self.measure([.35]*10)))

    def test_surface_visible_at_the_end_is_censored(self):
        self.assertEqual(self.measure([1]*10),1.)


if __name__=='__main__':
    unittest.main(verbosity=2)
