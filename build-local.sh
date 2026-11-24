#!/usr/bin/env bash
# Build without Gradle. Pinned VOICEVOX libraries are included in third_party/offline.
# Requires Java 17+, Python 3, Android platform 36 and Android SDK Build Tools.
set -euo pipefail
umask 077

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$project_dir"
if [[ -f .local-build.env ]]; then
    # Optional machine-local SDK paths. Do not include this file in source archives.
    source .local-build.env
fi

sdk_dir="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
tools_dir="${NOVELSHELF_BUILD_TOOLS:-}"
platform_jar="${NOVELSHELF_ANDROID_JAR:-}"
if [[ -z "$tools_dir" && -n "$sdk_dir" ]]; then
    tools_dir="$(python3 - "$sdk_dir" <<'PY'
import os, re, sys
directory = os.path.join(sys.argv[1], 'build-tools')
if os.path.isdir(directory):
    versions = [x for x in os.listdir(directory) if os.path.isfile(os.path.join(directory, x, 'aapt2'))]
    versions.sort(key=lambda s: tuple(int(x) for x in re.findall(r'\d+', s)))
    if versions:
        print(os.path.join(directory, versions[-1]))
PY
)"
fi
if [[ -z "$platform_jar" && -n "$sdk_dir" ]]; then
    platform_jar="$sdk_dir/platforms/android-36/android.jar"
fi

if [[ ! -f "$platform_jar" || ! -x "$tools_dir/aapt2" || ! -f "$tools_dir/lib/d8.jar" || ! -f "$tools_dir/lib/apksigner.jar" ]]; then
    printf '%s\n' 'Set ANDROID_SDK_ROOT, or NOVELSHELF_BUILD_TOOLS and NOVELSHELF_ANDROID_JAR.' >&2
    exit 2
fi

java_bin="${NOVELSHELF_JAVA:-java}"
keytool_bin="${NOVELSHELF_KEYTOOL:-keytool}"
"$java_bin" com.sun.tools.javac.Main -version

build_dir="$project_dir/build/local"
mkdir -p "$build_dir" "$project_dir/build/outputs" "$project_dir/.signing"
chmod 700 "$project_dir/.signing"
rm -rf -- "$build_dir/classes" "$build_dir/generated" "$build_dir/dex"
mkdir -p "$build_dir/classes" "$build_dir/generated" "$build_dir/dex"

python3 - "$project_dir" "$build_dir" <<'PY'
import os, pathlib, sys, xml.etree.ElementTree as ET
root = pathlib.Path(sys.argv[1])
out = pathlib.Path(sys.argv[2])
for asset in ('index.html', 'app.js', 'app.css', 'extractor.js', 'voice-licenses.txt'):
    path = root / 'app/src/main/assets' / asset
    if not path.is_file() or not path.stat().st_size:
        raise SystemExit('Required application asset is missing: ' + asset)
props = {}
for line in (root / 'version.properties').read_text().splitlines():
    if '=' in line and not line.startswith('#'):
        key, value = line.split('=', 1)
        props[key.strip()] = value.strip()
