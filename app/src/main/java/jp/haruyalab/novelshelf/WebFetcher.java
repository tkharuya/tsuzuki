package jp.haruyalab.novelshelf;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/** A normal cookie-sharing WebView, without a JavaScript-to-Java interface. */
final class WebFetcher {
    interface Callback { void done(JSONObject data,FetchError error); }
    static final class FetchError extends Exception {
        final String code,url;
        FetchError(String code,String message,String url){super(message);this.code=code;this.url=url;}
        JSONObject json(){return Json.obj("code",code,"message",getMessage(),"url",url);}
    }
    static final class Task {
        final String url,kind;final List<Callback> callbacks=new ArrayList<>();int tries;boolean extracting;
        Task(String u,String k,Callback c){url=u;kind=k;callbacks.add(c);}
    }
    final WebView view;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final LinkedList<Task> queue=new LinkedList<>();
    private final String extractor;
    private Task active;
    private Runnable timeout;
    private long nextAllowed;
    private boolean suspended,destroyed;
    WebFetcher(Context context) throws Exception {
        try(InputStream in=context.getAssets().open("extractor.js");ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);extractor=out.toString("UTF-8");
        }
        view=new WebView(context);configure(view);
        view.getSettings().setLoadsImagesAutomatically(false);view.getSettings().setBlockNetworkImage(true);
        view.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){
                if(!r.isForMainFrame())return false;String url=r.getUrl().toString();
                if(Urls.remote(url)&&!isLogin(url))return false;
                fail("AUTH_REQUIRED","公式サイトでログインまたは確認が必要です。設定のログイン画面を開いてください。",url);return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                if(r.isForMainFrame())return null;
                // The reader uses text. Third-party advertising/media requests are unnecessary here.
                if(!Urls.official(r.getUrl().toString()))return empty();
                return null;
            }
            @Override public void onPageStarted(WebView v,String url,Bitmap favicon){if(active!=null)active.extracting=false;}
            @Override public void onPageFinished(WebView v,String url){Task t=active;if(t!=null&&samePage(t.url,url))handler.postDelayed(()->extract(t),350);}
            @Override public void onReceivedHttpError(WebView v,WebResourceRequest r,WebResourceResponse e){
                if(!r.isForMainFrame()||active==null||!samePage(active.url,r.getUrl().toString()))return;
                int s=e.getStatusCode();if(s>=400)fail(s==429?"RATE_LIMIT":s==403?"ACCESS_RESTRICTED":"HTTP_"+s,
                    s==429?"アクセス制限が表示されました。自動取得を止めました。時間を置いて再開してください。":s==403?"サイト側が取得を制限しています。公式画面で状態を確認してください。":"ページを取得できませんでした（HTTP "+s+"）。",r.getUrl().toString());
            }
            @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e){
                if(r.isForMainFrame()&&active!=null&&samePage(active.url,r.getUrl().toString()))fail("NETWORK","通信できません。保存済みの話はオフラインで読めます。",r.getUrl().toString());
            }
        });
    }
    static void configure(WebView w){
        WebSettings s=w.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);s.setAllowUniversalAccessFromFileURLs(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setJavaScriptCanOpenWindowsAutomatically(false);s.setSupportMultipleWindows(false);s.setMediaPlaybackRequiresUserGesture(true);s.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(w,false);
        w.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
    }
    private static boolean isLogin(String url){String p=Uri.parse(url).getPath();return p!=null&&(p.matches("^/(?:auth/)?(?:login|signin|sign-in)(?:/.*)?$")||p.startsWith("/auth/"));}
    private static boolean samePage(String requested,String loaded){
        if(loaded==null)return false;
        Uri a=Uri.parse(requested),b=Uri.parse(loaded);
        String ah=a.getHost(),bh=b.getHost();if("wuxiaworld.com".equals(ah))ah="www.wuxiaworld.com";if("wuxiaworld.com".equals(bh))bh="www.wuxiaworld.com";
        if(ah==null||!ah.equals(bh))return false;
        String ap=a.getPath()==null?"":a.getPath().replaceAll("/+$",""),bp=b.getPath()==null?"":b.getPath().replaceAll("/+$","");
        if(!ap.equals(bp))return false;
        for(String key:new String[]{"q","page","p"}){String av=a.getQueryParameter(key),bv=b.getQueryParameter(key);String defaultValue=key.equals("q")?"":"1";if(av==null||av.isEmpty())av=defaultValue;if(bv==null||bv.isEmpty())bv=defaultValue;if(!av.equals(bv))return false;}
        return true;
    }
    private static WebResourceResponse empty(){return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));}
    /** Extract only the DOM that the user is actually viewing; never unlock or fetch a hidden body. */
    void extractVisible(WebView page,String url,Callback cb){
        if(destroyed||!Urls.remote(url)||!samePage(url,page.getUrl())||isLogin(url)){cb.done(null,new FetchError("URL","作品または各話のページを開いてから押してください。",url));return;}
        String kind;try{kind=Urls.book(url).equals(Urls.input(url))?"book":"chapter";}catch(Exception e){cb.done(null,new FetchError("URL","作品または各話のページを開いてから押してください。",url));return;}
        page.evaluateJavascript("window.__TSUZUKI_REQUEST__="+Json.obj("kind",kind,"url",url).toString()+";\n"+extractor,raw->{
            if(destroyed||!samePage(url,page.getUrl())){cb.done(null,new FetchError("NAVIGATED","ページが移動しました。表示後にもう一度押してください。",url));return;}
            try{
                Object value=new JSONTokener(raw==null?"null":raw).nextValue();JSONObject data=value instanceof String?new JSONObject((String)value):value instanceof JSONObject?(JSONObject)value:null;
                if(data==null)throw new IllegalArgumentException("empty");JSONObject error=data.optJSONObject("error");
                if(error!=null)cb.done(null,new FetchError(error.optString("code","EXTRACT"),error.optString("message","本文を取得できませんでした。"),url));else cb.done(data,null);
            }catch(Exception e){cb.done(null,new FetchError("EXTRACT","表示中の本文を取り出せませんでした。ページの読み込み完了後にもう一度お試しください。",url));}
        });
    }
    void fetch(String url,String kind,boolean priority,Callback cb){
        if(destroyed){cb.done(null,new FetchError("CLOSED","画面を開き直してください。",url));return;}
        if(!Urls.remote(url)){cb.done(null,new FetchError("URL","対応していないURLです。",url));return;}
        if(active!=null&&active.url.equals(url)&&active.kind.equals(kind)){active.callbacks.add(cb);return;}
        for(Task t:queue)if(t.url.equals(url)&&t.kind.equals(kind)){t.callbacks.add(cb);return;}
        Task t=new Task(url,kind,cb);if(priority)queue.addFirst(t);else queue.addLast(t);pump();
    }
    private void pump(){
        if(destroyed||suspended||active!=null||queue.isEmpty())return;
        long wait=nextAllowed-SystemClock.elapsedRealtime();if(wait>0){handler.postDelayed(this::pump,wait);return;}
        active=queue.removeFirst();Task t=active;
        timeout=()->{if(active==t)fail("TIMEOUT","読み込みに時間がかかっています。通信状態を確認し、再試行してください。",t.url);};handler.postDelayed(timeout,30000);
        view.loadUrl(t.url);
    }
    private void extract(Task t){
        if(active!=t||t.extracting||destroyed)return;
        String loaded=view.getUrl();if(loaded==null||!Urls.remote(loaded)||isLogin(loaded)){fail("AUTH_REQUIRED","公式サイトでログインまたは確認が必要です。",loaded==null?t.url:loaded);return;}
        if(!samePage(t.url,loaded))return;
        t.extracting=true;
        view.evaluateJavascript("window.__TSUZUKI_REQUEST__="+Json.obj("kind",t.kind,"url",t.url).toString()+";\n"+extractor,raw->{
            if(active!=t)return;t.extracting=false;
            try{
                Object value=new JSONTokener(raw==null?"null":raw).nextValue();JSONObject data=value instanceof String?new JSONObject((String)value):value instanceof JSONObject?(JSONObject)value:null;
                if(data==null)throw new IllegalArgumentException("empty");
                JSONObject err=data.optJSONObject("error");
                if(err!=null){
                    String code=err.optString("code","EXTRACT");
                    int attempts="LOADING".equals(code)||"PAGE_LOADING".equals(code)?8:2;
                    if(t.tries++<attempts&&!(code.contains("AUTH")||code.contains("LOCK")||code.contains("ACCESS")||code.contains("RATE")||code.contains("CHALLENGE"))){handler.postDelayed(()->extract(t),900);return;}
                    finish(null,new FetchError(code,err.optString("message","本文を取り出せません。公式画面で確認してください。"),t.url));return;
                }
                finish(data,null);
            }catch(Exception e){if(t.tries++<2){handler.postDelayed(()->extract(t),900);}else finish(null,new FetchError("EXTRACT","本文を取り出せませんでした。サイトの表示変更または認証画面を確認してください。",t.url));}
        });
    }
    private void fail(String code,String message,String url){if(active!=null)finish(null,new FetchError(code,message,url));}
    private void finish(JSONObject data,FetchError error){
        Task t=active;if(t==null)return;active=null;if(timeout!=null)handler.removeCallbacks(timeout);
        view.stopLoading();nextAllowed=SystemClock.elapsedRealtime()+2200;
        if(error!=null&&(error.code.contains("RATE_LIMIT")||error.code.startsWith("ACCESS_")||error.code.contains("CHALLENGE"))){
            // Do not retry or rotate identity after an access restriction.
            nextAllowed=SystemClock.elapsedRealtime()+60000;List<Task> pending=new ArrayList<>(queue);queue.clear();
            for(Task p:pending)for(Callback c:p.callbacks)c.done(null,error);
        }
        for(Callback cb:new ArrayList<>(t.callbacks))cb.done(data,error);pump();
    }
    void suspend(boolean value){suspended=value;if(!value)pump();}
    void cancelQueued(){List<Task> pending=new ArrayList<>(queue);queue.clear();for(Task t:pending)for(Callback cb:t.callbacks)cb.done(null,new FetchError("PAUSED","読み込みを一時停止しました。",t.url));}
    void destroy(){destroyed=true;handler.removeCallbacksAndMessages(null);queue.clear();active=null;view.stopLoading();view.destroy();}
}
