// Local reader-display regression checks. Uses original fixture text and a mock
// Native bridge; this suite never contacts a novel site or an AI/voice service.
const fs=require('fs'),path=require('path'),assert=require('assert').strict;
const {initializeNativeMock}=require('./ui-native-mock.cjs');
let playwright;try{playwright=require('playwright');}catch(_){playwright=require(path.join(process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES||'','playwright'));}
const root=path.resolve(__dirname,'..'),assets=path.join(root,'app/src/main/assets'),output=path.join(root,'build/qa'),origin='https://appassets.androidplatform.net';
fs.mkdirSync(output,{recursive:true});
const results=[];let browser;
function check(name,ok,detail=''){assert.ok(ok,name+(detail?': '+detail:''));results.push({name,ok:true});}
async function setup(width=360){
 const context=await browser.newContext({viewport:{width,height:840},deviceScaleFactor:1,isMobile:true,hasTouch:true,reducedMotion:'reduce'}),errors=[],requests=[];
 await context.addInitScript(initializeNativeMock,{rangeChapters:7});
 await context.route('**/*',async route=>{
  const u=new URL(route.request().url());requests.push(u.href);
  if(u.origin!==origin||!/^\/assets\/[a-zA-Z0-9._-]+$/.test(u.pathname))return route.abort();
  const f=path.join(assets,path.basename(u.pathname));if(!fs.existsSync(f))return route.fulfill({status:404,body:'Not found'});
  return route.fulfill({status:200,body:fs.readFileSync(f),contentType:/.html$/.test(f)?'text/html; charset=utf-8':/.css$/.test(f)?'text/css; charset=utf-8':'text/javascript; charset=utf-8'});
 });
 const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));page.on('console',m=>{if(m.type()==='error')errors.push(m.text());});
 await page.goto(origin+'/assets/index.html');await page.locator('.book-card').first().waitFor();return{page,context,errors,requests};
}
async function openBook(page){await page.locator('.book-open').first().click();await page.locator('.novel-paragraph').first().waitFor();await page.waitForTimeout(300);}
async function fullscreen(page,value){await page.waitForFunction(value=>!!document.querySelector('.reader.is-fullscreen')===value,value);}
async function closeSheet(page){await page.locator('.sheet [data-action="close-sheet"]').click();}
async function wheel(page,amount){await page.locator('#reader-scroll').hover({position:{x:180,y:250}});await page.mouse.wheel(0,amount);await page.waitForTimeout(120);}
async function manualFullscreen(page){await page.locator('[data-action="reader-fullscreen"]').click();await fullscreen(page,true);await page.waitForTimeout(80);}
async function visualPosition(page){return page.evaluate(()=>{const s=document.querySelector('#reader-scroll'),line=s.getBoundingClientRect().top+16,ps=[...s.querySelectorAll('.novel-paragraph')],p=ps.filter(p=>p.getBoundingClientRect().top<=line).at(-1)||ps[0];return{chapter:p.closest('[data-chapter-url]').dataset.chapterUrl,paragraph:p.dataset.pIndex,offset:line-p.getBoundingClientRect().top};});}
function samePosition(a,b){return a.chapter===b.chapter&&a.paragraph===b.paragraph&&Math.abs(a.offset-b.offset)<2.1;}
async function pref(page,key,value){const row=page.locator('.sheet [data-key="'+key+'"]');if((await row.getAttribute('aria-checked'))!==String(value))await row.click();await page.waitForFunction(({key,value})=>window.__mockState.settings[key]===value,{key,value});}
async function touchSwipe(context,page,fromY,toY){
 const cdp=await context.newCDPSession(page);
 await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:180,y:fromY}]});
 for(let i=1;i<=6;i++){await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:180,y:fromY+(toY-fromY)*i/6}]});await page.waitForTimeout(35);}
 await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await page.waitForTimeout(120);await cdp.detach();
}
async function displayCalls(page){return page.evaluate(()=>window.__nativeCalls.filter(c=>c.action==='readerDisplay').length);}
async function run(){
 browser=await playwright.chromium.launch({headless:true,executablePath:process.env.NOVELSHELF_CHROME,args:['--no-sandbox','--disable-dev-shm-usage']});
 const {page,context,errors,requests}=await setup();
 check('Reader defaults hide the mini player and enable scroll-down fullscreen',await page.evaluate(()=>window.__mockState.settings.showSpeechPlayer===false&&window.__mockState.settings.autoFullscreen===true));
 check('Shelf explicitly requests normal native bars',await page.evaluate(()=>window.__readerDisplay.reading===false&&window.__readerDisplay.fullscreen===false));
 await openBook(page);
 check('Opening a book starts with visible controls and normal native bars',await page.locator('.reader-top').isVisible()&&await page.locator('.reader-bottom').isVisible()&&await page.evaluate(()=>window.__readerDisplay.reading&&!window.__readerDisplay.fullscreen));
 await page.evaluate(()=>document.querySelector('#reader-scroll').scrollTop=1400);await page.waitForTimeout(320);
 check('Restoring or programmatically scrolling text does not enter fullscreen',!await page.locator('.reader').evaluate(e=>e.classList.contains('is-fullscreen')));
 const before=await visualPosition(page);await manualFullscreen(page);const after=await visualPosition(page);
 check('Manual fullscreen preserves the paragraph and within-paragraph offset',samePosition(before,after),JSON.stringify({before,after}));
 check('Fullscreen hides both reader bars and the mini player',!await page.locator('.reader-top').isVisible()&&!await page.locator('.reader-bottom').isVisible()&&!await page.locator('#speech-player-host').isVisible());
 check('Fullscreen sends only the requested reading/display booleans',await page.evaluate(()=>window.__readerDisplay.reading&&window.__readerDisplay.fullscreen&&Object.keys(window.__readerDisplay).sort().join(',')==='fullscreen,reading'));
 await page.waitForTimeout(130);await page.setViewportSize({width:360,height:920});await page.waitForTimeout(120);
 check('Delayed native-inset viewport expansion preserves the reading anchor',samePosition(after,await visualPosition(page)));
 await page.setViewportSize({width:360,height:840});await page.waitForTimeout(120);
 check('Viewport contraction also preserves the reading anchor',samePosition(after,await visualPosition(page)));
 await page.screenshot({path:path.join(output,'reader-fullscreen-mobile-360.png'),fullPage:true});
 await page.mouse.click(170,260);await fullscreen(page,false);await page.waitForTimeout(80);
 check('A short noninteractive text tap reveals controls without moving the anchor',samePosition(after,await visualPosition(page))&&await page.locator('.reader-top').isVisible());
 await page.waitForTimeout(280);await wheel(page,130);await fullscreen(page,true);
 check('A real downward wheel gesture enters fullscreen',await page.evaluate(()=>window.__readerDisplay.fullscreen));
 await wheel(page,-70);await fullscreen(page,false);
 check('An immediate upward gesture restores the controls',await page.locator('.reader-bottom').isVisible()&&await page.evaluate(()=>!window.__readerDisplay.fullscreen));
 await page.waitForTimeout(280);await wheel(page,6);await wheel(page,6);
 check('Small scroll movements do not flicker the reader bars',!await page.locator('.reader').evaluate(e=>e.classList.contains('is-fullscreen')));
 await wheel(page,90);await fullscreen(page,true);
 await page.evaluate(()=>{const s=document.querySelector('#reader-scroll'),p=[...s.querySelectorAll('.novel-paragraph')].find(p=>p.getBoundingClientRect().top>50),range=document.createRange();range.selectNodeContents(p);getSelection().removeAllRanges();getSelection().addRange(range);});
 await wheel(page,-80);
 check('Scrolling selected text does not switch display mode',await page.locator('.reader').evaluate(e=>e.classList.contains('is-fullscreen'))&&await page.evaluate(()=>!getSelection().isCollapsed));
 await page.mouse.click(175,280);await page.waitForTimeout(100);
 check('The tap that dismisses a selection does not reveal reader controls',await page.locator('.reader').evaluate(e=>e.classList.contains('is-fullscreen')));
 await page.evaluate(()=>getSelection().removeAllRanges());
 await page.keyboard.press('Escape');await fullscreen(page,false);
 check('Escape provides an independent fullscreen exit',await page.locator('.reader-top').isVisible());
 await page.locator('[data-action="reader-settings"]').click();await pref(page,'autoFullscreen',false);await pref(page,'showSpeechPlayer',true);
 check('Reader display preferences are labelled switches in the text/display sheet',await page.locator('.sheet [data-key="autoFullscreen"]').getAttribute('role')==='switch'&&await page.locator('.sheet [data-key="showSpeechPlayer"]').getAttribute('aria-checked')==='true');
 await page.screenshot({path:path.join(output,'reader-display-settings-mobile-360.png'),fullPage:true});
 await closeSheet(page);await page.waitForTimeout(280);await wheel(page,180);
 check('Disabling automatic fullscreen keeps controls visible while reading downward',!await page.locator('.reader').evaluate(e=>e.classList.contains('is-fullscreen')));
 await manualFullscreen(page);await wheel(page,-75);await fullscreen(page,false);
 check('Manual fullscreen still exits on upward scrolling when automatic entry is off',await page.locator('.reader-bottom').isVisible());
 await manualFullscreen(page);await page.keyboard.press('Tab');
 check('Keyboard focus reveals an accessible fullscreen exit',await page.evaluate(()=>{const b=document.activeElement,r=b.getBoundingClientRect();return b.dataset.action==='reader-show-controls'&&r.width>100&&r.height>=44;}));
 await page.keyboard.press('Enter');await fullscreen(page,false);
 check('The focused fullscreen-exit button restores controls',await page.locator('.reader-top').isVisible());
 await manualFullscreen(page);await page.evaluate(()=>document.querySelector('[data-action="reader-settings"]').click());await page.locator('.sheet').waitFor();
 check('Opening a settings sheet restores both app chrome and native bar state',await page.locator('.reader-top').isVisible()&&await page.evaluate(()=>!window.__readerDisplay.fullscreen));
 await page.evaluate(()=>window.handleBack());await page.locator('.sheet').waitFor({state:'detached'});
 check('Closing a modal with Back keeps the reader open with controls available',await page.locator('#reader-scroll').isVisible()&&!await page.locator('.reader').evaluate(e=>e.classList.contains('is-fullscreen')));
 await manualFullscreen(page);const consumed=await page.evaluate(()=>window.handleBack());await fullscreen(page,false);
 check('The first native Back exits fullscreen without leaving the book',consumed&&await page.locator('.novel-paragraph').count()>0);
 await page.evaluate(()=>window.handleBack());await page.locator('.book-card').first().waitFor();
 check('Leaving the reader resets native fullscreen and reading state',await page.evaluate(()=>!window.__readerDisplay.reading&&!window.__readerDisplay.fullscreen));
 await page.locator('[data-action="nav"][data-view="settings"]').click();
 check('Main settings expose the same persisted display preferences',await page.locator('[data-key="showSpeechPlayer"]').getAttribute('aria-checked')==='true'&&await page.locator('[data-key="autoFullscreen"]').getAttribute('aria-checked')==='false');
 await page.reload();await page.locator('.book-card').first().waitFor();await openBook(page);
 check('Preferences survive a restart but fullscreen itself never persists',await page.evaluate(()=>window.__mockState.settings.showSpeechPlayer&&!window.__mockState.settings.autoFullscreen&&!window.__readerDisplay.fullscreen)&&await page.locator('.reader-top').isVisible());
 await page.locator('[data-action="reader-settings"]').click();await pref(page,'showSpeechPlayer',false);await pref(page,'autoFullscreen',true);await closeSheet(page);
 await page.locator('[data-action="speech-open"]').click();await page.locator('[data-speech-panel]').waitFor();await page.locator('.sheet [data-action="speech-start"][data-from="resume"]').click();
 await page.waitForFunction(()=>window.__mockState.speech.playback.status==='playing');
 check('Playback can start while the mini controller stays hidden',!await page.locator('#speech-player-host').isVisible()&&await page.locator('.sheet [data-command="pause"]').count()===1);
 await page.locator('.sheet [data-command="pause"]').click();await page.waitForFunction(()=>window.__mockState.speech.playback.status==='paused');
 check('The listen sheet still updates playback state with a hidden mini controller',await page.locator('.sheet [data-command="resume"]').count()===1);
 await page.locator('.sheet [data-command="resume"]').click();await page.waitForFunction(()=>window.__mockState.speech.playback.status==='playing');
 await pref(page,'showSpeechPlayer',true);await closeSheet(page);await page.locator('#speech-player-host').waitFor({state:'visible'});
 const audioBefore=await page.evaluate(()=>({commands:window.__nativeCalls.filter(c=>c.action==='speechControl').length,cast:JSON.stringify(window.__mockState.speech.cast),position:JSON.stringify(window.__mockState.speech.positions),playback:JSON.stringify(window.__mockState.speech.playback)}));
 const positionBeforeHide=await visualPosition(page);await page.locator('[data-action="reader-hide-player"]').click();await page.waitForFunction(()=>window.__mockState.settings.showSpeechPlayer===false);await page.waitForTimeout(80);
 check('The mini close action only hides it and leaves audio/cast/resume state untouched',await page.evaluate(before=>before.commands===window.__nativeCalls.filter(c=>c.action==='speechControl').length&&before.cast===JSON.stringify(window.__mockState.speech.cast)&&before.position===JSON.stringify(window.__mockState.speech.positions)&&before.playback===JSON.stringify(window.__mockState.speech.playback),audioBefore)&&!await page.locator('#speech-player-host').isVisible());
 check('Hiding the player preserves the visible paragraph',samePosition(positionBeforeHide,await visualPosition(page)));
 await page.locator('[data-action="speech-open"]').click();await pref(page,'showSpeechPlayer',true);await closeSheet(page);
 check('The listen sheet can restore the mini controller without restarting audio',await page.locator('#speech-player-host').isVisible()&&await page.evaluate(before=>before.commands===window.__nativeCalls.filter(c=>c.action==='speechControl').length,audioBefore));
 await page.waitForFunction(()=>document.querySelector('#toast-host').textContent==='');
 await page.screenshot({path:path.join(output,'reader-controller-mobile-360.png'),fullPage:true});
 await manualFullscreen(page);
 check('Fullscreen temporarily hides an enabled mini controller without changing its preference',!await page.locator('#speech-player-host').isVisible()&&await page.evaluate(()=>window.__mockState.settings.showSpeechPlayer&&window.__mockState.speech.playback.status==='playing'));
 await page.evaluate(()=>window.handleBack());await fullscreen(page,false);await page.locator('#speech-player-host').waitFor({state:'visible'});
 check('Leaving fullscreen restores the enabled controller while playback continues',await page.evaluate(()=>window.__mockState.speech.playback.status==='playing'));
 const savedReading=await page.evaluate(()=>JSON.stringify(window.__mockState.books.kakuyomu_fixture.position));
 await page.evaluate(()=>{window.__readerOriginalNow=Date.now;Date.now=()=>window.__readerOriginalNow()+10000;const p=window.__mockState.speech.playback;Object.assign(p,{paragraph:20,unitIndex:20,status:'playing'});window.onNativeEvent('speechState',JSON.parse(JSON.stringify(p)));});
 await page.waitForTimeout(600);
 check('Automatic TTS paragraph following never triggers fullscreen',await page.locator('.novel-paragraph.is-speaking').getAttribute('data-p-index')==='20'&&!await page.locator('.reader').evaluate(e=>e.classList.contains('is-fullscreen')));
 check('Automatic TTS following preserves the separate reading bookmark',savedReading===await page.evaluate(()=>JSON.stringify(window.__mockState.books.kakuyomu_fixture.position)));
 await page.evaluate(()=>{Date.now=window.__readerOriginalNow;const p=window.__mockState.speech.playback;p.status='idle';window.onNativeEvent('speechState',JSON.parse(JSON.stringify(p)));});
 await page.locator('#reader-scroll').click({position:{x:180,y:180}});await manualFullscreen(page);await page.waitForTimeout(1300);
 const nativeBeforeContinuous=await displayCalls(page);
 for(let i=0;i<6;i++){await page.evaluate(()=>{const s=document.querySelector('#reader-scroll');s.scrollTop=s.scrollHeight-s.clientHeight-12;});await page.waitForTimeout(220);}
 check('Continuous chapters and old-chapter trimming keep fullscreen active',await page.locator('.reader').evaluate(e=>e.classList.contains('is-fullscreen'))&&await page.evaluate(()=>window.__nativeCalls.filter(c=>c.action==='readChapter').length>=7)&&await page.locator('.reader-chapter').count()<=5);
 check('Scrolling and auto-loading chapters do not spam the native display bridge',(await displayCalls(page))===nativeBeforeContinuous);
 await page.evaluate(()=>window.handleBack());await fullscreen(page,false);await page.locator('#reader-prev').click();await page.waitForTimeout(150);
 await manualFullscreen(page);await page.evaluate(()=>window.handleBack());await page.locator('#reader-next').click();const movedChapter=await visualPosition(page);await page.setViewportSize({width:360,height:900});await page.waitForTimeout(120);
 check('A late fullscreen resize never rolls explicit chapter navigation back to the previous chapter',samePosition(movedChapter,await visualPosition(page)));
 await page.setViewportSize({width:360,height:840});await page.waitForTimeout(100);
 check('Explicit chapter navigation restores normal reading controls',await page.locator('.reader-top').isVisible()&&await page.evaluate(()=>!window.__readerDisplay.fullscreen));
 const fit=await page.evaluate(()=>({w:innerWidth,page:document.documentElement.scrollWidth,reader:document.querySelector('.reader').scrollWidth}));
 check('Reader controls fit a small 360px phone',fit.page<=fit.w+1&&fit.reader<=fit.w+1,JSON.stringify(fit));
 check('Mobile display flow has no script or CSP errors',errors.length===0,errors.join('\n'));
 check('Mobile display tests make no external requests',requests.every(u=>u.startsWith(origin)));
 await context.close();
 const wide=await setup(720);await openBook(wide.page);await wide.page.evaluate(()=>document.querySelector('#reader-scroll').scrollTop=1100);await wide.page.waitForTimeout(300);const wideAnchor=await visualPosition(wide.page);await manualFullscreen(wide.page);
 check('Wide fold layout preserves its reading anchor when entering fullscreen',samePosition(wideAnchor,await visualPosition(wide.page)));
 await wide.page.screenshot({path:path.join(output,'reader-fullscreen-fold-720.png'),fullPage:true});
 await touchSwipe(wide.context,wide.page,240,460);await fullscreen(wide.page,false);
 check('An actual touch drag toward earlier text restores the controls',await wide.page.locator('.reader-top').isVisible());
 await wide.page.waitForTimeout(320);await touchSwipe(wide.context,wide.page,600,360);await fullscreen(wide.page,true);
 check('An actual touch drag down the story enters fullscreen',await wide.page.evaluate(()=>window.__readerDisplay.fullscreen));
 await wide.page.keyboard.press('Escape');await fullscreen(wide.page,false);await wide.page.locator('[data-action="reader-settings"]').click();await wide.page.screenshot({path:path.join(output,'reader-display-settings-fold-720.png'),fullPage:true});
 const wideFit=await wide.page.evaluate(()=>({w:innerWidth,page:document.documentElement.scrollWidth,sheet:document.querySelector('.sheet').scrollWidth,client:document.querySelector('.sheet').clientWidth}));
 check('Fold-width display settings fit without horizontal overflow',wideFit.page<=wideFit.w+1&&wideFit.sheet<=wideFit.client+1,JSON.stringify(wideFit));
 check('Fold display flow has no script or CSP errors',wide.errors.length===0,wide.errors.join('\n'));
 check('Fold display tests make no external requests',wide.requests.every(u=>u.startsWith(origin)));
 await wide.context.close();
 fs.writeFileSync(path.join(output,'reader-display-ui-results.json'),JSON.stringify({status:'passed',browser:browser.version(),nativeMock:true,nativeSystemBarsTested:false,checks:results},null,2));console.log(JSON.stringify({status:'passed',checks:results.length,output},null,2));
}
run().catch(e=>{fs.writeFileSync(path.join(output,'reader-display-ui-results.json'),JSON.stringify({status:'failed',nativeMock:true,checks:results,error:e.stack},null,2));console.error(e.stack);process.exitCode=1;}).finally(async()=>{if(browser)await browser.close();});
