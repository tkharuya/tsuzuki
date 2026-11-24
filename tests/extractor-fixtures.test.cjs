/* Synthetic DOM fixtures only: all prose is original test text. */
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const {parseHTML}=require(process.env.NOVELSHELF_DOM_MODULE || 'linkedom');
const code=fs.readFileSync(process.env.NOVELSHELF_EXTRACTOR || require('path').resolve(__dirname, '../app/src/main/assets/extractor.js'),'utf8');
let tests=0;
function run(html,url,kind='book'){
 const {document}=parseHTML(html);
 return JSON.parse(vm.runInNewContext(code,{window:{location:{href:url},__TSUZUKI_REQUEST__:{kind,url}},document,URL},{timeout:2500}));
}
function check(name,fn){try{fn();tests++;console.log('PASS',name)}catch(e){console.error('FAIL',name,e.message);process.exitCode=1;}}
const kak='https://kakuyomu.jp/works/123';
const narou='https://ncode.syosetu.com/n1234ab/';
const shell=(body,title='テスト')=>`<!doctype html><html><head><title>${title}</title></head><body>${body}</body></html>`;
const state={
 'Work:123':{id:'123',__typename:'Work',title:'自作作品',author:{__ref:'User:1'},introduction:'自作のテスト文です。',serialStatus:'COMPLETED',publicEpisodeCount:2,tableOfContentsV2:[{__ref:'TOC:1'}]},
 'User:1':{activityName:'テスト作者'},
 'TOC:1':{__typename:'TableOfContentsChapter',chapter:{__ref:'Chapter:1'},episodeUnions:[{__ref:'Episode:456'},{__ref:'Episode:457'}]},
 'Chapter:1':{title:'第1章'},'Episode:456':{__typename:'Episode',id:'456',title:'はじめ'},'Episode:457':{__typename:'Episode',id:'457',title:'つづき'}
};
check('synthetic Apollo TOC keeps order and chapter labels',()=>{
 const r=run(shell('<script id="__NEXT_DATA__" type="application/json">'+JSON.stringify({props:{pageProps:{__APOLLO_STATE__:state}}})+'</script>'),kak);
 assert(!r.error);assert(r.tocComplete);assert(r.complete);assert.equal(r.author,'テスト作者');assert.deepEqual(r.chapters.map(x=>[x.index,x.section]),[[0,'第1章'],[1,'第1章']]);
});
check('ruby, indentation, blank lines and active HTML sanitization',()=>{
 const r=run(shell('<p id="contentMain-header-workTitle">自作作品</p><p class="widget-episodeTitle">はじめ</p><div class="widget-episodeBody"><p>　<ruby onclick="evil()">漢<rp>（</rp><rt>かん</rt><rp>）</rp></ruby>字&lt;安全&gt;<script>evil()</script><img src=x onerror="evil()"><span class="ad-slot">広告</span><a href="javascript:evil()">文章</a><br>次の行</p><p><br></p><div class="advertisement"><p>広告その2</p></div></div>'),kak+'/episodes/456','chapter');
 assert(!r.error);assert.equal(r.paragraphs.length,2);assert.equal(r.paragraphs[0].text,'　漢字<安全>文章\n次の行');assert.equal(r.paragraphs[1].text,'');assert.equal(r.paragraphs[0].html,'　<ruby>漢<rp>（</rp><rt>かん</rt><rp>）</rp></ruby>字&lt;安全&gt;文章\n次の行');
});
check('Narou new TOC detects more pages without pretending 100 is total',()=>{
 const r=run(shell('<h1 class="p-novel__title">自作作品</h1><div class="p-novel__author">作者：テスト作者</div><div class="p-novel__summary">概要</div><div class="p-eplist__chapter-title">章一</div><a class="p-eplist__subtitle" href="/n1234ab/1/">第1話</a><a class="p-eplist__subtitle" href="/n1234ab/100/">第100話</a><a class="c-pager__item--next" href="/n1234ab/?p=2">次へ</a>'),narou);
 assert(!r.error);assert.equal(r.author,'テスト作者');assert.equal(r.chapters[1].index,99);assert.equal(r.tocNextUrl,narou+'?p=2');assert.equal(r.total,0);assert(!r.tocComplete);
});
check('Narou last TOC page remains partial until merged',()=>{
 const r=run(shell('<h1 class="p-novel__title">自作作品</h1><a class="p-eplist__subtitle" href="/n1234ab/101/">第101話</a><a class="p-eplist__subtitle" href="/n1234ab/102/">第102話</a>'),narou+'?p=2');
 assert(!r.error);assert.equal(r.total,102);assert.equal(r.tocNextUrl,null);assert(!r.tocComplete);
});
check('Narou legacy TOC',()=>{
 const r=run(shell('<p class="novel_title">自作作品</p><div class="chapter_title">旧目次</div><dl class="novel_sublist2"><dd class="subtitle"><a href="/n1234ab/1/">はじめ</a></dd></dl><dl class="novel_sublist2"><dd class="subtitle"><a href="/n1234ab/2/">つづき</a></dd></dl>'),narou);
 assert(!r.error);assert.equal(r.chapters.length,2);assert(r.tocComplete);assert.equal(r.total,2);assert.equal(r.chapters[1].section,'旧目次');
});
check('Narou chapter separates preface/body/afterword and reads navigation',()=>{
 const r=run(shell('<a href="/n1234ab/">自作作品</a><h1 class="p-novel__title p-novel__title--rensai">第2話</h1><div class="p-novel__number">2/3</div><div class="js-novel-text p-novel__text p-novel__text--preface"><p>前書き</p></div><div class="js-novel-text p-novel__text"><p>本文です。</p></div><div class="js-novel-text p-novel__text p-novel__text--afterword"><p>後書き</p></div><a class="c-pager__item--prev" href="/n1234ab/1/">前へ</a><a class="c-pager__item--next" href="/n1234ab/3/">次へ</a>'),narou+'2/','chapter');
 assert(!r.error);assert.equal(r.bookTitle,'自作作品');assert.equal(r.index,1);assert.equal(r.paragraphs[0].text,'本文です。');assert.equal(r.preface[0].text,'前書き');assert.equal(r.afterword[0].text,'後書き');assert.equal(r.total,3);assert.equal(r.prevUrl,narou+'1/');assert.equal(r.nextUrl,narou+'3/');
});
check('Narou short story at work root',()=>{
 const r=run(shell('<h1 class="p-novel__title">自作短編</h1><div id="novel_honbun"><p>短い本文です。</p></div>'),narou,'book');
 assert(!r.error);assert.equal(r.kind,'chapter');assert(r.isShort);assert.equal(r.total,1);assert.equal(r.index,0);assert(r.complete);assert(r.tocComplete);assert.equal(r.bookUrl,narou);assert.equal(r.nextUrl,null);
});
check('rejects cross-work links and credential URLs',()=>{
 const r=run(shell('<p class="widget-episodeTitle">テスト話</p><div class="widget-episodeBody"><p>自作の本文です。</p></div><a id="contentMain-readNextEpisode" href="https://kakuyomu.jp/works/999/episodes/888">次のエピソード</a><a rel="prev" href="https://user:pw@kakuyomu.jp/works/123/episodes/455">前のエピソード</a>'),kak+'/episodes/456','chapter');
 assert(!r.error);assert.equal(r.nextUrl,null);assert.equal(r.prevUrl,null);
});
check('stops on challenges, authentication, unsupported hosts and HTTP',()=>{
 assert.equal(run(shell('<form id="challenge-form">確認</form>','Just a moment...'),narou).error.code,'ACCESS_LIMIT');
 assert.equal(run(shell('<input type="password">','ログイン'),'https://kakuyomu.jp/auth/login').error.code,'AUTH_REQUIRED');
 assert.equal(run(shell('テスト'),'https://kakuyomu.jp.evil.test/works/123').error.code,'UNSUPPORTED_URL');
 assert.equal(run(shell('テスト'),'http://kakuyomu.jp/works/123').error.code,'UNSUPPORTED_URL');
});
check('malformed Apollo JSON falls back without executing it',()=>{
 const r=run(shell('<h1>自作作品</h1><script id="__NEXT_DATA__">not valid JSON</script><a href="/works/123/episodes/456" class="WorkTocSection_link__test"><div class="WorkTocSection_title__test">自作の第1話</div></a>'),kak);
 assert(!r.error);assert.equal(r.chapters.length,1);assert.equal(r.chapters[0].title,'自作の第1話');assert(r.tocComplete);
});

