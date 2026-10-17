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
        if (Minecraft.getInstance().player == null) {
            return;
        }
        String pending = pendingReady;
        if (pending != null && !pending.isEmpty()) {
            pendingReady = "";
            notify(pending);
        }
        // 引擎攒下的提示（例如「选的主模型还没下载，先用兜底模型」）也在这里弹一次，
        // 免得玩家在语音设置里选了没下载的模型之后，以为整个语音功能坏了。
        String extra = SherpaEngine.consumeNotice();
        if (!extra.isEmpty()) {
            notify(extra);
        }
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
        // 「有没有声音」不再只看整段最大电平（那会把远处的轻声判成没说话），
        // 交给引擎做体检：高通 + 噪声底 + 信噪比，不合格时给出人话原因。
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
        // 先在主线程做一次体检（只过滤/统计，不碰模型，几毫秒的事）：太轻、太远、只有环境噪声时
        // 立刻告诉玩家具体原因，而不是等模型跑完再听到一句含糊的「没听清」。
        SherpaEngine.Prepared prepared = SherpaEngine.analyzePrepared(pcm, length);
        SherpaEngine.Audio audio = prepared.audio();
        if (!audio.usable()) {
            Arrays.fill(pcm, (byte) 0);
            current.wipe();
            overlay(audio.problem());
            LOG.info("录音没通过体检：{}（{}）", audio.problem(), audio.detail());
            return;
        }
        recognizing = true;
        overlay("识别中…");
        // 候选闭集快照：**必须在主线程取**（要读 ClientSnapshot），取好再交给识别 worker。
        // AI 归一化只在本地听不出物品名时才用得上，所以只有开关开着才值得付这份快照的钱。
        final boolean aiOn = SherpaEngine.rewriteOn()
                && !VoiceRewrite.MODE_LOCAL.equals(SherpaEngine.rewriteMode());
        final List<VoiceRewrite.Candidate> aiPool = aiOn ? rewriteCandidates() : List.of();
        Thread worker = new Thread(() -> {
            String problem = null;
            List<SherpaEngine.Line> lines = null;
            long began = System.currentTimeMillis();
            try {
                problem = SherpaEngine.prepare();
                if (problem == null) {
                    lines = SherpaEngine.transcribe(prepared);
                }
            } catch (Throwable t) {
                problem = t.getClass().getSimpleName() + "：" + t.getMessage();
            } finally {
                Arrays.fill(pcm, (byte) 0);
                current.wipe();
                recognizing = false;
            }
            long took = System.currentTimeMillis() - began;
            // 第 ② 步：本地判不出物品名时，才可选地问一次 AI（一次识别最多一次，失败不重试）。
            // 这一段**必须在 worker 里**：网络超时最长 3 秒，放主线程就是卡游戏。
            AiEval ai = aiOn ? askAi(lines, aiPool) : null;
            String problemFinal = problem;
            List<SherpaEngine.Line> linesFinal = lines;
            AiEval aiFinal = ai;
            Minecraft.getInstance().execute(() -> {
                try {
                    report(linesFinal, problemFinal, took, aiFinal);
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

    /**
     * AI 归一化的一次尝试：模型给出的合法契约（单件 1 条、多件 1–4 条）+ 给玩家看的「AI 那边怎么了」。
     *
     * <p>契约里的 {@code item} 只是模型意见，**真正下什么单**由 {@link VoicePhrase#matchRewrite}
     * 在主线程再走一遍本地匹配决定；这里连物品名都没算出来。
     *
     * @param answers 本地校验过闭集/数量后的契约（可能为空 = AI 没帮上）
     * @param debug   本地结果之外的一句说明；{@code null} = 别提这事儿（开关关 / 本地本来就听懂了）
     */
    private record AiEval(List<VoiceRewrite.Answer> answers, String debug) {
        boolean hasAnswers() {
            return answers != null && !answers.isEmpty();
        }
    }

    /**
     * 在识别 worker 里试一次 AI 归一化。三条规矩（§5.3、§5.5）：
     * <ol>
     *     <li>「两个一起」模式下本地已经听懂了就**不调**（一分钱不花、一点延迟不加）；
     *         「完全调用 AI 改写」模式下每句都调一次；「完全本地」模式根本不进这里；</li>
     *     <li>一次识别最多一次调用，不重试；</li>
     *     <li>除了 401 那句「Key 无效」（由主线程收尾：提示 + 关开关 + 落盘），这里只攒 debug 文案，
     *         绝不弹界面、绝不发命令。</li>
     * </ol>
     */
    private static AiEval askAi(List<SherpaEngine.Line> lines, List<VoiceRewrite.Candidate> pool) {
        SherpaEngine.Line primary = primaryOf(lines);
        if (primary == null || primary.text().isEmpty() || primary.problem() != null) {
            return null;
        }
        String heard = primary.text();
        // 本地判断：认不出来 → 问；认出来了但句子里还带着本地吃不下的细节（等级／型号）→ 也问。
        VoicePhrase.Local local = VoicePhrase.inspect(heard);
        // 「完全调用 AI 改写」模式下每句都问，本地再有把握也问（玩家明确要 AI 先说一遍）；
        // 其余两种模式（两个一起 / 完全本地）仍按本地信心决定要不要问。
        boolean alwaysAsk = VoiceRewrite.MODE_AI.equals(SherpaEngine.rewriteMode());
        if (!alwaysAsk && local.recognized() && !local.detailed()) {
            // 本地认全了：AI 一次都不问（省额度、省一次延迟）
            return null;
        }
        if (pool == null || pool.isEmpty()) {
            return null;
        }
        List<VoiceRewrite.Candidate> top = ranked(pool, heard);
        if (top.isEmpty()) {
            return null;
        }
        VoiceRewrite.Result result;
        try {
            result = SherpaEngine.rewriteSpeech(heard, top, VoicePhrase::itemStackSize);
        } catch (Throwable t) {
            // 归一化是「锦上添花」的一层，任何异常都不该让这次识别变成失败
            LOG.debug("AI 归一化出错，按没帮忙处理：{}", t.toString());
            return new AiEval(List.of(), "AI 出错，用本地");
        }
        if (result == null || !result.ok()) {
            String debug = result == null ? null : debugFor(result.status());
            if (debug == null && alwaysAsk) {
                // 「完全调用 AI」模式下让玩家看得见「AI 真的被叫过、只是没给出可用答案」，
                // 否则会以为是开关没生效
                debug = "AI 没帮上，用本地";
            }
            return debug == null ? null : new AiEval(List.of(), debug);
        }
        List<VoiceRewrite.Answer> answers = result.answers() == null ? List.of() : result.answers();
        if (answers.isEmpty()) {
            return null;
        }
        String why = alwaysAsk ? "AI 先看过" : local.recognized() ? "问过 AI" : "AI 试过";
        return new AiEval(List.copyOf(answers), why);
    }

    /** 回显用的短句：太长就截断（模型偶尔会回一整段话）。 */
    private static String brief(String text) {
        String clean = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return clean.length() <= 24 ? clean : clean.substring(0, 24) + "…";
    }

    /** 多件契约回显：把各条的改写句按顺序连起来（只显示，绝不当命令）。 */
    private static String joinRewrites(List<VoiceRewrite.Answer> answers) {
        StringBuilder builder = new StringBuilder();
        for (VoiceRewrite.Answer answer : answers) {
            String text = answer == null || answer.rewrite() == null ? "" : answer.rewrite().trim();
            if (text.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("，");
            }
            builder.append(text);
        }
        return builder.toString();
    }

    /** 出网失败时给玩家看的一句人话；返回 null = 不打扰玩家（开关关、回答不合法都不打扰）。 */
    private static String debugFor(VoiceRewrite.Status status) {
        return switch (status) {
            case TIMEOUT -> "AI 超时，用本地";
            case NO_KEY -> "AI 没填 Key，用本地";
            case RATE_LIMITED -> "AI 太频繁，这句跳过";
            default -> null;
        };
    }

    /**
     * 主线程里把本地候选闭集转成 {@link VoiceRewrite} 的轻量视图。
     *
     * <p>**全量带进 worker**，等听清之后再按「与这句话有多像」挑前 {@link VoiceRewrite#MAX_CANDIDATES} 个
     * （{@link #ranked}）。以前在这里直接截前 5 个，等于把仓库最前面 5 件东西丢给模型 —— 玩家要的
     * 附魔书根本不在里面，模型当然挑不出来。
     */
    private static List<VoiceRewrite.Candidate> rewriteCandidates() {
        List<VoiceText.Candidate> all = VoicePhrase.candidatesSnapshot();
        List<VoiceRewrite.Candidate> out = new ArrayList<>(all.size());
        for (VoiceText.Candidate candidate : all) {
            out.add(new VoiceRewrite.Candidate(candidate.id(), candidate.name(), candidate.count()));
        }
        return out;
    }

    /**
     * 按「与这句话有多像」排序后取前 N 个：模型只看得到这几行，得挑最像的给它。
     *
     * <p>多件句（「和／还有／、」拆出两段以上）时，每一段各自取一个关键词去比 —— 否则整句话
     * 去比一个物品名，两件东西都会被稀释掉，模型看到的列表里一件都不对。
     */
    private static List<VoiceRewrite.Candidate> ranked(List<VoiceRewrite.Candidate> pool, String heard) {
        List<String> needles = new ArrayList<>();
        List<String> clauses = VoiceText.clauses(VoiceText.normalize(heard));
        if (clauses.size() >= 2) {
            for (String clause : clauses) {
                String needle = VoiceText.itemPhrase(clause);
                if (!needle.isEmpty()) {
                    needles.add(needle);
                }
            }
        }
        if (needles.isEmpty()) {
            String needle = VoiceText.itemPhrase(VoiceText.normalize(heard));
            if (!needle.isEmpty()) {
                needles.add(needle);
            }
        }
        List<VoiceRewrite.Candidate> sorted = new ArrayList<>(pool);
        if (!needles.isEmpty()) {
            sorted.sort((a, b) -> Double.compare(score(needles, b.name()), score(needles, a.name())));
        }
        int limit = Math.min(sorted.size(), VoiceRewrite.MAX_CANDIDATES);
        return List.copyOf(sorted.subList(0, limit));
    }

    /**
     * 一句（或几件）关键词与某个物品名的最像程度：每段都试，取最高。
     *
     * <p>带属性的行（「迟缓药水 · 药水：迟缓 IV 0:20」）要连「 · 」前面的干净名字一起算：
     * 玩家说的是「迟缓药水」，拿整行去比会被时长/等级那些注解稀释，排名掉出前 N 名，
     * 模型就永远看不到这一行（用户 m42742：开 AI 也认不出药水/烟花这类带属性的东西）。
     */
    private static double score(List<String> needles, String name) {
        double best = 0.0D;
        for (String needle : needles) {
            // 短说法也要能排上来：整名 / 干净名字 / 干净名字+等级都试（见 VoiceText.forms）
            best = Math.max(best, VoiceText.similarityToForms(needle, name));
        }
        return best;
    }

    private static void report(List<SherpaEngine.Line> lines, String problem, long tookMs, AiEval ai) {
        if (problem != null) {
            overlay("语音识别用不了：" + problem);
            LOG.warn("语音识别不可用：{}", problem);
            return;
        }
        if (lines == null || lines.isEmpty()) {
            overlay(ai != null && ai.debug() != null ? ai.debug() : "没听清，请再说一次");
            return;
        }
        SherpaEngine.Line primary = primaryOf(lines);
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
        // 第 ② 步：AI 契约（若有）在这里**逐条再过一次本地匹配器**才可能下单 ——
        // matchRewrite 在主线程只做纯计算，不发命令、不发网络；模型给的 item 不直接信。
        boolean multi = VoicePhrase.isMulti(primary.text());
        List<VoicePhrase.Rewrite> aiOrders = new ArrayList<>();
        if (ai != null && ai.hasAnswers()) {
            for (VoiceRewrite.Answer answer : ai.answers()) {
                VoicePhrase.Rewrite matched = VoicePhrase.matchRewrite(
                        primary.text(), answer.rewrite(), answer.count(), answer.itemId());
                if (matched != null) {
                    aiOrders.add(matched);
                }
            }
        }
        VoicePhrase.Rewrite rewrite = multi || aiOrders.isEmpty() ? null : aiOrders.get(0);
        // AI 那条路没走通时，把「AI 那边怎么了」缀在本地那句人话后面（比只显示本地结果更好懂）
        String localOutcome = multi
                ? VoicePhrase.handleMulti(primary, others, aiOrders)
                : VoicePhrase.handle(primary, others, rewrite);
        String debug = ai == null ? null : ai.debug();
        boolean aiUsed = multi ? !aiOrders.isEmpty() : rewrite != null;
        if (ai != null && ai.hasAnswers() && !aiUsed) {
            // 模型确实回了、只是本地复判一条都不认：以前这里会显示成「试试 AI 归一化…」，
            // 看着像压根没调过 —— 说清楚是「AI 给了 X，本地对不上，所以没下单」。
            debug = "AI 说「" + brief(joinRewrites(ai.answers())) + "」，本地不认";
        }
        String outcome = !aiUsed && debug != null ? localOutcome + "（" + debug + "）" : localOutcome;
        overlay(outcome);
        // Key 无效这类必须收尾的事：提示一次 + 把开关置回 off + 立刻落盘（只在主线程做）
        String notice = SherpaEngine.consumeRewriteNotice();
        if (!notice.isEmpty()) {
            statusAfterKeyFailure(notice);
        }
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

    /** 识别结果里哪一行是主模型（没有主标记就用第一行）。 */
    private static SherpaEngine.Line primaryOf(List<SherpaEngine.Line> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        for (SherpaEngine.Line line : lines) {
            if (line.primary()) {
                return line;
            }
        }
        return lines.get(0);
    }

    /** 401 之后的收尾：动作栏 + 聊天栏各说一次，并把 AI 开关置回 off（落盘由 SherpaEngine 做）。 */
    private static void statusAfterKeyFailure(String notice) {
        LOG.warn("AI 归一化已自动关闭：{}", notice);
        overlay(notice);
        chat(notice);
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
