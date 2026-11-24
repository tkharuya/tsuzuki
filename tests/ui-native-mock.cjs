// Local UI test data only. This file is never packaged as an Android asset.
// No external websites, accounts, cookies or real novels are used.
function initializeNativeMock(options) {
  const opts = options || {};
  const copy = value => JSON.parse(JSON.stringify(value));
  const defaultSettings = {
    theme:'paper',fontSize:20,lineHeight:2,fontFamily:'serif',showRuby:true,
    showPreface:false,autoNext:true,resumeOnLaunch:false,keepScreenOn:false,showSpeechPlayer:opts.showSpeechPlayer===true,autoFullscreen:opts.autoFullscreen!==false
  };
  const rows = [
    {id:'kakuyomu_fixture',url:'https://kakuyomu.jp/works/123456789012345678',source:'kakuyomu',title:'海辺の図書室と、次の一冊',author:'画面確認用の著者'},
    {id:'narou_fixture',url:'https://ncode.syosetu.com/n1234ab/',source:'narou',title:'長い作品名を表示した場合のレイアウト確認用の物語',author:'日本語表示の確認用'},
    {id:'wuxiaworld_fixture',url:'https://www.wuxiaworld.com/novel/ui-test-reading-room',source:'wuxiaworld',title:'The Reading Room — Original UI Fixture',author:'UI Test Author'}
  ];
  const initial = {settings:defaultSettings,books:{},chapters:{},bodies:{},marks:[],jobs:{},lastBookId:rows[0].id};
  for (const row of rows) {
    const chapters = [];
    for (let i=0;i<(opts.rangeChapters||4);i++) {
      const url=row.source==='kakuyomu'?row.url+'/episodes/'+(9000000000000000+i):row.source==='wuxiaworld'?row.url+'/test-chapter-'+(i+1):row.url+(i+1)+'/';
      const meta={url,title:row.source==='wuxiaworld'?'Chapter '+(i+1)+': The Reading Room':['朝の読書','書架をたどる','午後の窓辺','次の一冊'][i]||'範囲確認用の第'+(i+1)+'話',index:i,section:i<2?'第一章':'第二章',cached:i<2||(row.source==='wuxiaworld'&&opts.wuxiaworldPartialToc),read:i===0};
      chapters.push(meta);
      const paragraphs = Array.from({length:28},(_,p)=>({text:row.source==='wuxiaworld'?`${p+1}. This original paragraph checks the reading screen and saved position. A reader opens a notebook beside the window. This is test chapter ${i+1}, paragraph ${p+1}.`:`${p+1}　これは画面と読書位置を確かめるために作成した文章です。窓の近くの席で本を開き、続きを読みました。第${i+1}話の段落${p+1}です。`}));
      if(row.source!=='wuxiaworld')paragraphs[3]={text:'漢字と振り仮名の表示を確認します。',html:'<ruby>漢字<rt>かんじ</rt></ruby>と振り仮名の表示を確認します。'};
      initial.bodies[url]={bookId:row.id,url,title:meta.title,index:i,paragraphs,preface:[{text:'前書きのテストです。'}],afterword:[{text:'後書きのテストです。'}],cached:meta.cached,prevUrl:'',nextUrl:''};
    }
    chapters.forEach((c,i)=>{initial.bodies[c.url].prevUrl=i?chapters[i-1].url:'';initial.bodies[c.url].nextUrl=i+1<chapters.length?chapters[i+1].url:'';});
    initial.chapters[row.id]=chapters;
    const partial=row.source==='wuxiaworld'&&opts.wuxiaworldPartialToc;
    initial.books[row.id]={...row,description:'操作確認用に作成した作品データです。',total:partial?6:chapters.length,downloaded:partial?4:2,readCount:1,currentIndex:0,tocComplete:!partial,complete:false,updatedAt:1,lastReadAt:1,position:{chapterUrl:chapters[0].url,index:0,paragraph:0,offset:0,updatedAt:1}};
  }
  if(opts.wuxiaworldSparseToc){
    const id='wuxiaworld_fixture',b=initial.books[id],previous=initial.chapters[id];
    const chapters=[100,101,200].map((number,i)=>{
      const meta={...previous[i],url:b.url+'/test-chapter-'+number,title:'Chapter '+number+': The Reading Room',index:number-1,section:''};
      initial.bodies[meta.url]={...copy(initial.bodies[previous[i].url]),url:meta.url,title:meta.title,index:meta.index,prevUrl:i===2?b.url+'/test-chapter-199':'',nextUrl:i===2?b.url+'/test-chapter-201':''};
      return meta;
    });
    initial.chapters[id]=chapters;
    Object.assign(b,{total:300,tocComplete:false,currentIndex:99,position:{chapterUrl:chapters[0].url,index:99,paragraph:0,offset:0,updatedAt:1}});
  }
  let state;
  try { state=JSON.parse(localStorage.getItem('tsuzuki-ui-test-state')||'null')||initial; } catch (_) { state=initial; }
  if (opts.empty) {state={...initial,books:{},chapters:{},bodies:{},marks:[],jobs:{},lastBookId:''};}
  if (opts.resumeOnLaunch) state.settings.resumeOnLaunch=true;
  window.__nativeCalls=[];
  window.__mockState=state;
  const persist=()=>localStorage.setItem('tsuzuki-ui-test-state',JSON.stringify(state));
  const book=id=>{
    const b=state.books[id];if(!b)throw new Error('作品がありません');
    const chapters=state.chapters[id]||[];
    b.downloaded=chapters.filter(c=>c.cached).length;b.readCount=chapters.filter(c=>c.read).length;b.complete=b.tocComplete!==false&&chapters.length>0&&b.downloaded===chapters.length&&chapters.length>=b.total;
    return b;
  };
  const details=id=>({book:book(id),chapters:state.chapters[id],position:book(id).position,marks:state.marks.filter(m=>m.bookId===id)});
  const notify=(type,data)=>{if(typeof window.onNativeEvent==='function')window.onNativeEvent(type,copy(data));};
  const result=(id,ok,data)=>{if(typeof window.onNativeResult==='function')window.onNativeResult(id,ok,copy(data));};
  const requireReadable=(bookId,chapter)=>{
    if(bookId==='wuxiaworld_fixture'&&chapter.index===opts.wuxiaworldLockedChapter)throw Object.assign(new Error('この話はロックされています。公式ページでログイン・公開状態を確認してください。'),{code:'LOCKED',url:chapter.url});
  };
  const speechDefaults={engine:opts.speechEngine||'offline',speed:1,sleepMinutes:0,autoNext:true,followText:true,speechProvider:'voicevox',offlineDefaultVoice:'metan',deviceVoice:'',analysisProvider:'chatgpt',analysisModel:'gpt-6-luna',apiAnalysisModel:'gpt-6-luna',detailedActing:opts.detailedActing!==false,useStoryMemory:opts.useStoryMemory!==false,hasApiKey:false};
  state.speech=state.speech||{settings:{...speechDefaults},playback:{status:'idle'},plans:{},cast:{},positions:{}};
  state.speech.settings={...speechDefaults,...state.speech.settings};
  // Synthetic fixtures start with a ready model pack unless a test explicitly asks for first-install behavior.
  state.speech.offlinePacks=state.speech.offlinePacks||{status:'ready',ready:true,downloadedBytes:536870912,totalBytes:536870912,voiceCount:10,error:'',...(opts.offlinePacks||{})};
  window.__offlinePacksEvent=patch=>{Object.assign(state.speech.offlinePacks,copy(patch));persist();notify('speechOfflinePacks',state.speech.offlinePacks);};
  state.speech.ranges=state.speech.ranges||{};
  state.speech.memories=state.speech.memories||{};
  state.speech.memoryOverrides=state.speech.memoryOverrides||{};
  state.speech.chatgpt=state.speech.chatgpt||{status:'signed_out',busy:false,error:'',accounts:copy(opts.chatgptAccounts||[]),activeAccountId:opts.chatgptActiveAccountId||'',models:[],selectedModel:'',firstConnection:false};
  state.speech.chatgptCatalogs=state.speech.chatgptCatalogs||copy(opts.chatgptCatalogs||{});
  state.speech.chatgptSelectedModels=state.speech.chatgptSelectedModels||copy(opts.chatgptSelectedModels||{});
  state.speech.chatgptNotices=state.speech.chatgptNotices||{};
  const activeChatgpt=()=>state.speech.chatgpt.accounts.find(a=>a.id===state.speech.chatgpt.activeAccountId);
  const syncChatgpt=()=>{const g=state.speech.chatgpt,a=activeChatgpt();g.status=a&&a.connected?'connected':'signed_out';g.busy=false;g.models=a&&a.connected&&a.planEnabled?copy(state.speech.chatgptCatalogs[a.id]||[]):[];g.selectedModel=a?state.speech.chatgptSelectedModels[a.id]||'':'';g.firstConnection=!!(a&&a.connected&&a.planEnabled&&!state.speech.chatgptNotices[a.id]);return g;};
  syncChatgpt();
  // Helpers simulate official Native callbacks; they never open a browser or call a service.
  window.__chatgptEvent=patch=>{Object.assign(state.speech.chatgpt,copy(patch));persist();notify('speechChatgpt',state.speech.chatgpt);};
  window.__chatgptComplete=({account,models=[],selectedModel='',firstConnection=true})=>{const g=state.speech.chatgpt,i=g.accounts.findIndex(a=>a.id===account.id);if(i<0)g.accounts.push(copy(account));else g.accounts[i]=copy(account);g.activeAccountId=account.id;g.error='';state.speech.chatgptCatalogs[account.id]=copy(models);state.speech.chatgptSelectedModels[account.id]=selectedModel;state.speech.chatgptNotices[account.id]=!firstConnection;syncChatgpt();persist();notify('speechChatgpt',g);};
  const voiceCatalog=[['metan','四国めたん','はっきりした女性声',2],['zundamon','ずんだもん','明るいキャラクター声',3],['tsumugi','春日部つむぎ','軽快な女性声',8],['hau','雨晴はう','やわらかな女性声',10],['takehiro','玄野武宏','落ち着いた男性声',11],['kotaro','白上虎太郎','若い男性声',12],['ryusei','青山龍星','低い男性声',13],['himari','冥鳴ひまり','穏やかな女性声',14],['sora','九州そら','静かな女性声',16],['miko','櫻歌ミコ','幼いキャラクター声',43]].map(([id,name,description,style])=>({id,name,label:name+' · '+description,description,gender:'neutral',styles:[{id:style,name:'ノーマル',emotion:'neutral'}]}));
  const speechKey=p=>p.bookId+'|'+p.chapterUrl;
  const ensureCast=id=>state.speech.cast[id]||(state.speech.cast[id]=[{id:'narrator',name:'語り手',aliases:['語り'],gender:'neutral',tier:'narrator',offlineVoice:'metan',voiceLocked:true,profileLocked:false,deviceVoice:'',style:'',rate:1,pitch:1,manual:false},{id:'unknown',name:'話者未確定',aliases:[],gender:'neutral',tier:'extra',offlineVoice:'takehiro',voiceLocked:true,profileLocked:false,deviceVoice:'',style:'',rate:1,pitch:1,manual:false},...Array.from({length:opts.speechCastMembers||0},(_,i)=>({id:'fixture-character-'+i,name:'確認人物'+(i+1),aliases:['別名'+(i+1)],gender:'neutral',tier:i%2?'support':'main',offlineVoice:i%2?'zundamon':'hau',voiceLocked:true,profileLocked:false,deviceVoice:'',style:'',rate:1,pitch:1,manual:false}))]);
  const ensurePlan=p=>{
    const key=speechKey(p);if(state.speech.plans[key])return state.speech.plans[key];
    const body=state.bodies[p.chapterUrl];if(!body)throw new Error('本文を開いてください');
    const total=opts.speechUnits||body.paragraphs.length;
    return state.speech.plans[key]={chapterUrl:p.chapterUrl,title:body.title,inputHash:'synthetic-fixture',analysisStatus:'idle',analysisMethod:'chapter',memoryAssessed:false,analysisDetail:state.speech.settings.detailedActing?'detailed':'standard',analysisPhase:'',analyzedUnits:0,totalUnits:total,units:Array.from({length:total},(_,i)=>({unitId:'u'+i,index:i,paragraph:i%body.paragraphs.length,start:0,end:body.paragraphs[i%body.paragraphs.length].text.length,text:body.paragraphs[i%body.paragraphs.length].text+' 区間番号 '+(i+1),spokenText:body.paragraphs[i%body.paragraphs.length].text,kind:i%3===0?'dialogue':'narration',speakerId:i%3===0?'unknown':'narrator',emotion:'neutral',intensity:0,rate:1,pitch:1,confidence:0,analyzed:false,locked:false}))};
  };
  // Readiness is supplied by Native in production; these flags model that contract only.
  const pronunciationCounts=plan=>{if(!plan||!opts.pronunciation)return plan;for(const u of plan.units)u.pronunciationReady=u.pronunciationReady===true;plan.pronunciationTotalUnits=plan.units.length;plan.pronunciationUnits=plan.units.filter(u=>u.pronunciationReady).length;plan.missingPronunciationUnits=plan.pronunciationTotalUnits-plan.pronunciationUnits;return plan;};
  const pronunciationTemplate=unit=>{
    if(unit.pronunciationReady===true&&unit.pronunciation)return copy(unit.pronunciation);
    const text=typeof unit.spokenText==='string'?unit.spokenText:unit.text;
    const parts=unit.pronunciationTemplate?.parts||unit.performance?.parts||[{partId:'fixture-only-part',start:0,end:text.length}];
    return{version:1,sourceHash:unit.pronunciationTemplate?.sourceHash||'synthetic-pronunciation-'+unit.unitId+'-'+text.length,parts:parts.map(part=>({partId:part.partId,start:part.start,end:part.end,reading:text.slice(part.start,part.end).replace(/[\u0000-\u001f\u007f-\u009f]/g,' ')}))};
  };
  window.__pronunciationEvent=patch=>{const [key,plan]=Object.entries(state.speech.plans)[0];if(!plan)return;Object.assign(plan,copy(patch||{}));pronunciationCounts(plan);persist();notify('speechAnalysis',{bookId:key.split('|')[0],chapterUrl:plan.chapterUrl,plan,status:plan.analysisStatus});};
  const speechBundle=(p,prepare=false)=>({state:state.speech.playback,settings:state.speech.settings,chatgpt:state.speech.chatgpt,voiceCatalog,offlinePacks:state.speech.offlinePacks,cast:p.bookId?ensureCast(p.bookId):[],plan:pronunciationCounts(prepare?ensurePlan(p):state.speech.plans[speechKey(p)]||null),position:state.speech.positions[p.bookId]||{},analysisRange:state.speech.ranges[p.bookId]||{}});
  const memoryFor=bookId=>state.speech.memories[bookId]||(state.speech.memories[bookId]={version:1,revision:0,throughChapterUrl:'',throughChapterIndex:-1,facts:[],droppedFacts:0});
  const memoryTarget=p=>(state.chapters[p.bookId]||[]).find(c=>c.url===(p.chapterUrl||book(p.bookId).position?.chapterUrl))||(state.chapters[p.bookId]||[])[0];
  const memoryBundle=p=>{
    const stored=memoryFor(p.bookId),target=memoryTarget(p),index=target?.index||0,through=Math.min(stored.throughChapterIndex,index-1),chapters=state.chapters[p.bookId]||[];
    const facts=new Map(stored.facts.filter(f=>!f.manual&&(f.citations||[]).every(c=>(chapters.find(ch=>ch.url===c.chapterUrl)?.index??Infinity)<=through)).map(f=>[f.id,copy(f)]));
    for(const entry of (state.speech.memoryOverrides[p.bookId]||[]).filter(e=>e.fromIndex<=index).sort((a,b)=>a.fromIndex-b.fromIndex)){if(entry.removed)facts.delete(entry.id);else facts.set(entry.id,copy(entry.fact));}
    return{memory:{...copy(stored),throughChapterIndex:through,throughChapterUrl:chapters.find(c=>c.index===through)?.url||'',facts:[...facts.values()]},coverage:{fromNumber:through>=0?1:0,throughNumber:through+1,targetNumber:index+1,missingCount:Math.max(0,index-through-1)},cast:ensureCast(p.bookId)};
  };
  const historyPreview=p=>{const b=memoryBundle(p),enabled=state.speech.settings.detailedActing!==false&&state.speech.settings.useStoryMemory!==false,total=enabled?b.coverage.missingCount:0;return{workflowVersion:2,pronunciationVersion:1,useStoryMemory:enabled,historyFromNumber:total?b.coverage.throughNumber+1:0,historyToNumber:total?b.coverage.targetNumber-1:0,historyTotal:total,historyCompleted:0,historyThrough:b.coverage.throughNumber,memoryStatus:enabled?(total?'pending':'ready'):'disabled'};};
  const checkHistoryGuard=(p,preview)=>{if(p.expectedHistoryFromNumber!==preview.historyFromNumber||p.expectedHistoryToNumber!==preview.historyToNumber||p.expectedUseStoryMemory!==preview.useStoryMemory)throw new Error('作品メモに送る過去の範囲が変わりました。確認し直してください。');};
  const rangePreview=p=>{
    if(!Number.isSafeInteger(p.startNumber)||p.startNumber<1||!Number.isSafeInteger(p.count)||p.count<1)throw new Error('開始話と話数は1以上の整数で指定してください。');
    const b=book(p.bookId),chapters=state.chapters[p.bookId]||[],start=chapters.find(c=>c.index+1===p.startNumber);
    if(!start)throw new Error('開始話が目次に見つかりません。');
    const end=b.tocComplete?Math.min(b.total,p.startNumber+p.count-1):p.startNumber+p.count-1,amount=end-p.startNumber+1;
    return {...historyPreview({...p,chapterUrl:start.url}),bookId:p.bookId,startNumber:p.startNumber,startChapterUrl:start.url,startTitle:start.title,requestedCount:p.count,count:amount,endNumber:end,limitedToEnd:amount<p.count,tocComplete:b.tocComplete,knownTotal:b.total};
  };
  const emitRange=job=>notify('speechAnalysis',{bookId:job.bookId,chapterUrl:job.currentChapterUrl,status:job.status,error:job.error||'',analysisRange:job,cast:ensureCast(job.bookId)});
  window.__rangeEvent=(bookId,patch)=>{const old=state.speech.ranges[bookId]||{};state.speech.ranges[bookId]={...old,...copy(patch),bookId,updatedAt:Math.max(Date.now(),Number(old.updatedAt||0)+1)};persist();emitRange(state.speech.ranges[bookId]);};
  function mockSpeech(action,p){
    if(action==='speechState')return speechBundle(p);
    if(action==='speechAnalysisPreview')return {...historyPreview(p),bookId:p.bookId,chapterUrl:p.chapterUrl};
    if(action==='speechMemory')return memoryBundle(p);
    if(action==='speechMemorySave'){
      const memory=memoryFor(p.bookId),fromIndex=memoryTarget(p)?.index||0,current=memoryBundle(p).memory.facts;
      const apply=entry=>{const old=state.speech.memoryOverrides[p.bookId]||[];state.speech.memoryOverrides[p.bookId]=old.filter(e=>e.id!==entry.id||e.fromIndex!==fromIndex).concat({...entry,fromIndex});};
      for(const id of p.removeIds||[])apply({id,removed:true});
      for(const patch of p.changes||[]){const old=current.find(f=>f.id===patch.id),fact={...(old||{id:'manual-'+(++memory.revision),type:patch.type||'state',subjectId:'',objectId:'',citations:[]}),text:patch.text,uncertain:!!patch.uncertain,manual:true};apply({id:fact.id,fact});}
      memory.revision++;return memoryBundle(p);
    }
    if(action==='speechPrepare')return speechBundle(p,true);
    if(action==='speechAnalysisRangeOptions'){const b=book(p.bookId),chapters=state.chapters[p.bookId]||[],current=chapters.find(c=>c.url===p.chapterUrl);return{currentNumber:current?current.index+1:0,currentChapterUrl:p.chapterUrl,knownTotal:b.total,tocComplete:b.tocComplete,chapters:chapters.map(c=>({number:c.index+1,title:c.title,url:c.url,cached:c.cached})),analysisRange:state.speech.ranges[p.bookId]||{}};}
    if(action==='speechAnalysisRangePreview')return rangePreview(p);
    if(action==='speechAnalyzeRange'||action==='speechResumeRange'){
      if(!p.confirmed)throw new Error('範囲全体の本文送信を確認してください。');
      const g=state.speech.chatgpt,a=activeChatgpt(),provider=state.speech.settings.analysisProvider,model=provider==='chatgpt'?g.selectedModel:state.speech.settings.apiAnalysisModel;
      if(provider==='chatgpt'&&(!a||!a.connected||!a.planEnabled||!g.models.some(m=>m.slug===model)))throw new Error('ChatGPTのアカウントとモデルを確認してください。');
      if(provider==='api_key'&&!state.speech.settings.hasApiKey)throw new Error('APIキーを設定してください。');
      if(action==='speechAnalyzeRange'){
        const preview=rangePreview(p);checkHistoryGuard(p,preview);if(p.expectedStartUrl&&p.expectedStartUrl!==preview.startChapterUrl)throw new Error('開始話が変更されました。範囲を確認し直してください。');
        state.speech.ranges[p.bookId]={...preview,jobId:'fixture-range-'+Date.now(),status:'running',completedChapters:0,currentNumber:preview.startNumber,currentChapterUrl:preview.startChapterUrl,currentTitle:preview.startTitle,analyzedUnits:0,totalUnits:opts.speechUnits||28,model,provider,detailedActing:state.speech.settings.detailedActing===true,error:'',canResume:false,updatedAt:Date.now()};
      }else{
        const job=state.speech.ranges[p.bookId];if(!job||!job.canResume)throw new Error('再開できる範囲がありません。');if(job.model!==model||job.provider!==provider)throw new Error('同じモデルと方式に戻してから再開してください。');Object.assign(job,{status:'running',canResume:false,error:'',updatedAt:Math.max(Date.now(),job.updatedAt+1)});
      }
      emitRange(state.speech.ranges[p.bookId]);return speechBundle(p);
    }
    if(action==='speechSettings'){
      const settings=p.settings||{},provider=settings.analysisProvider||state.speech.settings.analysisProvider;
      if(provider==='chatgpt'&&Object.hasOwn(settings,'analysisModel')){const g=state.speech.chatgpt,a=activeChatgpt();if(!a||!a.connected||!a.planEnabled||p.chatgptAccountId!==a.id||!g.models.some(m=>m.slug===settings.analysisModel))throw Object.assign(new Error('現在のChatGPTアカウントで使えるモデルを選択してください。'),{code:'MODEL_UNAVAILABLE'});state.speech.chatgptSelectedModels[a.id]=settings.analysisModel;g.selectedModel=settings.analysisModel;}
      const patch=copy(settings);if(provider==='chatgpt')delete patch.analysisModel;Object.assign(state.speech.settings,patch);if(provider==='api_key'&&Object.hasOwn(settings,'analysisModel'))state.speech.settings.apiAnalysisModel=settings.analysisModel;if(p.apiKey)state.speech.settings.hasApiKey=true;if(p.deleteApiKey)state.speech.settings.hasApiKey=false;return state.speech.settings;
    }
    if(action.startsWith('speechChatgpt')){
      const g=state.speech.chatgpt;
      if(action==='speechChatgptUsage')return {opened:true};
      if(action==='speechChatgptLogin'){g.status='waiting';g.busy=true;g.error='';}
      else if(action==='speechChatgptCancel'){g.error='';syncChatgpt();}
      else if(action==='speechChatgptSelect'){if(!g.accounts.some(a=>a.id===p.accountId))throw new Error('アカウントがありません');g.activeAccountId=p.accountId;g.error='';syncChatgpt();}
      else if(action==='speechChatgptLogout'){const a=g.accounts.find(a=>a.id===p.accountId);if(a)a.connected=false;g.error='';syncChatgpt();}
      else if(action==='speechChatgptModels'){g.error='';syncChatgpt();}
      else if(action==='speechChatgptWelcome'){state.speech.chatgptNotices[p.accountId]=true;g.firstConnection=false;}
      else throw new Error('未対応のChatGPT Mock操作');
      notify('speechChatgpt',g);return g;
    }
    if(action==='speechOfflinePacks')return state.speech.offlinePacks;
    if(action==='speechOfflineInstall'){Object.assign(state.speech.offlinePacks,{status:'downloading',ready:false,error:''});notify('speechOfflinePacks',state.speech.offlinePacks);return state.speech.offlinePacks;}
    if(action==='speechOfflineCancel'){Object.assign(state.speech.offlinePacks,{status:'paused',ready:false});notify('speechOfflinePacks',state.speech.offlinePacks);return state.speech.offlinePacks;}
    if(action==='speechVoices')return {voices:[{id:'mock-ja-female',name:'日本語の声A',locale:'ja-JP',network:false,quality:300,latency:100},{id:'mock-ja-male',name:'日本語の声B',locale:'ja-JP',network:false,quality:300,latency:100},{id:'mock-en-online',name:'English',locale:'en-US',network:true,quality:400,latency:200}]};
    if(action==='speechControl'){
      let play=state.speech.playback;
      const engine=p.engine||state.speech.settings.engine;
      if(['play','preview','resume'].includes(p.command)&&engine==='offline'&&!state.speech.offlinePacks.ready)throw Object.assign(new Error('初回の音声データを音声設定からダウンロードしてください。'),{code:'VOICE_PACK_REQUIRED'});
      if(p.command==='play'){
        const plan=ensurePlan(p);play=state.speech.playback={status:'playing',bookId:p.bookId,chapterUrl:p.chapterUrl,unitIndex:Math.max(0,Math.min(plan.units.length-1,p.unitIndex!==undefined?p.unitIndex:(state.speech.positions[p.bookId]||{}).chapterUrl===p.chapterUrl?(state.speech.positions[p.bookId]||{}).unitIndex||0:p.paragraph||0)),unitCount:plan.units.length,engine:state.speech.settings.engine,speed:state.speech.settings.speed,pauseMode:state.speech.settings.engine==='device'?'unit':'exact',sleepAt:0};
      }else if(p.command==='preview'){play=state.speech.playback={status:'playing',preview:true,bookId:p.bookId,chapterUrl:'',unitIndex:0,unitCount:1,speakerId:p.speakerId,offlineVoice:p.offlineVoice||'',auditionMode:p.sceneAudition?(p.dialogueMode||(p.animeActing?'anime':'standard')):'',engine,speed:1};}
      else if(p.command==='pause')play.status='paused';
      else if(p.command==='resume')play.status='playing';
      else if(p.command==='stop')play.status='idle';
      else if(p.command==='next')play.unitIndex=Math.min(play.unitCount-1,(play.unitIndex||0)+1);
      else if(p.command==='previous')play.unitIndex=Math.max(0,(play.unitIndex||0)-1);
      else if(p.command==='seek')play.unitIndex=Math.min(play.unitCount-1,Math.max(0,p.unitIndex||0));
      else if(p.command==='speed'){play.speed=p.speed;state.speech.settings.speed=p.speed;}
      else if(p.command==='sleep'){play.sleepAt=p.minutes?Date.now()+p.minutes*60000:0;state.speech.settings.sleepMinutes=p.minutes;}
      const plan=state.speech.plans[speechKey(play)],unit=plan&&plan.units[play.unitIndex];if(unit)Object.assign(play,{paragraph:unit.paragraph,speakerId:unit.speakerId,kind:unit.kind,emotion:unit.emotion});
      if(play.bookId&&!play.preview)state.speech.positions[play.bookId]={chapterUrl:play.chapterUrl,unitIndex:play.unitIndex};notify('speechState',play);return play;
    }
    if(action==='speechAnalyze'){
      if(!p.confirmed)throw new Error('本文送信を確認してください');
      if(state.speech.settings.analysisProvider==='chatgpt'){const a=activeChatgpt(),g=state.speech.chatgpt;if(!a||!a.connected)throw Object.assign(new Error('ChatGPTで再ログインしてください。'),{code:'CHATGPT_LOGIN_REQUIRED'});if(!a.planEnabled)throw Object.assign(new Error('ChatGPTプランの利用許可が必要です。'),{code:'CHATGPT_PLAN_REQUIRED'});if(!g.models.some(m=>m.slug===g.selectedModel))throw new Error('使えるモデルを選択してください。');}
      else if(!state.speech.settings.hasApiKey)throw new Error('OpenAI APIキーを登録してください。');
      const preview=historyPreview(p);checkHistoryGuard(p,preview);const plan=ensurePlan(p);Object.assign(plan,{analysisStatus:'running',analysisMethod:state.speech.settings.detailedActing?'chapter':'standard',analysisDetail:state.speech.settings.detailedActing?'detailed':'standard',analysisPhase:'analyzing',analysisMetrics:{requestCount:1,modelMs:0,validationMs:0,elapsedMs:0}});if(opts.analysisActivity){const number=(memoryTarget(p)?.index||0)+1;plan.analysisActivity={stage:'waiting',scope:'chapter',stageStartedAt:Date.now(),runStartedAt:Date.now(),requestNumber:1,chapterFromNumber:number,chapterToNumber:number,targetUnits:plan.units.length,targetChapters:1};}notify('speechAnalysis',{...p,plan,cast:ensureCast(p.bookId),status:'running'});return speechBundle(p,true);
    }
    if(action==='speechCancel'){
      for(const [key,plan]of Object.entries(state.speech.plans)){if(plan.analysisStatus!=='running')continue;plan.analysisStatus='paused';if(plan.analysisActivity)Object.assign(plan.analysisActivity,{stage:'paused',endedAt:Date.now()});const bookId=key.split('|')[0];notify('speechAnalysis',{bookId,chapterUrl:plan.chapterUrl,plan,cast:ensureCast(bookId),status:'paused'});}for(const job of Object.values(state.speech.ranges)){if(job.status!=='running')continue;Object.assign(job,{status:'paused',canResume:true,updatedAt:Math.max(Date.now(),job.updatedAt+1)});emitRange(job);}return {status:'pausing'};
    }
    if(action==='speechCast'){
      const merged=ensureCast(p.bookId).map(copy);
      for(const patch of p.cast||[]){
        const index=merged.findIndex(c=>c.id===patch.id),previous=index>=0?merged[index]:{id:patch.id,aliases:[],deviceVoice:'',manual:false};
        const changed=index<0||Object.keys(patch).some(key=>key!=='manual'&&JSON.stringify(patch[key])!==JSON.stringify(previous[key]));
        const next={...previous,...patch,manual:!!(previous.manual||patch.manual||changed)};
        if(index>=0)merged[index]=next;else merged.push(next);
      }
      state.speech.cast[p.bookId]=merged;if(state.speech.playback.bookId===p.bookId){state.speech.playback.status='idle';notify('speechState',state.speech.playback);}return merged;
    }
    if(action==='speechPronunciation'){const plan=pronunciationCounts(ensurePlan(p)),unit=plan.units.find(u=>u.unitId===p.unitId);if(!unit)throw new Error('区間がありません');return{pronunciation:pronunciationTemplate(unit),pronunciationLocked:unit.pronunciationLocked===true,hasPronunciation:unit.pronunciationReady===true};}
    if(action==='speechPronunciationSave'){
      const plan=ensurePlan(p),unit=plan.units.find(u=>u.unitId===p.unitId);if(!unit)throw new Error('区間がありません');
      if(p.reset===true){delete unit.pronunciation;unit.pronunciationReady=false;unit.pronunciationLocked=false;}
      else {const canonical=pronunciationTemplate(unit),value=p.pronunciation;if(!value||value.version!==1||value.sourceHash!==canonical.sourceHash||!Array.isArray(value.parts)||value.parts.length!==canonical.parts.length||value.parts.some((part,i)=>part.partId!==canonical.parts[i].partId||part.start!==canonical.parts[i].start||part.end!==canonical.parts[i].end||typeof part.reading!=='string'||(/^[\u0009-\u000d\u001c-\u0020\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]*$/.test(part.reading)!==/^[\u0009-\u000d\u001c-\u0020\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]*$/.test((unit.spokenText||unit.text).slice(part.start,part.end)))))throw new Error('読みと本文が一致しません');unit.pronunciation=copy(value);unit.pronunciationReady=true;unit.pronunciationLocked=true;}
      return{plan:pronunciationCounts(plan)};
    }
    if(action==='speechUnit'){const plan=ensurePlan(p),unit=plan.units.find(u=>u.unitId===p.unitId);if(!unit)throw new Error('区間がありません');const changes=copy(p.changes||{});if(changes.performance===null||(!Object.hasOwn(changes,'performance')&&['emotion','intensity','rate','pitch'].some(key=>Object.hasOwn(changes,key)))){delete unit.performance;delete changes.performance;}Object.assign(unit,changes,{locked:true,analyzed:true});if(state.speech.playback.bookId===p.bookId){state.speech.playback.status='idle';notify('speechState',state.speech.playback);}return plan;}
    if(action==='speechClearCache')return {removedBytes:1048576};
    throw new Error('未対応の音声Mock操作');
  }

  window.Native={request(raw){
    const req=JSON.parse(raw);window.__nativeCalls.push(copy(req));
    setTimeout(()=>{
      try {
        const p=req.payload||{};let out={};const oldSpeech=copy(state.speech.playback),oldChatgpt=copy(state.speech.chatgpt);
        if(req.action.startsWith('speech'))out=mockSpeech(req.action,p);
        else switch(req.action){
          case 'bootstrap':out={books:Object.keys(state.books).map(book),settings:state.settings,lastBookId:state.lastBookId,downloads:Object.values(state.jobs)};break;
          case 'getBook':out=details(p.bookId);break;
          case 'openUrl':{const id=Object.keys(state.books).find(x=>state.books[x].url===p.url||(state.chapters[x]||[]).some(c=>c.url===p.url));if(!id)throw new Error('試験データにないURLです');out=details(id);if(state.books[id].url!==p.url)out={...out,chapterUrl:p.url};break;}
          case 'updateBook':out=details(p.bookId);break;
          case 'search':out={items:Object.keys(state.books).map(book).filter(b=>['kakuyomu','narou'].includes(b.source)&&(!p.source||p.source==='all'||b.source===p.source)),more:false,page:p.page||1,warnings:[]};break;
          case 'readChapter':{
            const c=(state.chapters[p.bookId]||[]).find(x=>x.url===p.chapterUrl);if(!c)throw new Error('話がありません');requireReadable(p.bookId,c);c.cached=true;state.bodies[c.url].cached=true;out=state.bodies[c.url];break;
          }
          case 'prefetch':{const c=(state.chapters[p.bookId]||[]).find(x=>x.url===p.chapterUrl);if(c){requireReadable(p.bookId,c);c.cached=true;}break;}
          case 'savePosition':{const b=book(p.bookId);b.position={chapterUrl:p.chapterUrl,index:p.index,paragraph:p.paragraph,offset:p.offset,updatedAt:Date.now()};b.currentIndex=p.index;b.lastReadAt=Date.now();state.lastBookId=p.bookId;break;}
          case 'setChapterRead':{const c=state.chapters[p.bookId].find(x=>x.url===p.chapterUrl);if(c)c.read=p.read!==false;out={book:book(p.bookId)};break;}
          case 'addMark':{const b=book(p.bookId);const mark={...p,id:'mark-'+(state.marks.length+1),bookTitle:b.title,createdAt:Date.now()};state.marks.unshift(mark);out=mark;break;}
          case 'getMarks':out={marks:state.marks.filter(m=>!p.bookId||m.bookId===p.bookId)};break;
          case 'deleteMark':state.marks=state.marks.filter(m=>m.id!==p.id);break;
          case 'getDownloads':out={downloads:Object.values(state.jobs)};break;
          case 'downloadBook':case 'resumeDownload':{
            const b=book(p.bookId);
            if(opts.completeDownloadsImmediately&&b.tocComplete!==false){state.chapters[b.id].forEach(c=>{c.cached=true;state.bodies[c.url].cached=true;});book(b.id);}
            const job={bookId:b.id,title:b.title,total:b.total,completed:b.downloaded,state:b.complete?'complete':'downloading',error:''};
            state.jobs[b.id]=job;out=job;
            if(job.state==='complete')notify('download',job);else setTimeout(()=>notify('download',job),5);
            break;
          }
          case 'pauseDownload':if(state.jobs[p.bookId]){state.jobs[p.bookId].state='paused';notify('download',state.jobs[p.bookId]);}break;
          case 'readerDisplay':window.__readerDisplay=copy(p);out=copy(p);break;
          case 'settings':state.settings={...state.settings,...p.settings};out={settings:state.settings};break;
          case 'exportTxt':if(!book(p.bookId).complete)throw new Error('全話保存が完了していません');out={pending:true};setTimeout(()=>notify('exported',{bookId:p.bookId,success:true}),30);break;
          case 'exportSavedTxt':if(!book(p.bookId).downloaded)throw new Error('保存済みの話がありません');out={pending:true};setTimeout(()=>notify('exported',{bookId:p.bookId,success:true,partial:true}),30);break;
          case 'importTxt':out={pending:true};break;
          case 'openLogin':case 'openOriginal':break;
          case 'deleteBook':delete state.books[p.bookId];delete state.chapters[p.bookId];if(state.lastBookId===p.bookId)state.lastBookId='';break;
          default:throw new Error('未対応のMock操作: '+req.action);
        }
        if(opts.staleSpeechReplies&&req.action==='speechControl')out=oldSpeech;
        if(opts.staleChatgptReplies&&req.action.startsWith('speechChatgpt')&&req.action!=='speechChatgptUsage')out=oldChatgpt;
        persist();result(req.id,true,out);
      }catch(error){result(req.id,false,{code:error.code||'MOCK',message:error.message,url:error.url});}
    },opts.delayRangePreview&&req.action==='speechAnalysisRangePreview'&&req.payload.startNumber===5?450:5);
  }};
}
module.exports={initializeNativeMock};
