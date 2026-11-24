#!/usr/bin/env python3
"""Run the persisted multi-chapter analysis queue against synthetic boundaries.

Uses production SpeechAnalysis, SpeechStore, SpeechAnalyzer, ChapterContent, and
Responses parsing. SQLite/page loading, account tokens, HTTP, and Android's main
looper are fixtures. It does not test an Android service or call a provider.
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
    out = root / 'build/range-runtime-tests'
    classes = out / 'classes'
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir(parents=True)
    production = root / 'app/src/main/java/jp/haruyalab/novelshelf'
    names = ['Json', 'SpeechPlan', 'SpeechPerformance', 'SpeechPronunciation', 'WordProsody', 'StoryMemory', 'VoicePcm', 'SpeechAnalyzer', 'SpeechStore', 'CastRegistry',
             'DialogueMode', 'OfflineVoices', 'Urls', 'ChatGptOAuth', 'ChatGptResponses',
             'ChatGptOutputItems', 'SpeechAnalysisRange', 'SpeechAnalysis', 'ChapterContent']
    sources = [p for p in sorted((root / 'tests/speech-store-stubs').rglob('*.java'))
               if p.name != 'ReaderStore.java']
    sources += sorted((root / 'tests/speech-range-stubs').rglob('*.java'))
    sources += [root / 'tests/chatgpt-session-stubs/android/os' / (name + '.java')
                for name in ('Handler', 'Looper')]
    sources += [production / (name + '.java') for name in names]
    sources += [root / 'tests/SpeechAnalysisRuntimeTest.java']
    dependencies = os.pathsep.join([str(Path(args.json_jar).resolve()), str(Path(args.android_jar).resolve())])
    command = [args.java, 'com.sun.tools.javac.Main', '-source', '8', '-target', '8',
               '-encoding', 'UTF-8', '-cp', dependencies, '-d', str(classes)] + list(map(str, sources))
    compiled = subprocess.run(command, cwd=root, capture_output=True, text=True)
    (out / 'compile.log').write_text(compiled.stdout + compiled.stderr, encoding='utf-8')
    if compiled.returncode:
        print(compiled.stdout + compiled.stderr, file=sys.stderr)
        return compiled.returncode
    print('Compiled production range queue/store/analyzer on an isolated fixture classpath.')
    result = subprocess.run([args.java, '-cp', str(classes) + os.pathsep + dependencies,
                             'jp.haruyalab.novelshelf.SpeechAnalysisRuntimeTest'], cwd=root,
                            capture_output=True, text=True, timeout=60)
    output = result.stdout + result.stderr
    (out / 'SpeechAnalysisRuntimeTest.log').write_text(output, encoding='utf-8')
    print(output.rstrip())
    return result.returncode


if __name__ == '__main__':
    sys.exit(main())
