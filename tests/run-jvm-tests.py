#!/usr/bin/env python3
"""Run production JVM logic against synthetic fixtures, without API calls.

Requires Java 17+, Android SDK platform 36 android.jar, and a JVM org.json jar.
Android boundary doubles are test-only and never enter the application build.
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
    out = root / 'build' / 'jvm-tests'
    classes = out / 'classes'
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir(parents=True)
    production = root / 'app/src/main/java/jp/haruyalab/novelshelf'
    production_names = ['Json', 'SpeechPlan', 'SpeechPerformance', 'SpeechPronunciation', 'WordProsody', 'SpeechReadingInput', 'StoryMemory', 'SpeechAnalyzer', 'SpeechStore', 'SpeechAnalysisRange', 'CastRegistry',
                        'DialogueActing', 'DialogueMode', 'OfflineVoices', 'VoicePcm', 'SpeechPcmSource', 'PcmPlayback', 'SpeechAudio', 'RealtimeSocket', 'Urls',
                        'ChatGptOAuth', 'ChatGptResponses', 'ChatGptOutputItems', 'ChatGptStore', 'ChatGptLoopback']
    tests = ['SpeechPlanTest', 'SpeechPerformanceTest', 'CastRegistryTest', 'SpeechAnalyzerTest', 'SpeechDetailedAnalysisTest',
             'SpeechChapterAnalysisTest', 'SpeechMemoryIdentityTest', 'SpeechActivityTest', 'SpeechPronunciationTest', 'SpeechPronunciationAnalysisTest',
             'SpeechPronunciationStoreTest', 'StoryMemoryTest', 'StoryMemoryStoreTest', 'SpeechStoreTest',
             'native/AudioProtocolTest', 'OfflineVoicesTest', 'UrlsTest',
             'ChatGptOAuthTest', 'ChatGptResponsesTest', 'ChatGptOutputItemsTest', 'ChatGptTransportFailureTest',
             'ChatGptResponsePipelineTest', 'SpeechAnalysisRangeTest', 'SpeechAnalysisRangeStoreTest',
             'ChatGptStoreTest', 'ChatGptLoopbackTest']
    sources = sorted((root / 'tests/speech-store-stubs').rglob('*.java'))
    sources += [production / (name + '.java') for name in production_names]
    sources += [root / 'tests' / (name + '.java') for name in tests]
    dependencies = os.pathsep.join([str(Path(args.json_jar).resolve()), str(Path(args.android_jar).resolve())])
    compile_command = [args.java, 'com.sun.tools.javac.Main', '-source', '8', '-target', '8',
                       '-encoding', 'UTF-8', '-cp', dependencies, '-d', str(classes)] + list(map(str, sources))
    compile_result = subprocess.run(compile_command, cwd=root, capture_output=True, text=True)
    (out / 'compile.log').write_text(compile_result.stdout + compile_result.stderr, encoding='utf-8')
    if compile_result.returncode:
        print(compile_result.stdout + compile_result.stderr, file=sys.stderr)
        return compile_result.returncode
    print('JVM production logic compiled; Android doubles are restricted to tests.')
    classpath = str(classes) + os.pathsep + dependencies
    for test in tests:
        name = test.rsplit('/', 1)[-1]
        result = subprocess.run([args.java, '-cp', classpath, 'jp.haruyalab.novelshelf.' + name],
                                cwd=root, capture_output=True, text=True)
        output = result.stdout + result.stderr
        (out / (name + '.log')).write_text(output, encoding='utf-8')
        print(output.rstrip())
        if result.returncode:
            return result.returncode
    print('All JVM suites passed. Real APIs, Android service lifecycle, Keystore and playback were not exercised.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
