#!/usr/bin/env python3
"""Inventario privado, somente leitura por padrao. Apenas biblioteca padrao (Python >=3.11)."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import stat
from datetime import datetime, timezone

EXTENSIONS = {'.png', '.jpg', '.jpeg', '.tif', '.tiff', '.bmp', '.webp'}
DERIVED = ('_resultado', '_mapa', '_filtrada', '_mask', '_mask_semantic', '_mask_instances')


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def exif_summary(data):
    """Le apenas tags EXIF nao sensiveis do IFD0. Nao exporta GPS/numero de serie."""
    result = {'present': True, 'fields': {}, 'note': 'Subconjunto IFD0; GPS e identificadores omitidos.'}
    try:
        endian = {b'II': '<', b'MM': '>'}[data[:2]]
        if struct.unpack_from(endian + 'H', data, 2)[0] != 42:
            raise ValueError('Cabecalho TIFF invalido')
        offset = struct.unpack_from(endian + 'I', data, 4)[0]
        count = struct.unpack_from(endian + 'H', data, offset)[0]
        tags = {271: 'make', 272: 'model', 274: 'orientation', 306: 'datetime'}
        for i in range(min(count, 1024)):
            pos = offset + 2 + 12 * i
            tag, kind, size = struct.unpack_from(endian + 'HHI', data, pos)
            if tag not in tags:
                continue
            if kind == 2 and 0 < size <= 4096:
                start = pos + 8 if size <= 4 else struct.unpack_from(endian + 'I', data, pos + 8)[0]
                value = data[start:start + size].rstrip(b'\0').decode('utf-8', errors='replace')
            elif kind == 3 and size == 1:
                value = struct.unpack_from(endian + 'H', data, pos + 8)[0]
            else:
                continue
            result['fields'][tags[tag]] = value
    except (KeyError, ValueError, struct.error) as exc:
        result['parse_warning'] = str(exc)
    return result


def image_metadata(path):
    """Inspecao de cabecalho, nao verificacao completa da decodificacao/foco."""
    result = {'format': None, 'width': None, 'height': None, 'bit_depth_per_sample': None,
              'channels': None, 'exif': {'present': None, 'fields': {}},
              'inspection': 'header_only'}
    with path.open('rb') as f:
        header = f.read(32)
        if header.startswith(b'\x89PNG\r\n\x1a\n'):
            if len(header) < 29 or header[12:16] != b'IHDR':
                raise ValueError('PNG sem IHDR valido')
            width, height, depth, color = struct.unpack('>IIBB', header[16:26])
            result.update(format='PNG', width=width, height=height, bit_depth_per_sample=depth,
                          channels={0: 1, 2: 3, 3: 1, 4: 2, 6: 4}.get(color), png_color_type=color)
            # PNG indexado: a profundidade representa o indice, nao as amostras RGB.
            f.seek(8)
            found = False
            while True:
                chunk = f.read(8)
                if len(chunk) != 8:
                    break
                length, kind = struct.unpack('>I4s', chunk)
                if kind == b'eXIf' and length <= 4 * 1024 * 1024:
                    result['exif'] = exif_summary(f.read(length))
                    found = True
                    break
                f.seek(length + 4, 1)
                if kind == b'IEND':
                    break
            if not found:
                result['exif']['present'] = False
        elif header[:2] == b'\xff\xd8':
            result['format'] = 'JPEG'
            result['exif']['present'] = False
            f.seek(2)
            while True:
                byte = f.read(1)
                if not byte:
                    break
                if byte != b'\xff':
                    continue
                marker = f.read(1)
                while marker == b'\xff':
                    marker = f.read(1)
                if not marker or marker in (b'\xda', b'\xd9'):
                    break
                if marker[0] in (0x01, *range(0xD0, 0xD9)):
                    continue
                size_bytes = f.read(2)
                if len(size_bytes) != 2:
                    raise ValueError('JPEG truncado')
                length = struct.unpack('>H', size_bytes)[0] - 2
                if length < 0:
                    raise ValueError('Segmento JPEG invalido')
                payload = f.read(length)
                if len(payload) != length:
                    raise ValueError('JPEG truncado')
                if marker == b'\xe1' and payload.startswith(b'Exif\0\0'):
                    result['exif'] = exif_summary(payload[6:])
                if marker[0] in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
                    depth, height, width, channels = struct.unpack('>BHHB', payload[:6])
                    result.update(width=width, height=height, bit_depth_per_sample=depth, channels=channels)
            if result['width'] is None:
                raise ValueError('JPEG sem marcador de dimensoes')
        else:
            result['format'] = path.suffix.lstrip('.').upper()
            result['inspection'] = 'unsupported_header_metadata_pending'
    return result


def file_role(path):
    stem = path.stem.lower()
    if stem.endswith('_original'):
        return 'exported_original_candidate'
    if stem.endswith(DERIVED):
        return 'derived_visualization'
    return 'unknown_image'


def is_link_or_junction(path):
    # Path.is_junction so existe a partir do Python 3.12. No 3.11, consultar
    # a tag do reparse point sem bloquear arquivos normais sob demanda do OneDrive.
    info = path.lstat()
    return stat.S_ISLNK(info.st_mode) or getattr(info, 'st_reparse_tag', 0) in (0xA0000003, 0xA000000C)


def inventory(source, output, copy_originals=False, repository=None):
    source = Path(source).resolve(strict=True)
    output = Path(output).resolve()
    if not source.is_dir():
        raise ValueError('Fonte precisa ser um diretorio')
    if output == source or source in output.parents or output in source.parents:
        raise ValueError('Saida e fonte precisam estar em arvores separadas')
    if repository:
        repo = Path(repository).resolve()
        if output == repo or repo in output.parents:
            raise ValueError('Manifestos privados nao podem ficar no repositorio')
    if output.exists():
        raise FileExistsError('Saida ja existe; use outra pasta. Nada sera sobrescrito')
    output.mkdir(parents=True, exist_ok=False)
    records, skipped = [], []
    errors = 0
    for root, dirs, files in os.walk(source, followlinks=False):
        # Nao seguir links de diretorios ou incluir metadados Git.
        safe_dirs = []
        for name in sorted(dirs):
            child = Path(root) / name
            if name == '.git' or is_link_or_junction(child):
                skipped.append(child.relative_to(source).as_posix())
            else:
                safe_dirs.append(name)
        dirs[:] = safe_dirs
        for name in sorted(files):
            path = Path(root) / name
            if path.suffix.lower() not in EXTENSIONS:
                continue
            rel = path.relative_to(source).as_posix()
            if is_link_or_junction(path) or source not in path.resolve().parents:
                skipped.append(rel)
                continue
            rec = {'relative_path': rel, 'role': file_role(path),
                   'acquisition_group_candidate': path.parent.relative_to(source).as_posix(),
                   'acquisition_group_confirmed': False, 'physical_sample_id': None,
                   'split': 'unassigned', 'illumination': None, 'optical_quality': None,
                   'has_11mm_reference': None, 'has_overlap': None, 'estimated_pellet_count': None,
                   'training_eligibility': 'pending_review', 'ground_truth': False}
            try:
                before = path.stat()
                rec.update(sha256=digest(path), size_bytes=before.st_size, **image_metadata(path))
                after = path.stat()
                if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
                    raise ValueError('Arquivo alterado durante o inventario; repetir aquisicao')
                rec['image_id'] = 'sha256:' + rec['sha256']
                if copy_originals and rec['role'] == 'exported_original_candidate':
                    target = output / 'originals' / (rec['sha256'] + path.suffix.lower())
                    target.parent.mkdir(exist_ok=True)
                    if not target.exists():
                        with path.open('rb') as src, target.open('xb') as dest:
                            shutil.copyfileobj(src, dest)
                    if digest(target) != rec['sha256']:
                        raise ValueError('Hash da copia diferente da fonte')
                    rec['local_copy'] = target.relative_to(output).as_posix()
            except (OSError, ValueError, struct.error) as exc:
                rec['error'] = str(exc)
                rec['training_eligibility'] = 'blocked_inventory_error'
                errors += 1
            records.append(rec)
    groups = {}
    for rec in records:
        if 'sha256' in rec and 'error' not in rec:
            groups.setdefault(rec['sha256'], []).append(rec['relative_path'])
    manifest = {'schema_version': 1, 'created_utc': datetime.now(timezone.utc).isoformat(),
                'private': True, 'source_absolute_path_omitted': True,
                'mode': 'copy_exported_originals' if copy_originals else 'read_only_inventory',
                'image_count': len(records), 'errors': errors,
                'role_counts': {role: sum(r['role'] == role for r in records)
                                for role in sorted({r['role'] for r in records})},
                'exact_duplicate_groups': {h: paths for h, paths in groups.items() if len(paths) > 1},
                'near_duplicates': {'status': 'not_evaluated', 'reason': 'Requer comparacao perceptual posterior'},
                'skipped_links_or_git': skipped, 'images': records}
    with (output / 'manifest.json').open('x', encoding='utf-8') as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--copy-originals', action='store_true', help='Copia candidatos *_original; nao move nem publica')
    args = parser.parse_args()
    manifest = inventory(args.source, args.output, args.copy_originals, Path(__file__).resolve().parents[2])
    print(json.dumps({k: manifest[k] for k in ('image_count', 'errors', 'role_counts')}, ensure_ascii=False))
    print('Manifesto privado:', args.output / 'manifest.json')
    return 1 if manifest['errors'] else 0


if __name__ == '__main__':
    raise SystemExit(main())
