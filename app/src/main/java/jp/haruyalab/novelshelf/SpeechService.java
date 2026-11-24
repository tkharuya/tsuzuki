package jp.haruyalab.novelshelf;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;

/** Owns audio independently of the Activity/WebView. All player state lives on the main thread. */
public final class SpeechService extends Service {
    private static final String CHANNEL = "tsuzuki_read_aloud";
    private static final int NOTIFICATION_ID = 1201;
    private static final String ACTION_PREFIX = "jp.haruyalab.novelshelf.speech.";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile SpeechService instance;
    private static volatile Listener listener;
    private static volatile String latest = "{\"status\":\"idle\",\"engine\":\"device\",\"unitIndex\":0,\"unitCount\":0}";

    public interface Listener { void onState(JSONObject state); }
    public static JSONObject snapshot() { return Json.parse(latest); }
    public static void setListener(Listener value) {
        listener = value;
        if (value != null) MAIN.post(() -> { if (listener == value) value.onState(snapshot()); });
    }
    public static void command(Context context, String action, JSONObject payload) {
        final Context app = context.getApplicationContext();
        final JSONObject data = Json.copy(payload);
        // Settings/cast edits arrive on main. Cancel immediately so an already queued onPrepared
        // callback cannot begin a stale-voice prefetch between an edit and a posted stop.
        if ("stop".equals(action) && Looper.myLooper() == Looper.getMainLooper()) {
            SpeechService active = instance;
            if (active != null && !active.destroyed) { active.handle(action, data); return; }
        }
        MAIN.post(() -> {
            SpeechService active = instance;
            if (active != null && !active.destroyed) { active.handle(action, data); return; }
            if ("clearCache".equals(action) || "deleteBook".equals(action)) {
                Thread clean = new Thread(() -> {
                    try{
                        SpeechStore disk=new SpeechStore(app);
                        if("deleteBook".equals(action)){String id=data.optString("bookId");if(!id.isEmpty())disk.deleteBook(id);return;}
                        long removed = disk.clearAudioCache();
                        MAIN.post(() -> publish(Json.obj("status","idle","engine","device","removedBytes",removed,
                                "unitIndex",0,"unitCount",0)));
                    }catch(Exception e){MAIN.post(()->publish(Json.obj("status","error","error","音声データを削除できませんでした。再試行してください。")));}
                },"TsuzukiClearAudio"); clean.setDaemon(true); clean.start(); return;
            }
            if ("stop".equals(action) || "pause".equals(action)) return;
            if ("speed".equals(action) || "sleep".equals(action)) return;
            String chosen = action;
            if (!"play".equals(chosen) && !"preview".equals(chosen)) {
                JSONObject saved = snapshot();
                if (!data.has("bookId")) Json.put(data,"bookId",saved.optString("bookId"));
                if (!data.has("chapterUrl")) Json.put(data,"chapterUrl",saved.optString("chapterUrl"));
                if (data.optString("bookId").isEmpty() || data.optString("chapterUrl").isEmpty()) {
                    publish(Json.merge(saved,Json.obj("status","error","error","読み上げる章を開いて再生してください。"))); return;
                }
                if("next".equals(chosen))Json.put(data,"unitIndex",saved.optInt("unitIndex")+1);
                if("previous".equals(chosen))Json.put(data,"unitIndex",Math.max(0,saved.optInt("unitIndex")-1));
                chosen = "play";
            }
            try {
                Intent intent = new Intent(app,SpeechService.class).setAction(ACTION_PREFIX+chosen)
                        .putExtra("payload",data.toString());
                app.startForegroundService(intent);
            } catch (RuntimeException e) {
                publish(Json.obj("status","error","error","読み上げサービスを開始できません。アプリを開いて再生してください。"));
            }
        });
    }
    private static void publish(JSONObject value) {
        latest = value.toString(); Listener current = listener;
        if (current != null) try{current.onState(Json.copy(value));}catch(RuntimeException ignored){}
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService planWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r,"TsuzukiSpeechPlan"); t.setDaemon(true); return t;
    });
    private SpeechStore store;
    private SpeechAudio audio;
    private SpeechAudio.Job generating;
    private final LinkedHashMap<Integer,Ahead> ahead=new LinkedHashMap<>();
    private static final int MAX_AHEAD_UNITS=3;
    private static final long AHEAD_TARGET_MS=15000;
    private PcmPlayback pcmPlayer;
    private long generationElapsedMs=-1, firstAudioMs=-1;
    private long pendingSeekMs=-1;
    private static final class Ahead {
        final String bookId,chapterUrl,key;
        final int index;
        SpeechAudio.Job job;
        SpeechPcmSource source;
        SpeechAudio.Result result;
        String failure;
        boolean promoted;
        long token;
        Ahead(String bookId,String url,int index,String key){this.bookId=bookId;chapterUrl=url;this.index=index;this.key=key;}
    }
    private MediaPlayer player;
    private boolean playerPrepared;
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean ttsStarting;
    private long ttsEpoch;
    private String utterance = "";
    private MediaSession session;
    private AudioManager audioManager;
    private AudioAttributes attributes;
    private AudioFocusRequest focusRequest;
    private PowerManager.WakeLock wake;
    private boolean hasFocus;
    private boolean resumeOnFocusGain;
    private boolean foreground;
    private boolean destroyed;
    private boolean receiverRegistered;
    private boolean preview;
    private boolean completed;
    private boolean clearing;
    private boolean audioCached;
    private long removedBytes = -1;
    private long generation;
    private long sleepAt;
    private long sleepDeadline;
    private long pausedSince;
    private long lastWakeRenewal;
    private String positionWarning="";
    private String status = "idle";
    private String error = "";
    private String bookId = "";
    private String chapterUrl = "";
    private String engine = "offline";
    private String previewVoice = "";
    private DialogueMode previewMode;
    private String previewEngine = "";
    private String selectedDeviceVoice = "";
    private boolean deviceNetwork;
    private JSONObject plan = new JSONObject();
    private JSONObject settings = new JSONObject();
    private JSONObject current = new JSONObject();
    private JSONObject speaker = new JSONObject();
    private JSONArray units = new JSONArray();
    private int unitIndex;
    private float speed = 1;
    private final Set<String> visited = new HashSet<>();

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction()) && activePlayback()) {
                resumeOnFocusGain=false; pausePlayback(true);
            }
        }
    };
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if(destroyed)return;
            if(sleepDeadline>0 && SystemClock.elapsedRealtime()>=sleepDeadline) {
                sleepAt=0;sleepDeadline=0;resumeOnFocusGain=false;pausePlayback(true);
            }
            if(activePlayback()) {
                if(SystemClock.elapsedRealtime()-lastWakeRenewal>60000)holdWake();
                emit(false);
            } else if ("paused".equals(status) && pausedSince>0
                    && SystemClock.elapsedRealtime()-pausedSince>=15*60*1000L) {
                stopPlayback(false); return;
            }
            main.postDelayed(this,1000);
        }
    };

    @Override public void onCreate() {
        super.onCreate(); instance=this; store=new SpeechStore(this); audio=new SpeechAudio(this, store.audioDir());
        attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        audioManager=(AudioManager)getSystemService(AUDIO_SERVICE);
        focusRequest=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(this::onFocusChanged,main).build();
        PowerManager manager=(PowerManager)getSystemService(POWER_SERVICE);
        if(manager!=null){wake=manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Tsuzuki:ReadAloud");wake.setReferenceCounted(false);}
        NotificationManager notifications=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(notifications!=null){NotificationChannel channel=new NotificationChannel(CHANNEL,"読み上げ",NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("読み上げの再生と停止");channel.setSound(null,null);notifications.createNotificationChannel(channel);}
        session=new MediaSession(this,"TsuzukiReadAloud");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS|MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback(){
            @Override public void onPlay(){handle("resume",new JSONObject());}
            @Override public void onPause(){handle("pause",new JSONObject());}
            @Override public void onStop(){handle("stop",new JSONObject());}
            @Override public void onSkipToNext(){handle("next",new JSONObject());}
            @Override public void onSkipToPrevious(){handle("previous",new JSONObject());}
            @Override public void onSeekTo(long position){
                if(pcmPlayer!=null){
                    if(!pcmPlayer.seek(Math.max(0,position))){
                        // EOF may have posted completion, but the seek must keep this unit.
                        boolean paused="paused".equals(status);cancelCurrent(false);
                        pendingSeekMs=Math.max(0,position);status=paused?"paused":"loading";generateAudio();
                    }else if(!"paused".equals(status))status="loading";
                    emit(true);return;
                }
                if(player!=null&&playerPrepared)try{player.seekTo((int)Math.max(0,Math.min(player.getDuration(),position)));emit(true);}catch(Exception e){fail("音声の再生位置を変更できませんでした。");}
            }
        },main);
        session.setActive(true);
        IntentFilter filter=new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(noisy,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(noisy,filter);
        receiverRegistered=true;main.post(ticker);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        try{ensureForeground();}catch(RuntimeException e){
            publish(Json.obj("status","error","error","バックグラウンド再生を開始できません。アプリを開いて再試行してください。"));
            stopSelf();return START_NOT_STICKY;}
        String action=intent.getAction()==null?"":intent.getAction().replace(ACTION_PREFIX,"");
        JSONObject payload=Json.parse(intent.getStringExtra("payload"));
        // A notification remains useful after Android recreated this service.
        if(bookId.isEmpty() && ("resume".equals(action)||"next".equals(action)||"previous".equals(action))) {
            if(!payload.optString("bookId").isEmpty()){
                if("next".equals(action))Json.put(payload,"unitIndex",payload.optInt("unitIndex")+1);
                if("previous".equals(action))Json.put(payload,"unitIndex",Math.max(0,payload.optInt("unitIndex")-1));
                action="play";
            }
        }
        handle(action,payload);return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent){return null;}

    private void handle(String action,JSONObject payload) {
        if(destroyed)return;
        try {
            if("play".equals(action)||"preview".equals(action)||"resume".equals(action)
                    ||"next".equals(action)||"previous".equals(action)||"seek".equals(action))audio.allowRetry();
            switch(action){
                case "play": startBook(payload);break;
                case "preview": startPreview(payload);break;
                case "pause": resumeOnFocusGain=false;pausePlayback(true);break;
                case "resume": resumeOnFocusGain=false;resumePlayback();break;
                case "next": resumeOnFocusGain=false;advance(true,activePlayback());break;
                case "previous": resumeOnFocusGain=false;jump(Math.max(0,unitIndex-1),activePlayback());break;
                case "seek": resumeOnFocusGain=false;jump(payload.optInt("unitIndex",unitIndex),activePlayback());break;
                case "speed": changeSpeed((float)SpeechAudio.clamp(payload.optDouble("speed",1),.5,2));break;
                case "sleep": setSleep(Math.max(0,Math.min(180,payload.optInt("minutes"))));emit(true);break;
                case "stop": stopPlayback(true);break;
                case "clearCache": clearCache();break;
                case "deleteBook": deleteBook(payload.optString("bookId"));break;
                default: fail("読み上げ操作を確認できませんでした。");
            }
        } catch(Exception e){fail(dataError(e));}
    }

    private void startBook(JSONObject payload) {
        savePosition();cancelCurrent(true);preview=false;previewVoice="";previewEngine="";previewMode=null;completed=false;clearing=false;removedBytes=-1;
        bookId=payload.optString("bookId");chapterUrl=payload.optString("chapterUrl");
        if(bookId.isEmpty()||chapterUrl.isEmpty())throw new IllegalArgumentException();
        plan=store.plan(bookId,chapterUrl);units=Json.arr(plan,"units");
        if(payload.has("inputHash")&&!plan.optString("inputHash").equals(payload.optString("inputHash")))
            throw new IllegalStateException("CHAPTER_CHANGED");
        if(units.length()==0){fail("読み上げる本文がありません。章を開き直してください。");return;}
        JSONObject position=store.position(bookId);
        int start=payload.has("unitIndex")?payload.optInt("unitIndex"):
                chapterUrl.equals(position.optString("chapterUrl"))&&plan.optString("inputHash").equals(position.optString("inputHash"))?position.optInt("unitIndex"):0;
        unitIndex=Math.max(0,Math.min(units.length()-1,start));settings=store.settings();
        speed=(float)SpeechAudio.clamp(settings.optDouble("speed",1),.5,2);setSleep(settings.optInt("sleepMinutes"));
        visited.clear();visited.add(chapterUrl);playUnit();
    }

    private void startPreview(JSONObject payload) {
        savePosition();cancelCurrent(true);preview=true;completed=false;clearing=false;removedBytes=-1;
        bookId=payload.optString("bookId","__preview__");if(bookId.isEmpty())bookId="__preview__";
        previewVoice=payload.optString("offlineVoice");
        previewMode = null;
        if (payload.optBoolean("sceneAudition")) {
            previewMode = payload.has("dialogueMode") ? DialogueMode.fromSettings(payload)
                    : (payload.optBoolean("animeActing", true) ? DialogueMode.ANIME : DialogueMode.STANDARD);
        }
        previewEngine="offline".equals(payload.optString("engine"))||!previewVoice.isEmpty()?"offline":"";
        chapterUrl="";unitIndex=0;settings=store.settings();speed=(float)SpeechAudio.clamp(settings.optDouble("speed",1),.5,2);
        String text=payload.optString("text","これは読み上げ音声の試聴です。落ち着いた声で、物語を読みます。");
        if(text.trim().isEmpty()||text.codePointCount(0,text.length())>600){fail("試聴する文章は1〜600文字で入力してください。");return;}
        JSONObject unit=Json.obj("unitId","preview","text",text,"spokenText",text,"paragraph",0,
                "speakerId",payload.optString("speakerId","narrator"),"kind","narration","emotion","neutral","intensity",.4,"rate",1,"pitch",1);
        if (previewMode != null) unit = "everyday".equals(payload.optString("auditionScene"))
                ? DialogueSample.everyday() : DialogueSample.unit();
        units=new JSONArray().put(unit);plan=Json.obj("title","音声の試聴","units",units);sleepAt=0;sleepDeadline=0;playUnit();
    }

    private void refreshUnit() {
        if(!preview){JSONObject updated=store.plan(bookId,chapterUrl);JSONArray next=Json.arr(updated,"units");
            if(!plan.optString("inputHash").equals(updated.optString("inputHash")))throw new IllegalStateException("CHAPTER_CHANGED");
            if(next.length()==0)throw new IllegalStateException();plan=updated;units=next;unitIndex=Math.min(unitIndex,units.length()-1);}
        JSONObject selected=units.optJSONObject(unitIndex);if(selected==null)throw new IllegalStateException();current=Json.copy(selected);
        settings=store.settings();engine=preview&&!previewEngine.isEmpty()?previewEngine:settings.optString("engine","offline");
        if (preview && previewMode!=null) previewMode.storeIn(settings);
        speaker=new JSONObject();JSONArray cast="__preview__".equals(bookId)?new JSONArray():store.cast(bookId);String id=current.optString("speakerId","narrator");
        for(int i=0;i<cast.length();i++){JSONObject value=cast.optJSONObject(i);if(value!=null&&id.equals(value.optString("id"))){speaker=value;break;}}
        if(speaker.length()==0)speaker=Json.obj("id",id,"offlineVoice",OfflineVoices.resolve(null,settings),"gender","neutral","rate",1,"pitch",1);
        if(preview&&OfflineVoices.valid(previewVoice))speaker=Json.obj("id","preview_"+previewVoice,"offlineVoice",previewVoice,"gender","neutral","rate",1,"pitch",1);
    }

    private void playUnit() {
        try{playUnitInternal();}catch(Exception e){fail(dataError(e));}
    }

    private void playUnitInternal() {
        cancelCurrent(false);completed=false;removedBytes=-1;refreshUnit();
        generationElapsedMs=-1;firstAudioMs=-1;audioCached=false;
        int skipped=0;
        ensureForeground();
        while(SpeechAudio.isBlank(current.optString("spokenText",current.optString("text","")))){
            if(unitIndex+1>=units.length()){
                status="loading";final long token=generation;
                main.post(()->{if(valid(token))advance(false,true);});return;
            }
            unitIndex++;skipped++;JSONObject next=units.optJSONObject(unitIndex);
            if(next==null)throw new IllegalStateException();current=Json.copy(next);
        }
        if(skipped>0)refreshUnit();
        // Local synthesis never sends the novel text or voice data over the network.
        ensureForeground();
        if(!requestFocus()){fail("ほかの音声が再生中のため開始できません。停止してから再試行してください。");return;}
        status="loading";error="";pausedSince=0;holdWake();savePosition();emit(true);
        if("offline".equals(engine))generateAudio();else playDevice();
    }

    private void generateAudio() {
        final long token=generation;
        generationElapsedMs=-1;firstAudioMs=-1;audioCached=false;
        final String key;
        try{key=SpeechAudio.requestKey(bookId,current,speaker,settings);}catch(Exception e){fail("音声データを準備できませんでした。");return;}
        Ahead prepared=ahead.remove(unitIndex);
        if(prepared!=null&&prepared.bookId.equals(bookId)&&prepared.chapterUrl.equals(chapterUrl)&&prepared.key.equals(key)){
            prepared.promoted=true;prepared.token=token;generating=prepared.job;
            if(prepared.failure!=null){fail(prepared.failure);return;}
            if(prepared.result!=null){if(prepared.source!=null)prepared.source.close();playCached(prepared.result,token);}
            else if(prepared.source!=null)playStream(prepared.source,token);
            emit(false);return;
        }
        cancelAhead(prepared);cancelPrefetch();
        final JSONObject u=Json.copy(current),s=Json.copy(speaker),cfg=Json.copy(settings);
        final String id=bookId,url=chapterUrl;final int index=unitIndex;
        // A cache hit never sits behind native inference for another future unit.
        planWorker.submit(()->{
            SpeechAudio.Result cached=null;try{cached=audio.cached(u,s,cfg,id);}catch(Exception ignored){}
            final SpeechAudio.Result hit=cached;
            main.post(()->{
                if(!valid(token))return;
                if(hit!=null){playCached(hit,token);return;}
                Ahead request=new Ahead(id,url,index,key);request.promoted=true;request.token=token;
                request.job=audio.generate(id,u,s,cfg,streamCallback(request));generating=request.job;emit(false);
            });
        });
    }

    private boolean current(Ahead entry){return entry.promoted&&valid(entry.token)&&entry.index==unitIndex&&entry.bookId.equals(bookId)&&entry.chapterUrl.equals(chapterUrl);}
    private boolean pending(Ahead entry){return !entry.promoted&&ahead.get(entry.index)==entry&&entry.bookId.equals(bookId)&&entry.chapterUrl.equals(chapterUrl);}
    private SpeechAudio.StreamCallback streamCallback(Ahead entry){return new SpeechAudio.StreamCallback(){
        @Override public void streaming(SpeechPcmSource source){
            entry.source=source;
            if(current(entry)){if(pendingSeekMs<0)playStream(source,entry.token);}
            else if(!pending(entry))source.close();
        }
        @Override public void ready(SpeechAudio.Result result){
            entry.result=result;entry.job=null;
            if(current(entry)){
                generating=null;generationElapsedMs=result.generationElapsedMs;firstAudioMs=result.firstAudioMs;audioCached=result.cached;
                if(pcmPlayer==null){if(entry.source!=null){entry.source.close();entry.source=null;}playCached(result,entry.token);}
                else{emit(false);prefetchNext();}
            }else{
                if(entry.source!=null){entry.source.close();entry.source=null;}
                if(pending(entry)){emit(false);prefetchNext();}
            }
        }
        @Override public void failed(String message){
            entry.failure=message;entry.job=null;
            if(current(entry)){generating=null;fail(message);}
            else{if(entry.source!=null){entry.source.close();entry.source=null;}if(pending(entry))emit(false);}
        }
    };}

    private void playStream(SpeechPcmSource source,long token){
        if(!valid(token)){source.close();return;}
        if(pcmPlayer!=null)return;
        if(generating!=null){generationElapsedMs=generating.elapsedMs();firstAudioMs=generating.firstAudioMs();}
        audioCached=false;
        PcmPlayback stream=new PcmPlayback(source,()->new AndroidPcmOutput(attributes),new PcmPlayback.Listener(){
            @Override public void playing(){main.post(()->{if(valid(token)&&pcmPlayer!=null&&!"paused".equals(status)){status="playing";emit(true);prefetchNext();}});}
            @Override public void buffering(){main.post(()->{if(valid(token)&&pcmPlayer!=null&&!"paused".equals(status)){status="loading";emit(true);}});}
            @Override public void completed(){main.post(()->{if(valid(token)&&pcmPlayer!=null&&!"paused".equals(status))advance(false,true);});}
            @Override public void failed(){main.post(()->{if(valid(token))fail("生成中の音声を再生できませんでした。再試行するか端末音声へ切り替えてください。");});}
        });
        pcmPlayer=stream;stream.start(speed,"paused".equals(status));emit(false);
    }

    private void playCached(SpeechAudio.Result result,long token){
        if(!valid(token))return;
        generating=null;audioCached=true;generationElapsedMs=-1;firstAudioMs=-1;
        try{
            MediaPlayer media=new MediaPlayer();player=media;playerPrepared=false;
            media.setAudioAttributes(attributes);media.setWakeMode(SpeechService.this,PowerManager.PARTIAL_WAKE_LOCK);
            media.setDataSource(result.file.getAbsolutePath());
            media.setOnPreparedListener(p->{if(!valid(token)||p!=player)return;
                try{
                    playerPrepared=true;
                    if(pendingSeekMs>=0){
                        long target=pendingSeekMs;
                        p.setOnSeekCompleteListener(m->{if(!valid(token)||m!=player)return;pendingSeekMs=-1;m.setOnSeekCompleteListener(null);startPreparedCached(m,token);});
                        p.seekTo((int)Math.max(0,Math.min(p.getDuration(),target)));emit(true);
                    }else startPreparedCached(p,token);
                }
                catch(Exception e){fail("この端末では音声を再生できませんでした。端末音声に切り替えてください。");}});
            media.setOnCompletionListener(p->{if(valid(token)&&p==player)advance(false,true);});
            media.setOnErrorListener((p,what,extra)->{if(valid(token)&&p==player)fail("保存音声を再生できません。音声キャッシュを削除して再試行してください。");return true;});
            media.prepareAsync();
        }catch(Exception e){fail("保存音声を開けませんでした。音声キャッシュを削除して再試行してください。");}
    }

    private void startPreparedCached(MediaPlayer media,long token){
        if(!valid(token)||media!=player)return;
        try{if(!"paused".equals(status)){media.setPlaybackParams(new PlaybackParams().setSpeed(speed).setPitch(1));media.start();status="playing";prefetchNext();}emit(true);}
        catch(Exception e){fail("この端末では音声を再生できませんでした。端末音声に切り替えてください。");}
    }

    private void prefetchNext() {
        JSONObject aheadSettings=store.settings();
        if(preview||!activePlayback()||!"offline".equals(engine)||!"offline".equals(aheadSettings.optString("engine"))
                ||unitIndex+1>=units.length())return;
        long buffered=0;for(Ahead value:ahead.values()){
            if(value.job!=null||value.failure!=null)return;
            if(value.result!=null)buffered+=value.result.durationMs;
        }
        if(ahead.size()>=MAX_AHEAD_UNITS||buffered>=AHEAD_TARGET_MS)return;
        int index=unitIndex+1;while(ahead.containsKey(index))index++;
        if(index>=units.length()||index>unitIndex+MAX_AHEAD_UNITS)return;
        JSONObject next=units.optJSONObject(index);if(next==null)return;
        JSONObject nextSpeaker=new JSONObject();JSONArray cast=store.cast(bookId);
        for(int i=0;i<cast.length();i++){JSONObject s=cast.optJSONObject(i);if(s!=null&&next.optString("speakerId").equals(s.optString("id"))){nextSpeaker=s;break;}}
        try{
            Ahead entry=new Ahead(bookId,chapterUrl,index,SpeechAudio.requestKey(bookId,next,nextSpeaker,aheadSettings));
            ahead.put(index,entry);entry.job=audio.generate(bookId,next,nextSpeaker,aheadSettings,streamCallback(entry));
        }catch(Exception ignored){}
    }

    private void playDevice() {
        if(ttsReady){speakDevice();return;}
        if(ttsStarting)return;ttsStarting=true;
        final long initToken=++ttsEpoch;
        main.postDelayed(()->{if(!destroyed&&initToken==ttsEpoch&&ttsStarting&&!ttsReady){ttsEpoch++;ttsStarting=false;if(tts!=null){tts.shutdown();tts=null;}
            if("device".equals(engine)&&"loading".equals(status))fail("端末の音声エンジンが応答しません。Androidの音声設定を確認してください。");}},12000);
        try{
            tts=new TextToSpeech(getApplicationContext(),result->main.post(()->{
                if(destroyed||initToken!=ttsEpoch)return;ttsStarting=false;
                if(result!=TextToSpeech.SUCCESS||tts==null){if(tts!=null){tts.shutdown();tts=null;}
                    if("device".equals(engine))fail("端末の音声エンジンを開始できません。Androidの音声設定を確認してください。");return;}
                ttsReady=true;tts.setAudioAttributes(attributes);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
                    @Override public void onStart(String id){main.post(()->{if(id.equals(utterance)&&"loading".equals(status)){status="playing";emit(true);}});}
                    @Override public void onDone(String id){main.post(()->{if(id.equals(utterance)&&activePlayback())advance(false,true);});}
                    @Override public void onError(String id){onError(id,TextToSpeech.ERROR);}
                    @Override public void onError(String id,int code){main.post(()->{if(id.equals(utterance))fail("端末音声を読み上げられませんでした。音声データとネットワーク設定を確認してください。");});}
                });
                if("device".equals(engine)&&"loading".equals(status))speakDevice();
            }));
        }catch(Exception e){ttsEpoch++;ttsStarting=false;fail("端末の音声エンジンを開始できませんでした。");}
    }

    private void speakDevice() {
        String text=SpeechAudio.playbackText(current);
        if(text.trim().isEmpty()||text.length()>TextToSpeech.getMaxSpeechInputLength()){
            fail("読み上げ区間を利用できません。章の読み上げデータを作り直してください。");return;}
        String chosen=speaker.optString("deviceVoice","");if(chosen.isEmpty())chosen=settings.optString("deviceVoice","");
        try {
            if(!chosen.isEmpty()){
                Voice selected=null;Set<Voice> voices=tts.getVoices();if(voices!=null)for(Voice v:voices)if(chosen.equals(v.getName())){selected=v;break;}
                if(selected==null||tts.setVoice(selected)==TextToSpeech.ERROR){fail("設定した端末音声が見つかりません。声を選び直してください。");return;}
            }else{
                Locale locale=SpeechAudio.hasJapanese(text)?Locale.JAPANESE:Locale.US;
                int result=tts.setLanguage(locale);
                if(result==TextToSpeech.LANG_MISSING_DATA||result==TextToSpeech.LANG_NOT_SUPPORTED){fail(locale==Locale.JAPANESE?
                        "日本語の端末音声データがありません。Androidの音声設定で追加してください。":"英語の端末音声データがありません。Androidの音声設定で追加してください。");return;}
                // Prefer an installed offline voice if the engine exposes one for this language.
                Voice offline=null;Set<Voice> voices=tts.getVoices();if(voices!=null)for(Voice v:voices)
                    if(!v.isNetworkConnectionRequired()&&locale.getLanguage().equals(v.getLocale().getLanguage())
                            &&(offline==null||v.getQuality()>offline.getQuality()))offline=v;
                if(offline!=null)tts.setVoice(offline);
            }
            Voice selected=tts.getVoice();selectedDeviceVoice=selected==null?"":selected.getName();deviceNetwork=selected!=null&&selected.isNetworkConnectionRequired();
            if(tts.setSpeechRate((float)SpeechAudio.clamp(speed*current.optDouble("rate",1)*speaker.optDouble("rate",1),.25,4))==TextToSpeech.ERROR
                    ||tts.setPitch((float)SpeechAudio.clamp(current.optDouble("pitch",1)*speaker.optDouble("pitch",1),.5,2))==TextToSpeech.ERROR){
                fail("端末音声の速度または高さを設定できませんでした。別の声を選んでください。");return;}
            utterance=generation+":"+unitIndex;audioCached=false;
            if(tts.speak(text,TextToSpeech.QUEUE_FLUSH,new Bundle(),utterance)==TextToSpeech.ERROR)
                fail("端末音声の読み上げを開始できませんでした。音声設定を確認してください。");
        }catch(Exception e){fail("端末音声を利用できませんでした。Androidの音声設定を確認してください。");}
    }

    private void pausePlayback(boolean abandonFocus) {
        if(!activePlayback()&&!"paused".equals(status))return;
        if(pcmPlayer!=null){
            pcmPlayer.pause();cancelPrefetch();status="paused";pausedSince=SystemClock.elapsedRealtime();
            savePosition();releaseWake();if(abandonFocus)abandonFocus();emit(true);return;
        }
        generation++;
        if(generating!=null){generating.cancel();generating=null;}cancelPrefetch();
        utterance="";if(tts!=null)tts.stop();
        if(player!=null){if(playerPrepared){try{if(player.isPlaying())player.pause();}catch(Exception e){releasePlayer();}}
            else releasePlayer();}
        status="paused";pausedSince=SystemClock.elapsedRealtime();savePosition();releaseWake();
        if(abandonFocus)abandonFocus();emit(true);
    }

    private void resumePlayback() {
        if(units.length()==0){fail("読み上げる章を開いて再生してください。");return;}
        settings=store.settings();String newEngine=preview&&!previewEngine.isEmpty()?previewEngine:settings.optString("engine","offline");
        if("paused".equals(status)&&"offline".equals(engine)&&newEngine.equals(engine)&&pendingSeekMs>=0){
            long target=pendingSeekMs;cancelCurrent(false);pendingSeekMs=target;
            ensureForeground();if(!requestFocus()){fail("音声の再生を再開できません。ほかの音声を停止して再試行してください。");return;}
            status="loading";error="";pausedSince=0;holdWake();generateAudio();emit(true);return;
        }
        if("paused".equals(status)&&"offline".equals(engine)&&newEngine.equals(engine)&&pcmPlayer!=null){
            ensureForeground();if(!requestFocus()){fail("音声の再生を再開できません。ほかの音声を停止して再試行してください。");return;}
            if(pcmPlayer.finished()){advance(false,true);return;}
            status="loading";error="";pausedSince=0;holdWake();pcmPlayer.resume(speed);emit(true);prefetchNext();return;
        }
        if("paused".equals(status)&&"offline".equals(engine)&&newEngine.equals(engine)&&player!=null&&playerPrepared){
            ensureForeground();if(!requestFocus()){fail("音声の再生を再開できません。ほかの音声を停止して再試行してください。");return;}
            try{player.setPlaybackParams(new PlaybackParams().setSpeed(speed).setPitch(1));player.start();
                status="playing";error="";pausedSince=0;holdWake();resetPlayerCallbacks();emit(true);prefetchNext();}
            catch(Exception e){fail("音声の再生を再開できませんでした。");}
        }else playUnit();
    }

    private void resetPlayerCallbacks(){
        if(player==null)return;final long token=generation;
        player.setOnCompletionListener(p->{if(valid(token)&&p==player)advance(false,true);});
        player.setOnErrorListener((p,what,extra)->{if(valid(token)&&p==player)fail("音声の再生中に問題が発生しました。再試行してください。");return true;});
    }

    private void jump(int target,boolean play) {
        if(units.length()==0)return;
        cancelCurrent(true);unitIndex=Math.max(0,Math.min(units.length()-1,target));completed=false;
        if(play)playUnit();else{refreshUnit();status="paused";pausedSince=SystemClock.elapsedRealtime();savePosition();releaseWake();abandonFocus();emit(true);}
    }

    private void advance(boolean explicit,boolean play) {
        try{advanceInternal(explicit,play);}catch(Exception e){fail(dataError(e));}
    }

    private void advanceInternal(boolean explicit,boolean play) {
        if(units.length()==0)return;
        if(unitIndex+1<units.length()){
            cancelCurrent(false);unitIndex++;
            if(play)playUnit();else{refreshUnit();status="paused";pausedSince=SystemClock.elapsedRealtime();savePosition();emit(true);}return;
        }
        if(preview){stopPlayback(true);return;}
        if(!explicit)SpeechContent.completed(this,bookId,chapterUrl);
        settings=store.settings();
        if(!explicit&&!settings.optBoolean("autoNext",true)){finishChapter();return;}
        String next=SpeechContent.next(this,bookId,chapterUrl);
        if(next.isEmpty()){finishChapter();return;}
        if(visited.contains(next)){fail("次の話のリンクが循環しています。目次から章を選んでください。");return;}
        cancelCurrent(true);status="loading";error="";holdWake();emit(true);final long token=generation;final String id=bookId;
        SpeechContent.load(this,id,next,new SpeechContent.Callback(){
            @Override public void loaded(JSONObject chapter){
                if(!valid(token))return;
                planWorker.submit(()->{
                    try{
                        JSONObject prepared=store.prepare(id,chapter);
                        main.post(()->{if(!valid(token))return;chapterUrl=chapter.optString("url",next);visited.add(chapterUrl);
                            plan=prepared;units=Json.arr(plan,"units");unitIndex=0;
                            if(units.length()==0){fail("次の話に読み上げる本文がありません。目次から確認してください。");return;}
                            if(play)playUnit();else{refreshUnit();status="paused";pausedSince=SystemClock.elapsedRealtime();savePosition();releaseWake();abandonFocus();emit(true);}});
                    }catch(Exception e){main.post(()->{if(valid(token))fail("次の話の読み上げデータを保存できませんでした。空き容量を確認してください。");});}
                });
            }
            @Override public void failed(String message){main.post(()->{if(valid(token))fail(message);});}
        });
    }

    private void finishChapter() {
        cancelCurrent(true);completed=true;status="idle";error="";savePosition();releaseWake();abandonFocus();emit(true);
        stopForeground(STOP_FOREGROUND_REMOVE);foreground=false;stopSelf();
    }

    private void changeSpeed(float value) {
        speed=value;
        if(pcmPlayer!=null){pcmPlayer.speed(speed);emit(true);return;}
        if(player!=null&&playerPrepared)try{if("playing".equals(status))
            player.setPlaybackParams(new PlaybackParams().setSpeed(speed).setPitch(1));}
        catch(Exception e){fail("この端末では再生速度を変更できませんでした。");return;}
        else if("device".equals(engine)&&activePlayback()){playUnit();return;}
        emit(true);
    }

    private void setSleep(int minutes) {
        minutes=Math.max(0,Math.min(180,minutes));
        sleepAt=minutes==0?0:System.currentTimeMillis()+minutes*60000L;
        sleepDeadline=minutes==0?0:SystemClock.elapsedRealtime()+minutes*60000L;
    }

    private void clearCache() {
        savePosition();cancelCurrent(true);status="loading";clearing=true;error="";releaseWake();abandonFocus();emit(true);
        audio.clearCache(removed->{if(destroyed)return;removedBytes=removed;clearing=false;status="idle";emit(true);
            stopForeground(STOP_FOREGROUND_REMOVE);foreground=false;stopSelf();});
    }

    private void deleteBook(String id){
        if(id.isEmpty())return;
        boolean currentBook=id.equals(bookId);
        if(currentBook){savePosition();cancelCurrent(true);status="idle";bookId="";chapterUrl="";
            units=new JSONArray();plan=new JSONObject();current=new JSONObject();unitIndex=0;preview=false;
            releaseWake();abandonFocus();emit(true);}
        // Both barriers survive a normal onDestroy; no late plan/audio commit can recreate this book.
        CountDownLatch audioQuiet=new CountDownLatch(1);audio.afterPendingOnWorker(audioQuiet::countDown);
        planWorker.submit(()->{
            try{audioQuiet.await();store.deleteBook(id);}
            catch(InterruptedException e){Thread.currentThread().interrupt();}
            finally{main.post(()->{if(!destroyed&&currentBook&&bookId.isEmpty()){
                stopForeground(STOP_FOREGROUND_REMOVE);foreground=false;stopSelf();}});}
        });
    }

    private void stopPlayback(boolean clearError) {
        savePosition();cancelCurrent(true);resumeOnFocusGain=false;status="idle";if(clearError)error="";
        sleepAt=0;sleepDeadline=0;releaseWake();abandonFocus();emit(true);
        stopForeground(STOP_FOREGROUND_REMOVE);foreground=false;stopSelf();
    }

    private void fail(String message) {
        cancelCurrent(true);status="error";error=message;resumeOnFocusGain=false;savePosition();releaseWake();abandonFocus();emit(true);
        // Leave a paused/error notification so retry is a deliberate action.
        if(foreground){stopForeground(STOP_FOREGROUND_DETACH);foreground=false;}
    }

    private boolean valid(long token){return !destroyed&&generation==token;}
    private boolean activePlayback(){return "playing".equals(status)||"loading".equals(status)&&!clearing;}
    private void cancelCurrent(boolean cancelAhead) {
        generation++;utterance="";pendingSeekMs=-1;
        if(generating!=null){generating.cancel();generating=null;}
        if(cancelAhead)cancelPrefetch();
        if(tts!=null)tts.stop();releasePlayer();
    }
    private void cancelAhead(Ahead value){if(value==null)return;if(value.job!=null)value.job.cancel();if(value.source!=null)value.source.close();}
    private void cancelPrefetch(){for(Ahead value:new ArrayList<>(ahead.values()))cancelAhead(value);ahead.clear();}
    private int bufferedUnits(){int count=0;for(Ahead value:ahead.values())if(value.result!=null)count++;return count;}
    private void releasePlayer(){PcmPlayback stream=pcmPlayer;pcmPlayer=null;if(stream!=null)stream.close();MediaPlayer previous=player;player=null;playerPrepared=false;
        if(previous!=null){try{previous.reset();}catch(Exception ignored){}try{previous.release();}catch(Exception ignored){}}}
    private void savePosition(){if(!preview&&!bookId.isEmpty()&&!chapterUrl.isEmpty()&&units.length()>0)
        try{store.savePosition(bookId,chapterUrl,unitIndex,plan.optString("inputHash"));positionWarning="";}
        catch(Exception ignored){positionWarning="読み上げ位置を保存できません。端末の空き容量を確認してください。";}}

    private static String dataError(Exception e){return "CHAPTER_CHANGED".equals(e.getMessage())?
            "本文が更新されました。章を開き直して再生してください。":"読み上げデータを読み込めませんでした。章を開き直してください。";}

    private void onFocusChanged(int change) {
        if(destroyed)return;
        if(change==AudioManager.AUDIOFOCUS_GAIN){hasFocus=true;if(resumeOnFocusGain){resumeOnFocusGain=false;try{resumePlayback();}catch(Exception e){fail("音声の再生を再開できませんでした。");}}}
        else if(change==AudioManager.AUDIOFOCUS_LOSS){hasFocus=false;resumeOnFocusGain=false;pausePlayback(true);}
        else if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT||change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK){
            resumeOnFocusGain=activePlayback();hasFocus=false;pausePlayback(false);}
    }
    private boolean requestFocus(){if(hasFocus)return true;
        hasFocus=audioManager!=null&&audioManager.requestAudioFocus(focusRequest)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;return hasFocus;}
    private void abandonFocus(){if(audioManager!=null&&focusRequest!=null)audioManager.abandonAudioFocusRequest(focusRequest);hasFocus=false;}
    private void holdWake(){if(wake!=null){wake.acquire(10*60*1000L);lastWakeRenewal=SystemClock.elapsedRealtime();}}
    private void releaseWake(){if(wake!=null&&wake.isHeld())wake.release();}

    private int elapsed(){if(pcmPlayer!=null)return(int)pcmPlayer.positionMs();if(player!=null&&playerPrepared)try{return player.getCurrentPosition();}catch(Exception ignored){}return 0;}
    private int duration(){if(pcmPlayer!=null)return(int)pcmPlayer.durationMs();if(player!=null&&playerPrepared)try{return player.getDuration();}catch(Exception ignored){}return 0;}
    private void emit(boolean notification) {
        if(generating!=null){generationElapsedMs=generating.elapsedMs();firstAudioMs=generating.firstAudioMs();}
        JSONObject state=Json.obj("status",status,"bookId",bookId,"chapterUrl",chapterUrl,"title",plan.optString("title"),
                "unitIndex",unitIndex,"unitCount",units.length(),"paragraph",current.optInt("paragraph"),
                "unitId",current.optString("unitId"),"speakerId",current.optString("speakerId"),"kind",current.optString("kind","narration"),
                "emotion",current.optString("emotion","neutral"),"error",error,"engine",engine,"speed",speed,
                "sleepAt",sleepAt,"pauseMode","offline".equals(engine)?"exact":"unit","elapsedMs",elapsed(),"durationMs",duration(),
                "preview",preview,"completed",completed,"clearingCache",clearing,"audioCached",audioCached,
                "voice","offline".equals(engine)?SpeechAudio.voice(current,speaker,settings):selectedDeviceVoice,
                "deviceNetwork",deviceNetwork,"offlineGenerated","offline".equals(engine),"speechProvider","voicevox");
        if (preview && previewMode!=null) Json.put(state,"auditionMode",previewMode.id);
        Json.put(state,"positionWarning",positionWarning);
        Json.put(state,"generationElapsedMs",generationElapsedMs);Json.put(state,"firstAudioMs",firstAudioMs);
        Json.put(state,"bufferedUnits",bufferedUnits());Json.put(state,"audioStreaming",pcmPlayer!=null);
        Json.put(state,"audioGenerating",generating!=null);
        if(removedBytes>=0)Json.put(state,"removedBytes",removedBytes);publish(state);
        if(session!=null){
            int playback="playing".equals(status)?PlaybackState.STATE_PLAYING:"loading".equals(status)?PlaybackState.STATE_BUFFERING:
                    "paused".equals(status)?PlaybackState.STATE_PAUSED:"error".equals(status)?PlaybackState.STATE_ERROR:PlaybackState.STATE_STOPPED;
            long actions=PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_PLAY_PAUSE|PlaybackState.ACTION_STOP
                    |PlaybackState.ACTION_SKIP_TO_NEXT|PlaybackState.ACTION_SKIP_TO_PREVIOUS;
            if(playerPrepared||pcmPlayer!=null)actions|=PlaybackState.ACTION_SEEK_TO;
            PlaybackState.Builder stateBuilder=new PlaybackState.Builder().setActions(actions)
                    .setState(playback,elapsed(),"playing".equals(status)?speed:0,SystemClock.elapsedRealtime());
            if(!error.isEmpty())stateBuilder.setErrorMessage(error);session.setPlaybackState(stateBuilder.build());
            if(notification)session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,plan.optString("title","つづき"))
                    .putString(MediaMetadata.METADATA_KEY_ARTIST,"offline".equals(engine)?"つづき · オフライン音声":"つづき · 端末音声")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION,duration()).build());
        }
        if(notification&&(foreground||!"idle".equals(status)))try{NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(n!=null)n.notify(NOTIFICATION_ID,notification());}catch(RuntimeException ignored){}
    }

    private void ensureForeground() {
        if(foreground)return;Notification notice=notification();
        if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION_ID,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIFICATION_ID,notice);foreground=true;
    }
    private Notification notification() {
        boolean playing=activePlayback();String mode="offline".equals(engine)?"オフライン音声":"端末音声";
        String line=mode+" · "+(units.length()==0?"準備中":(unitIndex+1)+" / "+units.length()+"区間");
        if("loading".equals(status))line=mode+" · "+(clearing?"キャッシュを削除中":"準備中");
        if("paused".equals(status))line+=" · 一時停止";
        if("error".equals(status))line=error;
        Intent open=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content=PendingIntent.getActivity(this,1200,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(plan.optString("title","つづき 読み上げ")).setContentText(line).setContentIntent(content)
                .setOnlyAlertOnce(true).setOngoing(playing).setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT).setDeleteIntent(actionIntent("stop",1204))
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous,"前の区間",actionIntent("previous",1201)).build())
                .addAction(new Notification.Action.Builder(playing?android.R.drawable.ic_media_pause:android.R.drawable.ic_media_play,
                        playing?"一時停止":"再生",actionIntent(playing?"pause":"resume",1202)).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next,"次の区間",actionIntent("next",1203)).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"停止",actionIntent("stop",1204)).build());
        if(session!=null)builder.setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0,1,2));
        return builder.build();
    }
    private PendingIntent actionIntent(String action,int request) {
        JSONObject payload=Json.obj("bookId",bookId,"chapterUrl",chapterUrl,"unitIndex",unitIndex,"inputHash",plan.optString("inputHash"));
        Intent intent=new Intent(this,SpeechService.class).setAction(ACTION_PREFIX+action).putExtra("payload",payload.toString());
        return PendingIntent.getForegroundService(this,request,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }

    @Override public void onDestroy(){
        savePosition();destroyed=true;cancelCurrent(true);main.removeCallbacksAndMessages(null);releaseWake();abandonFocus();
        if(tts!=null){tts.shutdown();tts=null;}ttsReady=false;audio.close();planWorker.shutdown();
        if(receiverRegistered)try{unregisterReceiver(noisy);}catch(Exception ignored){}
        if(session!=null){session.setActive(false);session.release();}
        if(instance==this)instance=null;super.onDestroy();
    }
}
