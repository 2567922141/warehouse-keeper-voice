package com.ds.warehouse.voice;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.LibraryUtils;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 离线语音识别：sherpa-onnx + 两个中文模型，同一句话两个模型**同时**各跑一遍。
 *
 * <ul>
 *     <li>{@code paraformer}：Paraformer 中文小模型（约 78 MB），最快，默认主模型；</li>
 *     <li>{@code zipformer}：Zipformer-CTC 中文 int8 大模型（约 350 MB），更稳但更慢。</li>
 * </ul>
 *
 * <p>两个模型的关系：**永远一起跑**（并行，总耗时≈慢的那个），结果也都参与匹配 ——
 * 主模型没听懂时，另一个模型的结果会自动顶上（见 {@code VoicePhrase.handle}）。
 * 没有「只跑一个模型」的开关，免得无意间变成单模型。
 *
 * <p>设置写在 {@code config/warehouse-keeper-voice/settings.txt}（第一次运行自动生成）：
 * {@code primary} 决定哪一路排在最前面，{@code compare} 决定聊天栏里要不要多显示另一个模型那一行，
 * {@code parallel} 是否并行，{@code preload} 是否进游戏后台预装。改完重进游戏生效。
 *
 * <p>模型与原生库只在**第一次**解压到硬盘（{@code config/warehouse-keeper-voice/}），
 * 之后每次都直接调用硬盘上的文件（大小和语音包里一致就不再解压），进游戏时后台预装。
 *
 * <p>隐私：音频只以 PCM 形式在内存里过一趟，所有模型跑完立刻清零；不联网、不落盘
 * （唯一写盘的是把随包的模型/原生库解压到 config 目录，只写一次）。
 */
final class SherpaEngine {

    private static final Logger LOG = LoggerFactory.getLogger("warehouse-keeper-voice");

    /** 一个可选模型：设置里的键、界面上显示的名字、jar 内资源目录、落盘目录名、是否 CTC 结构。 */
    private record Spec(String key, String label, String resourceDir, String dirName, boolean zipformerCtc) {
    }

    private static final Spec PARAFORMER = new Spec(
            "paraformer", "Paraformer小", "/warehouse-keeper-voice-sherpa/", "sherpa-paraformer-zh-small", false);
    private static final Spec ZIPFORMER = new Spec(
            "zipformer", "Zipformer大", "/warehouse-keeper-voice-sherpa-zipformer/", "sherpa-zipformer-ctc-zh-int8", true);
    private static final List<Spec> SPECS = List.of(PARAFORMER, ZIPFORMER);

    /** 原生库在 jar 里的位置与落点（见 {@link #prepareNatives}）。 */
    private static final String NATIVE_RESOURCE_DIR = "/sherpa-onnx/native/win-x64/";
    private static final String NATIVE_DIR = "sherpa-natives";
    private static final String[] NATIVE_FILES = {"onnxruntime.dll", "sherpa-onnx-jni.dll"};

    /** 模型训练用的特征采样率；输入音频由 sherpa-onnx 自己重采样到这个值。 */
    private static final int FEATURE_RATE = 16000;
    private static final int FEATURE_DIM = 80;
    /** 整段峰值低于这个数就当没说话（16 位满量程 32767）。 */
    private static final int MIN_PEAK = 120;
    /** 归一化后的目标峰值（约 -3 dBFS）。 */
    private static final double TARGET_PEAK = 0.7D * 32767.0D;
    /** 最大增益：再轻也不无限放大，免得把底噪放成语音。 */
    private static final double MAX_GAIN = 8.0D;

    private static final Map<String, OfflineRecognizer> LOADED = new ConcurrentHashMap<>();
    /** 失败记录：给玩家看的原因 + 记录时间（过一段时间允许重试，瞬时失败不该让模型整局不可用）。 */
    private record Failure(String message, long at) {
    }

    private static final Map<String, Failure> FAILURES = new ConcurrentHashMap<>();
    /** 装载失败后过多久允许再试一次。 */
    private static final long RETRY_MS = 60_000L;
    /** 每个模型一把锁：后台预装和「按住说话」可能同时要同一个模型，别把 350 MB 模型装两遍。 */
    private static final Map<String, Object> SPEC_LOCKS = Map.of(
            PARAFORMER.key(), new Object(),
            ZIPFORMER.key(), new Object());
    /**
     * 原生库只加载一次的锁。
     *
     * <p>**必须**是独立的一把锁，不能用类监视器：识别线程走 {@code prepare() → ensure()}
     * （类监视器 → 模型锁），预装线程走 {@code ensure() → prepareNatives()}（模型锁 → 类监视器），
     * 用类监视器就是标准的 AB-BA 死锁 —— 语音引擎永久卡死，且主线程按 B 时也会被冻住
     * （{@code toggleShowOther()} 同样要类监视器）。
     */
    private static final Object NATIVE_LOCK = new Object();
    /** 设置文件的锁（与模型锁、原生库锁相互独立，不会形成环）。 */
    private static final Object SETTINGS_LOCK = new Object();

