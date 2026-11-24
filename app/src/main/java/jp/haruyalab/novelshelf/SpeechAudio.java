package jp.haruyalab.novelshelf;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;

/** On-device VOICEVOX synthesis with an atomic, bounded, private WAV cache. */
public final class SpeechAudio {
    static final String DEFAULT_MODEL = OfflineVoices.VERSION;
    static final int SAMPLE_RATE = 24000;
    static final long CACHE_LIMIT_BYTES = 256L * 1024 * 1024;
    static final long MAX_PCM_BYTES = 180L * SAMPLE_RATE * 2;
    static final long MAX_WAV_BYTES = MAX_PCM_BYTES + 65536L;
    private static final Object CACHE_LOCK = new Object();
    private final File directory;
    private final VoicevoxEngine engine;
    private final ConcurrentHashMap<String,String> failures = new ConcurrentHashMap<>();
    private final Set<Work> jobs=Collections.newSetFromMap(new ConcurrentHashMap<Work,Boolean>());
    private final Handler main = new Handler(Looper.getMainLooper());
    // A single worker also prevents the current unit racing its one-unit prefetch.
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TsuzukiAudio"); t.setDaemon(true); return t;
    });

    public interface Callback {
        void ready(Result result);
        void failed(String message);
    }
    interface StreamCallback extends Callback { void streaming(SpeechPcmSource source); }
    public interface VoicesCallback { void done(JSONArray voices, String error); }
    public interface CacheCallback { void done(long removedBytes); }
    public interface Job {
        void cancel();
        default long elapsedMs() { return -1; }
        default long firstAudioMs() { return -1; }
    }

    public static final class Result {
        public final File file;
        public final boolean cached;
        public final String voice;
        public final String cacheKey;
        public final long generationElapsedMs, firstAudioMs, durationMs;
        Result(File f, boolean hit, String v, String key) { this(f,hit,v,key,-1,-1); }
        Result(File f, boolean hit, String v, String key, long elapsed, long first) {
            file=f; cached=hit; voice=v; cacheKey=key; generationElapsedMs=elapsed; firstAudioMs=first;
            durationMs = pcmDurationMs(f);
        }
    }

    public SpeechAudio(Context context, File audioDirectory) { directory = audioDirectory; engine = new VoicevoxEngine(context); }

    /** Call on a lightweight worker, so cached playback does not queue behind speculative inference. */
    Result cached(JSONObject unit, JSONObject speaker, JSONObject settings, String bookId) throws Exception {
        String voice=voice(unit,speaker,settings),key=requestKey(bookId,unit,speaker,settings);
        File target=new File(directory,key+".wav");
        synchronized(CACHE_LOCK) {
            if(!validWave(target))return null;
            target.setLastModified(System.currentTimeMillis());return new Result(target,true,voice,key);
        }
    }

    static String requestKey(String bookId,JSONObject unit,JSONObject speaker,JSONObject settings)throws Exception{
        String text=unit.optString("spokenText",unit.optString("text","")),voice=voice(unit,speaker,settings);
        return SpeechStore.key(bookId)+"-"+cacheKey(bookId,text,DEFAULT_MODEL,voice,
                castIdentity(unit,speaker,instructions(unit,speaker,voice,DialogueMode.fromSettings(settings))),1.0);
    }

    public Job generate(String bookId, JSONObject unit, JSONObject speaker, JSONObject settings, Callback callback) {
        JSONObject u = Json.copy(unit), s = Json.copy(speaker), cfg = Json.copy(settings);
        Work work = new Work(callback);
        jobs.add(work);
        work.future = worker.submit(() -> {
            File temporary = null;
            String requestKey = null;
            try {
                work.check();
                String text = u.optString("spokenText", u.optString("text", ""));
                if (isBlank(text)) throw new AudioFailure("読み上げる本文がありません。");
                // SpeechPlan splits long paragraphs before this point; never silently truncate.
                if (text.length() > 2000)
                    throw new AudioFailure("読み上げ区間が長すぎます。章の読み上げデータを作り直してください。");
                String model = DEFAULT_MODEL;
                String voice = voice(u, s, cfg);
                DialogueMode mode = DialogueMode.fromSettings(cfg);
                String instructions = instructions(u, s, voice, mode);
                String key = SpeechStore.key(bookId) + "-" + cacheKey(bookId, text, model, voice,
                        castIdentity(u, s, instructions), 1.0);
                requestKey = key;
                File target = new File(directory, key + ".wav");
                synchronized (CACHE_LOCK) {
                    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("CACHE_DIRECTORY");
                    if (validWave(target)) {
                        target.setLastModified(System.currentTimeMillis());
                        work.success(new Result(target, true, voice, key)); return;
                    }
                    if (target.exists() && !target.delete()) throw new IOException("CACHE_INVALID");
                }
                String priorFailure = failures.get(key);
                if (priorFailure != null) throw new AudioFailure(priorFailure);
                work.check();
                temporary = File.createTempFile(key + "-", ".part", directory);
                try (RandomAccessFile out = new RandomAccessFile(temporary, "rw")) {
                    out.setLength(0);
                    if(callback instanceof StreamCallback) {
                        out.write(VoicePcm.header(0));
                        SpeechPcmSource source=new SpeechPcmSource(temporary); work.source=source;
                        final long[] written={0};
                        byte[] finalWave=engine.synthesizeUnit(text,voice,u,s,
                                ()->work.cancelled||Thread.currentThread().isInterrupted(),(pcm,speechReady)->{
                                    work.check();out.write(pcm);written[0]+=pcm.length;source.publish(written[0]);
                                    if(speechReady)work.streamReady(source);
                                }, mode);
                        work.check();
                        if(pcmByteCount(finalWave)!=written[0])throw new IOException("VOICEVOX_STREAM_LENGTH");
                        // Replace only the header. The PCM file remains readable through its open descriptor.
                        out.seek(0);out.write(VoicePcm.header((int)written[0]));
                        work.streamReady(source); // A punctuation-only unit can contain only deliberate silence.
                    } else {
                        byte[] wave = engine.synthesizeUnit(text, voice, u, s,
                                () -> work.cancelled || Thread.currentThread().isInterrupted(), null, mode);
                        work.check(); out.write(wave);
                    }
                    work.check();out.getFD().sync();
                }
                work.check();
                if (!validWave(temporary)) throw new IOException("CACHE_INVALID");
                synchronized (CACHE_LOCK) {
                    work.check();
                    if (!validWave(target)) {
                        if (target.exists() && !target.delete()) throw new IOException("CACHE_WRITE");
                        if (!temporary.renameTo(target)) throw new IOException("CACHE_WRITE");
                        temporary = null;
                    }
                    target.setLastModified(System.currentTimeMillis());
                    pruneCache(directory, target);
                }
                work.completedNanos=System.nanoTime();
                if(work.source!=null)work.source.finish();
                work.success(new Result(target, false, voice, key, work.elapsedMs(),work.firstAudioMs));
            } catch (Exception error) {
                if (!work.cancelled) {
                    if(work.source!=null)work.source.fail("VOICEVOX_SYNTHESIS_FAILED");
                    String message = safeError(error);
                    if(requestKey!=null)failures.put(requestKey,message);
                    work.failure(message);
                }
            } catch (OutOfMemoryError memory) {
                if(work.source!=null)work.source.fail("VOICEVOX_SYNTHESIS_FAILED");
                if (!work.cancelled) work.failure("音声生成に使うメモリが不足しています。ほかのアプリを閉じて再試行してください。");
            } finally {
                if(work.source!=null&&(work.cancelled||work.firstAudioMs<0))work.source.close();
                if (temporary != null) temporary.delete();
                jobs.remove(work);
            }
        });
        return work;
    }

    public void close() {
        for(Work job:jobs)job.cancel();
        // Keep already queued deletion barriers. Cancelled generation jobs do no further work.
        worker.submit(engine::close);
        worker.shutdown();
    }
    public void allowRetry() { failures.clear(); }

    /** A barrier for deletion after in-flight generation has relinquished its temporary file. */
    public void afterPending(Runnable callback) { worker.submit(() -> main.post(callback)); }
    public void afterPendingOnWorker(Runnable callback) { worker.submit(callback); }

    /** Call after cancelling playback/prefetch jobs. Runs after their finalizers. */
    public void clearCache(CacheCallback callback) {
        worker.submit(() -> {
            long bytes = 0;
            synchronized (CACHE_LOCK) {
                File[] files = directory.listFiles();
                if (files != null) for (File file : files) {
                    if (!file.isFile()) continue;
                    long size = file.length(); if (file.delete()) bytes += size;
                }
            }
            final long removed = bytes;
            main.post(() -> callback.done(removed));
        });
    }

    private final class Work implements Job {
        final Callback callback;
        volatile boolean cancelled;
        volatile Future<?> future;
        final long requestedNanos=System.nanoTime();
        volatile long completedNanos, firstAudioMs=-1;
        volatile SpeechPcmSource source;
        Work(Callback cb) { callback = cb; }
        void check() throws IOException {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("CANCELLED");
        }
        void success(Result result) { main.post(() -> { if (!cancelled) callback.ready(result); }); }
        void failure(String message) { main.post(() -> { if (!cancelled) callback.failed(message); }); }
        void streamReady(SpeechPcmSource value) {
            if(firstAudioMs>=0)return;
            firstAudioMs=elapsedMs();
            main.post(()->{if(!cancelled)((StreamCallback)callback).streaming(value);else value.close();});
        }
        @Override public long elapsedMs(){return Math.max(0,((completedNanos==0?System.nanoTime():completedNanos)-requestedNanos)/1000000);}
        @Override public long firstAudioMs(){return firstAudioMs;}
        @Override public void cancel() {
            cancelled = true;
            if(source!=null)source.close();
            Future<?> f = future; if (f != null) f.cancel(true);
            jobs.remove(this);
        }
    }

    static String voice(JSONObject unit, JSONObject speaker, JSONObject settings) {
        return OfflineVoices.resolve(speaker, settings);
    }

    static String instructions(JSONObject unit, JSONObject speaker) {
        return instructions(unit, speaker, OfflineVoices.resolve(speaker, null), DialogueMode.STANDARD);
    }

    /** The original display text and acting offsets stay unchanged; only engine input is replaced. */
    static String playbackText(JSONObject unit) { return SpeechReadingInput.text(unit); }

    private static String instructions(JSONObject unit, JSONObject speaker, String voice, DialogueMode mode) {
        String performance = SpeechPerformance.signature(unit);
        String controls;
        if (performance.isEmpty())
            controls = OfflineVoices.signature(OfflineVoices.styleId(voice, unit), OfflineVoices.parameters(unit, speaker));
        else {
            // All validated parts, offsets, endings and pauses enter the identity. A neutral part records
            // the character baseline independently of any clamping in a particular acted part.
            JSONObject baseline = OfflineVoices.performanceParameters(new JSONObject(), speaker);
            controls = new JSONArray().put("detailed-parts-v1").put(performance)
                    .put(OfflineVoices.signature(OfflineVoices.styleId(voice, new JSONObject()), baseline)).toString();
        }
        String reading = SpeechPronunciation.signature(unit);
        // Preserve pre-pronunciation cache identity byte-for-byte when no valid reading exists.
        if (!reading.isEmpty()) controls = new JSONArray().put("word-contour-v1").put(controls).put(reading)
                .put(unit.optString("text", SpeechPerformance.spokenText(unit))).toString();
        if (mode == DialogueMode.STANDARD || !DialogueActing.eligible(unit)) return controls;
        return new JSONArray().put(mode == DialogueMode.ANIME ? "anime-dialogue-v1" : "conversational-dialogue-v1").put(controls).put(unit.optString("emotion","neutral"))
                .put(unit.optString("delivery","natural")).put(unit.optString("ending","auto")).put(Double.valueOf(unit.optDouble("intensity",0)))
                .put(unit.optInt("pauseAfterMs",0)).put(unit.optBoolean("locked")).toString();
    }

    static String castIdentity(JSONObject unit, JSONObject speaker, String style) {
        // Role identity and revision are independent from the transcript: recasting invalidates old audio.
        return new JSONArray().put(speaker.optString("id", unit.optString("speakerId", "narrator")))
                .put(speaker.optLong("revision", speaker.optLong("castRevision", 0)))
                .put(unit.optString("kind", "narration")).put(style).toString();
    }

    static double clamp(double v, double min, double max) {
        return Double.isNaN(v) || Double.isInfinite(v) ? 1 : Math.max(min, Math.min(max, v));
    }

    static boolean hasJapanese(String value) {
        for(int offset=0;offset<value.length();){int code=value.codePointAt(offset);offset+=Character.charCount(code);
            if(code>=0x3040&&code<=0x30ff || code>=0x3400&&code<=0x9fff || code>=0xf900&&code<=0xfaff
                    || code>=0xff66&&code<=0xff9d || code>=0x20000&&code<=0x2fa1f)return true;}
        return false;
    }

    static boolean isBlank(String value){
        for(int offset=0;offset<value.length();){int code=value.codePointAt(offset);offset+=Character.charCount(code);
            if(!Character.isWhitespace(code)&&!Character.isSpaceChar(code))return false;}
        return true;
    }

    static String cacheKey(String bookId, String text, String model, String voice, String instructions, double generationSpeed) throws Exception {
        // PlaybackParams speed is intentionally not a synthesis input: changing playback speed requires no new synthesis.
        String input = new JSONArray().put("tsuzuki-voicevox-wav-v2").put("voicevox").put(bookId).put(model).put(voice)
                .put(instructions).put(generationSpeed).put(SAMPLE_RATE).put(text).toString();
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(64);
        for (byte b : hash) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }

    static byte[] waveHeader(long pcmBytes) throws IOException {
        if (pcmBytes < 0 || pcmBytes > MAX_PCM_BYTES || (pcmBytes & 1) != 0) throw new IOException("WAVE_LENGTH");
        byte[] header = new byte[44];
        putAscii(header, 0, "RIFF"); putInt(header,4,36+pcmBytes); putAscii(header,8,"WAVEfmt ");
        putInt(header,16,16); header[20]=1; header[22]=1;
        putInt(header,24,SAMPLE_RATE); putInt(header,28,SAMPLE_RATE*2); header[32]=2; header[34]=16;
        putAscii(header,36,"data"); putInt(header,40,pcmBytes); return header;
    }

    private static void putAscii(byte[] bytes,int position,String value) {
        byte[] raw=value.getBytes(StandardCharsets.US_ASCII); System.arraycopy(raw,0,bytes,position,raw.length);
    }
    private static void putInt(byte[] bytes,int position,long value) {
        for(int i=0;i<4;i++)bytes[position+i]=(byte)(value >>> (8*i));
    }
    private static long getInt(byte[] bytes,int position) {
        return ((long)bytes[position]&255)|(((long)bytes[position+1]&255)<<8)
                |(((long)bytes[position+2]&255)<<16)|(((long)bytes[position+3]&255)<<24);
    }
    private interface WaveSource {
        long length();
        byte[] read(long position, int count) throws IOException;
    }
    static boolean validWave(File file) {
        if (!file.isFile() || file.length() <= 44 || file.length() > MAX_WAV_BYTES) return false;
        try (RandomAccessFile read = new RandomAccessFile(file, "r")) {
            final long length = read.length();
            waveDataBytes(new WaveSource() {
                @Override public long length() { return length; }
                @Override public byte[] read(long position, int count) throws IOException {
                    byte[] result = new byte[count]; read.seek(position); read.readFully(result); return result;
                }
            });
            return true;
        } catch (IOException invalid) { return false; }
    }
    private static long pcmDurationMs(File file) {
        if(file==null||!file.isFile())return 0;
        try(RandomAccessFile read=new RandomAccessFile(file,"r")){
            long bytes=waveDataBytes(new WaveSource(){
                @Override public long length(){try{return read.length();}catch(IOException e){return 0;}}
                @Override public byte[] read(long position,int count)throws IOException{byte[] b=new byte[count];read.seek(position);read.readFully(b);return b;}
            });return bytes/48;
        }catch(IOException e){return 0;}
    }
    private static long pcmByteCount(byte[] wave)throws IOException{
        return waveDataBytes(new WaveSource(){
            @Override public long length(){return wave.length;}
            @Override public byte[] read(long position,int count)throws IOException{
                if(position<0||position>wave.length-count)throw new IOException("WAVE_LENGTH");
                return Arrays.copyOfRange(wave,(int)position,(int)position+count);
            }
        });
    }

    /** Strict RIFF PCM validation while permitting normal non-audio metadata chunks. */
    private static long waveDataBytes(WaveSource source) throws IOException {
        long length = source.length();
        if (length <= 44 || length > MAX_WAV_BYTES) throw new IOException("WAVE_LENGTH");
        byte[] header = source.read(0, 12);
        if (!ascii(header, 0, "RIFF") || !ascii(header, 8, "WAVE") || uint32(header, 4) + 8 != length)
            throw new IOException("WAVE_HEADER");
        boolean format = false, data = false;
        long count = 0, position = 12, metadata = 12;
        int chunks = 0;
        while (position < length) {
            if (++chunks > 128 || length - position < 8) throw new IOException("WAVE_CHUNK");
            byte[] chunk = source.read(position, 8); long size = uint32(chunk, 4);
            long next = position + 8 + size + (size & 1);
            if (size > length - position - 8 || next > length) throw new IOException("WAVE_CHUNK");
            if (ascii(chunk, 0, "fmt ")) {
                if (format || data || size < 16) throw new IOException("WAVE_FORMAT");
                byte[] fmt = source.read(position + 8, 16);
                if (uint16(fmt, 0) != 1 || uint16(fmt, 2) != 1 || uint32(fmt, 4) != SAMPLE_RATE
                        || uint32(fmt, 8) != SAMPLE_RATE * 2 || uint16(fmt, 12) != 2 || uint16(fmt, 14) != 16)
                    throw new IOException("WAVE_FORMAT");
                format = true;
            } else if (ascii(chunk, 0, "data")) {
                if (!format || data || size == 0 || (size & 1) != 0 || size > MAX_PCM_BYTES) throw new IOException("WAVE_DATA");
                data = true; count = size;
            }
            metadata += 8 + (size & 1) + (ascii(chunk, 0, "data") ? 0 : size);
            if (metadata > 65536) throw new IOException("WAVE_METADATA");
            position = next;
        }
        if (!format || !data || position != length) throw new IOException("WAVE_INCOMPLETE");
        return count;
    }
    private static boolean ascii(byte[] bytes, int offset, String value) {
        for (int i = 0; i < value.length(); i++) if ((bytes[offset + i] & 255) != value.charAt(i)) return false;
        return true;
    }
    private static int uint16(byte[] bytes, int offset) { return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8); }
    private static long uint32(byte[] bytes, int offset) {
        return ((long) bytes[offset] & 255) | (((long) bytes[offset + 1] & 255) << 8)
                | (((long) bytes[offset + 2] & 255) << 16) | (((long) bytes[offset + 3] & 255) << 24);
    }

    static void pruneCache(File directory,File keep) {
        File[] files=directory.listFiles((dir,name)->name.matches("[0-9a-f]{64}-[0-9a-f]{64}\\.wav"));
        if(files==null)return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long total=0;for(File file:files)total+=file.length();
        for(File file:files){if(total<=CACHE_LIMIT_BYTES)break;if(file.equals(keep))continue;long length=file.length();if(file.delete())total-=length;}
        File[] parts=directory.listFiles((dir,name)->name.endsWith(".part"));
        if(parts!=null)for(File file:parts)if(System.currentTimeMillis()-file.lastModified()>3600000)file.delete();
    }

    private static String safeError(Exception error) {
        if(error instanceof AudioFailure)return error.getMessage();
        String message = error.getMessage() == null ? "" : error.getMessage();
        if (message.startsWith("VOICE_PACKS_NOT_READY") || message.startsWith("VOICEVOX_MODELS_MISSING"))
            return "オフライン音声モデルが未取得です。読み上げ設定の「オフライン音声」から取得してください。";
        if (message.startsWith("VOICEVOX_TEXT_TOO_LONG"))
            return "読み上げ区間が長すぎます。長いルビを含む場合はルビを使う設定をオフにして再試行してください。";
        if (message.startsWith("VOICEVOX_UNAVAILABLE") || message.startsWith("VOICEVOX_UNSUPPORTED"))
            return "この端末ではオフライン音声エンジンを起動できません。対応する64ビット版か確認するか、設定で端末音声を選んでください。";
        if (message.startsWith("CACHE_") || message.startsWith("WAVE_"))
            return "音声を保存できませんでした。空き容量を確認して音声キャッシュを削除し、再試行してください。";
        return "オフライン音声を生成できませんでした。モデルの取得状態と空きメモリを確認して再試行してください。";
    }

    private static final class AudioFailure extends IOException {
        private static final long serialVersionUID=1L;
        AudioFailure(String message){super(message);}
    }

    /** Lists actual engine-provided voice IDs; Android does not expose a gender field. */
    public static void deviceVoices(Context context,VoicesCallback callback) {
        Context app=context.getApplicationContext();Handler main=new Handler(Looper.getMainLooper());
        main.post(()->{
            TextToSpeech[] engine=new TextToSpeech[1];AtomicBoolean finished=new AtomicBoolean();
            Runnable timeout=()->{if(finished.compareAndSet(false,true)){if(engine[0]!=null)engine[0].shutdown();callback.done(new JSONArray(),"端末音声エンジンを確認できませんでした。");}};
            main.postDelayed(timeout,10000);
            try {
                engine[0]=new TextToSpeech(app,status->main.post(()->{
                    if(!finished.compareAndSet(false,true))return;
                    main.removeCallbacks(timeout);JSONArray values=new JSONArray();String error=null;
                    try{
                        if(status!=TextToSpeech.SUCCESS||engine[0]==null)throw new IllegalStateException();
                        Set<Voice> available=engine[0].getVoices();List<Voice> list=new ArrayList<>();if(available!=null)list.addAll(available);
                        list.sort(Comparator.comparing((Voice v)->!"ja".equals(v.getLocale().getLanguage())).thenComparing(Voice::getName));
                        for(Voice v:list)values.put(Json.obj("id",v.getName(),"name",v.getName(),"locale",v.getLocale().toLanguageTag(),
                                "network",v.isNetworkConnectionRequired(),"quality",v.getQuality(),"latency",v.getLatency()));
                    }catch(Exception e){error="端末に読み上げ音声が見つかりません。Androidの音声設定を確認してください。";}
                    finally{if(engine[0]!=null)engine[0].shutdown();}
                    callback.done(values,error);
                }));
            }catch(Exception e){main.removeCallbacks(timeout);if(finished.compareAndSet(false,true))callback.done(new JSONArray(),"端末音声エンジンを起動できませんでした。");}
        });
    }
}
