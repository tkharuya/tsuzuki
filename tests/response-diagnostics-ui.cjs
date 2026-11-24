// Synthetic Native events only. No sign-in, live model request or third-party novel content.
const fs=require('fs'),path=require('path'),assert=require('assert').strict;
const {initializeNativeMock}=require('./ui-native-mock.cjs');
let playwright;try{playwright=require('playwright');}catch(_){playwright=require(path.join(process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES||'','playwright'));}
const root=path.resolve(__dirname,'..'),assets=path.join(root,'app/src/main/assets'),output=path.join(root,'build/qa'),origin='https://appassets.androidplatform.net';
fs.mkdirSync(output,{recursive:true});const checks=[];let browser;
const check=(name,ok,detail='')=>{assert.ok(ok,name+(detail?': '+detail:''));checks.push({name,ok:true});};
const formatMessage='ChatGPT解析の応答形式を確認できませんでした。途中の応答は採用していません。';
const metadata={provider:'chatgpt',httpStatus:200,code:'invalid_stream',stage:'stream_read',responseShape:'completed_without_output',param:'response.output[0]',requestId:'req_synthetic_fixture',mediaType:'text/html',bodyFormat:'html'};
const expected=['appVersion: 1.12.2','model: fixture-model','provider: chatgpt','httpStatus: 200','code: invalid_stream','stage: stream_read','responseShape: completed_without_output','param: response.output[0]','requestId: req_synthetic_fixture','mediaType: text/html','bodyFormat: html'].join('\n');
async function setup(){
 const context=await browser.newContext({viewport:{width:360,height:900},deviceScaleFactor:1,isMobile:true,hasTouch:true,reducedMotion:'reduce'}),errors=[],requests=[];
 await context.addInitScript(initializeNativeMock,{speechUnits:32});
 await context.route('**/*',async route=>{const u=new URL(route.request().url());requests.push(u.href);if(u.origin!==origin||!/^\/assets\/[a-zA-Z0-9._-]+$/.test(u.pathname))return route.abort();const file=path.join(assets,path.basename(u.pathname));if(!fs.existsSync(file))return route.fulfill({status:404,body:'Not found'});return route.fulfill({status:200,body:fs.readFileSync(file),contentType:file.endsWith('.html')?'text/html; charset=utf-8':file.endsWith('.css')?'text/css; charset=utf-8':'text/javascript; charset=utf-8'});});
 const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));page.on('console',m=>{if(m.type()==='error')errors.push(m.text());});await page.goto(origin+'/assets/index.html');await page.locator('.book-card').first().waitFor();return{context,page,errors,requests};
}
async function openSpeech(page){await page.locator('[data-action="speech-open"]').click();await page.locator('[data-speech-panel]').waitFor();}
async function closeSheet(page){await page.locator('.sheet [data-action="close-sheet"]').click();}
async function emit(page,patch){
 await page.evaluate(patch=>{const [key,plan]=Object.entries(window.__mockState.speech.plans)[0];Object.assign(plan,patch);window.onNativeEvent('speechAnalysis',{bookId:key.split('|')[0],chapterUrl:plan.chapterUrl,plan:JSON.parse(JSON.stringify(plan)),cast:window.__mockState.speech.cast[key.split('|')[0]],status:plan.analysisStatus,error:plan.error});},patch);
}
async function run(){
 browser=await playwright.chromium.launch({headless:true,executablePath:process.env.NOVELSHELF_CHROME,args:['--no-sandbox','--disable-dev-shm-usage']});
 const{page,context,errors,requests}=await setup();
 await page.locator('.book-open').first().click();await page.locator('.novel-paragraph').first().waitFor();await openSpeech(page);
 check('Normal reading keeps diagnostic implementation details hidden',await page.locator('[data-analysis-diagnostics]').count()===0);
 await emit(page,{analysisStatus:'error',error:formatMessage,analysisError:null});
 check('Legacy errors without metadata retain the message and remain usable',await page.locator('[data-analysis-status] .download-error').innerText()===formatMessage&&await page.locator('[data-analysis-diagnostics]').count()===0&&await page.locator('[data-action="speech-analyze"]').isEnabled());
 const privateFields={geminiApiKey:'AQ.fixture-private-gemini-auth-key-not-real',apiKey:'fixture-private-openai-key-not-real',body:'fixture-private-response-body',prompt:'fixture-private-prompt',access_token:'fixture-private-access',refresh_token:'fixture-private-refresh',id_token:'fixture-private-id',account:{name:'fixture-private-account'},unknown:{value:'fixture-private-unknown'}};
 await emit(page,{analysisStatus:'error',error:formatMessage,analysisModel:'fixture-model',analysisError:{...metadata,...privateFields},secret:'fixture-private-plan'});
 const details=page.locator('[data-analysis-diagnostics]'),field=details.locator('textarea'),copyButton=details.locator('[data-action="speech-copy-diagnostics"]');
 check('Structured failures expose a collapsed optional diagnostic section',await details.count()===1&&!await details.evaluate(el=>el.open)&&await details.locator('summary').innerText()==='エラーの詳細');
 await details.locator('summary').click();
 check('Diagnostic text consists exactly of the approved metadata fields',await field.inputValue()===expected&&await field.getAttribute('readonly')==='');
 check('Unknown fields, account names, token values and body text never enter diagnostic DOM',!/fixture-private/.test(await details.evaluate(el=>el.outerHTML)));
 await page.evaluate(()=>Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:async value=>{window.__copiedDiagnostic=value;}}}));
 await copyButton.click();
 check('Clipboard API copies only the approved report and gives confirmation',await page.evaluate(()=>window.__copiedDiagnostic)===expected&&/コピーしました/.test(await page.locator('#toast-host').innerText()));
 await page.evaluate(()=>{Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:async()=>{throw new Error('Synthetic clipboard denial');}}});document.execCommand=command=>{const el=document.activeElement;window.__fallbackCopy={command,value:el.value.slice(el.selectionStart,el.selectionEnd),readonly:el.readOnly};return true;};});
 await copyButton.click();
 check('Clipboard denial falls back to selecting and copying the readonly report',await page.evaluate(()=>window.__fallbackCopy.command==='copy'&&window.__fallbackCopy.readonly)&&await page.evaluate(()=>window.__fallbackCopy.value)===expected);
 await page.evaluate(()=>{Object.defineProperty(navigator,'clipboard',{configurable:true,value:undefined});document.execCommand=()=>false;});
 await copyButton.click();
 check('Unavailable clipboard keeps the full report selected for manual copy',await field.evaluate(el=>document.activeElement===el&&el.selectionStart===0&&el.selectionEnd===el.value.length&&el.readOnly)&&/長押ししてコピー/.test(await page.locator('#toast-host').innerText()));
 await page.evaluate(()=>{window.__diagnosticNode=document.querySelector('[data-analysis-diagnostics] textarea');});
 await emit(page,{analysisStatus:'error'});
 check('Repeated native events preserve an expanded report and the manual copy selection',await details.evaluate(el=>el.open)&&await field.evaluate(el=>el===window.__diagnosticNode&&el.selectionStart===0&&el.selectionEnd===el.value.length));
 await closeSheet(page);await openSpeech(page);await details.locator('summary').click();await field.evaluate(el=>{el.focus();el.select();window.__diagnosticNode=el;});await emit(page,{analysisStatus:'error'});
 check('Reopened persisted failures also preserve selection on the first native event',await field.evaluate(el=>el===window.__diagnosticNode&&el.selectionStart===0&&el.selectionEnd===el.value.length));
 await emit(page,{analysisStatus:'running',error:'',analysisError:metadata});
 check('Starting a new analysis hides stale diagnostic metadata',await details.count()===0);
 await emit(page,{analysisStatus:'complete',error:'',analysisError:metadata});
 check('Completed analysis does not display old failure details',await details.count()===0);
 await emit(page,{analysisStatus:'error',error:formatMessage,analysisModel:'<img src=x>',analysisError:{provider:'<script>bad</script>',httpStatus:'200',code:'line\nsecret',stage:'bad value',responseShape:{private:'fixture-private-shape'},param:'<img src=x>',requestId:'Bearer fixture-private-token',unknown:'fixture-private-unknown',mediaType:'text/plain; secret=fixture-private-token',bodyFormat:'fixture-private-token'}});
 await details.locator('summary').click();
 check('Malformed values in approved fields are omitted without HTML execution',await field.inputValue()==='appVersion: 1.12.2'&&await details.locator('img,script').count()===0&&!/fixture-private/.test(await details.evaluate(el=>el.outerHTML)));
 await page.evaluate(()=>Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:async value=>{window.__copiedDiagnostic=value;}}}));await copyButton.click();
 check('Malformed metadata cannot enter copied text',await page.evaluate(()=>window.__copiedDiagnostic)==='appVersion: 1.12.2');
 const timeoutMessage='ChatGPTの応答待ちがタイムアウトしました。通信状況を確認して、もう一度お試しください。';
 await emit(page,{analysisStatus:'error',error:timeoutMessage,analysisModel:'fixture-model',analysisError:{...metadata,code:'timeout',stage:'stream_read',responseShape:'stream_interrupted'}});
 check('Timeout messages are shown independently of format errors and new details start collapsed',await page.locator('[data-analysis-status] .download-error').innerText()===timeoutMessage&&!await details.evaluate(el=>el.open));
 await details.locator('summary').click();check('Timeout diagnostics retain their classification',/code: timeout/.test(await field.inputValue())&&/responseShape: stream_interrupted/.test(await field.inputValue()));
 const networkMessage='ChatGPTとの通信が切断されました。通信状況を確認して、もう一度お試しください。';
 await emit(page,{analysisStatus:'error',error:networkMessage,analysisError:{...metadata,httpStatus:0,code:'network_error',stage:'request',responseShape:'no_response'}});
 check('Network failures retain their native message without a response-format substitution',await page.locator('[data-analysis-status] .download-error').innerText()===networkMessage);
 await details.locator('summary').click();check('Network diagnostics preserve zero status for a missing HTTP response',/httpStatus: 0/.test(await field.inputValue())&&/stage: request/.test(await field.inputValue()));
 await emit(page,{analysisStatus:'error',error:formatMessage,analysisModel:'fixture-model',analysisError:metadata});await details.locator('summary').click();
 await page.locator('.sheet-content').evaluate(el=>{el.scrollTop+=document.querySelector('[data-analysis-status]').getBoundingClientRect().top-el.getBoundingClientRect().top-14;});
 await page.locator('.toast').waitFor({state:'detached'});await page.screenshot({path:path.join(output,'response-diagnostics-mobile.png'),fullPage:true});
 check('Expanded diagnostics fit the 360px mobile viewport',await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth&&document.querySelector('.sheet').scrollWidth<=document.querySelector('.sheet').clientWidth));
 await page.setViewportSize({width:412,height:900});check('Expanded diagnostics fit a wider Android viewport',await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth&&document.querySelector('.sheet').scrollWidth<=document.querySelector('.sheet').clientWidth));
 await page.locator('.sheet [data-action="speech-settings"]').click();await page.locator('input[name="engine"][value="device"]').check();await page.locator('#speech-default-offline').selectOption('sora');await emit(page,{analysisStatus:'error',error:timeoutMessage,analysisError:{...metadata,code:'timeout'}});
 check('Analysis failures preserve unsaved offline engine and voice settings',await page.locator('input[name="engine"][value="device"]').isChecked()&&await page.locator('#speech-default-offline').inputValue()==='sora'&&await details.count()===0);
 await closeSheet(page);await openSpeech(page);await page.locator('[data-action="speech-cast-list"]').click();await page.locator('[data-action="speech-cast-edit"][data-id="narrator"]').click();await page.locator('#cast-name').fill('未保存の語り手');await page.locator('#cast-voice').selectOption('zundamon');await emit(page,{analysisStatus:'error',error:networkMessage,analysisError:{...metadata,code:'network_error'}});
 check('Analysis failures preserve unsaved per-character names and fixed voices',await page.locator('#cast-name').inputValue()==='未保存の語り手'&&await page.locator('#cast-voice').inputValue()==='zundamon'&&await details.count()===0);
 check('Showing and copying diagnostics never initiates model or audio calls',await page.evaluate(()=>!window.__nativeCalls.some(r=>r.action==='speechAnalyze'||r.action==='speechControl')));
 check('Diagnostic scenarios make only local asset requests',requests.every(url=>url.startsWith(origin+'/assets/')));
 check('Diagnostic scenarios have no script or CSP errors',errors.length===0,errors.join('\n'));
 check('Document title identifies the patched app version',await page.title()==='つづき 1.12.2 — 小説リーダー');
 await context.close();await browser.close();browser=null;
 fs.writeFileSync(path.join(output,'response-diagnostics-ui-results.json'),JSON.stringify({status:'passed',scope:'Synthetic Native events in local Chromium; no account, real model API or physical Android clipboard',checks},null,2));console.log('PASS response diagnostics UI: '+checks.length+' checks');
}
run().catch(async error=>{fs.writeFileSync(path.join(output,'response-diagnostics-ui-results.json'),JSON.stringify({status:'failed',checks,error:error.stack},null,2));console.error(error);if(browser)await browser.close();process.exitCode=1;});
