// Browser-side checks for the local Android WebView UI. Native behavior is mocked.
// Run with an existing Playwright installation and NOVELSHELF_CHROME pointing
// to an existing Chromium executable. Does not access any live websites.
const fs=require('fs');
const path=require('path');
const assert=require('assert').strict;
const {initializeNativeMock}=require('./ui-native-mock.cjs');
let playwright;
try{playwright=require('playwright');}
catch(_){playwright=require(path.join(process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES||'', 'playwright'));}

const root=path.resolve(__dirname,'..');
const assets=path.join(root,'app/src/main/assets');
const output=path.join(root,'build/qa');
const origin='https://appassets.androidplatform.net';
const csp="default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'";
for(const name of ['index.html','app.js','app.css'])if(!fs.existsSync(path.join(assets,name)))throw new Error('UI assets are incomplete: '+name);
fs.mkdirSync(output,{recursive:true});
const results=[];
let browser;
const check=(name,value,details='')=>{assert.ok(value,name+(details?': '+details:''));results.push({name,ok:true,details});};

async function contextFor(width,height,options={}){
  const context=await browser.newContext({viewport:{width,height},deviceScaleFactor:1,isMobile:true,hasTouch:true,reducedMotion:'reduce'});
  const errors=[];
  await context.addInitScript(initializeNativeMock,options);
  await context.route('**/*',async route=>{
    const url=new URL(route.request().url());
    if(url.origin!==origin||!/^\/assets\/[a-zA-Z0-9._-]+$/.test(url.pathname))return route.abort();
    const file=path.join(assets,path.basename(url.pathname));
    if(!fs.existsSync(file))return route.fulfill({status:404,body:'Not found'});
    const ext=path.extname(file);
    await route.fulfill({status:200,body:fs.readFileSync(file),contentType:ext==='.html'?'text/html; charset=utf-8':ext==='.css'?'text/css; charset=utf-8':ext==='.js'?'text/javascript; charset=utf-8':'text/plain; charset=utf-8',headers:{'Content-Security-Policy':csp,'X-Content-Type-Options':'nosniff'}});
  });
  const page=await context.newPage();
  page.on('pageerror',e=>errors.push(e.message));
  page.on('console',m=>{if(m.type()==='error')errors.push(m.text());});
  await page.goto(origin+'/assets/index.html');
  return {context,page,errors};
}

