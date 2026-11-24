package jp.haruyalab.novelshelf;

import java.net.URI;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Urls {
    private static final Pattern K=Pattern.compile("^/works/(\\d+)(?:/episodes/(\\d+))?/?$");
    private static final Pattern N=Pattern.compile("^/(n[0-9]{1,5}[a-z]{1,2})(?:/(\\d+))?/?$",Pattern.CASE_INSENSITIVE);
    private static final Pattern W=Pattern.compile("^/novel/([a-z0-9][a-z0-9-]{0,199})(?:/([a-z0-9][a-z0-9-]{0,199}))?/?$");
    private static final Pattern FOUND=Pattern.compile("https?://(?:kakuyomu\\.jp|ncode\\.syosetu\\.com|(?:www\\.)?wuxiaworld\\.com)/[^\\s<>\"　]+",Pattern.CASE_INSENSITIVE);
    static String input(String raw) {
        String value=raw==null?"":raw.trim();Matcher m=FOUND.matcher(value);if(m.find())value=m.group();
        if(value.startsWith("kakuyomu.jp/")||value.startsWith("ncode.syosetu.com/")||value.startsWith("wuxiaworld.com/")||value.startsWith("www.wuxiaworld.com/"))value="https://"+value;
        URI u=URI.create(value);String host=u.getHost();if(host==null||u.getUserInfo()!=null||!("https".equalsIgnoreCase(u.getScheme())||"http".equalsIgnoreCase(u.getScheme()))||(u.getPort()!=-1&&u.getPort()!=443&&!("http".equalsIgnoreCase(u.getScheme())&&u.getPort()==80)))throw new IllegalArgumentException("カクヨム・なろう・Wuxiaworldの作品または各話URLを入力してください。");
        host=host.toLowerCase(Locale.ROOT);String path=u.getRawPath()==null?"":u.getRawPath();Matcher k=K.matcher(path),n=N.matcher(path),w=W.matcher(path);
        if(host.equals("kakuyomu.jp")&&k.matches())return "https://kakuyomu.jp/works/"+k.group(1)+(k.group(2)==null?"":"/episodes/"+k.group(2));
        if(host.equals("ncode.syosetu.com")&&n.matches())return "https://ncode.syosetu.com/"+n.group(1).toLowerCase(Locale.ROOT)+"/"+(n.group(2)==null?"":n.group(2)+"/");
        if((host.equals("www.wuxiaworld.com")||host.equals("wuxiaworld.com"))&&w.matches())return "https://www.wuxiaworld.com/novel/"+w.group(1)+(w.group(2)==null?"":"/"+w.group(2));
        throw new IllegalArgumentException("対応している作品URLではありません。カクヨム・小説家になろう・Wuxiaworldの作品ページを指定してください。");
    }
    static String book(String raw) {
        String url=input(raw);URI u=URI.create(url);Matcher k=K.matcher(u.getPath()),n=N.matcher(u.getPath()),w=W.matcher(u.getPath());
        if(k.matches())return "https://kakuyomu.jp/works/"+k.group(1);
        if(n.matches())return "https://ncode.syosetu.com/"+n.group(1).toLowerCase(Locale.ROOT)+"/";
        if(w.matches())return "https://www.wuxiaworld.com/novel/"+w.group(1);
        throw new IllegalArgumentException("作品URLを確認してください。");
    }
    static String source(String url) {if(url.startsWith("local:"))return "local";String h=URI.create(url).getHost();if("kakuyomu.jp".equals(h))return "kakuyomu";if("www.wuxiaworld.com".equals(h)||"wuxiaworld.com".equals(h))return "wuxiaworld";return "narou";}
    static String id(String url) {
        try {byte[] h=MessageDigest.getInstance("SHA-256").digest(url.getBytes("UTF-8"));StringBuilder b=new StringBuilder();for(int i=0;i<12;i++)b.append(String.format(Locale.ROOT,"%02x",h[i]));return source(url)+"_"+b;}catch(Exception e){throw new IllegalStateException(e);}
    }
    static boolean sameBook(String a,String b){try{return book(a).equals(book(b));}catch(Exception e){return false;}}
    static boolean remote(String raw){try{URI u=URI.create(raw);String h=u.getHost();return "https".equals(u.getScheme())&&u.getUserInfo()==null&&(u.getPort()==-1||u.getPort()==443)&&("kakuyomu.jp".equals(h)||"ncode.syosetu.com".equals(h)||"yomou.syosetu.com".equals(h)||"www.wuxiaworld.com".equals(h)||"wuxiaworld.com".equals(h));}catch(Exception e){return false;}}
    static boolean official(String raw){try{URI u=URI.create(raw);String h=u.getHost();if(h==null||!"https".equals(u.getScheme())||u.getUserInfo()!=null||(u.getPort()!=-1&&u.getPort()!=443))return false;return h.equals("kakuyomu.jp")||h.endsWith(".kakuyomu.jp")||h.equals("syosetu.com")||h.endsWith(".syosetu.com")||h.equals("wuxiaworld.com")||h.endsWith(".wuxiaworld.com");}catch(Exception e){return false;}}
}
