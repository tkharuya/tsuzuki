#!/usr/bin/env python3
"""Run real Linux VOICEVOX JNI smoke tests plus the production installer/wrapper.

Requires separately downloaded official desktop artifacts/model fixtures; this
script does not fetch models or make API calls. See third_party/offline/README.md.
Android/HTTP doubles are confined to tests/offline-pack-stubs and never enter APKs.
"""
import argparse
from pathlib import Path
import subprocess
import os


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixtures', required=True, help='Directory with desktop JAR, linux-x64 runtime, dictionary and models')
    parser.add_argument('--json-jar', required=True, help='JVM org.json JAR')
    parser.add_argument('--java', default=os.environ.get('NOVELSHELF_JAVA', 'java'))
    parser.add_argument('--suite', choices=('all', 'native', 'production', 'auditions'), default='all',
                        help='Run all suites, or rerun only changed production rendering after an earlier native smoke pass')
    parser.add_argument('--installed', help='Installed real voice fixture (private/ and native/) for the audition suite')
    options = parser.parse_args()
    if options.suite == 'auditions' and not options.installed:
        parser.error('--installed is required to render auditions')
    root = Path(__file__).resolve().parent.parent
    fixtures = Path(options.fixtures).resolve()
    needed = [fixtures / 'voicevoxcore-0.17.1.jar', fixtures / 'dictionary.tar.gz',
              fixtures / 'linux-x64/libvoicevox_onnxruntime.so.1.23.2', Path(options.json_jar)]
    needed += [fixtures / 'models' / f'{n}.vvm' for n in (0, 1, 2, 4, 5, 9, 10, 11, 15)]
    for file in needed:
        if not file.is_file():
            parser.error(f'Required fixture is missing: {file}')
    classes = root / 'build/offline-runtime-tests/classes'
    classes.mkdir(parents=True, exist_ok=True)
    logs = root / 'tests/results'
    logs.mkdir(parents=True, exist_ok=True)
    jars = [fixtures / 'voicevoxcore-0.17.1.jar', Path(options.json_jar).resolve()]
    jars += [p for p in sorted((root / 'third_party/offline/libs').glob('*.jar')) if p.name != 'voicevox-core-0.17.1.jar']
    dependencies = os.pathsep.join(map(str, jars))
    production = root / 'app/src/main/java/jp/haruyalab/novelshelf'
    sources = list((root / 'tests/offline-pack-stubs').rglob('*.java'))
    sources += [production / f'{name}.java' for name in ('Json', 'OfflineVoicePacks', 'VoicevoxEngine', 'WordContour',
                                                       'DialogueActing', 'DialogueSample', 'DialogueMode', 'OfflineVoices', 'VoicePcm', 'SpeechPerformance', 'SpeechPronunciation', 'WordProsody', 'SpeechReadingInput', 'SpeechPlan', 'CastRegistry')]
    sources += [root / 'tests/VoicevoxNativeSmoke.java', root / 'tests/OfflineVoicePacksTest.java',
                root / 'tests/VoicevoxPerformanceTest.java', root / 'tests/VoicevoxStreamingTest.java', root / 'tests/VoicevoxPronunciationTest.java',
                root / 'tests/WordProsodySample.java', root / 'tests/VoicevoxWordProsodyTest.java', root / 'tests/DialogueActingTest.java', root / 'tests/DialogueAuditionRender.java']
    compile_command = [options.java, 'com.sun.tools.javac.Main', '-encoding', 'UTF-8',
                       '-cp', dependencies, '-d', str(classes)] + list(map(str, sources))
    compiled = subprocess.run(compile_command, cwd=root, text=True, capture_output=True)
    (logs / 'offline-runtime-compile.log').write_text(compiled.stdout + compiled.stderr)
    if compiled.returncode:
        print(compiled.stdout + compiled.stderr)
        return compiled.returncode
    suites = [
        ('VoicevoxNativeSmoke', 'voicevox-native-smoke.log',
         [str(fixtures), str(root / 'build/offline-runtime-tests/native-samples')]),
        ('jp.haruyalab.novelshelf.OfflineVoicePacksTest', 'offline-packs-runtime.log',
         [str(fixtures), str(root / 'build/offline-runtime-tests/samples')]),
    ]
    if options.suite == 'native': suites = suites[:1]
    elif options.suite == 'production': suites = suites[1:]
    elif options.suite == 'auditions':
        suites = [('jp.haruyalab.novelshelf.DialogueAuditionRender', mode + '-render.log',
                   [str(Path(options.installed).resolve()), str(classes.parent / ('samples-' + mode)), mode])
                  for mode in ('natural', 'anime', 'standard')]
    temp_dir = classes.parent / ('jni-temp-' + options.suite)
    temp_dir.mkdir(exist_ok=True)
    for name, log, args in suites:
        with (logs / log).open('w') as output:
            result = subprocess.run([options.java, '-Djava.io.tmpdir=' + str(temp_dir), '-cp', str(classes) + os.pathsep + dependencies, name] + args,
                                    cwd=root, stdout=output, stderr=subprocess.STDOUT)
        print((logs / log).read_text())
        if result.returncode:
            return result.returncode
    print(f'Selected real-JNI suites passed on Linux ({options.suite}); Android device/service lifecycle was not exercised.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
