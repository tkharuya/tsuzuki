#!/usr/bin/env python3
"""Run the production ChatGPT session/store/OAuth with isolated test boundaries.

No Android lifecycle, production loopback socket, AndroidKeyStore, browser, provider
request, or paid inference is exercised. The ordinary JVM runner is not modified.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--android-jar', default=os.environ.get('NOVELSHELF_ANDROID_JAR'))
    parser.add_argument('--json-jar', default=os.environ.get('NOVELSHELF_JSON_TEST_JAR'))
    parser.add_argument('--java', default=os.environ.get('NOVELSHELF_JAVA', 'java'))
    args = parser.parse_args()
    for name in ('android_jar', 'json_jar'):
        value = getattr(args, name)
        if not value or not Path(value).is_file():
            parser.error('--' + name.replace('_', '-') + ' must point to an existing jar')
    root = Path(__file__).resolve().parent.parent
    out = root / 'build' / 'chatgpt-session-tests'
    classes = out / 'classes'
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir(parents=True)
    production = root / 'app/src/main/java/jp/haruyalab/novelshelf'
    names = ['Json', 'SpeechPlan', 'SpeechPerformance', 'SpeechPronunciation', 'WordProsody', 'StoryMemory', 'VoicePcm', 'SpeechStore', 'SpeechAnalysisRange', 'Urls', 'CastRegistry', 'DialogueMode', 'OfflineVoices',
             'ChatGptStore', 'ChatGptOAuth', 'ChatGptSession']
    base = root / 'tests/speech-store-stubs'
    sources = [base / name for name in ['android/content/SharedPreferences.java',
               'android/util/AtomicFile.java', 'jp/haruyalab/novelshelf/ReaderStore.java',
               'jp/haruyalab/novelshelf/SpeechStoreTestContext.java']]
    sources += sorted((root / 'tests/chatgpt-session-stubs').rglob('*.java'))
    sources += [production / (name + '.java') for name in names]
    sources += [root / 'tests/ChatGptSessionTest.java']
    dependencies = os.pathsep.join([str(Path(args.json_jar).resolve()), str(Path(args.android_jar).resolve())])
    command = [args.java, 'com.sun.tools.javac.Main', '-source', '8', '-target', '8',
               '-encoding', 'UTF-8', '-cp', dependencies, '-d', str(classes)] + list(map(str, sources))
    compiled = subprocess.run(command, cwd=root, capture_output=True, text=True)
    (out / 'compile.log').write_text(compiled.stdout + compiled.stderr, encoding='utf-8')
    if compiled.returncode:
        print(compiled.stdout + compiled.stderr, file=sys.stderr)
        return compiled.returncode
    print('Compiled production Session/Store/OAuth on an isolated fixture classpath.')
    result = subprocess.run([args.java, '-cp', str(classes) + os.pathsep + dependencies,
                             'jp.haruyalab.novelshelf.ChatGptSessionTest'], cwd=root,
                            capture_output=True, text=True, timeout=60)
    output = result.stdout + result.stderr
    (out / 'ChatGptSessionTest.log').write_text(output, encoding='utf-8')
    print(output.rstrip())
    return result.returncode


if __name__ == '__main__':
    sys.exit(main())
