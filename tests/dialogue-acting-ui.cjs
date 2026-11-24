'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {JSDOM}=require('jsdom');
const {initializeNativeMock}=require('./ui-native-mock.cjs');
const assets=path.join(__dirname,'../app/src/main/assets');
const dom=new JSDOM(fs.readFileSync(path.join(assets,'index.html'),'utf8'),{runScripts:'outside-only',pretendToBeVisual:true,url:'https://reader.test/assets/index.html'});
const win=dom.window,doc=win.document;
const get=s=>doc.querySelector(s),requests=name=>win.__nativeCalls.filter(x=>x.action===name);
const tick=()=>new Promise(r=>setTimeout(r,10));
async function until(fn){for(let i=0;i<180;i++){if(fn())return;await tick();}throw Error('UI did not reach expected state');}
async function run(){
  win.eval('('+initializeNativeMock.toString()+')({offlinePacks:{status:"not_installed",ready:false}})');win.eval(fs.readFileSync(path.join(assets,'app.js'),'utf8'));
  await until(()=>get('.book-card'));
  get('[data-action="nav"][data-view="settings"]').click();
  get('[data-action="speech-settings"]').click();await until(()=>get('[name="dialogueMode"]'));
  assert.equal(get('[name="dialogueMode"]').value,'natural');
  const audition=m=>get('[data-action="speech-acting-preview"][data-mode="'+m+'"]');
  assert.ok(['natural','anime','standard'].every(m=>audition(m).disabled));
  assert.equal(get('[name="auditionScene"]').value,'everyday');
  win.__offlinePacksEvent({ready:true,status:'ready'});await until(()=>!audition('anime').disabled);
  get('[name="offlineDefaultVoice"]').value='kotaro';
  for(const scene of ['everyday','plea']) for(const mode of ['standard','natural','anime']){
    get('[name="auditionScene"]').value=scene;
    const before=requests('speechControl').length;audition(mode).click();await until(()=>requests('speechControl').length===before+1);
    const b=requests('speechControl').at(-1).payload;assert.equal(b.command,'preview');assert.equal(b.offlineVoice,'kotaro');
    assert.equal(b.sceneAudition,true);assert.equal(b.dialogueMode,mode);assert.equal(b.auditionScene,scene);
    await until(()=>get('[data-voice-preview-status]').textContent.includes(({anime:'アニメ調',standard:'標準朗読',natural:'自然な会話'})[mode]));
  }
  assert.equal(requests('speechSettings').length,0);assert.equal(requests('speechAnalyze').length,0);
  get('[name="dialogueMode"]').value='standard';get('[data-form="speech-settings"]').requestSubmit();
  await until(()=>requests('speechSettings').length===1);
  assert.equal(requests('speechSettings')[0].payload.settings.dialogueMode,'standard');
  await until(()=>get('[name="dialogueMode"]').value==='standard');
  console.log('Dialogue acting UI: PASS (actual DOM and app event handlers, two scripts in three modes on the same voice, draft preservation, native payload, playback label and saved mode; native bridge simulated)');
}
run().catch(e=>{console.error(e);process.exitCode=1;}).finally(()=>dom.window.close());
