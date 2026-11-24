package jp.haruyalab.novelshelf;

import org.json.JSONObject;

/** Local interpretations of stored directions. Values are relative to this character's own voice. */
enum DialogueActing {
    // Tempo, intonation, start/crest/end pitch in semitones, initial/final vowel length, onset/crest/end gain in dB.
    TALK     (1.03,1.04,  .3,  .9, -1.0,  .97,1.03,  -.4, .5,-1.2),
    STARTLED (1.16,1.18, 1.8, 4.0, -1.5,  .80, .75, -2.5,2.1,-4.5),
    CALL     (1.23,1.20, 1.0, 3.1,  -.5,  .78,1.12,  1.0,3.4,-1.4),
    ANGRY    (1.10,1.16,  .9, 2.5, -2.7,  .85, .79,   .9,3.1,-1.1),
    DECISIVE ( .94,1.10,  .5, 2.0, -3.2, 1.02, .80,   .4,2.2,-1.7),
    TENDER   ( .86, .93, -.6,  .5, -2.3, 1.12,1.33, -3.4,-2.2,-7.0),
    UNEASY   ( .90,1.07,-1.8, 1.1,  -.2, 1.38,1.14, -4.0,-1.0,-4.4),
    SAD      ( .84, .88,-1.0,  .1, -2.8, 1.13,1.40, -4.0,-2.6,-7.2),
    INNER    ( .98,1.00, -.6,  .5, -1.0, 1.04,1.08, -1.6, -.8,-2.8),
    HUSHED   ( .92, .89,-1.0, -.2, -1.7, 1.04,1.17, -5.8,-4.2,-8.0),
    BRIGHT   (1.12,1.17,  .4, 2.9,   .4,  .90,1.08,   .1,1.8,-1.0),
    QUESTION (1.02,1.08, -.4, 1.0,  2.3,  .98,1.12,  -.7, .6,-1.6);

    final double tempo, intonation, startPitch, peakPitch, endPitch, startLength, endLength;
    private final double startDb, peakDb, endDb;
    DialogueActing(double tempo,double intonation,double startPitch,double peakPitch,double endPitch,
                   double startLength,double endLength,double startDb,double peakDb,double endDb) {
        this.tempo=tempo; this.intonation=intonation; this.startPitch=startPitch; this.peakPitch=peakPitch; this.endPitch=endPitch;
        this.startLength=startLength; this.endLength=endLength; this.startDb=startDb; this.peakDb=peakDb; this.endDb=endDb;
    }

    static boolean eligible(JSONObject unit) {
        return unit!=null && ("dialogue".equals(unit.optString("kind")) || "thought".equals(unit.optString("kind")));
    }

    static DialogueActing select(JSONObject direction, String text, boolean thought) {
        String delivery=direction.optString("delivery","natural"), emotion=direction.optString("emotion","neutral");
        String trimmed=text.trim().replaceAll("^[「『（]+|[」』）]+$","").trim();
        if ("whisper".equals(delivery) || "whisper".equals(emotion)) return HUSHED;
        if ("anger".equals(emotion)) return ANGRY;
        if ("joy".equals(emotion)) return BRIGHT;
        if ("surprise".equals(emotion)) return STARTLED;
        if ("fear".equals(emotion)) return UNEASY;
        if ("sadness".equals(emotion)) return SAD;
        if ("urgent".equals(delivery)) return CALL;
        if ("determination".equals(emotion) || "firm".equals(delivery)) return DECISIVE;
        if ("hesitant".equals(delivery) || "embarrassment".equals(emotion)) return UNEASY;
        if (trimmed.matches("(?:え[っッ]?|は[っッ]|ええ[っッ]|あ[っッ])[！？?!…。、・]*")) return STARTLED;
        if ("soft".equals(delivery) || "calm".equals(emotion) || "relief".equals(emotion)) return TENDER;
        if ("tension".equals(emotion)) return UNEASY;
        if (thought) return INNER;
        if (trimmed.endsWith("！") || trimmed.endsWith("!")) return CALL;
        if (trimmed.endsWith("？") || trimmed.endsWith("?")) return QUESTION;
        if (trimmed.endsWith("…") || trimmed.endsWith("...")) return UNEASY;
        return TALK;
    }

    static double weight(JSONObject part) {
        return .72 + .28 * Math.max(0,Math.min(1,part.optDouble("intensity",0)));
    }

    double weight(JSONObject part, DialogueMode mode) {
        if (mode != DialogueMode.NATURAL) return weight(part);
        if (this == TALK) return 0;
        double intensity = Math.max(0, Math.min(1, part.optDouble("intensity", 0)));
        return .12 + .28 * intensity;
    }

    int conversationPause(String text, int authored, boolean manual) {
        if (manual) return authored;
        // Keep short scripted pauses. Long model-generated pauses taper towards conversational timing.
        int pause = authored > 220 ? 220 + (int) Math.round((authored - 220) * .55) : authored;
        if (text.contains("…") || text.contains("...")) return Math.max(pause, this == STARTLED ? 160 : 130);
        if (this == STARTLED) return Math.max(pause, 75);
        if (this == CALL || this == ANGRY) return Math.max(pause, 55);
        return pause;
    }

