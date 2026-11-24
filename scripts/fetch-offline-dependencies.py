#!/usr/bin/env python3
"""Fetch the pinned Java dependencies of the official VOICEVOX Android API.

The first maintained release records SHA-256 pins in dependencies.json. Later
builds verify those pins; Maven checksums are verified on the initial fetch.
"""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import hashlib
import json
import urllib.error
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
BASE = 'https://repo.maven.apache.org/maven2/'
COORDINATES = [
    ('com.google.code.gson', 'gson', '2.10.1'),
    ('jakarta.validation', 'jakarta.validation-api', '3.0.2'),
    ('jakarta.annotation', 'jakarta.annotation-api', '2.1.1'),
    ('org.jetbrains.kotlin', 'kotlin-stdlib', '1.9.10'),
    ('org.jetbrains.kotlin', 'kotlin-stdlib-common', '1.9.10'),
    ('org.jetbrains.kotlin', 'kotlin-stdlib-jdk7', '1.9.10'),
    ('org.jetbrains.kotlin', 'kotlin-stdlib-jdk8', '1.9.10'),
    ('org.jetbrains', 'annotations', '13.0'),
]
OUT = ROOT / 'third_party/offline/libs'
NOTICES = ROOT / 'third_party/offline/licenses'
LOCK = ROOT / 'third_party/offline/dependencies.json'

def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'TsuzukiDependencyBuild/1.7.0'}), timeout=35) as response:
        return response.read()

def fetch(coordinate):
    group, artifact, version = coordinate
    name = f'{artifact}-{version}.jar'
    base = BASE + '/'.join((group.replace('.', '/'), artifact, version, f'{artifact}-{version}'))
    target = OUT / name
    data = target.read_bytes() if target.is_file() else get(base + '.jar')
    sha = hashlib.sha256(data).hexdigest()
    known = pins.get(name)
    if known:
        if sha != known['sha256']:
            raise RuntimeError('Pinned SHA-256 mismatch: ' + name)
    else:
        try:
            wanted = get(base + '.jar.sha256').decode('ascii').strip().split()[0]
            actual = sha
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
            wanted = get(base + '.jar.sha1').decode('ascii').strip().split()[0]
            actual = hashlib.sha1(data).hexdigest()
        if wanted.lower() != actual:
            raise RuntimeError('Maven checksum mismatch: ' + name)
    target.write_bytes(data)
    pom = get(base + '.pom')
    (NOTICES / (artifact + '-' + version + '.pom')).write_bytes(pom)
    with zipfile.ZipFile(target) as jar:
        if jar.testzip() is not None:
            raise RuntimeError('Invalid dependency JAR: ' + name)
        for entry in jar.namelist():
            tail = entry.rsplit('/', 1)[-1]
            if tail and any(word in tail.lower() for word in ('license', 'notice', 'copying')) and not entry.endswith('/'):
                (NOTICES / (artifact + '-' + version + '-' + tail)).write_bytes(jar.read(entry))
    return {'file': name, 'coordinate': ':'.join(coordinate), 'url': base + '.jar', 'sha256': sha, 'bytes': len(data)}

OUT.mkdir(parents=True, exist_ok=True)
NOTICES.mkdir(parents=True, exist_ok=True)
pins = {x['file']: x for x in json.loads(LOCK.read_text())['dependencies']} if LOCK.is_file() else {}
with ThreadPoolExecutor(max_workers=4) as pool:
    records = list(pool.map(fetch, COORDINATES))
LOCK.write_text(json.dumps({'source': 'Maven Central', 'dependencies': records}, ensure_ascii=False, indent=2) + '\n')
for record in records:
    print(record['file'], record['bytes'], record['sha256'])
