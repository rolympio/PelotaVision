from pathlib import Path
import hashlib,json,shutil
root=Path(__file__).resolve().parent
meta=json.loads((root/'training/metadata.json').read_text())
assert hashlib.sha256((root/'training/crack_forest.pvrf').read_bytes()).hexdigest()==meta['sha256']
assets=root.parent/'app/src/main/assets'
for name in ['crack_forest.pvrf','metadata.json']:shutil.copy2(root/'training'/name,assets/name)
print('Modelo copiado. Recompile e repita os testes de paridade e de exportação.')
