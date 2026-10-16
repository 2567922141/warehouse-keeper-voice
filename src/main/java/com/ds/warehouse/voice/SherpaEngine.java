package com.ds.warehouse.voice;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.LibraryUtils;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 离线语音识别：sherpa-onnx + 可自由选择的模型，同一句话可以让**两个模型**各跑一遍。
 *
 * <p><b>语音包不内置任何模型</b>，清单见 {@link VoiceModelCatalog}，全部按需下载：
 * <ul>
 *     <li><b>SenseVoice</b>（默认推荐，约 228 MB，对多口音/远场/噪声更稳）；</li>
 *     <li><b>Paraformer 小</b>（约 78 MB，最省 CPU 与内存）；</li>
 *     <li><b>Zipformer-CTC</b> 中文 int8（约 350 MB）、<b>Paraformer 完整版</b>（约 217 MB）。</li>
 * </ul>
 * 在语音设置页里选好就自动下载到 {@code config/warehouse-keeper-voice/models/<id>/}；
 * 一个模型都没下载时，语音识别会明确提示「先去设置页下载一个」，而不是静默失败。
 *
 * <p>主模型（{@code primary}）的结果优先用来下单；副模型（{@code secondary}）在主模型没听懂时顶上
 * （见 {@code VoicePhrase.handle}）。副模型没装就自动忽略，主模型没装则自动回退到已装的模型，
 * 并在聊天栏里说清楚用的是哪个 —— 绝不因为「选了个还没下载的模型」就整个语音功能不可用。
 *
 * <p>设置写在 {@code config/warehouse-keeper-voice/settings.txt}（第一次运行自动生成，
 * 也可以在语音设置页里点着改）：{@code primary} / {@code secondary} 选模型，{@code compare} 决定
 * 聊天栏里要不要多显示副模型那一行，{@code parallel} 是否并行，{@code preload} 是否进游戏后台预装，
 * {@code mirror} 是下载模型用的镜像地址。
 *
 * <p>模型与原生库只在**第一次**解压/下载到硬盘（{@code config/warehouse-keeper-voice/}），
 * 之后每次都直接调用硬盘上的文件（大小对不上才重新弄），进游戏时后台预装。
 *
 * <p>隐私：音频只以 PCM 形式在内存里过一趟，所有模型跑完立刻清零；不联网（联网只发生在玩家在设置页里
 * 主动点「下载模型」时）、不落盘、不写世界存档目录 —— 唯一写盘的是模型/原生库落在 config 目录下。
 */
final class SherpaEngine {

    private static final Logger LOG = LoggerFactory.getLogger("warehouse-keeper-voice");

    static {
        // 模型的落盘位置由 Fabric 的 config 目录决定。模型层（清单/存储/下载器）刻意不依赖
        // Minecraft 与 Fabric，所以在这里把目录注入进去；它是纯 JDK 代码，方便单独测试。
        VoiceModelStore.init(FabricLoader.getInstance().getConfigDir());
        VoiceModelDownloader.setLog(message -> LOG.info("模型下载：{}", message));
    }

    /** 原生库在 jar 里的位置与落点（见 {@link #prepareNatives}）。 */
    private static final String NATIVE_RESOURCE_DIR = "/sherpa-onnx/native/win-x64/";
    private static final String NATIVE_DIR = "sherpa-natives";
    private static final String[] NATIVE_FILES = {"onnxruntime.dll", "sherpa-onnx-jni.dll"};

    /** 模型训练用的特征采样率；输入音频由 sherpa-onnx 自己重采样到这个值。 */
    private static final int FEATURE_RATE = 16000;
    private static final int FEATURE_DIM = 80;

    // ------------------------------------------------------------------ 录音端参数

    /** 高通滤波后的峰值低于这个数就当没说话（16 位满量程 32767）。 */
    private static final int MIN_PEAK = 120;
    /** 目标人声电平：让「人声段」的均方根落在这里（满量程的 10%，约 -20 dBFS）。 */
    private static final double TARGET_RMS = 0.10D * 32767.0D;
    /**
     * 增益上限。
     *
     * <p>以前是 8 倍，等于只对「峰值 ≥ 2867」这种贴着麦克风说的声音有效：更轻/更远的人会被
     * 系统性喂一段更小声、更脏的音频（同一个 8 倍也把底噪一起放大），这就是「只认某一个人的声音」
     * 的直接原因之一。现在改成按人声段电平归一化，上限放到 64 倍，再用削顶余量单独兜一层。
     */
    private static final double MAX_GAIN = 64.0D;
    /** 归一化后不允许超过这个峰值，避免削顶失真（削顶比声音小更伤识别）。 */
    private static final double HEAD_ROOM = 32000.0D;
    /**
     * 拒绝门槛：人声段电平至少要达到噪声底的这么多倍，否则判定「没听到人声」。
     *
     * <p>这个阈值卡的是**误判**的方向：定高了（原先写 3.0）会把「说得轻/离得远」的人判成
     * 「没说话」—— 而他们的 20 毫秒帧电平分布本来就比贴麦说话的人平，p95/p10 常常只有 2 倍出头。
     * 持续嗡鸣/电流声这类「很响但不是人话」的段自己就是平的（p95≈p10），会被这一条直接挡住，
     * 不必再加一条「起伏」判据 —— 那种判据会误伤「声音稳、没有停顿」的正常说话。
     *
     * <p>和下面的 {@link #SNR_TRIM} 是两个独立常量（V-09）：一个管「拒不拒」，一个管「掐头去尾从哪切」，
     * 调其中一个不会连带改另一个 —— 以前共用一个常量，想让裁剪更狠就会顺带把正常说话判成没声。
     */
    private static final double SNR_REJECT = 2.0D;
    /** 掐头去尾的门限：帧电平低于「噪声底 × 这个倍数」就当作静音段切掉（独立常量，见 {@link #SNR_REJECT}）。 */
    private static final double SNR_TRIM = 2.0D;
    /** 噪声底的绝对下限（16 位采样单位），避免数字静音时把底噪当成 0。 */
    private static final double NOISE_FLOOR = 6.0D;
    /** 一阶高通截止频率：滤掉直流偏移与桌面/空调的低频轰鸣，它们白占峰值和增益预算。 */
    private static final double HIGHPASS_HZ = 70.0D;
    /** 统计电平用的帧长（20 毫秒）。 */
    private static final int FRAME_SAMPLES = MicCapture.SAMPLE_RATE / 50;
    /** 掐头去尾各留的余量（150 毫秒），避免把首尾音素切掉。 */
    private static final int TRIM_MARGIN = MicCapture.SAMPLE_RATE * 15 / 100;

