"""Manifesto supervisionado de recortes (CSV UTF-8, uma pelota por linha).

Colunas obrigatórias (compatíveis com o kit): image_path,label,group_id,material_type,origin
Colunas adicionais desta pipeline:
  cx,cy,r            círculo da pelota no recorte (px)
  source_id          fonte (ver docs/datasets/fontes.json)
  source_image       arquivo de origem; source_sha256 = hash do arquivo original
  instance_id        id da pelota na anotação de origem
  label_origin       quem/como rotulou (ex.: assistant_visual_provisional, expert, synthetic_render)
  split_role         dev (validação cruzada agrupada) | holdout (teste final) | train_only
  source_width,x_offset  para reproduzir exatamente a variante de iluminação 4
  license_status     verified | internal_unverified | blocked

Regras (validate):
  label ∈ {cracked, intact, uncertain}; 'uncertain' nunca entra em treino/teste supervisionado.
  material_type ∈ {green, fired, unknown}; origin ∈ {real, synthetic, unverified}.
  synthetic nunca é holdout; um group_id não pode estar em dev e holdout ao mesmo tempo.
  Pelota sem anotação de trinca deve ser 'uncertain', nunca 'intact' implícito.
"""
import csv
from pathlib import Path

REQUIRED = ['image_path', 'label', 'group_id', 'material_type', 'origin']
EXTRA = ['cx', 'cy', 'r', 'source_id', 'source_image', 'source_sha256', 'instance_id', 'label_origin', 'split_role',
         'source_width', 'x_offset', 'license_status']
COLUMNS = REQUIRED + EXTRA
LABELS = {'cracked', 'intact', 'uncertain'}
MATERIALS = {'green', 'fired', 'unknown'}
ORIGINS = {'real', 'synthetic', 'unverified'}
ROLES = {'dev', 'holdout', 'train_only'}


class ManifestError(ValueError):
    pass


def write(rows, path):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('w', newline='', encoding='utf-8') as f:
        w = csv.DictWriter(f, fieldnames=COLUMNS, extrasaction='raise')
        w.writeheader()
        for r in rows:
            w.writerow({k: r.get(k, '') for k in COLUMNS})


def read(path):
    path = Path(path)
    with path.open(newline='', encoding='utf-8-sig') as f:
        rows = list(csv.DictReader(f))
    validate(rows)
    for r in rows:
        p = Path(r['image_path'])
        r['abs_path'] = str(p if p.is_absolute() else path.parent / p)
    return rows


def validate(rows):
    if not rows:
        raise ManifestError('manifesto vazio')
    missing = set(REQUIRED) - set(rows[0])
    if missing:
        raise ManifestError(f'colunas ausentes: {sorted(missing)}')
    roles_by_group = {}
    for i, r in enumerate(rows, 2):
        lab, mat, org = r['label'].strip().lower(), r['material_type'].strip().lower(), r['origin'].strip().lower()
        if lab not in LABELS:
            raise ManifestError(f'linha {i}: rótulo inválido {lab!r} (use cracked/intact/uncertain)')
        if mat not in MATERIALS:
            raise ManifestError(f'linha {i}: material_type inválido {mat!r}')
        if org not in ORIGINS:
            raise ManifestError(f'linha {i}: origin inválida {org!r}')
        if not r['group_id'].strip():
            raise ManifestError(f'linha {i}: group_id vazio')
        role = (r.get('split_role') or 'dev').strip()
        if role not in ROLES:
            raise ManifestError(f'linha {i}: split_role inválido {role!r}')
        if org == 'synthetic' and role == 'holdout':
            raise ManifestError(f'linha {i}: imagem sintética não pode estar no teste final')
        if org == 'synthetic' and role == 'dev':
            raise ManifestError(f'linha {i}: imagem sintética deve ser train_only (não entra em nenhuma avaliação)')
        roles_by_group.setdefault(r['group_id'], set()).add(role)
        r['label'], r['material_type'], r['origin'], r['split_role'] = lab, mat, org, role
    for g, roles in roles_by_group.items():
        if {'dev', 'holdout'} <= roles:
            raise ManifestError(f'grupo {g!r} aparece em dev e holdout (vazamento)')
    return rows
