package jp.haruyalab.novelshelf;

/** Pure-JVM checks of the actual production URL boundary. No Android mocks. */
public final class UrlsTest {
    private static int checks;
    private static void eq(Object actual,Object expected){checks++;if(!expected.equals(actual))throw new AssertionError("expected "+expected+" but got "+actual);}
    private static void denied(String url){checks++;try{Urls.input(url);throw new AssertionError("accepted "+url);}catch(IllegalArgumentException expected){}}
    public static void main(String[] args){
        eq(Urls.input("https://kakuyomu.jp/works/123/episodes/456?from=share#body"),"https://kakuyomu.jp/works/123/episodes/456");
        eq(Urls.book("https://kakuyomu.jp/works/123/episodes/456"),"https://kakuyomu.jp/works/123");
        eq(Urls.input("読んでみて\nhttps://ncode.syosetu.com/n1234ab/38/\nなろう"),"https://ncode.syosetu.com/n1234ab/38/");
        eq(Urls.input("http://ncode.syosetu.com/N1234AB/"),"https://ncode.syosetu.com/n1234ab/");
        eq(Urls.input("kakuyomu.jp/works/123"),"https://kakuyomu.jp/works/123");
        eq(Urls.book("https://ncode.syosetu.com/n1234ab/?p=9"),"https://ncode.syosetu.com/n1234ab/");
        eq(Urls.sameBook("https://ncode.syosetu.com/n1234ab/1/","https://ncode.syosetu.com/n1234ab/2/"),true);
        eq(Urls.sameBook("https://kakuyomu.jp/works/123/episodes/1","https://kakuyomu.jp/works/124/episodes/1"),false);
        eq(Urls.official("https://syosetu.com/login/input/"),true);
        eq(Urls.official("https://kakuyomu.jp/auth/login"),true);
        eq(Urls.official("https://kakuyomu.jp.evil.example/works/123"),false);
        eq(Urls.official("https://evilsyosetu.com/"),false);
        eq(Urls.official("https://evil.example@kakuyomu.jp/"),false);
        eq(Urls.official("https://kakuyomu.jp:444/"),false);
        eq(Urls.remote("https://127.0.0.1/"),false);
        eq(Urls.remote("http://kakuyomu.jp/works/123"),false);
        eq(Urls.remote("file:///etc/passwd"),false);
        eq(Urls.remote("https://yomou.syosetu.com/search.php?word=x"),true);
        denied("file:///sdcard/book.txt");denied("https://example.com/works/123");denied("https://kakuyomu.jp/works/123/javascript");denied("https://ncode.syosetu.com/../../etc/passwd");
        eq(Urls.id("https://kakuyomu.jp/works/123"),Urls.id("https://kakuyomu.jp/works/123"));
        eq(Urls.id("https://kakuyomu.jp/works/123").equals(Urls.id("https://kakuyomu.jp/works/124")),false);
        String w="https://www.wuxiaworld.com/novel/warlock-of-the-magus-world";
        eq(Urls.input(w),w);
        eq(Urls.input("http://wuxiaworld.com/novel/warlock-of-the-magus-world/?from=share"),w);
        eq(Urls.input("wuxiaworld.com/novel/warlock-of-the-magus-world/wmw-chapter-1"),w+"/wmw-chapter-1");
        eq(Urls.input("この章を読む\n"+w+"/wmw-chapter-2#chapter-content"),w+"/wmw-chapter-2");
        eq(Urls.book(w+"/wmw-chapter-1?share=1"),w);
        eq(Urls.book(w+"/wmw-afterword"),w);
        eq(Urls.source(w),"wuxiaworld");
        eq(Urls.source("https://wuxiaworld.com/novel/warlock-of-the-magus-world"),"wuxiaworld");
        eq(Urls.id(w),Urls.id(Urls.book("https://wuxiaworld.com/novel/warlock-of-the-magus-world/wmw-chapter-2")));
        eq(Urls.sameBook(w+"/wmw-chapter-1",w+"/wmw-chapter-2"),true);
        eq(Urls.sameBook(w+"/wmw-chapter-1","https://www.wuxiaworld.com/novel/other-work/other-chapter-1"),false);
        eq(Urls.remote(w),true);
        eq(Urls.remote("https://wuxiaworld.com/novel/warlock-of-the-magus-world"),true);
        eq(Urls.official("https://cdn.wuxiaworld.com/images/test.png"),true);
        eq(Urls.remote("https://cdn.wuxiaworld.com/novel/warlock-of-the-magus-world"),false);
        eq(Urls.official("https://wuxiaworld.com.evil.example/"),false);
        eq(Urls.official("https://evilwuxiaworld.com/"),false);
        eq(Urls.official("https://evil@www.wuxiaworld.com/"),false);
        eq(Urls.remote("https://www.wuxiaworld.com:444/novel/test"),false);
        denied("https://www.wuxiaworld.com/novel/test/chapter/extra");
        denied("https://www.wuxiaworld.com/profile/admin");
        denied("https://api.wuxiaworld.com/novel/test/chapter-1");
        denied("https://www.wuxiaworld.com:444/novel/test");
        denied("https://evil@www.wuxiaworld.com/novel/test");
        denied("https://www.wuxiaworld.com/novel/test%2fchapter-1");
        denied("https://www.wuxiaworld.com/novel/test/UPPERCASE");
        denied("https://www.wuxiaworld.com/novel/test/with_underscore");
        System.out.println("URL checks passed: "+checks);
    }
}
