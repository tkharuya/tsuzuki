'use strict';

(() => {
  const $ = (selector, scope = document) => scope.querySelector(selector);
  const $$ = (selector, scope = document) => [...scope.querySelectorAll(selector)];
  const app = $('#app');
  const sheetHost = $('#sheet-host');
  const esc = value => String(value == null ? '' : value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const clamp = (value, min, max) => Math.min(max, Math.max(min, Number(value) || 0));
  const count = value => Math.max(0, Number(value) || 0).toLocaleString('ja-JP');
  const sourceName = source => ({kakuyomu:'カクヨム',narou:'小説家になろう',wuxiaworld:'Wuxiaworld',local:'TXT'}[source] || '小説');
  const wuxiaworldHome = 'https://www.wuxiaworld.com/';
  const paths = {
    book:'M4 4h6a3 3 0 0 1 3 3v14a4 4 0 0 0-4-2H4V4Zm16 0h-4a3 3 0 0 0-3 3v14a4 4 0 0 1 4-2h3V4Z',
    shelf:'M4 4v16M8 5v14M12 4v16M16 5l4 14M3 20h18',
    search:'M20 20l-4.5-4.5M17 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0Z',
    download:'M12 3v12m-5-5 5 5 5-5M4 16v4h16v-4',
    settings:'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8Zm-2-5h4l.6 2.5 2.1 1.2 2.5-.7 2 3.5-1.9 1.8v2.4l1.9 1.8-2 3.5-2.5-.7-2.1 1.2L14 22h-4l-.6-2.5-2.1-1.2-2.5.7-2-3.5 1.9-1.8v-2.4L2.8 9.5l2-3.5 2.5.7 2.1-1.2L10 3Z',
    plus:'M12 5v14M5 12h14',left:'M15 5l-7 7 7 7',right:'M9 5l7 7-7 7',arrow:'M4 12h16m-6-6 6 6-6 6',
    bookmark:'M6 3h12v18l-6-4-6 4V3Z',list:'M9 6h12M9 12h12M9 18h12M3 6h1M3 12h1M3 18h1',
    text:'M4 5h16M12 5v15M8 20h8',close:'M5 5l14 14M19 5 5 19',check:'m5 12 4 4L20 5',
    refresh:'M20 7v5h-5M4 17v-5h5M5.4 6A8 8 0 0 1 20 12M4 12a8 8 0 0 0 14.6 6',
    dots:'M5 12h.01M12 12h.01M19 12h.01',sun:'M12 3v2m0 14v2M3 12h2m14 0h2M5.6 5.6 7 7m10 10 1.4 1.4M5.6 18.4 7 17M17 7l1.4-1.4M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z',
    moon:'M21 13a9 9 0 1 1-10-10 7 7 0 0 0 10 10Z',file:'M5 3h9l5 5v13H5V3Zm9 0v6h5M8 13h8M8 17h6',
    offline:'m3 3 18 18M4 9a13 13 0 0 1 2.5-1.5M9 6.5A13 13 0 0 1 21 9M7 13a8 8 0 0 1 3-1.5M14 11.5a8 8 0 0 1 3 1.5M10 17a3 3 0 0 1 4 0M12 20h.01',
    headphones:'M4 14v-2a8 8 0 0 1 16 0v2M4 13H3v7h4v-7H4Zm16 0h1v7h-4v-7h3',previous:'M6 5v14m12-14L8 12l10 7V5Z',next:'M18 5v14M6 5l10 7-10 7V5Z',spark:'m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5L12 3Z',people:'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2m20 0v-2a4 4 0 0 0-3-3.9M9 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8m8 .2a4 4 0 0 1 0 7.6',
    play:'m8 4 12 8-12 8V4Z',stop:'M5 5h14v14H5V5Z',pause:'M8 4v16M16 4v16',trash:'M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7M14 10v7',
    link:'M10 13a5 5 0 0 0 7 .5l3-3a5 5 0 0 0-7-7l-2 2M14 11a5 5 0 0 0-7-.5l-3 3a5 5 0 0 0 7 7l2-2',
    external:'M13 4h7v7m0-7L10 14M10 4H4v16h16v-6',login:'M13 4h7v16h-7M3 12h12m-5-5 5 5-5 5',
    alert:'M12 3 2 21h20L12 3Zm0 6v5m0 3v1',cloud:'M7 18H6a5 5 0 1 1 1-9 6 6 0 0 1 11-1 5 5 0 1 1 0 10h-1M12 13v8m-3-3 3 3 3-3',
    import:'M14 3H5v18h14V8l-5-5Zm0 0v6h5M8 14h8m-3-3 3 3-3 3',circle:'M20 12a8 8 0 1 1-16 0 8 8 0 0 1 16 0Z',fullscreen:'M8 3H3v5m13-5h5v5M3 16v5h5m13-5v5h-5'
  };
  const icon = name => `<svg class="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="${paths[name] || paths.book}"/></svg>`;
  const badge = source => `<span class="tag ${['kakuyomu','narou','wuxiaworld','local'].includes(source) ? source : 'subtle'}">${esc(sourceName(source))}</span>`;
  const defaultSettings = {theme:'paper',fontSize:20,lineHeight:2,fontFamily:'serif',showRuby:true,showPreface:false,autoNext:true,resumeOnLaunch:true,keepScreenOn:false,showSpeechPlayer:false,autoFullscreen:true};
  const state = {books:[],downloads:[],settings:{...defaultSettings},lastBookId:'',view:'library',filter:'all',reader:null,castPage:null,memoryPage:null,sheet:null,booted:false,operation:0,
    search:{query:'',source:'all',page:1,items:[],more:false,warnings:[],loading:false,hasSearched:false,error:null,token:0}};
  const detailsCache = new Map();
  const pending = new Map();
  const earlyEvents = [];
  const exportQueue = new Set();
  const completionChecks = new Map();
  const hasNative = !!(window.Native && typeof window.Native.request === 'function');
  let requestSequence = 0, readerSequence = 0, saveTimer = 0, settingsTimer = 0, refreshTimer = 0, settingsRevision = 0, toastTimer = 0;
  let busyToken = 0, busyActive = false, nativeReaderDisplay = '';
  let analysisClockTimer = 0;
  const analysisClockStops = new Map();
  const readerScrollKeys = new Set(['ArrowDown','ArrowUp','PageDown','PageUp','Home','End',' ']);

  function parseData(value) { if (typeof value !== 'string') return value; try { return JSON.parse(value); } catch (_) { return value; } }
  window.onNativeResult = (id, ok, raw) => {
    const item = pending.get(String(id));
    if (!item) return;
    clearTimeout(item.timer); pending.delete(String(id));
    const data = parseData(raw) || {};
    if (ok) item.resolve(data);
    else { const error = new Error(data.message || '処理できませんでした。'); error.code = data.code; error.url = data.url; item.reject(error); }
  };
  window.onNativeEvent = (type, raw) => {
    const data = parseData(raw) || {};
    if (!state.booted) { earlyEvents.push([type, data]); return; }
    handleNativeEvent(type, data);
  };
  function call(action, payload = {}) {
    if (!hasNative) return previewCall(action, payload);
    return new Promise((resolve, reject) => {
      const id = `ui-${++requestSequence}`;
      const timeout = ['openUrl','updateBook','readChapter','search','prefetch','speechPrepare','speechControl'].includes(action) ? 150000 : 30000;
      const timer = setTimeout(() => { pending.delete(id); reject(new Error('処理に時間がかかっています。通信状況を確認して、もう一度お試しください。')); }, timeout);
      pending.set(id, {resolve,reject,timer});
      try { window.Native.request(JSON.stringify({id,action,payload})); }
      catch (error) { clearTimeout(timer); pending.delete(id); reject(error); }
    });
  }
  function toast(message, error = false) {
    clearTimeout(toastTimer);
    $('#toast-host').innerHTML = `<div class="toast${error ? ' error' : ''}" role="status">${esc(message)}</div>`;
    toastTimer = setTimeout(() => { $('#toast-host').replaceChildren(); }, error ? 6500 : 3300);
  }
  function showError(error) {
    setReaderFullscreen(false);
    const message = error && error.message ? error.message : String(error || '処理できませんでした。');
    if (error && error.url && /^https:\/\//.test(error.url)) {
      openSheet('確認が必要です', `<div class="error-box"><div class="row">${icon('alert')}<span>${esc(message)}</span></div></div><p class="sheet-description" style="margin-top:17px">公式画面で公開状態やログイン状態を確認できます。</p><button class="button full" data-action="original" data-url="${esc(error.url)}">${icon('external')}公式画面を開く</button>`, {kind:'error'});
    } else toast(message, true);
  }
  function beginBusy(message) { setReaderFullscreen(false); const token = ++busyToken; busyActive = true; $('#busy-message').textContent = message; $('#busy-overlay').hidden = false; return token; }
  function endBusy(token) { if (token === busyToken) { busyActive = false; $('#busy-overlay').hidden = true; } }
  function nextPaint() { return new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))); }
  function getBook(id) { return state.books.find(book => book.id === id); }
  function upsertBook(book) {
    if (!book || !book.id) return;
    const at = state.books.findIndex(item => item.id === book.id);
    if (at < 0) state.books.unshift(book); else state.books[at] = {...state.books[at],...book};
    if (state.reader && state.reader.book.id === book.id) state.reader.book = {...state.reader.book,...book};
    const cached = detailsCache.get(book.id); if (cached) cached.book = {...cached.book,...book};
  }
  function rememberDetails(data) {
    if (!data || !data.book) throw new Error('作品情報を読み込めませんでした。');
    data.chapters = Array.isArray(data.chapters) ? data.chapters : [];
    data.marks = Array.isArray(data.marks) ? data.marks : [];
    if (data.position && !data.book.position) data.book.position = data.position;
    upsertBook(data.book); detailsCache.set(data.book.id, data); return data;
  }
  function knownTotal(book) { return Number(book && book.total) > 0 ? count(book.total) : book && book.tocComplete === false ? '…' : '0'; }
  function isFullySaved(book) { return !!book && book.complete === true && book.tocComplete !== false && Number(book.total) > 0 && Number(book.downloaded) >= Number(book.total); }
  function readPercent(book) { return book.total > 0 ? clamp((book.readCount || 0) / book.total * 100, 0, 100) : 0; }
  function positionLabel(book) { return book.position && book.position.chapterUrl ? `第${count(Number(book.position.index || 0) + 1)}話のつづき` : '未読'; }
  function currentBook() { return state.reader ? state.reader.book : null; }
  function applySettings() {
    const s = state.settings;
    document.documentElement.dataset.theme = s.theme;
    document.documentElement.style.setProperty('--reader-size', `${clamp(s.fontSize,14,34)}px`);
    document.documentElement.style.setProperty('--reader-line', String(clamp(s.lineHeight,1.4,2.6)));
    document.documentElement.style.setProperty('--reader-family', s.fontFamily === 'sans' ? 'system-ui,"Noto Sans CJK JP","Noto Sans JP",sans-serif' : '"Noto Serif CJK JP","Noto Serif JP","Yu Mincho","Hiragino Mincho ProN",serif');
    document.documentElement.classList.toggle('hide-ruby', !s.showRuby);
    $$('meta[name="theme-color"]').forEach(meta => meta.setAttribute('content', s.theme === 'night' ? '#171e1c' : s.theme === 'white' ? '#f8faf9' : '#f7f5ef'));
    $$('.chapter-extra').forEach(node => { node.hidden = !s.showPreface; });
    if (!s.autoFullscreen) setReaderFullscreen(false);
    renderSpeechPlayer(false);
    $$('[data-key="showSpeechPlayer"],[data-key="autoFullscreen"]').forEach(button => button.setAttribute('aria-checked',String(!!s[button.dataset.key])));
  }
  function updateSetting(key, value) {
    const r = state.reader;
    const changesText = ['fontSize','lineHeight','fontFamily','showRuby','showPreface'].includes(key);
    const anchor = changesText && r ? readingPosition() : null;
    const revisionAtInput = r && r.display.anchorRevision;
    state.settings[key] = value; applySettings();
    if (anchor) nextPaint().then(() => {
      if (state.reader === r && r.display.anchorRevision === revisionAtInput) restorePosition(anchor);
    });
    clearTimeout(settingsTimer);
    const revision = ++settingsRevision;
    settingsTimer = setTimeout(() => {
      call('settings', {settings:state.settings}).then(data => {
        if (revision === settingsRevision && data.settings) { state.settings = {...defaultSettings,...data.settings}; applySettings(); }
      }).catch(showError);
    }, 180);
  }
  // Reading display preferences do not change native playback or its saved position.
  function readerSelectionActive() { const selection=window.getSelection();return !!selection&&!selection.isCollapsed; }
  function syncReaderDisplay() {
    const reading=state.view==='reader'&&!!state.reader;
    document.body.classList.toggle('reader-open',reading);
    const fullscreen=reading&&!!state.reader.fullscreen&&!state.sheet;
    const key=String(reading)+'|'+String(fullscreen);if(key===nativeReaderDisplay)return;
    nativeReaderDisplay=key;
    call('readerDisplay',{reading,fullscreen}).catch(()=>{
      if(nativeReaderDisplay!==key)return;nativeReaderDisplay='';
      if(fullscreen&&state.reader&&state.reader.fullscreen){setReaderFullscreen(false);toast('全画面表示を切り替えられませんでした。',true);}
    });
  }
  function suspendReaderAutoFullscreen(r, duration=240) {
    if(!r||!r.display)return;
    const d=r.display,scroller=$('#reader-scroll');
    d.inputUntil=0;d.travel=0;d.direction=0;d.ignoreUntil=performance.now()+duration;
    d.lastY=scroller?scroller.scrollTop:0;
  }
  function invalidateReaderDisplayAnchor(r) {
    if(!r||!r.display)return;
    r.display.anchorRevision++;
  }
  function setReaderFullscreen(value) {
    const r=state.reader,reader=$('.reader');
    if(!r||state.view!=='reader'||!reader){document.body.classList.remove('reader-fullscreen');syncReaderDisplay();return;}
    value=!!value&&!state.sheet&&!r.restoring&&!r.error&&!busyActive;
    if(r.fullscreen===value){syncReaderDisplay();return;}
    r.fullscreen=value;
    suspendReaderAutoFullscreen(r);
    reader.classList.toggle('is-fullscreen',value);
    document.body.classList.toggle('reader-fullscreen',value);
    $('.reader-reveal',reader).hidden=!value;
    renderSpeechPlayer(false);
    syncReaderDisplay();
  }
  function readerInputIntent(r, direction) {
    if(!r||r.restoring||state.sheet||readerSelectionActive())return;
    const d=r.display;d.inputUntil=performance.now()+1200;invalidateReaderDisplayAnchor(r);
    if(r.fullscreen&&direction<0)d.ignoreUntil=0;
    if(direction&&d.intentDirection!==direction){d.travel=0;d.direction=0;}
    d.intentDirection=direction||0;
  }
  function bindReaderDisplay() {
    const r=state.reader,scroller=$('#reader-scroll');if(!r||!scroller)return;
    const d=r.display;d.lastY=scroller.scrollTop;d.layoutWidth=scroller.clientWidth;
    scroller.addEventListener('wheel',event=>{if(!event.ctrlKey&&event.deltaY)readerInputIntent(r,Math.sign(event.deltaY));},{passive:true});
    scroller.addEventListener('keydown',event=>{
      if(event.key==='Tab'&&r.fullscreen&&event.target===scroller){event.preventDefault();$('.reader-reveal').focus({preventScroll:true});return;}
      if(event.target.closest('input,textarea,select,button,a')||!readerScrollKeys.has(event.key))return;
      const up=['ArrowUp','PageUp','Home'].includes(event.key)||(event.key===' '&&event.shiftKey);readerInputIntent(r,up?-1:1);
    });
    scroller.addEventListener('pointerdown',event=>{
      if(!event.isPrimary||event.button!==0)return;
      invalidateReaderDisplayAnchor(r);
      d.pointer={x:event.clientX,y:event.clientY,lastY:event.clientY,time:performance.now(),moved:false,selected:readerSelectionActive()};d.tapUntil=0;
    },{passive:true});
    scroller.addEventListener('pointermove',event=>{
      const p=d.pointer;if(!p)return;
      if(Math.hypot(event.clientX-p.x,event.clientY-p.y)>8)p.moved=true;
      if(p.moved)readerInputIntent(r,Math.sign(p.lastY-event.clientY));p.lastY=event.clientY;
    },{passive:true});
    scroller.addEventListener('pointerup',()=>{
      const p=d.pointer;d.tapUntil=p&&!p.moved&&!p.selected&&performance.now()-p.time<450&&!readerSelectionActive()?performance.now()+350:0;d.pointer=null;
      scheduleChapterTrim(r,scroller);
    },{passive:true});
    scroller.addEventListener('pointercancel',()=>{d.pointer=null;d.tapUntil=0;},{passive:true});
    scroller.addEventListener('touchstart',event=>{d.touching=true;d.touchY=event.touches.length===1?event.touches[0].clientY:null;},{passive:true});
    scroller.addEventListener('touchmove',event=>{
      if(event.touches.length!==1||d.touchY===null){d.inputUntil=0;d.tapUntil=0;return;}
      const y=event.touches[0].clientY,delta=d.touchY-y;
      if(Math.abs(delta)>2){readerInputIntent(r,Math.sign(delta));if(d.pointer)d.pointer.moved=true;d.touchY=y;}
    },{passive:true});
    for (const type of ['touchend','touchcancel']) scroller.addEventListener(type,event=>{
      d.touching=event.touches.length!==0;d.touchY=null;
      scheduleChapterTrim(r,scroller);
    },{passive:true});
  }
  function updateReaderFullscreenFromScroll(r,scroller) {
    const d=r.display,now=performance.now(),y=scroller.scrollTop,delta=y-d.lastY;d.lastY=y;
    if((!state.settings.autoFullscreen&&!r.fullscreen)||now<d.ignoreUntil||now>d.inputUntil||r.speechFollowing||state.sheet||readerSelectionActive()){d.travel=0;d.direction=0;return;}
    if(Math.abs(delta)<.5)return;
    const direction=Math.sign(delta);if(direction!==d.direction){d.direction=direction;d.travel=0;}d.travel+=Math.abs(delta);
    if(direction>0&&!r.fullscreen&&state.settings.autoFullscreen&&d.travel>=32&&y>36)setReaderFullscreen(true);
    else if(direction<0&&r.fullscreen&&(d.travel>=20||y<=8))setReaderFullscreen(false);
  }
  function revealReaderFromTap(event) {
    const r=state.reader;if(!r||!r.fullscreen||state.sheet||!event.target.closest('#reader-scroll'))return;
    if(event.target.closest('a,button,input,textarea,select,[contenteditable],label')||readerSelectionActive())return;
    if(event.detail===0||performance.now()<r.display.tapUntil){r.display.tapUntil=0;setReaderFullscreen(false);}
  }
  function restoreReaderAfterResize() {
    const r=state.reader, scroller=$('#reader-scroll');
    if (!r || !scroller || r.restoring || r.error) return;
    const d=r.display, width=scroller.clientWidth;
    // A height-only resize leaves line wrapping intact. A scrollTop write here cancels a native fling.
    if (d.layoutWidth===width) return;
    d.layoutWidth=width;
    if (d.stableAnchor && !d.pointer && !d.touching && performance.now()>d.inputUntil)
      restorePosition(d.stableAnchor);
    d.stableAnchor=updateReaderChrome();
    clearTimeout(saveTimer);saveTimer=setTimeout(flushReadingPosition,350);
  }
  function sourceFilters(selected, action, includeLocal = false, includeWuxiaworld = false) {
    return `<div class="filter-row" role="group" aria-label="サイトで絞り込む">${[['all','すべて'],['kakuyomu','カクヨム'],['narou','なろう'],...(includeWuxiaworld ? [['wuxiaworld','Wuxiaworld']] : []),...(includeLocal ? [['local','TXT']] : [])].map(([value,label]) => `<button class="pill${selected === value ? ' active' : ''}" data-action="${action}" data-value="${value}" aria-pressed="${selected === value}">${label}</button>`).join('')}</div>`;
  }
  function wuxiaworldDiscovery() {
    return `<div class="source-discovery"><div class="row between">${badge('wuxiaworld')}<button class="text-button" data-action="original" data-url="${wuxiaworldHome}">${icon('external')}公式サイトで探す</button></div><p>Wuxiaworldの検索は公式サイトで行えます。作品ページを開いたら、本棚に追加できます。</p></div>`;
  }
  function incompleteTocNotice(book) {
    if (book.source !== 'wuxiaworld') return '<div class="loading-state" style="padding:24px 0"><span class="spinner"></span><span>残りの目次を読み込み中…</span></div>';
    return `<div class="note warning toc-incomplete"><p>目次は一部のみ取得済みです。表示されている話から読めます。続きは公式ページで確認するか、作品情報を更新してください。</p><div class="notice-actions"><button class="text-button" data-action="update-book" data-book="${esc(book.id)}">${icon('refresh')}作品情報を更新</button><button class="text-button" data-action="original" data-url="${esc(book.url)}">${icon('external')}公式目次を開く</button></div></div>`;
  }
  function searchForm(value = '', from = 'search') {
    return `<form class="searchbox" data-form="search" data-from="${from}">${icon('search')}<input name="query" type="search" inputmode="search" maxlength="140" autocomplete="off" aria-label="作品名・作者名・キーワード" placeholder="作品名・作者名・キーワード" value="${esc(value)}"><button class="button" type="submit">検索</button></form>`;
  }
  function renderShell(preserveScroll = false) {
    document.body.classList.remove('reader-fullscreen'); syncReaderDisplay(); renderSpeechPlayer(false);
    const scroll = preserveScroll && $('.shell-scroll') ? $('.shell-scroll').scrollTop : 0;
    const nav = [['library','shelf','本棚'],['search','search','検索'],['downloads','download','保存'],['settings','settings','設定']];
    const content = {library:renderLibrary,search:renderSearch,downloads:renderDownloads,settings:renderSettings,cast:renderCastPage,memory:renderMemoryPage}[state.view] || renderLibrary;
    app.innerHTML = `<div class="shell"><header class="brandbar"><div class="brandmark">${icon('book')}</div><span class="brandname">つづき</span><span class="brandmeta">小説リーダー</span><span class="spacer"></span><button class="icon-button subtle" data-action="add-url" aria-label="作品URLを追加">${icon('plus')}</button></header><div class="shell-scroll">${content()}</div><nav class="bottom-nav" aria-label="メインメニュー">${nav.map(([view,name,label]) => `<button class="nav-item${state.view === view ? ' active' : ''}" data-action="nav" data-view="${view}"${state.view === view ? ' aria-current="page"' : ''}><span class="nav-icon">${icon(name)}</span><span>${label}</span></button>`).join('')}</nav></div>`;
    if (scroll) $('.shell-scroll').scrollTop = scroll;
    updateVoicePreview();syncAnalysisClocks();
  }
  function renderLibrary() {
    const books = state.books.filter(book => state.filter === 'all' || book.source === state.filter).sort((a,b) => (b.lastReadAt || b.updatedAt || 0) - (a.lastReadAt || a.updatedAt || 0));
    const resume = (getBook(state.lastBookId) || [...state.books].sort((a,b) => (b.lastReadAt || 0) - (a.lastReadAt || 0))[0]);
    const resumeCard = resume && resume.position && resume.position.chapterUrl ? `<button class="resume-card" data-action="open-book" data-book="${esc(resume.id)}"><div class="row between"><span class="eyebrow">前回のつづき</span>${badge(resume.source)}</div><div class="resume-title">${esc(resume.title)}</div><div class="resume-meta">${esc(resume.author || '')}</div><div class="resume-bottom"><div><div class="small">${positionLabel(resume)}</div><div class="resume-meta">全${knownTotal(resume)}話 · 既読${count(resume.readCount)}話 · 保存${count(resume.downloaded)}話</div></div><span class="continue">続きを読む ${icon('arrow')}</span></div><div class="resume-progress"><span style="width:${readPercent(resume)}%"></span></div></button>` : '';
    return `<section class="page"><div class="page-heading"><div><h1>本棚</h1><p class="page-subtitle">読書位置は作品ごとに自動で保存されます</p></div><div style="text-align:right"><div class="page-number">${count(state.books.length)}</div><span class="page-count">作品</span></div></div>${resumeCard}${searchForm('', 'library')}<p class="search-scope">検索対象：カクヨム・小説家になろう</p>${sourceFilters(state.filter,'library-filter',state.books.some(book => book.source === 'local'),true)}${state.filter === 'wuxiaworld' ? wuxiaworldDiscovery() : ''}${books.length ? `<div class="row between" style="margin:0 0 13px"><h2 style="font-size:14px">${state.filter === 'all' ? 'すべての作品' : esc(sourceName(state.filter))}</h2><span class="book-filter-count">${count(books.length)}作品</span></div><div class="book-grid">${books.map(renderBookCard).join('')}</div>` : `<div class="empty"><div class="empty-icon">${icon('book')}</div><h2>${state.books.length ? 'このサイトの作品はありません' : '最初の一冊を追加'}</h2><p>カクヨム・なろう・Wuxiaworldを同じ本棚へ。<br>小説を探すか、作品URLから追加できます。</p><button class="button" data-action="nav" data-view="search">${icon('search')}小説を探す</button><div class="empty-links"><button class="text-button" data-action="add-url">URLから追加</button><button class="text-button" data-action="import">TXTを読み込む</button></div></div>`}${!state.books.length ? `<div class="source-intro"><button data-action="search-source" data-value="kakuyomu"><strong>カクヨム</strong><small>作品を検索する ${icon('right')}</small></button><button data-action="search-source" data-value="narou"><strong>小説家になろう</strong><small>作品を検索する ${icon('right')}</small></button><button class="source-intro-wide" data-action="original" data-url="${wuxiaworldHome}"><strong>Wuxiaworld</strong><small>公式サイトで探す ${icon('external')}</small></button></div>` : ''}</section>`;
  }
  function renderBookCard(book) {
    return `<article class="book-card"><button class="book-open" data-action="open-book" data-book="${esc(book.id)}"><div class="book-heading">${badge(book.source)}${isFullySaved(book) ? `<span class="offline-dot" title="全話保存済み">${icon('download')}</span>` : ''}</div><h3 class="book-title">${esc(book.title)}</h3><p class="book-author">${esc(book.author || '作者名なし')}</p><div class="book-stats"><span class="book-stat">全<strong>${knownTotal(book)}</strong>話</span><span class="book-stat">既読<strong>${count(book.readCount)}</strong></span><span class="book-stat">保存<strong>${count(book.downloaded)}</strong></span></div></button><div class="book-progress"><span style="width:${readPercent(book)}%"></span></div><div class="book-footer"><span class="book-position">${positionLabel(book)}</span><button class="icon-button subtle" data-action="book-menu" data-book="${esc(book.id)}" aria-label="${esc(book.title)}の管理">${icon('dots')}</button></div></article>`;
  }
  function renderSearch() {
    const s = state.search;
    let results;
    if (s.loading) results = `<div class="loading-state"><span class="spinner"></span><span>${s.source === 'all' ? 'カクヨムとなろうを検索しています' : sourceName(s.source) + 'を検索しています'}</span></div>`;
    else if (s.error) results = `<div class="error-box"><div class="row">${icon('alert')}<span>${esc(s.error.message || s.error)}</span></div><button class="button secondary compact" data-action="search-retry">${icon('refresh')}再検索</button></div>`;
    else if (!s.hasSearched) results = `<div class="empty"><div class="empty-icon">${icon('search')}</div><h2>二つのサイトをまとめて検索</h2><p>タイトル、作者名、キーワードを入力。<br>検索結果からそのまま読み始められます。</p></div><div class="note">作品URLが分かるときは、右上の ＋ から追加できます。ログインは「設定」から行えます。</div>`;
    else results = `${s.warnings.length ? `<div class="note warning" style="margin-bottom:16px">${s.warnings.map(w => esc(typeof w === 'string' ? w : w.message || '')).join('<br>')}</div>` : ''}<p class="search-info">「${esc(s.query)}」 · ${count(s.items.length)}件 · ${s.page}ページ</p>${s.items.length ? `<div class="result-list">${s.items.map((book,index) => `<button class="result-card" data-action="search-result" data-index="${index}"><div class="row">${badge(book.source)}${getBook(book.id) ? '<span class="tag subtle">本棚に追加済み</span>' : ''}</div><h3 class="result-title">${esc(book.title)}</h3><p class="result-author">${esc(book.author || '')}</p>${book.description ? `<p class="result-description">${esc(book.description)}</p>` : ''}<div class="result-footer"><span>${Number(book.total) > 0 ? '全' + count(book.total) + '話' : '作品を開いて話数を確認'}</span><span>読む ${icon('right')}</span></div></button>`).join('')}</div>` : '<div class="inline-empty">作品が見つかりませんでした。<br>キーワードや検索先を変えてお試しください。</div>'}<div class="pager"><button class="button secondary compact" data-action="search-page" data-page="${s.page - 1}" ${s.page <= 1 ? 'disabled' : ''}>${icon('left')}前へ</button><span>${s.page}</span><button class="button secondary compact" data-action="search-page" data-page="${s.page + 1}" ${s.more ? '' : 'disabled'}>次へ${icon('right')}</button></div>`;
    return `<section class="page"><div class="page-heading"><div><h1>小説を探す</h1><p class="page-subtitle">カクヨムと小説家になろうを横断検索</p></div></div>${searchForm(s.query)}${sourceFilters(s.source,'search-filter')}${wuxiaworldDiscovery()}${results}</section>`;
  }
  async function search(query, source = state.search.source, page = 1) {
    if (!['all','kakuyomu','narou'].includes(source)) source = 'all';
    query = String(query || '').trim(); if (!query) { toast('作品名・作者名・キーワードを入力してください'); return; }
    if (state.reader) flushReadingPosition();
    state.view = 'search'; state.reader = null;
    const token = ++state.search.token;
    Object.assign(state.search,{query,source,page,loading:true,hasSearched:true,error:null}); renderShell();
    try {
      const data = await call('search',{query,source,page}); if (token !== state.search.token) return;
      Object.assign(state.search,{items:Array.isArray(data.items) ? data.items : [],more:!!data.more,warnings:Array.isArray(data.warnings) ? data.warnings : [],loading:false,error:null});
    } catch (error) { if (token !== state.search.token) return; Object.assign(state.search,{loading:false,error}); }
    if (state.view === 'search') renderShell();
  }
  function jobFor(id) { return state.downloads.find(job => job.bookId === id); }
  function renderDownloads() {
    const books = state.books.filter(book => book.downloaded > 0 || jobFor(book.id));
    const saved = state.books.reduce((sum,book) => sum + (Number(book.downloaded) || 0),0);
    return `<section class="page"><div class="page-heading"><div><h1>保存した小説</h1><p class="page-subtitle">保存済みの話はオフラインでも読めます</p></div><button class="icon-button subtle" data-action="import" aria-label="TXTを読み込む">${icon('import')}</button></div><div class="note" style="margin-bottom:21px">${count(saved)}話を端末に保存。全話保存はアプリを開いている間に進み、途中から再開できます。TXTは全話保存後に書き出せます。</div>${books.length ? books.map(book => {
      const job = jobFor(book.id) || {};
      const status = isFullySaved(book) ? '全話保存済み' : ({downloading:book.tocComplete === false ? (book.source === 'wuxiaworld' ? '保存中・目次は一部' : '目次を取得中') : '保存中',paused:'一時停止',error:'保存を停止',idle:'一部保存済み'}[job.state] || '一部保存済み');
      const pct = book.total > 0 ? clamp(book.downloaded/book.total*100,0,100) : 0;
      return `<article class="download-card" data-download-book="${esc(book.id)}"><div class="row between">${badge(book.source)}<span class="tag ${isFullySaved(book) ? '' : 'subtle'}">${esc(status)}</span></div><h3 class="download-title">${esc(book.title)}</h3><div class="download-progress"><span style="width:${pct}%"></span></div><div class="download-meta"><span>保存 ${count(book.downloaded)} / ${knownTotal(book)} 話</span><span>既読 ${count(book.readCount)}話</span></div>${job.error ? `<p class="download-error">${esc(job.error)}</p>` : ''}<div class="download-actions"><button class="button secondary compact" data-action="open-book" data-book="${esc(book.id)}">${icon('book')}読む</button>${isFullySaved(book) ? '' : job.state === 'downloading' ? `<button class="button secondary compact" data-action="pause-download" data-book="${esc(book.id)}">${icon('pause')}一時停止</button>` : `<button class="button quiet compact" data-action="download" data-book="${esc(book.id)}">${icon('download')}${book.downloaded ? '残りを保存' : '全話保存'}</button>`}<button class="button ${isFullySaved(book) ? '' : 'secondary'} compact" data-action="export" data-book="${esc(book.id)}">${icon('file')}全編TXT</button>${book.source === 'wuxiaworld' && book.downloaded > 0 ? `<button class="button secondary compact" data-action="export-saved" data-book="${esc(book.id)}">${icon('file')}保存分TXT</button>` : ''}</div></article>`;
    }).join('') : `<div class="empty"><div class="empty-icon">${icon('download')}</div><h2>好きな小説を端末に保存</h2><p>作品のメニューから「全話を保存」を選ぶと、通信なしでつづきを読めます。</p><button class="button" data-action="nav" data-view="library">本棚を開く</button></div>`}<div style="text-align:center;margin-top:16px"><button class="text-button" data-action="import">${icon('import')}手元のTXTファイルを読み込む</button></div></section>`;
  }
  function toggleRow(key,label,hint) { return `<div class="setting-row"><div><div class="setting-label">${label}</div>${hint ? `<p class="setting-hint">${hint}</p>` : ''}</div><button class="switch" role="switch" aria-label="${label}" aria-checked="${!!state.settings[key]}" data-action="toggle-setting" data-key="${key}"></button></div>`; }
  function appearanceSettings() {
    const s = state.settings;
    return `<div class="theme-options" aria-label="配色">${[['paper','紙色','sun'],['white','白','sun'],['night','夜','moon']].map(([value,label,name]) => `<button class="theme-choice${s.theme === value ? ' selected' : ''}" data-action="theme" data-value="${value}" aria-pressed="${s.theme === value}">${icon(name)}${label}</button>`).join('')}</div><div class="range-row"><label>文字サイズ<output data-setting-output="fontSize">${s.fontSize}px</output></label><input type="range" min="14" max="34" step="1" value="${s.fontSize}" data-setting="fontSize" aria-label="文字サイズ"></div><div class="range-row"><label>行間<output data-setting-output="lineHeight">${Number(s.lineHeight).toFixed(1)}</output></label><input type="range" min="1.4" max="2.6" step="0.1" value="${s.lineHeight}" data-setting="lineHeight" aria-label="行間"></div><div class="setting-row"><span class="setting-label">書体</span><div class="segmented"><button class="${s.fontFamily === 'serif' ? 'active' : ''}" data-action="font" data-value="serif">明朝</button><button class="${s.fontFamily === 'sans' ? 'active' : ''}" data-action="font" data-value="sans">ゴシック</button></div></div>${toggleRow('showRuby','ルビを表示','ふりがなのある作品に適用')}${toggleRow('showPreface','前書き・後書きを表示','本文と分けて表示します')}`;
  }
  function readerDisplaySettings() {
    return toggleRow('autoFullscreen','下スクロールで全画面表示','上へスクロール・本文をタップ・戻る操作でバーを表示')+toggleRow('showSpeechPlayer','読み上げコントローラーを表示','画面下の表示だけを切り替えます。音声は「聴く」から操作できます');
  }
  function renderSettings() {
    return `<section class="page"><div class="page-heading"><div><h1>設定</h1><p class="page-subtitle">読みやすい表示に調整</p></div></div><div class="settings-columns"><div><h2 style="font-size:14px">読書画面</h2><div class="settings-group">${appearanceSettings()}</div><h2 style="font-size:14px">操作バーと全画面</h2><div class="settings-group">${readerDisplaySettings()}</div></div><div><h2 style="font-size:14px">読むときの動作</h2><div class="settings-group">${toggleRow('autoNext','次の話を自動で読み込む','末尾へ近づくと次話をつなげます')}${toggleRow('resumeOnLaunch','前回のつづきから起動','作品ごとの位置は常に自動保存')}${toggleRow('keepScreenOn','画面を消灯しない','アプリを開いている間に適用')}</div><h2 style="font-size:14px">読み上げ</h2><div class="settings-group"><button class="menu-item" data-action="speech-settings">${icon('headphones')}<span>音声・AI・ChatGPTログイン</span><span class="right">${icon('right')}</span></button></div><h2 style="font-size:14px">アカウント</h2><div class="settings-group"><button class="menu-item" data-action="login" data-source="kakuyomu">${icon('login')}<span>カクヨムにログイン</span><span class="right">公式画面 ${icon('right')}</span></button><button class="menu-item" data-action="login" data-source="narou">${icon('login')}<span>なろうにログイン</span><span class="right">公式画面 ${icon('right')}</span></button><button class="menu-item" data-action="login" data-source="wuxiaworld">${icon('login')}<span>Wuxiaworldにログイン</span><span class="right">公式画面 ${icon('right')}</span></button></div><p class="note">ログイン情報は各サイトの公式画面で入力します。本棚・読書位置・保存した本文はこの端末に保持されます。</p><div class="section-heading"><h2>ファイル</h2></div><button class="button secondary full" data-action="import">${icon('import')}TXTファイルを読み込む</button></div></div><div class="divider"></div><div class="about">つづき 1.12.2 · 個人用<br>カクヨム / 小説家になろう / Wuxiaworld 統合リーダー</div></section>`;
  }
  // Speech stays in the native service; this local UI never receives stored credentials.
  const speechDefaults = {engine:'offline',speed:1,sleepMinutes:0,autoNext:true,followText:true,speechProvider:'voicevox',offlineDefaultVoice:'metan',dialogueMode:'natural',deviceVoice:'',analysisProvider:'chatgpt',analysisModel:'gpt-6-luna',apiAnalysisModel:'gpt-6-luna',detailedActing:true,useStoryMemory:true,hasApiKey:false};
  const offlinePackDefaults={status:'not_installed',ready:false,downloadedBytes:0,totalBytes:0,voiceCount:10,error:''};
  let speechOpenSequence=0,speechEventSequence=0,chatgptRevision=0,speechRangeRevision=0,speechRangeStarting=false,speechAnalysisStarting=false,speechConsentLoading=false,offlinePackRevision=0,offlinePackPending=false;
  const chatgptDefaults={status:'signed_out',busy:false,error:'',accounts:[],activeAccountId:'',models:[],selectedModel:'',firstConnection:false};
  let chatgpt={...chatgptDefaults},chatgptStarting=false;
  const speech = {settings:{...speechDefaults},playback:{status:'idle'},context:null,plan:null,cast:[],position:{},voices:[],voiceCatalog:[],offlinePacks:{...offlinePackDefaults},voicesLoaded:false,followSuspendedUntil:0,followPlayback:false};
  const speechCastByBook=new Map();
  const speechRangesByBook=new Map(),speechRangeDrafts=new Map(),speechRangePausing=new Set();
  const castDrafts=new Map(),memoryDrafts=new Map(),pronunciationDrafts=new Map();
  let pronunciationOpenSequence=0;
  const castTierLabels={narrator:'語り手',main:'主要人物',support:'脇役',extra:'その他'};
  const castGenderLabels={neutral:'指定なし',female:'女性的',male:'男性的'};
  const dialogueModes = {natural:'自然な会話',anime:'アニメ調',standard:'標準朗読'};
  const kindLabels = {narration:'地の文',dialogue:'台詞',thought:'心の声'};
  const emotionLabels = {neutral:'自然',joy:'喜び',sadness:'悲しみ',anger:'怒り',fear:'不安',surprise:'驚き',calm:'落ち着き',tension:'緊張',whisper:'ささやき'};
  const performanceEmotionLabels = {neutral:'自然',joy:'喜び',sadness:'悲しみ',anger:'怒り',fear:'不安',surprise:'驚き',calm:'落ち着き',tension:'緊張',relief:'安堵',determination:'決意',embarrassment:'照れ',sarcasm:'皮肉'};
  const deliveryLabels = {natural:'自然',soft:'やわらかく',whisper:'ささやく',firm:'はっきり',urgent:'切迫',hesitant:'ためらう'};
  const endingLabels = {auto:'自動',rise:'上げる',fall:'下げる',hold:'保つ',cut:'短く切る'};
  const actingNumberFields = {intensity:['感情の強さ',0,1,.05],rate:['速さ',.7,1.4,.05],pitch:['声の高さ',.8,1.2,.05],intonation:['抑揚',.6,1.6,.05],volume:['音量',.75,1.2,.05],pauseAfterMs:['後ろの間（ms）',0,1200,10],emphasis:['強調量',0,1,.05]};
  // Native validates the source hash and phrase boundaries again before saving or playback.
  // Here only structurally complete plans can expose editable phrase controls.
  function unitPerformance(unit) {
    const p=unit&&unit.performance,text=unit&&(typeof unit.spokenText==='string'?unit.spokenText:String(unit.text||''));
    if(!p||p.version!==1||typeof p.sourceHash!=='string'||!p.sourceHash||typeof p.reviewed!=='boolean'||!text||!Array.isArray(p.parts)||!p.parts.length)return null;
    let end=0;const ids=new Set();
    for(const part of p.parts){
      if(!part||typeof part.partId!=='string'||!part.partId||ids.has(part.partId)||!Number.isInteger(part.start)||!Number.isInteger(part.end)||part.start!==end||part.end<=part.start||part.end>text.length)return null;
      if(!Object.hasOwn(performanceEmotionLabels,part.emotion)||!Object.hasOwn(deliveryLabels,part.delivery)||!Object.hasOwn(endingLabels,part.ending))return null;
      if(Object.entries(actingNumberFields).some(([key,[,min,max]])=>!Number.isFinite(part[key])||part[key]<min||part[key]>max)||!Number.isFinite(part.confidence)||part.confidence<0||part.confidence>1)return null;
      ids.add(part.partId);end=part.end;
    }
    return end===text.length?p:null;
  }
  function needsDetailedActing(plan=speech.plan) {return speech.settings.detailedActing!==false&&!!plan&&(plan.units||[]).some(u=>!u.locked&&!unitPerformance(u));}
  function needsStoryMemory(plan=speech.plan) {return speech.settings.detailedActing!==false&&speech.settings.useStoryMemory!==false&&!!plan&&plan.memoryAssessed!==true;}
  function needsPronunciation(plan=speech.plan) {return !!plan&&((Number.isSafeInteger(plan.missingPronunciationUnits)&&plan.missingPronunciationUnits>0)||(Number.isSafeInteger(plan.missingWordUnits)&&plan.missingWordUnits>0));}
  function pronunciationStatus(unit) {return unit?.pronunciationReady===true?(unit.pronunciationLocked===true?'読みを手動固定':'読み解析済み'):'読み未解析';}
  function pronunciationProgressHtml(plan) {
    if(!plan||![plan.pronunciationUnits,plan.missingPronunciationUnits,plan.pronunciationTotalUnits].every(n=>Number.isSafeInteger(n)&&n>=0))return '';
    return '<p class="speech-pronunciation-progress" data-pronunciation-progress>漢字・助詞の読み <strong>'+count(plan.pronunciationUnits)+' / '+count(plan.pronunciationTotalUnits)+'区間</strong>'+(plan.missingPronunciationUnits>0?' · 未準備 '+count(plan.missingPronunciationUnits)+'区間':' · 準備済み')+(plan.missingWordUnits>0?' · 単語の演技を追加できる区間 '+count(plan.missingWordUnits)+'件':'')+'</p>';
  }
  function pronunciationConsentHtml(preview,resume=false) {
    if(preview?.pronunciationVersion!==1)return resume?'<p class="speech-footnote" data-pronunciation-consent>この保存済み処理は、開始時の読み変換なしの方式で再開します。漢字・助詞の読みの追加は、この処理を終えてから新しく解析してください。</p>':'';
    return '<div class="pronunciation-consent" data-pronunciation-consent><strong>漢字・助詞の読みも解析</strong><p>話者・演技を新しく解析する場合は、音声用の読みも同じGPT要求で作ります。たとえば「私は」を「ワタシワ」と読むための表記です。同じ要求で、文脈に応じて単語の強調・やわらかさ・ためらい・言い切りも指定します。単語の演技は「オフラインのキャラ音声」で聴けます。</p><p>本文と作者のルビは保持します。保存済みの配役・演技がある区間は、その結果を使って不足する読みと単語の演技を追加します。固有名詞や文脈で読みを誤ることがあるため、区間ごとに確認・修正できます。手動固定した読みは保持します。</p></div>';
  }
  function actingAnalysisDescription(detailed=speech.settings.detailedActing!==false,legacy=false,useMemory=speech.settings.useStoryMemory!==false) {
    if(!detailed)return '<div class="acting-analysis-note"><strong>標準の演技解析</strong><p>区間ごとに話者・感情・速さ・声の高さを解析します。作品メモの準備は行いません。保存済みの細かい演技と手動固定した区間は保持します。</p></div>';
    if(legacy&&detailed)return '<div class="acting-analysis-note"><strong>保存した精密解析 · 旧方式</strong><p>開始時の2段階処理で再開し、演技案を作ってから再確認します。保存済みの結果と手動固定した区間を保持します。</p></div>';
    return '<div class="acting-analysis-note"><strong>一話をまとめて解析</strong><p>一話の本文'+(useMemory?'・作品メモ':'')+'・配役をまとめて渡し、'+(detailed?'短い句ごとの感情・発声・速さ・高さ・抑揚・音量・間・語尾を決めます。':'区間ごとの話者・感情・速さ・声の高さを決めます。')+'大きすぎる話は場面単位に分けます。</p><p>話者や感情を取り違える場合があります。解析の正確さや、音声エンジンで指定どおりに演じられることを保証する機能ではありません。</p></div>';
  }
  function measuredDuration(value) {return value<1000?Math.round(value)+' ms':value<60000?(value/1000).toFixed(1)+'秒':Math.floor(value/60000)+'分'+Math.floor(value%60000/1000)+'秒';}
  function analysisMetricsHtml(metrics,kind='current') {
    if(!metrics||typeof metrics!=='object')return '';
    const fields=[['requestCount','GPTリクエスト'],['modelMs','GPT応答時間'],['validationMs','応答の検証'],['elapsedMs','解析の経過']],rows=[];
    for(const [key,label]of fields){const value=metrics[key];if(typeof value!=='number'||!Number.isFinite(value)||value<0)continue;if(key==='requestCount'&&!Number.isInteger(value))continue;rows.push('<div><dt>'+label+'</dt><dd>'+(key==='requestCount'?count(value)+'回':measuredDuration(value))+'</dd></div>');}
    return rows.length?'<details class="speech-metrics" data-analysis-metrics data-metrics-kind="'+kind+'"><summary>'+(kind==='memory'?'作品メモ準備の計測':'この話の今回の解析')+'</summary><dl>'+rows.join('')+'</dl><p>'+(kind==='memory'?'保存した処理内の作品メモ準備の累計です。再開した分も含みます。':'今の話の開始・再開ごとの記録値です。範囲全体の累計ではありません。')+'通信待ちと検証を含むため、GPT応答時間と経過時間は異なる場合があります。</p></details>':'';
  }
  function audioProgressHtml(playback) {
    const rows=[];for(const [key,label]of [['firstAudioMs','最初の音声準備'],['generationElapsedMs','この区間の生成時間']]){const value=playback[key];if(typeof value==='number'&&Number.isFinite(value)&&value>=0)rows.push('<span>'+label+' <strong>'+measuredDuration(value)+'</strong></span>');}
    if(Number.isInteger(playback.bufferedUnits)&&playback.bufferedUnits>=0&&playback.bufferedUnits<=3)rows.push('<span>先読み済み <strong>'+playback.bufferedUnits+'区間</strong></span>');
    const generating=playback.audioGenerating===true,streaming=playback.audioStreaming===true;
    if(!rows.length&&!generating&&!streaming)return '';
    return '<div class="speech-audio-progress" data-audio-progress><p>'+(generating?(playback.status==='loading'?'次の音声データを準備中':'再生しながら次の音声データを生成中'):streaming?'準備できた音声から再生':'音声の準備状況')+'</p><div>'+rows.join('')+'</div>'+(rows.some(row=>row.includes('最初の音声準備'))?'<small>最初の音声データが完成するまでの時間です。スピーカーから音が出るまでの実測ではありません。</small>':'')+'</div>';
  }
  const speechStatus = {idle:'停止中',loading:'音声を準備中',playing:'読み上げ中',paused:'一時停止',error:'読み上げを停止'};
  const optionsHtml = (items,selected) => items.map(item => '<option value="'+esc(Array.isArray(item)?item[0]:item)+'"'+(String(Array.isArray(item)?item[0]:item)===String(selected)?' selected':'')+'>'+esc(Array.isArray(item)?item[1]:item)+'</option>').join('');
  function speechContext(preferPlayback = false) {
    if (preferPlayback && speech.playback.bookId && speech.playback.chapterUrl) return {bookId:speech.playback.bookId,chapterUrl:speech.playback.chapterUrl};
    const r=state.reader, segment=currentSegment();
    if (r) return {bookId:r.book.id,chapterUrl:segment?segment.chapter.url:r.currentUrl};
    if (speech.playback.bookId && speech.playback.chapterUrl) return {bookId:speech.playback.bookId,chapterUrl:speech.playback.chapterUrl};
    return speech.context;
  }
  function applySpeechBundle(bundle,context,revision=speechEventSequence,authRevision=chatgptRevision,rangeRevision=speechRangeRevision,packRevision=offlinePackRevision) {
    if (!bundle) return;
    if (bundle.settings) speech.settings={...speechDefaults,...bundle.settings};
    if (bundle.chatgpt&&authRevision===chatgptRevision) applyChatgptState(bundle.chatgpt);
    if (Array.isArray(bundle.voiceCatalog)) speech.voiceCatalog=bundle.voiceCatalog;
    if (bundle.offlinePacks&&packRevision===offlinePackRevision) applyOfflinePacks(bundle.offlinePacks);
    if (bundle.state&&revision===speechEventSequence) speech.playback=bundle.state;
    if ('analysisRange' in bundle&&rangeRevision===speechRangeRevision) rememberSpeechRange(bundle.analysisRange,context&&context.bookId);
    if (context) {speech.context={...context};speech.plan=bundle.plan||null;speech.cast=Array.isArray(bundle.cast)?bundle.cast:[];speechCastByBook.set(context.bookId,speech.cast);speech.position=bundle.position||{};}
    renderSpeechPlayer();
  }
  async function refreshSpeech(context,prepare=false) {
    const revision=speechEventSequence,authRevision=chatgptRevision,rangeRevision=speechRangeRevision,packRevision=offlinePackRevision;const data=await call(prepare?'speechPrepare':'speechState',context||{});
    applySpeechBundle(data,context,revision,authRevision,rangeRevision,packRevision);return data;
  }
  function speechSpeaker(id,bookId=speech.context&&speech.context.bookId) {return ((speechCastByBook.get(bookId)||(speech.context&&speech.context.bookId===bookId?speech.cast:[])).find(c=>c.id===id)||{}).name||({narrator:'語り手',unknown:'話者未確定'}[id])||'話者未確定';}
  function offlineVoiceForCast(c) {return c.offlineVoice||speech.settings.offlineDefaultVoice||'metan';}
  function offlineVoiceName(id) {const voice=speech.voiceCatalog.find(v=>v.id===id);return voice?(voice.name||voice.label||id):id;}
  function offlineVoiceOptions(value) {
    const selected=value||speech.settings.offlineDefaultVoice||'metan';
    const choices=speech.voiceCatalog.map(v=>[v.id,v.label||((v.name||v.id)+(v.description?' · '+v.description:''))]);
    if(!choices.some(v=>v[0]===selected))choices.push([selected,selected+'（現在の設定）']);
    return optionsHtml(choices,selected);
  }
  function formatVoiceBytes(value) {const bytes=Math.max(0,Number(value)||0);return bytes>=1000000000?(bytes/1000000000).toFixed(2)+' GB':(bytes/1000000).toFixed(1)+' MB';}
  function offlinePackStatusHtml() {
    const p=speech.offlinePacks,status=p.ready?'ready':p.status||'not_installed',active=['downloading','verifying'].includes(status),total=Math.max(0,Number(p.totalBytes)||0),done=Math.max(0,Number(p.downloadedBytes)||0);
    const percent=total?Math.min(100,Math.floor(done/total*100)):0;
    const labels={not_installed:'初回ダウンロードが必要です',downloading:'音声データをダウンロード中',verifying:'音声データを確認中',ready:'オフラインで読み上げできます',paused:'ダウンロードを中断しました',error:'ダウンロードできませんでした'};
    const size=total?formatVoiceBytes(done)+' / '+formatVoiceBytes(total):'容量を確認中';
    return `<div class="offline-pack-card ${status==='ready'?'is-ready':''}" data-pack-state="${esc(status)}"><div class="offline-pack-heading">${icon(status==='ready'?'check':active?'download':'headphones')}<div><strong>${labels[status]||'音声データを確認してください'}</strong><span>${count(p.voiceCount||10)}種類のキャラ音声 · ${status==='ready'?(p.installedBytes?'使用容量 '+formatVoiceBytes(p.installedBytes):total?'取得データ '+formatVoiceBytes(total):'準備済み'):size}</span></div></div>${active?`<div class="offline-pack-progress"><progress max="100"${total?` value="${percent}"`:''} aria-label="音声データのダウンロード"></progress><span>${status==='verifying'?'確認中':total?percent+'%':'準備中'}</span></div>`:''}${status==='error'&&p.error?`<p class="download-error" role="status">${esc(p.error)}</p>`:''}${status!=='ready'?`<p class="speech-footnote">初回だけ通信が必要です。約${formatVoiceBytes(p.requiredFreeBytes||800000000)}以上の空き容量を目安にしてください。準備後は、保存した本文をこの端末で音声にします。</p><button type="button" class="button ${active?'secondary':''} full" data-action="${active?'speech-offline-cancel':'speech-offline-install'}"${offlinePackPending?' disabled':''}>${icon(active?'pause':'download')}${offlinePackPending?'処理中…':active?'ダウンロードを中断':status==='paused'?'ダウンロードを続ける':status==='error'?'ダウンロードを再試行':'音声データをダウンロード'}</button>`:`<p class="speech-footnote">新しい文章も通信なしで音声にできます。GPTで解析済みの配役と抑揚も、端末に保存して使います。</p>`}</div>`;
  }
  function applyOfflinePacks(value) {
    if(!value||typeof value!=='object')return;
    speech.offlinePacks={...offlinePackDefaults,...value};offlinePackRevision++;updateOfflinePackView();
  }
  function updateOfflinePackView() {
    for(const host of $$('[data-offline-status]'))host.innerHTML=offlinePackStatusHtml();
    for(const button of $$('[data-action="speech-offline-preview"],[data-action="speech-acting-preview"]'))button.disabled=!speech.offlinePacks.ready;
    for(const host of $$('[data-offline-required]'))host.hidden=!!speech.offlinePacks.ready||speech.settings.engine!=='offline';
  }
  function offlineCatalogHtml() {
    const cards=speech.voiceCatalog.map(v=>`<article class="offline-voice-card" data-offline-voice="${esc(v.id)}"><div><h4>${esc(v.name||v.label||v.id)}</h4><p>${esc(v.description||'キャラクター音声')}</p>${Array.isArray(v.styles)&&v.styles.length?`<span class="offline-voice-styles">${esc(v.styles.map(style=>style.name).filter(Boolean).join(' / '))}</span>`:''}</div><button type="button" class="button secondary compact" data-action="speech-offline-preview" data-voice="${esc(v.id)}" aria-label="${esc(v.name||v.id)}の声を試聴"${speech.offlinePacks.ready?'':' disabled'}>${icon('play')}試聴</button></article>`).join('');
    return `<div class="offline-voice-catalog">${cards||'<p class="speech-footnote">音声一覧を読み込めませんでした。設定を開き直してください。</p>'}</div>`;
  }
  function updateVoicePreview() {
    const p=speech.playback,active=!!p.preview&&p.status!=='idle';
    const voice=p.offlineVoice||(p.engine==='offline'?p.voice:'');
    const mode = dialogueModes[p.auditionMode] ? dialogueModes[p.auditionMode]+' · ' : '';
    for(const host of $$('[data-voice-preview-status]')){
      host.hidden=!active;
      if(active)host.innerHTML=`<span>${esc(p.status==='error'?p.error||'試聴を停止しました':mode+(voice?offlineVoiceName(voice)+' · ':'')+(speechStatus[p.status]||'声の試聴'))}</span><button type="button" class="text-button" data-action="speech-control" data-command="stop">${icon('stop')}試聴を停止</button>`;
    }
  }
  async function offlinePackAction(action) {
    if(offlinePackPending)return;offlinePackPending=true;updateOfflinePackView();
    const revision=offlinePackRevision;
    try {const result=await call(action);if(revision===offlinePackRevision)applyOfflinePacks(result.offlinePacks||result);}
    finally {offlinePackPending=false;updateOfflinePackView();}
  }
  async function previewOfflineVoice(id,sceneMode,auditionScene) {
    if(!speech.offlinePacks.ready){toast('音声設定で初回の音声データをダウンロードしてください。',true);return;}
    await speechControl('preview',{bookId:'',chapterUrl:'',speakerId:'',engine:'offline',offlineVoice:id,...(Object.prototype.hasOwnProperty.call(dialogueModes,sceneMode)?{sceneAudition:true,dialogueMode:sceneMode,auditionScene}:{})});
  }
  function currentSpeechUnit() {const units=speech.plan&&speech.plan.units||[];return units[clamp(speech.playback.unitIndex,0,Math.max(0,units.length-1))]||null;}
  function renderSpeechPlayer(follow = true) {
    updateVoicePreview();const host=$('#speech-player-host');if(!host)return;
    const p=speech.playback, active=state.settings.showSpeechPlayer===true&&!!(p.bookId||p.preview)&&p.status!=='idle';
    const visible=active&&!(state.view==='reader'&&state.reader&&state.reader.fullscreen);
    document.body.classList.toggle('reader-player-space',active);
    document.body.classList.toggle('has-speech-player',visible);host.hidden=!visible;
    if (!visible) {host.replaceChildren();updateSpeechPanel();highlightSpeech(follow);return;}
    if(p.preview){host.innerHTML='<div class="speech-mini"><div class="speech-mini-info"><span class="speech-mini-status">'+(p.engine==='offline'?'キャラ音声':'端末音声')+' · '+esc(speechStatus[p.status]||'試聴')+'</span><strong>声の試聴</strong><span>小説の再開位置は変更しません</span></div><button class="icon-button" data-action="speech-control" data-command="stop" aria-label="試聴を停止">'+icon('stop')+'</button><button class="icon-button speech-mini-hide" data-action="reader-hide-player" aria-label="読み上げコントローラーを隠す">'+icon('close')+'</button></div>';updateSpeechPanel();highlightSpeech(follow);return;}
    const book=getBook(p.bookId),playing=p.status==='playing'||p.status==='loading';
    const label=speechStatus[p.status]||'読み上げ';
    host.innerHTML='<div class="speech-mini"><button class="speech-mini-info" data-action="speech-open-player" aria-label="読み上げの詳細"><span class="speech-mini-status">'+(p.engine==='offline'?'キャラ音声':'端末音声')+' · '+esc(label)+'</span><strong>'+esc(book?book.title:'読み上げ')+'</strong><span>'+esc(speechSpeaker(p.speakerId,p.bookId))+' · '+count(Number(p.unitIndex||0)+1)+' / '+count(p.unitCount)+'区間</span></button><button class="icon-button" data-action="speech-control" data-command="previous" aria-label="前の区間">'+icon('previous')+'</button><button class="speech-mini-play" data-action="speech-control" data-command="'+(playing?'pause':'resume')+'" aria-label="'+(playing?'読み上げを一時停止':'読み上げを再開')+'">'+icon(playing?'pause':'play')+'</button><button class="icon-button" data-action="speech-control" data-command="next" aria-label="次の区間">'+icon('next')+'</button><button class="icon-button speech-mini-hide" data-action="reader-hide-player" aria-label="読み上げコントローラーを隠す">'+icon('close')+'</button></div>';
    updateSpeechPanel();highlightSpeech(follow);
  }
  function highlightSpeech(follow = true) {
    $$('.novel-paragraph.is-speaking').forEach(p=>p.classList.remove('is-speaking'));
    const r=state.reader,p=speech.playback;if(!r||p.bookId!==r.book.id||!['playing','paused','loading'].includes(p.status))return;
    const segment=r.segments.find(s=>s.chapter.url===p.chapterUrl),paragraph=segment&&segment.paragraphElements[Number(p.paragraph)];
    if(!paragraph)return;paragraph.classList.add('is-speaking');
    if(!follow||!speech.settings.followText||p.status!=='playing'||Date.now()<speech.followSuspendedUntil||state.sheet)return;
    const scroller=$('#reader-scroll'),rect=paragraph.getBoundingClientRect(),view=scroller.getBoundingClientRect();
    const top=r.fullscreen?view.top:readingTop(), bottom=r.fullscreen?view.bottom:$('.reader-bottom').getBoundingClientRect().top;
    if(rect.top<top+20||rect.bottom>bottom-100){r.speechFollowing=true;invalidateReaderDisplayAnchor(r);suspendReaderAutoFullscreen(r);scroller.scrollTop+=rect.top-top-30;r.display.stableAnchor=readingPosition();}
  }
  function speechPanelHtml() {
    const p=speech.playback,plan=speech.plan||{},units=Array.isArray(plan.units)?plan.units:[],same=!!speech.context&&p.bookId===speech.context.bookId&&p.chapterUrl===speech.context.chapterUrl;
    const unit=same?units[p.unitIndex]:null,playing=same&&['playing','loading'].includes(p.status);
    const savedHere=speech.context&&speech.position.chapterUrl===speech.context.chapterUrl;
    const resume=savedHere?Number(speech.position.unitIndex)||0:0;
    const otherResume=speech.position.chapterUrl&&speech.context&&speech.position.chapterUrl!==speech.context.chapterUrl;
    return '<div class="speech-now"><div class="row between"><span class="tag">'+(speech.settings.engine==='offline'?'キャラ音声':'端末音声')+'</span><span class="small muted">'+esc(same?speechStatus[p.status]||'停止中':'停止中')+'</span></div><h3>'+esc(unit?speechSpeaker(unit.speakerId):'この話を聴く')+'</h3><p class="speech-now-meta">'+(unit?esc(kindLabels[unit.kind]||'地の文')+' · '+esc(emotionLabels[unit.emotion]||unit.emotion||'自然')+' · '+count(Number(p.unitIndex)+1)+' / ':count(units.length)+'区間 · ') +(unit?count(units.length)+'区間':'再開位置 '+count(resume+1))+'</p><p class="speech-current-text">'+esc(unit?unit.text:plan.title||'声と音声データは音声設定で選べます。')+'</p>'+((same&&p.error)?'<p class="download-error">'+esc(p.error)+'</p>':'')+'<div class="speech-transport"><button class="icon-button" data-action="speech-control" data-command="previous" aria-label="前の区間"'+(!same?' disabled':'')+'>'+icon('previous')+'</button><button class="button speech-main-play" data-action="'+(same&&p.status!=='idle'?'speech-control':'speech-start')+'" '+(same&&p.status!=='idle'?'data-command="'+(playing?'pause':'resume')+'"':'data-from="resume"')+'>'+icon(playing?'pause':'play')+(playing?'一時停止':same&&p.status==='paused'?'再開':savedHere?'つづきから聴く':'この話を聴く')+'</button><button class="icon-button" data-action="speech-control" data-command="next" aria-label="次の区間"'+(!same?' disabled':'')+'>'+icon('next')+'</button></div><div class="speech-small-actions"><button class="text-button" data-action="speech-start" data-from="here">表示中の段落から</button><button class="text-button" data-action="speech-start" data-from="start">話の先頭から</button>'+(otherResume?'<button class="text-button" data-action="speech-resume-book">前回聴いた話の続き</button>':'')+((same&&p.status!=='idle')?'<button class="text-button" data-action="speech-control" data-command="stop">停止</button>':'')+'</div>'+(same?audioProgressHtml(p):'')+'</div>';
  }
  function updateSpeechPanel() {
    const panel=$('[data-speech-panel]');if(panel&&state.sheet&&state.sheet.kind==='speech')panel.innerHTML=speechPanelHtml();
    const status=$('[data-analysis-status]');
    if(status)updateAnalysisRegion(status,analysisStatusHtml());
    updateSpeechRangePanel();
  }
  function updateAnalysisRegion(target,html) {
    if(target.__analysisHtml===html)return;
    const previous=$('[data-analysis-diagnostics]',target),wasOpen=!!previous&&previous.open,previousText=previous&&$('textarea',previous)?.value,metricsOpen=new Set($$('[data-analysis-metrics][open]',target).map(el=>el.dataset.metricsKind||'current'));
    target.innerHTML=html;target.__analysisHtml=html;
    const next=$('[data-analysis-diagnostics]',target);
    if(wasOpen&&next&&$('textarea',next)?.value===previousText)next.open=true;for(const metrics of $$('[data-analysis-metrics]',target))if(metricsOpen.has(metrics.dataset.metricsKind||'current'))metrics.open=true;
    syncAnalysisClocks();
  }
  const analysisActivityLabels={loading:'本文を取得中',preparing:'解析の準備中',authorizing:'GPTへの接続を確認中',waiting:'GPTの応答待ち',validating:'応答の内容を検証中',saving:'結果を保存中',complete:'処理完了',paused:'処理を中断',error:'処理を停止'};
  function activityNumber(value) {return typeof value==='number'&&Number.isSafeInteger(value)&&value>=0?value:0;}
  function analysisActivityView(owner) {
    const raw=owner?.analysisActivity;if(!raw||typeof raw!=='object'||Array.isArray(raw)||!Object.hasOwn(analysisActivityLabels,raw.stage)||!['history','chapter'].includes(raw.scope))return null;
    const status=owner.analysisStatus||owner.status,terminal=['paused','error','complete'].includes(raw.stage)?raw.stage:['paused','error'].includes(status)?status:'',stage=terminal||raw.stage;
    const started=activityNumber(raw.stageStartedAt),runStarted=activityNumber(raw.runStartedAt),request=activityNumber(raw.requestNumber),from=activityNumber(raw.chapterFromNumber),to=Math.max(from,activityNumber(raw.chapterToNumber));
    let ended=activityNumber(raw.endedAt);
    if(terminal&&!ended){const key=[runStarted,started,raw.scope,stage,request,from,to].join('|');if(!analysisClockStops.has(key)){analysisClockStops.set(key,activityNumber(owner.updatedAt)||Date.now());if(analysisClockStops.size>100)analysisClockStops.delete(analysisClockStops.keys().next().value);}ended=analysisClockStops.get(key);}
    return{stage,scope:raw.scope,started,runStarted,ended,request,from,to,units:activityNumber(raw.targetUnits),chapters:activityNumber(raw.targetChapters)};
  }
  function elapsedLabel(start,end) {
    const seconds=Math.max(0,Math.floor(((end||Date.now())-start)/1000)),minutes=Math.floor(seconds/60),hours=Math.floor(minutes/60);
    return hours?count(hours)+'時間 '+count(minutes%60)+'分 '+count(seconds%60)+'秒':minutes?count(minutes)+'分 '+count(seconds%60)+'秒':count(seconds)+'秒';
  }
  function analysisClockHtml(start,end,kind) {return start>0?'<strong data-analysis-elapsed data-clock-kind="'+kind+'" data-clock-start="'+start+'" data-clock-end="'+end+'">'+(end?elapsedLabel(start,end):'0秒')+'</strong>':'';}
  function tickAnalysisClocks() {
    let active=false;
    for(const field of $$('[data-analysis-elapsed]')){const start=Number(field.dataset.clockStart),end=Number(field.dataset.clockEnd);if(!(start>0))continue;const label=elapsedLabel(start,end);if(field.textContent!==label)field.textContent=label;if(!end)active=true;}
    if((!active||document.hidden)&&analysisClockTimer){clearInterval(analysisClockTimer);analysisClockTimer=0;}
    return active;
  }
  function syncAnalysisClocks() {if(tickAnalysisClocks()&&!document.hidden&&!analysisClockTimer)analysisClockTimer=setInterval(tickAnalysisClocks,1000);}
  function revealAnalysisProgress(range=false) {const host=$('.sheet-content'),target=$(range?'[data-speech-range-status]':'[data-analysis-status]');if(host&&target)host.scrollTop+=target.getBoundingClientRect().top-host.getBoundingClientRect().top-12;}
  function analysisActivityHtml(owner) {
    const a=analysisActivityView(owner);if(!a)return '';
    const target=a.from?'第'+count(a.from)+(a.to>a.from?'〜'+count(a.to):'')+'話':'対象話を確認中',size=[a.chapters?count(a.chapters)+'話':'',a.units?count(a.units)+'区間':''].filter(Boolean).join(' · ');
    const stageClock=analysisClockHtml(a.started,a.ended,'stage'),runClock=analysisClockHtml(a.runStarted,a.ended,'run');
    return '<section class="analysis-activity" data-analysis-activity data-activity-stage="'+a.stage+'"><div class="analysis-activity-heading"><strong data-analysis-activity-label>'+analysisActivityLabels[a.stage]+'</strong><span class="tag subtle">'+(a.scope==='history'?'作品メモ':'話の解析')+'</span></div><p class="analysis-activity-target" data-analysis-target>対象：'+target+(size?' · '+size:'')+'</p><p class="analysis-activity-requests">記録済みのGPT要求 '+count(a.request)+'回<small>再開前と接続失敗を含む試行回数です。旧版分は含みません。</small></p>'+((stageClock||runClock)?'<div class="analysis-activity-clocks">'+(stageClock?'<span>この処理の経過 '+stageClock+'</span>':'')+(runClock?'<span>今回の実行 '+runClock+'</span>':'')+'</div>':'')+(a.stage==='waiting'?'<p class="analysis-activity-note">応答待ちの進捗割合は取得できません。経過時間を表示し、検証できた結果から完了数を更新します。</p>':a.stage==='paused'?'<p class="analysis-activity-note">確定済みの結果から再開できます。</p>':'')+'</section>';
  }
  function memoryErrorMessage(plan) {
    const diagnostic=plan?.analysisError;if(plan?.analysisStatus!=='error'||diagnostic?.code!=='invalid_memory')return '';
    const labels={memory_format:'作品メモの応答形式を確認できませんでした。',memory_id:'作品メモの識別情報の形式を確認できませんでした。',memory_person:'作品メモの人物を、保存済みの配役と照合できませんでした。',memory_citation:'作品メモの出典を、対象本文の区間に照合できませんでした。',memory_text_limit:'作品メモの文章が、保存できる長さを超えました。',memory_duplicate:'作品メモに重複した項目があり、保存を停止しました。',memory_limit:'作品メモの項目数または出典数が、保存できる範囲を超えました。'};
    return (Object.hasOwn(labels,diagnostic.stage)?labels[diagnostic.stage]:'作品メモの形式または根拠を確認できませんでした。')+' 保存済みのメモと確定済みの解析結果は保持しています。';
  }
  function pronunciationErrorMessage(plan) {
    const diagnostic=plan?.analysisError;if(plan?.analysisStatus!=='error'||diagnostic?.code!=='invalid_pronunciation')return '';
    const labels={pronunciation_format:'音声用の読みの応答形式を確認できませんでした。',pronunciation_part:'音声用の読みを、対象の句に照合できませんでした。',pronunciation_source:'音声用の読みと、解析元の本文が一致しませんでした。',pronunciation_reading:'音声用の読みの文字や空白を確認できませんでした。',pronunciation_limit:'音声用の読みが、保存できる長さを超えました。',pronunciation_ruby:'音声用の読みを、作者のルビと照合できませんでした。'};
    return (Object.hasOwn(labels,diagnostic.stage)?labels[diagnostic.stage]:'音声用の読みの形式を確認できませんでした。')+' 保存済みの読み・配役・演技は保持しています。';
  }
  function analysisPauseHtml(owner) {return (owner?.analysisStatus||owner?.status)==='paused'&&owner?.analysisPauseReason==='pronunciation_changed'?'<p class="speech-footnote" data-analysis-pause-reason>読みを変更したため解析を中断しました。保存した範囲から再開してください。</p>':'';}
  function analysisErrorHtml(plan) {const memory=memoryErrorMessage(plan),reading=pronunciationErrorMessage(plan),message=memory||reading||plan?.error;return message?'<p class="download-error"'+(memory?' data-memory-error':reading?' data-pronunciation-error':'')+'>'+esc(message)+'</p>':'';}
  function analysisDiagnosticsText(plan) {
    if(!plan||plan.analysisStatus!=='error'||typeof plan.error!=='string'||!plan.error.trim())return '';
    const d=plan.analysisError;if(!d||typeof d!=='object'||Array.isArray(d))return '';
    // Public metadata only: never serialize a plan, response body, account, or arbitrary object field.
    const lines=['appVersion: 1.12.2'];
    const add=(key,value,pattern)=>{if(typeof value==='string'&&pattern.test(value))lines.push(key+': '+value);};
    add('model',plan.analysisModel,/^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$/);
    add('provider',d.provider,/^(chatgpt|apiKey|api_key)$/);
    if(Number.isInteger(d.httpStatus)&&d.httpStatus>=0&&d.httpStatus<=599)lines.push('httpStatus: '+d.httpStatus);
    add('code',d.code,/^[a-z][a-z0-9_]{0,100}$/);
    add('stage',d.stage,/^[a-z][a-z0-9_]{0,100}$/);
    add('responseShape',d.responseShape,/^[a-z][a-z0-9_]{0,100}$/);
    if(d.code==='invalid_memory')for(const key of ['factIndex','citationIndex'])if(Number.isSafeInteger(d[key])&&d[key]>=0)lines.push(key+': '+d[key]);
    if(d.code==='invalid_pronunciation'&&Number.isSafeInteger(d.partIndex)&&d.partIndex>=0)lines.push('partIndex: '+d.partIndex);
    add('param',d.param,/^[A-Za-z0-9_.\[\]-]{1,100}$/);
    add('requestId',d.requestId,/^req_[A-Za-z0-9_-]{1,120}$/);
    if(['text/event-stream','application/json','application/problem+json','application/x-ndjson','application/json-seq','text/plain','text/html','application/xhtml+xml','application/octet-stream','missing','other'].includes(d.mediaType))lines.push('mediaType: '+d.mediaType);
    if(['sse','json','html','empty','unknown'].includes(d.bodyFormat))lines.push('bodyFormat: '+d.bodyFormat);
    return lines.join('\n');
  }
  function analysisDiagnosticsHtml(plan,source='chapter') {
    const text=analysisDiagnosticsText(plan);if(!text)return '';
    return '<details class="speech-error-details" data-analysis-diagnostics data-diagnostics-source="'+source+'"><summary>エラーの詳細</summary><p>原因の確認に使う識別情報です。小説の本文やログイン情報は含みません。</p><textarea readonly rows="'+Math.min(12,text.split('\n').length+1)+'" aria-label="コピーできるエラーの詳細" spellcheck="false" autocomplete="off">'+esc(text)+'</textarea><button type="button" class="text-button" data-action="speech-copy-diagnostics">詳細をコピー</button></details>';
  }
  async function copyAnalysisDiagnostics(button) {
    const details=button.closest('[data-analysis-diagnostics]'),field=details&&$('textarea',details),source=details&&details.dataset.diagnosticsSource==='range'?rangeDiagnosticPlan(currentSpeechRange()):speech.plan,text=analysisDiagnosticsText(source);
    if(!text||!field){toast('エラーの詳細を開き直してください',true);return;}
    try{if(navigator.clipboard&&typeof navigator.clipboard.writeText==='function'){await navigator.clipboard.writeText(text);toast('エラーの詳細をコピーしました');return;}}catch(_){}
    if(!field.isConnected||field.value!==text){toast('表示が更新されました。エラーの詳細を開き直してください',true);return;}
    field.focus({preventScroll:true});field.select();field.setSelectionRange(0,text.length);
    let copied=false;try{copied=!!document.execCommand('copy');}catch(_){}
    if(copied){button.focus({preventScroll:true});toast('エラーの詳細をコピーしました');}
    else toast('詳細を選択しました。長押ししてコピーしてください');
  }
  function analysisStatusHtml() {
    const plan=speech.plan||{},total=Number.isInteger(plan.totalUnits)?plan.totalUnits:(plan.units||[]).length,done=Math.max(0,Number(plan.analyzedUnits)||0),status=plan.analysisStatus||'idle';
    const addDetails=status==='complete'&&needsDetailedActing(plan),addReading=status==='complete'&&needsPronunciation(plan),memoOnly=status==='complete'&&!addDetails&&!addReading&&needsStoryMemory(plan),detailed=plan.analysisDetail==='detailed',chapter=plan.analysisMethod==='chapter';
    const phase=status==='running'?({memory:'作品メモを準備中',analyzing:chapter?'一話をまとめて解析中':'区間を解析中',planning:'演技案を解析中',reviewing:'演技を再確認中'}[plan.analysisPhase]||(detailed?'精密な演技を解析中':'解析中')):'';
    const label=phase||({idle:'未解析',running:'解析中',paused:'解析を中断',complete:addDetails?'標準解析済み':addReading||memoOnly?'演技解析済み':'解析完了',error:'解析を停止'}[status]||'未解析');
    const action=status==='running'?'解析を中断':addDetails?'細かい演技を追加解析':addReading?(plan.missingPronunciationUnits>0?'漢字・助詞の読みを追加解析':'単語の演技を追加解析'):memoOnly?'作品メモを準備':done>0&&done<total?'続きからGPT解析':status==='complete'?'GPT解析を確認':'この話をGPT解析';
    const note=phase?(plan.analysisPhase==='reviewing'?'旧方式 2 / 2 · 演技案を文脈と照合しています。':plan.analysisPhase==='planning'?'旧方式 1 / 2 · 話者と短い句ごとの演技案を作っています。':plan.analysisPhase==='memory'?'開始話より前の未記録部分から作品メモを準備しています。':chapter?'一話の文脈をまとめて解析しています。大きすぎる話は場面単位に分け、検証できた結果から保存します。':'完了した区間から結果を保存します。'):'';
    return '<div class="row between"><strong data-analysis-phase>'+label+'</strong><span>確定済み '+count(done)+' / '+count(total)+'区間</span></div><progress class="speech-analysis-progress" value="'+Math.min(done,total)+'" max="'+Math.max(1,total)+'" aria-label="確定済み区間の進捗"></progress>'+pronunciationProgressHtml(plan)+analysisActivityHtml(plan)+(note&&!analysisActivityView(plan)?'<p class="speech-footnote acting-progress-note">'+note+'</p>':'')+(addDetails?'<p class="speech-footnote">既存の配役を使い、細かい演技がない区間を追加解析できます。手動固定した区間は保持します。</p>':'')+(addReading?'<p class="speech-footnote">保存した配役・演技を使い、未準備の漢字・助詞の読みを追加できます。手動固定した読みは保持します。</p>':'')+analysisPauseHtml(plan)+analysisErrorHtml(plan)+analysisDiagnosticsHtml(plan)+analysisMetricsHtml(plan.analysisMetrics)+(speech.settings.analysisProvider!=='api_key'?'<div class="chatgpt-analysis-plan compact"><span>'+ (accountUsesPlan()?'ChatGPTプランを使用':activeChatgptAccount()?.connected?'ChatGPTプランの利用許可が必要':'ChatGPTログインが必要')+'</span><button class="text-button" data-action="speech-chatgpt-usage">利用量を管理</button></div>':'')+'<button class="button secondary full compact" data-action="'+(status==='running'?'speech-cancel':'speech-analyze')+'">'+icon(status==='running'?'pause':'spark')+action+'</button>';
  }
  function historyConsentHtml(preview) {
    if(!preview||preview.workflowVersion<2||preview.useStoryMemory!==true)return '';
    const from=Math.max(0,Number(preview.historyFromNumber)||0),to=Math.max(0,Number(preview.historyToNumber)||0),total=Math.max(0,Number(preview.historyTotal)||0);
    return '<div class="story-history-consent" data-history-consent><strong>作品メモに使う過去の本文</strong>'+(total>0?'<p class="story-history-scope">第'+count(from)+'〜'+count(to)+'話 · '+count(total)+'話</p><p>指定した解析範囲に加え、この過去の未記録分もOpenAIへ送信します。本文が端末にない場合は順に取得します。初回のメモ準備には時間と利用量がかかります。</p>':'<p>必要な過去のメモは準備済み、または前の話がありません。過去の本文を追加送信せず、保存済みメモを再利用します。</p>')+'<p>解析対象より後の自動メモは使いません。手動修正・追加・削除は、保存時に表示していた話以降に適用します。</p></div>';
  }
  function historyProgressHtml(job,showActivity=false) {
    if(!job||job.workflowVersion<2||!job.useStoryMemory||!job.memoryStatus||job.memoryStatus==='disabled')return '';
    const labels={pending:'作品メモの準備待ち',building:'作品メモを準備中',ready:'作品メモを再利用できます',partial:'作品メモに未取得部分があります'},total=Math.max(0,Number(job.historyTotal)||0),done=Math.max(0,Number(job.historyCompleted)||0);
    return '<div class="story-history-progress" data-history-progress>'+(showActivity?analysisActivityHtml(job):'')+'<div class="row between"><strong>'+esc(labels[job.memoryStatus]||'作品メモ')+'</strong>'+(total?'<span>'+count(done)+' / '+count(total)+'話</span>':'')+'</div>'+(total?'<progress class="speech-analysis-progress" value="'+Math.min(done,total)+'" max="'+total+'" aria-label="作品メモの準備進捗"></progress>':'')+(Number(job.historyThrough)>0?'<p>第'+count(job.historyThrough)+'話までの履歴を確認済み</p>':'')+(job.historyError?'<p class="download-error">'+esc(job.historyError)+'</p>':'')+analysisMetricsHtml(job.memoryMetrics,'memory')+'</div>';
  }
  function historyGuard(preview) {return {expectedHistoryFromNumber:Number(preview.historyFromNumber)||0,expectedHistoryToNumber:Number(preview.historyToNumber)||0,expectedUseStoryMemory:preview.useStoryMemory===true};}
  function currentSpeechRange(context=speech.context) {return context?speechRangesByBook.get(context.bookId)||null:null;}
  function rangeIsActive(job) {return !!job&&['running','queued','loading','preparing','pausing'].includes(job.status);}
  function rememberSpeechRange(job,bookId) {
    const id=job&&job.bookId||bookId;if(!id)return;
    if(!job||typeof job.jobId!=='string'||!job.jobId||typeof job.status!=='string'||!job.status){speechRangesByBook.delete(id);speechRangePausing.delete(id);return;}
    const old=speechRangesByBook.get(id);
    if(old&&Number(old.updatedAt)>Number(job.updatedAt))return;
    speechRangesByBook.set(id,{...job,bookId:id});
    if(!rangeIsActive(job))speechRangePausing.delete(id);
  }
  function rangeDiagnosticPlan(job) {return job?{analysisStatus:job.status,error:job.error,analysisModel:job.model,analysisError:job.analysisError}:null;}
  function rangeDraftFor(context=speech.context) {
    if(!context)return null;const key=context.bookId+'|'+context.chapterUrl;
    if(!speechRangeDrafts.has(key))speechRangeDrafts.set(key,{key,context:{...context},options:null,loading:false,optionsError:'',initialized:false,startNumber:'',count:'10',preview:null,previewError:'',previewLoading:false,request:0,optionsRequest:0,timer:0});
    return speechRangeDrafts.get(key);
  }
  function visibleRangeDraft(draft) {return !!draft&&!!speech.context&&draft.key===speech.context.bookId+'|'+speech.context.chapterUrl&&state.sheet&&state.sheet.kind==='speech';}
  function rangeInput(draft) {
    const startNumber=Number(draft.startNumber),amount=Number(draft.count);
    if(!/^\d+$/.test(draft.startNumber)||!/^\d+$/.test(draft.count)||!Number.isSafeInteger(startNumber)||!Number.isSafeInteger(amount)||startNumber<1||amount<1||!Number.isSafeInteger(startNumber+amount-1))return null;
    return {...draft.context,startNumber,count:amount};
  }
  function rangeLabel(value) {return '第'+count(value.startNumber)+'〜'+count(value.endNumber)+'話 · '+count(value.count)+'話';}
  function rangePreviewHtml(draft) {
    const input=rangeInput(draft),p=draft.preview;
    if(!input)return '<p class="speech-range-help">開始話と話数を1以上の整数で入力してください。</p>';
    if(draft.previewError)return '<p class="download-error" role="status">'+esc(draft.previewError)+'</p>';
    const options=draft.options||{},start=(options.chapters||[]).find(c=>Number(c.number)===input.startNumber);
    const localEnd=options.tocComplete===true&&Number(options.knownTotal)>=input.startNumber?Math.min(input.startNumber+input.count-1,Number(options.knownTotal)):input.startNumber+input.count-1;
    const shown=p||{startNumber:input.startNumber,endNumber:localEnd,count:localEnd-input.startNumber+1,startTitle:start&&start.title||''};
    return '<strong class="speech-range-selection">'+rangeLabel(shown)+'</strong>'+(shown.startTitle?'<span class="speech-range-title">開始：'+esc(shown.startTitle)+'</span>':'')+(p&&p.limitedToEnd?'<p class="speech-range-limit">作品の最後は第'+count(p.endNumber)+'話です。指定した'+count(p.requestedCount)+'話のうち、'+count(p.count)+'話を解析します。</p>':'')+(draft.previewLoading?'<span class="speech-range-help" role="status">範囲を確認中…</span>':!p?'<span class="speech-range-help">範囲を確認してください。</span>':'');
  }
  function speechRangeFormHtml(draft) {
    if(!draft.options)return draft.loading?'<div class="loading-state compact"><span class="spinner"></span><span>目次を確認中…</span></div>':'<p class="download-error">'+esc(draft.optionsError||'目次を確認できませんでした。')+'</p><button class="text-button" data-action="speech-range-reload">目次を確認する</button>';
    return '<form data-form="speech-range"><button class="button secondary full compact speech-range-shortcut" type="button" data-action="speech-range-current">現在の話から10話</button><div class="speech-form-grid speech-range-inputs"><label for="speech-range-start">開始話<input id="speech-range-start" name="startNumber" type="number" inputmode="numeric" min="1" step="1" value="'+esc(draft.startNumber)+'" required></label><label for="speech-range-count">解析する話数<input id="speech-range-count" name="count" type="number" inputmode="numeric" min="1" step="1" value="'+esc(draft.count)+'" required></label></div><p class="speech-range-help">開始話を含めた話数です。目次の話数で指定します。</p><div class="speech-range-preview" data-speech-range-preview aria-live="polite">'+rangePreviewHtml(draft)+'</div>'+(draft.options.tocComplete===false?'<p class="speech-range-help">目次は一部取得済みです。続きの話を順に確認し、確認できない場合はそこで停止します。</p>':'')+'<button class="button secondary full" type="submit" data-range-submit disabled>'+icon('spark')+'この範囲をGPT解析</button></form>';
  }
  function speechRangeStatusHtml(job) {
    if(!job)return '';
    const active=rangeIsActive(job),pausing=speechRangePausing.has(job.bookId)||job.status==='pausing',completed=Math.max(0,Number(job.completedChapters)||0),total=Math.max(1,Number(job.count)||1),label=pausing?'解析を中断しています…':({running:'まとめて解析中',queued:'解析を準備中',loading:'本文を取得中',preparing:'解析を準備中',paused:'まとめて解析を中断',error:'まとめて解析を停止',complete:'まとめて解析完了'}[job.status]||'まとめて解析');
    const phase=active&&!pausing?({memory:'作品メモを準備中',analyzing:job.detailedActing?'一話をまとめて解析中':'区間を解析中',planning:'旧方式 1 / 2 · 演技案を解析中',reviewing:'旧方式 2 / 2 · 演技を再確認中'}[job.analysisPhase]||''):'';
    return '<div class="speech-range-job"><div class="row between"><strong>'+esc(label)+'</strong><span>'+count(completed)+' / '+count(job.count)+'話完了</span></div><p class="speech-range-job-scope">'+rangeLabel(job)+'</p><progress class="speech-analysis-progress" value="'+Math.min(completed,total)+'" max="'+total+'" aria-label="確定済みの話数進捗"></progress>'+analysisActivityHtml(job)+(job.currentNumber&&job.status!=='complete'?'<p class="speech-range-current">第'+count(job.currentNumber)+'話'+(job.currentTitle?' · '+esc(job.currentTitle):'')+'</p>':'')+(Number(job.totalUnits)>0&&job.status!=='complete'?'<p class="speech-range-help">この話：'+count(job.analyzedUnits)+' / '+count(job.totalUnits)+'区間</p>':'')+(phase&&!analysisActivityView(job)?'<p class="speech-range-help acting-range-phase" data-range-analysis-phase>'+phase+'</p>':'')+analysisPauseHtml(job)+analysisErrorHtml(rangeDiagnosticPlan(job))+historyProgressHtml(job)+analysisDiagnosticsHtml(rangeDiagnosticPlan(job),'range')+analysisMetricsHtml(job.analysisMetrics)+(active?'<button class="button secondary full compact" data-action="speech-range-pause"'+(pausing?' disabled':'')+'>'+icon('pause')+(pausing?'中断しています…':'まとめて解析を中断')+'</button>':job.canResume?'<button class="button full compact" data-action="speech-range-resume">'+icon('spark')+'保存した範囲の続きを解析</button>':'')+'</div>';
  }
  function speechRangeHtml() {
    const draft=rangeDraftFor();
    return '<section class="speech-range-section" aria-labelledby="speech-range-heading"><div class="section-heading"><h2 id="speech-range-heading">まとめてGPT解析</h2></div><div data-speech-range-controls>'+speechRangeFormHtml(draft)+'</div><div data-speech-range-status>'+speechRangeStatusHtml(currentSpeechRange())+'</div><p class="speech-footnote">画面を閉じても解析を続けます。端末側で処理が終了した場合は、保存した進捗から再開できます。手動で固定した配役・区間は保持します。</p></section>';
  }
  function updateSpeechRangePanel() {
    if(!state.sheet||state.sheet.kind!=='speech')return;
    const status=$('[data-speech-range-status]');if(status)updateAnalysisRegion(status,speechRangeStatusHtml(currentSpeechRange()));
    const draft=rangeDraftFor(),submit=$('[data-range-submit]');
    if(submit)submit.disabled=speechRangeStarting||rangeIsActive(currentSpeechRange())||!!(speech.plan&&speech.plan.analysisStatus==='running')||!draft.preview||draft.previewLoading||!!draft.previewError||!rangeInput(draft);
    const single=$('[data-action="speech-analyze"]');if(single)single.disabled=rangeIsActive(currentSpeechRange());
  }
  function updateRangePreview(draft) {
    if(!visibleRangeDraft(draft))return;
    const target=$('[data-speech-range-preview]');if(target)target.innerHTML=rangePreviewHtml(draft);
    updateSpeechRangePanel();
  }
  async function loadSpeechRangeOptions(context=speech.context,force=false) {
    const draft=rangeDraftFor(context);if(!draft||draft.loading&&!force)return;
    const ticket=++draft.optionsRequest,revision=speechRangeRevision;draft.loading=true;draft.optionsError='';
    if(visibleRangeDraft(draft)&&!draft.options){const target=$('[data-speech-range-controls]');if(target)target.innerHTML=speechRangeFormHtml(draft);}
    try {
      const data=await call('speechAnalysisRangeOptions',context);
      if(ticket!==draft.optionsRequest)return;
      draft.options=data;draft.loading=false;
      if(!draft.initialized){draft.startNumber=Number(data.currentNumber)>0?String(data.currentNumber):'';draft.count='10';draft.initialized=true;}
      if('analysisRange' in data&&revision===speechRangeRevision)rememberSpeechRange(data.analysisRange,context.bookId);
      if(visibleRangeDraft(draft)){const target=$('[data-speech-range-controls]');if(target&&!$('[data-form="speech-range"]',target))target.innerHTML=speechRangeFormHtml(draft);updateSpeechRangePanel();}
      scheduleRangePreview(draft,0);
    } catch(error) {
      if(ticket!==draft.optionsRequest)return;draft.loading=false;draft.optionsError=error.message;
      if(visibleRangeDraft(draft)&&!draft.options){const target=$('[data-speech-range-controls]');if(target)target.innerHTML=speechRangeFormHtml(draft);}
    }
  }
  function scheduleRangePreview(draft,delay=200) {
    clearTimeout(draft.timer);draft.request++;draft.preview=null;draft.previewError='';draft.previewLoading=!!rangeInput(draft);updateRangePreview(draft);
    if(draft.previewLoading)draft.timer=setTimeout(()=>{previewSpeechRange(draft).catch(()=>{});},delay);
  }
  async function previewSpeechRange(draft) {
    clearTimeout(draft.timer);const input=rangeInput(draft);if(!input){draft.preview=null;draft.previewLoading=false;updateRangePreview(draft);return null;}
    const ticket=++draft.request;draft.previewLoading=true;draft.previewError='';updateRangePreview(draft);
    try {
      const data=await call('speechAnalysisRangePreview',input);
      if(ticket!==draft.request)return null;
      if(Number(data.startNumber)!==input.startNumber||Number(data.requestedCount)!==input.count||typeof data.startChapterUrl!=='string'||!data.startChapterUrl||!Number.isSafeInteger(Number(data.count))||Number(data.count)<1||Number(data.count)>input.count||Number(data.endNumber)!==input.startNumber+Number(data.count)-1||Number(data.count)<input.count&&!data.limitedToEnd)throw new Error('解析範囲を確認できませんでした。もう一度指定してください。');
      draft.preview=data;draft.previewLoading=false;updateRangePreview(draft);return data;
    } catch(error) {if(ticket!==draft.request)return null;draft.preview=null;draft.previewLoading=false;draft.previewError=error.message;updateRangePreview(draft);return null;}
  }
  function editSpeechRange(form) {
    const draft=rangeDraftFor();if(!draft)return;draft.startNumber=form.elements.startNumber.value;draft.count=form.elements.count.value;scheduleRangePreview(draft);
  }
  function setCurrentSpeechRange() {
    const draft=rangeDraftFor();if(!draft||!draft.options)return;
    draft.startNumber=Number(draft.options.currentNumber)>0?String(draft.options.currentNumber):'';draft.count='10';
    const form=$('[data-form="speech-range"]');if(form){form.elements.startNumber.value=draft.startNumber;form.elements.count.value=draft.count;}
    scheduleRangePreview(draft,0);
  }
  function currentAnalysisSelection() {const provider=speech.settings.analysisProvider==='api_key'?'api_key':'chatgpt';return {provider,model:provider==='chatgpt'?chatgpt.selectedModel:speech.settings.apiAnalysisModel||speech.settings.analysisModel,accountId:provider==='chatgpt'?chatgpt.activeAccountId:'',detailedActing:speech.settings.detailedActing!==false,useStoryMemory:speech.settings.detailedActing!==false&&speech.settings.useStoryMemory!==false,workflowVersion:2,pronunciationVersion:1};}
  async function ensureSpeechAnalysisReady() {
    const useAccount=speech.settings.analysisProvider!=='api_key';
    if(useAccount&&(chatgpt.busy||chatgptStarting)){await showSpeechSettings();toast('ChatGPTへのログインが完了してから解析してください');return false;}
    if(useAccount&&!accountUsesPlan()){await showSpeechSettings();toast(activeChatgptAccount()?.connected?'ChatGPTプランの利用を許可してください':'ChatGPTアカウントでログインしてください');return false;}
    if(useAccount&&(!chatgpt.selectedModel||!chatgpt.models.some(m=>m.slug===chatgpt.selectedModel))){await showSpeechSettings();toast('このアカウントで使うGPTモデルを選んで保存してください');return false;}
    if(!useAccount&&!speech.settings.hasApiKey){await showSpeechSettings();toast('APIキー方式のGPT解析にはOpenAI APIキーが必要です');return false;}
    return true;
  }
  function rangeConsentProviderHtml(selection) {
    if(selection.provider==='api_key')return '<div class="chatgpt-analysis-plan"><strong>OpenAI APIキー方式</strong><span>'+esc(selection.model)+'</span><p>ChatGPTプランとは別のAPI利用枠を消費し、従量料金が発生する場合があります。</p></div>';
    const account=activeChatgptAccount();return '<div class="chatgpt-analysis-plan"><strong>ChatGPTプランを使用</strong><span>'+esc(account&&account.label||account&&account.email||'選択したアカウント')+' · '+esc(chatgpt.models.find(m=>m.slug===selection.model)?.display_name||selection.model)+'</span><p>指定した範囲の解析にChatGPTプランの利用枠を使います。</p><button class="text-button" data-action="speech-chatgpt-usage">'+icon('external')+'利用量を管理</button></div>';
  }
  async function beginSpeechRange(resume=false) {
    const context=speech.context&&{...speech.context};if(!context||speechRangeStarting)return;
    const job=currentSpeechRange(context);
    if(rangeIsActive(job)){toast('まとめて解析を中断してから、別の範囲を指定してください');return;}
    if(!await ensureSpeechAnalysisReady())return;
    const selection=currentAnalysisSelection();let preview;
    if(resume){
      if(!job||!job.canResume){toast('再開できる解析範囲がありません');return;}
      if(job.provider!==selection.provider||job.model!==selection.model){toast('保存した解析と同じ方式・モデルに戻してから再開してください。条件を変える場合は、新しい範囲として開始してください。',true);return;}
      selection.detailedActing=job.detailedActing===true;selection.useStoryMemory=job.useStoryMemory===true;selection.workflowVersion=Number(job.workflowVersion)||1;selection.pronunciationVersion=job.pronunciationVersion===1?1:0;
      preview={...job,startTitle:job.startTitle||'',requestedCount:job.requestedCount||job.count};
    }else{
      const draft=rangeDraftFor(context);if(!rangeInput(draft)){toast('開始話と話数を1以上の整数で入力してください',true);return;}
      preview=await previewSpeechRange(draft);if(!preview||!visibleRangeDraft(draft))return;
    }
    if(!state.sheet||state.sheet.kind!=='speech'||!speech.context||speech.context.bookId!==context.bookId||speech.context.chapterUrl!==context.chapterUrl)return;
    const remaining=resume?Math.max(0,Number(job.count)-Number(job.completedChapters||0)):Number(preview.count);
    openSheet(resume?'保存した範囲の解析を再開':'まとめてGPT解析の確認','<div class="speech-range-consent"><strong class="speech-range-selection">'+rangeLabel(preview)+'</strong>'+(preview.startTitle?'<p class="speech-range-title">開始：'+esc(preview.startTitle)+'</p>':'')+(resume?'<p class="speech-range-help">'+count(job.completedChapters)+'話完了 · 残り'+count(remaining)+'話。途中の話は完了した区間の続きから再開します。</p>':'<p class="speech-range-help">開始話を含む'+count(preview.count)+'話が対象です。</p>')+(preview.limitedToEnd?'<p class="speech-range-limit">作品の最終話に合わせ、指定した'+count(preview.requestedCount)+'話から'+count(preview.count)+'話へ調整しました。</p>':'')+'</div>'+(resume?'<p class="note">この処理は開始時の'+(selection.detailedActing?(selection.workflowVersion<2?'精密な演技解析（旧2段階）':'一話単位の精密解析'):'標準解析')+'の設定で再開します。現在の音声設定で選んだ演技の細かさは適用しません。</p>':'')+'<p class="sheet-description">この範囲の未解析部分と配役情報をOpenAIへ送信し、話者・感情・読み方を判定します。精密解析では、標準解析済みの区間も細かい演技の追加対象になります。本文が未保存の話は順に読み込みます。</p>'+actingAnalysisDescription(selection.detailedActing,selection.workflowVersion<2,selection.useStoryMemory)+pronunciationConsentHtml(preview,resume)+historyConsentHtml(preview)+rangeConsentProviderHtml(selection)+'<div class="note">この確認は指定した範囲全体に適用されます。中断しても完了済みの結果は残り、手動で固定した配役・区間は保持します。</div><div class="sheet-actions speech-range-consent-actions"><button class="button full" data-action="speech-range-confirm">'+(resume?'同じ条件で解析を再開':'この'+count(preview.count)+'話をOpenAIへ送信して解析')+'</button><button class="button secondary full" data-action="speech-back">戻る</button></div>',{kind:'speech-range-consent',...context,rangeConsent:{context,preview:{...preview},selection:{...selection},resume,jobId:job&&job.jobId}});
  }
  async function confirmSpeechRange() {
    const consent=state.sheet&&state.sheet.rangeConsent;if(!consent||speechRangeStarting)return;
    const selection=currentAnalysisSelection();if(consent.resume){selection.detailedActing=consent.selection.detailedActing;selection.useStoryMemory=consent.selection.useStoryMemory;selection.workflowVersion=consent.selection.workflowVersion;selection.pronunciationVersion=consent.selection.pronunciationVersion;}
    if(JSON.stringify(selection)!==JSON.stringify(consent.selection)){renderSpeechSheet();toast('解析の設定またはアカウントが変わりました。範囲を確認し直してください',true);return;}
    if(consent.resume&&currentSpeechRange(consent.context)?.jobId!==consent.jobId){renderSpeechSheet();toast('保存された解析範囲が変わりました。確認し直してください',true);return;}
    if(!await ensureSpeechAnalysisReady())return;
    if(speechRangeStarting||!state.sheet||state.sheet.rangeConsent!==consent)return;
    speechRangeStarting=true;const button=$('[data-action="speech-range-confirm"]');if(button)button.disabled=true;
    const revision=speechRangeRevision;
    try {
      // Send the confirmed effective count, so a newly published chapter cannot expand consent.
      const payload=consent.resume?{...consent.context,confirmed:true}:{...consent.context,startNumber:Number(consent.preview.startNumber),count:Number(consent.preview.count),expectedStartUrl:consent.preview.startChapterUrl,...historyGuard(consent.preview),confirmed:true};
      const result=await call(consent.resume?'speechResumeRange':'speechAnalyzeRange',payload);
      if(revision===speechRangeRevision)rememberSpeechRange(result.analysisRange||result.jobId&&result,consent.context.bookId);
      if(state.sheet&&state.sheet.rangeConsent===consent){renderSpeechSheet();revealAnalysisProgress(true);toast(consent.resume?'保存した範囲の解析を再開しました':'指定した範囲の解析を開始しました');}
    } finally {speechRangeStarting=false;if(button&&button.isConnected)button.disabled=false;updateSpeechRangePanel();}
  }
  async function pauseSpeechRange() {
    const context=speech.context&&{...speech.context},job=currentSpeechRange(context);if(!context||!rangeIsActive(job)||speechRangePausing.has(context.bookId))return;
    speechRangePausing.add(context.bookId);updateSpeechRangePanel();
    try {await call('speechCancel');const revision=speechRangeRevision,bundle=await call('speechState',context);if(revision===speechRangeRevision&&'analysisRange' in bundle)rememberSpeechRange(bundle.analysisRange,context.bookId);updateSpeechRangePanel();}
    catch(error){speechRangePausing.delete(context.bookId);updateSpeechRangePanel();throw error;}
  }
  async function showSpeech(preferPlayback=false) {
    const context=speechContext(preferPlayback);const ticket=++speechOpenSequence;if(!context){toast('作品を開いてから「聴く」を選んでください');return;}
    openSheet('読み上げ','<div class="loading-state"><span class="spinner"></span><span>読み上げ位置を確認中…</span></div>',{kind:'speech-loading',...context});
    try {const revision=speechEventSequence,authRevision=chatgptRevision,rangeRevision=speechRangeRevision,packRevision=offlinePackRevision;const bundle=await call('speechPrepare',context);if(ticket!==speechOpenSequence||!state.sheet||state.sheet.kind!=='speech-loading')return;applySpeechBundle(bundle,context,revision,authRevision,rangeRevision,packRevision);speech.followPlayback=preferPlayback;renderSpeechSheet();}
    catch(error){closeSheet();throw error;}
  }
  function renderSpeechSheet() {
    const c=speech.context;if(!c)return;
    openSheet('読み上げ','<div data-offline-required'+(speech.settings.engine!=='offline'||speech.offlinePacks.ready?' hidden':'')+'><div data-offline-status>'+offlinePackStatusHtml()+'</div></div><div data-speech-panel>'+speechPanelHtml()+'</div><div class="offline-preview-status" data-voice-preview-status role="status" hidden></div><div class="settings-group speech-display-option">'+toggleRow('showSpeechPlayer','読み上げコントローラーを表示','オフでも再生は続きます。この画面で操作できます。')+'</div><div class="speech-inline-options"><label>速度<select data-speech-control="speed" aria-label="読み上げ速度">'+optionsHtml([[.5,'0.5倍'],[.75,'0.75倍'],[1,'1倍'],[1.25,'1.25倍'],[1.5,'1.5倍'],[1.75,'1.75倍'],[2,'2倍']],(['playing','paused','loading'].includes(speech.playback.status)?speech.playback.speed:speech.settings.speed)||speech.settings.speed)+'</select></label><label>スリープ<select data-speech-control="sleep" aria-label="スリープタイマー">'+optionsHtml([[0,'オフ'],[5,'5分'],[15,'15分'],[30,'30分'],[60,'60分']],speech.settings.sleepMinutes)+'</select></label></div><p class="speech-footnote">'+(speech.settings.engine==='offline'?'保存済みのキャラ音声で、この端末上で音声を生成します。解析済みの話はGPTへ再接続せず、固定配役と抑揚を使って聴けます。':'端末の音声で再生します。一時停止後は現在の区間の先頭から再開します。')+'</p><div class="section-heading"><h2>話者と読み方</h2><button class="text-button" data-action="speech-settings">音声設定 '+icon('right')+'</button></div><p class="sheet-description">GPTが地の文・台詞・心の声、話者、感情、抑揚を解析します。'+(speech.settings.detailedActing!==false?'一話をまとめて解析し、短い句ごとの読み方を決めます。大きすぎる話は場面単位に分けます。':'現在は区間単位の標準解析です。')+'作品ごとの固定配役と、区間の細かい演技を手動修正できます。</p><div class="speech-analysis" data-analysis-status>'+analysisStatusHtml()+'</div>'+speechRangeHtml()+'<div class="speech-editor-actions"><button class="button secondary" data-action="speech-cast-list">'+icon('people')+'配役・声を編集</button><button class="button secondary" data-action="speech-units" data-page="0">'+icon('text')+'区間を修正</button></div><button class="button secondary full story-memory-open" data-action="speech-memory">'+icon('file')+'作品メモ</button><p class="speech-footnote">解析結果と作品メモを保存して次の話へ引き継ぎます。GPTの話者・感情判定には誤りが含まれる場合があります。AIの自己評価は測定済みの正解率ではありません。</p>',{kind:'speech',...c});
    const status=$('[data-analysis-status]');if(status)status.__analysisHtml=analysisStatusHtml();
    const rangeStatus=$('[data-speech-range-status]');if(rangeStatus)rangeStatus.__analysisHtml=speechRangeStatusHtml(currentSpeechRange());
    updateSpeechRangePanel();loadSpeechRangeOptions().catch(()=>{});
  }
  async function speechControl(command,extra={}) {
    const revision=speechEventSequence;const data=await call('speechControl',{command,...(speech.context||{}),...extra});
    if(revision===speechEventSequence)speech.playback=data.state||data;renderSpeechPlayer();
  }
  async function startSpeech(from) {
    if(!speech.context)return;
    const units=speech.plan&&speech.plan.units||[];
    if(from==='resume'){const pos=readingPosition();await speechControl('play',{...speech.context,...(pos&&pos.chapterUrl===speech.context.chapterUrl?{paragraph:pos.paragraph}:{})});return;}
    let index=speech.position.chapterUrl===speech.context.chapterUrl?Number(speech.position.unitIndex)||0:0;
    if(from==='start')index=0;
    else if(from==='here'){const pos=readingPosition();index=pos&&pos.chapterUrl===speech.context.chapterUrl?units.findIndex(u=>u.paragraph>=pos.paragraph):0;if(index<0)index=0;}
    await speechControl('play',{...speech.context,unitIndex:Math.min(index,Math.max(0,units.length-1))});
  }
  function deviceVoiceOptions(value) {
    const choices=[['','端末の既定音声'],...speech.voices.map(v=>[v.id,(v.name||v.id)+' · '+(v.locale||'')+(v.network?' · 通信が必要':'')])];
    if(value&&!choices.some(v=>v[0]===value))choices.push([value,value+'（現在の設定）']);
    return optionsHtml(choices,value||'');
  }
  function activeChatgptAccount() {return chatgpt.accounts.find(a=>a.id===chatgpt.activeAccountId)||null;}
  function accountUsesPlan() {const a=activeChatgptAccount();return !!(a&&a.connected&&a.planEnabled);}
  function chatgptButton(accountId='',consent=false) {
    return `<button class="button chatgpt-signin full" type="button" data-action="speech-chatgpt-login" data-oai-siwc-button data-account="${esc(accountId)}"${consent?' data-consent="true"':''}${chatgpt.busy?' disabled':''}>Continue with ChatGPT</button>`;
  }
  function chatgptSettingsHtml() {
    const active=activeChatgptAccount(),connected=!!(active&&active.connected),using=connected&&active.planEnabled;
    const accounts=chatgpt.accounts.length?`<label class="field-label" for="chatgpt-account">ChatGPTアカウント</label><select class="field-full" id="chatgpt-account" data-chatgpt-account${chatgpt.busy?' disabled':''}><option value="">アカウントを選択</option>${optionsHtml(chatgpt.accounts.map(a=>[a.id,(a.label||a.name||a.email||'ChatGPTアカウント')+(a.connected?'':' · 再ログインが必要')]),chatgpt.activeAccountId)}</select>`:'';
    const identity=active?`<div class="chatgpt-identity"><strong>${esc(active.name||active.label||'ChatGPTアカウント')}</strong>${active.email?`<span>${esc(active.email)}</span>`:''}</div>`:'';
    const status=chatgpt.busy?`<div class="chatgpt-pending" role="status"><span class="spinner"></span><span>${chatgpt.status==='waiting'?'ブラウザーでChatGPTへのログインを完了してください。':chatgpt.status==='exchanging'?'ログインを確認中…':'アカウント情報を更新中…'}</span></div><button class="text-button" type="button" data-action="speech-chatgpt-cancel">${['waiting','exchanging'].includes(chatgpt.status)?'ログインを中止':'モデルの更新を中止'}</button>`:'';
    const error=chatgpt.error?`<p class="download-error" role="status">${esc(chatgpt.error)}</p>`:'';
    const login=connected?`<div class="chatgpt-plan-status ${using?'enabled':'needs-consent'}"><strong>${using?'ChatGPTプランを使用':'プランの利用許可が必要です'}</strong><p>${using?'対象のGPT解析に、このアカウントのChatGPTプランの利用枠を使います。':'アカウントにはログインしています。GPT解析を始めるにはChatGPTプランの利用を許可してください。'}</p></div>${using?'':chatgptButton(active.id,true)}`:chatgptButton(active?active.id:'');
    const selected=chatgpt.selectedModel||'';
    const models=using?`<label class="field-label" for="chatgpt-model">このアカウントで使うGPTモデル</label><select class="field-full" id="chatgpt-model" name="chatgptModel" data-account="${esc(chatgpt.activeAccountId)}" data-dirty="false"${chatgpt.busy||!chatgpt.models.length?' disabled':''}><option value="">${chatgpt.models.length?'モデルを選択':'モデル一覧を取得してください'}</option>${optionsHtml(chatgpt.models.map(m=>[m.slug,m.display_name||m.slug]),selected)}</select><p class="speech-footnote chatgpt-model-help">モデルを選んだら、下の「設定を保存」で確定します。</p><button type="button" class="text-button" data-action="speech-chatgpt-models"${chatgpt.busy?' disabled':''}>${icon('refresh')}使えるモデルを更新</button>`:'';
    const manage=active?`<details class="chatgpt-manage"><summary>再ログイン・別のアカウントを追加</summary>${using?`<p class="speech-footnote">このアカウントに再ログイン</p>${chatgptButton(active.id)}`:''}<p class="speech-footnote">別のアカウントを追加</p>${chatgptButton()}${connected?`<button class="text-button danger" type="button" data-action="speech-chatgpt-logout" data-account="${esc(active.id)}"${chatgpt.busy?' disabled':''}>このアカウントからログアウト</button>`:''}</details>`:'';
    return `${accounts}${identity}${status}${error}${login}${models}<div class="chatgpt-account-actions"><button class="text-button" type="button" data-action="speech-chatgpt-usage">${icon('external')}利用量を管理</button></div>${manage}<p class="speech-footnote">ログインは端末のブラウザーで行います。パスワードはこのアプリへ入力しません。利用可能なモデル・枠はChatGPT側の権限と設定に従います。利用制限時にAPIキー方式へ自動切替はしません。</p>`;
  }
  function renderChatgptSettings() {
    const target=$('[data-chatgpt-settings]');if(!target)return;
    const old=$('#chatgpt-model',target),draft=old&&old.dataset.dirty==='true'?{accountId:old.dataset.account,value:old.value}:null;
    const account=$('#chatgpt-account',target),hadFocus=document.activeElement===account?'chatgpt-account':document.activeElement===old?'chatgpt-model':'',manageOpen=!!$('.chatgpt-manage[open]',target);
    target.innerHTML=chatgptSettingsHtml();
    const select=$('#chatgpt-model',target);
    if(draft&&select&&draft.accountId===chatgpt.activeAccountId&&chatgpt.models.some(m=>m.slug===draft.value)){select.value=draft.value;select.dataset.dirty='true';}
    const manage=$('.chatgpt-manage',target);if(manage&&manageOpen)manage.open=true;
    if(hadFocus){const focus=$('#'+hadFocus,target);if(focus)focus.focus({preventScroll:true});}
    updateAnalysisProviderView();
  }
  function applyChatgptState(value) {
    if(!value||typeof value!=='object')return;
    // Copy public fields only. Native credentials are never a UI state or persisted browser value.
    const statuses=['signed_out','waiting','exchanging','connected','error'];
    chatgpt={status:statuses.includes(value.status)?value.status:'signed_out',busy:!!value.busy,error:typeof value.error==='string'?value.error:'',activeAccountId:String(value.activeAccountId||''),selectedModel:String(value.selectedModel||''),firstConnection:!!value.firstConnection,
      accounts:(Array.isArray(value.accounts)?value.accounts:[]).filter(a=>a&&typeof a.id==='string').map(a=>({id:a.id,label:String(a.label||''),email:String(a.email||''),name:String(a.name||''),connected:!!a.connected,planEnabled:!!a.planEnabled})),
      models:(Array.isArray(value.models)?value.models:[]).filter(m=>m&&typeof m.slug==='string').map(m=>({slug:m.slug,display_name:String(m.display_name||m.slug)}))};
    chatgptRevision++;renderChatgptSettings();renderChatgptWelcome();updateSpeechPanel();
  }
  function renderChatgptWelcome() {
    const host=$('#chatgpt-welcome-host');if(!host)return;
    const account=activeChatgptAccount();
    if(!chatgpt.firstConnection||!accountUsesPlan()){host.replaceChildren();host.hidden=true;return;}
    if(host.dataset.account===chatgpt.activeAccountId&&host.firstElementChild){host.hidden=false;return;}
    host.dataset.account=chatgpt.activeAccountId;host.hidden=false;
    host.innerHTML=`<div class="chatgpt-welcome-backdrop"><section class="chatgpt-welcome" role="alertdialog" aria-modal="true" aria-labelledby="chatgpt-welcome-title"><p class="eyebrow">ChatGPT</p><h2 id="chatgpt-welcome-title">ChatGPTプランを使用します</h2><p>${esc(account.label||account.name||account.email||'選択したアカウント')}のプランを使って、対象のGPT解析を実行できます。利用量とアプリの制限はChatGPTの設定で管理できます。</p><button class="button chatgpt-signin full" data-action="speech-chatgpt-welcome" data-account="${esc(chatgpt.activeAccountId)}">確認しました</button><button class="text-button" data-action="speech-chatgpt-usage">${icon('external')}利用量を管理</button></section></div>`;
    const button=$('[data-action="speech-chatgpt-welcome"]',host);if(button)button.focus({preventScroll:true});
  }
  async function chatgptAction(action,payload={}) {
    const revision=chatgptRevision;
    try {
      const result=await call(action,payload);
      if(result&&(result.status||result.accounts)&&revision===chatgptRevision)applyChatgptState(result);
      return result;
    } catch(error) {renderChatgptSettings();throw error;}
  }
  async function startChatgptLogin(accountId='',consent=false) {
    if(chatgpt.busy||chatgptStarting)return;
    chatgptStarting=true;
    try {
      const saved=await call('speechSettings',{settings:{analysisProvider:'chatgpt'}});
      speech.settings={...speechDefaults,...(saved.settings||saved)};
      const radio=$('input[name="analysisProvider"][value="chatgpt"]');if(radio)radio.checked=true;
      updateAnalysisProviderView();
      await chatgptAction('speechChatgptLogin',{...(accountId?{accountId}:{}),...(consent?{consent:true}:{})});
    } finally {chatgptStarting=false;}
  }
  function updateAnalysisProviderView() {
    const form=$('[data-form="speech-settings"]');if(!form)return;
    const mode=$('input[name="analysisProvider"]:checked',form)?.value||'chatgpt';
    const accountRegion=$('[data-chatgpt-settings]',form);if(accountRegion)accountRegion.hidden=mode!=='chatgpt';
    const legacy=$('[data-api-key-fields]',form);if(legacy)legacy.hidden=mode!=='api_key';
    const model=$('#speech-analysis-model',form);if(model)model.disabled=mode!=='api_key';
  }
  async function showSpeechSettings() {
    const authRevision=chatgptRevision,packRevision=offlinePackRevision,bundle=await call('speechState',{});if(bundle.settings)speech.settings={...speechDefaults,...bundle.settings};
    if(Array.isArray(bundle.voiceCatalog))speech.voiceCatalog=bundle.voiceCatalog;
    if(bundle.offlinePacks&&packRevision===offlinePackRevision)applyOfflinePacks(bundle.offlinePacks);
    if(bundle.chatgpt&&authRevision===chatgptRevision)applyChatgptState(bundle.chatgpt);
    renderSpeechSettings();
  }
  function renderSpeechSettings() {
    const s=speech.settings;
    openSheet('読み上げ・AI設定',`<form data-form="speech-settings">
      <div class="speech-engine-picker"><label><input type="radio" name="engine" value="offline"${s.engine==='offline'?' checked':''}><span><strong>オフラインのキャラ音声</strong><small>10種類 · VOICEVOX · 端末で生成</small></span></label><label><input type="radio" name="engine" value="device"${s.engine==='device'?' checked':''}><span><strong>端末の音声</strong><small>端末に入っている音声エンジンを利用</small></span></label></div>
      <div class="speech-workflow"><div><span class="workflow-step">1</span><strong>GPTで解析</strong><small>話者・感情・抑揚を保存</small></div>${icon('right')}<div><span class="workflow-step">2</span><strong>端末で読み上げ</strong><small>決めた声でオフライン生成</small></div></div>
      <section class="speech-provider-section" aria-labelledby="offline-settings-heading"><h3 id="offline-settings-heading">キャラ音声 · VOICEVOX</h3><p class="speech-footnote">音声生成は端末内で行います。GPTの解析には通信が必要ですが、解析結果と本文を保存した後はオフラインで聴けます。未解析の台詞も選んだ演技で再生できます。</p><label class="field-label" for="speech-dialogue-mode">台詞の演技</label><select class="field-full" id="speech-dialogue-mode" name="dialogueMode">${Object.entries(dialogueModes).map(([id,label])=>'<option value="'+id+'"'+(id===s.dialogueMode?' selected':'')+'>'+label+'</option>').join('')}</select><p class="speech-footnote">台詞と心の声に適用します。</p><label class="field-label" for="speech-audition-scene">試聴する台詞</label><select class="field-full" id="speech-audition-scene" name="auditionScene"><option value="everyday">おかえり。今日は早かったね。</option><option value="plea">えっ……待って！お願い、行かないで。</option></select><div class="acting-audition">${Object.entries(dialogueModes).map(([id,label])=>'<button class="button secondary compact" type="button" data-action="speech-acting-preview" data-mode="'+id+'"'+(speech.offlinePacks.ready?'':' disabled')+'>'+label+'を試聴</button>').join('')}</div><div data-offline-status>${offlinePackStatusHtml()}</div><label class="field-label" for="speech-default-offline">新しい配役の既定音声</label><select class="field-full" id="speech-default-offline" name="offlineDefaultVoice">${offlineVoiceOptions(s.offlineDefaultVoice)}</select><p class="speech-footnote">登録済み人物の声は作品の「配役・声」で変更します。既定音声や解析モデルを変えても、保存した配役は保持します。</p><details class="offline-voice-library"><summary>10種類の声を聴き比べる</summary><p class="speech-footnote">音声データの準備後に試聴できます。試聴だけでは配役や読み上げの再開位置は変わりません。</p>${offlineCatalogHtml()}</details><div class="offline-preview-status" data-voice-preview-status role="status" hidden></div><p class="speech-footnote">話者：VOICEVOXの各キャラクター。声の表現は声ごとのスタイルと、速度・高さ・抑揚の調整で変わります。音声の利用条件は各キャラクターの規約で確認できます。</p><button class="text-button" type="button" data-action="speech-licenses">${icon('file')}音声クレジット・利用条件</button></section>
      <section class="speech-provider-section" aria-labelledby="gpt-settings-heading"><h3 id="gpt-settings-heading">GPT · 解析用</h3><label class="acting-setting"><input type="checkbox" name="detailedActing"${s.detailedActing!==false?' checked':''}><span><strong>精密な演技解析</strong><small>一話の文脈から、短い句ごとに感情や読み方を指定</small></span></label><p class="speech-footnote">オンにすると感情・発声・速さ・高さ・抑揚・音量・間・語尾まで解析します。出力する情報が増え、時間・利用量が変わります。解析は手動で開始し、保存済みの演技と固定配役は保持します。正確さやアニメ同等の演技を保証する機能ではありません。</p><label class="acting-setting"><input type="checkbox" name="useStoryMemory"${s.useStoryMemory!==false?' checked':''}><span><strong>作品メモを使う</strong><small>精密解析で、過去の人物関係・場面メモを使う</small></span></label><p class="speech-footnote">精密解析でオンにすると、初回は必要な過去の話を読んでメモを準備するため、時間と利用量が増えます。準備済みのメモは再利用します。本文を送る範囲は解析前に確認でき、起動時には送信しません。</p><p class="speech-footnote">章の本文・配役情報をOpenAIへ送り、話者と抑揚を解析します。ChatGPTアカウントでログインすると、対象の解析にChatGPTプランを使えます。</p><label class="chatgpt-provider-choice"><input type="radio" name="analysisProvider" value="chatgpt"${s.analysisProvider!=='api_key'?' checked':''}><span><strong>ChatGPTプランを使う</strong><small>OpenAI APIキーの入力は不要</small></span></label><div data-chatgpt-settings>${chatgptSettingsHtml()}</div><details class="speech-advanced api-key-alternative"${s.analysisProvider==='api_key'?' open':''}><summary>APIキー方式を使う場合</summary><label class="chatgpt-provider-choice"><input type="radio" name="analysisProvider" value="api_key"${s.analysisProvider==='api_key'?' checked':''}><span><strong>OpenAI APIキーを使う</strong><small>ChatGPTプランとは別のAPI利用枠・料金</small></span></label><div data-api-key-fields${s.analysisProvider!=='api_key'?' hidden':''}><p class="speech-footnote">この方式を明示的に選んで保存した場合だけAPIキーを使います。ChatGPTの利用上限に達しても自動切替はしません。</p><div class="speech-key-status">OpenAI APIキー：<strong>${s.hasApiKey?'登録済み（キーは表示しません）':'未登録'}</strong></div><label class="field-label" for="speech-api-key">${s.hasApiKey?'OpenAI APIキーを変更':'OpenAI APIキーを登録'}</label><input class="field-full" id="speech-api-key" name="apiKey" type="password" autocomplete="new-password" autocapitalize="none" spellcheck="false" placeholder="${s.hasApiKey?'変更するときだけ入力':'APIキー方式を使う場合のみ'}" maxlength="600">${s.hasApiKey?'<button type="button" class="text-button danger" data-action="speech-delete-key">OpenAI APIキーを削除</button>':''}<label class="field-label" for="speech-analysis-model">APIキー方式のGPTモデル</label><input class="field-full" id="speech-analysis-model" name="analysisModel" value="${esc(s.apiAnalysisModel||speechDefaults.apiAnalysisModel)}" pattern="(?:[A-Za-z0-9._:]|-)+" maxlength="100"${s.analysisProvider!=='api_key'?' disabled':''}></div></details></section>
      <label class="field-label" for="speech-device-voice">既定の端末音声</label><select class="field-full" id="speech-device-voice" name="deviceVoice">${deviceVoiceOptions(s.deviceVoice)}</select><button class="text-button" type="button" data-action="speech-load-voices">${icon('refresh')}端末で使える声を取得</button><p class="speech-footnote">端末音声の種類はインストール済みの音声エンジンによって変わります。通信不要の声を選ぶとオフラインで再生できます。</p><label class="speech-check"><input type="checkbox" name="autoNext"${s.autoNext?' checked':''}>次の話も続けて読み上げる</label><label class="speech-check"><input type="checkbox" name="followText"${s.followText?' checked':''}>読み上げ中の段落を追いかける</label><p class="speech-footnote">本文を操作すると追従を一時停止します。音声の再開位置は、読書位置とは別に保存します。</p><button class="button full" type="submit">設定を保存</button></form><div class="divider"></div><button class="text-button" data-action="speech-clear-cache">${icon('trash')}生成済み音声のキャッシュを削除</button><p class="speech-footnote">音声モデルと配役は残ります。同じ文章を再生するときは端末で音声を作り直します。</p>`,{kind:'speech-settings'});
    updateVoicePreview();
    updateAnalysisProviderView();
  }
  async function saveSpeechSettings(form,data) {
    const engine=String(data.get('engine')||'offline'),provider=String(data.get('analysisProvider')||'chatgpt');
    const key=String(data.get('apiKey')||'').trim();
    const settings={engine,speechProvider:'voicevox',offlineDefaultVoice:String(data.get('offlineDefaultVoice')||'metan'),dialogueMode:String(data.get('dialogueMode')||'natural'),deviceVoice:String(data.get('deviceVoice')||''),autoNext:data.get('autoNext')==='on',followText:data.get('followText')==='on',detailedActing:data.get('detailedActing')==='on',useStoryMemory:data.get('useStoryMemory')==='on',analysisProvider:provider};
    const selected=$('#chatgpt-model',form);let accountId='';
    if(provider==='api_key')settings.analysisModel=String(data.get('analysisModel')||speechDefaults.analysisModel).trim();
    else if(selected&&selected.dataset.dirty==='true'){
      if(chatgpt.busy||chatgptStarting)throw new Error('ChatGPTのログインまたはモデル一覧の更新が完了してから、解析モデルを保存してください。入力中の設定は残っています。');
      if(!selected.value||selected.dataset.account!==chatgpt.activeAccountId||!chatgpt.models.some(m=>m.slug===selected.value))throw new Error('現在のChatGPTアカウントで使えるモデルを選択してください。');
      settings.analysisModel=selected.value;accountId=chatgpt.activeAccountId;
    }
    for(const name of ['apiKey']){const input=form.elements[name];if(input)input.value='';data.delete(name);}
    const saved=await call('speechSettings',{settings,...(accountId?{chatgptAccountId:accountId}:{}),...(key?{apiKey:key}:{})});speech.settings={...speechDefaults,...(saved.settings||saved)};
    if(accountId&&accountId===chatgpt.activeAccountId)chatgpt.selectedModel=settings.analysisModel;
    if(state.sheet&&state.sheet.kind==='speech-settings')renderSpeechSettings();toast('読み上げ設定を保存しました');renderSpeechPlayer();
  }
  async function beginSpeechAnalysis(confirmed=false) {
    if(!speech.context||speechAnalysisStarting||speechConsentLoading)return;
    const plan=speech.plan||{},context={...speech.context};
    if(plan.analysisStatus==='running'){toast('この話は解析中です');return;}
    if(plan.analysisStatus==='complete'&&!needsDetailedActing(plan)&&!needsPronunciation(plan)&&!needsStoryMemory(plan)&&!(speech.settings.detailedActing!==false&&speech.settings.useStoryMemory!==false)){toast('この話の解析は完了しています。配役や区間を修正できます');return;}
    const consent=confirmed&&state.sheet&&state.sheet.analysisConsent;
    if(confirmed&&(!consent||consent.context.bookId!==context.bookId||consent.context.chapterUrl!==context.chapterUrl||JSON.stringify(consent.selection)!==JSON.stringify(currentAnalysisSelection()))){renderSpeechSheet();toast('解析の設定またはアカウントが変わりました。確認し直してください',true);return;}
    if(!await ensureSpeechAnalysisReady())return;
    const selection=currentAnalysisSelection();
    if(!confirmed){
      speechConsentLoading=true;const sheet=state.sheet,button=$('[data-action="speech-analyze"]');if(button)button.disabled=true;
      try {
        const preview=await call('speechAnalysisPreview',context);
        if(state.sheet!==sheet||!speech.context||speech.context.bookId!==context.bookId||speech.context.chapterUrl!==context.chapterUrl)return;
        if(JSON.stringify(selection)!==JSON.stringify(currentAnalysisSelection())){toast('解析の設定またはアカウントが変わりました。確認し直してください',true);return;}
        if(plan.analysisStatus==='complete'&&!needsDetailedActing(plan)&&!needsPronunciation(plan)&&!needsStoryMemory(plan)&&!(Number(preview.historyTotal)>0)){toast('この話の解析と作品メモの準備は完了しています。配役や区間を修正できます');return;}
        openSheet('この話をGPT解析','<p class="sheet-description">'+(plan.analysisStatus==='complete'&&!needsDetailedActing(plan)?(needsPronunciation(plan)?(plan.missingPronunciationUnits>0?'保存した配役・演技を使い、未準備の漢字・助詞の読みと単語の演技を追加します。':'保存した配役・演技を使い、台詞のどの言葉を強めるか、ためらうかを追加します。'):'保存した演技を再利用し、作品メモを準備します。'):'')+'この話の本文と配役情報をOpenAIへ送信し、話者・感情・読み方を判定します。'+(selection.detailedActing?'標準解析済みの区間も、細かい演技の追加対象になります。':'')+'</p>'+actingAnalysisDescription(selection.detailedActing)+pronunciationConsentHtml(preview)+historyConsentHtml(preview)+rangeConsentProviderHtml(selection)+'<div class="note">解析済み '+count(plan.analyzedUnits)+' / '+count((plan.units||[]).length)+'区間。途中で中断しても完了した結果は残ります。手動で修正した配役・区間は保持します。</div><div class="sheet-actions"><button class="button secondary" data-action="speech-back">戻る</button><button class="button" data-action="speech-analyze-confirm">OpenAIへ送信して解析</button></div>',{kind:'speech-consent',...context,analysisConsent:{context,selection,preview}});
      } finally {speechConsentLoading=false;if(button&&button.isConnected)button.disabled=false;}
      return;
    }
    if(!state.sheet||state.sheet.analysisConsent!==consent)return;
    speechAnalysisStarting=true;const sheet=state.sheet,button=$('[data-action="speech-analyze-confirm"]');if(button)button.disabled=true;
    try {
      const revision=speechEventSequence,authRevision=chatgptRevision,rangeRevision=speechRangeRevision,packRevision=offlinePackRevision;
      const data=await call('speechAnalyze',{...context,...historyGuard(consent.preview),confirmed:true});
      if(state.sheet===sheet){applySpeechBundle(data,context,revision,authRevision,rangeRevision,packRevision);renderSpeechSheet();revealAnalysisProgress();}
    } finally {speechAnalysisStarting=false;if(button&&button.isConnected)button.disabled=false;}
  }
  const memoryTypeLabels={character:'人物情報',relationship:'人物関係',event:'出来事',state:'人物・場面の状態',scene:'場面'};
  function memoryTargetLabel(bundle) {const number=Number(bundle?.coverage?.targetNumber);return Number.isInteger(number)&&number>0?'第'+count(number)+'話':'表示中の話';}
  function memoryDraftKey(bookId,id) {return bookId+'|'+(id||'new');}
  function readMemoryForm(form) {const data=new FormData(form);return {id:form.dataset.factId||'',text:String(data.get('text')||''),uncertain:data.get('uncertain')==='on'};}
  function rememberMemoryDraft() {
    const form=$('[data-form="speech-memory"]');if(!form||form.dataset.dirty!=='true'||form.dataset.saving==='true')return;
    memoryDrafts.set(memoryDraftKey(form.dataset.bookId,form.dataset.factId),readMemoryForm(form));
  }
  function memoryDraftChanged(form) {if(!form)return;form.dataset.dirty='true';rememberMemoryDraft();const status=$('[data-memory-draft-status]',form);if(status)status.textContent='未保存の変更があります。保存すると手動固定します。';}
  function memoryCoverageHtml(bundle) {
    const coverage=bundle&&bundle.coverage||{},memory=bundle&&bundle.memory||{},target=Number(coverage.targetNumber),through=Number(coverage.throughNumber),missing=Number(coverage.missingCount),asOf=Number(memory.throughChapterIndex);
    return '<section class="story-memory-coverage" data-memory-coverage><h2>参照する時点と記録範囲</h2><p class="story-memory-target">'+(Number.isInteger(target)&&target>0?'第'+count(target)+'話を解析する前のメモ':'作品に保存したメモ')+'</p><p>'+(Number.isInteger(through)&&through>0?'第'+count(Math.max(1,Number(coverage.fromNumber)||1))+'〜'+count(through)+'話の記録を確認済み':'過去の話の記録はまだありません')+'</p>'+(Number.isInteger(asOf)&&asOf>=0?'<p>表示中の自動メモ：第'+count(asOf+1)+'話の時点</p>':'')+(Number.isInteger(missing)&&missing>0?'<p class="story-memory-missing">準備が必要な過去の話：'+count(missing)+'話。解析を開始する前に送信範囲を確認できます。</p>':'')+'<p class="speech-footnote">この話より後の自動メモは参照しません。手動修正・追加・削除は、表示中の話以降に適用します。それより前の記録は変わりません。</p>'+(Number(memory.droppedFacts)>0?'<p class="speech-footnote">記録上限により整理した項目：'+count(memory.droppedFacts)+'件</p>':'')+'</section>';
  }
  function memoryFactSource(fact,bookId) {
    const chapters=detailsCache.get(bookId)?.chapters||[],numbers=[...new Set((fact.citations||[]).map(c=>chapters.find(ch=>ch.url===c.chapterUrl)?.index).filter(index=>Number.isInteger(index)).map(index=>index+1))];
    return numbers.length?'出典：'+numbers.map(n=>'第'+count(n)+'話').join('、'):Array.isArray(fact.citations)&&fact.citations.length?'出典 '+count(fact.citations.length)+'区間':'出典未登録';
  }
  function renderMemoryPage() {
    const page=state.memoryPage;if(!page)return '<section class="page"><h1>作品メモ</h1><p>作品を選んでください。</p></section>';
    const book=getBook(page.bookId)||{},all=page.bundle?.memory?.facts||[],q=page.query.trim(),filtered=all.filter(f=>(!q||String(f.text||'').includes(q))&&(page.filter==='all'||page.filter==='manual'&&f.manual||page.filter==='uncertain'&&f.uncertain)),size=20,totalPages=Math.max(1,Math.ceil(filtered.length/size));page.page=clamp(page.page,0,totalPages-1);
    const cards=filtered.slice(page.page*size,(page.page+1)*size).map(f=>'<article class="story-memory-fact" data-memory-id="'+esc(f.id)+'"><div class="story-memory-fact-top"><strong>'+esc(memoryTypeLabels[f.type]||'作品の情報')+'</strong><span class="tag subtle">'+(f.manual?'手動固定':'GPTの記録')+'</span></div>'+(f.uncertain?'<p class="story-memory-uncertain">未確定として扱う</p>':'')+'<p class="story-memory-text">'+esc(f.text)+'</p><p class="story-memory-source">'+esc(memoryFactSource(f,page.bookId))+'</p><div class="story-memory-fact-actions"><button class="button secondary compact" data-action="speech-memory-edit" data-id="'+esc(f.id)+'">内容を編集'+icon('right')+'</button>'+(memoryDrafts.has(memoryDraftKey(page.bookId,f.id))?'<span class="cast-draft-badge">未保存の下書き</span>':'')+'</div></article>').join('');
    return '<section class="page story-memory-page" data-memory-book="'+esc(page.bookId)+'"><button class="text-button" data-action="speech-memory-back">'+icon('left')+(page.origin?.view==='reader'?'読書に戻る':'前の画面へ')+'</button><div class="page-heading"><div><p class="eyebrow">人物関係と場面の記録</p><h1>作品メモ</h1><p class="cast-book-title">'+esc(book.title||'小説')+'</p></div><button class="icon-button" data-action="speech-memory-new" aria-label="作品メモを追加">'+icon('plus')+'</button></div><div class="cast-page-note"><strong>次の話へ引き継ぐ情報を確認</strong><p>精密解析で人物関係・出来事・未確定情報を使います。読み方や声は「配役・声」「区間を修正」で変更します。メモを開くだけでは本文を送信しません。</p></div><div data-memory-progress>'+historyProgressHtml(speechRangesByBook.get(page.bookId),true)+'</div>'+(page.loading?'<div class="loading-state"><span class="spinner"></span><span>作品メモを読み込み中…</span></div>':page.error?'<div class="error-box">'+esc(page.error)+'<button class="button secondary" data-action="speech-memory-refresh">再読み込み</button></div>':memoryCoverageHtml(page.bundle))+'<div class="section-heading"><h2>メモの内容</h2><button class="text-button" data-action="speech-memory-refresh">'+icon('refresh')+'再読み込み</button></div><form class="searchbox" data-form="speech-memory-filter">'+icon('search')+'<input name="query" type="search" aria-label="作品メモを検索" placeholder="メモの内容で検索" value="'+esc(page.query)+'"><button class="button" type="submit">検索</button></form><div class="filter-row" role="group" aria-label="作品メモの絞り込み">'+[['all','すべて'],['manual','手動固定'],['uncertain','未確定']].map(([value,label])=>'<button class="pill'+(page.filter===value?' active':'')+'" data-action="speech-memory-filter" data-value="'+value+'" aria-pressed="'+(page.filter===value)+'">'+label+'</button>').join('')+'</div><p class="story-memory-count">全 '+count(all.length)+'件 · 表示 '+count(filtered.length)+'件</p>'+(memoryDrafts.has(memoryDraftKey(page.bookId,''))?'<button class="cast-new-draft" data-action="speech-memory-new">'+icon('file')+'追加中の下書きを開く'+icon('right')+'</button>':'')+(cards?'<div class="story-memory-facts">'+cards+'</div>':!page.loading&&!page.error?'<div class="inline-empty">'+(all.length?'一致するメモがありません。':'メモはまだありません。次の精密解析で必要な過去の話から準備します。手動で情報を追加することもできます。')+'</div>':'')+'<div class="pager"><button class="button secondary compact" data-action="speech-memory-page" data-page="'+(page.page-1)+'"'+(page.page===0?' disabled':'')+'>'+icon('left')+'前へ</button><span>'+(page.page+1)+' / '+totalPages+'</span><button class="button secondary compact" data-action="speech-memory-page" data-page="'+(page.page+1)+'"'+(page.page+1>=totalPages?' disabled':'')+'>次へ'+icon('right')+'</button></div><button class="button secondary full" data-action="speech-memory-new">'+icon('plus')+'メモを追加</button><p class="speech-footnote">初回は過去のメモ準備に時間がかかります。準備済みの記録は再利用します。手動で保存した内容はGPTによる更新で元に戻しません。</p><div class="cast-page-footer"><button class="text-button" data-action="speech-settings">'+icon('settings')+'音声設定</button>'+(page.context.chapterUrl?'<button class="text-button" data-action="speech-memory-analysis">'+icon('headphones')+'解析・読み上げに戻る</button>':'')+'</div></section>';
  }
  async function refreshStoryMemory(quiet=false) {
    const page=state.memoryPage;if(!page||state.view!=='memory')return;
    const ticket=++page.request;if(!quiet){page.loading=true;page.error='';renderShell(true);}
    try {const bundle=await call('speechMemory',page.context);if(state.memoryPage!==page||state.view!=='memory'||ticket!==page.request)return;page.bundle=bundle;page.loading=false;page.error='';if(Array.isArray(bundle.cast))speechCastByBook.set(page.bookId,bundle.cast);renderShell(true);}
    catch(error){if(state.memoryPage!==page||ticket!==page.request)return;page.loading=false;page.error=error.message;if(!quiet)renderShell(true);else updateStoryMemoryProgress();}
  }
  async function showStoryMemory(requestedBookId) {
    const bookId=requestedBookId||speech.context?.bookId;if(!bookId){toast('作品を選んでから作品メモを開いてください');return;}
    if(state.view==='memory'&&state.memoryPage?.bookId===bookId){closeSheet();renderShell(true);return;}
    const reading=readingPosition(),saved=(getBook(bookId)||{}).position,origin={view:state.view,bookId:state.reader?.book.id,position:reading},chapterUrl=(reading&&origin.bookId===bookId?reading.chapterUrl:speech.context?.bookId===bookId?speech.context.chapterUrl:saved?.chapterUrl)||'';
    flushReadingPosition();closeSheet();++state.operation;state.memoryPage={bookId,context:{bookId,...(chapterUrl?{chapterUrl}:{})},origin,bundle:null,query:'',filter:'all',page:0,loading:true,error:'',request:0};state.reader=null;state.view='memory';renderShell();await refreshStoryMemory();
  }
  async function returnFromMemory(openPlayer=false) {
    rememberMemoryDraft();const page=state.memoryPage;if(!page){navigate('library');return;}const origin=page.origin||{},context={...page.context};closeSheet();
    if(origin.view==='reader'&&origin.bookId)await openBook(origin.bookId,{...(origin.position?{chapterUrl:origin.position.chapterUrl,position:origin.position}:{})});else navigate(['library','search','downloads','settings'].includes(origin.view)?origin.view:'library');
    if(openPlayer&&context.chapterUrl){await refreshSpeech(context,true);renderSpeechSheet();}
  }
  function updateStoryMemoryProgress() {const page=state.memoryPage;if(!page||state.view!=='memory')return;const target=$('[data-memory-progress]');if(target)updateAnalysisRegion(target,historyProgressHtml(speechRangesByBook.get(page.bookId),true));}
  function showMemoryEditor(id='') {
    const page=state.memoryPage;if(!page)return;rememberMemoryDraft();const original=(page.bundle?.memory?.facts||[]).find(f=>f.id===id);if(id&&!original){toast('このメモは更新されています。再読み込みしてください',true);return;}
    const draft=memoryDrafts.get(memoryDraftKey(page.bookId,id)),fact=draft||original||{id:'',text:'',uncertain:false};
    openSheet(id?'作品メモを編集':'作品メモを追加','<p class="sheet-description">保存した変更は、'+memoryTargetLabel(page.bundle)+'以降に適用します。前の話のメモは保持します。GPTの更新で上書きしません。新たな本文送信は行いません。</p><form data-form="speech-memory" data-book-id="'+esc(page.bookId)+'" data-fact-id="'+esc(id)+'" data-dirty="'+!!draft+'"><p class="cast-draft-status" data-memory-draft-status role="status">'+(draft?'未保存の下書きを復元しました。':original?.manual?'手動固定した情報です。':'必要な訂正や補足を入力してください。')+'</p><label class="field-label" for="memory-fact-text">メモの内容</label><textarea class="field-full story-memory-editor" id="memory-fact-text" name="text" required maxlength="320" rows="6" placeholder="例：二人は兄妹。会話で呼ぶ名前と地の文の名前が異なる。">'+esc(fact.text)+'</textarea><label class="speech-check"><input name="uncertain" type="checkbox"'+(fact.uncertain?' checked':'')+'>未確定の情報として扱う</label><p class="speech-footnote">推測や確定していない関係は、未確定として保存できます。手動固定しても、内容が正しいと保証されるわけではありません。</p><button class="button full" type="submit">手動固定して保存</button></form>'+(id?'<button class="text-button danger" data-action="speech-memory-delete" data-id="'+esc(id)+'">'+icon('trash')+'このメモを削除</button>':'')+'<button class="text-button" data-action="speech-memory-list">作品メモへ戻る</button>',{kind:'speech-memory-edit',bookId:page.bookId,factId:id});
    const form=$('[data-form="speech-memory"]');form.__memoryContext={...page.context};
  }
  async function saveMemoryFact(form) {
    if(form.dataset.saving==='true')return;const fact=readMemoryForm(form);fact.text=fact.text.trim();if(!fact.text)throw new Error('メモの内容を入力してください。');if(fact.text.length>320)throw new Error('メモは320文字以内で入力してください。');
    const context={...form.__memoryContext},key=memoryDraftKey(context.bookId,fact.id),page=state.memoryPage;memoryDrafts.set(key,fact);form.dataset.saving='true';const controls=Array.from(form.elements);controls.forEach(el=>el.disabled=true);
    try {const bundle=await call('speechMemorySave',{...context,changes:[{...fact,...(!fact.id?{type:'state'}:{})}],removeIds:[]});memoryDrafts.delete(key);if(state.memoryPage===page&&page.bookId===context.bookId){++page.request;page.bundle=bundle;page.error='';page.loading=false;}if(form.isConnected){form.dataset.dirty='false';closeSheet();}else if(state.view==='memory'&&state.memoryPage===page)renderShell(true);toast('作品メモを手動固定して保存しました');}
    finally {form.dataset.saving='false';controls.forEach(el=>el.disabled=false);}
  }
  function confirmMemoryDelete(id) {
    const page=state.memoryPage,fact=page?.bundle?.memory?.facts?.find(f=>f.id===id);if(!page||!fact)return;
    openSheet('作品メモを削除','<p class="sheet-description">この項目を、'+memoryTargetLabel(page.bundle)+'以降の作品メモから削除します。前の話のメモは保持します。</p><p class="quote-preview">'+esc(fact.text)+'</p><div class="sheet-actions"><button class="button secondary" data-action="speech-memory-edit" data-id="'+esc(id)+'">戻る</button><button class="button danger" data-action="speech-memory-delete-confirm" data-id="'+esc(id)+'">削除する</button></div>',{kind:'speech-memory-delete',bookId:page.bookId,factId:id});
  }
  async function deleteMemoryFact(button) {
    if(button.disabled)return;const page=state.memoryPage,context=page&&{...page.context},id=button.dataset.id;if(!page||!id)return;button.disabled=true;const sheet=state.sheet;
    try {const bundle=await call('speechMemorySave',{...context,changes:[],removeIds:[id]});memoryDrafts.delete(memoryDraftKey(context.bookId,id));if(state.memoryPage===page){++page.request;page.bundle=bundle;page.error='';}if(state.sheet===sheet)closeSheet();else if(state.view==='memory'&&state.memoryPage===page)renderShell(true);toast('作品メモを削除しました');}finally{if(button.isConnected)button.disabled=false;}
  }
  function castForBook(bookId) {return speechCastByBook.get(bookId)||(speech.context&&speech.context.bookId===bookId?speech.cast:[]);}
  function castDraftKey(bookId,id) {return bookId+'|'+id;}
  function readCastForm(form) {
    const data=new FormData(form);
    return {id:form.dataset.castId,name:String(data.get('name')||''),aliases:String(data.get('aliases')||'').split(/\r?\n/).map(v=>v.trim()).filter(Boolean),tier:String(data.get('tier')||'support'),gender:String(data.get('gender')||'neutral'),offlineVoice:String(data.get('offlineVoice')||'metan'),deviceVoice:String(data.get('deviceVoice')||''),style:String(data.get('style')||''),rate:String(data.get('rate')||''),pitch:String(data.get('pitch')||'')};
  }
  function rememberCastDraft() {
    const form=$('[data-form="speech-cast"]');if(!form||form.dataset.dirty!=='true')return;
    castDrafts.set(castDraftKey(form.dataset.bookId,form.dataset.castId),readCastForm(form));
  }
  function castDraftChanged(form) {
    if(!form||form.dataset.form!=='speech-cast')return;
    form.dataset.dirty='true';rememberCastDraft();const status=$('[data-cast-draft-status]',form);if(status)status.textContent='未保存の変更があります。この画面を閉じても下書きは残ります。';
  }
  async function showSpeechCast(requestedBookId) {
    const bookId=requestedBookId||(state.castPage&&state.view==='cast'?state.castPage.bookId:speech.context&&speech.context.bookId);
    if(!bookId){toast('作品を開いてから配役ページを選んでください');return;}
    if(state.view==='cast'&&state.castPage&&state.castPage.bookId===bookId){closeSheet();renderShell(true);return;}
    const reading=readingPosition();
    const origin={view:state.view,bookId:state.reader&&state.reader.book.id,position:reading,speech:!!(state.sheet&&['speech','speech-cast-edit'].includes(state.sheet.kind)),context:speech.context?{...speech.context}:null};
    const savedPosition=(getBook(bookId)||{}).position;
    const chapterUrl=(reading&&origin.bookId===bookId?reading.chapterUrl:origin.context&&origin.context.bookId===bookId?origin.context.chapterUrl:savedPosition&&savedPosition.chapterUrl)||'';
    flushReadingPosition();closeSheet();const operation=++state.operation;
    state.castPage={bookId,context:{bookId,chapterUrl},query:'',filter:'all',page:0,loading:true,origin,error:''};state.reader=null;state.view='cast';renderShell();
    try {
      const packRevision=offlinePackRevision,bundle=await call('speechState',{bookId,...(chapterUrl?{chapterUrl}:{})});
      if(bundle.offlinePacks&&packRevision===offlinePackRevision)applyOfflinePacks(bundle.offlinePacks);
      if(bundle.settings)speech.settings={...speechDefaults,...bundle.settings};if(Array.isArray(bundle.voiceCatalog))speech.voiceCatalog=bundle.voiceCatalog;
      if(Array.isArray(bundle.cast))speechCastByBook.set(bookId,bundle.cast);
      if(state.operation!==operation||state.view!=='cast'||!state.castPage||state.castPage.bookId!==bookId)return;
      state.castPage.loading=false;renderShell();
    }catch(error){if(state.operation===operation&&state.castPage&&state.castPage.bookId===bookId){state.castPage.loading=false;state.castPage.error=error.message;renderShell();}}
  }
  function renderCastPage() {
    const page=state.castPage;if(!page)return '<section class="page"><h1>配役・声</h1><p>本棚から作品を選んでください。</p></section>';
    const book=getBook(page.bookId)||{},all=castForBook(page.bookId),query=page.query.trim().toLocaleLowerCase('ja-JP');
    const filtered=all.filter(c=>(page.filter==='all'||c.tier===page.filter)&&(!query||[c.name,c.id,...(Array.isArray(c.aliases)?c.aliases:[])].join('\n').toLocaleLowerCase('ja-JP').includes(query)));
    const size=24,totalPages=Math.max(1,Math.ceil(filtered.length/size));page.page=clamp(page.page,0,totalPages-1);
    const draftEntries=[...castDrafts.entries()].filter(([key])=>key.startsWith(page.bookId+'|'));
    const cards=filtered.slice(page.page*size,(page.page+1)*size).map(c=>`<article class="speech-cast-row" data-id="${esc(c.id)}"><div class="cast-card-heading"><span class="speech-avatar">${icon(c.id==='narrator'?'book':'people')}</span><div class="cast-card-name"><h3>${esc(c.name||c.id)}</h3><p>${esc(castTierLabels[c.tier]||'その他')} · ${esc(castGenderLabels[c.gender]||'指定なし')}</p></div><span class="tag subtle">声を固定</span></div><div class="cast-voice-map"><span>キャラ音声</span><strong>${esc(offlineVoiceName(offlineVoiceForCast(c)))}</strong>${icon('headphones')}</div>${Array.isArray(c.aliases)&&c.aliases.length?`<p class="cast-aliases"><span>別名</span> ${esc(c.aliases.join(' / '))}</p>`:''}<p class="cast-style">${esc(c.style||'標準の話し方')}</p><div class="cast-card-meta"><span>${c.profileLocked||c.manual?'手動設定を保持':'自動登録 · 声は保持'}</span>${castDrafts.has(castDraftKey(page.bookId,c.id))?'<span class="cast-draft-badge">未保存の下書き</span>':''}</div><div class="cast-card-actions"><button class="button secondary compact" data-action="speech-cast-edit" data-id="${esc(c.id)}" data-book="${esc(page.bookId)}">配役を編集${icon('right')}</button><button class="text-button" data-action="speech-cast-preview" data-id="${esc(c.id)}" data-book="${esc(page.bookId)}" aria-label="${esc(c.name||c.id)}の声を試聴">${icon('play')}試聴</button></div></article>`).join('');
    return `<section class="page cast-page" data-cast-book="${esc(page.bookId)}"><button class="text-button cast-back" data-action="speech-cast-back">${icon('left')}${page.origin&&page.origin.view==='reader'?'読書に戻る':'前の画面へ'}</button><div class="page-heading"><div><p class="eyebrow">作品ごとの固定配役</p><h1>配役・声</h1><p class="cast-book-title">${esc(book.title||'小説')}</p></div><button class="icon-button" data-action="speech-cast-new" data-book="${esc(page.bookId)}" aria-label="登場人物を追加">${icon('plus')}</button></div><div class="cast-page-note"><strong>人物と声の対応を保存</strong><p>GPTが話者と感情を解析し、端末がここで決めた声で読み上げます。声の割り当ては、再解析や章の移動でも保持します。変更はこのページで行えます。</p></div><div class="offline-preview-status" data-voice-preview-status role="status" hidden></div><div class="cast-page-tools"><form class="searchbox" data-form="speech-cast-filter">${icon('search')}<input type="search" name="query" value="${esc(page.query)}" placeholder="人物名・別名で検索" aria-label="人物名・別名で検索"><button class="button" type="submit">検索</button></form><div class="filter-row" role="group" aria-label="登場人物の役割で絞り込む">${[['all','すべて'],...Object.entries(castTierLabels)].map(([value,label])=>`<button class="pill${page.filter===value?' active':''}" data-action="speech-cast-filter" data-value="${value}" aria-pressed="${page.filter===value}">${label}</button>`).join('')}</div></div>${draftEntries.filter(([,draft])=>!all.some(c=>c.id===draft.id)).map(([,draft])=>`<button class="cast-new-draft" data-action="speech-cast-edit" data-id="${esc(draft.id)}" data-book="${esc(page.bookId)}">${icon('people')}追加中の下書き：${esc(draft.name||'名前未入力')}${icon('right')}</button>`).join('')}<div class="row between cast-page-count"><span>全 ${count(all.length)}人</span><span>${count(filtered.length)}人 · ${page.page+1} / ${totalPages}ページ</span></div>${page.loading?'<div class="loading-state"><span class="spinner"></span><span>この作品の配役を読み込み中…</span></div>':page.error?`<div class="error-box">${esc(page.error)}<button class="button secondary" data-action="speech-cast-refresh">再読み込み</button></div>`:cards?`<div class="speech-cast-list">${cards}</div>`:'<div class="inline-empty">該当する人物がいません。名前や別名を登録できます。</div>'}<div class="pager"><button class="button secondary compact" data-action="speech-cast-page" data-page="${page.page-1}"${page.page===0?' disabled':''}>${icon('left')}前へ</button><span>${page.page+1} / ${totalPages}</span><button class="button secondary compact" data-action="speech-cast-page" data-page="${page.page+1}"${page.page+1>=totalPages?' disabled':''}>次へ${icon('right')}</button></div><button class="button secondary full" data-action="speech-cast-new" data-book="${esc(page.bookId)}">${icon('plus')}登場人物を追加</button><p class="speech-footnote">${speech.settings.engine==='offline'?'試聴は端末内で短いサンプルを生成します。初回は音声設定で音声データをダウンロードしてください。':'現在の試聴は端末音声です。キャラ音声は音声設定で選べます。'}</p><div class="cast-page-footer"><button class="text-button" data-action="speech-settings">${icon('settings')}音声設定</button>${page.context.chapterUrl?'<button class="text-button" data-action="speech-back">'+icon('headphones')+'読み上げに戻る</button>':''}</div></section>`;
  }
  async function returnFromCast(openPlayer=false) {
    rememberCastDraft();const page=state.castPage;if(!page){navigate('library');return;}
    const origin=page.origin||{},context={...page.context};closeSheet();
    if(origin.view==='reader'&&origin.bookId)await openBook(origin.bookId,{...(origin.position?{chapterUrl:origin.position.chapterUrl,position:origin.position}:{})});else navigate(['library','search','downloads','settings'].includes(origin.view)?origin.view:'library');
    if(openPlayer&&context.chapterUrl){await refreshSpeech(context,true);renderSpeechSheet();}
  }
  function showCastEditor(id,bookId) {
    bookId=bookId||(state.castPage&&state.view==='cast'?state.castPage.bookId:speech.context&&speech.context.bookId);if(!bookId)return;
    rememberCastDraft();
    const existing=castForBook(bookId).find(c=>c.id===id),draft=id&&castDrafts.get(castDraftKey(bookId,id));
    const c=draft||existing||{id:id||'character-'+Date.now(),name:'',aliases:[],gender:'neutral',tier:'support',offlineVoice:speech.settings.offlineDefaultVoice,deviceVoice:'',style:'',rate:1,pitch:1};
    const book=getBook(bookId)||{};
    openSheet(existing?'配役を編集':'登場人物を追加',`<p class="cast-editor-book">${esc(book.title||'この作品')}の配役</p><form data-form="speech-cast" data-cast-id="${esc(c.id)}" data-book-id="${esc(bookId)}" data-dirty="${draft?'true':'false'}"><p class="cast-draft-status" data-cast-draft-status role="status">${draft?'未保存の下書きを復元しました。':'保存するとこの人物の声と設定を固定します。'}</p><label class="field-label" for="cast-name">名前</label><input class="field-full" id="cast-name" name="name" required maxlength="100" value="${esc(c.name)}"><label class="field-label" for="cast-aliases">別名・呼び名</label><textarea class="field-full" id="cast-aliases" name="aliases" rows="2" maxlength="2000" placeholder="一行にひとつ。例：姓、愛称、通称">${esc((c.aliases||[]).join('\n'))}</textarea><p class="speech-footnote">同じ人物の別名を登録すると、GPTが話者を照合するときに使います。</p><div class="speech-form-grid"><label>役割<select name="tier">${optionsHtml(Object.entries(castTierLabels),c.tier)}</select></label><label>声の性別表現<select name="gender">${optionsHtml(Object.entries(castGenderLabels),c.gender)}</select></label></div><label class="field-label" for="cast-voice">この人物のキャラ音声</label><select class="field-full" id="cast-voice" name="offlineVoice">${offlineVoiceOptions(offlineVoiceForCast(c))}</select><p class="cast-voice-lock">${icon('check')}この声を固定 · 再解析でも変更しません</p><label class="field-label" for="cast-style">GPTに伝える話し方</label><textarea class="field-full" id="cast-style" name="style" rows="2" maxlength="240" placeholder="例：低めの声で、落ち着いて話す">${esc(c.style)}</textarea><div class="speech-form-grid"><label>話す速さ<input name="rate" type="number" min="0.5" max="1.5" step="0.05" value="${esc(c.rate||1)}"></label><label>声の高さ<input name="pitch" type="number" min="0.5" max="2" step="0.05" value="${esc(c.pitch||1)}"></label></div><p class="speech-footnote">1が標準です。性別表現・話し方はGPTの解析に使います。声は選んだ音声で固定し、抑揚は音声の対応スタイル・速さ・高さへ反映します。「ゆっくり」「早口」「落ち着く」「元気」などに対応します。指示どおりの演技になるとは限りません。</p><details class="speech-advanced"><summary>この人物の端末音声</summary><label class="field-label" for="cast-device">端末の音声</label><select class="field-full" id="cast-device" name="deviceVoice">${deviceVoiceOptions(c.deviceVoice)}</select><button class="text-button" type="button" data-action="speech-load-voices">${icon('refresh')}端末で使える声を取得</button></details><button class="button full" type="submit">この配役を固定して保存</button><button class="button secondary full speech-preview-button" type="button" data-action="speech-preview">${icon('play')}保存してこの声を試す</button><p class="speech-footnote">${speech.settings.engine==='offline'?'試聴は端末内で行います。音声データを準備済みなら、通信は不要です。':'現在の試聴は端末音声です。キャラ音声は音声設定で選べます。'}</p></form><div class="offline-preview-status" data-voice-preview-status role="status" hidden></div><button class="text-button" data-action="speech-cast-list" data-book="${esc(bookId)}">配役ページへ戻る</button>`,{kind:'speech-cast-edit',bookId,castId:c.id});
  }
  async function previewCast(bookId,id) {
    await speechControl('preview',{bookId,chapterUrl:'',speakerId:id});toast('声の試聴を開始しました');
  }
  async function saveSpeechCast(form,data,preview=false) {
    if(!form.reportValidity())return;
    const id=form.dataset.castId,bookId=form.dataset.bookId,draft=readCastForm(form);
    const character={...draft,name:draft.name.trim(),aliases:[...new Set(draft.aliases)],style:draft.style.trim(),rate:clamp(draft.rate,.5,1.5),pitch:clamp(draft.pitch,.5,2),voiceLocked:true,profileLocked:true,manual:true};
    if(!character.name)throw new Error('登場人物の名前を入力してください。');
    const submitted=JSON.stringify(draft),wasPlaying=speech.playback.bookId===bookId&&['playing','paused','loading'].includes(speech.playback.status);form.dataset.saving='true';
    const saved=await call('speechCast',{bookId,cast:[character]});
    const previous=castForBook(bookId),fallback=previous.some(c=>c.id===id)?previous.map(c=>c.id===id?{...c,...character}:c):[...previous,character];
    const cast=Array.isArray(saved)?saved:Array.isArray(saved.cast)?saved.cast:fallback;speechCastByBook.set(bookId,cast);
    if(speech.context&&speech.context.bookId===bookId)speech.cast=cast;
    const latestDraft=castDrafts.get(castDraftKey(bookId,id));
    if((form.isConnected&&JSON.stringify(readCastForm(form))!==submitted)||(latestDraft&&JSON.stringify(latestDraft)!==submitted)){form.dataset.saving='false';rememberCastDraft();toast('送信した配役を保存しました。入力中の変更は下書きに残っています。');return;}
    castDrafts.delete(castDraftKey(bookId,id));form.dataset.dirty='false';form.dataset.saving='false';
    const stillEditing=form.isConnected&&state.sheet&&state.sheet.kind==='speech-cast-edit'&&state.sheet.bookId===bookId&&state.sheet.castId===id;
    if(stillEditing){if(preview)showCastEditor(id,bookId);else{closeSheet();if(state.view==='cast'&&state.castPage.bookId===bookId)renderShell(true);}}
    else if(state.view==='cast'&&state.castPage&&state.castPage.bookId===bookId)renderShell(true);
    if(preview)await previewCast(bookId,id);else toast(wasPlaying?'配役を保存しました。再生は停止し、次の再生から新しい設定を使います。':'この人物の配役を固定して保存しました');
  }
  function showSpeechUnits(page=0,query='') {
    const all=speech.plan&&speech.plan.units||[],q=String(query||'').trim(),units=all.filter(u=>!q||String(u.text||'').includes(q)||String(Number(u.index)+1)===q),size=20;
    page=clamp(page,0,Math.max(0,Math.ceil(units.length/size)-1));
    openSheet('話者・読み方を修正','<form class="toc-search" data-form="speech-unit-filter"><input name="query" type="search" aria-label="読み上げ区間を検索" placeholder="本文・区間番号で検索" value="'+esc(q)+'"><button class="button secondary compact" type="submit">検索</button></form><div class="row between speech-unit-count"><span>全 '+count(all.length)+'区間</span><span>'+count(units.length)+'件 · '+(page+1)+' / '+Math.max(1,Math.ceil(units.length/size))+'ページ</span></div><div class="speech-unit-list">'+units.slice(page*size,(page+1)*size).map(u=>'<button class="speech-unit-row" data-action="speech-unit-edit" data-id="'+esc(u.unitId)+'"><span class="speech-unit-meta">区間 '+count(Number(u.index)+1)+' · '+esc(speechSpeaker(u.speakerId))+' · '+esc(kindLabels[u.kind]||'地の文')+(u.locked?' · 手動固定':u.analyzed?' · 話者のAI自己評価 '+Math.round(clamp(u.confidence,0,1)*100)+'%':' · 未解析')+'</span><span class="speech-unit-text">'+esc(u.text)+'</span><span class="speech-unit-emotion">'+esc(unitPerformance(u)?'細かい演技 · '+unitPerformance(u).parts.length+'句':emotionLabels[u.emotion]||u.emotion||'自然')+'</span><span class="speech-unit-pronunciation">'+pronunciationStatus(u)+'</span></button>').join('')+'</div>'+(!units.length?'<div class="inline-empty">一致する区間がありません。</div>':'')+'<div class="pager"><button class="button secondary compact" data-action="speech-units" data-page="'+(page-1)+'" data-query="'+esc(q)+'"'+(page===0?' disabled':'')+'>'+icon('left')+'前へ</button><span>'+(page+1)+'</span><button class="button secondary compact" data-action="speech-units" data-page="'+(page+1)+'" data-query="'+esc(q)+'"'+((page+1)*size>=units.length?' disabled':'')+'>次へ'+icon('right')+'</button></div><button class="text-button" data-action="speech-back">読み上げに戻る</button>',{kind:'speech-units',...(speech.context||{}),unitPage:page,unitQuery:q});
  }
  function actingNumberHtml(index,key,part) {
    const [label,min,max]=actingNumberFields[key],name='part_'+index+'_'+key;
    return '<label>'+label+'<input name="'+name+'" type="number" min="'+min+'" max="'+max+'" step="'+(key==='pauseAfterMs'?'1':'any')+'" required value="'+esc(part[key])+'"></label>';
  }
  function actingPartHtml(unit,part,index) {
    const spoken=typeof unit.spokenText==='string'?unit.spokenText:String(unit.text||'');
    return '<section class="acting-part" data-acting-part="'+index+'"><div class="acting-part-heading"><strong>句 '+(index+1)+'</strong><span class="acting-confidence">'+(unit.locked?'手動固定':'感情のAI自己評価 '+Math.round(part.confidence*100)+'%')+'</span></div><p class="acting-part-text">'+esc(spoken.slice(part.start,part.end))+'</p><div class="acting-fields"><label>感情<select name="part_'+index+'_emotion">'+optionsHtml(Object.entries(performanceEmotionLabels),part.emotion)+'</select></label><label>発声<select name="part_'+index+'_delivery">'+optionsHtml(Object.entries(deliveryLabels),part.delivery)+'</select></label>'+actingNumberHtml(index,'intensity',part)+'</div><details class="acting-part-details"><summary>速さ・高さ・抑揚・音量・間・語尾・強調</summary><div class="acting-fields">'+['rate','pitch','intonation','volume','pauseAfterMs'].map(key=>actingNumberHtml(index,key,part)).join('')+'<label>語尾<select name="part_'+index+'_ending">'+optionsHtml(Object.entries(endingLabels),part.ending)+'</select></label>'+actingNumberHtml(index,'emphasis',part)+'</div><p class="speech-footnote">速さ・高さ・抑揚・音量の1は基準値です。間の1000 msは1秒です。指定の反映範囲は音声エンジンによって異なります。</p></details></section>';
  }
  function unitOverallValues(unit) {return {emotion:unit.emotion||'neutral',intensity:clamp(unit.intensity||0,0,1),rate:clamp(unit.rate||1,.5,1.5),pitch:clamp(unit.pitch||1,.5,2)};}
  function updateActingMode(form) {
    if(!form)return;const parts=form.elements.actingMode&&form.elements.actingMode.value==='parts';
    const fine=$('[data-acting-parts]',form),whole=$('[data-acting-overall]',form);
    if(fine){fine.hidden=!parts;fine.disabled=!parts;}
    if(whole){whole.hidden=!!fine&&parts;whole.disabled=!!fine&&parts;}
  }
  function showUnitEditor(id,returnDraft=null) {
    const current=(speech.plan&&speech.plan.units||[]).find(x=>x.unitId===id);if(!current)return;
    const draft=returnDraft||((state.sheet?.kind==='speech-pronunciation')?state.sheet.unitEditorDraft:null),restore=!!draft&&draft.unitId===id&&draft.context?.bookId===speech.context?.bookId&&draft.context?.chapterUrl===speech.context?.chapterUrl&&draft.original?.text===current.text&&draft.original?.spokenText===current.spokenText;
    const u=restore?{...draft.original,pronunciation:current.pronunciation,pronunciationReady:current.pronunciationReady,pronunciationLocked:current.pronunciationLocked}:current;
    const returnPage=state.sheet&&state.sheet.unitPage||0,returnQuery=state.sheet&&state.sheet.unitQuery||'',performance=unitPerformance(u),whole=unitOverallValues(u);
    const status=u.locked?'手動で固定済み':u.analyzed?'話者のAI自己評価 '+Math.round(clamp(u.confidence,0,1)*100)+'%':'未解析 · 話者と読み方を指定できます';
    const modes=performance?'<fieldset class="acting-mode"><legend>読み方の指定</legend><label><input type="radio" name="actingMode" value="parts" checked><span><strong>細かい演技を使う</strong><small>句ごとの感情・抑揚を調整</small></span></label><label><input type="radio" name="actingMode" value="overall"><span><strong>区間全体を指定</strong><small>保存すると、この区間の細かい演技を置き換えます</small></span></label></fieldset>':'';
    const parts=performance?'<fieldset class="acting-parts" data-acting-parts><legend>細かい演技</legend><p class="acting-saved-status">'+(u.locked?'手動固定':performance.reviewed?'GPT再確認済み':'GPT解析済み')+' · '+performance.parts.length+'句</p><p class="speech-footnote">ルビを含む読み上げ用の文字列で表示します。句の本文と境界は変更せず、演技だけを編集します。感情のAI自己評価は正解率ではありません。</p>'+performance.parts.map((part,index)=>actingPartHtml(u,part,index)).join('')+'</fieldset>':'';
    const overall='<fieldset class="acting-overall" data-acting-overall'+(performance?' hidden disabled':'')+'><legend>区間全体の読み方</legend>'+(performance?'<p class="note">保存すると細かい演技を解除し、以下の指定を区間全体へ適用します。GPTの再解析からは手動固定して保護します。</p>':'<p class="speech-footnote">細かい演技は未保存です。精密な演技解析をオンにして、この話を手動で解析すると句ごとに調整できます。手動固定した区間は追加解析しません。</p>')+'<div class="speech-form-grid"><label>感情<select name="emotion">'+optionsHtml(Object.entries(emotionLabels),whole.emotion)+'</select></label><label>感情の強さ<input name="intensity" type="number" min="0" max="1" step="any" required value="'+whole.intensity+'"></label></div><div class="speech-form-grid"><label>速さ<input name="rate" type="number" min="0.5" max="1.5" step="any" required value="'+whole.rate+'"></label><label>声の高さ<input name="pitch" type="number" min="0.5" max="2" step="any" required value="'+whole.pitch+'"></label></div></fieldset>';
    openSheet('区間 '+count(Number(u.index)+1)+' の読み方','<p class="speech-footnote">'+status+'。AIの自己評価は測定済みの正解率ではありません。</p><div class="quote-preview speech-unit-quote">'+esc(u.text)+'</div><div class="pronunciation-entry"><span>'+pronunciationStatus(current)+'</span><button class="button secondary full compact" data-action="speech-pronunciation" data-id="'+esc(id)+'">読み・単語の演技を編集</button><p>漢字や助詞の読み、強める言葉とその表現を句ごとに指定できます。</p></div><form data-form="speech-unit" data-unit-id="'+esc(id)+'"><label class="field-label" for="unit-kind">文章の種類</label><select class="field-full" id="unit-kind" name="kind">'+optionsHtml(Object.entries(kindLabels),u.kind)+'</select><label class="field-label" for="unit-speaker">話者</label><select class="field-full" id="unit-speaker" name="speakerId">'+optionsHtml(speech.cast.map(c=>[c.id,c.name||c.id]),u.speakerId)+'</select>'+modes+parts+overall+'<p class="speech-footnote">本文は変更せず、この区間の話者と読み方を手動固定します。話者だけを変えた場合、保存済みの細かい演技を保持します。</p><button class="button full" type="submit">修正を固定して保存</button></form><button class="text-button" data-action="speech-play-unit" data-index="'+Number(u.index)+'">'+icon('play')+'この区間から聴く</button><button class="text-button" data-action="speech-units" data-page="'+returnPage+'" data-query="'+esc(returnQuery)+'">区間一覧へ戻る</button>',{kind:'speech-unit-edit',...(speech.context||{}),unitPage:returnPage,unitQuery:returnQuery});
    const form=$('[data-form="speech-unit"]');form.__originalUnit=JSON.parse(JSON.stringify(u));form.__unitContext={...(speech.context||{})};restoreUnitEditorDraft(form,restore?draft:null);updateActingMode(form);
  }
  function unitNumericInput(data,name,min,max,label,integer=false) {
    const raw=data.get(name),value=Number(raw);if(raw===null||String(raw).trim()===''||!Number.isFinite(value)||value<min||value>max||(integer&&!Number.isInteger(value)))throw new Error(label+'を'+min+'〜'+max+'の'+(integer?'整数':'数値')+'で入力してください。');return value;
  }
  function unitEditorChanges(form,data) {
    const original=form.__originalUnit,performance=unitPerformance(original),changes={};
    if(!original)throw new Error('区間を開き直してください。');
    for(const key of ['kind','speakerId']){const value=String(data.get(key)||'');if(value!==String(original[key]||''))changes[key]=value;}
    if(performance&&data.get('actingMode')==='parts'){
      const edited=JSON.parse(JSON.stringify(performance));
      for(const [index,part]of edited.parts.entries()){
        for(const [key,choices]of Object.entries({emotion:performanceEmotionLabels,delivery:deliveryLabels,ending:endingLabels})){
          const value=String(data.get('part_'+index+'_'+key)||'');if(!Object.hasOwn(choices,value))throw new Error('句 '+(index+1)+' の演技を選択してください。');part[key]=value;
        }
        for(const [key,[label,min,max]]of Object.entries(actingNumberFields))part[key]=unitNumericInput(data,'part_'+index+'_'+key,min,max,'句 '+(index+1)+' の'+label,key==='pauseAfterMs');
      }
      if(JSON.stringify(edited)!==JSON.stringify(performance))changes.performance=edited;
    }else{
      const values={emotion:String(data.get('emotion')||''),intensity:unitNumericInput(data,'intensity',0,1,'感情の強さ'),rate:unitNumericInput(data,'rate',.5,1.5,'速さ'),pitch:unitNumericInput(data,'pitch',.5,2,'声の高さ')},before=unitOverallValues(original);
      if(!Object.hasOwn(emotionLabels,values.emotion))throw new Error('感情を選択してください。');
      for(const key of Object.keys(values))if(performance||values[key]!==before[key])changes[key]=values[key];
      if(performance)changes.performance=null;
    }
    return changes;
  }
  async function saveSpeechUnit(form,data) {
    if(form.dataset.saving==='true')return;
    const back={page:state.sheet.unitPage,query:state.sheet.unitQuery},context=form.__unitContext,changes=unitEditorChanges(form,data),submitted=JSON.stringify(Array.from(data.entries())),button=$('button[type="submit"]',form);
    form.dataset.saving='true';if(button)button.disabled=true;
    try {
      const result=await call('speechUnit',{...context,unitId:form.dataset.unitId,changes}),plan=result.plan||result;
      if(speech.context&&speech.context.bookId===context.bookId&&speech.context.chapterUrl===context.chapterUrl)speech.plan=plan;
      if(!form.isConnected){toast('区間の読み方を保存しました');return;}
      if(JSON.stringify(Array.from(new FormData(form).entries()))!==submitted){
        const saved=(plan.units||[]).find(u=>u.unitId===form.dataset.unitId);if(saved)form.__originalUnit=JSON.parse(JSON.stringify(saved));toast('送信した読み方を保存しました。入力中の変更は残っています。');return;
      }
      showSpeechUnits(back.page,back.query);toast('区間の読み方を固定して保存しました');
    } finally {form.dataset.saving='false';if(button&&button.isConnected)button.disabled=false;}
  }
  function pronunciationDraftKey(context,id,sourceHash) {return JSON.stringify([context.bookId,context.chapterUrl,id,sourceHash]);}
  // Matches Native Character.isWhitespace || Character.isSpaceChar, including blank separators.
  function pronunciationWhitespace(text) {return /^[\u0009-\u000d\u001c-\u0020\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]*$/.test(text);}
  function invalidatePronunciationDrafts(bookId,chapterUrl) {
    for(const key of pronunciationDrafts.keys()){const scope=JSON.parse(key);if(scope[0]===bookId&&scope[1]===chapterUrl)pronunciationDrafts.delete(key);}
    const form=$('[data-form="speech-pronunciation"]');if(form?.__unitContext.bookId===bookId&&form.__unitContext.chapterUrl===chapterUrl){form.__draftKey=null;form.__invalidated=true;}
  }
  function pronunciationReadings(form) {return (form.__pronunciation?.parts||[]).map((_,i)=>form.elements['reading_'+i]?.value||'');}
  function wordSelections(form) {
    return form.__pronunciation.parts.map((_,i)=>Array.from(form.querySelectorAll('[data-word-list="'+i+'"] [data-word-row]')).map(row=>({
      word:row.querySelector('[data-cue-word]').value,occurrence:Number(row.querySelector('[data-cue-occurrence]').value),
      tone:row.querySelector('[data-cue-tone]').value,strength:Number(row.querySelector('[data-cue-strength]').value)
    })));
  }
  function storedWords(parts) {
    return parts.map(part=>(part.words||[]).map(c=>({word:c.word,occurrence:c.occurrence,tone:c.tone,strength:c.strength})));
  }
  function pronunciationEditorValues(form) {return {readings:pronunciationReadings(form),words:wordSelections(form)};}
  function draftMatches(draft,signature) {
    return !!draft&&JSON.stringify({readings:draft.readings,words:draft.words})===signature;
  }
  function rememberPronunciationDraft(form=$('[data-form="speech-pronunciation"]')) {
    if(!form||!form.__draftKey||!form.__pronunciation)return;
    const values=pronunciationEditorValues(form),parts=form.__pronunciation.parts;
    if(JSON.stringify(values)===JSON.stringify({readings:parts.map(p=>p.reading),words:storedWords(parts)}))pronunciationDrafts.delete(form.__draftKey);
    else pronunciationDrafts.set(form.__draftKey,{...values,partKeys:parts.map(p=>[p.partId,p.start,p.end]),unitEditorDraft:state.sheet?.unitEditorDraft||null});
    if(pronunciationDrafts.size>50)pronunciationDrafts.delete(pronunciationDrafts.keys().next().value);
  }
  function updatePronunciationEditor(form) {
    if(!form||!form.__pronunciation)return;
    const values=pronunciationEditorValues(form),total=values.readings.reduce((sum,value)=>sum+value.length,0),parts=form.__pronunciation.parts;
    const dirty=JSON.stringify(values)!==JSON.stringify({readings:parts.map(p=>p.reading),words:storedWords(parts)});
    for(const label of form.querySelectorAll('[data-word-count]'))label.textContent=String(values.words[Number(label.dataset.wordCount)].length);
    const counter=$('[data-pronunciation-count]',form);if(counter){counter.textContent=count(total)+' / 2,000文字';counter.classList.toggle('is-over-limit',total>2000);}
    const status=$('[data-pronunciation-state]',form);if(status)status.textContent=(form.__hasPronunciation?(form.__pronunciationLocked?'読みを手動固定':'読み解析済み'):'読み未解析 · 元の表記を表示')+(dirty?' · 未保存の修正あり':'');
    const reset=$('[data-pronunciation-reset]');if(reset)reset.hidden=!form.__pronunciationLocked;
  }
  function pronunciationTemplate(data,unit) {
    const p=data?.pronunciation,spoken=typeof unit.spokenText==='string'?unit.spokenText:String(unit.text||'');
    if(!p||p.version!==1||typeof p.sourceHash!=='string'||!p.sourceHash||!Array.isArray(p.parts)||!p.parts.length||typeof data.hasPronunciation!=='boolean'||typeof data.pronunciationLocked!=='boolean')throw new Error('音声用の読みを確認できませんでした。区間を開き直してください。');
    let end=0;const ids=new Set();
    for(const part of p.parts){if(!part||typeof part.partId!=='string'||!part.partId||ids.has(part.partId)||!Number.isInteger(part.start)||!Number.isInteger(part.end)||part.start!==end||part.end<=part.start||part.end>spoken.length||typeof part.reading!=='string')throw new Error('音声用の読みと本文の区切りを確認できませんでした。区間を開き直してください。');ids.add(part.partId);end=part.end;}
    if(end!==spoken.length)throw new Error('音声用の読みと本文の範囲を確認できませんでした。区間を開き直してください。');
    return JSON.parse(JSON.stringify(p));
  }
  const wordTones={focus:'強調',soft:'やわらかく',hesitate:'ためらい',firm:'言い切り'};
  function wordRowHtml(cue={word:'',tone:'focus',strength:2,occurrence:1}) {
    const options=Object.entries(wordTones).map(([v,name])=>'<option value="'+v+'"'+(v===cue.tone?' selected':'')+'>'+name+'</option>').join('');
    const levels=['弱','中','強'].map((name,i)=>'<option value="'+(i+1)+'"'+(i+1===cue.strength?' selected':'')+'>'+name+'</option>').join('');
    return '<div class="word-row" data-word-row><label>単語の読み<input data-cue-word type="text" autocomplete="off" placeholder="例　ゼッタイ" value="'+esc(cue.word)+'"></label><div class="word-options"><label>表現<select data-cue-tone>'+options+'</select></label><label>強さ<select data-cue-strength>'+levels+'</select></label><label>何回目<input data-cue-occurrence type="number" min="1" step="1" inputmode="numeric" value="'+esc(cue.occurrence)+'"></label></div><button type="button" class="text-button" data-action="speech-word-remove">この単語を外す</button></div>';
  }
  function wordEditorHtml(index,words) {
    return '<details class="word-editor"><summary>単語の演技（<span data-word-count="'+index+'">'+words.length+'</span>件）</summary><p class="speech-footnote">上の読みから演技を付けたい言葉を入力します。同じ言葉が複数あるときは、句の中で何回目かを選べます。</p><div data-word-list="'+index+'">'+words.map(wordRowHtml).join('')+'</div><button type="button" class="button secondary compact" data-action="speech-word-add">単語を追加</button></details>';
  }
  function pronunciationPartHtml(spoken,part,index,reading,words) {
    const source=spoken.slice(part.start,part.end),silent=pronunciationWhitespace(source);
    return '<section class="pronunciation-part" data-pronunciation-silent="'+silent+'"><h3>句 '+(index+1)+'</h3><p class="pronunciation-source-label">解析元の表記（ルビを反映）</p><p class="pronunciation-source">'+esc(source)+'</p>'+(silent?'<p class="speech-footnote">空白だけの区切りです。発音する文字は追加できません。</p>':'')+'<label for="pronunciation-'+index+'">音声用の読み'+(silent?'（空白または空欄）':'')+'</label><textarea id="pronunciation-'+index+'" name="reading_'+index+'" rows="2" maxlength="2000"'+(silent?'':' required')+' autocomplete="off" spellcheck="false">'+esc(reading)+'</textarea>'+(silent?'':wordEditorHtml(index,words))+'</section>';
  }
  function changeWordRow(button,add) {
    const form=button.closest('form');
    if(!form?.__pronunciation)return;
    if(add){
      const list=button.closest('details').querySelector('[data-word-list]');
      list.insertAdjacentHTML('beforeend',wordRowHtml());list.lastElementChild.querySelector('[data-cue-word]').focus();
    }else{
      const details=button.closest('details');button.closest('[data-word-row]').remove();details.querySelector('[data-action="speech-word-add"]').focus();
    }
    rememberPronunciationDraft(form);updatePronunciationEditor(form);
  }
  function captureUnitEditorDraft() {
    const form=$('[data-form="speech-unit"]');
    return form?.__originalUnit?{unitId:form.dataset.unitId,original:JSON.parse(JSON.stringify(form.__originalUnit)),context:{...form.__unitContext},values:Array.from(new FormData(form).entries())}:state.sheet?.unitEditorDraft||null;
  }
  function restoreUnitEditorDraft(form,draft) {
    if(!draft)return;
    for(const [name,value]of draft.values||[])for(const field of Array.from(form.elements).filter(e=>e.name===name)){if(field.type==='radio')field.checked=field.value===value;else field.value=value;}
    updateActingMode(form);
  }
  async function showPronunciationEditor(id) {
    const context=speech.context&&{...speech.context},unit=(speech.plan?.units||[]).find(u=>u.unitId===id),sheet=state.sheet;if(!context||!unit||!sheet)return;
    const ticket=++pronunciationOpenSequence,button=$('[data-action="speech-pronunciation"][data-id="'+CSS.escape(id)+'"]');if(button)button.disabled=true;
    try {
      const data=await call('speechPronunciation',{...context,unitId:id});
      if(ticket!==pronunciationOpenSequence||state.sheet!==sheet||speech.context?.bookId!==context.bookId||speech.context?.chapterUrl!==context.chapterUrl)return;
      const p=pronunciationTemplate(data,unit),key=pronunciationDraftKey(context,id,p.sourceHash),draft=pronunciationDrafts.get(key),partKeys=p.parts.map(part=>[part.partId,part.start,part.end]),usableDraft=draft&&JSON.stringify(draft.partKeys)===JSON.stringify(partKeys)&&draft.readings.length===p.parts.length?draft:null;
      const unitEditorDraft=captureUnitEditorDraft()||usableDraft?.unitEditorDraft||null,returnPage=sheet.unitPage||0,returnQuery=sheet.unitQuery||'',spoken=typeof unit.spokenText==='string'?unit.spokenText:String(unit.text||'');
      openSheet('区間 '+count(Number(unit.index)+1)+' の読み・単語の演技','<p class="sheet-description">音声用の読みと、強めたりためらったりする言葉を確認・修正します。小説の本文と、演技の句の区切りは変更しません。</p><div class="pronunciation-original"><strong>小説の本文</strong><div class="quote-preview speech-unit-quote">'+esc(unit.text)+'</div></div><form data-form="speech-pronunciation" data-unit-id="'+esc(id)+'"><p class="pronunciation-state" data-pronunciation-state></p><p class="speech-footnote">解析元には作者のルビを反映しています。GPTの解析ではそのルビを優先し、この画面の手動修正でのみ上書きできます。未解析の欄は元の表記です。</p>'+p.parts.map((part,i)=>pronunciationPartHtml(spoken,part,i,usableDraft?usableDraft.readings[i]:part.reading,usableDraft?.words?.[i]||part.words||[])).join('')+'<div class="pronunciation-limit"><span data-pronunciation-count></span><small>1区間の合計。絵文字などは2文字で数える場合があります。空白だけの句を除き、空欄にはできません。改行・制御文字は使えません。</small></div><p class="speech-footnote">音として読ませたい表記をかなで入力してください。例：「母は花を」→「ハハワハナオ」。元の表記のままの句や、漢字が残る句は音声エンジンが読みます。読みと単語の演技をまとめて保存します。配役と句全体の演技は別に設定できます。</p><button class="button full" type="submit">読みと単語の演技を固定して保存</button></form><details class="pronunciation-reset" data-pronunciation-reset'+(data.pronunciationLocked?'':' hidden')+'><summary>手動の読み固定を解除</summary><p>保存した読みと単語の演技を削除します。次の解析で付け直します。解析は自動で開始しません。配役・演技は保持します。</p><button class="button secondary full" data-action="speech-pronunciation-reset">固定を解除して読みを削除</button></details><button class="text-button" data-action="speech-pronunciation-back" data-id="'+esc(id)+'">話者・演技の編集に戻る</button>',{kind:'speech-pronunciation',...context,unitId:id,unitPage:returnPage,unitQuery:returnQuery,unitEditorDraft});
      const form=$('[data-form="speech-pronunciation"]');form.__unitContext=context;form.__sourceText=spoken;form.__pronunciation=p;form.__hasPronunciation=data.hasPronunciation;form.__pronunciationLocked=data.pronunciationLocked;form.__draftKey=key;updatePronunciationEditor(form);rememberPronunciationDraft(form);
    } finally {if(button&&button.isConnected)button.disabled=false;}
  }
  function pronunciationInput(form,data) {
    const p=JSON.parse(JSON.stringify(form.__pronunciation)),words=wordSelections(form);let total=0;
    for(const [i,part]of p.parts.entries()){
      const reading=form.elements['reading_'+i]?.value,silent=pronunciationWhitespace(form.__sourceText.slice(part.start,part.end));if(typeof reading!=='string')throw new Error('句 '+(i+1)+' の音声用の読みを入力してください。');
      if(pronunciationWhitespace(reading)!==silent)throw new Error(silent?'句 '+(i+1)+' は空白だけの区切りです。空白または空欄にしてください。':'句 '+(i+1)+' の音声用の読みを入力してください。');
      if(/[\u0000-\u001f\u007f-\u009f]/.test(reading))throw new Error('句 '+(i+1)+' の読みには改行・タブ・制御文字を使えません。');
      for(let c=0;c<reading.length;c++){const value=reading.charCodeAt(c);if(value>=0xd800&&value<=0xdbff){const next=reading.charCodeAt(++c);if(!(next>=0xdc00&&next<=0xdfff))throw new Error('句 '+(i+1)+' の読みで使えない文字が見つかりました。');}else if(value>=0xdc00&&value<=0xdfff)throw new Error('句 '+(i+1)+' の読みで使えない文字が見つかりました。');}
      const cues=words[i];
      for(const cue of cues)if(!cue.word.trim()||!Number.isSafeInteger(cue.occurrence)||cue.occurrence<1||cue.occurrence>2147483647)
        throw new Error('句 '+(i+1)+' の単語の読みと、1以上の回数を確認してください。');
      if(cues.length||part.words)part.words=cues;
      part.reading=reading;total+=reading.length;
    }
    if(total>2000)throw new Error('音声用の読みは、1区間の合計で2,000文字以内にしてください。');
    return p;
  }
  async function saveSpeechPronunciation(form,data) {
    if(form.dataset.saving==='true')return;
    const p=pronunciationInput(form,data),context={...form.__unitContext},unitId=form.dataset.unitId,key=form.__draftKey,submitted=JSON.stringify(pronunciationEditorValues(form)),button=$('button[type="submit"]',form),reset=$('[data-action="speech-pronunciation-reset"]');
    form.dataset.saving='true';button.disabled=true;if(reset)reset.disabled=true;rememberPronunciationDraft(form);
    try {
      const result=await call('speechPronunciationSave',{...context,unitId,pronunciation:p}),plan=result.plan,saved=(plan?.units||[]).find(u=>u.unitId===unitId);
      if(form.__invalidated)return;
      if(!saved||saved.pronunciationReady!==true||saved.pronunciationLocked!==true)throw new Error('読みの保存結果を確認できませんでした。入力は残しています。区間を開き直して確認してください。');
      if(speech.context?.bookId===context.bookId&&speech.context?.chapterUrl===context.chapterUrl&&plan)speech.plan=plan;
      if(draftMatches(pronunciationDrafts.get(key),submitted))pronunciationDrafts.delete(key);
      if(form.isConnected&&saved?.pronunciationReady===true){form.__pronunciation=pronunciationTemplate({pronunciation:saved.pronunciation,hasPronunciation:true,pronunciationLocked:saved.pronunciationLocked===true},saved);form.__hasPronunciation=true;form.__pronunciationLocked=saved.pronunciationLocked===true;rememberPronunciationDraft(form);updatePronunciationEditor(form);}
      toast(form.isConnected&&JSON.stringify(pronunciationEditorValues(form))!==submitted?'送信した読みを保存しました。入力中の変更は残っています。':'読みと単語の演技を固定して保存しました');
    } finally {form.dataset.saving='false';if(button.isConnected)button.disabled=false;if(reset?.isConnected)reset.disabled=false;}
  }
  async function resetSpeechPronunciation() {
    const form=$('[data-form="speech-pronunciation"]');if(!form||form.dataset.saving==='true')return;
    const context={...form.__unitContext},unitId=form.dataset.unitId,key=form.__draftKey,submitted=JSON.stringify(pronunciationEditorValues(form)),button=$('[data-action="speech-pronunciation-reset"]'),save=$('button[type="submit"]',form);
    form.dataset.saving='true';button.disabled=true;save.disabled=true;
    try {
      const result=await call('speechPronunciationSave',{...context,unitId,reset:true});
      if(form.__invalidated)return;
      if(speech.context?.bookId===context.bookId&&speech.context?.chapterUrl===context.chapterUrl&&result.plan)speech.plan=result.plan;
      if(draftMatches(pronunciationDrafts.get(key),submitted))pronunciationDrafts.delete(key);
      if(form.isConnected){form.__hasPronunciation=false;form.__pronunciationLocked=false;updatePronunciationEditor(form);if(JSON.stringify(pronunciationEditorValues(form))===submitted){pronunciationDrafts.delete(key);const current=pronunciationEditorValues(form);form.__pronunciation.parts.forEach((part,i)=>{part.reading=current.readings[i];part.words=current.words[i];});await showPronunciationEditor(unitId);}else rememberPronunciationDraft(form);}
      toast('読みの固定を解除しました。次の解析で読みを付け直します');
    } finally {form.dataset.saving='false';if(button.isConnected)button.disabled=false;if(save.isConnected)save.disabled=false;}
  }
  async function loadDeviceVoices() {
    const data=await call('speechVoices');speech.voices=Array.isArray(data.voices)?data.voices:[];speech.voicesLoaded=true;
    for(const select of $$('#speech-device-voice,#cast-device')){const value=select.value;select.innerHTML=deviceVoiceOptions(value);}
    if(data.error)toast(data.error,true);else toast(count(speech.voices.length)+'種類の端末音声を取得しました');
  }
  async function handleSpeechAction(button) {
    const action=button.dataset.action;
    if(action==='speech-open')await showSpeech(false);
    else if(action==='speech-open-player')await showSpeech(true);
    else if(action==='speech-back'){if(state.view==='cast')await returnFromCast(true);else if(state.view==='memory')await returnFromMemory(true);else renderSpeechSheet();}
    else if(action==='speech-reload'){await refreshSpeech(speech.context,true);renderSpeechSheet();}
    else if(action==='speech-settings')await showSpeechSettings();
    else if(action==='speech-licenses'){const response=await fetch('voice-licenses.txt');if(!response.ok)throw new Error('音声ライセンスを開けませんでした。');const text=await response.text();openSheet('音声クレジット・利用条件','<p class="speech-footnote">このアプリで利用する音声・辞書・ソフトウェアのクレジットと利用条件です。</p><pre class="voice-license-text">'+esc(text)+'</pre><button class="button secondary full" data-action="speech-settings">音声設定へ戻る</button>',{kind:'speech-licenses'});}
    else if(action==='speech-offline-install')await offlinePackAction('speechOfflineInstall');
    else if(action==='speech-offline-cancel')await offlinePackAction('speechOfflineCancel');
    else if(action==='speech-acting-preview'){const fields=button.closest('form').elements;await previewOfflineVoice(fields.offlineDefaultVoice.value,button.dataset.mode,fields.auditionScene.value);}
    else if(action==='speech-offline-preview')await previewOfflineVoice(button.dataset.voice);
    else if(action==='speech-chatgpt-login')await startChatgptLogin(button.dataset.account||'',button.dataset.consent==='true');
    else if(action==='speech-chatgpt-cancel')await chatgptAction('speechChatgptCancel');
    else if(action==='speech-chatgpt-models')await chatgptAction('speechChatgptModels');
    else if(action==='speech-chatgpt-logout')await chatgptAction('speechChatgptLogout',{accountId:button.dataset.account||chatgpt.activeAccountId});
    else if(action==='speech-chatgpt-usage')await call('speechChatgptUsage');
    else if(action==='speech-chatgpt-welcome')await chatgptAction('speechChatgptWelcome',{accountId:button.dataset.account||chatgpt.activeAccountId});
    else if(action==='speech-control')await speechControl(button.dataset.command);
    else if(action==='speech-start')await startSpeech(button.dataset.from);
    else if(action==='speech-resume-book'){const c={bookId:speech.context.bookId,chapterUrl:speech.position.chapterUrl};await refreshSpeech(c,true);renderSpeechSheet();await startSpeech('resume');}
    else if(action==='speech-analyze')await beginSpeechAnalysis(false);
    else if(action==='speech-analyze-confirm')await beginSpeechAnalysis(true);
    else if(action==='speech-range-current')setCurrentSpeechRange();
    else if(action==='speech-range-reload')await loadSpeechRangeOptions(speech.context,true);
    else if(action==='speech-range-resume')await beginSpeechRange(true);
    else if(action==='speech-range-confirm')await confirmSpeechRange();
    else if(action==='speech-range-pause')await pauseSpeechRange();
    else if(action==='speech-cancel'){if(rangeIsActive(currentSpeechRange()))await pauseSpeechRange();else{await call('speechCancel');if(speech.plan&&speech.plan.analysisStatus==='running')speech.plan.analysisStatus='paused';updateSpeechPanel();}}
    else if(action==='speech-copy-diagnostics')await copyAnalysisDiagnostics(button);
    else if(action==='speech-memory')await showStoryMemory(button.dataset.book);
    else if(action==='speech-memory-back')await returnFromMemory();
    else if(action==='speech-memory-analysis')await returnFromMemory(true);
    else if(action==='speech-memory-list'){closeSheet();renderShell(true);}
    else if(action==='speech-memory-refresh')await refreshStoryMemory();
    else if(action==='speech-memory-edit')showMemoryEditor(button.dataset.id);
    else if(action==='speech-memory-new')showMemoryEditor();
    else if(action==='speech-memory-delete')confirmMemoryDelete(button.dataset.id);
    else if(action==='speech-memory-delete-confirm')await deleteMemoryFact(button);
    else if(action==='speech-memory-filter'){if(state.memoryPage){state.memoryPage.filter=button.dataset.value;state.memoryPage.page=0;renderShell(true);}}
    else if(action==='speech-memory-page'){if(state.memoryPage){state.memoryPage.page=Number(button.dataset.page)||0;renderShell();}}
    else if(action==='speech-cast-list')await showSpeechCast(button.dataset.book);
    else if(action==='speech-cast-edit')showCastEditor(button.dataset.id,button.dataset.book);
    else if(action==='speech-cast-new')showCastEditor(undefined,button.dataset.book);
    else if(action==='speech-cast-back')await returnFromCast();
    else if(action==='speech-cast-filter'){if(state.castPage){state.castPage.filter=button.dataset.value;state.castPage.page=0;renderShell(true);}}
    else if(action==='speech-cast-page'){if(state.castPage){state.castPage.page=Number(button.dataset.page)||0;renderShell();}}
    else if(action==='speech-cast-preview')await previewCast(button.dataset.book,button.dataset.id);
    else if(action==='speech-cast-refresh'){if(state.castPage){const page=state.castPage;page.loading=true;page.error='';renderShell();try{const bundle=await call('speechState',page.context);if(Array.isArray(bundle.cast))speechCastByBook.set(page.bookId,bundle.cast);if(Array.isArray(bundle.voiceCatalog))speech.voiceCatalog=bundle.voiceCatalog;}catch(error){page.error=error.message;}finally{page.loading=false;if(state.view==='cast'&&state.castPage===page)renderShell(true);}}}
    else if(action==='speech-units')showSpeechUnits(Number(button.dataset.page)||0,button.dataset.query||'');
    else if(action==='speech-unit-edit')showUnitEditor(button.dataset.id);
    else if(action==='speech-word-add'||action==='speech-word-remove')changeWordRow(button,action==='speech-word-add');
    else if(action==='speech-pronunciation')await showPronunciationEditor(button.dataset.id);
    else if(action==='speech-pronunciation-back')showUnitEditor(button.dataset.id);
    else if(action==='speech-pronunciation-reset')await resetSpeechPronunciation();
    else if(action==='speech-play-unit'){await speechControl('play',{...speech.context,unitIndex:Number(button.dataset.index)||0});renderSpeechSheet();}
    else if(action==='speech-preview'){const form=button.closest('form');if(form)await saveSpeechCast(form,new FormData(form),true);}
    else if(action==='speech-load-voices')await loadDeviceVoices();
    else if(action==='speech-delete-key'){const data=await call('speechSettings',{deleteApiKey:true});speech.settings={...speechDefaults,...(data.settings||data)};if(state.sheet&&state.sheet.kind==='speech-settings')renderSpeechSettings();toast('OpenAI APIキーを削除しました');}
    else if(action==='speech-clear-cache'){const data=await call('speechClearCache');toast((Number(data.removedBytes||0)/1048576).toFixed(1)+' MBの音声キャッシュを削除しました');}
  }
  function handleSpeechEvent(type,data) {
    if(type==='speechOfflinePacks'){applyOfflinePacks(data);return true;}
    if(type==='speechChatgpt'){applyChatgptState(data);return true;}
    if(type==='speechState'){
      if(data.status==='error'&&data.error&&(speech.playback.status!=='error'||speech.playback.error!==data.error))toast(data.error,true);
      speechEventSequence++;speech.playback=data;renderSpeechPlayer();
      if(speech.followPlayback&&!data.preview&&data.chapterUrl&&state.sheet&&state.sheet.kind==='speech'&&speech.context&&speech.context.bookId===data.bookId&&speech.context.chapterUrl!==data.chapterUrl){
        const next={bookId:data.bookId,chapterUrl:data.chapterUrl},key=data.bookId+'|'+data.chapterUrl;
        if(speech.syncingChapter!==key){speech.syncingChapter=key;const revision=speechEventSequence,authRevision=chatgptRevision,rangeRevision=speechRangeRevision,packRevision=offlinePackRevision;call('speechState',next).then(bundle=>{if(!state.sheet||state.sheet.kind!=='speech'||speech.playback.chapterUrl!==next.chapterUrl)return;applySpeechBundle(bundle,next,revision,authRevision,rangeRevision,packRevision);renderSpeechSheet();}).catch(showError).finally(()=>{if(speech.syncingChapter===key)speech.syncingChapter='';});}
      }
      return true;
    }
    if(type==='speechAnalysis'){
      if(state.view==='memory'&&state.memoryPage?.bookId===data.bookId&&data.status==='complete')refreshStoryMemory(true).catch(()=>{});
      if('analysisRange' in data){speechRangeRevision++;rememberSpeechRange(data.analysisRange,data.bookId);updateSpeechRangePanel();updateStoryMemoryProgress();}
      if(data.invalidated){invalidatePronunciationDrafts(data.bookId,data.chapterUrl);if(speech.context&&speech.context.bookId===data.bookId&&speech.context.chapterUrl===data.chapterUrl){speech.plan=null;if(state.sheet&&['speech','speech-units','speech-unit-edit','speech-pronunciation','speech-consent'].includes(state.sheet.kind))openSheet('本文が更新されています','<p class="sheet-description">解析中に本文が変わったため、読み上げ用の区間を読み込み直してください。</p><button class="button full" data-action="speech-reload">読み上げを読み込み直す</button>',{kind:'speech-invalidated'});else toast('本文が更新されました。読み上げを開き直してください');}return true;}
      if(Array.isArray(data.cast)){speechCastByBook.set(data.bookId,data.cast);if(speech.context&&speech.context.bookId===data.bookId)speech.cast=data.cast;if(state.view==='cast'&&state.castPage&&state.castPage.bookId===data.bookId&&!state.sheet)renderShell(true);}
      if(speech.context&&speech.context.bookId===data.bookId&&speech.context.chapterUrl===data.chapterUrl){const incoming=data.plan&&data.plan.chapterUrl===speech.context.chapterUrl&&Array.isArray(data.plan.units)?data.plan:null;if(incoming)speech.plan=incoming;if(data.status&&speech.plan&&(!data.analysisRange||incoming))speech.plan.analysisStatus=data.status;if(data.error&&speech.plan&&(!data.analysisRange||incoming))speech.plan.error=data.error;updateSpeechPanel();}
      if(data.status==='error'){const failure=data.plan?.analysisError?data.plan:rangeDiagnosticPlan(data.analysisRange);toast(memoryErrorMessage(failure)||pronunciationErrorMessage(failure)||data.error||'AI解析を停止しました。途中までの結果は保存されています。',true);}return true;
    }
    return false;
  }

  function navigate(view) {
    if (!['library','search','downloads','settings'].includes(view)) return;
    flushReadingPosition(); state.operation++;
    state.view = view; state.reader = null; closeSheet(); renderShell();
    if (view === 'downloads') refreshLibrary();
  }
  function openSheet(title, html, options = {}) {
    rememberCastDraft();rememberMemoryDraft();rememberPronunciationDraft();setReaderFullscreen(false);
    state.sheet = {kind:options.kind || 'generic',...options};
    sheetHost.innerHTML = `<div class="sheet-layer" data-sheet-layer="true"><section class="sheet" role="dialog" aria-modal="true" aria-label="${esc(title)}"><div class="sheet-grip"></div><header class="sheet-header"><h2>${esc(title)}</h2><button class="icon-button subtle" data-action="close-sheet" aria-label="閉じる">${icon('close')}</button></header><div class="sheet-content">${html}</div></section></div>`;
    updateVoicePreview();syncAnalysisClocks();
    if (options.focus) setTimeout(() => { const input = $(options.focus, sheetHost); if (input) input.focus(); },100);
  }
  function closeSheet() {rememberCastDraft();rememberMemoryDraft();rememberPronunciationDraft();state.sheet = null; sheetHost.replaceChildren();if(state.view==='cast'||state.view==='memory')renderShell(true);syncAnalysisClocks();}
  function showUrlSheet() {
    openSheet('作品URLから追加', `<p class="sheet-description">カクヨム・なろう・Wuxiaworldの作品ページ、または各話のURLを貼り付けてください。</p><form data-form="url"><label class="field-label" for="book-url">作品・エピソードのURL</label><input id="book-url" class="field-full" name="url" type="text" inputmode="url" autocomplete="off" autocapitalize="none" spellcheck="false" placeholder="https://kakuyomu.jp/works/…" required><button class="button full" type="submit">${icon('book')}本棚に追加して読む</button></form><div class="divider"></div><button class="text-button" data-action="import">${icon('import')}TXTファイルから読み込む</button>`, {kind:'url',focus:'#book-url'});
  }
  async function detailsFor(id, refresh = false) {
    if (!refresh && state.reader && state.reader.book.id === id) return {book:state.reader.book,chapters:state.reader.chapters,marks:state.reader.marks,position:state.reader.book.position};
    if (!refresh && detailsCache.has(id)) return detailsCache.get(id);
    const data = rememberDetails(await call('getBook',{bookId:id}));
    if (state.reader && state.reader.book.id === id) { state.reader.chapters = data.chapters; state.reader.marks = data.marks; }
    return data;
  }
  async function showBookMenu(id) {
    const book = getBook(id); if (!book) return;
    openSheet('作品を管理', bookMenuHtml(book), {kind:'book',bookId:id});
  }
  function bookMenuHtml(book) {
    const job = jobFor(book.id) || {};
    return `${badge(book.source)}<h3 class="book-details-title">${esc(book.title)}</h3><p class="book-details-meta">${esc(book.author || '')}</p><div class="stat-grid"><div class="stat-cell"><strong>${knownTotal(book)}</strong><span>総話数</span></div><div class="stat-cell"><strong>${count(book.readCount)}</strong><span>既読の話</span></div><div class="stat-cell"><strong>${count(book.downloaded)}</strong><span>保存済み</span></div></div><div class="book-current">${icon('bookmark')}<span>${positionLabel(book)}</span></div><button class="button full" data-action="open-book" data-book="${esc(book.id)}">${icon('book')}${book.position && book.position.chapterUrl ? 'つづきから読む' : book.source === 'wuxiaworld' && book.tocComplete === false ? '取得済みの話を読む' : '最初から読む'}</button><div class="menu-list" style="margin-top:16px"><button class="menu-item" data-action="speech-memory" data-book="${esc(book.id)}">${icon('file')}<span>作品メモ</span><span class="right">人物関係・場面 ${icon('right')}</span></button><button class="menu-item" data-action="speech-cast-list" data-book="${esc(book.id)}">${icon('people')}<span>配役・声</span><span class="right">作品ごとに固定 ${icon('right')}</span></button><button class="menu-item" data-action="toc" data-book="${esc(book.id)}">${icon('list')}目次・既読の管理<span class="right">${knownTotal(book)}話 ${icon('right')}</span></button>${isFullySaved(book) ? '' : `<button class="menu-item" data-action="${job.state === 'downloading' ? 'pause-download' : 'download'}" data-book="${esc(book.id)}">${icon(job.state === 'downloading' ? 'pause' : 'download')}${job.state === 'downloading' ? '全話保存を一時停止' : '全話を端末に保存'}<span class="right">${count(book.downloaded)}話 保存済み</span></button>`}<button class="menu-item" data-action="export" data-book="${esc(book.id)}">${icon('file')}全編をTXTで書き出す<span class="right">${isFullySaved(book) ? '出力できます' : '全話保存後'}</span></button>${book.source === 'wuxiaworld' && book.downloaded > 0 ? `<button class="menu-item" data-action="export-saved" data-book="${esc(book.id)}">${icon('file')}<span>保存済みの話だけTXT出力</span><span class="right">${count(book.downloaded)} / ${knownTotal(book)}話</span></button>` : ''}${book.source !== 'local' ? `<button class="menu-item" data-action="update-book" data-book="${esc(book.id)}">${icon('refresh')}作品情報・最新話数を更新</button><button class="menu-item" data-action="original" data-url="${esc(book.url)}">${icon('external')}公式ページを開く</button>` : ''}<button class="menu-item danger" data-action="delete-book-confirm" data-book="${esc(book.id)}">${icon('trash')}本棚から削除</button></div>${book.description ? `<div class="divider"></div><p class="small muted line-clamp" style="white-space:pre-wrap;line-height:1.9">${esc(book.description)}</p>` : ''}`;
  }
  async function showToc(id) {
    if (!getBook(id)) return;
    openSheet('目次・既読の管理',
      '<div class="loading-state"><span class="spinner"></span><span>目次を読み込んでいます</span></div>',
      {kind:'toc',bookId:id,filter:''});
    const sheet = state.sheet;
    try {
      const response = await call('getBook',{bookId:id});
      // A background event can already have supplied newer data for this sheet.
      if (state.sheet !== sheet || sheet.details || !getBook(id)) return;
      const details = rememberDetails(response);
      if (state.reader && state.reader.book.id === id) {
        state.reader.chapters = details.chapters;
        state.reader.marks = details.marks;
      }
      renderToc(details);
    } catch (error) {
      if (state.sheet !== sheet || sheet.details) return;
      closeSheet();
      throw error;
    }
  }

  function tocCurrentUrl(book) {
    return state.reader && state.reader.book.id === book.id
      ? state.reader.currentUrl : book.position?.chapterUrl;
  }

  function renderToc(details, preserveScroll = true) {
    const sheet = state.sheet;
    if (sheet?.kind !== 'toc' || !details || sheet.bookId !== details.book.id) return;
    sheet.details = details;
    const content = $('.sheet-content',sheetHost);
    const scroll = preserveScroll ? content.scrollTop : 0;
    const focused = preserveScroll ? document.activeElement?.closest('.toc-list button') : null;
    if (!$('.toc-results',content)) {
      content.innerHTML = `<p class="sheet-description toc-book-title"></p>
        <div class="toc-summary"></div>
        <form class="toc-search" data-form="toc-filter"><input name="filter" type="search" inputmode="search"
          placeholder="話数・見出しで絞り込む" aria-label="目次内検索"><button class="button secondary compact" type="submit">絞る</button></form>
        <div class="row between toc-actions"><p class="toc-note">右の丸を押すと、既読・未読を切り替えられます。</p>
          <button class="button secondary compact" type="button" data-action="toc-current">現在の話へ</button></div>
        <div class="toc-results"></div>`;
      $('[name="filter"]',content).value = sheet.filter;
    }
    const book = details.book;
    $('.toc-book-title',content).textContent = book.title;
    $('.toc-summary',content).innerHTML = `<span>全 <strong>${knownTotal(book)}</strong>話</span>
      <span>既読 <strong>${count(book.readCount)}</strong>話</span><span>保存 <strong>${count(book.downloaded)}</strong>話</span>`;
    const currentUrl = tocCurrentUrl(book);
    $('[data-action="toc-current"]',content).hidden = !details.chapters.some(chapter => chapter.url === currentUrl);
    $('.toc-results',content).innerHTML = tocResultsHtml(details,sheet.filter,currentUrl);
    if (focused) {
      const replacement = $$('.toc-list button',content).find(button =>
        button.dataset.url === focused.dataset.url && button.dataset.action === focused.dataset.action);
      replacement?.focus({preventScroll:true});
    }
    content.scrollTop = scroll;
  }

  function tocResultsHtml(data, filter, currentUrl) {
    const book = data.book;
    const query = filter.trim().normalize('NFKC').toLowerCase();
    const chapters = data.chapters.filter(chapter => !query ||
      String(chapter.title || '').normalize('NFKC').toLowerCase().includes(query) ||
      String(Number(chapter.index || 0) + 1) === query);
    let section = '';
    return `<div class="toc-list">${chapters.map(chapter => {
      const sectionHeader = chapter.section && chapter.section !== section ? `<div class="toc-section">${esc(chapter.section)}</div>` : '';
      section = chapter.section || section;
      return `${sectionHeader}<div class="toc-row${chapter.url === currentUrl ? ' current' : ''}"><button class="toc-open" ${chapter.url === currentUrl ? 'aria-current="true" ' : ''}data-action="toc-open" data-book="${esc(book.id)}" data-url="${esc(chapter.url)}"><span class="toc-flags"><span class="toc-number">第${count(Number(chapter.index || 0) + 1)}話</span>${chapter.url === currentUrl ? '<span class="tag">読書位置</span>' : ''}<span>${chapter.read ? '既読' : '未読'}</span>${chapter.cached ? '<span class="tag subtle">保存済み</span>' : ''}</span><span class="toc-title">${esc(chapter.title || '無題')}</span></button><button class="toc-read${chapter.read ? ' is-read' : ''}" data-action="set-read" data-book="${esc(book.id)}" data-url="${esc(chapter.url)}" data-read="${!chapter.read}" aria-label="第${count(Number(chapter.index || 0) + 1)}話を${chapter.read ? '未読' : '既読'}にする">${chapter.read ? icon('check') : '<span class="unread-circle"></span>'}</button></div>`;
    }).join('') || '<div class="inline-empty">該当する話がありません。</div>'}</div>${book.tocComplete === false ? incompleteTocNotice(book) : ''}`;
  }

  function jumpToCurrentTocChapter() {
    const sheet = state.sheet;
    if (sheet?.kind !== 'toc' || !sheet.details) return;
    let button = $('.toc-row.current .toc-open',sheetHost);
    if (!button && sheet.filter) {
      sheet.filter = '';
      $('[name="filter"]',sheetHost).value = '';
      renderToc(sheet.details,false);
      button = $('.toc-row.current .toc-open',sheetHost);
    }
    if (button) {
      button.scrollIntoView({block:'center',inline:'nearest'});
      button.focus({preventScroll:true});
    }
  }

  async function setChapterRead(id,url,read,manual = true) {
    const r = state.reader;
    if (manual && r && r.book.id === id) { if (read) r.manualUnread.delete(url); else r.manualUnread.add(url); }
    const data = await call('setChapterRead',{bookId:id,chapterUrl:url,read});
    if (data.book) upsertBook(data.book);
    for (const details of [detailsCache.get(id),r && r.book.id === id ? r : null]) {
      if (!details) continue;
      const chapter = details.chapters.find(item => item.url === url); if (chapter) chapter.read = read;
    }
    if (state.sheet?.kind === 'toc' && state.sheet.bookId === id) renderToc(state.sheet.details);
    updateReaderChrome();
    if (['library','downloads'].includes(state.view) && !state.sheet) renderShell(true);
  }
  async function showMarks() {
    const r = state.reader; if (!r) return;
    const data = await call('getMarks',{bookId:r.book.id}); if (state.reader !== r) return;
    r.marks = Array.isArray(data.marks) ? data.marks : [];
    const pos = readingPosition() || r.book.position;
    const chapter = pos ? r.chapters.find(item => item.url === pos.chapterUrl) : null;
    openSheet('付箋', `<button class="button full" data-action="new-mark">${icon('plus')}今の位置に付箋を追加</button><div class="section-label">自動付箋</div>${pos ? `<div class="mark-card auto"><button class="mark-open" data-action="mark-open" data-url="${esc(pos.chapterUrl)}" data-paragraph="${pos.paragraph || 0}" data-offset="${Number(pos.offset) || 0}"><span class="tag">自動保存</span><div class="mark-title">第${count(Number(pos.index || 0) + 1)}話 · ${esc(chapter ? chapter.title : r.book.title)}</div><p class="mark-excerpt">読み進めた位置を自動で更新します。起動時はこの位置から再開します。</p></button></div>` : '<p class="inline-empty">読み始めると自動で保存されます。</p>'}<div class="section-label">自分の付箋 · ${count(r.marks.length)}件</div>${r.marks.length ? [...r.marks].sort((a,b) => (b.createdAt || 0) - (a.createdAt || 0)).map(mark => {
      const c = r.chapters.find(item => item.url === mark.chapterUrl);
      return `<div class="mark-card"><button class="mark-open" data-action="mark-open" data-url="${esc(mark.chapterUrl)}" data-paragraph="${mark.paragraph || 0}" data-offset="${Number(mark.offset) || 0}"><div class="tag subtle">第${count(Number(mark.index || 0) + 1)}話</div><div class="mark-title">${esc(c ? c.title : '保存した位置')}</div>${mark.excerpt ? `<p class="mark-excerpt">${esc(mark.excerpt)}</p>` : ''}${mark.note ? `<p class="mark-note">${esc(mark.note)}</p>` : ''}</button><div class="mark-footer"><span>${mark.createdAt ? dateLabel(mark.createdAt) : ''}</span><button class="icon-button subtle" data-action="delete-mark" data-id="${esc(mark.id)}" aria-label="この付箋を削除">${icon('trash')}</button></div></div>`;
    }).join('') : '<p class="inline-empty">気になる場所に付箋を残せます。</p>'}`, {kind:'marks',bookId:r.book.id});
  }
  function dateLabel(value) { try { return new Date(Number(value)).toLocaleDateString('ja-JP',{month:'numeric',day:'numeric'}) + ' ' + new Date(Number(value)).toLocaleTimeString('ja-JP',{hour:'2-digit',minute:'2-digit'}); } catch (_) { return ''; } }
  function showNewMark() {
    const r = state.reader; const position = readingPosition(); if (!r || !position) return;
    const segment = r.segments.find(item => item.chapter.url === position.chapterUrl);
    const p = segment && segment.chapter.paragraphs[position.paragraph]; const excerpt = p ? String(typeof p === 'string' ? p : p.text || '').slice(0,200) : '';
    openSheet('付箋を追加', `<p class="sheet-description">第${count(position.index + 1)}話の、いま読んでいる位置を保存します。</p><div class="quote-preview">${esc(excerpt || 'この読書位置を保存')}</div><form data-form="mark"><label class="field-label" for="mark-note">メモ <span class="muted">（任意）</span></label><textarea id="mark-note" class="field-full" name="note" maxlength="1000" placeholder="気になったことなど"></textarea><button class="button full" type="submit">${icon('bookmark')}付箋を保存</button></form>`, {kind:'new-mark',bookId:r.book.id,position,excerpt});
  }
  function showReaderSettings() {
    openSheet('文字と表示', `<div class="settings-group">${readerDisplaySettings()}</div><div class="reading-preview">これは表示の確認用テキストです。<br>読みやすい大きさと行間に調整できます。</div><div class="settings-group">${appearanceSettings()}</div><div class="settings-group">${toggleRow('autoNext','次の話を自動で読み込む','末尾へ近づくと次話をつなげます')}</div>`, {kind:'reader-settings'});
  }
  async function openUrl(raw) {
    const match = String(raw || '').match(/https?:\/\/[^\s<>"「」]+/i); const url = match ? match[0] : String(raw || '').trim();
    if (!url) { toast('作品URLを入力してください'); return; }
    flushReadingPosition(); closeSheet();
    const operation = ++state.operation; const token = beginBusy('作品と目次を読み込んでいます');
    try {
      const data = rememberDetails(await call('openUrl',{url})); if (operation !== state.operation) return;
      endBusy(token); await enterReader(data,{chapterUrl:data.chapterUrl});
    } catch (error) { if (operation === state.operation) showError(error); }
    finally { endBusy(token); }
  }
  async function openBook(id, options = {}) {
    flushReadingPosition(); closeSheet();
    const operation = ++state.operation; const token = beginBusy('本棚から開いています');
    try {
      const data = rememberDetails(await call('getBook',{bookId:id})); if (operation !== state.operation) return;
      endBusy(token); await enterReader(data,options);
    } catch (error) { if (operation === state.operation) showError(error); }
    finally { endBusy(token); }
  }
  async function enterReader(data, options = {}) {
    const saved = data.position || data.book.position;
    const url = options.chapterUrl || (saved && saved.chapterUrl) || (data.chapters[0] && data.chapters[0].url);
    if (!url) throw new Error('目次に本文が見つかりません。作品情報を更新するか、公式ページをご確認ください。');
    const position = options.position || (!options.chapterUrl || options.chapterUrl === (saved && saved.chapterUrl) ? saved : null);
    const r = {book:data.book,chapters:data.chapters,marks:data.marks || [],segments:[],currentUrl:url,session:++readerSequence,loadToken:0,restoring:true,loadingNext:false,prefetched:new Set(),prefetching:new Map(),readPending:new Set(),manualUnread:new Set(),lastSaved:'',error:null,nextError:null,fullscreen:false,display:{lastY:0,travel:0,direction:0,inputUntil:0,ignoreUntil:0,pointer:null,tapUntil:0,anchorRevision:0,layoutWidth:0,stableAnchor:null,touching:false,trimTimer:0}};
    state.reader = r; state.view = 'reader'; state.lastBookId = data.book.id;
    renderReader(); await loadSingleChapter(url,position);
  }
  function renderReader() {
    const r = state.reader; if (!r) return;
    app.innerHTML = `<section class="reader"><header class="reader-top"><button class="icon-button" data-action="reader-back" aria-label="本棚に戻る">${icon('left')}</button><div class="reader-top-title"><div class="reader-book-title">${esc(r.book.title)}</div><div class="reader-top-meta" id="reader-meta">${esc(sourceName(r.book.source))}</div></div><button class="icon-button" data-action="new-mark" aria-label="現在位置に付箋を追加">${icon('bookmark')}</button><button class="icon-button" data-action="reader-fullscreen" aria-label="全画面で読む" title="全画面で読む">${icon('fullscreen')}</button><button class="icon-button" data-action="book-menu" data-book="${esc(r.book.id)}" aria-label="作品の管理">${icon('dots')}</button></header><div class="reader-scroll" id="reader-scroll" tabindex="0" aria-label="小説の本文"><div class="chapter-stack" id="chapter-stack"></div><div class="next-area" id="next-area"></div></div><footer class="reader-bottom"><div class="reader-progress" aria-label="作品内の読書位置"><span id="reader-progress-fill"></span></div><div class="reader-controls"><button class="reader-control" id="reader-prev" data-action="reader-prev" aria-label="前の話へ">${icon('left')}<span>前の話</span></button><button class="reader-control" data-action="toc" data-book="${esc(r.book.id)}">${icon('list')}<span>目次</span></button><button class="reader-control" data-action="marks">${icon('bookmark')}<span>付箋</span></button><button class="reader-control" data-action="speech-open">${icon('headphones')}<span>聴く</span></button><button class="reader-control" data-action="reader-settings">${icon('text')}<span>文字</span></button><button class="reader-control" id="reader-next" data-action="reader-next" aria-label="次の話へ">${icon('right')}<span>次の話</span></button></div></footer><button class="reader-reveal" data-action="reader-show-controls" hidden>操作バーを表示</button></section>`;
    $('#reader-scroll').addEventListener('scroll',onReaderScroll,{passive:true});
    for (const eventName of ['touchstart','wheel','pointerdown','keydown']) $('#reader-scroll').addEventListener(eventName,()=>{if(state.reader)state.reader.speechFollowing=false;speech.followSuspendedUntil=Date.now()+8000;},{passive:true});
    syncReaderDisplay(); bindReaderDisplay(); renderSpeechPlayer(false);
    updateReaderChrome();
  }
  function skeleton() { return `<div class="reader-skeleton" style="padding:0"><div class="skeleton-line" style="height:25px;width:64%;margin-bottom:40px"></div>${Array.from({length:9},() => '<div class="skeleton-line"></div>').join('')}<p class="small muted">本文を読み込んでいます…</p></div>`; }
  async function loadSingleChapter(url, position = null) {
    const r = state.reader; if (!r) return;
    setReaderFullscreen(false); invalidateReaderDisplayAnchor(r); suspendReaderAutoFullscreen(r);flushReadingPosition(); closeSheet();
    clearTimeout(r.display.trimTimer);
    const token = ++r.loadToken; r.restoring = true; r.loadingNext = false; r.error = null; r.nextError = null; r.segments = []; r.currentUrl = url;
    $('#chapter-stack').innerHTML = skeleton(); $('#next-area').innerHTML = ''; $('#reader-scroll').scrollTop = 0;
    updateReaderChrome();
    try {
      const chapter = await call('readChapter',{bookId:r.book.id,chapterUrl:url});
      if (state.reader !== r || r.loadToken !== token) return;
      $('#chapter-stack').replaceChildren(); appendChapter(r,chapter);
      await nextPaint(); if (state.reader !== r || r.loadToken !== token) return;
      if (position && position.chapterUrl === chapter.url) restorePosition(position); else $('#reader-scroll').scrollTop = 0;
      r.restoring = false; r.lastSaved = ''; suspendReaderAutoFullscreen(r);r.display.stableAnchor=readingPosition();updateReaderChrome(); updateNextArea();
      flushReadingPosition(); prefetchNext(r); setTimeout(() => { if (state.reader === r) onReaderScroll(); },240);
    } catch (error) {
      if (state.reader !== r || r.loadToken !== token) return;
      r.restoring = false; r.error = error;
      $('#chapter-stack').innerHTML = `<div class="error-box"><div class="row">${icon('alert')}<span>${esc(error.message || '本文を読み込めませんでした。')}</span></div><button class="button secondary" data-action="retry-chapter">${icon('refresh')}もう一度読み込む</button>${r.book.source !== 'local' ? `<button class="text-button" data-action="original" data-url="${esc(error.url || url)}">公式ページで確認</button>` : ''}</div>`;
    }
  }
  function safeInline(html, text) {
    if (!html) return esc(text || '').replace(/\n/g,'<br>');
    const template = document.createElement('template'); template.innerHTML = html;
    const permitted = new Set(['RUBY','RT','RP','BR']); const forbidden = new Set(['SCRIPT','STYLE','IFRAME','OBJECT','SVG','MATH','TEMPLATE']);
    function clean(node) {
      if (node.nodeType === Node.TEXT_NODE) return document.createTextNode(node.textContent);
      if (node.nodeType !== Node.ELEMENT_NODE || forbidden.has(node.tagName)) return document.createDocumentFragment();
      const out = permitted.has(node.tagName) ? document.createElement(node.tagName.toLowerCase()) : document.createDocumentFragment();
      for (const child of [...node.childNodes]) out.appendChild(clean(child)); return out;
    }
    const target = document.createElement('div'); for (const child of [...template.content.childNodes]) target.appendChild(clean(child));
    return target.innerHTML || esc(text || '').replace(/\n/g,'<br>');
  }
  function paragraphHtml(p) { return safeInline(typeof p === 'object' ? p.html : '', typeof p === 'object' ? p.text : p); }
  function appendChapter(r, chapter) {
    chapter.paragraphs = Array.isArray(chapter.paragraphs) ? chapter.paragraphs : [];
    chapter.index = Number(chapter.index) || 0;
    const article = document.createElement('article'); article.className = 'reader-chapter'; article.dataset.chapterUrl = chapter.url;
    const extra = (items,label,className) => Array.isArray(items) && items.length ? `<section class="chapter-extra ${className}"${state.settings.showPreface ? '' : ' hidden'}><div class="chapter-extra-label">${label}</div>${items.map(p => `<p>${paragraphHtml(p) || '<br>'}</p>`).join('')}</section>` : '';
    article.innerHTML = `<div class="chapter-eyebrow">第${count(chapter.index + 1)}話 <span class="muted">/ 全${knownTotal(r.book)}話</span></div><h1 class="chapter-heading">${esc(chapter.title || '本文')}</h1>${extra(chapter.preface,'前書き','preface')}<div class="novel-body">${chapter.paragraphs.map((p,index) => `<p class="novel-paragraph" data-p-index="${index}">${paragraphHtml(p) || '<br>'}</p>`).join('')}</div>${extra(chapter.afterword,'後書き','afterword')}<div class="chapter-end" data-chapter-end="${esc(chapter.url)}">第${count(chapter.index + 1)}話 おわり</div>`;
    $('#chapter-stack').appendChild(article); r.segments.push({chapter,element:article,paragraphElements:$$('.novel-paragraph',article)});
    let meta = r.chapters.find(item => item.url === chapter.url);
    if (!meta) { meta = {url:chapter.url,title:chapter.title,index:chapter.index,read:false,cached:true}; r.chapters.push(meta); r.chapters.sort((a,b) => a.index-b.index); }
    meta.cached = true;
  }
  // Use the same reference line with the toolbar shown or hidden, including when restoring 1.12.1 bookmarks.
  function readingTop() { return $('.reader-top').getBoundingClientRect().bottom; }
  function currentSegment() {
    const r = state.reader; if (!r || !r.segments.length) return null;
    const scroller = $('#reader-scroll'); if (!scroller) return null;
    const line = readingTop() + 30;
    let selected = r.segments[0];
    for (const segment of r.segments) { if (segment.element.getBoundingClientRect().top <= line) selected = segment; else break; }
    return selected;
  }
  function readingPosition(segment = currentSegment()) {
    const r = state.reader; const scroller = $('#reader-scroll');
    if (!r || !scroller || r.restoring || !r.segments.length) return null;
    if (!segment) return null;
    const paragraphs = segment.paragraphElements; if (!paragraphs.length) return null;
    const line = readingTop() + 16;
    let low = 0, high = paragraphs.length - 1, selected = paragraphs[0];
    while (low <= high) {
      const mid = (low + high) >>> 1;
      if (paragraphs[mid].getBoundingClientRect().top <= line) { selected = paragraphs[mid]; low = mid + 1; } else high = mid - 1;
    }
    return {bookId:r.book.id,chapterUrl:segment.chapter.url,index:segment.chapter.index,paragraph:Number(selected.dataset.pIndex),offset:Math.round((line-selected.getBoundingClientRect().top)*100)/100};
  }
  function restorePosition(position) {
    const r = state.reader; const scroller = $('#reader-scroll'); if (!r || !scroller || !position) return;
    const segment = r.segments.find(item => item.chapter.url === position.chapterUrl); if (!segment) return;
    const paragraphs = segment.paragraphElements; const paragraph = paragraphs[clamp(position.paragraph,0,paragraphs.length-1)];
    if (!paragraph) return;
    suspendReaderAutoFullscreen(r);
    const line = readingTop() + 16;
    const delta = paragraph.getBoundingClientRect().top - line + (Number(position.offset) || 0);
    if (Math.abs(delta) >= .5) scroller.scrollTop += delta;
    r.currentUrl = position.chapterUrl;
  }
  function flushReadingPosition() {
    clearTimeout(saveTimer); const r = state.reader; const position = readingPosition();
    if (!r || !position || r.speechFollowing) return null;
    const key = JSON.stringify(position); if (key === r.lastSaved) return position;
    r.lastSaved = key; r.book.position = {...position}; r.book.currentIndex = position.index; r.book.lastReadAt = Date.now(); state.lastBookId = r.book.id;
    upsertBook(r.book);
    call('savePosition',position).catch(error => { if (state.reader === r) { r.lastSaved = ''; toast('読書位置を保存できませんでした。' + (error.message || ''),true); } });
    return position;
  }
  window.flushReadingPosition = flushReadingPosition;
  function adjacentUrl(r, direction, segment = currentSegment()) {
    if (!segment) return '';
    const field = direction > 0 ? 'nextUrl' : 'prevUrl'; if (segment.chapter[field]) return segment.chapter[field];
    const i = r.chapters.findIndex(item => item.url === segment.chapter.url);
    const adjacent = i >= 0 ? r.chapters[i+direction] : null;
    if (!adjacent) return '';
    if (r.book.tocComplete === false) {
      const index = Number(segment.chapter.index), nextIndex = Number(adjacent.index);
      if (!Number.isInteger(index) || !Number.isInteger(nextIndex) || nextIndex !== index + direction) return '';
    }
    return adjacent.url;
  }
  function updateReaderChrome() {
    const r = state.reader; if (!r || !$('#reader-meta')) return;
    const segment = currentSegment(); const meta = segment ? segment.chapter : r.chapters.find(item => item.url === r.currentUrl);
    if (segment) r.currentUrl = segment.chapter.url;
    const index = meta ? Number(meta.index) || 0 : (r.book.position && Number(r.book.position.index)) || 0;
    const label = `第${count(index+1)} / ${knownTotal(r.book)}話 · 既読${count(r.book.readCount)} · 保存${count(r.book.downloaded)}`;
    const metaNode = $('#reader-meta'); if (metaNode.textContent !== label) metaNode.textContent = label;
    $('#reader-prev').disabled = !segment || !adjacentUrl(r,-1,segment);
    $('#reader-next').disabled = !segment || !adjacentUrl(r,1,segment);
    const pos = readingPosition(segment);
    const fractional = segment && pos ? clamp(pos.paragraph / Math.max(1,segment.chapter.paragraphs.length),0,1) : 0;
    $('#reader-progress-fill').style.width = `${r.book.total > 0 ? clamp((index+fractional)/r.book.total*100,0,100) : 0}%`;
    return pos;
  }
  let scrollingFrame = false;
  function onReaderScroll() {
    if (scrollingFrame) return;
    scrollingFrame = true;
    requestAnimationFrame(() => {
      scrollingFrame = false; const r = state.reader; const scroller = $('#reader-scroll'); if (!r || !scroller || r.restoring || r.error) return;
      updateReaderFullscreenFromScroll(r,scroller);
      r.display.stableAnchor=updateReaderChrome();clearTimeout(saveTimer); saveTimer = setTimeout(flushReadingPosition,500);
      checkReadEnds(r,scroller); scheduleChapterTrim(r,scroller);
      if (shouldLoadNext(scroller)) appendNext(false);
    });
  }
  function checkReadEnds(r, scroller) {
    if (state.sheet || document.hidden) return;
    const box = scroller.getBoundingClientRect();
    const top = r.fullscreen ? box.top : readingTop(), bottom = r.fullscreen ? box.bottom : $('.reader-bottom').getBoundingClientRect().top;
    for (const segment of r.segments) {
      const meta = r.chapters.find(item => item.url === segment.chapter.url);
      if (meta && meta.read || r.readPending.has(segment.chapter.url) || r.manualUnread.has(segment.chapter.url)) continue;
      const end = $('.chapter-end',segment.element).getBoundingClientRect();
      if (end.top >= top && end.top <= bottom-8) {
        r.readPending.add(segment.chapter.url);
        setChapterRead(r.book.id,segment.chapter.url,true,false).catch(() => { r.readPending.delete(segment.chapter.url); });
      }
    }
  }
  function shouldLoadNext(scroller) {
    return state.settings.autoNext && !state.sheet &&
      scroller.scrollHeight-scroller.scrollTop-scroller.clientHeight < Math.max(1100,scroller.clientHeight*1.6);
  }
  function scheduleChapterTrim(r,scroller) {
    if (r.segments.length < 5) return;
    const d=r.display;
    clearTimeout(d.trimTimer);
    // Removing an old chapter rebases scrollTop. Let the finger and the compositor finish first.
    d.trimTimer=setTimeout(()=>{
      if (state.reader!==r || r.restoring || d.touching || d.pointer || !scroller.isConnected) return;
      if (trimSegments(r,scroller) && shouldLoadNext(scroller)) appendNext(false);
    },180);
  }
  function trimSegments(r,scroller) {
    const previousCount=r.segments.length;
    while (r.segments.length >= 5) {
      const first = r.segments[0];
      if (first.element.getBoundingClientRect().bottom >= scroller.getBoundingClientRect().top-scroller.clientHeight) break;
      const anchor = r.segments[1].element; const before = anchor.getBoundingClientRect().top;
      first.element.remove(); r.segments.shift();
      const delta=anchor.getBoundingClientRect().top-before;
      if (Math.abs(delta)>=.5) scroller.scrollTop+=delta;
      r.display.lastY=scroller.scrollTop;
    }
    return r.segments.length < previousCount;
  }
  function prefetchNext(r) {
    if (!state.settings.autoNext || !r.segments.length) return;
    const last = r.segments[r.segments.length-1]; const url = adjacentUrl(r,1,last);
    if (!url || r.prefetched.has(url)) return;
    r.prefetched.add(url);
    const promise = call('prefetch',{bookId:r.book.id,chapterUrl:url}).then(() => true,() => false);
    r.prefetching.set(url,promise);
    promise.then(() => r.prefetching.delete(url));
  }
  async function appendNext(manual) {
    const r = state.reader; if (!r || r.loadingNext || r.restoring || !r.segments.length) return;
    if (!manual && r.nextError) return;
    const last = r.segments[r.segments.length-1]; const url = adjacentUrl(r,1,last);
    if (!url || r.segments.some(segment => segment.chapter.url === url)) { updateNextArea(); return; }
    if (r.segments.length >= 5) {
      if (!manual) return;
      if (!trimSegments(r,$('#reader-scroll'))) { await loadSingleChapter(url); return; }
    }
    const token = r.loadToken; r.loadingNext = true; updateNextArea();
    try {
      if (r.prefetching.has(url)) await r.prefetching.get(url);
      if (state.reader !== r || r.loadToken !== token) return;
      const chapter = await call('readChapter',{bookId:r.book.id,chapterUrl:url});
      if (state.reader !== r || r.loadToken !== token) return;
      appendChapter(r,chapter); r.loadingNext = false; updateNextArea(); prefetchNext(r);
      scheduleChapterTrim(r,$('#reader-scroll'));
    } catch (error) {
      if (state.reader !== r || r.loadToken !== token) return;
      r.loadingNext = false; r.nextError = error; updateNextArea();
      if (manual) showError(error);
    }
  }
  function updateNextArea() {
    const r = state.reader; const area = $('#next-area'); if (!r || !area || !r.segments.length) return;
    const last = r.segments[r.segments.length-1]; const url = adjacentUrl(r,1,last);
    if (r.loadingNext) area.innerHTML = '<div class="loading-state"><span class="spinner"></span><span>次の話を読み込んでいます</span></div>';
    else if (url) area.innerHTML = `<button class="button secondary" data-action="append-next">${icon('right')}${r.nextError ? '次の話を再読み込み' : '次の話を読み込む'}</button>${r.nextError ? `<small>${esc(r.nextError.message)}</small>${r.book.source !== 'local' ? `<button class="text-button" data-action="original" data-url="${esc(r.nextError.url || url)}">${icon('external')}公式ページで確認</button>` : ''}` : state.settings.autoNext ? '<small>末尾に近づくと自動でつながります</small>' : ''}`;
    else if (r.book.tocComplete === false) area.innerHTML = incompleteTocNotice(r.book);
    else area.innerHTML = `<span class="tag subtle">この作品の最後の話です</span><div style="margin-top:18px"><button class="button secondary" data-action="reader-back">${icon('shelf')}本棚へ戻る</button></div>`;
  }
  async function goChapter(url, position = null) {
    const r = state.reader; if (!r || !url) return;
    setReaderFullscreen(false); invalidateReaderDisplayAnchor(r); suspendReaderAutoFullscreen(r);
    flushReadingPosition(); closeSheet();
    const existing = r.segments.find(segment => segment.chapter.url === url);
    if (existing) {
      if (position) restorePosition({...position,chapterUrl:url});
      else { const scroll = $('#reader-scroll'); scroll.scrollTop += existing.element.getBoundingClientRect().top-readingTop()-25; }
      r.currentUrl = url;r.display.stableAnchor=readingPosition();updateReaderChrome(); flushReadingPosition();
    } else await loadSingleChapter(url,position);
  }
  async function startDownload(id, exportAfter = false) {
    if (exportAfter) exportQueue.add(id);
    let data;
    try { data = await call('downloadBook',{bookId:id}); }
    catch (error) { if (exportAfter) exportQueue.delete(id); throw error; }
    mergeDownload({...data,bookId:id});
    closeSheet();
    if (data.state === 'complete') {
      if (await confirmSavedBook(id)) {
        if (exportAfter && exportQueue.has(id)) { exportQueue.delete(id); await call('exportTxt',{bookId:id}); }
        else if (!exportAfter) toast('全話を保存済みです');
      } else { exportQueue.delete(id); toast('保存済みの話は読めます。全話保存はまだ完了していません',true); }
    } else if (data.state === 'error') { exportQueue.delete(id); toast(data.error || '保存を停止しました。公式ページで確認してください',true); }
    else if (data.state === 'paused') toast('保存を一時停止しています。保存タブから再開できます');
    else toast(exportAfter ? '全話保存後にTXTの保存先を選びます' : '全話保存を開始しました。保存タブで確認できます');
    if (state.view !== 'reader') renderShell(true);
  }
  function confirmSavedBook(id) {
    if (!completionChecks.has(id)) {
      const check = detailsFor(id,true).then(data => {
        updateReaderChrome();
        if (state.view === 'downloads') renderShell(true);
        if (state.sheet && state.sheet.kind === 'book' && state.sheet.bookId === id) $('.sheet-content').innerHTML = bookMenuHtml(data.book);
        return isFullySaved(data.book);
      }).finally(() => completionChecks.delete(id));
      completionChecks.set(id,check);
    }
    return completionChecks.get(id);
  }
  async function exportBook(id) {
    const book = getBook(id); if (!book) return;
    if (isFullySaved(book)) { closeSheet(); await call('exportTxt',{bookId:id}); return; }
    openSheet('全編をTXTで書き出す', `<p class="sheet-description">全${knownTotal(book)}話の本文を端末に保存してから、1つのTXTファイルにまとめます。</p><div class="stat-grid"><div class="stat-cell"><strong>${knownTotal(book)}</strong><span>総話数</span></div><div class="stat-cell"><strong>${count(book.downloaded)}</strong><span>保存済み</span></div><div class="stat-cell"><strong>${book.total ? count(Math.max(0,book.total-book.downloaded)) : '…'}</strong><span>残り</span></div></div><p class="note">保存中はアプリを開いたままにしてください。中断しても取得済みの話は残ります。${book.source === 'wuxiaworld' ? '<br>全編TXTは全話の目次と本文がそろってから書き出せます。ロックされている話は公式ページで確認してください。' : ''}</p><div class="sheet-actions export-actions"><button class="button" data-action="save-export" data-book="${esc(id)}">${icon('download')}保存して全編TXTを書き出す</button>${book.source === 'wuxiaworld' && book.downloaded > 0 ? `<button class="button secondary" data-action="export-saved" data-book="${esc(id)}">${icon('file')}保存済みの話だけ書き出す</button>` : ''}</div>`,{kind:'export',bookId:id});
  }
  async function exportSavedBook(id) {
    const data = await detailsFor(id,true); const book = data.book;
    if (book.source !== 'wuxiaworld' || !(book.downloaded > 0)) { toast('TXTに出力できる保存済みの話がありません'); return; }
    openSheet('保存済みの話だけTXT出力', `<h3 class="book-details-title">${esc(book.title)}</h3><p class="sheet-description">保存済み <strong>${count(book.downloaded)}</strong> / 全 <strong>${knownTotal(book)}</strong> 話を出力します。未保存の話は含まれません。</p><div class="stat-grid"><div class="stat-cell"><strong>${knownTotal(book)}</strong><span>総話数</span></div><div class="stat-cell"><strong>${count(book.downloaded)}</strong><span>出力する話</span></div><div class="stat-cell"><strong>${book.total ? count(Math.max(0,book.total-book.downloaded)) : '…'}</strong><span>未保存</span></div></div><p class="note">ファイル名と本文の先頭に、保存済みの話数を明記します。保存した話を元の順序でまとめます。</p><div class="sheet-actions"><button class="button full" data-action="export-saved-confirm" data-book="${esc(id)}">${icon('file')}保存した${count(book.downloaded)}話を書き出す</button></div>`,{kind:'export-saved',bookId:id});
  }
  function mergeDownload(data) {
    if (!data || !data.bookId) return;
    const at = state.downloads.findIndex(job => job.bookId === data.bookId);
    if (at < 0) state.downloads.push(data); else state.downloads[at] = {...state.downloads[at],...data};
    const book = getBook(data.bookId);
    if (book) {
      if (typeof data.completed === 'number') book.downloaded = data.completed;
      if (Number(data.total) > 0) book.total = data.total;
      upsertBook(book);
    }
    updateReaderChrome();
  }
  async function refreshLibrary() {
    try {
      const data = await call('bootstrap'); state.books = Array.isArray(data.books) ? data.books : state.books;
      state.downloads = Array.isArray(data.downloads) ? data.downloads : state.downloads; state.lastBookId = data.lastBookId || state.lastBookId;
      if (state.reader) { const b = getBook(state.reader.book.id); if (b) state.reader.book = {...state.reader.book,...b}; updateReaderChrome(); }
      else if (['library','downloads'].includes(state.view)) renderShell(true);
    } catch (error) { toast('本棚の再読み込みに失敗しました',true); }
  }
  function handleNativeEvent(type, data) {
    if (type === 'readerInsets') {
      document.documentElement.style.setProperty('--reader-status-inset',(Number(data.top)||0)+'px');
      document.documentElement.style.setProperty('--reader-nav-inset',(Number(data.bottom)||0)+'px');
      return;
    }
    if (handleSpeechEvent(type,data)) return;
    if (type === 'download') {
      mergeDownload(data);
      if (state.view === 'downloads') renderShell(true);
      if (state.sheet && state.sheet.kind === 'book' && state.sheet.bookId === data.bookId) { const b = getBook(data.bookId); if (b) $('.sheet-content').innerHTML = bookMenuHtml(b); }
      if (data.state === 'complete') {
        confirmSavedBook(data.bookId).then(complete => {
          if (!complete) { exportQueue.delete(data.bookId); toast('保存済みの話は読めます。全話保存はまだ完了していません',true); return; }
          if (exportQueue.has(data.bookId)) { exportQueue.delete(data.bookId); return call('exportTxt',{bookId:data.bookId}); }
          toast('全話の保存が完了しました');
        }).catch(error => { exportQueue.delete(data.bookId); showError(error); });
      } else if (data.state === 'error') { exportQueue.delete(data.bookId); toast(data.error || '保存を停止しました。保存タブで確認できます',true); }
    } else if (type === 'libraryChanged' || type === 'browserClosed') {
      clearTimeout(refreshTimer); refreshTimer = setTimeout(refreshLibrary,450);
    } else if (type === 'tocUpdated') {
      call('getBook',{bookId:data.bookId}).then(result => {
        if (!getBook(data.bookId)) return;
        const details = rememberDetails(result);
        if (state.reader && state.reader.book.id === data.bookId) { state.reader.chapters = details.chapters; state.reader.book = details.book; updateReaderChrome(); updateNextArea(); prefetchNext(state.reader); }
        renderToc(details);
        if (['library','downloads'].includes(state.view)) renderShell(true);
      }).catch(() => {});
    } else if (type === 'openUrl') openUrl(data.url).catch(showError);
    else if (type === 'notice') toast(data.message || '公式サイトの状態をご確認ください',true);
    else if (type === 'imported') refreshLibrary().then(() => openBook(data.bookId)).catch(showError);
    else if (type === 'exported') { if (!data.cancelled) toast(data.success ? 'TXTファイルを書き出しました' : (data.message || 'TXTの書き出しを完了できませんでした'),!data.success); }
    else if (type === 'back') window.handleBack();
    else if (type === 'pause' || type === 'appPause') flushReadingPosition();
  }
  window.handleBack = () => {
    const welcome=$('#chatgpt-welcome-host');if(welcome&&!welcome.hidden){chatgptAction('speechChatgptWelcome',{accountId:chatgpt.activeAccountId}).catch(showError);return true;}
    if (busyActive) { ++state.operation; ++busyToken; busyActive = false; $('#busy-overlay').hidden = true; return true; }
    if (state.sheet) { closeSheet(); return true; }
    if (state.view === 'cast') { returnFromCast().catch(showError); return true; }
    if (state.view === 'memory') { returnFromMemory().catch(showError); return true; }
    if (state.view === 'reader' && state.reader && state.reader.fullscreen) {setReaderFullscreen(false);return true;}
    if (state.view === 'reader') { navigate('library'); return true; }
    if (state.view !== 'library') { navigate('library'); return true; }
    return false;
  };
  async function handleAction(button) {
    const action = button.dataset.action, id = button.dataset.book;
    if(action.startsWith('speech-')) {await handleSpeechAction(button);return;}
    switch (action) {
      case 'nav': navigate(button.dataset.view); break;
      case 'add-url': showUrlSheet(); break;
      case 'close-sheet': closeSheet(); break;
      case 'library-filter': state.filter = button.dataset.value; renderShell(); break;
      case 'search-source': state.search.source = button.dataset.value; navigate('search'); break;
      case 'search-filter': { const source = button.dataset.value; state.search.source = source; if (state.search.hasSearched && state.search.query) await search(state.search.query,source,1); else renderShell(); break; }
      case 'search-retry': await search(state.search.query,state.search.source,state.search.page); break;
      case 'search-page': await search(state.search.query,state.search.source,Number(button.dataset.page)); break;
      case 'search-result': { const book = state.search.items[Number(button.dataset.index)]; if (book) await openUrl(book.url); break; }
      case 'open-book': await openBook(id); break;
      case 'book-menu': await showBookMenu(id); break;
      case 'toc': await showToc(id || currentBook().id); break;
      case 'toc-current': jumpToCurrentTocChapter(); break;
      case 'toc-open': if (state.reader && state.reader.book.id === id) await goChapter(button.dataset.url); else await openBook(id,{chapterUrl:button.dataset.url}); break;
      case 'set-read': await setChapterRead(id,button.dataset.url,button.dataset.read === 'true'); break;
      case 'marks': await showMarks(); break;
      case 'new-mark': showNewMark(); break;
      case 'mark-open': await goChapter(button.dataset.url,{chapterUrl:button.dataset.url,paragraph:Number(button.dataset.paragraph)||0,offset:Number(button.dataset.offset)||0}); break;
      case 'delete-mark': await call('deleteMark',{id:button.dataset.id}); await showMarks(); toast('付箋を削除しました'); break;
      case 'reader-back': navigate('library'); break;
      case 'reader-settings': showReaderSettings(); break;
      case 'reader-fullscreen': setReaderFullscreen(true);$('#reader-scroll').focus({preventScroll:true});break;
      case 'reader-show-controls': setReaderFullscreen(false);break;
      case 'reader-hide-player': updateSetting('showSpeechPlayer',false);toast('コントローラーを隠しました。「文字」または設定で再表示できます');break;
      case 'reader-prev': if (state.reader) await goChapter(adjacentUrl(state.reader,-1)); break;
      case 'reader-next': if (state.reader) await goChapter(adjacentUrl(state.reader,1)); break;
      case 'append-next': if (state.reader) state.reader.nextError = null; await appendNext(true); break;
      case 'retry-chapter': if (state.reader) await loadSingleChapter(state.reader.currentUrl,state.reader.book.position); break;
      case 'download': await startDownload(id); break;
      case 'save-export': await startDownload(id,true); break;
      case 'pause-download': await call('pauseDownload',{bookId:id}); mergeDownload({bookId:id,state:'paused'}); if (state.view !== 'reader') renderShell(true); closeSheet(); toast('保存を一時停止しました'); break;
      case 'export': await exportBook(id); break;
      case 'export-saved': await exportSavedBook(id); break;
      case 'export-saved-confirm': closeSheet(); await call('exportSavedTxt',{bookId:id}); break;
      case 'import': closeSheet(); flushReadingPosition(); await call('importTxt'); break;
      case 'login': setReaderFullscreen(false);flushReadingPosition(); await call('openLogin',{source:button.dataset.source}); break;
      case 'original': setReaderFullscreen(false);flushReadingPosition(); closeSheet(); await call('openOriginal',{url:button.dataset.url}); break;
      case 'update-book': {
        const token = beginBusy('最新の目次と話数を確認しています');
        try { const data = rememberDetails(await call('updateBook',{bookId:id})); if (state.reader && state.reader.book.id === id) { state.reader.chapters=data.chapters; updateReaderChrome(); updateNextArea(); } if (state.sheet && state.sheet.kind === 'book') await showBookMenu(id); else renderToc(data); if (state.view !== 'reader') renderShell(true); toast(`作品情報を更新しました · 全${knownTotal(data.book)}話`); } finally { endBusy(token); }
        break;
      }
      case 'delete-book-confirm': {
        const book = getBook(id); if (!book) break;
        openSheet('本棚から削除', `<p class="sheet-description">「${esc(book.title)}」を削除します。この作品の保存済み本文・読書位置・付箋も端末から削除されます。</p><div class="sheet-actions"><button class="button secondary" data-action="close-sheet">キャンセル</button><button class="button danger" data-action="delete-book" data-book="${esc(id)}">${icon('trash')}削除する</button></div>`, {kind:'delete',bookId:id}); break;
      }
      case 'delete-book': await call('deleteBook',{bookId:id}); state.books=state.books.filter(book => book.id !== id); state.downloads=state.downloads.filter(job => job.bookId !== id); detailsCache.delete(id); exportQueue.delete(id); if (state.reader && state.reader.book.id === id) state.reader=null; closeSheet(); state.view='library'; renderShell(); toast('本棚から削除しました'); break;
      case 'theme': updateSetting('theme',button.dataset.value); $$('.theme-choice').forEach(item => { const active=item.dataset.value===state.settings.theme; item.classList.toggle('selected',active); item.setAttribute('aria-pressed',String(active)); }); break;
      case 'font': updateSetting('fontFamily',button.dataset.value); $$('[data-action="font"]').forEach(item => item.classList.toggle('active',item.dataset.value===state.settings.fontFamily)); break;
      case 'toggle-setting': { const key=button.dataset.key; updateSetting(key,!state.settings[key]); button.setAttribute('aria-checked',String(state.settings[key])); if (key==='autoNext' && state.settings.autoNext && state.reader) { prefetchNext(state.reader); onReaderScroll(); } break; }
    }
  }
  document.addEventListener('click',event => {
    if (event.target && event.target.dataset && event.target.dataset.sheetLayer === 'true') { closeSheet(); return; }
    const button = event.target.closest && event.target.closest('[data-action]');
    if (!button) { revealReaderFromTap(event);return; }
    if (button.disabled) return;
    event.preventDefault(); handleAction(button).catch(showError);
  });
  document.addEventListener('submit',event => {
    const form = event.target; if (!form.dataset.form) return; event.preventDefault();
    const data = new FormData(form);
    (async () => {
      if(form.dataset.form==='speech-settings')await saveSpeechSettings(form,data);
      else if(form.dataset.form==='speech-range')await beginSpeechRange(false);
      else if(form.dataset.form==='speech-cast')await saveSpeechCast(form,data);
      else if(form.dataset.form==='speech-unit')await saveSpeechUnit(form,data);
      else if(form.dataset.form==='speech-pronunciation')await saveSpeechPronunciation(form,data);
      else if(form.dataset.form==='speech-memory')await saveMemoryFact(form);
      else if(form.dataset.form==='speech-memory-filter'&&state.memoryPage){state.memoryPage.query=String(data.get('query')||'');state.memoryPage.page=0;renderShell(true);}
      else if(form.dataset.form==='speech-unit-filter')showSpeechUnits(0,String(data.get('query')||''));
      else if(form.dataset.form==='speech-cast-filter'&&state.castPage){state.castPage.query=String(data.get('query')||'');state.castPage.page=0;renderShell(true);}
      else if (form.dataset.form === 'search') await search(data.get('query'),form.dataset.from === 'library' ? 'all' : state.search.source,1);
      else if (form.dataset.form === 'url') await openUrl(data.get('url'));
      else if (form.dataset.form === 'toc-filter' && state.sheet?.kind === 'toc' && state.sheet.details) {
        state.sheet.filter = String(data.get('filter') || '');
        renderToc(state.sheet.details,false);
      }
      else if (form.dataset.form === 'mark' && state.sheet && state.sheet.kind === 'new-mark') {
        const saved = {...state.sheet}; const mark = await call('addMark',{bookId:saved.bookId,...saved.position,excerpt:saved.excerpt,note:String(data.get('note') || '').trim()});
        if (state.reader && state.reader.book.id === saved.bookId) state.reader.marks.push(mark); closeSheet(); toast('付箋を保存しました');
      }
    })().catch(showError);
  });
  document.addEventListener('input',event => {
    const input=event.target;const pronunciationForm=input.closest('[data-form="speech-pronunciation"]');if(pronunciationForm){rememberPronunciationDraft(pronunciationForm);updatePronunciationEditor(pronunciationForm);}const memoryForm=input.closest('[data-form="speech-memory"]');if(memoryForm)memoryDraftChanged(memoryForm);const rangeForm=input.closest('[data-form="speech-range"]');if(rangeForm){editSpeechRange(rangeForm);return;}const castForm=input.closest('[data-form="speech-cast"]');if(castForm)castDraftChanged(castForm);if(input.closest('[data-form="speech-cast-filter"]')&&state.castPage)state.castPage.query=input.value;if(input.closest('[data-form="speech-memory-filter"]')&&state.memoryPage)state.memoryPage.query=input.value; if (!input.dataset.setting) return;
    const key=input.dataset.setting; const value=Number(input.value); updateSetting(key,value);
    $$(`[data-setting-output="${key}"]`).forEach(output => { output.textContent=key==='fontSize' ? value+'px' : value.toFixed(1); });
  });
  document.addEventListener('change',event=>{const input=event.target;const memoryForm=input.closest('[data-form="speech-memory"]');if(memoryForm)memoryDraftChanged(memoryForm);
    if(input.name==='actingMode'){updateActingMode(input.closest('[data-form="speech-unit"]'));return;}
    if(input.name==='analysisProvider'){updateAnalysisProviderView();return;}
    if(input.id==='chatgpt-model'){input.dataset.dirty='true';return;}
    if(input.hasAttribute('data-chatgpt-account')){const id=input.value;if(id)chatgptAction('speechChatgptSelect',{accountId:id}).catch(showError);else renderChatgptSettings();return;}
    const castForm=input.closest('[data-form="speech-cast"]');if(castForm)castDraftChanged(castForm);const command=input.dataset.speechControl;if(!command)return;const value=Number(input.value);if(command==='speed')speech.settings.speed=value;else speech.settings.sleepMinutes=value;speechControl(command,command==='speed'?{speed:value}:{minutes:value}).catch(showError);});
  document.addEventListener('keydown',event=>{
    if(event.key==='Escape'&&state.reader&&state.reader.fullscreen&&!state.sheet){event.preventDefault();setReaderFullscreen(false);return;}
    const host=$('#chatgpt-welcome-host');if(!host||host.hidden)return;
    if(event.key==='Escape'){event.preventDefault();chatgptAction('speechChatgptWelcome',{accountId:chatgpt.activeAccountId}).catch(showError);return;}
    if(event.key!=='Tab')return;
    const buttons=$$('button:not([disabled])',host),first=buttons[0],last=buttons[buttons.length-1];
    if(!first)return;
    if(event.shiftKey&&(document.activeElement===first||!host.contains(document.activeElement))){event.preventDefault();last.focus();}
    else if(!event.shiftKey&&(document.activeElement===last||!host.contains(document.activeElement))){event.preventDefault();first.focus();}
  });
  document.addEventListener('visibilitychange',() => { if (document.hidden) flushReadingPosition();syncAnalysisClocks(); });
  window.addEventListener('pagehide',() => {flushReadingPosition();if(analysisClockTimer){clearInterval(analysisClockTimer);analysisClockTimer=0;}});
  window.addEventListener('resize',restoreReaderAfterResize);
  function updateOnline() { const offline=navigator.onLine===false; $('#offline-label').hidden=!offline; document.body.classList.toggle('is-offline',offline); }
  window.addEventListener('online',updateOnline); window.addEventListener('offline',updateOnline);

  // Only used when these bundled assets are opened outside Android for UI inspection.
  const previewBook = {id:'preview',source:'local',url:'local://preview',title:'動作確認用の短い小説',author:'アプリ内サンプル',description:'この文章は表示確認のためのオリジナルサンプルです。',total:3,downloaded:3,readCount:0,complete:true,tocComplete:true,updatedAt:Date.now()};
  const previewChapters = [0,1,2].map(index => ({url:`local://preview/${index}`,title:['本棚の一冊','読みかけのページ','つづきの保存'][index],index,cached:true,read:false}));
  const previewMarks=[];
  async function previewCall(action,payload) {
    const clone=value=>JSON.parse(JSON.stringify(value));
    const details=()=>({book:clone(previewBook),chapters:clone(previewChapters),marks:clone(previewMarks),position:clone(previewBook.position||null)});
    if (action==='bootstrap') return {books:[clone(previewBook)],settings:{...state.settings},lastBookId:previewBook.position?'preview':'',downloads:[]};
    if (action==='getBook'||action==='updateBook') return details();
    if (action==='readChapter') {
      const meta=previewChapters.find(chapter=>chapter.url===payload.chapterUrl)||previewChapters[0];
      const lines=['これは「つづき」の表示を確かめるためのサンプルです。実際のサイトから取得した本文ではありません。','机の上に、一冊の本が置いてあった。最後に読んだページには、小さな付箋が残っている。','付箋を確かめ、前回のつづきを開いた。短い段落を一つ読み、指で静かに画面を送る。','設定を変えると、文字の大きさや行間がその場で変わる。保存した位置は、別の作品を開いたあとにも残る。','下まで読むと次の話がつながる。目次では、読んだ話とまだ読んでいない話を確かめられる。','本文を保存しておけば、通信できない場所でも本を開ける。'];
      return {...clone(meta),bookId:'preview',paragraphs:lines.map(text=>({text})),preface:[{text:'表示確認用のオリジナル文章です。'}],afterword:[],prevUrl:meta.index?previewChapters[meta.index-1].url:'',nextUrl:meta.index<2?previewChapters[meta.index+1].url:''};
    }
    if (action==='savePosition') { previewBook.position={...payload}; previewBook.lastReadAt=Date.now(); return {}; }
    if (action==='setChapterRead') { const c=previewChapters.find(item=>item.url===payload.chapterUrl); if(c)c.read=payload.read; previewBook.readCount=previewChapters.filter(item=>item.read).length; return {book:clone(previewBook)}; }
    if (action==='getMarks') return {marks:clone(previewMarks)};
    if (action==='addMark') { const mark={...payload,id:'preview-mark-'+Date.now(),createdAt:Date.now()}; previewMarks.push(mark); return clone(mark); }
    if (action==='deleteMark') { const i=previewMarks.findIndex(mark=>mark.id===payload.id); if(i>=0)previewMarks.splice(i,1); return {}; }
    if (action==='settings') return {settings:{...payload.settings}};
    if (action==='prefetch'||action==='readerDisplay') return {};
    if (action==='search') return {items:[],more:false,warnings:['UI確認用です。実サイトの検索はAndroidアプリで利用できます。']};
    if (action==='getDownloads') return {downloads:[]};
    if (action==='downloadBook'||action==='resumeDownload') return {bookId:'preview',state:'complete',total:3,completed:3};
    if (action==='pauseDownload'||action==='deleteBook') return {};
    throw new Error('UI確認用です。この操作はAndroidアプリで利用できます。');
  }
  async function boot() {
    if (!hasNative) { $('#preview-label').hidden=false; document.body.classList.add('is-preview'); }
    updateOnline();
    try {
      const data=await call('bootstrap'); state.books=Array.isArray(data.books)?data.books:[]; state.downloads=Array.isArray(data.downloads)?data.downloads:[]; state.settings={...defaultSettings,...(data.settings||{})}; state.lastBookId=data.lastBookId||'';
      applySettings(); state.booted=true; renderShell();
      refreshSpeech(null).catch(()=>{});
      const incoming=earlyEvents.splice(0); const hasIncomingUrl=incoming.some(([type])=>type==='openUrl'); incoming.forEach(([type,value])=>handleNativeEvent(type,value));
      const last=getBook(state.lastBookId); if (!hasIncomingUrl && state.settings.resumeOnLaunch && last && last.position && last.position.chapterUrl) await openBook(last.id);
    } catch (error) {
      state.booted=true; applySettings(); renderShell(); showError(error);
    }
  }
  boot();
})();
