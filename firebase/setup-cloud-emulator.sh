#!/usr/bin/env bash
# Optional cloud fallback when storage.googleapis.com is unavailable.
# Uses an unmodified official Google Cloud SDK artifact and verifies its SHA-256.
set -euo pipefail
cd /workspace/evening-deals-android
source scripts/env.sh
mkdir -p /workspace/tools
python3 - <<'PYCODE'
import json,pathlib,urllib.request,hashlib,tarfile
root=pathlib.Path('/workspace/tools');spec=json.loads(pathlib.Path('firebase/emulator-component.json').read_text())
path=root/'firestore-component.tar.gz'
if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest()!=spec['sha256']:
 with urllib.request.urlopen(spec['source']) as response, path.open('wb') as target:
  import shutil
  shutil.copyfileobj(response,target)
assert hashlib.sha256(path.read_bytes()).hexdigest()==spec['sha256'], 'Official SDK component checksum mismatch'
tarfile.open(path).extractall(root/'firestore-component',filter='data')
print('Verified official Google Cloud SDK Firestore emulator',spec['version'])
PYCODE
mkdir -p /workspace/tools/firestore-launcher-empty
cat > /workspace/tools/firestore-launcher.manifest <<'EOF'
Manifest-Version: 1.0
Main-Class: com.google.cloud.datastore.emulator.firestore.CloudFirestore
Class-Path: file:/workspace/tools/firestore-component/platform/cloud-firestore-emulator/cloud-firestore-emulator.jar

EOF
# Launcher configuration only. The verified upstream JAR is not modified.
"$JAVA_HOME/bin/jar" cfm /workspace/tools/firestore-launcher.jar /workspace/tools/firestore-launcher.manifest -C /workspace/tools/firestore-launcher-empty .
