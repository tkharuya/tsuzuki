/*
 * Tsuzuki page adapter. Evaluated inside the site's WebView; no requests,
 * credentials, clicks, or page mutations are made by this file.
 * Input: window.__TSUZUKI_REQUEST__ = {kind: 'book'|'chapter'|'search', url}.
 * Output: JSON string. Episode indices are zero-based. tocComplete describes
 * the TOC, while complete describes whether publication has finished.
 */
(function () {
  'use strict';

  var request = window.__TSUZUKI_REQUEST__ || {};
  var result = {kind: request.kind || 'book', source: '', url: '', title: '', author: '',
    description: '', bookUrl: '', bookTitle: '', chapters: [], items: [], total: 0,
    complete: false, tocComplete: false, tocNextUrl: null, more: false,
    nextUrl: null, prevUrl: null, paragraphs: [], preface: [], afterword: []};
  var allowed = {'kakuyomu.jp': true, 'ncode.syosetu.com': true,
    'yomou.syosetu.com': true, 'syosetu.com': true,
    'www.wuxiaworld.com': true, 'wuxiaworld.com': true};
  var here;

  function clean(value) { return String(value == null ? '' : value).replace(/\s+/g, ' ').trim(); }
  function q(selector, root) { return (root || document).querySelector(selector); }
  function qa(selector, root) { return Array.prototype.slice.call((root || document).querySelectorAll(selector)); }
  function content(selector, root) { var node = q(selector, root); return node ? clean(node.textContent) : ''; }
  function meta(name) { var node = q('meta[property="' + name + '"],meta[name="' + name + '"]'); return node ? clean(node.getAttribute('content')) : ''; }
  function number(value) { var n = Number(value); return Number.isFinite(n) && n >= 0 ? Math.floor(n) : 0; }
  function fail(code, message) { result.error = {code: code, message: message}; return result; }
  function urlOf(value, base) {
    try {
      var u = new URL(String(value || ''), base || here.href);
      if (u.protocol !== 'https:' || !allowed[u.hostname] || u.username || u.password || (u.port && u.port !== '443')) return null;
      u.hash = '';
      return u;
    } catch (_) { return null; }
  }
  function workAddress(value) {
    var u = urlOf(value); if (!u) return null;
    var m;
    if (u.hostname === 'kakuyomu.jp' && (m = u.pathname.match(/^\/works\/(\d{1,25})(?:\/episodes\/(\d{1,25}))?\/?$/))) {
      return {source: 'kakuyomu', id: m[1], episode: m[2] || '',
        bookUrl: 'https://kakuyomu.jp/works/' + m[1],
        url: 'https://kakuyomu.jp/works/' + m[1] + (m[2] ? '/episodes/' + m[2] : '')};
    }
    if (u.hostname === 'ncode.syosetu.com' && (m = u.pathname.match(/^\/(n\d{1,8}[a-z]{1,4})(?:\/(\d+))?\/?$/i))) {
      if (m[2] && Number(m[2]) < 1) return null;
      var root = 'https://ncode.syosetu.com/' + m[1].toLowerCase() + '/';
      return {source: 'narou', id: m[1].toLowerCase(), episode: m[2] || '',
        bookUrl: root, url: root + (m[2] ? Number(m[2]) + '/' : '')};
    }
    if ((u.hostname === 'www.wuxiaworld.com' || u.hostname === 'wuxiaworld.com') &&
        (m = u.pathname.match(/^\/novel\/([a-z0-9][a-z0-9-]{0,199})(?:\/([a-z0-9][a-z0-9-]{0,199}))?\/?$/))) {
      var wuxiaRoot = 'https://www.wuxiaworld.com/novel/' + m[1];
      return {source: 'wuxiaworld', id: m[1], episode: m[2] || '', bookUrl: wuxiaRoot,
        url: wuxiaRoot + (m[2] ? '/' + m[2] : '')};
    }
    return null;
  }
  function episodeLink(value, book) {
    var address = workAddress(value);
    return address && address.episode && address.bookUrl === book.bookUrl && address.url !== book.url ? address.url : null;
  }
  function pageLink(value, book) {
    var u = urlOf(value), address = u && workAddress(u.href);
    if (!address || address.episode || address.bookUrl !== book.bookUrl) return null;
    var page = number(u.searchParams.get('p'));
    if (!page || page <= (number(here.searchParams.get('p')) || 1)) return null;
    return address.bookUrl + '?p=' + page;
  }
  function findEpisodeLink(selectors, label, book) {
    var list = qa(selectors);
    for (var i = 0; i < list.length; i++) {
      var found = episodeLink(list[i].getAttribute('href'), book); if (found) return found;
    }
    list = qa('a[href]');
    for (var j = 0; j < list.length; j++) {
      if (label.test(clean(list[j].textContent))) {
        var next = episodeLink(list[j].getAttribute('href'), book); if (next) return next;
      }
    }
    return null;
  }

  // Rebuild safe text/ruby rather than returning the site's HTML. Both the
  // reader and TXT exporter therefore receive no images, links, events or ads.
  var rejectedTags = /^(SCRIPT|STYLE|SVG|MATH|IFRAME|OBJECT|EMBED|IMG|PICTURE|VIDEO|AUDIO|SOURCE|NOSCRIPT|TEMPLATE|FORM|BUTTON|INPUT|TEXTAREA|SELECT|OPTION|CANVAS)$/;
  function excluded(node) {
    if (!node || node.nodeType !== 1) return false;
    if (rejectedTags.test(node.tagName)) return true;
    if (node.hasAttribute('hidden') || node.getAttribute('aria-hidden') === 'true') return true;
    if (/display\s*:\s*none|visibility\s*:\s*hidden/i.test(node.getAttribute('style') || '')) return true;
    var labels = String(node.className || '') + ' ' + (node.id || '');
    return /(?:^|\s)(?:ad|ads|advert|advertisement|adsbygoogle)(?:$|[\s_-])/i.test(labels) ||
      node.hasAttribute('data-ad-slot') || node.getAttribute('role') === 'advertisement';
  }
  function escapeText(value) { return String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;'); }
  function serialize(node) {
    if (node.nodeType === 3) return {text: node.nodeValue || '', html: escapeText(node.nodeValue || '')};
    if (node.nodeType !== 1 || excluded(node)) return {text: '', html: ''};
    if (node.tagName === 'BR') return {text: '\n', html: '\n'};
    var text = '', html = '';
    Array.prototype.forEach.call(node.childNodes, function (child) {
      var part = serialize(child); text += part.text; html += part.html;
    });
    if (node.tagName === 'RT' || node.tagName === 'RP') text = '';
    if (/^(RUBY|RT|RP)$/.test(node.tagName)) {
      var tag = node.tagName.toLowerCase(); html = '<' + tag + '>' + html + '</' + tag + '>';
    }
    return {text: text, html: html};
  }
  function trimLayout(value) { return value.replace(/\r\n?/g, '\n').replace(/^[\t\n\r ]+|[\t\n\r ]+$/g, ''); }
  function paragraphs(root) {
    if (!root) return [];
    var nodes = qa('p', root).filter(function (node) {
      for (var parent = node; parent && parent !== root; parent = parent.parentElement) {
        if (excluded(parent)) return false;
        if (parent !== node && parent.tagName === 'P') return false;
      }
      return true;
    });
    if (!nodes.length) nodes = [root];
    return nodes.map(function (node) {
      var value = serialize(node);
      return {text: trimLayout(value.text), html: trimLayout(value.html)};
    });
  }
  function hasText(parts) { return parts.some(function (part) { return part.text.trim().length > 0; }); }

  function apolloState() {
    var script = q('script#__NEXT_DATA__');
    if (!script || script.textContent.length > 12000000) return {};
    try {
      var json = JSON.parse(script.textContent);
      return json && json.props && json.props.pageProps && json.props.pageProps.__APOLLO_STATE__ || {};
    } catch (_) { return {}; }
  }
  function resolver(state) {
    return function resolve(value) {
      var visited = {}, count = 0;
      while (value && typeof value === 'object' && typeof value.__ref === 'string' && count++ < 12) {
        if (visited[value.__ref]) return null;
        visited[value.__ref] = true; value = state[value.__ref];
      }
      return value || null;
    };
  }
  function kakuyomuMetadata(work, resolve) {
    var author = resolve(work.author) || {};
    return {title: clean(work.title || work.alternateTitle),
      author: clean(work.alternateAuthorName || author.activityName || author.name || author.screenName),
      description: String(work.introduction || work.catchphrase || '').trim(),
      total: number(work.publicEpisodeCount), complete: /^(COMPLETED|FINISHED)$/.test(work.serialStatus || '')};
  }
  function fillMetadata(data) {
    result.title = data.title || result.title; result.bookTitle = data.title || result.bookTitle;
    result.author = data.author || result.author; result.description = data.description || result.description;
    result.total = data.total || result.total; result.complete = !!data.complete;
  }
  function kakuyomuBook(book, state) {
    var resolve = resolver(state), work = resolve(state['Work:' + book.id]) || {};
    fillMetadata(kakuyomuMetadata(work, resolve));
    if (!result.title) result.title = content('#workTitle, h1 a[title], h1');
    if (!result.title) result.title = meta('og:title').replace(/（[^（）]*）\s*-\s*カクヨム$/, '').replace(/\s*-\s*カクヨム$/, '');
    if (!result.author) result.author = content('#workAuthor-activityName, [class*="WorkAuthorBox"] a[href^="/users/"], .widget-author a');
    if (!result.description) result.description = content('#introduction, [class*="WorkIntroduction"]') || meta('description');
    result.bookTitle = result.title;
    var chapters = [], seen = {}, seenRefs = {};
    function append(id, title, section) {
      if (!/^\d{1,25}$/.test(String(id || ''))) return;
      var url = book.bookUrl + '/episodes/' + id;
      if (seen[url]) return; seen[url] = true;
      chapters.push({url: url, title: clean(title) || '第' + (chapters.length + 1) + '話', index: chapters.length, section: section || ''});
    }
    function walk(value, section, depth) {
      if (!value || depth > 15) return;
      if (Array.isArray(value)) { value.forEach(function (v) { walk(v, section, depth + 1); }); return; }
      var ref = value.__ref;
      if (ref) { if (seenRefs[ref]) return; seenRefs[ref] = true; }
      var node = resolve(value); if (!node || typeof node !== 'object') return;
      if (node.__typename === 'Episode' || (ref && /^Episode:/.test(ref))) { append(node.id || ref.slice(8), node.title, section); return; }
      var heading = resolve(node.chapter);
      if (heading) section = clean(heading.title) || section;
      if (/^Chapter$/.test(node.__typename || '')) section = clean(node.title) || section;
      ['episodeUnions', 'episodes', 'children', 'sections', 'chapters', 'nodes'].forEach(function (key) {
        if (node[key]) walk(node[key], section, depth + 1);
      });
    }
    var toc = work.tableOfContentsV2 || work.tableOfContents;
    if (toc) walk(toc, '', 0);
    if (!chapters.length && number(work.publicEpisodeCount) === 1) walk(work.firstPublicEpisodeUnion || work.firstPublicEpisode, '', 0);
    if (!chapters.length) {
      var section = '';
      qa('.widget-toc-chapterTitle, [class*="WorkTocSection"] h3, a[href]').forEach(function (node) {
        if (node.tagName !== 'A') { section = clean(node.textContent); return; }
        var address = workAddress(node.getAttribute('href'));
        if (!address || address.bookUrl !== book.bookUrl || !address.episode) return;
        var title = content('[class*="WorkTocSection_title"], .widget-toc-episode-titleLabel', node) || clean(node.textContent);
        if (/^(?:1話目から読む|第1話から読む|続きを読む|続きから読む)$/.test(title)) return;
        append(address.episode, title, section);
      });
    }
    result.chapters = chapters;
    if (!result.total) {
      var match = clean(document.body && document.body.textContent).match(/全\s*([\d,]+)\s*話/);
      if (match) result.total = number(match[1].replace(/,/g, ''));
    }
    var collapsed = qa('button').some(function (node) { return /つづきを表示|続きを表示/.test(clean(node.textContent)); });
    result.tocComplete = result.total > 0 ? chapters.length === result.total : chapters.length > 0 && !collapsed;
    if (!result.total && result.tocComplete) result.total = chapters.length;
    result.more = !result.tocComplete;
    if (!chapters.length) return fail('NO_EPISODES', '公開エピソードの目次を取得できませんでした。公式ページを開いて確認してください。');
    return result;
  }
  function kakuyomuChapter(book, root) {
    result.kind = 'chapter';
    result.title = content('.widget-episodeTitle, .contentMain-episodeTitle, #episodeTitle');
    result.bookTitle = content('#contentMain-header-workTitle, .contentMain-workTitle, #workTitle');
    result.author = content('#contentMain-header-author, .contentMain-author, #workAuthor-activityName').replace(/^作者[：:]\s*/, '');
    result.paragraphs = paragraphs(root);
    result.nextUrl = findEpisodeLink('#contentMain-readNextEpisode, .js-read-next-episode, a[rel="next"], link[rel="next"], .widget-episodeNavigation-next a', /^(?:次のエピソード|次の話|次話|次へ)/, book);
    result.prevUrl = findEpisodeLink('#contentMain-readPreviousEpisode, #contentMain-readPrevEpisode, .js-read-previous-episode, a[rel="prev"], link[rel="prev"], .widget-episodeNavigation-previous a', /^(?:前のエピソード|前の話|前話|前へ)/, book);
    if (!result.title) result.title = meta('og:title').split(' - ')[0] || clean(document.title);
    if (!hasText(result.paragraphs)) return fail('NO_CONTENT', '本文が見つかりません。ログイン・公開状態を公式ページで確認してください。');
    return result;
  }
  function searchNext(source) {
    var next = null;
    qa('a[rel="next"], a[aria-label], a[href]').some(function (node) {
      if (node.getAttribute('rel') !== 'next' && !/^(?:次|次へ|次のページ|Next)(?:\s|[›»>→]|$)/i.test(clean(node.getAttribute('aria-label') || node.textContent))) return false;
      var u = urlOf(node.getAttribute('href'));
      if (!u || u.hostname !== here.hostname || u.pathname !== here.pathname) return false;
      var key = source === 'kakuyomu' ? 'page' : 'p';
      if (number(u.searchParams.get(key)) <= (number(here.searchParams.get(key)) || 1)) return false;
      next = u.href; return true;
    });
    return next;
  }
  function searchPage(source, state) {
    result.kind = 'search'; result.title = '検索結果';
    var resolve = resolver(state), connection = null, rootQuery = state.ROOT_QUERY || {};
    if (source === 'kakuyomu') {
      Object.keys(rootQuery).some(function (key) {
        if (/^searchWorks(?:\(|$)/.test(key)) { connection = resolve(rootQuery[key]); return true; }
        return false;
      });
    }
    if (connection && Array.isArray(connection.nodes)) {
      result.items = connection.nodes.map(function (ref) {
        var work = resolve(ref) || {}; if (!/^\d{1,25}$/.test(String(work.id || ''))) return null;
        var item = kakuyomuMetadata(work, resolve); item.url = 'https://kakuyomu.jp/works/' + work.id; item.source = source; return item;
      }).filter(Boolean);
      result.total = number(connection.totalCount);
      var pageInfo = resolve(connection.pageInfo) || {};
      result.more = pageInfo.hasNextPage === true;
      if (result.more) { var next = new URL(here.href); next.searchParams.set('page', (number(here.searchParams.get('page')) || 1) + 1); result.nextUrl = next.href; }
      return result;
    }
    var seen = {};
    qa('a[href]').forEach(function (node) {
      var work = workAddress(node.getAttribute('href'));
      if (!work || work.source !== source || work.episode || seen[work.bookUrl]) return;
      var title = clean(node.getAttribute('title') || node.textContent);
      if (!title || /^(?:続きを読む|1話目から読む|作品情報|目次|読む)$/.test(title)) return;
      seen[work.bookUrl] = true;
      var box = node.closest('[class*="WorkListItem"], [class*="WorkBox_workBox"], .searchkekka_box, .p-novel, article, li') || node.parentElement;
      result.items.push({url: work.bookUrl, title: title,
        author: content('[class*="WorkTitle_workLabelAuthor"] a, a[href^="/users/"], .p-novel__author, .novel_writername, a[href*="mypage.syosetu.com"]', box).replace(/^作者[：:]\s*/, ''),
        description: content('[class*="introduction"], [class*="Introduction"], .ex, .p-novel__summary', box), source: source});
    });
    var match = clean(document.body && document.body.textContent).match(/(?:全|検索結果\s*)\s*([\d,]+)\s*(?:件|作品)/);
    result.total = match ? number(match[1].replace(/,/g, '')) : result.items.length;
    result.nextUrl = searchNext(source); result.more = !!result.nextUrl;
    if (!result.items.length && !/見つかりません|該当する.*(?:ありません|見つか)|0\s*件|検索結果はありません/.test(clean(document.body && document.body.textContent))) {
      return fail('SEARCH_UNAVAILABLE', '検索結果を取得できませんでした。公式検索画面を開いてください。');
    }
    return result;
  }
  function narouTitle(book, episodeTitle) {
    var candidates = qa('a[href]').filter(function (node) {
      var address = workAddress(node.getAttribute('href'));
      var text = clean(node.textContent);
      return address && !address.episode && address.bookUrl === book.bookUrl && text &&
        !/^(?:目次|最初|前へ|次へ|最後|ページ|戻る|作品情報|全て|↑|↓|＜|＞|<<|>>)/.test(text);
    });
    if (candidates.length) return clean(candidates[0].textContent);
    var title = meta('og:title') || clean(document.title);
    if (episodeTitle && title.endsWith(' - ' + episodeTitle)) title = title.slice(0, -(episodeTitle.length + 3));
    return title.replace(/\s*[-|]\s*小説家になろう$/, '');
  }
  function narouChapter(book, root) {
    result.kind = 'chapter';
    result.title = content('.p-novel__title--rensai, .novel_subtitle, .p-novel__title, .novel_title');
    result.bookTitle = book.episode ? narouTitle(book, result.title) : result.title;
    result.author = content('.p-novel__author, .novel_writername').replace(/^作者[：:]\s*/, '');
    result.paragraphs = paragraphs(root);
    result.preface = paragraphs(q('.p-novel__text--preface, #novel_p'));
    result.afterword = paragraphs(q('.p-novel__text--afterword, #novel_a'));
    result.index = book.episode ? Number(book.episode) - 1 : 0;
    if (book.episode) {
      result.nextUrl = findEpisodeLink('a.c-pager__item--next, a.novelview_pager-next, a[rel="next"], link[rel="next"]', /^(?:次へ|次の話|次のエピソード|次話|次のページ|[>＞]{1,2}\s*次)/, book);
      result.prevUrl = findEpisodeLink('a.c-pager__item--prev, a.novelview_pager-prev, a[rel="prev"], link[rel="prev"]', /^(?:前へ|前の話|前のエピソード|前話|前のページ|[<＜]{1,2}\s*前)/, book);
      var numberText = content('.p-novel__number, #novel_no');
      var count = numberText.match(/[\d,]+\s*\/\s*([\d,]+)/);
      if (count) result.total = number(count[1].replace(/,/g, ''));
    } else {
      result.total = 1; result.complete = true; result.tocComplete = true; result.isShort = true;
      result.chapters = [{url: book.bookUrl, title: result.title, index: 0, section: ''}];
    }
    if (!hasText(result.paragraphs)) return fail('NO_CONTENT', '本文が見つかりません。公開状態を公式ページで確認してください。');
    return result;
  }
  function narouBook(book) {
    result.title = content('.p-novel__title, .novel_title') || meta('og:title').replace(/\s*-\s*小説家になろう$/, '');
    result.bookTitle = result.title;
    result.author = content('.p-novel__author, .novel_writername').replace(/^作者[：:]\s*/, '');
    result.description = content('.p-novel__summary, #novel_ex');
    var section = '', seen = {};
    qa('.p-eplist__chapter-title, .chapter_title, a.p-eplist__subtitle, .novel_sublist2 .subtitle a, dd.subtitle a').forEach(function (node) {
      if (node.tagName !== 'A') { section = clean(node.textContent); return; }
      var address = workAddress(node.getAttribute('href'));
      if (!address || address.bookUrl !== book.bookUrl || !address.episode || seen[address.url]) return;
      seen[address.url] = true;
      result.chapters.push({url: address.url, title: clean(node.textContent), index: Number(address.episode) - 1, section: section});
    });
    qa('a.c-pager__item--next, a.novelview_pager-next, a[rel="next"]').some(function (node) {
      result.tocNextUrl = pageLink(node.getAttribute('href'), book); return !!result.tocNextUrl;
    });
    if (!result.tocNextUrl) qa('a[href]').some(function (node) {
      if (!/^(?:次へ|次のページ|次の100話)/.test(clean(node.textContent))) return false;
      result.tocNextUrl = pageLink(node.getAttribute('href'), book); return !!result.tocNextUrl;
    });
    var status = content('.p-novel__type, .p-infotop-type, .noveltype, .p-novel__status');
    result.complete = /完結/.test(status);
    var count = status.match(/(?:全\s*)?([\d,]+)\s*(?:エピソード|話)/);
    if (count) result.total = number(count[1].replace(/,/g, ''));
    var maxIndex = result.chapters.reduce(function (max, chapter) { return Math.max(max, chapter.index); }, -1);
    if (!result.total && !result.tocNextUrl) result.total = maxIndex + 1;
    result.tocComplete = !result.tocNextUrl && result.chapters.length > 0 && result.chapters[0].index === 0 && result.chapters.length === result.total;
    result.more = !!result.tocNextUrl;
    if (!result.chapters.length) return fail('NO_EPISODES', '目次を取得できませんでした。公式ページを開いて確認してください。');
    return result;
  }

  // Wuxiaworld uses a server-rendered React Query snapshot. Read public
  // metadata only; its content/paragraphs fields are never a body fallback.
  // A partial TOC is normal: chapterGroups[].chapterList is empty until the
  // official UI loads a group. Never synthesize chapter URLs or call its API.
  function wuxiaworldState() {
    var value = window.__REACT_QUERY_STATE__;
    if (value && typeof value === 'object' && Array.isArray(value.queries)) return value;
    var scripts = qa('script:not([src])');
    for (var i = 0; i < scripts.length; i++) {
      var text = scripts[i].textContent || '';
      if (text.length > 12000000) continue;
      var start = /(?:^|[;\s])window\.__REACT_QUERY_STATE__\s*=\s*(\{)/.exec(text);
      if (!start) continue;
      var begin = start.index + start[0].length - 1, depth = 0, quoted = false, escaped = false;
      for (var j = begin; j < text.length; j++) {
        var ch = text[j];
        if (quoted) {
          if (escaped) escaped = false;
          else if (ch === '\\') escaped = true;
          else if (ch === '"') quoted = false;
          continue;
        }
        if (ch === '"') quoted = true;
        else if (ch === '{' || ch === '[') depth++;
        else if (ch === '}' || ch === ']') {
          depth--;
          if (depth === 0) {
            try { var parsed = JSON.parse(text.slice(begin, j + 1)); if (Array.isArray(parsed.queries)) return parsed; }
            catch (_) { /* Invalid data is ignored, never executed. */ }
            break;
          }
        }
      }
    }
    return {};
  }
  function wuxiaworldData(book) {
    var state = wuxiaworldState(), data = {novel: null, chapter: null, groups: [], article: null, schemaBook: null};
    (state.queries || []).forEach(function (entry) {
      var key = entry && entry.queryKey, payload = entry && entry.state && entry.state.data;
      if (!Array.isArray(key) || key[1] !== book.id || !payload) return;
      if (key[0] === 'novel' && payload.item && payload.item.slug === book.id) data.novel = payload.item;
      if (key[0] === 'chapter' && key[2] === book.episode && payload.item && payload.item.slug === book.episode) {
        var novelSlug = payload.item.novelInfo && payload.item.novelInfo.slug;
        if (!novelSlug || novelSlug === book.id) data.chapter = payload.item;
      }
      if (key[0] === 'chapterGroups' && Array.isArray(payload.items)) data.groups = data.groups.concat(payload.items);
    });
    qa('script[type="application/ld+json"]').forEach(function (node) {
      if ((node.textContent || '').length > 1000000) return;
      try {
        var parsed = JSON.parse(node.textContent);
        var entries = Array.isArray(parsed) ? parsed : parsed['@graph'] || [parsed];
        entries.forEach(function (item) {
          if (!item || typeof item !== 'object') return;
          var address = workAddress(item.url || item.mainEntityOfPage);
          if (!address || address.bookUrl !== book.bookUrl) return;
          if (item['@type'] === 'Book' && !address.episode) data.schemaBook = item;
          if (item['@type'] === 'Article' && address.url === book.url) data.article = item;
        });
      } catch (_) { /* Metadata must be JSON, not executable script. */ }
    });
    return data;
  }
  function markupText(value) {
    if (typeof value !== 'string') return '';
    var template = document.createElement('template');
    template.innerHTML = value;
    var root = template.content || template, parts = [];
    Array.prototype.forEach.call(root.childNodes, function (node) { parts.push(serialize(node).text); });
    return clean(parts.join(' '));
  }
  function wuxiaworldMeta(book, data) {
    var novel = data.novel || {}, info = novel.chapterInfo || {}, schema = data.schemaBook || data.article || {};
    var chapterNovel = data.chapter && data.chapter.novelInfo || {};
    var name = novel.name || chapterNovel.name || (data.schemaBook && data.schemaBook.name);
    if (!name) qa('a[href]').some(function (node) {
      var address = workAddress(node.getAttribute('href'));
      if (address && address.bookUrl === book.bookUrl && !address.episode && clean(node.textContent)) {
        name = clean(node.textContent); return true;
      }
      return false;
    });
    if (!name && !book.episode) name = content('h1') || meta('og:title').replace(/\s*\|\s*Wuxiaworld\s*$/, '');
    var author = novel.authorName && novel.authorName.value;
    if (!author && schema.author) {
      var authors = Array.isArray(schema.author) ? schema.author : [schema.author];
      author = authors.map(function (a) { return typeof a === 'string' ? a : a && a.name || ''; }).filter(Boolean).join(', ');
    }
    result.bookTitle = clean(name); result.author = clean(author);
    result.description = markupText(novel.synopsis && novel.synopsis.value || data.schemaBook && data.schemaBook.description || '');
    result.total = number(info.chapterCount && info.chapterCount.value);
    if (!result.total && !book.episode) {
      // This label is public metadata on the work cover, outside the chapter body.
      qa('span, p, div').some(function (node) {
        var count = clean(node.textContent).match(/^([\d,]+)\s+Chapters$/i);
        if (!count) return false;
        result.total = number(count[1].replace(/,/g, '')); return true;
      });
    }
    result.complete = Array.isArray(novel.tags) && novel.tags.indexOf('Completed') >= 0;
  }
  function wuxiaworldToc(book, data) {
    var novel = data.novel || {}, info = novel.chapterInfo || {}, seen = {}, chapters = [];
    function append(chapter, section) {
      if (!chapter || !/^[a-z0-9][a-z0-9-]{0,199}$/.test(chapter.slug || '') || chapter.visible === false ||
          chapter.sponsorInfo && chapter.sponsorInfo.advanceChapter === true ||
          chapter.novelId != null && novel.id != null && chapter.novelId !== novel.id) return;
      var offset = number(chapter.offset);
      if (!offset || result.total && offset > result.total) return;
      var url = book.bookUrl + '/' + chapter.slug;
      if (seen[url]) return;
      seen[url] = true;
      chapters.push({url: url, title: clean(chapter.name) || 'Chapter ' + offset, index: offset - 1, section: clean(section)});
    }
    (info.chapterGroups || []).concat(data.groups).forEach(function (group) {
      (group.chapterList || []).forEach(function (chapter) { append(chapter, group.title); });
    });
    append(info.firstChapter, '');
    chapters.sort(function (a, b) { return a.index - b.index; });
    var continuous = [], expected = 0;
    for (var i = 0; i < chapters.length; i++) {
      if (chapters[i].index !== expected) break;
      continuous.push(chapters[i]); expected++;
    }
    // Do not expose a sparse TOC as adjacent chapters: Native may use it for navigation.
    return {chapters: continuous, complete: result.total > 0 && continuous.length === result.total};
  }
  function wuxiaworldBook(book, data) {
    result.kind = 'book'; wuxiaworldMeta(book, data); result.title = result.bookTitle;
    var toc = wuxiaworldToc(book, data); result.chapters = toc.chapters;
    result.tocComplete = toc.complete;
    if (!result.chapters.length) qa('a[href]').some(function (node) {
      if (!/^START READING$/i.test(clean(node.textContent))) return false;
      var url = episodeLink(node.getAttribute('href'), book);
      if (!url) return false;
      result.chapters = [{url: url, title: 'Chapter 1', index: 0, section: ''}]; return true;
    });
    result.more = !result.tocComplete;
    if (!result.chapters.length) return fail('NO_EPISODES', '目次を取得できません。Wuxiaworldの公式画面で章を開き、表示中のページを取り込んでください。');
    return result;
  }
  function wuxiaworldVisible(node) {
    for (var ancestor = node; ancestor && ancestor.nodeType === 1; ancestor = ancestor.parentElement) {
      if (excluded(ancestor)) return false;
      if (typeof window.getComputedStyle === 'function') {
        var style = window.getComputedStyle(ancestor);
        if (style && (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse')) return false;
      }
    }
    return !!node;
  }
  function wuxiaworldChapter(book, data) {
    result.kind = 'chapter'; wuxiaworldMeta(book, data);
    var chapter = data.chapter || {}, scope = q('.chapter-content');
    var root = scope && q('.chapter-body', scope), heading = q('h4[data-testid="heading"]');
    var titleNode = heading && q('[data-testid="title"]', heading);
    result.title = clean(titleNode ? titleNode.textContent : heading && heading.textContent) || clean(chapter.name);
    if (!result.title && data.article) result.title = markupText(data.article.headline).replace(result.bookTitle + ' - ', '');
    if (number(chapter.offset)) result.index = number(chapter.offset) - 1;
    // The site's initial snapshot can still say "teaser" after an authorised
    // unlock in its live UI. A current titled heading without its lock marker,
    // teaser suffix or content overlay is the stronger live-DOM signal.
    var liveHeading = !!(heading && titleNode && clean(titleNode.textContent));
    var locked = !!(heading && q('[data-testid="lock-icon"]', heading)) || /\(\s*Teaser\s*\)\s*$/i.test(result.title);
    if (root && qa('[style]', root).some(function (node) {
      return /linear-gradient/i.test(node.getAttribute('style') || '') && /(?:^|\s)absolute(?:\s|$)/.test(String(node.className || ''));
    })) locked = true;
    if (scope && qa('button, h1, h2, h3, h4, [role="heading"]', scope).some(function (node) {
      if (root && root.contains(node)) return false;
      var label = clean(node.textContent);
      return /^(?:Log in to continue your adventure|Unlock (?:this )?chapter|Unlock with|Wait to unlock|Subscribe to (?:read|unlock))/i.test(label) ||
        ((!root || !liveHeading) && /^LOG IN$/i.test(label));
    })) locked = true;
    if (!liveHeading && (chapter.isTeaser === true || chapter.karmaInfo && chapter.karmaInfo.isKarmaRequired === true ||
        data.article && data.article.isAccessibleForFree === false)) locked = true;
    if (locked) return fail('CHAPTER_LOCKED', 'この章は未解放または試し読みです。Wuxiaworldの公式画面でログイン・解放を行い、全文が表示されてから取り込んでください。');
    // Never collect the gate, translator notes, comments, or embedded JSON body.
    if (!wuxiaworldVisible(root)) return fail('NO_CONTENT', 'Wuxiaworldの本文が表示されていません。公式画面で章を開いてください。');
    result.paragraphs = paragraphs(root);
    if (!hasText(result.paragraphs)) return fail('NO_CONTENT', 'Wuxiaworldの本文を取得できません。公式画面で表示状態を確認してください。');
    result.nextUrl = findEpisodeLink('a[rel="next"], link[rel="next"]', /^NEXT\s+CHAPTER$/i, book);
    result.prevUrl = findEpisodeLink('a[rel="prev"], link[rel="prev"]', /^(?:PREVIOUS|PREV)\s+CHAPTER$/i, book);
    var related = chapter.relatedChapterInfo || {};
    function relatedUrl(value) {
      if (!value || !/^[a-z0-9][a-z0-9-]{0,199}$/.test(value.slug || '') || value.visible === false ||
          value.novelId != null && chapter.novelId != null && value.novelId !== chapter.novelId) return null;
      return episodeLink(book.bookUrl + '/' + value.slug, book);
    }
    if (!result.nextUrl) result.nextUrl = relatedUrl(related.nextChapter);
    if (!result.prevUrl) result.prevUrl = relatedUrl(related.previousChapter || related.prevChapter);
    var toc = wuxiaworldToc(book, data);
    if (toc.complete) { result.chapters = toc.chapters; result.tocComplete = true; }
    return result;
  }

  try {
    here = new URL(window.location.href);
    if (!urlOf(here.href, here.href)) return JSON.stringify(fail('UNSUPPORTED_URL', '対応していないページです。'));
    result.url = here.href;
    result.source = here.hostname === 'kakuyomu.jp' ? 'kakuyomu' : /^(?:www\.)?wuxiaworld\.com$/.test(here.hostname) ? 'wuxiaworld' : 'narou';
    var kakuyomuBody = q('.widget-episodeBody, .js-episode-body');
    var narouBody = q('.js-novel-text.p-novel__text:not(.p-novel__text--preface):not(.p-novel__text--afterword), .p-novel__text:not(.p-novel__text--preface):not(.p-novel__text--afterword), #novel_honbun');
    var wuxiaworldBody = result.source === 'wuxiaworld' && q('.chapter-content .chapter-body');
    var heading = clean(document.title) + ' ' + content('h1');
    var bodyStart = clean(document.body && document.body.textContent).slice(0, 2200);
    if (q('#challenge-form, #challenge-running, #cf-challenge-running, .cf-browser-verification') ||
        /^(?:.*\s)?(?:403 Forbidden|429 Too Many Requests|Access Denied|Just a moment)/i.test(heading) ||
        (!kakuyomuBody && !narouBody && !wuxiaworldBody && /不正と思われるアクセス|アクセスが禁止されています|アクセスが集中|アクセスを制限しています|Too Many Requests|Access denied/i.test(bodyStart))) {
      return JSON.stringify(fail('ACCESS_LIMIT', 'サイトがアクセスを制限しています。取得を停止し、公式ページで確認してください。'));
    }
    if (/\/(?:auth\/)?login(?:\/|$)/.test(here.pathname) ||
        (!kakuyomuBody && !narouBody && !wuxiaworldBody && q('input[type="password"]') && /ログイン|Sign in/i.test(heading))) {
      return JSON.stringify(fail('AUTH_REQUIRED', '公式ページでログインしてください。'));
    }
    if (request.kind === 'search') {
      var validSearch = here.hostname === 'kakuyomu.jp' && /^\/search\/?$/.test(here.pathname) ||
        here.hostname === 'yomou.syosetu.com' && here.pathname === '/search.php';
      if (!validSearch) return JSON.stringify(fail('UNSUPPORTED_URL', '公式検索ページを開いてください。'));
      return JSON.stringify(searchPage(result.source, result.source === 'kakuyomu' ? apolloState() : {}));
    }
    var book = workAddress(here.href);
    if (!book) return JSON.stringify(fail('UNSUPPORTED_URL', '作品の目次またはエピソードのURLを指定してください。'));
    result.bookUrl = book.bookUrl; result.url = book.url;
    if (book.source === 'kakuyomu') {
      if (book.episode) {
        if (!kakuyomuBody) return JSON.stringify(fail('NO_CONTENT', '本文が見つかりません。ログインや公開状態を公式ページで確認してください。'));
        return JSON.stringify(kakuyomuChapter(book, kakuyomuBody));
      }
      return JSON.stringify(kakuyomuBook(book, apolloState()));
    }
    if (book.source === 'wuxiaworld') {
      var wuxiaData = wuxiaworldData(book);
      return JSON.stringify(book.episode ? wuxiaworldChapter(book, wuxiaData) : wuxiaworldBook(book, wuxiaData));
    }
    if (narouBody) return JSON.stringify(narouChapter(book, narouBody));
    if (book.episode) return JSON.stringify(fail('NO_CONTENT', '本文が見つかりません。公開状態を公式ページで確認してください。'));
    return JSON.stringify(narouBook(book));
  } catch (_) {
    return JSON.stringify(fail('EXTRACTION_ERROR', 'ページの構造を解析できませんでした。公式ページを開いてください。'));
  }
})();
