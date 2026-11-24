import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import jp.hiroshiba.voicevoxcore.AccelerationMode;
import jp.hiroshiba.voicevoxcore.AudioQuery;
import jp.hiroshiba.voicevoxcore.blocking.Onnxruntime;
import jp.hiroshiba.voicevoxcore.blocking.OpenJtalk;
import jp.hiroshiba.voicevoxcore.blocking.Synthesizer;
import jp.hiroshiba.voicevoxcore.blocking.VoiceModelFile;

/** Runs the real upstream desktop JNI/ONNX/model artifacts, not an Android emulator. */
public final class VoicevoxNativeSmoke {
    private static int uint16(byte[] b, int at) { return (b[at]&255) | (b[at+1]&255)<<8; }
    private static long uint32(byte[] b, int at) {
        return (b[at]&255L) | (b[at+1]&255L)<<8 | (b[at+2]&255L)<<16 | (b[at+3]&255L)<<24;
    }
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]).toAbsolutePath();
        Path output = Paths.get(args[1]).toAbsolutePath();
        Files.createDirectories(output);
        Onnxruntime ort = Onnxruntime.loadOnce().filename(root.resolve("linux-x64/libvoicevox_onnxruntime.so.1.23.2").toString()).perform();
        OpenJtalk dictionary = new OpenJtalk(root.resolve("dictionary/open_jtalk_dic_utf_8-1.11").toString());
        Synthesizer synth = Synthesizer.builder(ort, dictionary).accelerationMode(AccelerationMode.CPU).cpuNumThreads(2).build();
        int[] files = {0, 1, 2, 4, 5, 9, 10, 11, 15};
        int[][] styles = {{0,1,2,3,4,5,6,7,8,10}, {14}, {15,16,17,18}, {11}, {19,22,36,37,38},
                {12,32,33,34,35}, {39,40,41}, {43,44,45}, {13,75,76,81,82,83,84,85,86}};
        int[] normal = {2,3,8,10,11,12,13,14,16,43};
        int count = 0;
        for (int i = 0; i < files.length; i++) {
            long loading = System.nanoTime();
            try (VoiceModelFile model = new VoiceModelFile(root.resolve("models/"+files[i]+".vvm").toString())) {
                synth.loadVoiceModel(model).perform();
                System.out.println("MODEL " + files[i] + " load_ms=" + (System.nanoTime()-loading)/1000000);
                for (int style : styles[i]) {
                    long start = System.nanoTime();
                    AudioQuery query = synth.createAudioQuery("音声の確認です。次のページを読みます。", style);
                    query.speedScale = 1.05;
                    query.pitchScale = 0.015;
                    query.intonationScale = 1.15;
                    query.volumeScale = .85;
                    query.prePhonemeLength = .04;
                    query.postPhonemeLength = .1;
                    query.outputSamplingRate = 24000;
                    query.outputStereo = false;
                    byte[] wave = synth.synthesis(query, style).interrogativeUpspeak(true).perform();
                    if (wave.length < 44 || !new String(wave,0,4,"US-ASCII").equals("RIFF")
                            || !new String(wave,8,4,"US-ASCII").equals("WAVE")) throw new AssertionError("Invalid RIFF style "+style);
                    int pcm = -1, length = -1; boolean format = false;
                    for (int offset = 12; offset + 8 <= wave.length;) {
                        String id = new String(wave,offset,4,"US-ASCII"); int n = (int)uint32(wave,offset+4);
                        if (n < 0 || offset+8L+n > wave.length) throw new AssertionError("Invalid chunk");
                        if (id.equals("fmt ")) {
                            if (n < 16 || uint16(wave,offset+8)!=1 || uint16(wave,offset+10)!=1
                                    || uint32(wave,offset+12)!=24000 || uint16(wave,offset+22)!=16) throw new AssertionError("Invalid format");
                            format = true;
                        }
                        if (id.equals("data")) { pcm = offset+8; length=n; }
                        offset += 8+n+(n&1);
                    }
                    if (!format || pcm < 0 || length < 24000) throw new AssertionError("Missing audio style "+style);
                    long sum = 0; int peak = 0;
                    for (int j = pcm; j+1 < pcm+length; j+=2) { int sample = (short)uint16(wave,j); peak=Math.max(peak,Math.abs(sample)); sum += (long)sample*sample; }
                    double rms = Math.sqrt((double)sum/(length/2));
                    if (rms < 10 || peak < 100) throw new AssertionError("Silent audio style "+style);
                    boolean save = false; for (int standard : normal) if (style == standard) save = true;
                    if (save) Files.write(output.resolve("voicevox-style-"+style+".wav"),wave);
                    System.out.println("PASS style="+style+" duration_s="+String.format(java.util.Locale.ROOT,"%.2f",length/48000d)
                            +" peak="+peak+" rms="+Math.round(rms)+" synthesis_ms="+(System.nanoTime()-start)/1000000);
                    count++;
                }
                synth.unloadVoiceModel(model.id);
            }
        }
        System.out.println("PASS: "+count+" real native styles across 10 characters; 9 verified VVM files. Platform=Linux x86_64, not Android device.");
    }
}
