#!/usr/bin/env python3
"""Exercise production streaming/cache/player logic with controllable native and audio boundaries."""
from pathlib import Path
import argparse,os,subprocess

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--android-jar',required=True);p.add_argument('--json-jar',required=True);p.add_argument('--java',default='java')
a=p.parse_args();root=Path(__file__).resolve().parent.parent;out=root/'build/audio-stream-tests';classes=out/'classes';classes.mkdir(parents=True,exist_ok=True)
production=root/'app/src/main/java/jp/haruyalab/novelshelf'
names=['Json','SpeechAudio','VoicePcm','SpeechPcmSource','PcmPlayback','SpeechPerformance','SpeechPronunciation', 'WordProsody','SpeechReadingInput','SpeechPlan','CastRegistry','DialogueActing', 'DialogueMode', 'OfflineVoices']
sources=[production/f'{n}.java' for n in names]+list((root/'tests/audio-stream-stubs').rglob('*.java'))+[root/'tests/SpeechStreamingTest.java']
sources += [root/'tests/offline-pack-stubs'/name for name in ['android/content/Context.java','android/content/Intent.java','android/content/pm/ApplicationInfo.java','android/os/Handler.java','android/os/Looper.java']]
cp=os.pathsep.join([a.json_jar,a.android_jar])
r=subprocess.run([a.java,'com.sun.tools.javac.Main','-encoding','UTF-8','-cp',cp,'-d',str(classes)]+list(map(str,sources)),capture_output=True,text=True)
(out/'compile.log').write_text(r.stdout+r.stderr)
if r.returncode:print(r.stdout+r.stderr);raise SystemExit(r.returncode)
r=subprocess.run([a.java,'-cp',str(classes)+os.pathsep+cp,'jp.haruyalab.novelshelf.SpeechStreamingTest'],capture_output=True,text=True,timeout=45)
(root/'tests/results/audio-stream-runtime.log').write_text(r.stdout+r.stderr)
print(r.stdout+r.stderr);raise SystemExit(r.returncode)
