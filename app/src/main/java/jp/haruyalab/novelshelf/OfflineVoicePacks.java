package jp.haruyalab.novelshelf;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/** A pinned data-only download. Executable native code is always shipped inside the APK. */
public final class OfflineVoicePacks {
    public interface Listener { void onState(JSONObject state); }
    private static final String VERSION = "voicevox-0.17.1-v1";
    private static final String BASE = "https://github.com/VOICEVOX/voicevox_vvm/releases/download/0.17.1/";
    private static final String DICTIONARY_NAME = "open_jtalk_dic_utf_8-1.11.tar.gz";
    private static final String DICTIONARY_ROOT = "open_jtalk_dic_utf_8-1.11";
    public static final long DOWNLOAD_BYTES = 565360558L;
    public static final long INSTALLED_BYTES = 649018528L;
    private static final long DICTIONARY_BYTES = 107304813L;
    private static final long RECOMMENDED_FREE_BYTES = 800000000L;
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Listener listener;
    private static volatile Listener serviceListener;
    private static volatile boolean cancelled;
    private static boolean startRequested;
    private static volatile Thread worker;
    private static volatile HttpURLConnection connection;
    private static volatile String latest;
    private static long lastProgress;

    private static final class Spec {
        final String name, url, sha;
        final long bytes;
        Spec(String name, long bytes, String sha) { this(name, BASE + name, bytes, sha); }
        Spec(String name, String url, long bytes, String sha) {
            this.name = name; this.url = url; this.bytes = bytes; this.sha = sha;
        }
    }
    private static final Spec[] FILES = {
        new Spec(DICTIONARY_NAME, "https://github.com/r9y9/open_jtalk/releases/download/v1.11.1/" + DICTIONARY_NAME,
                23646843L, "fe6ba0e43542cef98339abdffd903e062008ea170b04e7e2a35da805902f382a"),
        new Spec("0.vvm", 59308488L, "ecd35374d4182cd883cba5040376f7f888cc6ba248b1c2f4cea07cdb34bb1318"),
        new Spec("1.vvm", 60571189L, "e16ffb6fc3e13e1be4ebc3831cc39df019cbac96b28bbed7653a4bb51c4d7d5d"),
        new Spec("2.vvm", 59308683L, "2eb9a67e3bce09a7f209eb21a56926fa94476352b6ce874c9acd8f465871a4f4"),
        new Spec("4.vvm", 59304904L, "716d2900f6b93ba22119af0e236c32fdbf6fea81c519b5d4830afcb2db349522"),
        new Spec("5.vvm", 57611312L, "67451cdbc3e473dba4d09c878903a656be9e7030cb5901155e5a51634ed8b318"),
        new Spec("9.vvm", 58328935L, "3dca586c0aa553772419bdc090045f9ea73bd9ed82b88b7dd5737bdd6eddee29"),
        new Spec("10.vvm", 58327087L, "dea2104b05391fdc37d8c27b4104569d5bff3a52bcb9e4077bb2b1f951c7524a"),
        new Spec("11.vvm", 61687324L, "3fb0c0f12ec37fda568020509d6e63a0a6b5d6edf3c2330508c6bf4e3eeb8b82"),
        new Spec("15.vvm", 67265793L, "c29b0c0f8e332fcedc2a5de4b4426feb75fc9f587c314da99af8dbc964287221")
    };
    private static final String[] DICT_NAMES = {
        "matrix.bin", "right-id.def", "pos-id.def", "COPYING", "rewrite.def",
        "left-id.def", "sys.dic", "char.bin", "unk.dic"
    };
    private static final long[] DICT_SIZES = {3792262L, 77672L, 1923L, 5865L, 7457L, 77672L, 103073776L, 262496L, 5690L};

    private final Context context;
    private final File directory;

    public OfflineVoicePacks(Context context) {
        this.context = context.getApplicationContext();
        // noBackupFilesDir avoids trying to back up hundreds of MB of reproducible model data.
        this.directory = new File(this.context.getNoBackupFilesDir(), VERSION);
        synchronized (LOCK) {
            if (latest == null) {
                long bytes = presentDownloadBytes();
                boolean ready = installed();
                latest = state(ready ? "ready" : bytes > 0 ? "paused" : "not_installed",
                        ready ? DOWNLOAD_BYTES : bytes, "", "", "").toString();
            }
        }
    }

