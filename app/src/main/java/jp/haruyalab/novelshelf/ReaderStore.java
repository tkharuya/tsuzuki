package jp.haruyalab.novelshelf;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.Writer;
import java.util.UUID;

/** Device-local storage. Bodies are separate rows so long novels are streamed on export. */
final class ReaderStore extends SQLiteOpenHelper {
    ReaderStore(Context context) { super(context,"tsuzuki.db",null,1);setWriteAheadLoggingEnabled(true); }
    @Override public void onConfigure(SQLiteDatabase db) { super.onConfigure(db);db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE books(id TEXT PRIMARY KEY,data TEXT NOT NULL,updated INTEGER NOT NULL,last_read INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE chapters(book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,url TEXT NOT NULL,title TEXT NOT NULL,idx INTEGER NOT NULL,section TEXT NOT NULL DEFAULT '',body TEXT,read_flag INTEGER NOT NULL DEFAULT 0,fetched INTEGER NOT NULL DEFAULT 0,active INTEGER NOT NULL DEFAULT 1,seen_snapshot TEXT NOT NULL DEFAULT '',PRIMARY KEY(book_id,url))");
        db.execSQL("CREATE INDEX chapter_order ON chapters(book_id,idx)");
        db.execSQL("CREATE TABLE positions(book_id TEXT PRIMARY KEY REFERENCES books(id) ON DELETE CASCADE,data TEXT NOT NULL)");
        db.execSQL("CREATE TABLE marks(id TEXT PRIMARY KEY,book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,data TEXT NOT NULL,created INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE downloads(book_id TEXT PRIMARY KEY REFERENCES books(id) ON DELETE CASCADE,state TEXT NOT NULL,error TEXT NOT NULL DEFAULT '',updated INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) { }
    synchronized JSONObject rawBook(String id) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM books WHERE id=?",new String[]{id})) { return c.moveToFirst()?Json.parse(c.getString(0)):null; }
    }
    synchronized JSONObject book(String id) {
        JSONObject b;long updated,last;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT data,updated,last_read FROM books WHERE id=?",new String[]{id})) {
            if(!c.moveToFirst())return null;b=Json.parse(c.getString(0));updated=c.getLong(1);last=c.getLong(2);
        }
        int count=0,cached=0,read=0,minIndex=-1,maxIndex=-1,distinctIndices=0;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*),SUM(CASE WHEN body IS NOT NULL THEN 1 ELSE 0 END),SUM(read_flag),MIN(idx),MAX(idx),COUNT(DISTINCT idx) FROM chapters WHERE book_id=? AND active=1",new String[]{id})) {
            if(c.moveToFirst()){count=c.getInt(0);cached=c.getInt(1);read=c.getInt(2);minIndex=c.getInt(3);maxIndex=c.getInt(4);distinctIndices=c.getInt(5);}
        }
        JSONObject p=position(id);int total=Math.max(b.optInt("total"),count);
        Json.put(b,"id",id);Json.put(b,"total",total);Json.put(b,"downloaded",cached);Json.put(b,"readCount",read);
        Json.put(b,"currentIndex",p.optInt("index",0));Json.put(b,"position",p);Json.put(b,"updatedAt",updated);Json.put(b,"lastReadAt",last);
        boolean rangeComplete=count>0&&cached==count&&count==total&&distinctIndices==count&&minIndex==0&&maxIndex==total-1;
        Json.put(b,"rangeComplete",rangeComplete);Json.put(b,"complete",b.optBoolean("tocComplete")&&rangeComplete);
        return b;
    }
    synchronized JSONArray books() {
        JSONArray a=new JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM books ORDER BY last_read DESC,updated DESC",null)) { while(c.moveToNext())a.put(book(c.getString(0))); }
        return a;
    }
    synchronized void putBook(JSONObject b) {
        String id=b.optString("id");if(id.isEmpty())throw new IllegalArgumentException("作品IDがありません");
        JSONObject merged=Json.merge(rawBook(id),b);for(String k:new String[]{"position","downloaded","readCount","currentIndex","lastReadAt","complete","rangeComplete"})merged.remove(k);
        ContentValues v=new ContentValues();v.put("data",merged.toString());v.put("updated",System.currentTimeMillis());
        SQLiteDatabase db=getWritableDatabase();if(db.update("books",v,"id=?",new String[]{id})==0){v.put("id",id);db.insertOrThrow("books",null,v);}
    }
    synchronized void mergeChapters(String bookId,JSONArray items,int fallbackOffset) {
        mergeChapters(bookId,items,fallbackOffset,null);
    }
    synchronized void mergeChapters(String bookId,JSONArray items,int fallbackOffset,String snapshot) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            for(int i=0;i<items.length();i++){
                JSONObject p=items.optJSONObject(i);if(p==null)continue;String url=p.optString("url");if(url.isEmpty())continue;
                ContentValues v=new ContentValues();v.put("title",Json.limit(p.optString("title","第"+(fallbackOffset+i+1)+"話"),1000));
                v.put("idx",p.has("index")?p.optInt("index",fallbackOffset+i):fallbackOffset+i);v.put("section",Json.limit(p.optString("section"),1000));v.put("active",1);if(snapshot!=null)v.put("seen_snapshot",snapshot);
                if(db.update("chapters",v,"book_id=? AND url=?",new String[]{bookId,url})==0){v.put("book_id",bookId);v.put("url",url);db.insertOrThrow("chapters",null,v);}
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    synchronized void finishTocSnapshot(String bookId,String snapshot) {
        // Keep previous body bytes for recovery; only the complete new TOC changes current counts.
        ContentValues v=new ContentValues();v.put("active",0);getWritableDatabase().update("chapters",v,"book_id=? AND seen_snapshot<>?",new String[]{bookId,snapshot});
    }
    synchronized JSONArray chapters(String bookId) {
        JSONArray a=new JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT url,title,idx,section,body IS NOT NULL,read_flag FROM chapters WHERE book_id=? AND active=1 ORDER BY idx,url",new String[]{bookId})) {
            while(c.moveToNext())a.put(Json.obj("url",c.getString(0),"title",c.getString(1),"index",c.getInt(2),"section",c.getString(3),"cached",c.getInt(4)!=0,"read",c.getInt(5)!=0));
        }
        return a;
    }
    synchronized JSONObject chapterMeta(String bookId,String url) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT title,idx,section,body IS NOT NULL,read_flag FROM chapters WHERE book_id=? AND url=? AND active=1",new String[]{bookId,url})) {
            if(!c.moveToFirst())return null;
            return Json.obj("url",url,"title",c.getString(0),"index",c.getInt(1),"section",c.getString(2),"cached",c.getInt(3)!=0,"read",c.getInt(4)!=0);
        }
    }
    synchronized JSONObject chapter(String bookId,String url) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT body,idx,title FROM chapters WHERE book_id=? AND url=? AND active=1",new String[]{bookId,url})) {
            if(!c.moveToFirst()||c.isNull(0))return null;JSONObject d=Json.parse(c.getString(0));Json.put(d,"index",c.getInt(1));Json.put(d,"title",c.getString(2));
            Json.put(d,"bookId",bookId);Json.put(d,"cached",true);return d;
        }
    }
    synchronized void putChapter(String bookId,JSONObject d) {
        String url=d.optString("url");JSONObject meta=chapterMeta(bookId,url);
        if(meta==null)mergeChapters(bookId,new JSONArray().put(Json.obj("url",url,"title",d.optString("title"),"index",d.optInt("index",chapterCount(bookId)))),chapterCount(bookId));
        ContentValues v=new ContentValues();v.put("body",d.toString());v.put("fetched",System.currentTimeMillis());
        v.put("title",Json.limit(d.optString("title",meta==null?"":meta.optString("title")),1000));v.put("idx",d.optInt("index",meta==null?0:meta.optInt("index")));
        getWritableDatabase().update("chapters",v,"book_id=? AND url=?",new String[]{bookId,url});
    }
    synchronized int chapterCount(String id) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM chapters WHERE book_id=? AND active=1",new String[]{id})){return c.moveToFirst()?c.getInt(0):0;}
    }
    synchronized int snapshotChapterCount(String id,String snapshot) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM chapters WHERE book_id=? AND seen_snapshot=?",new String[]{id,snapshot})){return c.moveToFirst()?c.getInt(0):0;}
    }
    synchronized JSONObject nextMissing(String id) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT url,title,idx FROM chapters WHERE book_id=? AND active=1 AND body IS NULL ORDER BY idx LIMIT 1",new String[]{id})){
            return c.moveToFirst()?Json.obj("url",c.getString(0),"title",c.getString(1),"index",c.getInt(2)):null;
        }
    }
    synchronized JSONObject savedPrefixEnd(String id) {
        JSONObject last=null;int expected=0;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT url,idx,body IS NOT NULL FROM chapters WHERE book_id=? AND active=1 ORDER BY idx,url",new String[]{id})){
            while(c.moveToNext()){
                if(c.getInt(1)!=expected||c.getInt(2)==0)break;
                last=Json.obj("url",c.getString(0),"index",expected);expected++;
            }
        }return last;
    }
    synchronized void setRead(String id,String url,boolean read) {
        ContentValues v=new ContentValues();v.put("read_flag",read?1:0);getWritableDatabase().update("chapters",v,"book_id=? AND url=?",new String[]{id,url});
    }
    synchronized void savePosition(String id,JSONObject p) {
        if(rawBook(id)==null)return;
        ContentValues v=new ContentValues();v.put("book_id",id);v.put("data",p.toString());getWritableDatabase().insertWithOnConflict("positions",null,v,SQLiteDatabase.CONFLICT_REPLACE);
        ContentValues t=new ContentValues();t.put("last_read",System.currentTimeMillis());getWritableDatabase().update("books",t,"id=?",new String[]{id});
    }
    synchronized JSONObject position(String id) {
        JSONObject p;try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM positions WHERE book_id=?",new String[]{id})){p=c.moveToFirst()?Json.parse(c.getString(0)):new JSONObject();}
        JSONObject current=chapterMeta(id,p.optString("chapterUrl"));if(current!=null)Json.put(p,"index",current.optInt("index"));return p;
    }
    synchronized JSONObject addMark(String bookId,JSONObject source) {
        JSONObject m=Json.copy(source);String id=UUID.randomUUID().toString();long now=System.currentTimeMillis();
        Json.put(m,"id",id);Json.put(m,"bookId",bookId);Json.put(m,"createdAt",now);JSONObject book=book(bookId);
        Json.put(m,"bookTitle",book==null?"":book.optString("title"));
        ContentValues v=new ContentValues();v.put("id",id);v.put("book_id",bookId);v.put("data",m.toString());v.put("created",now);getWritableDatabase().insertOrThrow("marks",null,v);return m;
    }
    synchronized JSONArray marks(String bookId) {
        JSONArray a=new JSONArray();String sql="SELECT data FROM marks"+(bookId==null||bookId.isEmpty()?"":" WHERE book_id=?")+" ORDER BY created DESC";
        try(Cursor c=getReadableDatabase().rawQuery(sql,bookId==null||bookId.isEmpty()?null:new String[]{bookId})){while(c.moveToNext())a.put(Json.parse(c.getString(0)));}return a;
    }
    synchronized void deleteMark(String id) {getWritableDatabase().delete("marks","id=?",new String[]{id});}
    synchronized void deleteBook(String id) {getWritableDatabase().delete("books","id=?",new String[]{id});}
    synchronized JSONObject details(String id) {
        JSONObject b=book(id);if(b==null)throw new IllegalArgumentException("本棚に作品がありません");
        return Json.obj("book",b,"chapters",chapters(id),"position",position(id),"marks",marks(id));
    }
    synchronized void setJob(String id,String state,String error) {
        if(rawBook(id)==null)return;
        ContentValues v=new ContentValues();v.put("book_id",id);v.put("state",state);v.put("error",error==null?"":error);v.put("updated",System.currentTimeMillis());getWritableDatabase().insertWithOnConflict("downloads",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    synchronized void pauseInterruptedJobs() {ContentValues v=new ContentValues();v.put("state","paused");getWritableDatabase().update("downloads",v,"state IN ('downloading','queued','running')",null);}
    synchronized JSONObject job(String id) {
        JSONObject b=book(id);if(b==null)return null;String state="idle",error="";
        try(Cursor c=getReadableDatabase().rawQuery("SELECT state,error FROM downloads WHERE book_id=?",new String[]{id})){if(c.moveToFirst()){state=c.getString(0);error=c.getString(1);}}
        return Json.obj("bookId",id,"title",b.optString("title"),"total",b.optInt("total"),"completed",b.optInt("downloaded"),"state",state,"error",error);
    }
    synchronized JSONArray jobs() {
        JSONArray a=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT book_id FROM downloads ORDER BY updated DESC",null)){while(c.moveToNext())a.put(job(c.getString(0)));}return a;
    }
    void exportText(String bookId,Writer writer,boolean partial) throws IOException {
        JSONObject b=book(bookId);if(b==null||(!partial&&!b.optBoolean("complete")))throw new IOException("全話保存が完了していません。保存を完了してから書き出してください。");
        if(partial&&b.optInt("downloaded")==0)throw new IOException("保存済みの本文がありません。");
        writer.write(b.optString("title")+"\n著者："+b.optString("author")+"\n出典："+b.optString("url")+"\n\n");
        if(partial)writer.write("［保存済みの章のみ："+b.optInt("downloaded")+"話／登録総話数："+b.optInt("total")+"話"+(b.optBoolean("tocComplete")?"":"（目次は一部未確認）")+"。未保存の章は含みません。］\n\n");
        try(Cursor c=getReadableDatabase().rawQuery("SELECT body,title,idx,section FROM chapters WHERE book_id=? AND active=1"+(partial?" AND body IS NOT NULL":"")+" ORDER BY idx,url",new String[]{bookId})) {
            String section="";
            while(c.moveToNext()){
                if(c.isNull(0))throw new IOException("未保存の話があります。");
                String nextSection=c.getString(3);if(!nextSection.isEmpty()&&!nextSection.equals(section)){writer.write("\n【"+nextSection+"】\n");section=nextSection;}
                writer.write("\n────────────────────\n第"+(c.getInt(2)+1)+"話　"+c.getString(1)+"\n────────────────────\n\n");
                JSONObject chapter=Json.parse(c.getString(0));
                writeParagraphs(writer,Json.arr(chapter,"preface"),"［前書き］");writeParagraphs(writer,Json.arr(chapter,"paragraphs"),"");writeParagraphs(writer,Json.arr(chapter,"afterword"),"［後書き］");
            }
        }
        writer.flush();
    }
    private void writeParagraphs(Writer w,JSONArray a,String label)throws IOException {
        if(a.length()==0)return;if(!label.isEmpty())w.write(label+"\n\n");
        for(int i=0;i<a.length();i++){JSONObject p=a.optJSONObject(i);w.write(p==null?a.optString(i):p.optString("text"));w.write("\n\n");}
    }
}
