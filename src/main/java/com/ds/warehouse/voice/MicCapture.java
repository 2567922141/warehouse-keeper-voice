package com.ds.warehouse.voice;

import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC11;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;

/**
 * 麦克风采集线程：48 kHz / 单声道 / 16 位 PCM，只采到内存，绝不落盘。
 *
 * <p>首选 OpenAL：Minecraft 客户端运行时本来就带着 LWJGL 的 OpenAL Soft
 * （模组不需要打包任何原生库），直接把目标采样率交给它转换，也避开了
 * Java Sound「多数麦克风只支持 44100/48000」的格式强制。
 *
 * <p>Java Sound（{@link TargetDataLine}）是回退路径，需要它直接给 16 kHz；
 * 拿不到就报错，不做手写重采样（避免引入精度问题）。
 *
 * <p>线程只做采集与统计，不碰任何游戏状态；界面更新一律由主线程做。
 */
final class MicCapture extends Thread {

    /**
     * 采集采样率：按设备原生档位取 48 kHz，**不再**让 OpenAL 直接降到 16 kHz。
     *
     * <p>原因：设备是 48 kHz 时，请求 16 kHz 会走 OpenAL Soft 的采集重采样，实测会糊掉
     * 辅音（「玻璃」被听成「帮你」）。改成原生 48 kHz 采集、由 sherpa-onnx 在解码时重采样到
     * 模型要的 16 kHz，语音清晰度明显更好。
     */
    static final int SAMPLE_RATE = 48000;

    /** 每次最多读 100 毫秒，保证停止指令能及时生效。 */
    private static final int CHUNK_SAMPLES = SAMPLE_RATE / 10;

    /** 一段录音最多留 15 秒的音频（与 VoiceSession.MAX_MS 一致），只是内存上限。 */
    private static final int MAX_PCM_BYTES = SAMPLE_RATE * 2 * 15;

    /** 录到的 PCM 只放在这里：不落盘、不写文件；识别完由调用方 wipe() 清零。 */
    private final Object pcmLock = new Object();
    private byte[] pcm = new byte[SAMPLE_RATE * 2 * 4];
    private int pcmLength;

    private volatile boolean stopping;
    private volatile float level;
    private volatile int maxRaw;
    private volatile long samples;
    private volatile String backend = "未启动";
    private volatile String error;

    MicCapture() {
        super("warehouse-keeper-voice-mic");
        setDaemon(true);
    }

    /** 最近一块的峰值，0..1，用于电平条。 */
    float level() {
        return level;
    }

    /** 整段录音的最大峰值，0..32767；太小说明没收到声音。 */
    int maxRaw() {
        return maxRaw;
    }

    long samples() {
        return samples;
    }

    String backend() {
        return backend;
    }

    String error() {
        return error;
    }

    /** 复制一份已录到的 PCM（48 kHz / 单声道 / 16 位小端），交给识别线程用。 */
    byte[] pcmSnapshot() {
        synchronized (pcmLock) {
            return Arrays.copyOf(pcm, pcmLength);
        }
    }

    int pcmLength() {
        synchronized (pcmLock) {
            return pcmLength;
        }
    }

    /** 识别完立刻把内存里的音频清零：不留残余，也不落盘。 */
    void wipe() {
        synchronized (pcmLock) {
            Arrays.fill(pcm, (byte) 0);
            pcmLength = 0;
        }
    }

    private void appendPcm(byte[] data, int length) {
        synchronized (pcmLock) {
            if (pcmLength + length > MAX_PCM_BYTES) {
                return;
            }
            if (pcmLength + length > pcm.length) {
                int capacity = Math.min(MAX_PCM_BYTES, Math.max(pcm.length * 2, pcmLength + length));
                pcm = Arrays.copyOf(pcm, capacity);
            }
            System.arraycopy(data, 0, pcm, pcmLength, length);
            pcmLength += length;
        }
    }

    void stopCapture() {
        stopping = true;
    }