    public JSONObject snapshot() { return Json.parse(latest); }
    public static void setListener(Listener value) {
        listener = value;
        if (value != null) MAIN.post(() -> { if (listener == value && latest != null) value.onState(Json.parse(latest)); });
    }

    public void start(Listener value) {
        if (value != null) setListener(value);
        if (isReady()) { publish(state("ready", DOWNLOAD_BYTES, "", "", "")); return; }
        synchronized (LOCK) {
            if (worker != null || startRequested) { publish(snapshot()); return; }
            startRequested = true;
            cancelled = false;
            publish(state("downloading", presentDownloadBytes(), "", "", ""));
        }
        try {
            Intent intent = new Intent(context, OfflineVoiceDownloadService.class)
                    .setAction(OfflineVoiceDownloadService.ACTION_START);
            context.startForegroundService(intent);
        } catch (RuntimeException e) {
            synchronized (LOCK) { startRequested = false; }
            publish(state("error", presentDownloadBytes(), "", "DOWNLOAD_START_FAILED",
                    "音声の取得を開始できません。アプリを開いて再試行してください。"));
        }
    }

    public void cancel() {
        synchronized (LOCK) { cancelled = true; startRequested = false; }
        HttpURLConnection active = connection;
        if (active != null) active.disconnect();
        synchronized (LOCK) {
            if (worker == null && !isReady()) publish(state("paused", presentDownloadBytes(), "", "", ""));
        }
    }

    public boolean isReady() { return installed(); }
    public File dictionaryDirectory() { return new File(directory, "dictionary"); }

    public File modelFile(int styleId) {
        String name;
        switch (styleId) {
            case 0: case 1: case 2: case 3: case 4: case 5: case 6: case 7: case 8: case 10: name = "0.vvm"; break;
            case 14: name = "1.vvm"; break;
            case 15: case 16: case 17: case 18: name = "2.vvm"; break;
            case 11: name = "4.vvm"; break;
            case 19: case 22: case 36: case 37: case 38: name = "5.vvm"; break;
            case 12: case 32: case 33: case 34: case 35: name = "9.vvm"; break;
            case 39: case 40: case 41: name = "10.vvm"; break;
            case 43: case 44: case 45: name = "11.vvm"; break;
            case 13: case 75: case 76: case 81: case 82: case 83: case 84: case 85: case 86: name = "15.vvm"; break;
            default: return null;
        }
        return new File(new File(directory, "models"), name);
    }

    static void observeService(Listener value) { serviceListener = value; }
    static boolean isWorking() { return worker != null; }

    /** Called only after the foreground service has posted its notification. */
    boolean beginDownload() {
        synchronized (LOCK) {
            if (worker != null) { publish(snapshot()); return true; }
            if (!startRequested || cancelled) return false;
            startRequested = false;
            cancelled = false;
            worker = new Thread(this::install, "TsuzukiVoiceDownload");
            worker.setDaemon(true);
            worker.start();
            return true;
        }
    }

