package jp.haruyalab.novelshelf;

import android.content.Context;
import android.os.Handler;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.json.JSONObject;
import jp.hiroshiba.voicevoxcore.AudioQuery;

/** Exercises production installer code with a controlled HTTP boundary and actual release dictionary. */
public final class OfflineVoicePacksTest {
    private static int checks;
    private static byte[] fixture;
    private static String mode = "normal";
    private static int requests;
    private static final List<String> ranges = new ArrayList<>();
    private static final Class<?> SPEC;
    static {
        try { SPEC = Class.forName("jp.haruyalab.novelshelf.OfflineVoicePacks$Spec"); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); checks++; System.out.println("PASS " + name); }
    private static Object call(Object target, String method, Class<?>[] types, Object... args) throws Exception {
        Class<?> type = target instanceof Class ? (Class<?>) target : target.getClass();
        Method m = type.getDeclaredMethod(method, types); m.setAccessible(true);
        try { return m.invoke(target instanceof Class ? null : target, args); }
        catch (InvocationTargetException e) { Throwable cause=e.getCause(); if(cause instanceof Exception)throw (Exception)cause; throw (Error)cause; }
    }
    private static Object field(Object target, String name) throws Exception {
        Class<?> type=target instanceof Class ? (Class<?>)target : target.getClass();
        Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(target instanceof Class ? null : target);
    }
    private static void set(String name,Object value) throws Exception {
        Field f=OfflineVoicePacks.class.getDeclaredField(name);f.setAccessible(true);f.set(null,value);
    }
    private static String digest(byte[] bytes) throws Exception {
        StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))s.append(String.format("%02x",b&255));return s.toString();
    }
    private static Object spec(String name) throws Exception {
        Constructor<?> c=SPEC.getDeclaredConstructor(String.class,String.class,long.class,String.class);c.setAccessible(true);
        return c.newInstance(name,"https://github.com/VOICEVOX/test/"+name,(long)fixture.length,digest(fixture));
    }
    private static void resetHttp(String next) { mode=next;requests=0;ranges.clear(); }
    private static final class MockHttp extends HttpURLConnection {
        private int begin;
        private final int request;
        MockHttp(URL url) { super(url);request=++requests; }
        @Override public int getResponseCode() {
            if (connected) return mode.equals("redirect") ? 302 : begin > 0 ? 206 : 200;
            String range=getRequestProperty("Range");
            begin=range==null ? 0 : Integer.parseInt(range.substring(6,range.length()-1));
            if (!connected) { ranges.add(range==null ? "none" : range);connected=true; }
            if(mode.equals("redirect"))return 302;
            return begin>0 ? 206 : 200;
        }
        @Override public String getHeaderField(String name) {
            if(name.equals("Location"))return "https://untrusted.example/model";
            if(name.equals("Content-Range"))return "bytes "+(mode.equals("bad-range") ? begin+1 : begin)+"-"+(fixture.length-1)+"/"+fixture.length;
            return null;
        }
        @Override public long getContentLengthLong(){return fixture.length-begin;}
        @Override public InputStream getInputStream() {
            byte[] source=fixture.clone();
            if(mode.equals("corrupt"))source[source.length-1]^=0x20;
            int end=mode.equals("truncate")&&request==1 ? begin+(fixture.length-begin)/2 : fixture.length;
            return new ByteArrayInputStream(Arrays.copyOfRange(source,begin,end));
        }
        @Override public void disconnect(){ }
        @Override public boolean usingProxy(){return false;}
        @Override public void connect(){connected=true;}
    }

    private static File complete(OfflineVoicePacks packs,Object spec) throws Exception {
        return (File)call(packs,"completeFile",new Class<?>[]{SPEC},spec);
    }
    private static void download(OfflineVoicePacks packs,Object spec) throws Exception {
        call(packs,"download",new Class<?>[]{SPEC,File.class},spec,complete(packs,spec));
    }
    private static void rejected(OfflineVoicePacks packs,Object spec,String name) throws Exception {
        boolean failed=false;try{download(packs,spec);}catch(IOException e){failed=true;}
        check(failed&&!complete(packs,spec).exists(),name);
    }

    public static void main(String[] args) throws Exception {
        Path research=Path.of(args[0]).toAbsolutePath(), temporary=Files.createTempDirectory(research,"installer-test-");
        URL.setURLStreamHandlerFactory(protocol -> protocol.equals("https") ? new URLStreamHandler(){
            @Override protected URLConnection openConnection(URL url){return new MockHttp(url);}
        } : null);
        Path runtime=temporary.resolve("native");Files.createDirectories(runtime);
        Files.createLink(runtime.resolve("libvoicevox_onnxruntime.so"),research.resolve("linux-x64/libvoicevox_onnxruntime.so.1.23.2"));
        Context context=new Context(temporary.resolve("private").toFile(),runtime.toString());
        OfflineVoicePacks packs=new OfflineVoicePacks(context);
        Path data=temporary.resolve("private/voicevox-0.17.1-v1");
        Files.createDirectories(data.resolve("models"));Files.createDirectories(data.resolve("downloads"));
        check(!packs.isReady() && packs.snapshot().optLong("totalBytes")==565360558L,"empty pack has pinned size and is not ready");

        packs.start(null);packs.cancel();
        check(!packs.beginDownload(),"cancel before service arrival cannot start a download");
        List<String> states=new ArrayList<>();OfflineVoicePacks.setListener(s->states.add(s.optString("status")));
        Handler.drain();check(!states.contains("downloading"),"stale queued progress is not delivered after pause");
        set("cancelled",false);
        fixture=new byte[131079];for(int i=0;i<fixture.length;i++)fixture[i]=(byte)(i*13+i/47);

        Object resume=spec("resume.vvm");
        Files.write(data.resolve("downloads/resume.vvm.part"),Arrays.copyOf(fixture,4099));
        resetHttp("normal");download(packs,resume);
        check("bytes=4099-".equals(ranges.get(0)) && Arrays.equals(fixture,Files.readAllBytes(complete(packs,resume).toPath())),"HTTP Range resumes exact partial bytes");
        check((Boolean)call(OfflineVoicePacks.class,"verified",new Class<?>[]{File.class,SPEC},complete(packs,resume),resume),"verified receipt follows SHA256 and atomic promotion");

        Object interrupted=spec("interrupted.vvm");resetHttp("truncate");download(packs,interrupted);
        check(requests==2 && !ranges.get(1).equals("none"),"interrupted response retries from stored partial bytes");
        Object corrupt=spec("corrupt.vvm");resetHttp("corrupt");rejected(packs,corrupt,"SHA256 mismatch never promotes corrupt bytes");
        check(!Files.exists(data.resolve("downloads/corrupt.vvm.part")),"bad digest clears unusable partial file");
        Object range=spec("range.vvm");Files.write(data.resolve("downloads/range.vvm.part"),Arrays.copyOf(fixture,19));
        resetHttp("bad-range");rejected(packs,range,"mismatched Content-Range is rejected");
        Object redirect=spec("redirect.vvm");resetHttp("redirect");rejected(packs,redirect,"redirect to an unapproved host is rejected");
        resetHttp("normal");packs.cancel();
        boolean cancelled=false;try{download(packs,spec("cancelled.vvm"));}catch(InterruptedIOException e){cancelled=true;}
        check(cancelled&&requests==0,"cancelled installer makes no network request");set("cancelled",false);

        // Extract the exact upstream dictionary downloaded with its pinned archive SHA256.
        call(packs,"extractDictionary",new Class<?>[]{File.class},research.resolve("dictionary.tar.gz").toFile());
        check((Boolean)call(packs,"dictionaryValid",new Class<?>[]{}),"actual upstream dictionary extracts all nine files and records receipts");
        Path bad=temporary.resolve("unsafe.tar.gz");writeUnsafeTar(bad);
        boolean unsafe=false;try{call(packs,"extractDictionary",new Class<?>[]{File.class},bad.toFile());}catch(IOException e){unsafe=true;}
        check(unsafe&&!Files.exists(temporary.resolve("escape")),"tar traversal is rejected without changing installed dictionary");
        check((Boolean)call(packs,"dictionaryValid",new Class<?>[]{}),"failed dictionary staging preserves the valid installation");

        Object[] release=(Object[])field(OfflineVoicePacks.class,"FILES");
        for(int i=1;i<release.length;i++){
            String name=(String)field(release[i],"name");File target=complete(packs,release[i]);
            Files.createLink(target.toPath(),research.resolve("models/"+name));
            call(OfflineVoicePacks.class,"writeReceipt",new Class<?>[]{File.class,SPEC},target,release[i]);
        }
        call(packs,"install",new Class<?>[]{});check(packs.isReady()&&packs.snapshot().optString("status").equals("ready"),"all verified model files finish installation without network");
        set("latest",null);OfflineVoicePacks reopened=new OfflineVoicePacks(context);
        check(reopened.isReady()&&reopened.snapshot().optString("status").equals("ready"),"process restart restores installed pack from disk receipts");

        VoicevoxEngine engine=new VoicevoxEngine(context);
        byte[] wave=engine.synthesize("音声を確認します。",2,Json.obj("speedScale",1.1,"intonationScale",1.2),()->false);
        check(wave.length>24000,"production wrapper creates real offline PCM with installed dictionary/model");
        for (int style : new int[]{3,8,10,11,12,13,14,16,43}) {
            byte[] voice=engine.synthesize("確認します。",style,Json.obj("intonationScale",1.1,"pauseLengthScale",1.15),()->false);
            check(voice.length>24000,"production wrapper synthesizes standard character style "+style);
        }
        check(((Map<?,?>)field(engine,"loaded")).size()==2,"production wrapper bounds resident models to two");
        engine.synthesize("確認します。",2,null,()->false);
        check(((Map<?,?>)field(engine,"loaded")).size()==2,"evicted model reloads successfully on character change");
        AudioQuery query=new AudioQuery();VoicevoxEngine.applyParameters(query,Json.obj("speedScale",20,"pitchScale",-20,"intonationScale",20));
        check(query.speedScale==2&&query.pitchScale==-.15&&query.intonationScale==2,"untrusted prosody values stay within safe numeric bounds");
        boolean stopped=false;try{engine.synthesize("確認します。",2,null,()->true);}catch(InterruptedIOException e){stopped=true;}
        check(stopped,"production wrapper cancels before native inference");
        VoicevoxPerformanceTest.run(engine, new File(args.length > 1 ? args[1] : "build/offline-runtime-tests/samples"));
        VoicevoxStreamingTest.run(engine, new File(args.length > 1 ? args[1] : "build/offline-runtime-tests/samples"));
        VoicevoxPronunciationTest.run(engine, new File(args.length > 1 ? args[1] : "build/offline-runtime-tests/samples"));
        VoicevoxWordProsodyTest.run(engine);
        DialogueActingTest.run(engine);
        boolean badStyle=false;try{engine.synthesize("確認します。",999999,null,()->false);}catch(IOException e){badStyle=e.getMessage().equals("VOICEVOX_UNSUPPORTED_STYLE");}
        check(badStyle,"unsupported style gets a fixed public error code");engine.close();
        check(((Map<?,?>)field(engine,"loaded")).isEmpty(),"close unloads all native voice models");
        System.out.println("PASS: "+checks+" installer/wrapper checks. HTTP and Android boundaries are controlled; dictionary and native inference are real. Android service lifecycle is not exercised.");
        // Delete only this test's private directory; hard-linked upstream fixture files remain intact.
        try(java.util.stream.Stream<Path> paths=Files.walk(temporary)){paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException e){throw new RuntimeException(e);}});}
    }

    private static void writeUnsafeTar(Path path) throws IOException {
        byte[] header=new byte[512];byte[] name="open_jtalk_dic_utf_8-1.11/../../escape".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name,0,header,0,name.length);header[156]='0';
        byte[] zero="00000000000".getBytes(StandardCharsets.US_ASCII);System.arraycopy(zero,0,header,124,zero.length);
        Arrays.fill(header,148,156,(byte)' ');int sum=0;for(byte b:header)sum+=b&255;
        byte[] octal=String.format("%06o",sum).getBytes(StandardCharsets.US_ASCII);System.arraycopy(octal,0,header,148,6);header[154]=0;
        try(GZIPOutputStream out=new GZIPOutputStream(Files.newOutputStream(path))){out.write(header);out.write(new byte[1024]);}
    }
}
