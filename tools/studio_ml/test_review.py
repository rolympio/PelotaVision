"""Testes sintéticos; não leem fotos ou dados de revisão reais."""
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import cv2
import numpy as np

from . import review
from .pipeline import read_image, sha256, write_image


class ReviewTest(unittest.TestCase):
    def setUp(self):
        approved = Path(os.environ.get("LOCALAPPDATA", tempfile.gettempdir())) / "Temp" / "opencode"
        approved.mkdir(parents=True, exist_ok=True)
        self.tmp = tempfile.TemporaryDirectory(prefix="review-test-", dir=approved)
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.private = self.base / "private"
        self.private.mkdir()
        self.candidate = self.private / "run" / "image-0000"
        self.candidate.mkdir(parents=True)
        self.gray = np.full((30, 40), 80, np.uint8)
        self.labels = np.zeros(self.gray.shape, np.uint16)
        self.labels[5:25, 4:18] = 1
        self.labels[5:25, 20:34] = 400
        write_image(self.candidate / "instances_uint16.png", self.labels)
        np.savez_compressed(self.candidate / "training_dev.npz", gray=self.gray,
                            weight=np.full(self.gray.shape, 255, np.uint8))
        with (self.candidate / "record.json").open("w", encoding="utf-8") as f:
            json.dump({"mask_shape_hw": [30, 40], "source_sha256": "synthetic",
                       "source_shape_hw": [60, 80], "transform": {"scale_xy": [.5, .5]}}, f)
        self.private_patch = patch.object(review, "PRIVATE", self.private)
        self.private_patch.start()
        self.addCleanup(self.private_patch.stop)
        self.model = review.load_candidate(self.candidate)

    def test_unknown_background_and_no_heuristic_weights(self):
        self.assertFalse(self.model.background.any())
        self.assertFalse(self.model.cracks.any())
        self.model.paint("fundo", None, [(0, 0), (10, 10)], 1)
        self.assertGreater(self.model.background.sum(), 0)
        self.assertFalse(np.any(self.model.background[self.model.labels > 0]))
        self.model.paint("apagar", 1, [(8, 8)], 1)
        self.assertEqual(self.model.labels[8, 8], 0)
        self.assertEqual(self.model.background[8, 8], 0)
        self.assertEqual(self.model.background[29, 39], 0)

    def test_sam_prompt_provenance_survives_review(self):
        path = self.candidate / 'record.json'
        record = json.loads(path.read_text(encoding='utf-8'))
        record.update(candidate_model='sam2.1_hiera_tiny', candidate_method='stardist_centers')
        path.write_text(json.dumps(record), encoding='utf-8')
        model = review.load_candidate(self.candidate)
        self.assertEqual(model.meta[1]['origem'], 'sam2.1_hiera_tiny:stardist_centers')
        out = review.export_review(model, 'teste_sintetico')
        manifest = json.loads((out / 'revisao.json').read_text(encoding='utf-8'))
        self.assertEqual(manifest['origem_pipeline']['candidate_method'], 'stardist_centers')
        self.assertEqual(manifest['origem_pipeline']['candidate_model'], 'sam2.1_hiera_tiny')

    def test_reference_excluded_without_inventing_scale(self):
        self.model.set_metadata(1, {'classe':'esfera_11mm_confirmada_sem_medicao',
            'confianca':'alta','oclusao':'nao','estado':'revisada_leiga'})
        out=review.export_review(self.model,'teste_sintetico')
        manifest=json.loads((out/'revisao.json').read_text(encoding='utf-8'))
        self.assertIn(1,manifest['excluir_granulometria_ids'])
        self.assertIn('registro rastreavel',manifest['calibracao_fisica'])

    def test_brush_avoids_other_ids_and_background_collision(self):
        self.model.paint("fundo", None, [(19, 10)], 1)
        self.model.paint("pintar", 1, [(18, 10), (23, 10)], 1)
        self.assertEqual(self.model.labels[10, 21], 400)
        self.assertEqual(self.model.labels[10, 18], 1)
        self.assertEqual(self.model.background[10, 18], 0)
        with self.assertRaises(ValueError):
            self.model.paint("fundo", None, [(40, 0)], 1)

    def test_new_crack_is_explicit_and_unannotated_is_not_negative(self):
        ident = self.model.create()
        self.model.paint("pintar", ident, [(37, 27)], 1)
        self.model.paint("trinca", ident, [(37, 27)], 1)
        self.assertEqual(self.model.cracks[27, 37], 255)
        self.assertEqual(self.model.cracks[10, 10], 0)
        self.model.paint("apagar", ident, [(37, 27)], 1)
        self.assertEqual(self.model.cracks[27, 37], 0)
        self.assertEqual(self.model.background[27, 37], 0)

    def test_split_merge_and_reconsider_metadata(self):
        self.model.set_metadata(1, {"classe": "esfera_11mm_confirmada_sem_medicao", "confianca": "alta",
                                    "oclusao": "nao", "estado": "revisada_leiga"})
        before = self.model.labels.copy()
        with self.assertRaises(ValueError):
            self.model.split(1, [(0, 0)], 1)
        np.testing.assert_array_equal(before, self.model.labels)
        other = self.model.split(1, [(10, 0), (10, 29)], 1)
        self.assertEqual(self.model.meta[1]["estado"], "pendente")
        self.assertEqual(self.model.meta[other]["oclusao"], "desconhecida")
        self.assertEqual(self.model.labels[10, 10], 0)
        self.assertEqual(self.model.background[10, 10], 0)
        self.model.merge(1, other)
        self.assertEqual(self.model.meta[1]["classe"], "indeterminada")
        self.assertEqual(self.model.meta[1]["integridade_estrutural"], "nao_avaliada")
        self.assertFalse(np.any(self.model.labels == other))

    def test_edit_demotes_review_and_invalid_metadata_rejected(self):
        good = {"classe": "ignorar", "confianca": "media", "oclusao": "sim", "estado": "revisada_leiga"}
        self.model.set_metadata(1, good)
        self.model.paint("apagar", 1, [(8, 8)], 1)
        self.assertEqual(self.model.meta[1]["estado"], "pendente")
        with self.assertRaises(ValueError):
            self.model.set_metadata(1, {**good, "classe": "integra"})

    def test_export_is_private_exclusive_traceable_and_does_not_modify_candidate(self):
        before = {p.name: sha256(p) for p in self.candidate.iterdir()}
        self.model.paint("fundo", None, [(0, 0)], 1)
        self.model.paint("trinca", 1, [(8, 8)], 1)
        out = review.export_review(self.model, "revisor sintético")
        self.assertTrue(out.is_relative_to(self.private))
        self.assertNotEqual(out, review.export_review(self.model, "outro"))
        self.assertEqual(before, {p.name: sha256(p) for p in self.candidate.iterdir()})
        with (out / "revisao.json").open(encoding="utf-8") as f:
            manifest = json.load(f)
        with (out / "hashes.json").open(encoding="utf-8") as f:
            hashes = json.load(f)
        for name, digest in hashes.items():
            self.assertEqual(sha256(out / name), digest)
        self.assertEqual(manifest["hashes_entrada_sha256"], before)
        self.assertEqual(manifest["estado_revisao"], "leiga_nao_ground_truth")
        self.assertEqual(manifest["origem_pipeline"]["transform"]["scale_xy"], [.5, .5])
        self.assertTrue(all(i["integridade_estrutural"] == "nao_avaliada" for i in manifest["instancias"]))
        self.assertEqual(read_image(out / "instancias_uint16.png", cv2.IMREAD_UNCHANGED).dtype, np.uint16)
        bg = read_image(out / "fundo_humano_uint8.png", cv2.IMREAD_UNCHANGED)
        self.assertEqual(bg[29, 39], 0)
        self.assertEqual(bg[0, 0], 255)
        with self.assertRaises(FileExistsError):
            write_image(out / "fundo_humano_uint8.png", bg)

    def test_export_rejects_untrusted_paths_changed_input_and_no_reviewer(self):
        (self.base / "outside").mkdir()
        with self.assertRaises(ValueError): review.export_review(self.model, "x", self.base / "outside")
        with self.assertRaises(ValueError): review.export_review(self.model, "x", self.candidate)
        with self.assertRaises(ValueError): review.export_review(self.model, " ")
        # Entrada já aberta não pode ser trocada sem rejeição.
        with (self.candidate / "record.json").open("a", encoding="utf-8") as f: f.write(" ")
        with self.assertRaises(ValueError): review.export_review(self.model, "x")
        self.assertEqual(sorted(p.name for p in self.private.iterdir()), ["run"])

    def test_pickle_refused_and_wrong_grid_rejected(self):
        np.savez_compressed(self.candidate / "training_dev.npz", gray=np.array([object()], dtype=object))
        with self.assertRaises(ValueError): review.load_candidate(self.candidate)
        np.savez_compressed(self.candidate / "training_dev.npz", gray=np.zeros((4, 4), np.uint8))
        with self.assertRaises(ValueError): review.load_candidate(self.candidate)

    def test_symlink_escape_is_rejected_if_available(self):
        link = self.private / "escape"
        try:
            link.symlink_to(self.base, target_is_directory=True)
        except OSError:
            self.skipTest("Criação de link simbólico não permitida neste Windows")
        with self.assertRaises(ValueError): review.export_review(self.model, "x", link)


if __name__ == "__main__":
    unittest.main(verbosity=2)