    /** 两个模型并行识别用的线程池（守护线程，进程退出时自己结束）。 */
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "warehouse-keeper-voice-asr");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile String primaryKey = PARAFORMER.key();
    /** 聊天栏里要不要多显示另一个模型那一行（两个模型无论如何都会跑）。 */
    private static volatile boolean showOther = true;
    private static volatile boolean parallel = true;
    private static volatile boolean preload = true;
    private static volatile boolean preloadStarted;
    private static volatile boolean settingsLoaded;

    /** 一路识别结果。 */
    record Line(String label, String text, boolean primary, String problem) {
    }

    private SherpaEngine() {
    }

    /**
     * 就绪返回 null，否则返回给玩家看的中文原因。
     *
     * <p>**不能**加 {@code synchronized}：那会在持有类监视器时再去抢模型锁，与
     * {@code ensure() → prepareNatives()} 的方向相反，构成 AB-BA 死锁（见 {@link #NATIVE_LOCK}）。
     * 「只装载一次」由每模型锁保证。
     */
    static String prepare() {
        loadSettings();
        return ensure(primarySpec());
    }

    /**
     * 进游戏后在后台把要用的模型都装进内存（两个并行）。
     *
     * <p>装好之后第一次按住 V 就能直接识别，不用再等模型加载；磁盘上的模型文件不会重新解压。
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
        List<Spec> specs = allSpecs();
        Thread worker = new Thread(() -> {
            long began = System.currentTimeMillis();
            List<Future<String>> futures = new ArrayList<>(specs.size());
            for (Spec spec : specs) {
                futures.add(POOL.submit(() -> ensure(spec)));
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
                    problems.add(specs.get(i).label() + "：" + problem);
                }
            }
            long took = System.currentTimeMillis() - began;
            if (problems.isEmpty()) {
                LOG.info("语音模型预加载完成：{} 个模型已在内存里（用时 {} 毫秒）", ready, took);
                VoiceSession.notifyPreloaded("两个语音模型已在后台装好（用时 " + seconds(took)
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
     * 识别一段音频：主模型一定跑；开了双模型时另一个也跑，默认两条线程同时跑。
     *
     * @return 每路一行结果，主模型那行永远排第一；音频太短/太静返回空表
     */
    static List<Line> transcribe(byte[] pcm, int length) {
        loadSettings();
        float[] samples = condition(pcm, length);
        if (samples == null || samples.length == 0) {
            return List.of();
        }
        List<Spec> specs = allSpecs();
        try {
            if (specs.size() <= 1 || !parallel) {
                return runSequential(specs, samples);
            }
            return runParallel(specs, samples);
        } finally {
            // 所有模型跑完立刻把这段音频从内存里抹掉
            java.util.Arrays.fill(samples, 0f);
        }
    }

    /** 主模型的名字（界面上显示用）。 */
    static String primaryLabel() {
        loadSettings();
        return primarySpec().label();
    }

    /** 聊天栏里显示不显示另一个模型那一行。 */
    static boolean showOtherOn() {
        loadSettings();
        return showOther;
    }

    // ------------------------------------------------------------------ 内部

    private static List<Line> runSequential(List<Spec> specs, float[] samples) {
        List<Line> out = new ArrayList<>(specs.size());
        for (Spec spec : specs) {
            out.add(one(spec, samples));
        }
        return out;
    }

    /** 两个模型各占一条线程同时解码：总耗时≈慢的那个，而不是两个相加。 */
    private static List<Line> runParallel(List<Spec> specs, float[] samples) {
        List<Future<Line>> futures = new ArrayList<>(specs.size());
        for (Spec spec : specs) {
            futures.add(POOL.submit(() -> one(spec, samples)));
        }
        List<Line> out = new ArrayList<>(specs.size());
        for (int i = 0; i < futures.size(); i++) {
            Spec spec = specs.get(i);
            boolean primary = spec.key().equals(primaryKey);
            try {
                out.add(futures.get(i).get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                out.add(new Line(spec.label(), "", primary, "识别被打断"));
            } catch (ExecutionException e) {
                out.add(new Line(spec.label(), "", primary,
                        describe(e.getCause() == null ? e : e.getCause())));
            }
        }
        return out;
    }

    private static Line one(Spec spec, float[] samples) {
        boolean primary = spec.key().equals(primaryKey);
        String problem = ensure(spec);
        if (problem != null) {
            return new Line(spec.label(), "", primary, problem);
        }
        return new Line(spec.label(), run(LOADED.get(spec.key()), samples), primary, null);
    }

    /**
     * 要跑的模型：**永远是两个**，主模型排第一（它的结果优先用来下单）。
     *
     * <p>{@code showOther} 只影响界面显示，不影响这里 —— 两个模型始终都跑、都能顶上。
     */
    private static List<Spec> allSpecs() {
        List<Spec> order = new ArrayList<>(SPECS.size());
        order.add(primarySpec());
        for (Spec spec : SPECS) {
            if (!spec.key().equals(primaryKey)) {
                order.add(spec);
            }
        }
        return order;
    }

    private static Spec primarySpec() {
        for (Spec spec : SPECS) {
            if (spec.key().equals(primaryKey)) {
                return spec;
            }
        }
        return PARAFORMER;
    }

    private static String ensure(Spec spec) {
        Object lock = SPEC_LOCKS.getOrDefault(spec.key(), SherpaEngine.class);
        synchronized (lock) {
            if (LOADED.containsKey(spec.key())) {
                return null;
            }
            Failure failed = FAILURES.get(spec.key());
            if (failed != null) {
                if (System.currentTimeMillis() - failed.at() < RETRY_MS) {
                    return failed.message();
                }
                // 到点了：清掉失败记录再试一次（瞬时 IO 失败不该让这个模型整局都用不了）
                FAILURES.remove(spec.key());
                LOG.info("重试装载语音模型：{}（上次失败：{}）", spec.key(), failed.message());
            }
            try {
                Path base = configDir();
                prepareNatives(base.resolve(NATIVE_DIR));
                removeOldVoskFiles(base);
                long began = System.currentTimeMillis();
                LOADED.put(spec.key(), load(spec, base));
                LOG.info("语音模型就绪：{}（加载用时 {} 毫秒）", spec.dirName(), System.currentTimeMillis() - began);
                return null;
            } catch (Throwable t) {
                String message = describe(t);
                FAILURES.put(spec.key(), new Failure(message, System.currentTimeMillis()));
                LOG.warn("语音模型没准备好：{} → {}", spec.key(), message);
                return message;
            }
        }
    }

    private static OfflineRecognizer load(Spec spec, Path base) throws IOException {
        Path dir = base.resolve(spec.dirName());
        Path model = dir.resolve("model.int8.onnx");
        Path tokens = dir.resolve("tokens.txt");
        Path bpe = dir.resolve("bbpe.model");
        boolean fresh = ensureFile(spec.resourceDir() + "model.int8.onnx", model);
        if (ensureFile(spec.resourceDir() + "tokens.txt", tokens)) {
            fresh = true;
        }
        if (spec.zipformerCtc() && ensureFile(spec.resourceDir() + "bbpe.model", bpe)) {
            fresh = true;
        }
        if (fresh) {
            LOG.info("首次解压语音模型到硬盘（以后都直接调用，不会再解压）：{}", dir);
        } else {
            LOG.info("硬盘上已有完整的语音模型，直接调用（没有解压）：{}", dir);
        }
        OfflineModelConfig.Builder modelConfig = new OfflineModelConfig.Builder()
                .setTokens(tokens.toString())
                .setNumThreads(2)
                .setDebug(false)
                .setProvider("cpu");
        if (spec.zipformerCtc()) {
            modelConfig.setZipformerCtc(new OfflineZipformerCtcModelConfig.Builder().setModel(model.toString()).build());
            modelConfig.setBpeVocab(bpe.toString());
        } else {
            modelConfig.setParaformer(new OfflineParaformerModelConfig.Builder().setModel(model.toString()).build());
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
            return text.replaceAll("\\s+", "").trim();
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
     * 录音端优化：掐掉首尾静音 + 把整段峰值归一化（约 -3 dBFS），再转成 [-1,1] 的 float。
     *
     * <p>实测本机麦克风整段峰值只有 2600~3800（满量程的 ~10%），太轻会让模型丢词。
     */
    private static float[] condition(byte[] pcm, int length) {
        int count = length / 2;
        if (count < MicCapture.SAMPLE_RATE / 10) {
            return null;
        }
        int peak = 0;
        for (int i = 0; i < count; i++) {
            peak = Math.max(peak, Math.abs(sampleAt(pcm, i)));
        }
        if (peak < MIN_PEAK) {
            return null;
        }
        int threshold = Math.max(40, peak / 25);
        int first = 0;
        int last = count - 1;
        for (int i = 0; i < count; i++) {
            if (Math.abs(sampleAt(pcm, i)) >= threshold) {
                first = i;
                break;
            }
        }
        for (int i = count - 1; i >= 0; i--) {
            if (Math.abs(sampleAt(pcm, i)) >= threshold) {
                last = i;
                break;
            }
        }
        int margin = MicCapture.SAMPLE_RATE * 15 / 100;
        first = Math.max(0, first - margin);
        last = Math.min(count - 1, last + margin);
        double gain = Math.min(MAX_GAIN, TARGET_PEAK / peak);
        int used = last - first + 1;
        float[] out = new float[used];
        for (int i = 0; i < used; i++) {
            double value = sampleAt(pcm, first + i) * gain;
            out[i] = (float) Math.max(-1.0D, Math.min(1.0D, value / 32768.0D));
        }
        return out;
    }

    private static int sampleAt(byte[] pcm, int index) {
        int i = index * 2;
        return (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
    }

    /**
     * 把两个原生库解压到 config 目录（只做一次），并告诉 sherpa-onnx 去哪里加载。
     *
     * <p>sherpa-onnx 的 {@code LibraryUtils} 支持系统属性 {@code sherpa_onnx.native.path}：
     * 指向一个目录时它会先 {@code System.load(onnxruntime.dll)}、再 {@code System.load(sherpa-onnx-jni.dll)}。
     * 这是唯一在「模组 jar 由 Fabric 类加载器加载」的环境里可靠的方式（它自带的 classpath 解压
     * 路径在游戏里找不到模组 jar，会退化成 {@code System.loadLibrary} 并失败）；按绝对路径先加载
     * 我们自己的 onnxruntime 还能避开系统目录里那个旧版本（{@code C:\Windows\System32\onnxruntime.dll}
     * 是 1.17.1，用错会直接崩）。必须在任何 sherpa 类被首次使用之前调用。
     *
     * <p>方法同时负责「原生库只加载一次」：属性设好之后主动 {@link LibraryUtils#load()} 一次，
     * 这样后面两个模型并行加载时不会同时去动原生库。用的是独立的 {@link #NATIVE_LOCK}，
     * 刻意不用类监视器 —— 否则会和每模型锁形成 AB-BA 死锁。
     */
    private static void prepareNatives(Path dir) throws IOException {
        synchronized (NATIVE_LOCK) {
            Files.createDirectories(dir);
            for (String name : NATIVE_FILES) {
                Path target = dir.resolve(name);
                // 和模型文件一样按「语音包里的字节数」校验：上次解压中断留下的半个 DLL
                // 以前会被 size>0 放过去，之后报 UnsatisfiedLinkError 且永不自愈。
                if (ensureFile(NATIVE_RESOURCE_DIR + name, target)) {
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
     */
    private static boolean ensureFile(String resource, Path target) throws IOException {
        long expected = expectedSize(resource);
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
                if (key.equals("primary")) {
                    for (Spec spec : SPECS) {
                        if (spec.key().equals(value)) {
                            primaryKey = value;
                        }
                    }
                } else if (key.equals("compare")) {
                    showOther = truthy(value);
                } else if (key.equals("parallel")) {
                    parallel = truthy(value);
                } else if (key.equals("preload")) {
                    preload = truthy(value);
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
            Files.writeString(file, "# 语音附加包设置（改完重进游戏生效）\n"
                    + "# primary  = paraformer | zipformer   哪一路排在最前面（它的结果优先用来下单）\n"
                    + "# compare  = on | off                 聊天栏里是否多显示另一个模型那一行（两个模型始终都跑）\n"
                    + "# parallel = on | off                 两个模型是否并行跑（关掉就一个接一个，慢但省内存）\n"
                    + "# preload  = on | off                 进游戏后是否在后台先把模型装进内存\n"
                    + "primary=" + primaryKey + "\n"
                    + "compare=" + (showOther ? "on" : "off") + "\n"
                    + "parallel=" + (parallel ? "on" : "off") + "\n"
                    + "preload=" + (preload ? "on" : "off") + "\n", StandardCharsets.UTF_8);
        } catch (Throwable t) {
            LOG.warn("保存语音设置失败：{}", t.toString());
        }
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