android = 'http://schemas.android.com/apk/res/android'
ET.register_namespace('android', android)
tree = ET.parse(root / 'app/src/main/AndroidManifest.xml')
manifest = tree.getroot()
manifest.set('package', 'jp.haruyalab.novelshelf')
manifest.set('{%s}versionCode' % android, props['VERSION_CODE'])
manifest.set('{%s}versionName' % android, props['VERSION_NAME'])
uses = ET.Element('uses-sdk')
uses.set('{%s}minSdkVersion' % android, '26')
uses.set('{%s}targetSdkVersion' % android, '36')
manifest.insert(0, uses)
tree.write(out / 'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
(out / 'version.txt').write_text(props['VERSION_NAME'])
PY

"$tools_dir/aapt2" compile --dir "$project_dir/app/src/main/res" -o "$build_dir/resources.zip"
"$tools_dir/aapt2" link \
    --manifest "$build_dir/AndroidManifest.xml" \
    -I "$platform_jar" \
    --java "$build_dir/generated" \
    -A "$project_dir/app/src/main/assets" \
    -o "$build_dir/resources.apk" \
    "$build_dir/resources.zip"

python3 - "$project_dir" "$build_dir" <<'PY'
import pathlib, sys
root, out = map(pathlib.Path, sys.argv[1:])
sources = sorted((root / 'app/src/main/java').rglob('*.java'))
sources += sorted((out / 'generated').rglob('*.java'))
if not any(p.name == 'MainActivity.java' for p in sources):
    raise SystemExit('MainActivity.java is missing.')
def arg(path):
    return '"' + str(path).replace('\\', '\\\\').replace('"', '\\"') + '"'
(out / 'sources.args').write_text('\n'.join(map(arg, sources)), encoding='utf-8')
libraries = sorted((root / 'third_party/offline/libs').glob('*.jar'))
if not any(p.name == 'voicevox-core-0.17.1.jar' for p in libraries):
    raise SystemExit('Official VOICEVOX Java API dependency is missing.')
import hashlib, json
pins = json.loads((root / 'third_party/offline/dependencies.json').read_text())['dependencies']
for pin in pins:
    file = root / 'third_party/offline/libs' / pin['file']
    if not file.is_file() or hashlib.sha256(file.read_bytes()).hexdigest() != pin['sha256']:
        raise SystemExit('Dependency checksum mismatch: ' + pin['file'])
native_pins = json.loads((root / 'third_party/offline/native-dependencies.json').read_text())['nativeDependencies']
for pin in native_pins:
    file = root / pin['file']
    if not file.is_file() or file.stat().st_size != pin['bytes'] or hashlib.sha256(file.read_bytes()).hexdigest() != pin['sha256']:
        raise SystemExit('Native dependency checksum mismatch: ' + pin['file'])
(out / 'library-classpath.txt').write_text(':'.join(map(str, libraries)), encoding='utf-8')
PY

"$java_bin" com.sun.tools.javac.Main \
    -encoding UTF-8 -source 8 -target 8 \
    -bootclasspath "$platform_jar:$tools_dir/core-lambda-stubs.jar" \
    -classpath "$(cat "$build_dir/library-classpath.txt")" \
    -d "$build_dir/classes" \
    "@$build_dir/sources.args"

python3 - "$build_dir" <<'PY'
import pathlib, sys, zipfile
out = pathlib.Path(sys.argv[1])
classes = out / 'classes'
with zipfile.ZipFile(out / 'classes.jar', 'w', zipfile.ZIP_DEFLATED) as jar:
    for path in sorted(classes.rglob('*.class')):
        jar.write(path, path.relative_to(classes).as_posix())
PY

library_files=("$project_dir"/third_party/offline/libs/*.jar)
"$java_bin" -cp "$tools_dir/lib/d8.jar" com.android.tools.r8.D8 \
    --release --min-api 26 --lib "$platform_jar" \
    --output "$build_dir/dex" "$build_dir/classes.jar" "${library_files[@]}"

python3 - "$build_dir" "$project_dir" <<'PY'
import pathlib, shutil, sys, zipfile
out = pathlib.Path(sys.argv[1])
root = pathlib.Path(sys.argv[2])
shutil.copyfile(out / 'resources.apk', out / 'unsigned.apk')
with zipfile.ZipFile(out / 'unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as apk:
    for path in sorted((out / 'dex').glob('*.dex')):
        apk.write(path, path.name)
    native = root / 'app/src/main/jniLibs'
    for abi in ('arm64-v8a', 'x86_64'):
        for name in ('libvoicevox_core_java_api.so', 'libvoicevox_onnxruntime.so', 'libc++_shared.so'):
            if not (native / abi / name).is_file():
                raise SystemExit('Native library missing: ' + abi + '/' + name)
    for path in sorted(native.rglob('*.so')):
        apk.write(path, 'lib/' + path.relative_to(native).as_posix())
PY
"$tools_dir/zipalign" -f -p 4 "$build_dir/unsigned.apk" "$build_dir/aligned.apk"

signing_dir="$project_dir/.signing"
if [[ ! -f "$signing_dir/novelshelf-release.p12" ]]; then
    if [[ ! -f "$signing_dir/password.txt" ]]; then
        python3 - "$signing_dir/password.txt" <<'PY'
import pathlib, secrets, sys
p = pathlib.Path(sys.argv[1])
p.write_text(secrets.token_urlsafe(32) + '\n', encoding='utf-8')
p.chmod(0o600)
PY
    fi
    "$keytool_bin" -genkeypair \
        -alias novelshelf -keyalg RSA -keysize 3072 -validity 9125 \
        -dname 'CN=Tsuzuki Personal, O=Personal Apps, C=JP' \
        -storetype PKCS12 -keystore "$signing_dir/novelshelf-release.p12" \
        -storepass:file "$signing_dir/password.txt" \
        -keypass:file "$signing_dir/password.txt"
fi
if [[ ! -f "$signing_dir/password.txt" ]]; then
    printf '%s\n' 'Signing password.txt is missing. Restore the original signing backup; do not generate a new key.' >&2
    exit 3
fi

version_name="$(cat "$build_dir/version.txt")"
output_apk="$project_dir/build/outputs/tsuzuki-$version_name-personal.apk"
"$java_bin" -jar "$tools_dir/lib/apksigner.jar" sign \
    --ks "$signing_dir/novelshelf-release.p12" --ks-type PKCS12 \
    --ks-key-alias novelshelf --ks-pass "file:$signing_dir/password.txt" \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$output_apk" "$build_dir/aligned.apk"
"$java_bin" -jar "$tools_dir/lib/apksigner.jar" verify --verbose "$output_apk"
"$tools_dir/zipalign" -c -p 4 "$output_apk"
python3 - "$output_apk" <<'PY'
import hashlib, pathlib, sys
p = pathlib.Path(sys.argv[1])
digest = hashlib.sha256(p.read_bytes()).hexdigest()
p.with_suffix('.apk.sha256').write_text(digest + '  ' + p.name + '\n')
print('APK: ' + str(p))
print('SHA256: ' + digest)
PY