async function run(){
  browser=await playwright.chromium.launch({headless:true,executablePath:process.env.NOVELSHELF_CHROME||undefined,args:['--no-sandbox','--disable-dev-shm-usage']});
  const {context,page,errors}=await contextFor(412,915);
  await page.locator('.book-card').first().waitFor();
  check('Books from all three sources appear in one shelf',await page.locator('.book-card').count()===3);
  check('Native bridge is selected instead of preview fixtures',await page.locator('#preview-label').isHidden());
  check('No initial script errors or CSP violations',errors.length===0,errors.join('\n'));
  await page.screenshot({path:path.join(output,'shelf-mobile.png'),fullPage:true});
  await page.locator('.book-open').first().click();
  await page.locator('.reader').waitFor();
  await page.locator('.novel-paragraph').first().waitFor();
  check('Reader renders a real mock bridge chapter',await page.locator('.novel-paragraph').count()>=28);
  check('Ruby survives reader rendering',await page.locator('.novel-body ruby').count()>0);
  await page.screenshot({path:path.join(output,'reader-mobile.png'),fullPage:true});
  await page.locator('.reader-scroll').evaluate(el=>{el.scrollTop=1400;el.dispatchEvent(new Event('scroll'));});
  await page.waitForFunction(()=>window.__nativeCalls.some(r=>r.action==='savePosition'&&r.payload.paragraph>0),null,{timeout:5000});
  check('Scrolling saves a paragraph anchor through Native',true);
  await page.locator('.reader-controls [data-action="marks"]').click();
  await page.locator('.mark-card.auto').waitFor();
  check('Automatic bookmark is visible at the current reading position',true);
  await page.locator('.sheet [data-action="new-mark"]').click();
  await page.locator('#mark-note').fill('読書位置とメモの保存確認');
  await page.locator('.sheet form[data-form="mark"] button[type="submit"]').click();
  await page.waitForFunction(()=>window.__nativeCalls.some(r=>r.action==='addMark'&&r.payload.note==='読書位置とメモの保存確認'&&r.payload.paragraph>0));
  await page.locator('.reader-controls [data-action="marks"]').click();
  await page.locator('.mark-note').waitFor();
  check('Manual bookmark retains its note and paragraph anchor',await page.locator('.mark-note').innerText()==='読書位置とメモの保存確認');
  await page.locator('.sheet [data-action="close-sheet"]').click();
  await page.locator('.reader-controls [data-action="toc"]').click();
  await page.locator('.toc-row').first().waitFor();
  check('Complete TOC is visible',await page.locator('.toc-row').count()===4);
  const tocSearch=page.getByRole('searchbox',{name:'目次内検索'});
  await tocSearch.fill('編集中の検索');
  await page.evaluate(()=>{
    const bookId=document.querySelector('.toc-open').dataset.book;
    window.__mockState.chapters[bookId][1].title='更新で追加された見出し';
    window.onNativeEvent('tocUpdated',JSON.stringify({bookId}));
  });
  await page.getByText('更新で追加された見出し',{exact:true}).waitFor();
  check('A background TOC refresh preserves the search draft and its focus',
    await tocSearch.inputValue()==='編集中の検索'&&await tocSearch.evaluate(el=>document.activeElement===el));
  const queriesBeforeFilter=await page.evaluate(()=>window.__nativeCalls.filter(r=>r.action==='getBook').length);
  await tocSearch.fill('２');
  await tocSearch.press('Enter');
  await page.waitForFunction(()=>document.querySelectorAll('.toc-row').length===1);
  check('TOC accepts full-width chapter numbers without another load',
    await page.locator('.toc-number').innerText()==='第2話'&&
    await page.evaluate(()=>window.__nativeCalls.filter(r=>r.action==='getBook').length)===queriesBeforeFilter);
  await page.getByRole('button',{name:'現在の話へ'}).click();
  check('The current-chapter action clears a hiding filter and focuses the reading position',
    await tocSearch.inputValue()===''&&await page.locator('.toc-row').count()===4&&
    await page.locator('.toc-row.current .toc-open').evaluate(el=>document.activeElement===el));
  await page.locator('.toc-read').first().click();
  await page.waitForFunction(()=>window.__nativeCalls.some(r=>r.action==='setChapterRead'&&r.payload.read===false));
  check('TOC can explicitly mark an already-read chapter as unread',true);
  await page.locator('.toc-read').first().click();
  await page.waitForFunction(()=>window.__nativeCalls.some(r=>r.action==='setChapterRead'&&r.payload.read===true));
  check('TOC can mark the chapter as read again',true);
  await page.locator('.toc-open').nth(1).click();
  await page.waitForFunction(()=>document.querySelector('.reader-chapter')?.dataset.chapterUrl.endsWith('9000000000000001'));
  check('Selecting a TOC row opens that chapter',true);
  await page.evaluate(()=>{
    const original=window.onNativeResult;
    window.onNativeResult=(id,ok,raw)=>{
      if(window.__nativeCalls.find(r=>r.id===id)?.action!=='getBook')return original(id,ok,raw);
      window.onNativeResult=original;
      window.__finishDeferredToc=()=>original(id,ok,raw);
    };
  });
  await page.locator('.reader-controls [data-action="toc"]').click();
  await page.waitForFunction(()=>typeof window.__finishDeferredToc==='function');
  await page.locator('.sheet [data-action="close-sheet"]').click();
  await page.evaluate(()=>{window.__finishDeferredToc();return new Promise(resolve=>requestAnimationFrame(resolve));});
  check('Closing a loading TOC prevents a delayed reply from reopening it',
    await page.locator('.sheet').count()===0&&await page.locator('.reader').isVisible());
  await page.locator('.reader-controls [data-action="reader-settings"]').click();
  await page.locator('.theme-choice[data-value="night"]').click();
  await page.waitForFunction(()=>document.documentElement.dataset.theme==='night');
  await page.waitForFunction(()=>window.__nativeCalls.some(r=>r.action==='settings'&&r.payload.settings.theme==='night'));
  check('Night theme is applied and persisted through Native',true);
  await page.locator('input[data-setting="fontSize"]').fill('26');
  await page.waitForFunction(()=>document.documentElement.style.getPropertyValue('--reader-size')==='26px');
  check('Font-size control applies to the reader',true);
  await page.locator('.sheet [data-action="close-sheet"]').click();
  await page.screenshot({path:path.join(output,'reader-night-mobile.png'),fullPage:true});
  await page.locator('.reader-scroll').evaluate(el=>{el.scrollTop=el.scrollHeight-el.clientHeight;el.dispatchEvent(new Event('scroll'));});
  await page.waitForFunction(()=>document.querySelectorAll('.reader-chapter').length>=2,null,{timeout:7000});
  check('Next chapter is appended without leaving the reader',true);
  check('Next chapter prefetch uses the Native bridge',await page.evaluate(()=>window.__nativeCalls.some(r=>r.action==='prefetch')));
  await page.evaluate(()=>window.flushReadingPosition&&window.flushReadingPosition());
  await page.waitForTimeout(150);
  await page.locator('.reader-top [data-action="reader-back"]').click();
  await page.locator('[data-action="nav"][data-view="settings"]').click();
  await page.locator('[data-action="toggle-setting"][data-key="resumeOnLaunch"]').click();
  await page.waitForFunction(()=>window.__mockState.settings.resumeOnLaunch===true);
  await page.locator('[data-action="nav"][data-view="library"]').click();
  await page.locator('.book-open').first().click();
  await page.locator('.novel-paragraph').first().waitFor();
  await page.locator('.reader-scroll').evaluate(el=>{el.scrollTop=1800;el.dispatchEvent(new Event('scroll'));});
  await page.waitForTimeout(700);
  await page.evaluate(()=>window.flushReadingPosition());
  await page.waitForTimeout(100);
  const saved=await page.evaluate(()=>window.__mockState.books.kakuyomu_fixture.position);
  await page.reload();
  await page.locator('.reader-chapter').first().waitFor();
  await page.waitForTimeout(700);
  const restored=await page.evaluate(()=>({position:window.__mockState.books.kakuyomu_fixture.position,scroll:document.querySelector('.reader-scroll').scrollTop,theme:document.documentElement.dataset.theme}));
  check('Launch resumes the saved chapter and paragraph',restored.position.chapterUrl===saved.chapterUrl&&restored.position.paragraph===saved.paragraph&&Math.abs(restored.position.offset-saved.offset)<3,JSON.stringify({saved,restored}));
  check('Launch restores scroll and appearance',restored.scroll>100&&restored.theme==='night');
  check('Reading UI remains free of script errors',errors.length===0,errors.join('\n'));
  await context.close();

  const ww=await contextFor(412,915,{completeDownloadsImmediately:true});
  await ww.page.locator('.book-card').first().waitFor();
  check('Wuxiaworld has a source badge on the shared shelf',await ww.page.locator('.book-card .tag.wuxiaworld').count()===1);
  await ww.page.locator('[data-action="library-filter"][data-value="wuxiaworld"]').click();
  check('Wuxiaworld shelf filter keeps only its own book',await ww.page.locator('.book-card').count()===1&&await ww.page.locator('.book-open').getAttribute('data-book')==='wuxiaworld_fixture');
  check('Wuxiaworld shows total, read and saved chapter counts',JSON.stringify(await ww.page.locator('.book-stat strong').allTextContents())===JSON.stringify(['4','1','2']));
  await ww.page.screenshot({path:path.join(output,'wuxiaworld-shelf-mobile.png'),fullPage:true});
  await ww.page.locator('[data-action="nav"][data-view="settings"]').click();
  check('Settings reports app version 1.12.2',/つづき 1\.12\.2/.test(await ww.page.locator('.about').innerText()));
  await ww.page.locator('[data-action="login"][data-source="wuxiaworld"]').click();
  await ww.page.waitForFunction(()=>window.__nativeCalls.some(r=>r.action==='openLogin'&&r.payload.source==='wuxiaworld'));
  check('Wuxiaworld login is delegated to the official Native screen',true);
  await ww.page.locator('[data-action="nav"][data-view="search"]').click();
  check('Integrated search still offers only Kakuyomu and Narou',JSON.stringify(await ww.page.locator('[data-action="search-filter"]').evaluateAll(items=>items.map(el=>el.dataset.value)))===JSON.stringify(['all','kakuyomu','narou'])&&/カクヨムと小説家になろう/.test(await ww.page.locator('.page-subtitle').innerText()));
  await ww.page.locator('.source-discovery [data-action="original"]').click();
  await ww.page.waitForFunction(()=>window.__nativeCalls.some(r=>r.action==='openOriginal'&&r.payload.url==='https://www.wuxiaworld.com/'));
  check('Wuxiaworld discovery opens its official site instead of pretending to search',true);
  await ww.page.locator('.searchbox input[name="query"]').fill('自作の検索テスト');
  await ww.page.locator('.searchbox button[type="submit"]').click();
  await ww.page.locator('.result-card').first().waitFor();
  check('Unified search returns the two supported sources',await ww.page.locator('.result-card').count()===2&&await ww.page.locator('.result-card .tag.wuxiaworld').count()===0&&await ww.page.evaluate(()=>window.__nativeCalls.some(r=>r.action==='search'&&r.payload.source==='all')));
  await ww.page.locator('[data-action="add-url"]').click();
  check('Add-URL instructions include Wuxiaworld',/Wuxiaworld/.test(await ww.page.locator('.sheet-description').innerText()));
  const wwUrl=await ww.page.evaluate(()=>window.__mockState.books.wuxiaworld_fixture.url);
  await ww.page.locator('#book-url').fill(wwUrl+'/test-chapter-2');
  await ww.page.locator('[data-form="url"] button[type="submit"]').click();
  await ww.page.locator('.novel-paragraph').first().waitFor();
  check('A Wuxiaworld chapter URL opens the requested chapter',await ww.page.locator('.reader-chapter').first().getAttribute('data-chapter-url')===wwUrl+'/test-chapter-2'&&/This original paragraph/.test(await ww.page.locator('.novel-paragraph').first().innerText()));
  await ww.page.locator('.reader-top [data-action="book-menu"]').click();
  check('Whole-book and saved-chapter TXT actions are distinct',await ww.page.locator('.sheet [data-action="export"]').count()===1&&await ww.page.locator('.sheet [data-action="export-saved"]').count()===1);
  await ww.page.locator('.sheet [data-action="export-saved"]').click();
  await ww.page.locator('[data-action="export-saved-confirm"]').waitFor();
  const wwSaved=await ww.page.evaluate(()=>window.__mockState.chapters.wuxiaworld_fixture.filter(c=>c.cached).length);
  check('Saved-chapter TXT asks for confirmation with its exact counts',JSON.stringify(await ww.page.locator('.sheet .stat-cell strong').allTextContents())===JSON.stringify(['4',String(wwSaved),String(4-wwSaved)])&&/未保存の話は含まれません/.test(await ww.page.locator('.sheet-description').innerText())&&await ww.page.evaluate(()=>!window.__nativeCalls.some(r=>r.action==='exportSavedTxt')));
  await ww.page.locator('[data-action="export-saved-confirm"]').click();
  await ww.page.waitForFunction(()=>window.__nativeCalls.some(r=>r.action==='exportSavedTxt'&&r.payload.bookId==='wuxiaworld_fixture'));
  check('Confirmed partial TXT uses exportSavedTxt, not whole-book export',await ww.page.evaluate(()=>window.__nativeCalls.filter(r=>r.action==='exportSavedTxt').length===1&&!window.__nativeCalls.some(r=>r.action==='exportTxt')));
  await ww.page.locator('.reader-top [data-action="book-menu"]').click();
  await ww.page.locator('.sheet [data-action="export"]').click();
  await ww.page.locator('[data-action="save-export"]').click();
  await ww.page.waitForFunction(()=>window.__nativeCalls.some(r=>r.action==='exportTxt'&&r.payload.bookId==='wuxiaworld_fixture'));
  await ww.page.waitForTimeout(100);
  check('Completion event before download reply opens only one whole-book TXT picker',await ww.page.evaluate(()=>window.__nativeCalls.filter(r=>r.action==='exportTxt').length===1&&window.__mockState.books.wuxiaworld_fixture.complete));
  check('Wuxiaworld normal flows have no script errors or CSP violations',ww.errors.length===0,ww.errors.join('\n'));
  await ww.context.close();

  const partial=await contextFor(360,900,{wuxiaworldPartialToc:true});
  await partial.page.locator('.book-card').first().waitFor();
  const partialCard=partial.page.locator('.book-card').filter({has:partial.page.locator('[data-action="open-book"][data-book="wuxiaworld_fixture"]')});
  check('A partial Wuxiaworld TOC is not shown as fully saved',JSON.stringify(await partialCard.locator('.book-stat strong').allTextContents())===JSON.stringify(['6','1','4'])&&await partialCard.locator('[title="全話保存済み"]').count()===0);
  await partial.page.locator('[data-action="book-menu"][data-book="wuxiaworld_fixture"]').click();
  await partial.page.locator('.sheet [data-action="export"]').click();
  check('Whole TXT remains gated while only part of the book is saved',await partial.page.locator('[data-action="save-export"]').count()===1&&await partial.page.evaluate(()=>!window.__nativeCalls.some(r=>r.action==='exportTxt')));
  await partial.page.locator('.sheet [data-action="close-sheet"]').click();
  await partial.page.locator('[data-action="book-menu"][data-book="wuxiaworld_fixture"]').click();
  await partial.page.locator('.sheet [data-action="toc"]').click();
  await partial.page.locator('.toc-incomplete').waitFor();
  check('Partial TOC shows known chapters and a clear official-page fallback',await partial.page.locator('.toc-row').count()===4&&/一部のみ取得済み/.test(await partial.page.locator('.toc-incomplete').innerText())&&await partial.page.locator('.toc-incomplete [data-action="original"]').count()===1&&await partial.page.locator('.sheet .spinner').count()===0);
  await partial.page.locator('.toc-open').last().click();
  await partial.page.locator('.next-area .toc-incomplete').waitFor();
  check('End of the known TOC is not claimed to be the end of the book',!/この作品の最後の話/.test(await partial.page.locator('.next-area').innerText()));
  await partial.page.screenshot({path:path.join(output,'wuxiaworld-partial-mobile.png'),fullPage:true});
  check('Partial TOC has no script errors or CSP violations',partial.errors.length===0,partial.errors.join('\n'));
  await partial.context.close();

  const locked=await contextFor(360,900,{wuxiaworldLockedChapter:2});
  await locked.page.locator('.book-card').first().waitFor();
  const lockedUrl=await locked.page.evaluate(()=>window.__mockState.chapters.wuxiaworld_fixture[2].url);
  await locked.page.locator('[data-action="add-url"]').click();
  await locked.page.locator('#book-url').fill(lockedUrl);
  await locked.page.locator('[data-form="url"] button[type="submit"]').click();
  await locked.page.locator('.reader .error-box').waitFor();
  check('A locked chapter displays an error instead of novel paragraphs',await locked.page.locator('.novel-paragraph').count()===0&&/ロックされています/.test(await locked.page.locator('.error-box').innerText()));
  await locked.page.locator('.error-box [data-action="original"]').click();
  await locked.page.waitForFunction(url=>window.__nativeCalls.some(r=>r.action==='openOriginal'&&r.payload.url===url),lockedUrl);
  check('A locked chapter offers its exact official page for verification',await locked.page.evaluate(()=>!window.__mockState.chapters.wuxiaworld_fixture[2].cached));
  check('Locked chapter UI has no script errors or CSP violations',locked.errors.length===0,locked.errors.join('\n'));
  await locked.context.close();

  const sparse=await contextFor(360,900,{wuxiaworldSparseToc:true});
  await sparse.page.locator('.book-card').first().waitFor();
  const sparseUrl=await sparse.page.evaluate(()=>window.__mockState.books.wuxiaworld_fixture.url);
  await sparse.page.locator('.book-open[data-book="wuxiaworld_fixture"]').click();
  await sparse.page.locator('.novel-paragraph').first().waitFor();
  await sparse.page.locator('#reader-next').click();
  await sparse.page.waitForFunction(url=>document.querySelector('.reader-chapter')?.dataset.chapterUrl===url&&document.querySelector('#reader-prev')?.disabled===false,sparseUrl+'/test-chapter-101');
  check('Sparse TOC permits the consecutive fallback from chapter 100 to 101',await sparse.page.locator('#reader-prev').isEnabled());
  await sparse.page.locator('.reader-scroll').evaluate(el=>{el.scrollTop=el.scrollHeight-el.clientHeight;el.dispatchEvent(new Event('scroll'));});
  await sparse.page.waitForTimeout(300);
  check('Sparse TOC never treats chapter 200 as the next chapter after 101',await sparse.page.locator('#reader-next').isDisabled()&&await sparse.page.locator('.next-area .toc-incomplete').count()===1&&await sparse.page.evaluate(url=>!window.__nativeCalls.some(r=>['readChapter','prefetch'].includes(r.action)&&r.payload.chapterUrl===url),sparseUrl+'/test-chapter-200'));
  await sparse.page.locator('.reader-controls [data-action="toc"]').click();
  await sparse.page.locator('.toc-open').last().click();
  await sparse.page.waitForFunction(url=>window.__nativeCalls.some(r=>r.action==='prefetch'&&r.payload.chapterUrl===url),sparseUrl+'/test-chapter-201');
  await sparse.page.locator('#reader-prev').click();
  await sparse.page.waitForFunction(url=>window.__nativeCalls.some(r=>r.action==='readChapter'&&r.payload.chapterUrl===url),sparseUrl+'/test-chapter-199');
  check('DOM links take priority over sparse TOC rows in both directions',await sparse.page.evaluate(base=>window.__nativeCalls.some(r=>r.action==='prefetch'&&r.payload.chapterUrl===base+'/test-chapter-201')&&window.__nativeCalls.filter(r=>r.action==='readChapter').at(-1).payload.chapterUrl===base+'/test-chapter-199',sparseUrl));
  check('Sparse TOC navigation has no script errors or CSP violations',sparse.errors.length===0,sparse.errors.join('\n'));
  await sparse.context.close();

  for(const width of [320,360,720]){
    const q=await contextFor(width,900);
    await q.page.locator('.book-card').first().waitFor();
    const size=await q.page.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth}));
    check('Shelf has no horizontal overflow at '+width+'px',size.scroll<=size.width+1,JSON.stringify(size));
    check('No script errors at '+width+'px',q.errors.length===0,q.errors.join('\n'));
    await q.page.screenshot({path:path.join(output,'shelf-'+width+'.png'),fullPage:true});
    await q.context.close();
  }
  fs.writeFileSync(path.join(output,'ui-results.json'),JSON.stringify({status:'passed',browser:browser.version(),nativeMock:true,liveSiteRequests:false,checks:results},null,2));
  console.log(JSON.stringify({status:'passed',checks:results.length,output},null,2));
}
run().catch(error=>{
  fs.writeFileSync(path.join(output,'ui-results.json'),JSON.stringify({status:'failed',nativeMock:true,checks:results,error:error.message},null,2));
  console.error(error.stack);process.exitCode=1;
}).finally(async()=>{if(browser)await browser.close();});
