#!/usr/bin/env python3
"""Measure actual production speech, including original-version PCM and same-version mode controls."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import wave

import numpy as np


def load(folder, name):
    with wave.open(str(folder / (name + '.wav')), 'rb') as wav:
        assert (wav.getnchannels(), wav.getframerate(), wav.getsampwidth()) == (1, 24000, 2)
        raw = wav.readframes(wav.getnframes())
    pcm = np.frombuffer(raw, dtype='<i2').astype(float) / 32768
    meta = json.loads((folder / (name + '.json')).read_text())
    rows = []
    for chunk in meta['chunks']:
        segment = pcm[chunk['start']:chunk['start'] + chunk['frames']]
        frame = 480
        energy = (segment[:len(segment) // frame * frame].reshape(-1, frame)**2).mean(axis=1)
        active = energy[energy > 10**(-48/10)]
        assert len(active) > 0
        rows.append({'startMs': round(chunk['start']/24,1), 'speechMs': round(chunk['frames']/24,1),
                     'activeRmsDbfs': round(float(10*np.log10(active.mean())),2)})
    return raw, {'voice': meta['voice'], 'text': meta['text'], 'ms': round(len(pcm)/24,1),
                 'clippedSamples': int((np.abs(pcm) >= 32767/32768).sum()), 'phrases': rows}


def riff(tag, data):
    return tag + struct.pack('<I',len(data)) + data + (b'\x00' if len(data)%2 else b'')


def write(destination, data, title):
    fmt = struct.pack('<HHIIHH',1,1,24000,48000,2,16)
    def label(key, value): return riff(key, value.encode('utf-8')+b'\x00')
    info = b'INFO'+label(b'IART','VOICEVOX:白上虎太郎')+label(b'INAM',title)
    destination.write_bytes(riff(b'RIFF',b'WAVE'+riff(b'fmt ',fmt)+riff(b'data',data)+riff(b'LIST',info)))
    with wave.open(str(destination),'rb') as f: assert f.readframes(f.getnframes()) == data


def metrics(rows):
    reaction,call,_,plea=rows['phrases']
    return {'addedSilenceAfterReactionMs':round(call['startMs']-reaction['startMs']-reaction['speechMs']),
            'callMs':call['speechMs'],'pleaMs':plea['speechMs'],
            'callVsPleaDb':round(call['activeRmsDbfs']-plea['activeRmsDbfs'],2)}


def measure_conversation(args):
    """Compare the distributed 1.11 voice to the same script through all three current playback modes."""
    modes = {'standard': args.standard, 'anime': args.anime, 'natural': args.natural}
    cases = {}
    retained = {'standard': 0, 'anime': 0}
    historical = ('audition', 'default-voice', 'previous-plea', 'previous-promise', 'unanalysed', 'narration')
    for name in (*historical, 'everyday', 'everyday-no-focus'):
        cases[name] = {}
        for mode, folder in modes.items():
            raw, row = load(folder, name)
            assert row['clippedSamples'] == 0
            cases[name][mode] = row
            reference = args.previous_standard if mode == 'standard' else args.previous_anime if mode == 'anime' else None
            if reference is not None and name in historical:
                assert raw == load(reference, name)[0], mode + ' rendering changed for ' + name
                retained[mode] += 1
        assert len({row['text'] for row in cases[name].values()}) == 1
        assert len({row['voice'] for row in cases[name].values()}) == 1

    with wave.open(str(args.published_anime), 'rb') as original:
        assert (original.getnchannels(), original.getframerate(), original.getsampwidth()) == (1, 24000, 2)
        old = original.readframes(original.getnframes())
    assert old == load(args.anime, 'audition')[0], 'comparison must use the previously released sound'
    narrator = load(args.standard, 'narration')[0]
    assert all(load(folder, 'narration')[0] == narrator for folder in modes.values())
    assert load(args.natural, 'unanalysed')[0] == load(args.standard, 'unanalysed')[0]
    everyday = load(args.natural, 'everyday')[0]
    assert len(cases['everyday']['natural']['phrases']) == 1
    assert everyday != load(args.natural, 'everyday-no-focus')[0], 'the intended single word must reach native audio'
    comparison = {mode: metrics(cases['audition'][mode]) for mode in ('anime', 'natural')}
    strong, natural = comparison['anime'], comparison['natural']
    assert 0 < natural['addedSilenceAfterReactionMs'] < strong['addedSilenceAfterReactionMs']
    assert natural['callMs'] > strong['callMs'] and natural['pleaMs'] < strong['pleaMs']
    assert 0 < natural['callVsPleaDb'] < strong['callVsPleaDb']

    args.out.mkdir(parents=True, exist_ok=True)
    sample = args.out / 'tsuzuki-1.12.0-natural.wav'
    joined = args.out / 'tsuzuki-1.12.0-comparison.wav'
    write(sample, everyday, 'つづき 1.12.0 自然な会話・おかえり')
    gap_ms = 700
    current = load(args.natural, 'audition')[0]
    write(joined, old + bytes(48 * gap_ms) + current, 'つづき 1.11.0 アニメ調 → 1.12.0 自然な会話')
    report = {'version': '1.12.0', 'referenceVersion': '1.11.0',
              'platform': 'Linux x86_64; VOICEVOX CORE 0.17.1', 'humanListeningStudy': False, 'androidDeviceTested': False,
              'liveGptTested': False, 'annotations': 'same manually specified script, voice, direction and readings',
              'priorPublishedAnimePcmIdentical': True, 'unchangedReferenceCases': retained,
              'everydaySingleNativeGroup': True, 'everydayFocusChangesPcm': True, 'oldFirstGapMs': gap_ms,
              'newStartSeconds': round(len(old) / 48000 + gap_ms / 1000, 3), 'contrast': comparison, 'cases': cases,
              'sha256': {f.name: hashlib.sha256(f.read_bytes()).hexdigest() for f in (sample, joined)}}
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: report[k] for k in ('contrast', 'newStartSeconds', 'unchangedReferenceCases')}, ensure_ascii=False))


def main():
    parser=argparse.ArgumentParser()
    for key in ['standard','anime','out','report']: parser.add_argument('--'+key,required=True,type=Path)
    parser.add_argument('--old',type=Path,help='1.10 standard reference, for the historical 1.11 comparison')
    parser.add_argument('--natural',type=Path,help='Render folder for the current conversational mode')
    parser.add_argument('--published-anime',type=Path,help='Published 1.11.0 anime audition WAV')
    parser.add_argument('--previous-anime',type=Path,help='Optional full render set from 1.11 in anime mode')
    parser.add_argument('--previous-standard',type=Path,help='Optional full render set from 1.11 in standard mode')
    args=parser.parse_args()
    if args.natural:
        if not args.published_anime: parser.error('--published-anime is required with --natural')
        return measure_conversation(args)
    if not args.old: parser.error('--old is required for the historical comparison')
    cases={}
    for name in ('audition','previous-plea','previous-promise','default-voice','unanalysed','narration'):
        old,before=load(args.old,name); standard,_=load(args.standard,name); anime,after=load(args.anime,name)
        assert old==standard, name+' changed in standard mode'
        assert (old==anime)==(name=='narration'), name
        assert before['text']==after['text'] and before['voice']==after['voice']
        assert before['clippedSamples']==after['clippedSamples']==0
        cases[name]={'old':before,'anime':after}
    scene=cases['audition']
    contrasts={name:metrics(scene[name]) for name in ('old','anime')}
    a,b=contrasts['old'],contrasts['anime']
    assert b['addedSilenceAfterReactionMs']-a['addedSilenceAfterReactionMs']>=300
    assert b['callMs']<.90*a['callMs'] and b['pleaMs']>1.15*a['pleaMs']
    assert b['callVsPleaDb']-a['callVsPleaDb']>3.0
    args.out.mkdir(parents=True,exist_ok=True)
    old,_=load(args.old,'audition'); new,_=load(args.anime,'audition')
    gapMs=850
    single=args.out/'tsuzuki-1.11.0-anime.wav'; comparison=args.out/'tsuzuki-1.11.0-comparison.wav'
    write(single,new,'つづき 1.11.0 アニメ調')
    write(comparison,old+bytes(48*gapMs)+new,'つづき 1.10.0 → 1.11.0 アニメ調')
    report={'version':'1.11.0','referenceVersion':'1.10.0','platform':'Linux x86_64; VOICEVOX CORE 0.17.1',
            'annotations':'fixed manual directions shared by both versions','humanListeningStudy':False,'liveGptTested':False,
            'oldStandardBitIdenticalCases':len(cases),'audioMix':'unaltered production PCM, 850ms between versions',
            'newStartSeconds':round((len(old)/48+gapMs)/1000,3),'contrast':contrasts,'cases':cases,
            'sha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [single,comparison]}}
    args.report.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({k:report[k] for k in ['oldStandardBitIdenticalCases','newStartSeconds','contrast']},ensure_ascii=False))


if __name__=='__main__': main()
