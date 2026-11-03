import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zlib

spec = importlib.util.spec_from_file_location('inventariar', Path(__file__).with_name('inventariar.py'))
inv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inv)


def png():
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 2, 1, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(b'\0\xff\0\0\0\xff\0')) + chunk(b'IEND', b''))


class InventoryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='pelotavision-')
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.src = self.root / 'fonte com acentos e espacos'
        self.src.mkdir()
        self.img = self.src / 'foto_original.png'
        self.img.write_bytes(png())

    def test_read_only_metadata_duplicates_and_no_labels(self):
        (self.src / 'copia_original.png').write_bytes(png())
        before = self.img.read_bytes()
        result = inv.inventory(self.src, self.root / 'out')
        self.assertEqual(result['errors'], 0)
        self.assertEqual(len(result['exact_duplicate_groups']), 1)
        self.assertEqual(result['images'][0]['width'], 2)
        self.assertIsNone(result['images'][0]['optical_quality'])
        self.assertFalse(result['images'][0]['ground_truth'])
        self.assertEqual(self.img.read_bytes(), before)
        self.assertFalse((self.root / 'out' / 'originals').exists())

    def test_copy_only_originals_and_verify_hash(self):
        (self.src / 'foto_resultado.png').write_bytes(png())
        result = inv.inventory(self.src, self.root / 'out', copy_originals=True)
        self.assertEqual(sum('local_copy' in r for r in result['images']), 1)
        rec = next(r for r in result['images'] if 'local_copy' in r)
        self.assertEqual(inv.digest(self.root / 'out' / rec['local_copy']), inv.digest(self.img))

    def test_refuses_overlap_existing_destination_and_repo(self):
        for dest in (self.src / 'nested', self.root, self.src):
            with self.assertRaises(ValueError):
                inv.inventory(self.src, dest)
        out = self.root / 'out'
        inv.inventory(self.src, out)
        with self.assertRaises(FileExistsError):
            inv.inventory(self.src, out)
        with self.assertRaises(ValueError):
            inv.inventory(self.src, self.root / 'repo' / 'data', repository=self.root / 'repo')

    def test_corruption_is_recorded_and_no_copy(self):
        self.img.write_bytes(b'\x89PNG\r\n\x1a\n')
        result = inv.inventory(self.src, self.root / 'out', copy_originals=True)
        self.assertEqual(result['errors'], 1)
        self.assertNotIn('local_copy', result['images'][0])
        self.assertEqual(json.loads((self.root / 'out' / 'manifest.json').read_text('utf-8'))['errors'], 1)

    def test_jpeg_dimensions_and_basic_exif(self):
        path = self.src / 'mini.jpeg'
        tiff = b'II' + struct.pack('<HIH', 42, 8, 1) + struct.pack('<HHI', 274, 3, 1) + struct.pack('<H', 6) + b'\0\0' + b'\0' * 4
        app1 = b'Exif\0\0' + tiff
        frame = struct.pack('>BHHB', 8, 30, 40, 3) + b'\x01\x11\0\x02\x11\0\x03\x11\0'
        path.write_bytes(b'\xff\xd8\xff\xe1' + struct.pack('>H', len(app1) + 2) + app1
                         + b'\xff\xc0' + struct.pack('>H', len(frame) + 2) + frame + b'\xff\xd9')
        meta = inv.image_metadata(path)
        self.assertEqual((meta['width'], meta['height']), (40, 30))
        self.assertEqual(meta['exif']['fields']['orientation'], 6)

    def test_derived_files_are_not_originals(self):
        for name in ('a_resultado.png', 'a_mapa.png', 'a_filtrada.png', 'a_mask_semantic.png'):
            self.assertEqual(inv.file_role(Path(name)), 'derived_visualization')
        self.assertEqual(inv.file_role(Path('camera.JPG')), 'unknown_image')

    def test_unicode_paths_and_no_absolute_source_in_manifest(self):
        folder = self.src / 'aquisi\u00e7\u00e3o'
        folder.mkdir()
        (folder / 'fotografia_original.png').write_bytes(png())
        result = inv.inventory(self.src, self.root / 'out')
        text = (self.root / 'out' / 'manifest.json').read_text('utf-8')
        self.assertNotIn(str(self.src), text)
        self.assertEqual(len(result['images']), 2)
        self.assertTrue(any('aquisi\u00e7\u00e3o' in r['relative_path'] for r in result['images']))


if __name__ == '__main__':
    unittest.main()