    int conversationStyle(String voice, JSONObject part, boolean manual) {
        // Ordinary lines stay in one register. Preserve a requested whisper or a manually fixed character direction.
        return OfflineVoices.styleId(voice, manual || this == HUSHED ? part : null);
    }

    static JSONObject conversationControls(JSONObject stored, boolean manual) {
        if (manual) return stored;
        JSONObject part = Json.copy(stored);
        for (String field : new String[]{"rate", "pitch", "intonation", "volume"})
            if (part.has(field)) Json.put(part, field, 1 + .58 * (part.optDouble(field, 1) - 1));
        return part;
    }

    int pause(String text, int authored, boolean manual) {
        if (manual) return authored;
        if (text.contains("…") || text.contains("...")) return Math.max(authored,this==STARTLED ? 430 : 320);
        if (this==STARTLED) return Math.max(authored,190);
        if (this==CALL || this==ANGRY) return Math.max(authored,110);
        if (this==UNEASY && (text.contains("、") || text.contains(","))) return Math.max(authored,175);
        if (text.trim().matches(".*。[」』）]*$")) return Math.max(authored,120);
        return authored;
    }

    int style(String voice, JSONObject part) {
        int stored=OfflineVoices.styleId(voice,part);
        int normal=OfflineVoices.styleId(voice,null);
        if (stored!=normal) return stored;
        if (this==HUSHED && "whisper".equals(part.optString("delivery")))
            return OfflineVoices.styleId(voice,Json.obj("delivery","whisper"));
        if ("kotaro".equals(voice)) {
            if (this==STARTLED || this==UNEASY) return 33;
            if (this==ANGRY) return 34;
            if (this==SAD) return 35;
            if (this==BRIGHT) return 32;
        }
        if (this==TENDER || this==ANGRY)
            return OfflineVoices.styleId(voice,Json.obj("emotion",this==TENDER?"tender":"defiant","intensity",1));
        return stored;
    }

    static double curve(double x,double start,double peak,double end) {
        double pivot=.32, t=x<pivot?x/pivot:(x-pivot)/(1-pivot);
        t=Math.max(0,Math.min(1,t));
        t=t*t*(3-2*t);
        return x<pivot?start+(peak-start)*t:peak+(end-peak)*t;
    }

    /** Estimate voiced level in 20ms windows; pauses and a wholly silent/very quiet render receive no makeup gain. */
    private static double activeLevel(byte[] pcm) {
        double total=0; int used=0;
        for (int at=0;at<pcm.length;) {
            int end=Math.min(pcm.length,at+960),frames=(end-at)/2;
            double sum=0;
            for (;at<end;at+=2) {
                int v=(short)((pcm[at]&255)|(pcm[at+1]<<8));
                sum+=(double)v*v;
            }
            if (sum>frames*32768.0*32768.0*.000016) { total+=sum; used+=frames; }
        }
        return used==0?Double.NaN:10*Math.log10(total/(used*32768.0*32768.0));
    }

    /** Calibrate projected speech across voices, without raising quiet lines or overriding a manual volume reduction. */
    private double projection(byte[] samples,double volume) {
        double target;
        switch(this) {
            case CALL: target=-18.5; break;
            case ANGRY: target=-19; break;
            case DECISIVE: target=-21; break;
            case STARTLED: target=-24; break;
            default: return 0;
        }
        double level=activeLevel(samples);
        if (!Double.isFinite(level)) return 0;
        target+=20*Math.log10(Math.max(.6,Math.min(1.25,volume)));
        return Math.max(-3,Math.min(5.5,(target-level)*.85));
    }

    /** Multipliers are interpolated per sample between 5ms knots. Apply a soft peak ceiling. */
    void dynamics(byte[] samples,JSONObject part, DialogueMode mode) {
        double weight = weight(part, mode);
        if (weight == 0) return;
        // Carry quiet emphasis in level without enlarging the pitch arc or switching voice model.
        if (mode == DialogueMode.NATURAL) weight *= 1.65;
        double makeUp = mode == DialogueMode.NATURAL ? 0 : projection(samples,part.optDouble("volume",1));
        int frames=samples.length/2,block=120;
        if (frames==0) return;
        double gain=Math.pow(10,(startDb+makeUp)*weight/20);
        for (int n=0;n<frames;) {
            int end=Math.min(frames,n+block), count=end-n;
            double target=Math.pow(10,(curve(end/(double)frames,startDb,peakDb,endDb)+makeUp)*weight/20);
            double step=(target-gain)/count;
            for (;n<end;n++) {
                int i=n*2;
                double value=(short)((samples[i]&255)|(samples[i+1]<<8))*gain;
                double absolute=Math.abs(value);
                if (absolute>28500) value=Math.copySign(28500+4200*(1-Math.exp(-(absolute-28500)/4200)),value);
                int sample=(int)Math.round(value); samples[i]=(byte)sample; samples[i+1]=(byte)(sample>>8);
                gain+=step;
            }
            gain=target;
        }
    }
}
