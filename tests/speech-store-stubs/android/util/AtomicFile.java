package android.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Disk-backed contract double: successful finish replaces the base; fail keeps it.
 * It is NOT Android's AtomicFile implementation and does not test filesystem durability.
 * Deterministic write/start/finish faults test production SpeechStore's error handling.
 */
public final class AtomicFile {
    private static final Map<String, Fault> FAULTS = new HashMap<>();
    private final File base, pending, backup;
    private Fault active;
    private static final class Fault {
        final String stage; final int bytes;
        Fault(String stage, int bytes) { this.stage = stage; this.bytes = bytes; }
    }
    public AtomicFile(File base) { this.base = base; this.pending = new File(base + ".new"); this.backup = new File(base + ".bak"); }
    public static synchronized void failNextStart(File base) { FAULTS.put(base.getAbsolutePath(), new Fault("start", 0)); }
    public static synchronized void failNextWrite(File base, int afterBytes) { FAULTS.put(base.getAbsolutePath(), new Fault("write", afterBytes)); }
    public static synchronized void failNextFinish(File base) { FAULTS.put(base.getAbsolutePath(), new Fault("finish", 0)); }
    private static synchronized Fault take(File base) { return FAULTS.remove(base.getAbsolutePath()); }
    public FileInputStream openRead() throws FileNotFoundException {
        if (backup.exists()) {
            try { Files.move(backup.toPath(), base.toPath(), StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException e) { throw new FileNotFoundException("fixture backup restore failed"); }
        }
        if (base.exists() && pending.exists()) pending.delete();
        return new FileInputStream(base);
    }
    public FileOutputStream startWrite() throws IOException {
        active = take(base);
        if (active != null && "start".equals(active.stage)) throw new IOException("injected start failure");
        return new FaultStream(pending, active != null && "write".equals(active.stage) ? active.bytes : -1);
    }
    public void finishWrite(FileOutputStream stream) {
        try {
            stream.getFD().sync(); stream.close();
            if (active != null && "finish".equals(active.stage)) throw new IOException("injected finish failure");
            Files.move(pending.toPath(), base.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    public void failWrite(FileOutputStream stream) {
        try { stream.close(); } catch (IOException ignored) { }
        pending.delete();
    }
    public void delete() { base.delete(); pending.delete(); backup.delete(); }
    private static final class FaultStream extends FileOutputStream {
        private int remaining;
        FaultStream(File file, int remaining) throws FileNotFoundException { super(file); this.remaining = remaining; }
        @Override public void write(byte[] bytes) throws IOException { write(bytes, 0, bytes.length); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if (remaining < 0) { super.write(bytes, offset, length); return; }
            int first = Math.min(remaining, length);
            if (first > 0) super.write(bytes, offset, first);
            remaining -= first;
            if (first < length || remaining == 0) throw new IOException("injected partial write failure");
        }
        @Override public void write(int value) throws IOException {
            if (remaining == 0) throw new IOException("injected byte write failure");
            super.write(value); if (remaining > 0) remaining--;
        }
    }
}