    /** 一路识别结果。 */
    record Line(String label, String text, boolean primary, String problem) {
    }

    /**
     * 一段录音的体检结果：既给玩家看诊断，也给出归一化用的增益。
     *
     * <p>{@code problem != null} 表示这段音频没救（太短/没声/只有噪声），不必再花几百毫秒跑模型。
     */
    record Audio(int samples, int peak, double noiseRms, double speechRms, double gain, String problem) {

        boolean usable() {
            return problem == null;
        }

        /** 给日志和排错用的一行数字。 */
        String detail() {
            return "样本=" + samples + "，峰值=" + peak + "，人声=" + Math.round(speechRms)
                    + "，噪声=" + Math.round(noiseRms) + "，增益=" + String.format(Locale.ROOT, "%.2f", gain);
        }
    }

    /**
     * 处理好的音频 + 体检结果。
     *
     * <p>V-08：不设为 {@code private}，这样「松开按键」时体检出来的结果可以直接交给识别线程复用
     * （见 {@link #analyzePrepared} 与 {@link #transcribe(Prepared)}），不必把同一段 PCM 处理两三遍。
     */
    record Prepared(float[] samples, Audio audio) {
    }

    private static final Map<String, OfflineRecognizer> LOADED = new ConcurrentHashMap<>();

    /** 失败记录：给玩家看的原因 + 记录时间（过一段时间允许重试，瞬时失败不该让模型整局不可用）。 */
    private record Failure(String message, long at) {
    }

    private static final Map<String, Failure> FAILURES = new ConcurrentHashMap<>();
    /** 装载失败后过多久允许再试一次。 */
    private static final long RETRY_MS = 60_000L;

    /**
     * 每个模型一把锁。两个作用：
     * <ul>
     *     <li>后台预装和「按住说话」可能同时要同一个模型，别把 350 MB 模型装两遍；</li>
     *     <li>「识别中」必须压住「卸载」（设置页换模型时会释放不再需要的模型）——
     *         否则会在解码过程中把原生对象 free 掉，直接崩游戏。</li>
     * </ul>
     */
    private static final Map<String, Object> MODEL_LOCKS = new ConcurrentHashMap<>();

    /**
     * 原生库只加载一次的锁。
     *
     * <p>**必须**是独立的一把锁，不能用类监视器：识别线程走 {@code prepare() → ensure()}
     * （类监视器 → 模型锁），预装线程走 {@code ensure() → prepareNatives()}（模型锁 → 类监视器），
     * 用类监视器就是标准的 AB-BA 死锁 —— 语音引擎永久卡死，且主线程按 B 时也会被冻住。
     */
    private static final Object NATIVE_LOCK = new Object();
    /** 设置文件与设置字段的锁（与模型锁、原生库锁相互独立，不会形成环）。 */
    private static final Object SETTINGS_LOCK = new Object();

