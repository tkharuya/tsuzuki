package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Shared validation for both visual reading and background speech. */
final class ChapterContent {
    private ChapterContent() {}
    static JSONObject clean(ReaderStore store,String id,String url,JSONObject data){
        JSONArray paragraphs=cleanParagraphs(Json.arr(data,"paragraphs"));if(paragraphs.length()==0)throw new IllegalArgumentException("本文がありません。ログイン・公開状態を公式画面で確認してください。");
        JSONObject book=store.rawBook(id),meta=store.chapterMeta(id,url);
        boolean officialIndex=book!=null&&"wuxiaworld".equals(book.optString("source"))&&data.has("index")&&data.optInt("index",-1)>=0;
        int index=Math.max(0,officialIndex?data.optInt("index"):meta==null?data.optInt("index",store.chapterCount(id)):meta.optInt("index"));
        String next=sameBookLink(url,data.optString("nextUrl")),prev=sameBookLink(url,data.optString("prevUrl"));
        boolean newNext=!next.isEmpty()&&store.chapterMeta(id,next)==null;
        JSONObject out=Json.obj("bookId",id,"url",url,"title",Json.limit(data.optString("title",meta==null?"第"+(index+1)+"話":meta.optString("title")),1000),"index",index,"paragraphs",paragraphs,"preface",cleanParagraphs(Json.arr(data,"preface")),"afterword",cleanParagraphs(Json.arr(data,"afterword")),"nextUrl",next,"prevUrl",prev,"cached",true);
        if(out.toString().getBytes(StandardCharsets.UTF_8).length>1500000)throw new IllegalArgumentException("この話は長すぎるため、欠落を避けて保存を停止しました。公式画面で読んでください。");
        if(book!=null){
            int total=Math.max(book.optInt("total"),Math.max(data.optInt("total"),index+(next.isEmpty()?1:2)));
            boolean wuxiaworld="wuxiaworld".equals(book.optString("source"));
            int reportedTotal=wuxiaworld&&data.optInt("total")>0?data.optInt("total"):book.optInt("reportedTotal");
            // A newly discovered chapter cannot extend a previously complete
            // snapshot: intermediate chapters may still be missing.
            if(meta==null||newNext||total>book.optInt("total")||meta.optInt("index")!=index||reportedTotal!=book.optInt("reportedTotal")){
                store.putBook(Json.obj("id",id,"total",total,"reportedTotal",reportedTotal,"tocComplete",false));
                JSONObject job=store.job(id);if(job!=null&&"complete".equals(job.optString("state"))){store.setJob(id,"paused","");}
            }
        }
        if(newNext)store.mergeChapters(id,new JSONArray().put(Json.obj("url",next,"title","第"+(index+2)+"話","index",index+1)),index+1);
        return out;
    }
    private static JSONArray cleanParagraphs(JSONArray in){
        if(in.length()>50000)throw new IllegalArgumentException("この話は段落数が多すぎるため、欠落を避けて保存を停止しました。公式画面で確認してください。");
        int length=0;JSONArray out=new JSONArray();for(int i=0;i<in.length();i++){JSONObject p=in.optJSONObject(i);String text=p==null?in.optString(i):p.optString("text");
            String html=p==null?"":p.optString("html");length+=text.length()+html.length();if(length>800000)throw new IllegalArgumentException("この話は長すぎるため、欠落を避けて保存を停止しました。公式画面で確認してください。");
            JSONObject v=Json.obj("text",text);if(p!=null&&p.has("html"))Json.put(v,"html",safeRuby(html));out.put(v);}return out;
    }
    private static String safeRuby(String source){
        StringBuilder out=new StringBuilder();String s=source;
        for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='<'){int end=s.indexOf('>',i+1);if(end>i&&end-i<12){String tag=s.substring(i,end+1).toLowerCase(Locale.ROOT);if(tag.matches("</?(ruby|rt|rp)>")){out.append(tag);i=end;continue;}}out.append("&lt;");}else if(c=='>')out.append("&gt;");else out.append(c);}return out.toString();
    }
    private static String sameBookLink(String base,String url){try{String u=Urls.input(url);return Urls.sameBook(base,u)&&!u.equals(base)?u:"";}catch(Exception e){return "";}}
    static JSONObject withLinks(ReaderStore store,String id,JSONObject body){
        JSONObject out=Json.copy(body),book=store.rawBook(id);JSONArray all=store.chapters(id);String url=out.optString("url");
        boolean complete=book!=null&&book.optBoolean("tocComplete");int index=out.optInt("index");
        for(int i=0;i<all.length();i++)if(all.optJSONObject(i).optString("url").equals(url)){
            JSONObject prev=i>0?all.optJSONObject(i-1):null,next=i+1<all.length()?all.optJSONObject(i+1):null;
            if(complete){Json.put(out,"prevUrl",prev==null?"":prev.optString("url"));Json.put(out,"nextUrl",next==null?"":next.optString("url"));}
            else{
                // Saved rows can be sparse. Preserve the page's actual links;
                // only fill a missing link from a strictly adjacent index.
                if(out.optString("prevUrl").isEmpty()&&prev!=null&&prev.optInt("index")==index-1)Json.put(out,"prevUrl",prev.optString("url"));
                if(out.optString("nextUrl").isEmpty()&&next!=null&&next.optInt("index")==index+1)Json.put(out,"nextUrl",next.optString("url"));
            }break;
        }return out;
    }
}
