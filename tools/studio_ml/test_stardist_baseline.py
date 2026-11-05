"""Testes sintéticos do baseline StarDist, sem imagens privadas."""
import unittest
import numpy as np
from .stardist_baseline import rasterize

class StardistBaselineTest(unittest.TestCase):
    def test_rasterize_polygons_e_ids(self):
        labels = rasterize((20, 20), [[(3, 3), (7, 3), (7, 7), (3, 7)], [(12, 12), (16, 12), (16, 16), (12, 16)]])
        self.assertEqual(labels.dtype, np.uint16)
        self.assertEqual(set(np.unique(labels)) - {0}, {1, 2})
        self.assertGreater((labels == 1).sum(), 10)
        self.assertGreater((labels == 2).sum(), 10)

    def test_rasterize_rejeita_poligono_vazio(self):
        labels = rasterize((10, 10), [[]])
        self.assertFalse(labels.any())

if __name__ == '__main__':
    unittest.main()
