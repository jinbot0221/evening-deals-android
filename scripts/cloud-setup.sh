#!/usr/bin/env bash
set -euo pipefail
cd /workspace/evening-deals-android
source scripts/env.sh
mkdir -p /workspace/tools "$ANDROID_HOME" "$ANDROID_USER_HOME" "$GRADLE_USER_HOME"
if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/javac" ]]; then
  curl -fsSL --retry 2 https://download.oracle.com/java/21/latest/jdk-21_linux-x64_bin.tar.gz -o /workspace/tools/jdk.tar.gz
  curl -fsSL --retry 2 https://download.oracle.com/java/21/latest/jdk-21_linux-x64_bin.tar.gz.sha256 -o /workspace/tools/jdk.sha256
  python3 - <<'PY'
import hashlib,pathlib,tarfile
p=pathlib.Path('/workspace/tools')
assert hashlib.sha256((p/'jdk.tar.gz').read_bytes()).hexdigest()==(p/'jdk.sha256').read_text().strip()
tarfile.open(p/'jdk.tar.gz').extractall(p,filter='data')
PY
  source scripts/env.sh
fi
# Use the platform's proxy and OS-installed certificate authorities with Java.
python3 - <<'PY'
import os,urllib.parse,pathlib,subprocess
root=pathlib.Path('/workspace/tools');trust=root/'cacerts'
if not trust.exists():
 import shutil
 shutil.copyfile('/etc/ssl/certs/java/cacerts',trust)
 for i,cert in enumerate(pathlib.Path('/usr/local/share/ca-certificates').glob('*.crt')):
  subprocess.run([os.environ['JAVA_HOME']+'/bin/keytool','-importcert','-noprompt','-alias',f'cloud-os-{i}','-file',str(cert),'-keystore',str(trust),'-storepass','changeit'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
p=pathlib.Path(os.environ['GRADLE_USER_HOME'])/'gradle.properties'
lines=p.read_text().splitlines() if p.exists() else []
updates={'systemProp.javax.net.ssl.trustStore':str(trust),'systemProp.javax.net.ssl.trustStorePassword':'changeit','systemProp.http.nonProxyHosts':'localhost|127.*|[::1]'}
u=urllib.parse.urlsplit(os.environ.get('HTTPS_PROXY',''))
if u.hostname:
 for scheme in ('http','https'):
  updates[f'systemProp.{scheme}.proxyHost']=u.hostname
  updates[f'systemProp.{scheme}.proxyPort']=str(u.port or 80)
lines=[l for l in lines if l.split('=',1)[0] not in updates]
p.write_text('\n'.join(lines+[f'{k}={v}' for k,v in updates.items()])+'\n')
PY
if [[ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]]; then
  curl -fsSL --retry 2 https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o /workspace/tools/android-tools.zip
  curl -fsSL --retry 2 https://dl.google.com/android/repository/repository2-1.xml -o /workspace/tools/repository.xml
  python3 - <<'PY'
import pathlib,hashlib,xml.etree.ElementTree as E,zipfile
p=pathlib.Path('/workspace/tools');found=False
for a in E.parse(p/'repository.xml').iter('archive'):
 c=a.find('complete')
 if c is not None and c.findtext('url')=='commandlinetools-linux-11076708_latest.zip':
  assert hashlib.sha1((p/'android-tools.zip').read_bytes()).hexdigest()==c.findtext('checksum');found=True
assert found
zipfile.ZipFile(p/'android-tools.zip').extractall(p/'sdk-temp')
PY
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  mv /workspace/tools/sdk-temp/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
  chmod +x "$ANDROID_HOME/cmdline-tools/latest/bin/"*
fi
java_proxy=$(python3 - <<'PY'
import os,urllib.parse
u=urllib.parse.urlsplit(os.environ.get('HTTPS_PROXY',''))
if u.hostname: print(f'-Dhttps.proxyHost={u.hostname} -Dhttps.proxyPort={u.port or 80} -Dhttp.proxyHost={u.hostname} -Dhttp.proxyPort={u.port or 80}')
PY
)
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djavax.net.ssl.trustStore=/workspace/tools/cacerts -Djavax.net.ssl.trustStorePassword=changeit $java_proxy"
# sdkmanager closes stdin once all licenses are accepted; ignore yes's expected SIGPIPE only.
set +o pipefail
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --licenses > /tmp/evening-sdk-licenses.log
sdk_status=${PIPESTATUS[1]}
set -o pipefail
[[ "$sdk_status" == 0 ]]
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
./gradlew --no-daemon assembleDebug lintDebug
(cd server && python3 -m unittest -v)
