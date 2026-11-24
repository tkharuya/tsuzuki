package jp.haruyalab.novelshelf;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.PlaybackParams;
import android.os.Build;

/** Android boundary only; at most a small native output buffer is allocated. */
final class AndroidPcmOutput implements PcmPlayback.Output {
    private final AudioTrack track;
    private final byte[] endPadding;
    private int paddingRemaining;
    AndroidPcmOutput(AudioAttributes attributes) {
        int minimum = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IllegalStateException("VOICEVOX_PLAYBACK_FAILED");
        track = new AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(minimum * 3, 12000)).build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) { track.release(); throw new IllegalStateException("VOICEVOX_PLAYBACK_FAILED"); }
        if(Build.VERSION.SDK_INT>=31)track.setStartThresholdInFrames(1);
        // Older tracks may not start a very short utterance until their minimum buffer is filled.
        // Padding goes only to the output device; the cached/source PCM and reported duration are unchanged.
        paddingRemaining=Build.VERSION.SDK_INT>=31?0:track.getBufferSizeInFrames()*2;
        endPadding=new byte[Math.min(4096,paddingRemaining)];
    }
    @Override public int write(byte[] pcm, int offset, int length) { return track.write(pcm, offset, length, AudioTrack.WRITE_NON_BLOCKING); }
    @Override public long playedFrames() { return track.getPlaybackHeadPosition() & 0xffffffffL; }
    @Override public void play() { track.play(); }
    @Override public void pause() { track.pause(); }
    @Override public void speed(float value) { track.setPlaybackParams(new PlaybackParams().setSpeed(value).setPitch(1)); }
    @Override public void finishInput(){
        if(paddingRemaining<=0)return;
        int count=track.write(endPadding,0,Math.min(endPadding.length,paddingRemaining),AudioTrack.WRITE_NON_BLOCKING);
        if(count<0)throw new IllegalStateException("VOICEVOX_PLAYBACK_FAILED");
        paddingRemaining-=count;
    }
    @Override public void close() { try { track.pause(); track.flush(); } finally { track.release(); } }
}