    private void install() {
        String finalStatus = "error", code = "DOWNLOAD_FAILED", error = errorMessage("DOWNLOAD_FAILED");
        try {
            mkdir(directory);
            mkdir(new File(directory, "models"));
            mkdir(new File(directory, "downloads"));
            long needed = DOWNLOAD_BYTES - presentDownloadBytes()
                    + (dictionaryValid() ? 0 : DICTIONARY_BYTES) + 64000000L;
            if (directory.getUsableSpace() < needed) throw new IOException("DOWNLOAD_NO_SPACE");
            for (Spec spec : FILES) {
                checkCancelled();
                if (spec == FILES[0] && dictionaryValid()) continue;
                File complete = completeFile(spec);
                if (!verified(complete, spec)) {
                    // A final file without a receipt may be left behind by process death after rename.
                    if (complete.isFile() && complete.length() == spec.bytes && sha256(complete).equals(spec.sha)) {
                        writeReceipt(complete, spec);
                    } else {
                        Files.deleteIfExists(complete.toPath());
                        Files.deleteIfExists(receipt(complete).toPath());
                        download(spec, complete);
                    }
                }
                if (spec == FILES[0]) extractDictionary(complete);
            }
            checkCancelled();
            if (!dictionaryValid()) throw new IOException("DOWNLOAD_INVALID_ARCHIVE");
            for (int i = 1; i < FILES.length; i++)
                if (!verified(completeFile(FILES[i]), FILES[i])) throw new IOException("DOWNLOAD_HASH_MISMATCH");
            writeJson(new File(directory, "installed.json"), Json.obj("version", VERSION));
            // The verified dictionary is now installed; retaining its compressed archive wastes space.
            Files.deleteIfExists(completeFile(FILES[0]).toPath());
            Files.deleteIfExists(receipt(completeFile(FILES[0])).toPath());
            finalStatus = "ready"; code = ""; error = "";
        } catch (Exception e) {
            if (cancelled || e instanceof InterruptedIOException && "DOWNLOAD_CANCELLED".equals(e.getMessage())) {
                finalStatus = "paused";
                code = ""; error = "";
            } else {
                finalStatus = "error";
                code = safeCode(e);
                error = errorMessage(code);
            }
        } finally {
            connection = null;
            synchronized (LOCK) {
                worker = null;
                publish(state(finalStatus, "ready".equals(finalStatus) ? DOWNLOAD_BYTES : presentDownloadBytes(), "", code, error));
            }
        }
    }

