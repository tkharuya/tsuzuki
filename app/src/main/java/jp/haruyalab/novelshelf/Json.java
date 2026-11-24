package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;

final class Json {
    private Json() {}
    static JSONObject obj(Object... pairs) {
        JSONObject o = new JSONObject();
        for (int i=0; i+1<pairs.length; i+=2) put(o, String.valueOf(pairs[i]), pairs[i+1]);
        return o;
    }
    static JSONObject parse(String s) {
        try { return new JSONObject(s == null ? "{}" : s); } catch (Exception e) { return new JSONObject(); }
    }
    static void put(JSONObject o,String k,Object v) {
        try { o.put(k,v == null ? JSONObject.NULL : v); } catch (Exception e) { throw new IllegalArgumentException(e); }
    }
    static JSONObject copy(JSONObject o) { return parse(o == null ? "{}" : o.toString()); }
    static JSONObject merge(JSONObject a,JSONObject b) {
        JSONObject out=copy(a);
        if (b!=null) for(Iterator<String> it=b.keys();it.hasNext();) { String k=it.next();put(out,k,b.opt(k)); }
        return out;
    }
    static JSONArray arr(JSONObject o,String k) { JSONArray a=o.optJSONArray(k);return a==null?new JSONArray():a; }
    static String limit(String s,int n) { return s==null?"":s.length()>n?s.substring(0,n):s; }
}
