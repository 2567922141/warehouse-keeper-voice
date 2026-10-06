package com.ds.warehouse.voice;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 一次「按住说话」的录音会话：按住键开始录，松开就结束。
 *
 * <p>第 1 期只负责：开关录音、把状态和电平显示在原版动作栏上、结束时报告
 * 时长与电平。识别与下单在第 2、3 期接（本类就是那两步的挂载点）。
 *
 * <p>任何界面更新都在客户端主线程做（{@link #tick()} 由 tick 事件调用），
 * 采集线程只往 {@link MicCapture} 里写数字，绝不碰游戏状态。
 */
final class VoiceSession {

    private static final Logger LOG = LoggerFactory.getLogger("warehouse-keeper-voice");

    /** 松开的按键没送到（切窗口、掉焦点）时兜底，不会一直录。 */
    private static final long MAX_MS = 15_000L;
    /** 太短当误触。 */
    private static final long MIN_MS = 300L;
    /** 整段最大电平低于这个数，就认为没收到声音。 */
    private static final int SILENT_RAW = 300;

    private static MicCapture capture;
    private static long startedAt;
    private static float shown;
    /** 识别在后台线程跑，这个标记只用于界面提示。 */
    private static volatile boolean recognizing;
    /** 最近一次听到的文字（第 3 期的解析层从这里取）。 */
    private static volatile String lastHeard = "";
    /** 后台预加载完成后要提示的一句话（记下来，等玩家进了世界再弹）。 */
    private static volatile String pendingReady = "";

    private VoiceSession() {
    }

    /** 引擎在后台把模型装好后调这个（可能在任意线程，这里只记一句话）。 */
    static void notifyPreloaded(String text) {
        pendingReady = text;
    }

    /** 每个客户端 tick 调一次：玩家已经进了世界，就把攒下的提示弹出来（只弹一次）。 */
    static void idleTick() {
        String pending = pendingReady;
        if (pending == null || pending.isEmpty() || Minecraft.getInstance().player == null) {
            return;
        }
        pendingReady = "";
        notify(pending);
    }

    static boolean recording() {
        return capture != null;
    }

    /** 键按下（按住说话）：还没在录、且上一句已经识别完，才开始。 */
    static void press() {
        if (capture != null) {
            return;
        }
        if (recognizing) {
            // 上一句还在识别（大模型可能要几秒）：这时再录会让两段音频同时在内存里，也可能连下两单
            overlay("上一句还在识别，稍等一下再说");
            return;
        }
        start();
    }

    /** 键松开：结束这一段。没在录时什么也不做。 */
    static void release() {
        stop();
    }

    private static void start() {
        Minecraft mc = Minecraft.getInstance();
        // 界面打开时鼠标是松开的（26.3 的 Minecraft 不再对外暴露 screen 字段）：
        // 那时按 V 是在聊天框里打字，不是说话，不录。
        if (mc.player == null || !mc.mouseHandler.isMouseGrabbed()) {
            return;
        }
        MicCapture fresh = new MicCapture();
        capture = fresh;
        startedAt = System.currentTimeMillis();
        shown = 0f;
        fresh.start();
        overlay("● 录音中… 说完松开就结束");
    }

    /** 每个客户端 tick 调一次：刷新电平、到点自动收尾。 */
    static void tick() {
        MicCapture current = capture;
        if (current == null) {
            return;
        }
        // 录音期间打开了界面（聊天框等）或窗口掉了焦点：立刻收尾，
        // 免得把打字声/静音当口令。
        if (!Minecraft.getInstance().mouseHandler.isMouseGrabbed()) {
            stop();
            return;
        }
        long ms = System.currentTimeMillis() - startedAt;
        if (ms >= MAX_MS) {
            stop();
            return;
        }
        shown = Math.max(current.level(), shown * 0.7f);
        overlay("● 录音 " + seconds(ms) + " 秒  " + meter(shown) + "  松开结束");
    }

    private static void stop() {
        MicCapture current = capture;
        if (current == null) {
            return;
        }
        capture = null;
        current.stopCapture();
        try {
            current.join(500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long ms = System.currentTimeMillis() - startedAt;
        LOG.info("录音结束：{} 秒，后端={}，样本={}，最大电平={}，错误={}",
                seconds(ms), current.backend(), current.samples(), current.maxRaw(), current.error());

        if (current.samples() == 0L) {
            current.wipe();
            overlay("麦克风没有声音：" + (current.error() == null ? "采集不到数据" : current.error())
                    + "　（Windows 设置 → 隐私和安全性 → 麦克风：打开「麦克风访问」和「允许桌面应用访问麦克风」）");
            return;
        }
        if (ms < MIN_MS) {
            current.wipe();
            overlay("太短了（" + seconds(ms) + " 秒），当误触忽略");
            return;
        }
        if (current.maxRaw() < SILENT_RAW) {
            current.wipe();
            overlay("没收到声音（最大电平 " + current.maxRaw() + "／32767）——检查麦克风是否被其他程序独占，或设成了静音");
            return;
        }
        recognize(current);
    }

    /**
     * 把刚录到的 PCM 交给识别：模型加载/识别都在独立线程，绝不卡住游戏线程。
     *
     * <p>隐私：这段音频只在内存里过一趟，识别一结束（无论成败）立刻清零，
     * 不落盘；识别出的文字只显示在本地动作栏，不进聊天、不发服务器、不写进日志正文。
     */
    private static void recognize(MicCapture current) {
        byte[] pcm = current.pcmSnapshot();
        int length = current.pcmLength();
        if (pcm.length == 0 || length == 0) {
            current.wipe();
            overlay("没录到音频，请再试一次");
            return;
        }
        recognizing = true;
        overlay("识别中…");
        Thread worker = new Thread(() -> {
            String problem = null;
            List<SherpaEngine.Line> lines = null;
            long began = System.currentTimeMillis();
            try {
                problem = SherpaEngine.prepare();
                if (problem == null) {
                    lines = SherpaEngine.transcribe(pcm, length);
                }
            } catch (Throwable t) {
                problem = t.getClass().getSimpleName() + "：" + t.getMessage();
            } finally {
                Arrays.fill(pcm, (byte) 0);
                current.wipe();
                recognizing = false;
            }
            long took = System.currentTimeMillis() - began;
            String problemFinal = problem;
            List<SherpaEngine.Line> linesFinal = lines;
            Minecraft.getInstance().execute(() -> {
                try {
                    report(linesFinal, problemFinal, took);
                } catch (Throwable t) {
                    // report 里会发取货指令、动界面；玩家正好断线时抛出的异常不能逃逸进主线程任务队列
                    LOG.warn("语音处理出错：{}", t.toString());
                    overlay("语音处理出错：" + t);
                }
            });
        }, "warehouse-keeper-voice-recognizer");
        worker.setDaemon(true);
        worker.start();
    }

    private static void report(List<SherpaEngine.Line> lines, String problem, long tookMs) {
        if (problem != null) {
            overlay("语音识别用不了：" + problem);
            LOG.warn("语音识别不可用：{}", problem);
            return;
        }
        if (lines == null || lines.isEmpty()) {
            overlay("没听清，请再说一次");
            return;
        }
        SherpaEngine.Line primary = null;
        for (SherpaEngine.Line line : lines) {
            if (line.primary()) {
                primary = line;
            }
        }
        if (primary == null) {
            primary = lines.get(0);
        }
        if (primary.problem() != null) {
            overlay("语音识别用不了：" + primary.problem());
            return;
        }
        if (primary.text().isEmpty()) {
            overlay("没听清，请再说一次");
            for (SherpaEngine.Line line : lines) {
                chat("[" + line.label() + "] " + (line.text().isEmpty() ? "（没听出内容）" : line.text()));
            }
            return;
        }
        lastHeard = primary.text();
        // 只记长度不记正文：口令内容留在玩家自己屏幕上。
        LOG.info("识别完成：{} 个字，用时 {} 毫秒，模型数={}", primary.text().length(), tookMs, lines.size());
        // 交给第 3 期：闭集匹配（只在仓库真实有的物品里找）+ 安全阀，能下单就直接下单。
        // 两个模型一起工作：主模型没听出物品名时，另一个模型的结果会顶上（见 VoicePhrase.handle）。
        List<SherpaEngine.Line> others = new ArrayList<>();
        for (SherpaEngine.Line line : lines) {
            if (!line.primary()) {
                others.add(line);
            }
        }
        String outcome = VoicePhrase.handle(primary, others);
        overlay(outcome);
        for (SherpaEngine.Line line : lines) {
            if (line.primary()) {
                chat("[" + line.label() + "] " + line.text() + "　→　" + outcome);
            } else if (SherpaEngine.showOtherOn()) {
                // 两个模型始终都在下单流程里干活，这里只是额外显示另一个模型听到了什么（按 B 可关这一行）
                chat("[" + line.label() + "] " + (line.text().isEmpty() ? "（没听出内容）" : line.text())
                        + "　→　它听到的：" + VoicePhrase.preview(line.text()));
            }
        }
    }

    /** 给按键反馈之类用的一句话提示（动作栏 + 本地聊天栏）。 */
    static void notify(String text) {
        overlay(text);
        chat(text);
    }

    /** 本机系统消息：只加在本地聊天栏，不发给服务器，别人看不到。 */
    private static void chat(String text) {
        try {
            Minecraft.getInstance().gui.chatListener()
                    .handleSystemMessage(Component.literal("[语音] " + text), false);
        } catch (Throwable t) {
            LOG.warn("聊天栏提示失败：{}", t.toString());
        }
    }

    /** 关游戏/退服时静默收尾，不弹任何提示。 */
    static void abort() {
        MicCapture current = capture;
        capture = null;
        if (current == null) {
            return;
        }
        current.stopCapture();
        try {
            current.join(300L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 关游戏/退服也要把内存里的音频清零：任何路径都不留残余（音频从不落盘）
        current.wipe();
    }

    /** 走原版动作栏：26.3 的 HUD 是「抽取—提交」新模型，第 1 期先不自己画。 */
    private static void overlay(String text) {
        try {
            Minecraft.getInstance().gui.chatListener().handleOverlay(Component.literal(text));
        } catch (Throwable t) {
            LOG.warn("动作栏提示失败：{}", t.toString());
        }
    }

    private static String meter(float level) {
        int lit = Math.max(0, Math.min(10, Math.round(level * 10f)));
        StringBuilder bar = new StringBuilder(12);
        bar.append('[');
        for (int i = 0; i < 10; i++) {
            bar.append(i < lit ? '#' : '.');
        }
        return bar.append(']').toString();
    }

    private static String seconds(long ms) {
        return String.format(java.util.Locale.ROOT, "%.1f", ms / 1000.0);
    }
}