    private void download(Spec spec, File complete) throws IOException {
        File part = new File(new File(directory, "downloads"), spec.name + ".part");
        if (part.length() > spec.bytes) Files.deleteIfExists(part.toPath());
        IOException failure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            checkCancelled();
            try {
                long offset = part.length();
                if (offset < spec.bytes) {
                    HttpURLConnection http = open(spec.url, offset);
                    connection = http;
                    try {
                        int status = http.getResponseCode();
                        if (status == HttpURLConnection.HTTP_OK) offset = 0;
                        else if (status == HttpURLConnection.HTTP_PARTIAL) {
                            String range = http.getHeaderField("Content-Range");
                            if (range == null || !range.startsWith("bytes " + offset + "-")
                                    || !range.endsWith("/" + spec.bytes)) throw new IOException("DOWNLOAD_BAD_RESPONSE");
                        } else throw new IOException(status == 403 ? "DOWNLOAD_FORBIDDEN" : "DOWNLOAD_HTTP_ERROR");
                        long length = http.getContentLengthLong();
                        if (length > 0 && length != spec.bytes - offset) throw new IOException("DOWNLOAD_BAD_RESPONSE");
                        try (InputStream in = new BufferedInputStream(http.getInputStream());
                             FileOutputStream raw = new FileOutputStream(part, offset > 0);
                             BufferedOutputStream out = new BufferedOutputStream(raw, 65536)) {
                            byte[] buffer = new byte[65536];
                            int count;
                            while ((count = in.read(buffer)) != -1) {
                                checkCancelled();
                                if (offset + count > spec.bytes) throw new IOException("DOWNLOAD_BAD_RESPONSE");
                                out.write(buffer, 0, count);
                                offset += count;
                                progress("downloading", spec.name, offset, spec);
                            }
                            out.flush();
                            raw.getFD().sync();
                        }
                        if (part.length() != spec.bytes) throw new IOException("DOWNLOAD_INCOMPLETE");
                    } finally {
                        http.disconnect();
                        connection = null;
                    }
                }
                publish(state("verifying", presentDownloadBytes(), spec.name, "", ""));
                if (!sha256(part).equals(spec.sha)) {
                    Files.deleteIfExists(part.toPath());
                    throw new IOException("DOWNLOAD_HASH_MISMATCH");
                }
                checkCancelled();
                move(part, complete);
                writeReceipt(complete, spec);
                return;
            } catch (IOException e) {
                checkCancelled();
                failure = e;
                if (attempt < 2) {
                    try { Thread.sleep((attempt + 1) * 1000L); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new InterruptedIOException("DOWNLOAD_CANCELLED"); }
                }
            }
        }
        throw failure == null ? new IOException("DOWNLOAD_FAILED") : failure;
    }

    private static HttpURLConnection open(String address, long offset) throws IOException {
        URL url = new URL(address);
        for (int redirects = 0; redirects < 6; redirects++) {
            checkCancelled();
            if (!allowed(url)) throw new IOException("DOWNLOAD_BAD_RESPONSE");
            HttpURLConnection http = (HttpURLConnection) url.openConnection();
            connection = http;
            http.setInstanceFollowRedirects(false);
            http.setConnectTimeout(20000);
            http.setReadTimeout(20000);
            http.setRequestProperty("User-Agent", "Tsuzuki/1.9.0 (offline voice data)");
            http.setRequestProperty("Accept-Encoding", "identity");
            if (offset > 0) http.setRequestProperty("Range", "bytes=" + offset + "-");
            int status;
            try { status = http.getResponseCode(); }
            catch (IOException e) { http.disconnect(); connection = null; throw e; }
            if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) return http;
            String next = http.getHeaderField("Location");
            http.disconnect();
            connection = null;
            if (next == null) throw new IOException("DOWNLOAD_BAD_RESPONSE");
            url = new URL(url, next);
        }
        throw new IOException("DOWNLOAD_BAD_RESPONSE");
    }

    private static boolean allowed(URL url) {
        if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null
                || url.getPort() != -1 && url.getPort() != 443) return false;
        String host = url.getHost().toLowerCase(Locale.ROOT);
        return host.equals("github.com") || host.equals("release-assets.githubusercontent.com")
                || host.equals("objects.githubusercontent.com") || host.equals("github-releases.githubusercontent.com");
    }

    private void extractDictionary(File archive) throws IOException {
        publish(state("verifying", presentDownloadBytes(), "日本語辞書を展開", "", ""));
        File stage = new File(directory, "dictionary.pending");
        deleteTree(stage);
        mkdir(stage);
        Set<String> found = new HashSet<>();
        try (InputStream input = new GZIPInputStream(new BufferedInputStream(new FileInputStream(archive)), 65536)) {
            byte[] header = new byte[512], buffer = new byte[65536];
            while (true) {
                checkCancelled();
                readFully(input, header, 512);
                boolean zero = true;
                for (byte value : header) if (value != 0) { zero = false; break; }
                if (zero) break;
                long checksum = 0;
                for (int i = 0; i < 512; i++) checksum += i >= 148 && i < 156 ? 32 : header[i] & 255;
                if (checksum != octal(header, 148, 8)) throw new IOException("DOWNLOAD_INVALID_ARCHIVE");
                String name = cString(header, 0, 100);
                String prefix = cString(header, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
                long size = octal(header, 124, 12);
                int type = header[156];
                if ((name.equals(DICTIONARY_ROOT) || name.equals(DICTIONARY_ROOT + "/")) && type == '5' && size == 0) continue;
                if (!name.startsWith(DICTIONARY_ROOT + "/")) throw new IOException("DOWNLOAD_INVALID_ARCHIVE");
                String child = name.substring(DICTIONARY_ROOT.length() + 1);
                int index = dictionaryIndex(child);
                if (index < 0 || size != DICT_SIZES[index] || type != 0 && type != '0' || !found.add(child))
                    throw new IOException("DOWNLOAD_INVALID_ARCHIVE");
                // A strict nine-file allowlist rejects links, special files and all path traversal.
                File output = new File(stage, child);
                try (FileOutputStream raw = new FileOutputStream(output);
                     BufferedOutputStream out = new BufferedOutputStream(raw, 65536)) {
                    long remaining = size;
                    while (remaining > 0) {
                        checkCancelled();
                        int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (count < 0) throw new EOFException("DOWNLOAD_INVALID_ARCHIVE");
                        out.write(buffer, 0, count);
                        remaining -= count;
                    }
                    out.flush(); raw.getFD().sync();
                }
                long padding = (512 - size % 512) % 512;
                while (padding > 0) { int count = input.read(buffer, 0, (int) padding); if (count < 0) throw new EOFException(); padding -= count; }
            }
            // Reading through EOF also validates the GZIP trailer's CRC.
            while (input.read(buffer) != -1) checkCancelled();
        }
        if (found.size() != DICT_NAMES.length) throw new IOException("DOWNLOAD_INVALID_ARCHIVE");
        JSONObject entries = new JSONObject();
        for (String name : DICT_NAMES) {
            File file = new File(stage, name);
            Json.put(entries, name, Json.obj("bytes", file.length(), "modified", file.lastModified()));
        }
        writeJson(new File(stage, "verified.json"), Json.obj("sha256", FILES[0].sha, "files", entries));
        checkCancelled();
        deleteTree(dictionaryDirectory());
        move(stage, dictionaryDirectory());
    }

    private boolean installed() {
        JSONObject marker = readJson(new File(directory, "installed.json"));
        if (!VERSION.equals(marker.optString("version")) || !dictionaryValid()) return false;
        for (int i = 1; i < FILES.length; i++) if (!verified(completeFile(FILES[i]), FILES[i])) return false;
        return true;
    }

    private boolean dictionaryValid() {
        File root = dictionaryDirectory();
        JSONObject info = readJson(new File(root, "verified.json"));
        if (!FILES[0].sha.equals(info.optString("sha256"))) return false;
        JSONObject entries = info.optJSONObject("files");
        if (entries == null) return false;
        for (int i = 0; i < DICT_NAMES.length; i++) {
            File file = new File(root, DICT_NAMES[i]);
            JSONObject record = entries.optJSONObject(DICT_NAMES[i]);
            if (record == null || !file.isFile() || file.length() != DICT_SIZES[i]
                    || record.optLong("bytes", -1) != file.length() || record.optLong("modified", -1) != file.lastModified()) return false;
        }
        return true;
    }

    private File completeFile(Spec spec) {
        return new File(new File(directory, spec == FILES[0] ? "downloads" : "models"), spec.name);
    }
    private static File receipt(File file) { return new File(file.getParentFile(), file.getName() + ".verified.json"); }
    private static boolean verified(File file, Spec spec) {
        if (!file.isFile() || file.length() != spec.bytes) return false;
        JSONObject info = readJson(receipt(file));
        return spec.sha.equals(info.optString("sha256")) && info.optLong("bytes", -1) == spec.bytes
                && info.optLong("modified", -1) == file.lastModified();
    }
    private static void writeReceipt(File file, Spec spec) throws IOException {
        writeJson(receipt(file), Json.obj("sha256", spec.sha, "bytes", spec.bytes, "modified", file.lastModified()));
    }

    private long presentDownloadBytes() { return presentDownloadBytes(null); }
    private long presentDownloadBytes(Spec excluded) {
        long result = 0;
        for (Spec spec : FILES) {
            if (spec == excluded) continue;
            if (spec == FILES[0] && dictionaryValid()) { result += spec.bytes; continue; }
            File file = completeFile(spec);
            if (file.isFile()) result += Math.min(spec.bytes, file.length());
            else result += Math.min(spec.bytes, new File(new File(directory, "downloads"), spec.name + ".part").length());
        }
        return result;
    }

    private void progress(String status, String file, long current, Spec spec) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastProgress < 300) return;
        lastProgress = now;
        publish(state(status, presentDownloadBytes(spec) + current, file, "", ""));
    }
    private static JSONObject state(String status, long downloaded, String file, String code, String error) {
        return Json.obj("status", status, "ready", "ready".equals(status), "version", VERSION,
                "downloadedBytes", Math.max(0, Math.min(DOWNLOAD_BYTES, downloaded)), "totalBytes", DOWNLOAD_BYTES,
                "installedBytes", INSTALLED_BYTES, "requiredFreeBytes", RECOMMENDED_FREE_BYTES,
                "currentFile", file, "errorCode", code, "error", error, "voiceCount", 10);
    }
    private static void publish(JSONObject value) {
        final String encoded = value.toString();
        latest = encoded;
        MAIN.post(() -> {
            // An older queued progress event must not stop a newer foreground-service request.
            if (!encoded.equals(latest)) return;
            Listener ui = listener, service = serviceListener;
            if (ui != null) try { ui.onState(Json.parse(encoded)); } catch (RuntimeException ignored) { }
            if (service != null) try { service.onState(Json.parse(encoded)); } catch (RuntimeException ignored) { }
        });
    }

    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) { checkCancelled(); digest.update(buffer, 0, count); }
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException("DOWNLOAD_HASH_MISMATCH", e); }
    }
    private static void checkCancelled() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("DOWNLOAD_CANCELLED");
    }
    private static void mkdir(File file) throws IOException {
        if (!file.isDirectory() && !file.mkdirs()) throw new IOException("DOWNLOAD_STORAGE_ERROR");
    }
    private static void move(File from, File to) throws IOException {
        try { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING); }
    }
    private static void writeJson(File file, JSONObject object) throws IOException {
        File pending = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(pending)) {
            out.write(object.toString().getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
        }
        move(pending, file);
    }
    private static JSONObject readJson(File file) {
        if (!file.isFile() || file.length() > 32768) return new JSONObject();
        try { return Json.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)); }
        catch (IOException e) { return new JSONObject(); }
    }
    private static void deleteTree(File file) throws IOException {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("DOWNLOAD_STORAGE_ERROR");
            for (File child : children) deleteTree(child);
        }
        Files.deleteIfExists(file.toPath());
    }
    private static int dictionaryIndex(String name) {
        for (int i = 0; i < DICT_NAMES.length; i++) if (DICT_NAMES[i].equals(name)) return i;
        return -1;
    }
    private static String cString(byte[] source, int start, int length) {
        int end = start;
        while (end < start + length && source[end] != 0) end++;
        return new String(source, start, end - start, StandardCharsets.US_ASCII);
    }
    private static long octal(byte[] source, int start, int length) throws IOException {
        long result = 0;
        for (int i = start; i < start + length; i++) {
            int c = source[i];
            if (c == 0 || c == ' ') continue;
            if (c < '0' || c > '7' || result > (Long.MAX_VALUE >> 3)) throw new IOException("DOWNLOAD_INVALID_ARCHIVE");
            result = result * 8 + c - '0';
        }
        return result;
    }
    private static void readFully(InputStream in, byte[] data, int length) throws IOException {
        int offset = 0;
        while (offset < length) {
            int count = in.read(data, offset, length - offset);
            if (count < 0) throw new EOFException("DOWNLOAD_INVALID_ARCHIVE");
            offset += count;
        }
    }
    private static String safeCode(Exception error) {
        String value = error.getMessage();
        if (value != null && (value.equals("DOWNLOAD_NO_SPACE") || value.equals("DOWNLOAD_HASH_MISMATCH")
                || value.equals("DOWNLOAD_INVALID_ARCHIVE") || value.equals("DOWNLOAD_STORAGE_ERROR")
                || value.equals("DOWNLOAD_FORBIDDEN"))) return value;
        return "DOWNLOAD_FAILED";
    }
    private static String errorMessage(String code) {
        switch (code) {
            case "DOWNLOAD_NO_SPACE": return "音声データを保存する空き容量が不足しています。空きを増やして再開してください。";
            case "DOWNLOAD_HASH_MISMATCH": return "取得した音声データの検証に失敗しました。再取得してください。";
            case "DOWNLOAD_INVALID_ARCHIVE": return "日本語辞書を展開できませんでした。空き容量を確認して再試行してください。";
            case "DOWNLOAD_STORAGE_ERROR": return "音声データを保存できませんでした。空き容量を確認して再試行してください。";
            case "DOWNLOAD_FORBIDDEN": return "公式の音声配布先に接続できません。時間をおいて再開してください。";
            default: return "音声データの取得が中断しました。通信を確認して再開してください。完了済みのファイルは保持しています。";
        }
    }
}
