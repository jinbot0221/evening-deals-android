#!/usr/bin/env bash
set -euo pipefail
cd /workspace/evening-deals-android
python3 - <<'PY'
import json,os,subprocess,time,urllib.request
port=int(os.environ.get('PORT','8080'))
base=f'http://127.0.0.1:{port}'
def ready():
 try:
  with urllib.request.urlopen(base+'/health',timeout=2) as r: health=json.load(r)
  with urllib.request.urlopen(base+'/deals',timeout=2) as r: deals=json.load(r)
  return health.get('status')=='ok' and isinstance(deals.get('items'),list)
 except (OSError,ValueError):return False
if ready():
 print('API ready: health and guest catalog verified (existing server)')
else:
 with open('/tmp/evening-api.log','ab') as log:
  child=subprocess.Popen(['python3','server/server.py'],stdout=log,stderr=log,start_new_session=True)
 for _ in range(20):
  time.sleep(.5)
  if child.poll() is not None:raise SystemExit('Server startup failed: see /tmp/evening-api.log')
  if ready():
   print('API ready: health and guest catalog verified');break
 else:raise SystemExit('Server readiness timed out: see /tmp/evening-api.log')
PY
