#!/usr/bin/env bash
set -euo pipefail
cd /workspace/evening-deals-android
python3 - <<'PY'
import pathlib,json,hashlib,urllib.request,tarfile,shutil
root=pathlib.Path('/workspace/tools');root.mkdir(exist_ok=True)
spec=json.loads(pathlib.Path('firebase/node-runtime.json').read_text());path=root/'node22.tar.xz'
if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest()!=spec['sha256']:
 with urllib.request.urlopen(spec['source']) as response,path.open('wb') as target:shutil.copyfileobj(response,target)
assert hashlib.sha256(path.read_bytes()).hexdigest()==spec['sha256'],'Official Node.js checksum mismatch'
if not (root/spec['directory']/'bin/node').exists():tarfile.open(path).extractall(root,filter='data')
print('Verified Node.js',spec['version'])
PY