// Wuxiaworld fixtures reproduce public DOM structure, with original prose.
const ww='https://www.wuxiaworld.com/novel/original-test-book';
const wwChapter=(n,extra={})=>({slug:'test-chapter-'+n,name:'Chapter '+n,offset:n,novelId:9,visible:true,...extra});
const wwNovel=(chapters=[],total=3)=>({id:9,slug:'original-test-book',name:'Original Test Book',
 authorName:{value:'Test Author'},synopsis:{value:'<p>An original test synopsis.</p>'},tags:['Completed'],
 chapterInfo:{firstChapter:wwChapter(1),chapterCount:{value:total},chapterGroups:[{title:'Test Section',chapterList:chapters}]}});
function wwState(novel,chapter,other=[]){return {queries:[
 {queryKey:['novel','original-test-book',null],state:{data:{item:novel}}},
 ...(chapter?[{queryKey:['chapter','original-test-book',chapter.slug,null],state:{data:{item:chapter}}}]:[]),...other]};}
function wwScript(data){return '<script>window.__REACT_QUERY_STATE__ = '+JSON.stringify(data)+'; window.__APP_CONTEXT__ = {"themeName":"dark"};</script>';}
function wwPage(inner,data){return shell('<h1>Original Test Book</h1>'+inner+(data?wwScript(data):''));}
function wwReading(title,body,tail=''){return '<h4 data-testid="heading"><span data-testid="title">'+title+'</span></h4><div class="chapter-content"><div class="fr-view chapter-body">'+body+'</div>'+tail+'</div>';}
check('Wuxiaworld initial metadata stays partial with real total and one first chapter',()=>{
 const r=run(wwPage('<a href="/novel/original-test-book/test-chapter-1">START READING</a>',wwState(wwNovel())),ww);
 assert(!r.error);assert.equal(r.source,'wuxiaworld');assert.equal(r.bookUrl,ww);assert.equal(r.bookTitle,'Original Test Book');assert.equal(r.author,'Test Author');
 assert.equal(r.total,3);assert.equal(r.chapters.length,1);assert.equal(r.chapters[0].index,0);assert.equal(r.tocComplete,false);assert.equal(r.more,true);assert.equal(r.tocNextUrl,null);assert(r.complete);
});
check('Wuxiaworld complete public metadata sorts offsets and preserves section',()=>{
 const n=wwNovel([wwChapter(3),wwChapter(1),wwChapter(2),wwChapter(4,{novelId:99})]);
 const r=run(wwPage('',wwState(n)),ww);assert(!r.error);assert(r.tocComplete);assert.equal(r.chapters.length,3);assert.deepEqual(r.chapters.map(x=>x.index),[0,1,2]);assert.equal(r.chapters[0].section,'Test Section');
});
check('Wuxiaworld sparse groups never become false adjacent chapter links',()=>{
 const r=run(wwPage('',wwState(wwNovel([wwChapter(1),wwChapter(3)]))),ww);
 assert(!r.error);assert.equal(r.chapters.length,1);assert.equal(r.tocComplete,false);
});
check('Wuxiaworld body uses only displayed DOM, strips controls and keeps ruby safely',()=>{
 const c=wwChapter(2,{content:{value:'EMBEDDED BODY MUST NOT BE READ'},novelInfo:{slug:'original-test-book',name:'Original Test Book'},
  relatedChapterInfo:{nextChapter:wwChapter(3),previousChapter:wwChapter(1)},pricingInfo:{isFree:true}});
 const html=wwPage(wwReading('Chapter 2','<p>Original <ruby onclick="bad()">reading<rt>sample</rt></ruby> test.</p><p><a href="javascript:bad()">Plain link text</a><script>bad()</script><span class="ads">ADVERTISEMENT</span></p>',
  '<blockquote><p>Translator note is outside the body.</p></blockquote><button>LOG IN</button>')+'<section><p>COMMENT SHOULD NOT BE READ</p></section>',wwState(wwNovel(),c));
 const r=run(html,ww+'/test-chapter-2','chapter');assert(!r.error);assert.equal(r.index,1);assert.equal(r.total,3);assert.equal(r.author,'Test Author');
 assert.equal(r.paragraphs.length,2);assert.equal(r.paragraphs[0].text,'Original reading test.');assert(r.paragraphs[0].html.includes('<rt>sample</rt>'));assert(!r.paragraphs[0].html.includes('onclick'));
 assert.equal(r.paragraphs[1].text,'Plain link text');assert.equal(r.nextUrl,ww+'/test-chapter-3');assert.equal(r.prevUrl,ww+'/test-chapter-1');
});
check('Wuxiaworld DOM navigation is same-work and wins over snapshot navigation',()=>{
 const c=wwChapter(2,{novelInfo:{slug:'original-test-book'},relatedChapterInfo:{nextChapter:wwChapter(3)}});
 const r=run(wwPage(wwReading('Chapter 2','<p>Original paragraph.</p>')+
  '<a href="/novel/other-book/other-chapter">NEXT CHAPTER</a><a href="/novel/original-test-book/current-next">NEXT CHAPTER</a><a href="https://user@www.wuxiaworld.com/novel/original-test-book/test-chapter-1">PREVIOUS CHAPTER</a>',wwState(wwNovel(),c)),ww+'/test-chapter-2','chapter');
 assert(!r.error);assert.equal(r.nextUrl,ww+'/current-next');assert.equal(r.prevUrl,null);
});
check('Wuxiaworld locked teaser DOM produces no saved paragraphs',()=>{
 const c=wwChapter(2,{isTeaser:true,karmaInfo:{isKarmaRequired:true}});
 const h='<h4 data-testid="heading"><svg data-testid="lock-icon"></svg><span data-testid="title">Chapter 2 (Teaser)</span></h4>'+
  '<div class="chapter-content"><div class="chapter-body"><p>Original teaser only.</p><div class="absolute bottom-0" style="background:linear-gradient(#fff,#000)"></div></div></div>';
 const r=run(wwPage(h,wwState(wwNovel(),c)),ww+'/test-chapter-2','chapter');assert.equal(r.error.code,'CHAPTER_LOCKED');assert.deepEqual(r.paragraphs,[]);
});
check('Wuxiaworld current unlocked DOM can supersede a stale SSR teaser snapshot',()=>{
 const c=wwChapter(2,{isTeaser:true,karmaInfo:{isKarmaRequired:true},pricingInfo:{isFree:false}});
 const r=run(wwPage(wwReading('Chapter 2','<p>Original authorised visible text.</p>'),wwState(wwNovel(),c)),ww+'/test-chapter-2','chapter');
 assert(!r.error);assert.equal(r.paragraphs[0].text,'Original authorised visible text.');
});
check('Wuxiaworld content overlay alone still blocks a stale unlocked snapshot',()=>{
 const h=wwReading('Chapter 2','<p>Original teaser.</p><div class="absolute" style="background:linear-gradient(#fff,#000)"></div>');
 const r=run(wwPage(h,wwState(wwNovel(),wwChapter(2,{isTeaser:false}))),ww+'/test-chapter-2','chapter');assert.equal(r.error.code,'CHAPTER_LOCKED');assert.equal(r.paragraphs.length,0);
});
check('Wuxiaworld gates and comments cannot become the chapter body',()=>{
 const h='<h4 data-testid="heading"><span data-testid="title">Chapter 2</span></h4><div class="chapter-content"><h2>Log in to continue your adventure</h2><button>LOG IN</button><p>Access instructions.</p></div><div><p>A user comment.</p></div>';
 const r=run(wwPage(h,wwState(wwNovel(),wwChapter(2))),ww+'/test-chapter-2','chapter');assert.equal(r.error.code,'CHAPTER_LOCKED');assert.equal(r.paragraphs.length,0);
 const absent=run(wwPage('<div class="chapter-content"><p>Generic text.</p></div>',wwState(wwNovel(),wwChapter(2))),ww+'/test-chapter-2','chapter');assert.equal(absent.error.code,'NO_CONTENT');
});
check('Wuxiaworld hidden content and snapshot-only body are rejected',()=>{
 const c=wwChapter(2,{content:{value:'EMBEDDED BODY MUST NOT BE READ'}});
 const r=run(wwPage('<div hidden>'+wwReading('Chapter 2','<p>Hidden original text.</p>')+'</div>',wwState(wwNovel(),c)),ww+'/test-chapter-2','chapter');assert.equal(r.error.code,'NO_CONTENT');
 const noDOM=run(wwPage('',wwState(wwNovel(),c)),ww+'/test-chapter-2','chapter');assert.equal(noDOM.error.code,'NO_CONTENT');
});
check('Wuxiaworld schema fallback and malformed assignment never execute script',()=>{
 const h='<h1>Original Test Book</h1><span>3 Chapters</span><a href="/novel/original-test-book/test-chapter-1">START READING</a>'+
  '<script>window.__REACT_QUERY_STATE__ = {bad: process.exit()};</script>'+
  '<script type="application/ld+json">'+JSON.stringify({'@type':'Book',url:ww,name:'Original Test Book',author:{name:'Schema Author'}})+'</script>';
 const r=run(shell(h),ww);assert(!r.error);assert.equal(r.author,'Schema Author');assert.equal(r.total,3);assert.equal(r.chapters.length,1);assert(!r.tocComplete);
});
check('Wuxiaworld strict path and host normalization rejects lookalikes and percent slashes',()=>{
 const html=wwPage('',wwState(wwNovel()));
 assert.equal(run(html,'https://wuxiaworld.com/novel/original-test-book/').bookUrl,ww);
 for(const url of ['https://www.wuxiaworld.com.evil.test/novel/original-test-book','http://www.wuxiaworld.com/novel/original-test-book','https://www.wuxiaworld.com/novel/original-test-book/test%2fchapter','https://www.wuxiaworld.com/novel/original-test-book/a/b'])assert.equal(run(html,url).error.code,'UNSUPPORTED_URL');
});
console.log('Passed '+tests+' checks.');