    @Override
    public void run() {
        try {
            if (runOpenAl()) {
                return;
            }
        } catch (Throwable t) {
            error = "OpenAL：" + text(t);
        }
        try {
            runJavaSound();
        } catch (Throwable t) {
            error = (error == null ? "" : error + "；") + "Java Sound：" + text(t);
        }
    }

    private boolean runOpenAl() {
        long device = 0L;
        ByteBuffer buffer = null;
        IntBuffer available = null;
        try {
            // 默认设备 + 目标采样率；不需要先打开播放设备（Simple Voice Chat 也这么用）。
            device = ALC11.alcCaptureOpenDevice((CharSequence) null, SAMPLE_RATE, AL10.AL_FORMAT_MONO16, SAMPLE_RATE / 4);
            if (device == 0L) {
                return false;
            }
            backend = "OpenAL";
            error = null;
            ALC11.alcCaptureStart(device);
            buffer = MemoryUtil.memAlloc(CHUNK_SAMPLES * 2);
            available = MemoryUtil.memAllocInt(1);
            byte[] chunkBytes = new byte[CHUNK_SAMPLES * 2];
            long total = 0L;
            while (!stopping) {
                ALC11.alcGetIntegerv(device, ALC11.ALC_CAPTURE_SAMPLES, available);
                int ready = available.get(0);
                if (ready < CHUNK_SAMPLES / 4) {
                    sleepQuiet(5L);
                    continue;
                }
                int take = Math.min(ready, CHUNK_SAMPLES);
                buffer.clear();
                buffer.limit(take * 2);
                ALC11.alcCaptureSamples(device, buffer, take);
                buffer.get(0, chunkBytes, 0, take * 2);
                appendPcm(chunkBytes, take * 2);
                publish(peakOf(buffer, take), total += take);
            }
            return true;
        } finally {
            if (available != null) {
                MemoryUtil.memFree(available);
            }
            if (buffer != null) {
                MemoryUtil.memFree(buffer);
            }
            if (device != 0L) {
                try {
                    ALC11.alcCaptureStop(device);
                } catch (Throwable ignored) {
                    // 收尾失败无所谓
                }
                try {
                    ALC11.alcCaptureCloseDevice(device);
                } catch (Throwable ignored) {
                    // 收尾失败无所谓
                }
            }
        }
    }

    private void runJavaSound() throws Exception {
        AudioFormat want = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        TargetDataLine line = null;
        try {
            line = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, want));
            line.open(want, SAMPLE_RATE);
            line.start();
            backend = "Java Sound";
            error = null;
            byte[] chunk = new byte[CHUNK_SAMPLES * 2];
            long total = 0L;
            while (!stopping) {
                int read = line.read(chunk, 0, chunk.length);
                if (read <= 0) {
                    sleepQuiet(5L);
                    continue;
                }
                appendPcm(chunk, read);
                publish(peakOf(chunk, read), total += read / 2);
            }
        } finally {
            if (line != null) {
                try {
                    line.stop();
                } catch (Throwable ignored) {
                    // 收尾失败无所谓
                }
                line.close();
            }
        }
    }

    private static int peakOf(ByteBuffer littleEndianShorts, int count) {
        int peak = 0;
        for (int i = 0; i + 1 < count * 2; i += 2) {
            short value = (short) ((littleEndianShorts.get(i) & 0xFF) | (littleEndianShorts.get(i + 1) << 8));
            int abs = Math.abs(value);
            if (abs > peak) {
                peak = abs;
            }
        }
        return peak;
    }

    private static int peakOf(byte[] littleEndianShorts, int length) {
        int peak = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            short value = (short) ((littleEndianShorts[i] & 0xFF) | (littleEndianShorts[i + 1] << 8));
            int abs = Math.abs(value);
            if (abs > peak) {
                peak = abs;
            }
        }
        return peak;
    }

    private void publish(int peak, long total) {
        if (peak > maxRaw) {
            maxRaw = peak;
        }
        // 人声通常只占满量程很小一部分，乘 6 让电平条看得见（纯视觉，不影响识别）
        level = Math.min(1f, peak / 32768f * 6f);
        samples = total;
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String text(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isEmpty() ? t.getClass().getSimpleName() : message;
    }
}
