#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source firebase/dev-env.sh
python3 - <<'PY'
import json,subprocess,time,urllib.request,os
base='http://127.0.0.1:8085/v1/projects/demo-evening-deals/databases/(default)/documents:runQuery'
def running():
 try:
  with urllib.request.urlopen('http://127.0.0.1:4400/emulators',timeout=2) as r: hub=json.load(r)
  return hub.get('firestore',{}).get('port')==8085 and hub.get('auth',{}).get('port')==9099
 except (OSError,ValueError):return False
if not running():
 if not os.path.isfile('node_modules/.bin/firebase'):raise SystemExit('Run npm ci before starting local Firebase.')
 with open('/tmp/evening-firebase.log','ab') as log:
  child=subprocess.Popen(['node_modules/.bin/firebase','emulators:start','--project','demo-evening-deals','--only','auth,firestore'],stdout=log,stderr=log,start_new_session=True)
 for _ in range(40):
  time.sleep(.5)
  if child.poll() is not None:raise SystemExit('Firebase startup failed; see /tmp/evening-firebase.log')
  if running():break
 else:raise SystemExit('Firebase startup timed out; see /tmp/evening-firebase.log')
subprocess.run(['node','firebase/seed.mjs'],check=True)
body=json.dumps({'email':'customer@demo.kr','password':'Demo1234!','returnSecureToken':True}).encode()
r=urllib.request.Request('http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=demo-api-key',data=body,headers={'Content-Type':'application/json'})
with urllib.request.urlopen(r,timeout=5) as response:auth=json.load(response)
assert auth.get('idToken') and auth.get('localId'), 'Auth emulator login failed'
r=urllib.request.Request(base,data=json.dumps({'structuredQuery':{'from':[{'collectionId':'deals'}],'limit':20}}).encode(),headers={'Content-Type':'application/json'})
with urllib.request.urlopen(r,timeout=5) as response:catalog=json.load(response)
assert isinstance(catalog,list) and any('document' in item for item in catalog), 'Guest catalog smoke test failed'
print('Firebase ready: Auth email login and rules-protected guest catalog verified (tokens not logged).')
PY
