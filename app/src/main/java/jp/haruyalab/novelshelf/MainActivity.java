package jp.haruyalab.novelshelf;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String ASSET_ORIGIN="https://appassets.androidplatform.net";
    private static final String HOME=ASSET_ORIGIN+"/assets/index.html";
    private static final int IMPORT_TXT=301,EXPORT_TXT=302;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private FrameLayout root;
    private WebView ui,browser;
    private LinearLayout browserPanel;
    private TextView browserAddress;
    private ReaderStore store;
    private SpeechController speechController;
    private SharedPreferences prefs;
    private WebFetcher fetcher;
    private boolean uiReady,foreground=true,destroyed,browserExtracting,pendingExportPartial;
    // Only preferences are persisted. The current reader/fullscreen request belongs to this Activity.
    private boolean activityResumed,windowFocused,readerDisplayReading,readerDisplayFullscreen,systemBarsHidden;
    private float readerStatusInset = -1, readerNavInset = -1;
    private String incomingUrl,pendingExportId;
    private final Set<String> downloading=new HashSet<>();
    private final Set<String> downloadBusy=new HashSet<>();
    private final Map<String,Set<String>> downloadLinkChecks=new HashMap<>();
    private final Map<String,List<WebFetcher.Callback>> tocWaiters=new HashMap<>();
    private final Map<String,JSONObject> searchCache=new LinkedHashMap<>();
    private final Map<String,Long> searchTimes=new HashMap<>();

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);prefs=getSharedPreferences("reader",MODE_PRIVATE);store=new ReaderStore(this);store.pauseInterruptedJobs();
        speechController=new SpeechController(this,store,this::event);
        if(saved!=null){pendingExportId=saved.getString("exportBook");pendingExportPartial=saved.getBoolean("exportPartial",false);}
        // Insets are handled once at the root, including the keyboard on edge-to-edge Android.
        if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        root=new FrameLayout(this);root.setBackgroundColor(Color.rgb(246,243,234));setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{applySystemInsets(v,insets);return insets;});
        try{
            fetcher=new WebFetcher(this);FrameLayout.LayoutParams hidden=new FrameLayout.LayoutParams(1,1);root.addView(fetcher.view,hidden);fetcher.view.setAlpha(0);
            ui=new WebView(this);WebFetcher.configure(ui);ui.setBackgroundColor(Color.rgb(246,243,234));
            ui.getSettings().setTextZoom(100);ui.addJavascriptInterface(new AppBridge(),"Native");
            ui.setWebViewClient(new WebViewClient(){
                @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){
                    if(HOME.equals(r.getUrl().toString()))return false;
                    if(r.isForMainFrame()&&Urls.official(r.getUrl().toString()))showBrowser(r.getUrl().toString());return true;
                }
                @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                    Uri u=r.getUrl();if(!"https".equals(u.getScheme())||!"appassets.androidplatform.net".equals(u.getHost()))return emptyResponse();
                    String path=u.getPath();if(path==null||!path.startsWith("/assets/")||path.contains(".."))return emptyResponse();
                    String name=path.substring(8);if(!name.matches("[a-zA-Z0-9._/-]+")||name.contains("/"))return emptyResponse();
                    String type=name.endsWith(".html")?"text/html":name.endsWith(".css")?"text/css":name.endsWith(".js")?"text/javascript":name.endsWith(".svg")?"image/svg+xml":"text/plain";
                    try{WebResourceResponse response=new WebResourceResponse(type,"UTF-8",getAssets().open(name));Map<String,String> headers=new HashMap<>();
                        headers.put("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'");
                        headers.put("X-Content-Type-Options","nosniff");response.setResponseHeaders(headers);return response;
                    }catch(Exception e){return emptyResponse();}
                }
            });
            ui.setWebChromeClient(new WebChromeClient());root.addView(ui,new FrameLayout.LayoutParams(-1,-1));ui.loadUrl(HOME);applySettings();handleIncoming(getIntent());
        }catch(Exception e){TextView error=new TextView(this);error.setText("起動できませんでした。Android System WebViewを更新して再起動してください。\n"+e.getClass().getSimpleName());error.setPadding(32,64,32,32);root.addView(error);}
    }
    private static WebResourceResponse emptyResponse(){return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));}
    private final class AppBridge {
        @JavascriptInterface public void request(String raw){
            if(raw==null||raw.length()>262144)return;
            main.post(()->{if(destroyed||ui==null||!HOME.equals(ui.getUrl()))return;JSONObject req=Json.parse(raw);String id=req.optString("id");if(id.isEmpty()||id.length()>100)return;
                try{handle(id,req.optString("action"),req.optJSONObject("payload")==null?new JSONObject():req.optJSONObject("payload"));}
                catch(Exception e){answer(id,false,Json.obj("code","APP","message",safeMessage(e)));}
            });
        }
    }
    private String safeMessage(Exception e){String m=e.getMessage();return m==null||m.trim().isEmpty()?"操作を完了できませんでした。もう一度お試しください。":Json.limit(m,350);}
    private void answer(String id,boolean ok,Object result){
        if(destroyed||ui==null)return;String data=result==null?"{}":result.toString();
        ui.evaluateJavascript("window.onNativeResult&&window.onNativeResult("+JSONObject.quote(id)+","+ok+","+data+")",null);
    }
    private void event(String type,Object data){
        if(!uiReady||destroyed||ui==null)return;
        ui.evaluateJavascript("window.onNativeEvent&&window.onNativeEvent("+JSONObject.quote(type)+","+(data==null?"{}":data.toString())+")",null);
    }
    private void handle(String id,String action,JSONObject p)throws Exception{
        if(speechController!=null&&speechController.handle(action,p,(ok,data)->answer(id,ok,data)))return;
        String bookId=p.optString("bookId");
        switch(action){
            case "bootstrap":uiReady=true;applyReaderDisplay();answer(id,true,Json.obj("books",store.books(),"settings",settings(),"lastBookId",prefs.getString("lastBookId",""),"downloads",store.jobs()));deliverIncoming();break;
            case "readerDisplay":{
                readerDisplayReading=Boolean.TRUE.equals(p.opt("reading"));
                readerDisplayFullscreen=readerDisplayReading&&Boolean.TRUE.equals(p.opt("fullscreen"));
                applyReaderDisplay();answer(id,true,Json.obj("reading",readerDisplayReading,"fullscreen",systemBarsHidden));break;
            }
            case "getBook":answer(id,true,store.details(bookId));break;
            case "openUrl":openBook(p.optString("url"),false,(d,e)->answer(id,e==null,e==null?d:e.json()));break;
            case "updateBook":{JSONObject b=requiredBook(bookId);if("local".equals(b.optString("source")))answer(id,true,store.details(bookId));else openBook(b.optString("url"),true,(d,e)->answer(id,e==null,e==null?d:e.json()));break;}
            case "search":search(id,p);break;
            case "readChapter":readChapter(bookId,p.optString("chapterUrl"),true,(d,e)->answer(id,e==null,e==null?d:e.json()));break;
            case "prefetch":readChapter(bookId,p.optString("chapterUrl"),false,(d,e)->answer(id,e==null,e==null?new JSONObject():e.json()));break;
            case "savePosition":{
                if(store.chapterMeta(bookId,p.optString("chapterUrl"))==null){answer(id,true,new JSONObject());break;}
                JSONObject position=Json.obj("chapterUrl",p.optString("chapterUrl"),"index",Math.max(0,p.optInt("index")),"paragraph",Math.max(0,p.optInt("paragraph")),"offset",Math.max(-10000,Math.min(100000,p.optDouble("offset"))),"updatedAt",System.currentTimeMillis());
                store.savePosition(bookId,position);prefs.edit().putString("lastBookId",bookId).apply();answer(id,true,new JSONObject());break;
            }
            case "setChapterRead":store.setRead(bookId,p.optString("chapterUrl"),p.optBoolean("read",true));answer(id,true,Json.obj("book",store.book(bookId)));break;
            case "addMark":{
                requiredBook(bookId);JSONObject m=Json.obj("chapterUrl",p.optString("chapterUrl"),"index",Math.max(0,p.optInt("index")),"paragraph",Math.max(0,p.optInt("paragraph")),"offset",p.optDouble("offset",0),"excerpt",Json.limit(p.optString("excerpt"),1000),"note",Json.limit(p.optString("note"),1000));
                answer(id,true,store.addMark(bookId,m));break;
            }
            case "getMarks":answer(id,true,Json.obj("marks",store.marks(bookId)));break;
            case "deleteMark":store.deleteMark(p.optString("id"));answer(id,true,new JSONObject());break;
            case "downloadBook":case "resumeDownload":requiredBook(bookId);startDownload(bookId);answer(id,true,store.job(bookId));break;
            case "pauseDownload":downloading.remove(bookId);downloadLinkChecks.remove(bookId);store.setJob(bookId,"paused","");emitDownload(bookId);applySettings();answer(id,true,new JSONObject());break;
            case "getDownloads":answer(id,true,Json.obj("downloads",store.jobs()));break;
            case "exportTxt":exportPicker(bookId,false);answer(id,true,Json.obj("pending",true));break;
            case "exportSavedTxt":exportPicker(bookId,true);answer(id,true,Json.obj("pending",true));break;
            case "importTxt":{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("text/*");startActivityForResult(i,IMPORT_TXT);answer(id,true,Json.obj("pending",true));break;}
            case "deleteBook":if(speechController!=null)speechController.deleteBook(bookId);downloading.remove(bookId);downloadLinkChecks.remove(bookId);store.deleteBook(bookId);if(bookId.equals(prefs.getString("lastBookId","")))prefs.edit().remove("lastBookId").apply();answer(id,true,new JSONObject());event("libraryChanged",new JSONObject());break;
            case "settings":{JSONObject s=validatedSettings(p.optJSONObject("settings"));prefs.edit().putString("settings",s.toString()).apply();applySettings();answer(id,true,Json.obj("settings",s));break;}
            case "openLogin":showBrowser("wuxiaworld".equals(p.optString("source"))?"https://www.wuxiaworld.com/":"kakuyomu".equals(p.optString("source"))?"https://kakuyomu.jp/auth/login":"https://syosetu.com/login/input/");answer(id,true,new JSONObject());break;
            case "openOriginal":if(!Urls.official(p.optString("url")))throw new IllegalArgumentException("公式サイトのURLを指定してください。");showBrowser(p.optString("url"));answer(id,true,new JSONObject());break;
            default:throw new IllegalArgumentException("未対応の操作です："+action);
        }
    }
    private JSONObject requiredBook(String id){JSONObject b=store.book(id);if(b==null)throw new IllegalArgumentException("本棚に作品がありません。");return b;}
    private JSONObject settings(){return normalizedDisplaySettings(Json.merge(Json.obj("theme","paper","fontSize",20,"lineHeight",2.0,"fontFamily","serif","showRuby",true,"showPreface",false,"autoNext",true,"resumeOnLaunch",true,"keepScreenOn",false,"showSpeechPlayer",false,"autoFullscreen",true),Json.parse(prefs.getString("settings","{}"))));}
    private JSONObject normalizedDisplaySettings(JSONObject s){
        Json.put(s,"showSpeechPlayer",s.optBoolean("showSpeechPlayer",false));
        Json.put(s,"autoFullscreen",s.optBoolean("autoFullscreen",true));return s;
    }
    private JSONObject validatedSettings(JSONObject patch){
        JSONObject s=Json.merge(settings(),patch);String theme=s.optString("theme");if(!theme.equals("night")&&!theme.equals("white"))theme="paper";Json.put(s,"theme",theme);
        Json.put(s,"fontSize",Math.max(14,Math.min(36,s.optInt("fontSize",20))));Json.put(s,"lineHeight",Math.max(1.4,Math.min(2.8,s.optDouble("lineHeight",2))));
        Json.put(s,"fontFamily","sans".equals(s.optString("fontFamily"))?"sans":"serif");return normalizedDisplaySettings(s);
    }
    private void applySettings(){
        JSONObject s=settings();if(s.optBoolean("keepScreenOn")||foreground&&!downloading.isEmpty())getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        boolean dark="night".equals(s.optString("theme"));int color=dark?Color.rgb(21,29,26):"white".equals(s.optString("theme"))?Color.WHITE:Color.rgb(246,243,234);
        root.setBackgroundColor(color);getWindow().setStatusBarColor(color);getWindow().setNavigationBarColor(color);
        applyReaderDisplay();
    }
    private void applyReaderDisplay(){
        if(root==null||prefs==null||destroyed)return;
        boolean browserVisible=browserPanel!=null&&browserPanel.getVisibility()==View.VISIBLE;
        boolean hide=readerDisplayReading&&readerDisplayFullscreen&&uiReady&&activityResumed&&foreground&&windowFocused&&!browserVisible;
        boolean dark="night".equals(settings().optString("theme"));
        systemBarsHidden=hide;
        if(Build.VERSION.SDK_INT>=30){
            WindowInsetsController controller=getWindow().getInsetsController();
            if(controller!=null){
                int light=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(dark?0:light,light);
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if(hide)controller.hide(WindowInsets.Type.systemBars());else controller.show(WindowInsets.Type.systemBars());
            }
        }else{
            // Retain layout and theme flags together; theme changes must not silently exit immersive mode.
            int flags=View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if(!dark)flags|=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            if(hide)flags|=View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
        root.requestApplyInsets();
    }
    private void applySystemInsets(View view, WindowInsets insets) {
        boolean reading = readerDisplayReading && (browserPanel == null || browserPanel.getVisibility() != View.VISIBLE);
        int left, top, right, bottom, status = 0, nav = 0;
        if (Build.VERSION.SDK_INT >= 30) {
            int bars = reading ? WindowInsets.Type.captionBar() : WindowInsets.Type.systemBars();
            android.graphics.Insets safe = insets.getInsets(bars | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            left = safe.left; top = safe.top; right = safe.right; bottom = safe.bottom;
            if (reading) {
                android.graphics.Insets stable = insets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                // Side navigation must not change line wrapping when it is hidden in landscape.
                left = Math.max(left, stable.left); right = Math.max(right, stable.right);
                status = Math.max(0, stable.top - top); nav = Math.max(0, stable.bottom - bottom);
            }
        } else {
            left = reading ? insets.getStableInsetLeft() : insets.getSystemWindowInsetLeft();
            right = reading ? insets.getStableInsetRight() : insets.getSystemWindowInsetRight();
            top = reading ? 0 : insets.getSystemWindowInsetTop();
            int visibleBottom = insets.getSystemWindowInsetBottom();
            bottom = reading && visibleBottom <= insets.getStableInsetBottom() ? 0 : visibleBottom;
            if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                android.view.DisplayCutout cutout = insets.getDisplayCutout();
                left = Math.max(left, cutout.getSafeInsetLeft()); top = Math.max(top, cutout.getSafeInsetTop());
                right = Math.max(right, cutout.getSafeInsetRight()); bottom = Math.max(bottom, cutout.getSafeInsetBottom());
            }
            if (reading) {
                status = Math.max(0, insets.getStableInsetTop() - top);
                nav = Math.max(0, insets.getStableInsetBottom() - bottom);
            }
        }
        if (view.getPaddingLeft()!=left || view.getPaddingTop()!=top || view.getPaddingRight()!=right || view.getPaddingBottom()!=bottom)
            view.setPadding(left,top,right,bottom);

        // Only overlays reserve status/navigation space. A swipe never moves the reading WebView.
        if (reading && uiReady) {
            float density = getResources().getDisplayMetrics().density;
            float statusCss = status / density, navCss = nav / density;
            if (statusCss != readerStatusInset || navCss != readerNavInset) {
                readerStatusInset = statusCss; readerNavInset = navCss;
                event("readerInsets", Json.obj("top", statusCss, "bottom", navCss));
            }
        } else {
            readerStatusInset = -1; readerNavInset = -1;
        }
    }
    private void openBook(String raw,boolean refresh,WebFetcher.Callback cb){
        final String url,bookUrl,bookId;
        try{url=Urls.input(raw);bookUrl=Urls.book(url);bookId=Urls.id(bookUrl);}catch(Exception e){cb.done(null,new WebFetcher.FetchError("URL",safeMessage(e),raw));return;}
        JSONObject existing=store.book(bookId);
        if(!refresh&&existing!=null){JSONObject d=store.details(bookId);if(!url.equals(bookUrl))Json.put(d,"chapterUrl",url);cb.done(d,null);continueAvailableToc(bookId);return;}
        fetcher.fetch(bookUrl,"book",true,(data,error)->{
            if(error!=null){cb.done(null,error);return;}
            if(refresh&&existing!=null&&store.rawBook(bookId)==null){cb.done(null,new WebFetcher.FetchError("MISSING","作品が削除されています。",bookUrl));return;}
            try{
                ingestBook(bookId,bookUrl,data,refresh,0);JSONObject details=store.details(bookId);if(!url.equals(bookUrl))Json.put(details,"chapterUrl",url);
                cb.done(details,null);event("libraryChanged",Json.obj("bookId",bookId));
                continueAvailableToc(bookId);
            }catch(Exception e){cb.done(null,new WebFetcher.FetchError("EXTRACT",safeMessage(e),bookUrl));}
        });
    }
    private void continueAvailableToc(String id){
        JSONObject b=store.book(id);if(b==null||b.optBoolean("tocComplete"))return;
        if("wuxiaworld".equals(b.optString("source"))&&b.optString("tocNextUrl").isEmpty())return;
        ensureToc(id,(x,e)->{if(e!=null)event("notice",e.json());});
    }
    private void ingestBook(String id,String url,JSONObject data,boolean refresh,int pageOffset)throws Exception{
        JSONArray items=validatedToc(url,Json.arr(data,"chapters"),pageOffset);boolean isShort=Json.arr(data,"paragraphs").length()>0;
        JSONObject old=store.rawBook(id);String title=data.optString("title",data.optString("bookTitle"));
        if(isShort&&!data.optString("bookTitle").isEmpty())title=data.optString("bookTitle");
        if(title.trim().isEmpty())title=old==null?"名称未取得":old.optString("title");
        int total=Math.max(data.optInt("total"),items.length()+pageOffset);String next=data.optString("tocNextUrl");
        if(!next.isEmpty()&&(!Urls.remote(next)||!Urls.sameBook(next,url)))next="";
        boolean complete=data.optBoolean("tocComplete",next.isEmpty()&&items.length()>0);
        // A paginated Narou adapter describes only the current page; the store
        // has already collected the preceding pages of this same snapshot.
        if("narou".equals(Urls.source(url))&&next.isEmpty()&&pageOffset>0&&items.length()>0&&pageOffset+items.length()==total)complete=true;
        if(isShort&&items.length()==0){items.put(Json.obj("url",url,"title",title,"index",0));total=1;complete=true;}
        if(items.length()==0&&!isShort)throw new IllegalArgumentException("目次を取り出せませんでした。公式画面で作品ページを確認してください。");
        String snapshot=refresh||old==null?UUID.randomUUID().toString():old.optString("tocSnapshotId",UUID.randomUUID().toString());
        int currentTotal=refresh||complete?total:Math.max(total,old==null?0:old.optInt("total"));
        JSONObject meta=Json.obj("id",id,"url",url,"source",Urls.source(url),"title",Json.limit(title,1000),"author",Json.limit(data.optString("author",old==null?"":old.optString("author")),1000),"description",Json.limit(data.optString("description",old==null?"":old.optString("description")),15000),"total",currentTotal,"tocComplete",complete,"tocNextUrl",next,"tocSnapshotId",snapshot);
        Json.put(meta,"reportedTotal",Math.max(0,data.optInt("total")));
        store.putBook(meta);store.mergeChapters(id,items,pageOffset,snapshot);if(complete)store.finishTocSnapshot(id,snapshot);
        JSONObject job=store.job(id);if("complete".equals(job.optString("state"))&&!store.book(id).optBoolean("complete")){store.setJob(id,"paused","");emitDownload(id);}
        if(isShort){JSONObject chapter=cleanChapter(id,url,data);store.putChapter(id,chapter);}
    }
    private JSONArray validatedToc(String bookUrl,JSONArray source,int offset){
        JSONArray out=new JSONArray();Set<String> seen=new HashSet<>();
        for(int i=0;i<source.length();i++){JSONObject c=source.optJSONObject(i);if(c==null)continue;try{
            String url=Urls.input(c.optString("url"));if(!Urls.sameBook(bookUrl,url)||!seen.add(url))continue;
            int index=c.optInt("index",offset+i);
            out.put(Json.obj("url",url,"title",Json.limit(c.optString("title","第"+(index+1)+"話"),1000),"index",index,"section",Json.limit(c.optString("section"),1000)));
        }catch(Exception ignored){}}
        return out;
    }
    private void ensureToc(String id,WebFetcher.Callback cb){
        JSONObject b=store.book(id);if(b==null){cb.done(null,new WebFetcher.FetchError("MISSING","作品が削除されています。",""));return;}
        if(b.optBoolean("tocComplete")){cb.done(store.details(id),null);return;}
        if(tocWaiters.containsKey(id)){tocWaiters.get(id).add(cb);return;}
        List<WebFetcher.Callback> callbacks=new ArrayList<>();callbacks.add(cb);tocWaiters.put(id,callbacks);loadTocPage(id,new HashSet<>());
    }
    private void loadTocPage(String id,Set<String> seen){
        JSONObject b=store.book(id);if(b==null){finishToc(id,new WebFetcher.FetchError("MISSING","作品が削除されています。",""));return;}
        String next=b.optString("tocNextUrl");
        if(b.optBoolean("tocComplete")){finishToc(id,null);return;}
        if(next.isEmpty()||seen.contains(next)||seen.size()>200){finishToc(id,new WebFetcher.FetchError("TOC_INCOMPLETE","目次の全体を確認できません。公式画面を開くか、作品情報を更新してください。",b.optString("url")));return;}
        seen.add(next);int offset=store.snapshotChapterCount(id,b.optString("tocSnapshotId"));
        fetcher.fetch(next,"book",false,(data,error)->{
            if(error!=null){finishToc(id,error);return;}
            try{if(store.rawBook(id)==null){finishToc(id,new WebFetcher.FetchError("MISSING","作品が削除されています。",next));return;}
                ingestBook(id,b.optString("url"),data,false,offset);event("tocUpdated",Json.obj("bookId",id));loadTocPage(id,seen);
            }catch(Exception e){finishToc(id,new WebFetcher.FetchError("TOC",safeMessage(e),next));}
        });
    }
    private void finishToc(String id,WebFetcher.FetchError error){
        List<WebFetcher.Callback> callbacks=tocWaiters.remove(id);if(callbacks==null)return;
        JSONObject data=store.rawBook(id)==null?null:store.details(id);for(WebFetcher.Callback cb:callbacks)cb.done(data,error);event("tocUpdated",Json.obj("bookId",id));
    }
    private void readChapter(String id,String raw,boolean priority,WebFetcher.Callback cb){
        readChapter(id,raw,priority,false,cb);
    }
    private void readChapter(String id,String raw,boolean priority,boolean refresh,WebFetcher.Callback cb){
        JSONObject b=store.book(id);if(b==null){cb.done(null,new WebFetcher.FetchError("MISSING","作品がありません。",raw));return;}
        String url=raw;if(url==null||url.isEmpty()){JSONArray a=store.chapters(id);url=a.length()>0?a.optJSONObject(0).optString("url"):"";}
        JSONObject cached=store.chapter(id,url);if(!refresh&&cached!=null){cb.done(withChapterLinks(id,cached),null);return;}
        if("local".equals(b.optString("source"))){cb.done(null,new WebFetcher.FetchError("MISSING","保存データが見つかりません。",url));return;}
        final String chapterUrl;try{chapterUrl=Urls.input(url);if(!Urls.sameBook(chapterUrl,b.optString("url")))throw new IllegalArgumentException("別作品の話は取得できません。");}
        catch(Exception e){cb.done(null,new WebFetcher.FetchError("URL",safeMessage(e),url));return;}
        fetcher.fetch(chapterUrl,"chapter",priority,(data,error)->{
            if(error!=null){cb.done(null,error);return;}
            try{if(store.rawBook(id)==null)throw new IllegalArgumentException("作品が削除されています。");
                JSONObject clean=cleanChapter(id,chapterUrl,data);store.putChapter(id,clean);JSONObject result=withChapterLinks(id,clean);cb.done(result,null);event("libraryChanged",Json.obj("bookId",id));
            }catch(Exception e){cb.done(null,new WebFetcher.FetchError("EXTRACT",safeMessage(e),chapterUrl));}
        });
    }
    private JSONObject cleanChapter(String id,String url,JSONObject data){
        JSONObject result=ChapterContent.clean(store,id,url,data);
        JSONObject job=store.job(id);if(job!=null&&"paused".equals(job.optString("state")))emitDownload(id);
        return result;
    }
    private JSONObject withChapterLinks(String id,JSONObject body){return ChapterContent.withLinks(store,id,body);}
    private void search(String requestId,JSONObject p){
        String query=Json.limit(p.optString("query").trim(),140);String source=p.optString("source","all");int page=Math.max(1,Math.min(100,p.optInt("page",1)));
        if(query.isEmpty()){answer(requestId,false,Json.obj("code","QUERY","message","作品名・作者名・キーワードを入力してください。"));return;}
        String key=source+"|"+page+"|"+query;
        if(searchCache.containsKey(key)&&System.currentTimeMillis()-searchTimes.get(key)<600000){answer(requestId,true,searchCache.get(key));return;}
        final JSONArray warnings=new JSONArray();final Map<String,JSONArray> parts=new HashMap<>();final boolean[] more={false};
        final int[] remain={source.equals("all")?2:1};
        WebFetcher.Callback finish=(data,error)->{
            if(error!=null)warnings.put(error.getMessage());else if(data!=null){parts.put(data.optString("source"),Json.arr(data,"items"));more[0]|=data.optBoolean("more");}
            if(--remain[0]>0)return;
            JSONArray result=new JSONArray();JSONArray a=parts.get("kakuyomu"),b=parts.get("narou");if(a==null)a=new JSONArray();if(b==null)b=new JSONArray();
            Set<String> seen=new HashSet<>();for(int i=0;i<Math.max(a.length(),b.length());i++){appendSearch(result,a.optJSONObject(i),seen);appendSearch(result,b.optJSONObject(i),seen);}
            if(parts.isEmpty()){answer(requestId,false,Json.obj("code","SEARCH","message",warnings.length()>0?warnings.optString(0):"検索できませんでした。"));return;}
            JSONObject response=Json.obj("items",result,"more",more[0],"warnings",warnings,"page",page);searchCache.put(key,response);searchTimes.put(key,System.currentTimeMillis());
            if(searchCache.size()>40){String first=searchCache.keySet().iterator().next();searchCache.remove(first);searchTimes.remove(first);}answer(requestId,true,response);
        };
        if(source.equals("all")||source.equals("kakuyomu")){
            String url="https://kakuyomu.jp/search?q="+Uri.encode(query)+"&page="+page;
            fetcher.fetch(url,"search",true,(d,e)->{if(d!=null)Json.put(d,"source","kakuyomu");finish.done(d,e);});
        }
        if(source.equals("all")||source.equals("narou"))io.execute(()->{
            JSONObject data=null;WebFetcher.FetchError error=null;
            try{String url="https://api.syosetu.com/novelapi/api/?out=json&lim=20&st="+((page-1)*20+1)+"&order=hyoka&of=t-n-w-s-ga-e&word="+URLEncoder.encode(query,"UTF-8");
                JSONArray rows=new JSONArray(httpGet(url));JSONArray items=new JSONArray();int all=rows.length()>0?rows.optJSONObject(0).optInt("allcount"):0;
                for(int i=1;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row==null)continue;String code=row.optString("ncode").toLowerCase(Locale.ROOT);if(!code.matches("n[0-9]{1,5}[a-z]{1,2}"))continue;
                    String bookUrl="https://ncode.syosetu.com/"+code+"/";items.put(Json.obj("url",bookUrl,"title",row.optString("title"),"author",row.optString("writer"),"description",row.optString("story"),"total",row.optInt("general_all_no"),"source","narou","novelCompleted",row.optInt("end",1)==0));
                }data=Json.obj("source","narou","items",items,"more",page*20<all);
            }catch(Exception e){error=new WebFetcher.FetchError("SEARCH_NAROU","なろう検索を取得できませんでした："+safeMessage(e),"https://api.syosetu.com/novelapi/api/");}
            JSONObject result=data;WebFetcher.FetchError failure=error;main.post(()->finish.done(result,failure));
        });
        if(!source.equals("all")&&!source.equals("narou")&&!source.equals("kakuyomu"))answer(requestId,false,Json.obj("code","QUERY","message","検索先を選んでください。"));
    }
    private void appendSearch(JSONArray out,JSONObject item,Set<String> seen){
        if(item==null)return;try{String url=Urls.book(item.optString("url"));if(!seen.add(url))return;
            out.put(Json.obj("id",Urls.id(url),"url",url,"source",Urls.source(url),"title",Json.limit(item.optString("title"),1000),"author",Json.limit(item.optString("author"),1000),"description",Json.limit(item.optString("description"),5000),"total",item.optInt("total"),"downloaded",0,"readCount",0));
        }catch(Exception ignored){}
    }
    private String httpGet(String url)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(15000);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","TsuzukiPersonalReader/1.0 (Android; metadata search)");
        try{int status=c.getResponseCode();if(status!=200)throw new IllegalArgumentException(status==429?"アクセス制限（429）。時間を置いてください。":"HTTP "+status);
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>3000000)throw new IllegalArgumentException("応答が大きすぎます。");out.write(buf,0,n);}return out.toString("UTF-8");}
        }finally{c.disconnect();}
    }
    private void startDownload(String id){
        if(downloading.contains(id))return;
        JSONObject b=store.book(id);if(b.optBoolean("complete")){store.setJob(id,"complete","");emitDownload(id);return;}
        downloading.add(id);downloadLinkChecks.put(id,new HashSet<>());store.setJob(id,"downloading","");emitDownload(id);applySettings();
        if("wuxiaworld".equals(b.optString("source"))&&!b.optBoolean("tocComplete")){
            // Refresh the public total and first chapter, then grow the known
            // TOC through ordinary NEXT CHAPTER links. A locked chapter stops
            // the existing queue; no purchase/unlock request is made.
            openBook(b.optString("url"),true,(data,error)->{if(!downloading.contains(id))return;if(error!=null){failDownload(id,error.getMessage());return;}downloadStep(id);});return;
        }
        ensureToc(id,(data,error)->{if(!downloading.contains(id))return;if(error!=null){failDownload(id,error.getMessage());return;}downloadStep(id);});
    }
    private void downloadStep(String id){
        if(!downloading.contains(id)||downloadBusy.contains(id))return;
        if(!foreground){downloading.remove(id);store.setJob(id,"paused","");emitDownload(id);return;}
        JSONObject b=store.book(id);if(b==null){downloading.remove(id);return;}
        JSONObject missing=store.nextMissing(id);
        boolean refreshLink=false;
        if("wuxiaworld".equals(b.optString("source"))&&!b.optBoolean("tocComplete")&&!b.optBoolean("rangeComplete")){
            JSONObject edge=store.savedPrefixEnd(id);
            if(edge!=null&&b.optInt("reportedTotal")>edge.optInt("index")+1&&(missing==null||missing.optInt("index")>edge.optInt("index")+1)){
                // A previously final chapter may now have a new NEXT link.
                // Recheck each saved frontier only once per explicit run.
                Set<String> checked=downloadLinkChecks.get(id);if(checked==null){checked=new HashSet<>();downloadLinkChecks.put(id,checked);}
                if(!checked.add(edge.optString("url"))){failDownload(id,"続きへのリンクを確認できませんでした。公式ページを確認してください。保存済みの章は残ります。");return;}
                missing=edge;refreshLink=true;
            }
        }
        if(missing==null){
            // A complete, unique 0..N-1 body range plus the site's reported N
            // confirms a TOC discovered through chapter navigation.
            if("wuxiaworld".equals(b.optString("source"))&&!b.optBoolean("tocComplete")&&b.optBoolean("rangeComplete")&&b.optInt("reportedTotal")>0&&b.optInt("reportedTotal")==b.optInt("total")){
                store.putBook(Json.obj("id",id,"tocComplete",true,"tocNextUrl",""));b=store.book(id);
            }
            downloading.remove(id);downloadLinkChecks.remove(id);boolean complete=b.optBoolean("complete");store.setJob(id,complete?"complete":"error",complete?"":"全体の目次と本文がまだそろっていません。公式ページの次章リンクや作品情報を確認してください。保存済みの章は残ります。");emitDownload(id);applySettings();event("libraryChanged",Json.obj("bookId",id));return;
        }
        downloadBusy.add(id);readChapter(id,missing.optString("url"),false,refreshLink,(data,error)->{
            downloadBusy.remove(id);if(!downloading.contains(id))return;
            if(error!=null){failDownload(id,error.getMessage());return;}emitDownload(id);main.postDelayed(()->downloadStep(id),100);
        });
    }
    private void failDownload(String id,String message){downloading.remove(id);downloadLinkChecks.remove(id);store.setJob(id,"error",message);emitDownload(id);applySettings();}
    private void emitDownload(String id){JSONObject d=store.job(id);if(d!=null)event("download",d);}
    private void exportPicker(String id,boolean partial){
        JSONObject b=requiredBook(id);if(!partial&&!b.optBoolean("complete"))throw new IllegalArgumentException("まず全話保存を完了してください。未保存の話がある状態では全編TXTを書き出せません。");
        if(partial&&b.optInt("downloaded")==0)throw new IllegalArgumentException("書き出せる保存済み本文がありません。先に閲覧できる章を開いてください。");
        if(pendingExportId!=null)throw new IllegalArgumentException("ファイル保存画面を完了してください。");
        pendingExportId=id;pendingExportPartial=partial;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("text/plain");
        String name=b.optString("title","小説").replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_");i.putExtra(Intent.EXTRA_TITLE,Json.limit(name,90)+(partial?"（保存済み"+b.optInt("downloaded")+"話）":"")+".txt");startActivityForResult(i,EXPORT_TXT);
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==EXPORT_TXT){String id=pendingExportId;boolean partial=pendingExportPartial;pendingExportId=null;pendingExportPartial=false;if(id==null)return;
            if(resultCode!=RESULT_OK||data==null||data.getData()==null){event("exported",Json.obj("bookId",id,"success",false,"cancelled",true));return;}
            Uri uri=data.getData();io.execute(()->{boolean success=false;String message="";
                try(OutputStream stream=getContentResolver().openOutputStream(uri,"wt")){if(stream==null)throw new IllegalArgumentException("保存先を開けませんでした。");try(OutputStreamWriter writer=new OutputStreamWriter(stream,StandardCharsets.UTF_8)){store.exportText(id,writer,partial);}success=true;}
                catch(Exception e){message=safeMessage(e);}boolean done=success;String err=message;main.post(()->event("exported",Json.obj("bookId",id,"success",done,"partial",partial,"message",err)));});
        }else if(requestCode==IMPORT_TXT&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null)importText(data.getData());
    }
    private void importText(Uri uri){
        io.execute(()->{String createdId=null;try{
            String filename="読み込んだ小説";try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())filename=c.getString(0);}
            byte[] bytes;try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
                if(in==null)throw new IllegalArgumentException("ファイルを開けません。");byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>25*1024*1024)throw new IllegalArgumentException("TXTは25MB以下のファイルに対応しています。");out.write(buf,0,n);}bytes=out.toByteArray();}
            String text=decodeText(bytes).replace("\r\n","\n").replace('\r','\n').replace("\u0000","");if(text.trim().isEmpty())throw new IllegalArgumentException("空のテキストファイルです。");
            String bookId="local_"+UUID.randomUUID().toString();createdId=bookId;String title=filename.replaceFirst("(?i)\\.txt$","");String bookUrl="local:"+bookId;
            List<JSONObject> pieces=splitText(text,bookId,bookUrl);JSONObject book=Json.obj("id",bookId,"url",bookUrl,"source","local","title",title,"author","端末から読み込み","description","TXTファイルから読み込み","total",pieces.size(),"tocComplete",true,"tocNextUrl","");store.putBook(book);
            JSONArray toc=new JSONArray();for(JSONObject c:pieces)toc.put(Json.obj("url",c.optString("url"),"title",c.optString("title"),"index",c.optInt("index")));store.mergeChapters(bookId,toc,0);
            for(int i=0;i<pieces.size();i++){JSONObject c=pieces.get(i);if(i>0)Json.put(c,"prevUrl",pieces.get(i-1).optString("url"));if(i+1<pieces.size())Json.put(c,"nextUrl",pieces.get(i+1).optString("url"));store.putChapter(bookId,c);}
            store.setJob(bookId,"complete","");main.post(()->{event("imported",Json.obj("bookId",bookId));event("libraryChanged",Json.obj("bookId",bookId));});
        }catch(Exception e){if(createdId!=null)store.deleteBook(createdId);String msg=safeMessage(e);main.post(()->event("notice",Json.obj("message","TXTを読み込めませんでした："+msg)));}});
    }
    private String decodeText(byte[] b)throws CharacterCodingException{
        if(b.length>=2&&(b[0]&255)==255&&(b[1]&255)==254)return new String(b,2,b.length-2,StandardCharsets.UTF_16LE);
        if(b.length>=2&&(b[0]&255)==254&&(b[1]&255)==255)return new String(b,2,b.length-2,StandardCharsets.UTF_16BE);
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString().replaceFirst("^\uFEFF","");}
        catch(CharacterCodingException e){return Charset.forName("windows-31j").newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();}
    }
    private List<JSONObject> splitText(String text,String id,String base){
        List<JSONObject> chapters=new ArrayList<>();JSONArray paragraphs=new JSONArray();String title="本文";int length=0,lines=0;
        for(int start=0;start<=text.length();){
            if(++lines>100000)throw new IllegalArgumentException("TXTの行数が多すぎます。10万行以下に分けて読み込んでください。");
            int end=text.indexOf('\n',start);if(end<0)end=text.length();String line=text.substring(start,end);start=end+1;
            boolean heading=line.length()<160&&line.matches("^第[0-9０-９一二三四五六七八九十百千]+[話章節].*");
            if((heading&&paragraphs.length()>0)||length>=22000||paragraphs.length()>=800){chapters.add(localChapter(id,base,chapters.size(),title,paragraphs));paragraphs=new JSONArray();length=0;title=heading?line:"本文 "+(chapters.size()+1);}
            if(heading){title=line;continue;}if(line.matches("^[─━－=]{8,}$"))continue;
            // Every SQLite row is bounded, including a TXT with one very long line.
            if(line.isEmpty()){paragraphs.put(Json.obj("text",""));continue;}
            for(int at=0;at<line.length();){
                if(length>=22000){chapters.add(localChapter(id,base,chapters.size(),title,paragraphs));paragraphs=new JSONArray();length=0;title="本文 "+(chapters.size()+1);}
                int cut=Math.min(line.length(),at+22000-length);if(cut<line.length()&&cut>at&&Character.isHighSurrogate(line.charAt(cut-1)))cut--;
                if(cut==at){chapters.add(localChapter(id,base,chapters.size(),title,paragraphs));paragraphs=new JSONArray();length=0;title="本文 "+(chapters.size()+1);continue;}
                String part=line.substring(at,cut);paragraphs.put(Json.obj("text",part));length+=part.length();at=cut;
            }
        }
        if(paragraphs.length()>0)chapters.add(localChapter(id,base,chapters.size(),title,paragraphs));return chapters;
    }
    private JSONObject localChapter(String id,String base,int index,String title,JSONArray paragraphs){return Json.obj("bookId",id,"url",base+"/"+index,"index",index,"title",title,"paragraphs",paragraphs,"preface",new JSONArray(),"afterword",new JSONArray(),"prevUrl","","nextUrl","","cached",true);}

    private void showBrowser(String url){
        if(!Urls.official(url))throw new IllegalArgumentException("公式サイトのURLを指定してください。");
        if(browserPanel==null){
            browserPanel=new LinearLayout(this);browserPanel.setOrientation(LinearLayout.VERTICAL);browserPanel.setBackgroundColor(Color.WHITE);
            LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setBackgroundColor(Color.rgb(237,242,234));bar.setPadding(dp(4),0,dp(4),0);
            Button back=new Button(this);back.setText("戻る");back.setTextSize(12);back.setOnClickListener(v->{if(browser.canGoBack())browser.goBack();else closeBrowser();});bar.addView(back,new LinearLayout.LayoutParams(dp(68),dp(52)));
            browserAddress=new TextView(this);browserAddress.setTextSize(12);browserAddress.setMaxLines(2);browserAddress.setTextColor(Color.rgb(35,62,48));bar.addView(browserAddress,new LinearLayout.LayoutParams(0,dp(52),1));
            Button close=new Button(this);close.setText("閉じる");close.setTextSize(12);close.setOnClickListener(v->closeBrowser());bar.addView(close,new LinearLayout.LayoutParams(dp(76),dp(52)));browserPanel.addView(bar);
            browser=new WebView(this);WebFetcher.configure(browser);browser.setWebViewClient(new WebViewClient(){
                @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){
                    if(!r.isForMainFrame())return false;String target=r.getUrl().toString();String host=r.getUrl().getHost();
                    if(Urls.official(target)||"https".equals(r.getUrl().getScheme())&&("accounts.google.com".equals(host)||"appleid.apple.com".equals(host)))return false;
                    if("https".equals(r.getUrl().getScheme()))try{startActivity(new Intent(Intent.ACTION_VIEW,r.getUrl()));}catch(Exception ignored){Toast.makeText(MainActivity.this,"外部ブラウザを開けません。",Toast.LENGTH_SHORT).show();}return true;
                }
                @Override public void onPageFinished(WebView v,String target){Uri parsed=Uri.parse(target);browserAddress.setText("公式サイト\n"+(parsed.getHost()==null?"":parsed.getHost()));CookieManager.getInstance().flush();}
            });browser.setWebChromeClient(new WebChromeClient());browserPanel.addView(browser,new LinearLayout.LayoutParams(-1,0,1));
            Button read=new Button(this);read.setText("このページを「つづき」で読む");read.setTextColor(Color.WHITE);read.setBackgroundColor(Color.rgb(40,80,60));read.setOnClickListener(v->{try{String work=Urls.input(browser.getUrl());if("wuxiaworld".equals(Urls.source(work)))captureVisiblePage(work);else{closeBrowser();event("openUrl",Json.obj("url",work));}}catch(Exception e){Toast.makeText(this,"作品または各話のページを開いてから押してください。",Toast.LENGTH_LONG).show();}});browserPanel.addView(read,new LinearLayout.LayoutParams(-1,dp(52)));
            root.addView(browserPanel,new FrameLayout.LayoutParams(-1,-1));
        }
        browserPanel.setVisibility(View.VISIBLE);applyReaderDisplay();browserAddress.setText("公式サイトを読み込み中…");browser.loadUrl(url);browser.requestFocus();
    }
    private void captureVisiblePage(String url){
        if(browserExtracting)return;browserExtracting=true;Toast.makeText(this,"表示中のページから本文を確認しています。",Toast.LENGTH_SHORT).show();
        fetcher.extractVisible(browser,url,(data,error)->{
            browserExtracting=false;if(destroyed||browserPanel==null||browserPanel.getVisibility()!=View.VISIBLE)return;
            if(error!=null){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();return;}
            try{
                String bookUrl=Urls.book(url),id=Urls.id(bookUrl);
                if("chapter".equals(data.optString("kind"))){
                    if(Json.arr(data,"paragraphs").length()==0)throw new IllegalArgumentException("表示中の本文が見つかりません。公式ページの読み込み完了後に押してください。");
                    JSONObject existing=store.rawBook(id);JSONArray toc=validatedToc(bookUrl,Json.arr(data,"chapters"),0);
                    if(data.optBoolean("tocComplete")&&toc.length()>0){
                        JSONObject bookData=Json.copy(data);Json.put(bookData,"title",data.optString("bookTitle"));bookData.remove("paragraphs");bookData.remove("preface");bookData.remove("afterword");ingestBook(id,bookUrl,bookData,true,0);
                    }else if(existing==null){
                        String title=data.optString("bookTitle");if(title.trim().isEmpty())title=Uri.parse(bookUrl).getLastPathSegment().replace('-',' ');
                        store.putBook(Json.obj("id",id,"url",bookUrl,"source","wuxiaworld","title",title,"author",data.optString("author"),"description",data.optString("description"),"total",Math.max(data.optInt("total"),data.optInt("index",0)+1),"reportedTotal",Math.max(0,data.optInt("total")),"tocComplete",false,"tocNextUrl","","tocSnapshotId",UUID.randomUUID().toString()));
                    }
                    JSONObject chapter=cleanChapter(id,url,data);store.putChapter(id,chapter);
                }else ingestBook(id,bookUrl,data,true,0);
                closeBrowser();event("libraryChanged",Json.obj("bookId",id));event("openUrl",Json.obj("url",url));
            }catch(Exception e){Toast.makeText(this,safeMessage(e),Toast.LENGTH_LONG).show();}
        });
    }
    private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
    private void closeBrowser(){if(browserPanel!=null){browserPanel.setVisibility(View.GONE);browser.stopLoading();CookieManager.getInstance().flush();ui.requestFocus();applyReaderDisplay();event("browserClosed",new JSONObject());}}
    @Override public void onBackPressed(){
        if(browserPanel!=null&&browserPanel.getVisibility()==View.VISIBLE){if(browser.canGoBack())browser.goBack();else closeBrowser();return;}
        if(ui!=null)ui.evaluateJavascript("window.handleBack?window.handleBack():false",result->{if("false".equals(result)||"null".equals(result))MainActivity.super.onBackPressed();});else super.onBackPressed();
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleIncoming(intent);}
    private void handleIncoming(Intent intent){
        if(intent==null)return;String raw=null;
        // This link only returns to the UI. OAuth codes are accepted solely on the loopback socket.
        if(Intent.ACTION_VIEW.equals(intent.getAction())&&intent.getData()!=null){
            Uri returnUri=intent.getData();
            if("tsuzuki".equals(returnUri.getScheme())&&"chatgpt".equals(returnUri.getHost())&&"/return".equals(returnUri.getPath()))return;
        }
        if(Intent.ACTION_VIEW.equals(intent.getAction())&&intent.getData()!=null)raw=intent.getData().toString();
        else if(Intent.ACTION_SEND.equals(intent.getAction())){
            raw=intent.getStringExtra(Intent.EXTRA_TEXT);Uri stream=intent.getParcelableExtra(Intent.EXTRA_STREAM);if(stream!=null&&"content".equals(stream.getScheme())){importText(stream);return;}
        }
        if(raw!=null)try{incomingUrl=Urls.input(raw);deliverIncoming();}catch(Exception e){Toast.makeText(this,safeMessage(e),Toast.LENGTH_LONG).show();}
    }
    private void deliverIncoming(){if(uiReady&&incomingUrl!=null){String url=incomingUrl;incomingUrl=null;main.postDelayed(()->event("openUrl",Json.obj("url",url)),350);}}
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);if(pendingExportId!=null){b.putString("exportBook",pendingExportId);b.putBoolean("exportPartial",pendingExportPartial);}}
    @Override public void onWindowFocusChanged(boolean hasFocus){super.onWindowFocusChanged(hasFocus);windowFocused=hasFocus;applyReaderDisplay();}
    @Override protected void onPause(){if(ui!=null)ui.evaluateJavascript("window.flushReadingPosition&&window.flushReadingPosition()",null);activityResumed=false;applyReaderDisplay();CookieManager.getInstance().flush();super.onPause();}
    @Override protected void onStop(){
        foreground=false;for(String id:new ArrayList<>(downloading)){store.setJob(id,"paused","");emitDownload(id);}downloading.clear();downloadLinkChecks.clear();
        if(fetcher!=null){fetcher.suspend(true);fetcher.cancelQueued();}applySettings();super.onStop();
    }
    @Override protected void onResume(){super.onResume();foreground=true;activityResumed=true;applyReaderDisplay();if(speechController!=null)speechController.listen();if(fetcher!=null)fetcher.suspend(false);}
    @Override protected void onDestroy(){readerDisplayReading=false;readerDisplayFullscreen=false;applyReaderDisplay();destroyed=true;if(speechController!=null)speechController.close();if(fetcher!=null)fetcher.destroy();if(browser!=null)browser.destroy();if(ui!=null){ui.removeJavascriptInterface("Native");ui.destroy();}io.shutdown();main.removeCallbacksAndMessages(null);super.onDestroy();}
}
