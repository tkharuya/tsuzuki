'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {initializeNativeMock} = require('./ui-native-mock.cjs');
const {chromium} = require(require.resolve('playwright', {paths:[__dirname,process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES].filter(Boolean)}));
const assets = process.env.NOVELSHELF_READER_ASSETS || path.resolve(__dirname,'../app/src/main/assets');
const output = process.env.NOVELSHELF_SCROLL_REPORT || path.resolve(__dirname,'../build/qa/reader-scroll-stability-results.json');
const origin = 'https://appassets.androidplatform.net';
async function drag(cdp, page, x, from, to) {
  await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x,y:from}]});
  for (let i=1;i<=9;i++) {
    await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x,y:from+(to-from)*i/9}]});
    await page.waitForTimeout(27);
  }
  await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
}
async function checkChapterJoin(page,cdp,x) {
  for(let want=2;want<=4;want++) {
    await page.evaluate(()=>{const s=document.querySelector('#reader-scroll');s.scrollTop=s.scrollHeight-s.clientHeight-20});
    await page.waitForFunction(n=>document.querySelectorAll('.reader-chapter').length>=n,want);
  }
  await page.evaluate(()=>{
    const s=document.querySelector('#reader-scroll');
    const trigger=Math.max(1100,s.clientHeight*1.6);
    s.scrollTop=s.scrollHeight-s.clientHeight-trigger-75;
  });
  await page.waitForTimeout(300);
  await page.evaluate(()=>{
    const scroller=document.querySelector('#reader-scroll');
    const chapters=scroller.querySelectorAll('.reader-chapter');
    if(chapters.length!==4)throw new Error('Expected four chapters before crossing the prefetch boundary');
    const first=chapters[0], kept=chapters[3].querySelector('.novel-paragraph');
    let touching=false,lastMove=performance.now();
    scroller.addEventListener('touchstart',()=>{touching=true},{passive:true});
    scroller.addEventListener('touchend',()=>{touching=false},{passive:true});
    scroller.addEventListener('scroll',()=>{lastMove=performance.now()},{passive:true});
    const remove=first.remove;
    first.remove=function() {
      const before=kept.getBoundingClientRect().top;
      const when={duringTouch:touching,quietMs:Math.round(performance.now()-lastMove)};
      remove.call(this);
      queueMicrotask(()=>{window.chapterTrimTrace={...when,rowDeltaPx:kept.getBoundingClientRect().top-before}});
    };
  });
  await drag(cdp,page,x,615,255);
  await page.waitForFunction(()=>!!window.chapterTrimTrace,{},{timeout:5500});
  return page.evaluate(()=>({...window.chapterTrimTrace,loaded:window.__nativeCalls.filter(c=>c.action==='readChapter').length}));
}
async function verifyEdges(page){
  const metrics=()=>page.evaluate(()=>{
    const s=document.querySelector('#reader-scroll'),header=document.querySelector('.reader-top'),footer=document.querySelector('.reader-bottom');
    return {scroll:s.scrollTop,height:s.clientHeight,full:s.scrollHeight,top:header.getBoundingClientRect().top,
      bottom:innerHeight-footer.getBoundingClientRect().bottom,first:s.querySelector('.novel-paragraph').getBoundingClientRect().top};
  });
  // Enforce an exact bottom position without triggering another automatic chapter read.
  await page.locator('[data-action="reader-settings"]').click();
  const next=page.locator('.sheet [data-key="autoNext"]');if(await next.getAttribute('aria-checked')==='true')await next.click();
  await page.locator('.sheet [data-action="close-sheet"]').click();
  await page.evaluate(()=>{const s=document.querySelector('#reader-scroll');s.scrollTop=s.scrollHeight});
  await page.waitForTimeout(250);
  const before=await metrics();
  await page.locator('[data-action="reader-fullscreen"]').click();await page.waitForTimeout(120);
  const hidden=await metrics();await page.keyboard.press('Escape');await page.waitForTimeout(120);
  const shown=await metrics();
  return {statusGap:shown.top,navGap:shown.bottom,
    maxBottomDriftPx:Math.max(Math.abs(hidden.first-before.first),Math.abs(shown.first-before.first)),
    sameRange:[hidden,shown].every(v=>Math.abs(v.full-before.full)<1 && v.height===before.height && Math.abs(v.scroll-before.scroll)<1)};
}
async function checkWidth(browser,width) {
  const context = await browser.newContext({viewport:{width,height:820},isMobile:true,hasTouch:true,deviceScaleFactor:1});
  await context.addInitScript(initializeNativeMock,{rangeChapters:7});
  await context.route('**/*',async route=>{
    const url=new URL(route.request().url()), name=path.basename(url.pathname);
    if(url.origin!==origin || !['index.html','app.css','app.js'].includes(name))return route.abort();
    return route.fulfill({body:fs.readFileSync(path.join(assets,name)),contentType:({'index.html':'text/html','app.css':'text/css','app.js':'text/javascript'})[name]+'; charset=utf-8'});
  });
  const page=await context.newPage(), errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  try {
    await page.goto(origin+'/assets/index.html');
    await page.locator('.book-open').first().click();
    await page.locator('.novel-paragraph').first().waitFor();
    await page.waitForTimeout(300);
    await page.evaluate(()=>{
      window.onNativeEvent('readerInsets',{top:24,bottom:24});
      document.querySelector('#reader-scroll').scrollTop=1600;
    });
    await page.waitForTimeout(320);
    await page.evaluate(()=>{
      const scroller=document.querySelector('#reader-scroll');
      const target=[...scroller.querySelectorAll('.novel-paragraph')].find(p=>p.getBoundingClientRect().bottom>300);
      const native=Object.getOwnPropertyDescriptor(Element.prototype,'scrollTop');
      const samples=[],writes=[];let active=true;
      // Native scrolling bypasses this setter. Count only repositioning requested by the app.
      Object.defineProperty(scroller,'scrollTop',{get(){return native.get.call(this)},set(value){writes.push(value-native.get.call(this));native.set.call(this,value)}});
      const frame=()=>{
        samples.push({y:scroller.scrollTop,line:target.getBoundingClientRect().top,fullscreen:document.querySelector('.reader').classList.contains('is-fullscreen')});
        if(active)requestAnimationFrame(frame);
      };
      frame();window.finishScrollTrace=()=>{active=false;return {samples,writes}};
    });
    const cdp=await context.newCDPSession(page), x=Math.floor(width/2);
    await drag(cdp,page,x,610,300);
    await page.waitForFunction(()=>document.querySelector('.reader').classList.contains('is-fullscreen'));
    // Emulate a late Android viewport-height notification while a flick may still be running.
    await page.setViewportSize({width,height:846});
    await page.waitForTimeout(140);
    await drag(cdp,page,x,300,585);
    await page.waitForFunction(()=>!document.querySelector('.reader').classList.contains('is-fullscreen'));
    await page.setViewportSize({width,height:820});
    await page.waitForTimeout(500);
    const {samples,writes}=await page.evaluate(()=>window.finishScrollTrace());
    const originY=samples[0].line+samples[0].y;
    // Remove the browser's actual scroll distance from the on-screen displacement of one fixed line.
    const drift=Math.max(...samples.map(s=>Math.abs(s.line+s.y-originY)));
    const span=Math.max(...samples.map(s=>s.y))-Math.min(...samples.map(s=>s.y));
    const joined=await checkChapterJoin(page,cdp,x);
    // Show the controls explicitly if the last forward swipe hid them.
    if(await page.locator('.reader.is-fullscreen').count())await page.keyboard.press('Escape');
    const edges=await verifyEdges(page);await cdp.detach();
    return {width,joined,edges,frames:samples.length,scrollSpanPx:+span.toFixed(2),maxExtraLineDisplacementPx:+drift.toFixed(2),
      applicationScrollWrites:writes.length,largestApplicationWritePx:+Math.max(0,...writes.map(Math.abs)).toFixed(2),
      chromeToggled:samples.some(s=>s.fullscreen)&&!samples.at(-1).fullscreen,errors};
  } finally {await context.close()}
}
(async()=>{
  const browser=await chromium.launch({headless:true,executablePath:process.env.NOVELSHELF_CHROME,args:['--no-sandbox','--disable-dev-shm-usage']});
  try{
    const checks=[];for(const width of [393,720])checks.push(await checkWidth(browser,width));
    const passed=checks.every(r=>r.errors.length===0&&r.chromeToggled&&r.scrollSpanPx>200&&r.maxExtraLineDisplacementPx<=1&&r.applicationScrollWrites===0
      && !r.joined.duringTouch && r.joined.quietMs>=120 && Math.abs(r.joined.rowDeltaPx)<=1 && r.joined.loaded>=5
      && r.edges.statusGap===24 && r.edges.navGap===24 && r.edges.maxBottomDriftPx<=1 && r.edges.sameRange);
    const report={status:passed?'passed':'failed',browser:browser.version(),nativeMock:true,checks};
    fs.mkdirSync(path.dirname(output),{recursive:true});fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n');
    console.log(JSON.stringify(report));assert.ok(passed,'Reader toolbar changes must not move the text or write scrollTop during scrolling');
  } finally {await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