    /** 多模型并行识别用的线程池（守护线程，进程退出时自己结束）。 */
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "warehouse-keeper-voice-asr");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile String primaryKey = VoiceModelCatalog.defaultId();
    private static volatile String secondaryKey = VoiceModelCatalog.fallbackId();
    /** 聊天栏里要不要多显示副模型那一行（副模型无论如何都会跑）。 */
    private static volatile boolean showOther = true;
    private static volatile boolean parallel = true;
    private static volatile boolean preload = true;
    private static volatile String mirror = VoiceModelCatalog.defaultMirror();
    /** 攒下来给玩家的一句提示（例如「还没有可用的语音模型，先去设置页下载一个」），由 VoiceSession 取走显示。 */
    private static volatile String notice = "";
    private static volatile boolean preloadStarted;
    private static volatile boolean settingsLoaded;

    private SherpaEngine() {
    }

    // ------------------------------------------------------------------ 对外接口

    /**
     * 就绪返回 null，否则返回给玩家看的中文原因。
     *
     * <p>**不能**加 {@code synchronized}：那会在持有类监视器时再去抢模型锁，与
     * {@code ensure() → prepareNatives()} 的方向相反，构成 AB-BA 死锁（见 {@link #NATIVE_LOCK}）。
     * 「只装载一次」由每模型锁保证。
     */
    static String prepare() {
        loadSettings();
        noteFallback();
        return ensure(effectivePrimary());
    }

    /**
     * 设了主模型、但它还没下载时，攒一句提示（进游戏后台预装时就会攒好，玩家一进世界就看见）。
     *
     * <p>两种情形分开说：
     * <ul>
     *     <li>一个模型都没下载：直接告诉玩家去设置页下载 —— 这时没有任何模型可用，
     *         含糊其辞会让玩家以为是语音功能坏了；</li>
     *     <li>只是选中的那个没下载：说明「先用 X 识别」，绝不因为选了个没下载的模型就让功能看起来坏了。</li>
     * </ul>
     */
    private static void noteFallback() {
        if (!anythingInstalled()) {
            notice = "还没有可用的语音模型：输入 /warehousevoice 打开语音设置页，先下载一个（首次使用必须先下载）";
            return;
        }
        VoiceModel wanted = primaryModel();
        VoiceModel used = effectivePrimary();
        if (!used.id().equals(wanted.id())) {
            notice = "主模型「" + wanted.label() + "」还没下载，先用「" + used.label()
                    + "」识别（输入 /warehousevoice 打开语音设置页即可下载）";
        }
    }

    /** 硬盘上有没有任何一个完整可用的模型。 */
    private static boolean anythingInstalled() {
        for (VoiceModel model : VoiceModelCatalog.all()) {
            if (VoiceModelStore.installed(model)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 进游戏后在后台把要用的模型都装进内存（并行）。
     *
     * <p>装好之后第一次按住 V 就能直接识别，不用再等模型加载；磁盘上的模型文件不会重新解压/下载。
     */
    static void preloadAsync() {
        loadSettings();
        if (!preload) {
            LOG.info("语音预加载已关闭（settings.txt 里 preload=off），第一次说话时再装模型");
            return;
        }
        synchronized (SETTINGS_LOCK) {
            if (preloadStarted) {
                return;
            }
            preloadStarted = true;
        }
        noteFallback();
        List<VoiceModel> models = runModels();
        Thread worker = new Thread(() -> {
            long began = System.currentTimeMillis();
            List<Future<String>> futures = new ArrayList<>(models.size());
            for (VoiceModel model : models) {
                futures.add(POOL.submit(() -> ensure(model)));
            }
            List<String> problems = new ArrayList<>();
            int ready = 0;
            for (int i = 0; i < futures.size(); i++) {
                String problem;
                try {
                    problem = futures.get(i).get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    problem = "预加载被打断";
                } catch (ExecutionException e) {
                    problem = describe(e.getCause() == null ? e : e.getCause());
                }
                if (problem == null) {
                    ready++;
                } else {
                    problems.add(models.get(i).label() + "：" + problem);
                }
            }
            long took = System.currentTimeMillis() - began;
            if (problems.isEmpty()) {
                LOG.info("语音模型预加载完成：{} 个模型已在内存里（用时 {} 毫秒）", ready, took);
                VoiceSession.notifyPreloaded(ready + " 个语音模型已在后台装好（用时 " + seconds(took)
                        + " 秒），按住 V 直接说就行");
            } else {
                LOG.warn("语音模型预加载部分失败：{}", String.join("；", problems));
                VoiceSession.notifyPreloaded("语音模型预加载失败：" + String.join("；", problems));
            }
        }, "warehouse-keeper-voice-preload");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 识别一段音频：主模型一定跑；配了副模型、且副模型已装，就也跑一遍（默认两条线程同时跑）。
     *
     * @return 每路一行结果，主模型那行永远排第一；音频太短/太静/只有噪声返回空表
     */
    static List<Line> transcribe(byte[] pcm, int length) {
        return transcribe(prepare(pcm, length));
    }

    /**
     * 用**已经体检过**的音频识别：不再重复做一遍前处理。
     *
     * <p>V-08：「松开按键」时为了立刻回话已经体检过一次（高通 + 分帧 + 裁剪 + 增益），识别时
     * 以前又整段算第二遍 —— 白烧 CPU，而且两次结果一旦分叉就是隐性 bug（同一个纯函数跑两遍
     * 现在看起来一致，将来改了一处就会不一致）。现在把 {@link #analyzePrepared} 的返回值传进来。
     *
     * @return 每路一行结果，主模型那行永远排第一；音频太短/太静/只有噪声返回空表
     */
    static List<Line> transcribe(Prepared prepared) {
        loadSettings();
        if (prepared == null || prepared.audio() == null) {
            return List.of();
        }
        if (!prepared.audio().usable()) {
            LOG.info("这段录音没送进模型：{}（{}）", prepared.audio().problem(), prepared.audio().detail());
            return List.of();
        }
        float[] samples = prepared.samples();
        List<VoiceModel> models = runModels();
        try {
            if (models.size() <= 1 || !parallel) {
                return runSequential(models, samples);
            }
            return runParallel(models, samples);
        } finally {
            // 所有模型跑完立刻把这段音频从内存里抹掉
            Arrays.fill(samples, 0f);
        }
    }

    /**
     * 只做体检、不跑模型：给「松开按键」那一刻用，好在几百毫秒内就告诉玩家
     * 「太轻/太远/没收到声音」，而不是让人等模型跑完再听到一句「没听清」。
     */
    static Audio analyze(byte[] pcm, int length) {
        return prepare(pcm, length).audio();
    }

    /** 体检并把处理好的音频一起带回来（V-08：交给 {@link #transcribe(Prepared)} 复用，不再算第二遍）。 */
    static Prepared analyzePrepared(byte[] pcm, int length) {
        return prepare(pcm, length);
    }

    /** 主模型的名字（界面上显示用；未下载时会显示实际回退到的那个）。 */
    static String primaryLabel() {
        loadSettings();
        return effectivePrimary().label();
    }

    /** 聊天栏里显示不显示副模型那一行。 */
    static boolean showOtherOn() {
        loadSettings();
        return showOther;
    }

    /** 取走攒下的一句提示（没有就返回空串，只提示一次）。 */
    static String consumeNotice() {
        String value = notice;
        notice = "";
        return value;
    }

    // ------------------------------------------------------------------ 设置页/命令用

    static List<VoiceModel> catalog() {
        return VoiceModelCatalog.all();
    }

    static boolean installed(VoiceModel model) {
        return VoiceModelStore.installed(model);
    }

    static String statusText(VoiceModel model) {
        return VoiceModelStore.statusText(model);
    }

    static String primaryId() {
        loadSettings();
        return primaryKey;
    }

    static String secondaryId() {
        loadSettings();
        return secondaryKey;
    }

    static String mirror() {
        loadSettings();
        return mirror;
    }

    static boolean compareOn() {
        loadSettings();
        return showOther;
    }

    static boolean parallelOn() {
        loadSettings();
        return parallel;
    }

    static boolean preloadOn() {
        loadSettings();
        return preload;
    }

    static void setPrimary(String id) {
        String canonical = VoiceModelCatalog.canonicalId(id);
        if (canonical != null) {
            primaryKey = canonical;
        }
    }

    static void setSecondary(String id) {
        if (id == null || id.isBlank() || id.equalsIgnoreCase("off") || id.equals("关")) {
            secondaryKey = "off";
            return;
        }
        String canonical = VoiceModelCatalog.canonicalId(id);
        if (canonical != null) {
            secondaryKey = canonical;
        }
    }

    static void setCompare(boolean value) {
        showOther = value;
    }

    static void setParallel(boolean value) {
        parallel = value;
    }

    static void setPreload(boolean value) {
        preload = value;
    }

    static void setMirror(String url) {
        if (url != null && !url.isBlank()) {
            mirror = url.trim();
        }
    }

    /** 设置页点「保存」时调用：落盘 + 在后台把不再需要的模型从内存里放掉、把新的装上。 */
    static void saveAndApply() {
        synchronized (SETTINGS_LOCK) {
            settingsLoaded = true;
            saveSettings();
        }
        reconcileAsync();
    }

    static void download(String id) {
        VoiceModel model = VoiceModelCatalog.byId(id);
        if (model == null) {
            return;
        }
        VoiceModelDownloader.start(model, mirror);
    }

    static void cancelDownload() {
        VoiceModelDownloader.cancel();
    }

    static VoiceModelDownloader.Progress downloadProgress() {
        return VoiceModelDownloader.progress();
    }

    static boolean downloading() {
        return VoiceModelDownloader.busy();
    }

    /** 下载完成后调：如果它就是当前要用的模型，立刻在后台装进内存，不用重进游戏。 */
    static void afterDownload(String id) {
        VoiceModel model = VoiceModelCatalog.byId(id);
        if (model == null) {
            return;
        }
        notice = "「" + model.label() + "」下载完成，已可以使用";
        Thread worker = new Thread(() -> {
            for (VoiceModel wanted : runModels()) {
                ensure(wanted);
            }
        }, "warehouse-keeper-voice-after-download");
        worker.setDaemon(true);
        worker.start();
    }

    /** 设置页删掉一个模型后调：把内存里的那份也放掉。 */
    static void forget(String id) {
        unload(id);
    }

    // ------------------------------------------------------------------ 模型选择

    private static List<Line> runSequential(List<VoiceModel> models, float[] samples) {
        List<Line> out = new ArrayList<>(models.size());
        for (VoiceModel model : models) {
            out.add(one(model, samples));
        }
        return out;
    }

    /** 多个模型各占一条线程同时解码：总耗时≈慢的那个，而不是几个相加。 */
    private static List<Line> runParallel(List<VoiceModel> models, float[] samples) {
        List<Future<Line>> futures = new ArrayList<>(models.size());
        for (VoiceModel model : models) {
            futures.add(POOL.submit(() -> one(model, samples)));
        }
        List<Line> out = new ArrayList<>(models.size());
        for (int i = 0; i < futures.size(); i++) {
            VoiceModel model = models.get(i);
            boolean primary = model.id().equals(primaryKey);
            try {
                out.add(futures.get(i).get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                out.add(new Line(model.label(), "", primary, "识别被打断"));
            } catch (ExecutionException e) {
                out.add(new Line(model.label(), "", primary,
                        describe(e.getCause() == null ? e : e.getCause())));
            }
        }
        return out;
    }

    /**
     * 一个模型跑一遍。
     *
     * <p>整段（装载 + 解码）都压在这个模型的锁里：解码期间别的线程不能把它从内存里放掉。
     * {@code synchronized} 可重入，所以里面再调 {@link #ensure} 是安全的。
     */
    private static Line one(VoiceModel model, float[] samples) {
        boolean primary = model.id().equals(primaryKey);
        synchronized (lockFor(model.id())) {
            String problem = ensure(model);
            if (problem != null) {
                return new Line(model.label(), "", primary, problem);
            }
            OfflineRecognizer recognizer = LOADED.get(model.id());
            if (recognizer == null) {
                return new Line(model.label(), "", primary, "模型刚被卸载，请再说一次");
            }
            return new Line(model.label(), run(recognizer, samples), primary, null);
        }
    }

    private static VoiceModel primaryModel() {
        VoiceModel model = VoiceModelCatalog.byId(primaryKey);
        return model == null ? VoiceModelCatalog.byId(VoiceModelCatalog.fallbackId()) : model;
    }

    /** 副模型：设置成 off、和主模型同一个、或还没装，都算「没有副模型」。 */
    private static VoiceModel secondaryModel() {
        if (secondaryKey == null || secondaryKey.isBlank() || secondaryKey.equalsIgnoreCase("off")) {
            return null;
        }
        VoiceModel model = VoiceModelCatalog.byId(secondaryKey);
        if (model == null || model.id().equals(primaryKey)) {
            return null;
        }
        return model;
    }

    /** 主模型没装就自动挑一个已装的；一个都没装则返回玩家选的那个（{@link #ensure} 会给出「还没下载」的提示）。 */
    private static VoiceModel effectivePrimary() {
        VoiceModel wanted = primaryModel();
        if (VoiceModelStore.installed(wanted)) {
            return wanted;
        }
        for (VoiceModel candidate : VoiceModelCatalog.all()) {
            if (VoiceModelStore.installed(candidate)) {
                return candidate;
            }
        }
        return wanted;
    }

    /** 真正会参与识别的模型：主模型一定在，副模型装了才在。 */
    private static List<VoiceModel> runModels() {
        VoiceModel primary = effectivePrimary();
        List<VoiceModel> out = new ArrayList<>(2);
        out.add(primary);
        VoiceModel second = secondaryModel();
        if (second != null && !second.id().equals(primary.id()) && VoiceModelStore.installed(second)) {
            out.add(second);
        }
        return out;
    }

    private static Object lockFor(String id) {
        return MODEL_LOCKS.computeIfAbsent(id, key -> new Object());
    }

    private static String ensure(VoiceModel model) {
        synchronized (lockFor(model.id())) {
            if (LOADED.containsKey(model.id())) {
                return null;
            }
            Failure failed = FAILURES.get(model.id());
            if (failed != null) {
                if (System.currentTimeMillis() - failed.at() < RETRY_MS) {
                    return failed.message();
                }
                // 到点了：清掉失败记录再试一次（瞬时 IO 失败不该让这个模型整局都用不了）
                FAILURES.remove(model.id());
                LOG.info("重试装载语音模型：{}（上次失败：{}）", model.id(), failed.message());
            }
            try {
                Path base = configDir();
                prepareNatives(base.resolve(NATIVE_DIR));
                removeOldVoskFiles(base);
                Path dir = VoiceModelStore.installedDir(model);
                if (dir == null) {
                    return remember(model, "「" + model.label() + "」还没下载（" + model.displaySize()
                            + "），输入 /warehousevoice 打开语音设置页即可下载");
                }
                long began = System.currentTimeMillis();
                LOADED.put(model.id(), load(model, dir));
                LOG.info("语音模型就绪：{}（用时 {} 毫秒，目录 {}）",
                        model.label(), System.currentTimeMillis() - began, dir);
                return null;
            } catch (Throwable t) {
                return remember(model, describe(t));
            }
        }
    }

    private static String remember(VoiceModel model, String message) {
        FAILURES.put(model.id(), new Failure(message, System.currentTimeMillis()));
        LOG.warn("语音模型没准备好：{} → {}", model.id(), message);
        return message;
    }

    /** 把内存里的一个模型放掉（设置页删模型/换模型时用）。 */
    private static void unload(String id) {
        synchronized (lockFor(id)) {
            FAILURES.remove(id);
            OfflineRecognizer recognizer = LOADED.remove(id);
            if (recognizer == null) {
                return;
            }
            try {
                recognizer.release();
                LOG.info("已把语音模型从内存里放掉：{}", id);
            } catch (Throwable t) {
                LOG.warn("卸载语音模型失败：{} → {}", id, t.toString());
            }
        }
    }

    /** 设置改动后：后台放掉不再需要的模型、把要用的装上（换主模型不用重进游戏）。 */
    private static void reconcileAsync() {
        loadSettings();
        List<VoiceModel> keep = runModels();
        List<String> keepIds = new ArrayList<>(keep.size());
        for (VoiceModel model : keep) {
            keepIds.add(model.id());
        }
        Thread worker = new Thread(() -> {
            for (String id : List.copyOf(LOADED.keySet())) {
                if (!keepIds.contains(id)) {
                    unload(id);
                }
            }
            if (preload) {
                for (VoiceModel model : keep) {
                    ensure(model);
                }
            }
        }, "warehouse-keeper-voice-reconcile");
        worker.setDaemon(true);
        worker.start();
    }

    // ------------------------------------------------------------------ 装载

    private static OfflineRecognizer load(VoiceModel model, Path dir) throws IOException {
        Path weights = dir.resolve("model.int8.onnx");
        Path tokens = dir.resolve("tokens.txt");
        OfflineModelConfig.Builder modelConfig = new OfflineModelConfig.Builder()
                .setTokens(tokens.toString())
                .setNumThreads(2)
                .setDebug(false)
                .setProvider("cpu");
        switch (model.kind()) {
            case ZIPFORMER_CTC -> {
                modelConfig.setZipformerCtc(new OfflineZipformerCtcModelConfig.Builder()
                        .setModel(weights.toString()).build());
                modelConfig.setBpeVocab(dir.resolve("bbpe.model").toString());
            }
            case SENSE_VOICE -> modelConfig.setSenseVoice(OfflineSenseVoiceModelConfig.builder()
                    .setModel(weights.toString())
                    .setLanguage("zh")
                    // 关闭逆文本规整：我们要的是「十二个」这种原词，规整成数字反而不好切数量
                    .setInverseTextNormalization(false)
                    .build());
            default -> modelConfig.setParaformer(new OfflineParaformerModelConfig.Builder()
                    .setModel(weights.toString()).build());
        }
        OfflineRecognizerConfig config = new OfflineRecognizerConfig.Builder()
                .setFeatureConfig(new FeatureConfig.Builder().setSampleRate(FEATURE_RATE).setFeatureDim(FEATURE_DIM).build())
                .setOfflineModelConfig(modelConfig.build())
                .build();
        return new OfflineRecognizer(config);
    }

    private static String run(OfflineRecognizer recognizer, float[] samples) {
        OfflineStream stream = null;
        try {
            stream = recognizer.createStream();
            // 输入是采集端的原生采样率（48 kHz），sherpa-onnx 内部重采样到模型要的 16 kHz
            stream.acceptWaveform(samples, MicCapture.SAMPLE_RATE);
            recognizer.decode(stream);
            OfflineRecognizerResult result = recognizer.getResult(stream);
            String text = result == null || result.getText() == null ? "" : result.getText();
            return scrub(text);
        } catch (Throwable t) {
            LOG.warn("识别失败：{}", describe(t));
            return "";
        } finally {
            if (stream != null) {
                try {
                    stream.release();
                } catch (Throwable ignored) {
                    // 释放失败无所谓
                }
            }
        }
    }

    /**
     * 清掉模型吐出来的空白、标点与特殊标记。
     *
     * <p>标点对「物品名 + 数量」的切分只有坏处（{@code 红色的玻璃，十二个} 里的逗号会把短语切开），
     * 而 SenseVoice 这类模型还会在句首吐 {@code <|zh|><|NEUTRAL|>} 这种控制标记 —— 全部丢掉。
     * 数字、字母、汉字与小数点/连字符保留。
     */
    private static String scrub(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            if (text.charAt(i) == '<') {
                int end = text.indexOf("|>", i);
                if (end > 0) {
                    i = end + 2;
                    continue;
                }
            }
            char c = text.charAt(i++);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (Character.isLetterOrDigit(c) || c == '.' || c == '-') {
                out.append(c);
            }
        }
        return out.toString().trim();
    }

    // ------------------------------------------------------------------ 音频处理

    /**
     * 录音端优化：一阶高通 → 按帧统计噪声底/人声电平 → 按**人声电平**归一化 → 掐掉首尾静音。
     *
     * <p>老实现的两处硬伤（就是「只认我的声音」的主因）：
     * <ol>
     *     <li>增益用「整段峰值」算，且上限只有 8 倍 —— 只有嘴贴麦克风、峰值够大的人才能被归一化到
     *         目标电平，别人（更轻/更远）会拿到一段比目标小得多的音频；</li>
     *     <li>没有高通、也没有噪声底概念 —— 低频轰鸣会冒充「峰值」把增益预算吃掉，
     *         而放大 8 倍同时也把底噪放大了 8 倍。</li>
     * </ol>
     * 现在用 20 ms 帧的**第 95 百分位**代表人声、**第 10 百分位**代表噪声底：信噪比不达标就直接
     * 告诉玩家「太轻/太远」而不是硬塞给模型；达标时按人声电平归一化，并留削顶余量。
     */
    private static Prepared prepare(byte[] pcm, int length) {
        // V-02：公开入口不信任调用方给的 length —— 越界以前会抛 ArrayIndexOutOfBoundsException，
        // 而调用方没接住，直接把客户端崩掉。
        if (pcm == null || pcm.length == 0) {
            return failed("没有录到音频数据");
        }
        int count = Math.max(0, Math.min(length, pcm.length)) / 2;
        if (count < MicCapture.SAMPLE_RATE / 10) {
            return failed("录到的声音太短（不到 0.1 秒）");
        }
        // 1) 一阶高通（y[n] = x[n] - x[n-1] + a*y[n-1]）：干掉直流偏移与几十赫兹的轰鸣
        double a = Math.exp(-2.0D * Math.PI * HIGHPASS_HZ / MicCapture.SAMPLE_RATE);
        float[] filtered = new float[count];
        double prevIn = 0.0D;
        double prevOut = 0.0D;
        for (int i = 0; i < count; i++) {
            double x = sampleAt(pcm, i);
            double y = x - prevIn + a * prevOut;
            prevIn = x;
            prevOut = y;
            filtered[i] = (float) y;
        }
        // 2) 20 毫秒一帧算均方根，再取分位数当「人声」与「噪声底」
        int frames = count / FRAME_SAMPLES;
        if (frames < 1) {
            return failed("录到的声音太短（不到 0.1 秒）");
        }
        double[] levels = new double[frames];
        for (int f = 0; f < frames; f++) {
            double sum = 0.0D;
            int base = f * FRAME_SAMPLES;
            for (int i = 0; i < FRAME_SAMPLES; i++) {
                float value = filtered[base + i];
                sum += (double) value * value;
            }
            levels[f] = Math.sqrt(sum / FRAME_SAMPLES);
        }
        int peak = 0;
        for (int i = 0; i < count; i++) {
            int magnitude = (int) Math.abs(filtered[i]);
            if (magnitude > peak) {
                peak = magnitude;
            }
        }
        double[] sorted = levels.clone();
        Arrays.sort(sorted);
        double speechRms = sorted[(int) Math.round(0.95D * (frames - 1))];
        double middleRms = sorted[(int) Math.round(0.50D * (frames - 1))];
        double noiseRms = sorted[(int) Math.round(0.10D * (frames - 1))];
        if (peak < MIN_PEAK) {
            return failed("没收到声音（最大电平 " + peak + "／32767）——检查麦克风是否静音、被其他程序独占，"
                    + "或 Windows 麦克风权限没开", peak, noiseRms, speechRms);
        }
        double floor = Math.max(noiseRms, NOISE_FLOOR);
        // 只判一条：人声段要压过噪声底。刻意不做「起伏必须多大」这类判据 ——
        // 声音稳、停顿少的正常说话起伏也小，会被误判成「没说话」，方向刚好和要修的问题相反。
        if (speechRms < SNR_REJECT * floor) {
            return failed("人声太轻或太远（人声 " + Math.round(speechRms) + "，中位 " + Math.round(middleRms)
                    + "，环境噪声 " + Math.round(noiseRms) + "）——靠近麦克风、说大声点再说一次",
                    peak, noiseRms, speechRms);
        }
        // 3) 增益按人声段电平算，并用削顶余量兜一层：宁可稍微小一点，也不要失真
        double gain = Math.min(MAX_GAIN, Math.max(1.0D, TARGET_RMS / speechRms));
        gain = Math.min(gain, HEAD_ROOM / Math.max(1.0D, peak));
        // 4) 掐头去尾：门限取「噪声底的 3 倍」和「峰值的 4%」里较大的那个；一帧都没过门限就整段保留
        double threshold = Math.max(SNR_TRIM * floor, peak * 0.04D);
        int firstFrame = -1;
        int lastFrame = -1;
        for (int f = 0; f < frames; f++) {
            if (levels[f] >= threshold) {
                firstFrame = f;
                break;
            }
        }
        for (int f = frames - 1; f >= 0; f--) {
            if (levels[f] >= threshold) {
                lastFrame = f;
                break;
            }
        }
        if (firstFrame < 0 || lastFrame < firstFrame) {
            firstFrame = 0;
            lastFrame = frames - 1;
        }
        int first = Math.max(0, firstFrame * FRAME_SAMPLES - TRIM_MARGIN);
        int last = Math.min(count - 1, (lastFrame + 1) * FRAME_SAMPLES + TRIM_MARGIN);
        int used = last - first + 1;
        float[] out = new float[used];
        for (int i = 0; i < used; i++) {
            double value = filtered[first + i] * gain / 32768.0D;
            out[i] = (float) Math.max(-1.0D, Math.min(1.0D, value));
        }
        Audio audio = new Audio(used, peak, noiseRms, speechRms, gain, null);
        LOG.info("录音体检：{}（掐掉首尾后 {} 个样本）", audio.detail(), used);
        return new Prepared(out, audio);
    }

    private static Prepared failed(String problem) {
        return failed(problem, 0, 0.0D, 0.0D);
    }

    /**
     * 失败时也把**已经算出来的**电平度量带上（V-01）。
     *
     * <p>以前失败分支一律塞 0，于是日志「录音没通过体检」后面那串数字永远是 0：玩家说「我明明
     * 说话了却被判太轻」时，看不到当时的人声/噪声底/峰值到底是多少，等于没有排查线索。
     */
    private static Prepared failed(String problem, int peak, double noiseRms, double speechRms) {
        return new Prepared(null, new Audio(0, peak, noiseRms, speechRms, 1.0D, problem));
    }

    private static int sampleAt(byte[] pcm, int index) {
        int i = index * 2;
        return (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
    }

    // ------------------------------------------------------------------ 原生库与文件

    /**
     * 把原生库解压到 config 目录（只做一次），并告诉 sherpa-onnx 去哪里加载。
     *
     * <p>sherpa-onnx 的 {@code LibraryUtils} 支持系统属性 {@code sherpa_onnx.native.path}：
     * 指向一个目录时它会先 {@code System.load(onnxruntime.dll)}、再 {@code System.load(sherpa-onnx-jni.dll)}。
     * 这是唯一在「模组 jar 由 Fabric 类加载器加载」的环境里可靠的方式（它自带的 classpath 解压
     * 路径在游戏里找不到模组 jar，会退化成 {@code System.loadLibrary} 并失败）；按绝对路径先加载
     * 我们自己的 onnxruntime 还能避开系统目录里那个旧版本（{@code C:\Windows\System32\onnxruntime.dll}
     * 是 1.17.1，用错会直接崩）。必须在任何 sherpa 类被首次使用之前调用。
     *
     * <p>方法同时负责「原生库只加载一次」：属性设好之后主动 {@link LibraryUtils#load()} 一次，
     * 这样后面多个模型并行加载时不会同时去动原生库。用的是独立的 {@link #NATIVE_LOCK}，
     * 刻意不用类监视器 —— 否则会和每模型锁形成 AB-BA 死锁。
     */
    private static void prepareNatives(Path dir) throws IOException {
        synchronized (NATIVE_LOCK) {
            Files.createDirectories(dir);
            for (String name : NATIVE_FILES) {
                Path target = dir.resolve(name);
                // 和模型文件一样按「语音包里的字节数」校验：上次解压中断留下的半个 DLL
                // 以前会被 size>0 放过去，之后报 UnsatisfiedLinkError 且永不自愈。
                if (ensureFile(NATIVE_RESOURCE_DIR + name, target, 0L)) {
                    LOG.info("首次解压语音原生库到硬盘（以后都直接调用）：{}", target);
                }
            }
            System.setProperty("sherpa_onnx.native.path", dir.toAbsolutePath().toString());
            try {
                LibraryUtils.load();
            } catch (Throwable t) {
                // 这里必须当致命错误：一旦放过去，sherpa 会退化成「把 jar 里的原生库解压到
                // %TEMP%」（config 目录之外写盘，违背本附加包的隐私约定）。
                throw new IOException("原生库加载失败：" + describe(t), t);
            }
        }
    }

    /** 旧引擎（Vosk）留下的文件顺手清掉，别白占磁盘。 */
    private static void removeOldVoskFiles(Path base) {
        for (String name : new String[]{"model-small-cn", "model-small-cn.ready", "natives", "sherpa-natives/win32-x86-64"}) {
            Path path = base.resolve(name);
            try {
                if (Files.isDirectory(path)) {
                    try (var walk = Files.walk(path)) {
                        for (Path each : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                            Files.deleteIfExists(each);
                        }
                    }
                } else {
                    Files.deleteIfExists(path);
                }
            } catch (Throwable ignored) {
                // 清理失败无所谓
            }
        }
    }

    /**
     * 硬盘上已经有完整的一份就直接用，返回 {@code false}；
     * 只有缺失、空文件、或大小和语音包里对不上（上次解压中断）才重新解压，返回 {@code true}。
     *
     * @param fallbackExpected 读不到 jar 内条目大小时用的期望字节数（0 表示不校验）
     */
    private static boolean ensureFile(String resource, Path target, long fallbackExpected) throws IOException {
        long expected = expectedSize(resource);
        if (expected <= 0L) {
            expected = fallbackExpected;
        }
        if (Files.isRegularFile(target)) {
            long actual = Files.size(target);
            if (actual > 0 && (expected <= 0L || actual == expected)) {
                return false;
            }
            LOG.info("硬盘上的文件不完整（{} 字节，语音包里是 {} 字节），重新解压一次：{}",
                    actual, expected <= 0L ? "未知" : expected, target);
        }
        extract(resource, target);
        return true;
    }

    /** 资源在语音包里的原始大小（读不到返回 -1，表示不做校验）。 */
    private static long expectedSize(String resource) {
        try {
            URL url = SherpaEngine.class.getResource(resource);
            if (url == null) {
                return -1L;
            }
            URLConnection connection = url.openConnection();
            // jar 内的条目直接问 JarEntry 要大小。**不要** setUseCaches(false)：
            // 那会让每次查询都新建一个 JarFile 且从不关闭（Windows 上表现为语音包被占用）。
            if (connection instanceof JarURLConnection jar) {
                var entry = jar.getJarEntry();
                long size = entry == null ? -1L : entry.getSize();
                return size > 0L ? size : -1L;
            }
            long size = connection.getContentLengthLong();
            return size > 0L ? size : -1L;
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** 把随包的资源解压到磁盘（先写 .part 再原子改名；失败时清掉 .part）。 */
    private static void extract(String resource, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        try {
            try (InputStream in = SherpaEngine.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IOException("语音包里缺少 " + resource);
                }
                try (OutputStream out = Files.newOutputStream(temp)) {
                    in.transferTo(out);
                }
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            try {
                Files.deleteIfExists(temp);
            } catch (Throwable ignored) {
                // 清理失败无所谓
            }
            if (t instanceof IOException io) {
                throw io;
            }
            throw new IOException(t.toString(), t);
        }
    }

    private static Path configDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("warehouse-keeper-voice");
    }

    private static Path settingsFile() {
        return configDir().resolve("settings.txt");
    }

    // ------------------------------------------------------------------ 设置

    private static void loadSettings() {
        if (settingsLoaded) {
            return;
        }
        synchronized (SETTINGS_LOCK) {
            if (settingsLoaded) {
                return;
            }
            loadSettingsLocked();
        }
    }

    /** 真正读取设置；只在持有 {@link #SETTINGS_LOCK} 时调用。 */
    private static void loadSettingsLocked() {
        try {
            Path file = settingsFile();
            if (!Files.isRegularFile(file)) {
                saveSettings();
                return;
            }
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                    continue;
                }
                String[] parts = line.split("=", 2);
                String key = parts[0].trim().toLowerCase(Locale.ROOT);
                String value = parts[1].trim().toLowerCase(Locale.ROOT);
                switch (key) {
                    case "primary" -> {
                        String id = VoiceModelCatalog.canonicalId(value);
                        if (id != null) {
                            primaryKey = id;
                        }
                    }
                    case "secondary" -> {
                        if (value.equals("off") || value.equals("none") || value.equals("关")) {
                            secondaryKey = "off";
                        } else {
                            String id = VoiceModelCatalog.canonicalId(value);
                            if (id != null) {
                                secondaryKey = id;
                            }
                        }
                    }
                    case "compare" -> showOther = truthy(value);
                    case "parallel" -> parallel = truthy(value);
                    case "preload" -> preload = truthy(value);
                    case "mirror" -> {
                        if (value.startsWith("http")) {
                            mirror = value;
                        }
                    }
                    default -> {
                        // 老版本或手写的别的键：忽略
                    }
                }
            }
        } catch (Throwable t) {
            LOG.warn("读取语音设置失败：{}", t.toString());
        } finally {
            settingsLoaded = true;
        }
    }

    private static boolean truthy(String value) {
        return value.equals("on") || value.equals("true") || value.equals("1") || value.equals("yes")
                || value.equals("开");
    }

    private static void saveSettings() {
        try {
            Path file = settingsFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file, "# 语音附加包设置（在语音设置页里点着改，也可以手改这个文件；手改重进游戏生效）\n"
                    + "# primary   = " + modelIds() + "   主模型：它的结果优先用来下单\n"
                    + "# secondary = " + modelIds() + " | off   副模型：主模型没听出时顶上（没装就自动忽略）\n"
                    + "# compare   = on | off    聊天栏里是否多显示副模型那一行（副模型始终在下单流程里干活）\n"
                    + "# parallel  = on | off    多个模型是否并行跑（关掉就一个接一个，慢但省内存）\n"
                    + "# preload   = on | off    进游戏后是否在后台先把模型装进内存\n"
                    + "# mirror    = 下载模型用的镜像地址（默认 " + VoiceModelCatalog.defaultMirror() + "）\n"
                    + "primary=" + primaryKey + "\n"
                    + "secondary=" + secondaryKey + "\n"
                    + "compare=" + (showOther ? "on" : "off") + "\n"
                    + "parallel=" + (parallel ? "on" : "off") + "\n"
                    + "preload=" + (preload ? "on" : "off") + "\n"
                    + "mirror=" + mirror + "\n", StandardCharsets.UTF_8);
        } catch (Throwable t) {
            LOG.warn("保存语音设置失败：{}", t.toString());
        }
    }

    /** 设置文件注释里那句「可选值」按清单自动生成，避免以后加模型忘了改注释。 */
    private static String modelIds() {
        StringBuilder builder = new StringBuilder();
        for (VoiceModel model : VoiceModelCatalog.all()) {
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append(model.id());
        }
        return builder.toString();
    }

    private static String seconds(long ms) {
        return String.format(Locale.ROOT, "%.1f", ms / 1000.0D);
    }

    private static String describe(Throwable t) {
        List<String> parts = new ArrayList<>();
        for (Throwable cause = t; cause != null && parts.size() < 3; cause = cause.getCause()) {
            String message = cause.getMessage();
            parts.add(cause.getClass().getSimpleName() + (message == null ? "" : ": " + message));
        }
        return String.join(" ← ", parts);
    }
}
