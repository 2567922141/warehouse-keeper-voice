package com.ds.warehouse.voice;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 把某个 {@link VoiceModel} 的模型文件下到 {@code models/<id>/}。
 *
 * <p>为什么不用第三方 HTTP 库：语音包的所有依赖都要跟着 jar 走一遍，为了下两个文件引入
 * 一个 HTTP 客户端不划算；JDK 自带的 {@code java.net.http.HttpClient} 已经够用
 * （超时、跟随重定向、Range 续传都支持）。也因此这个类是**纯 JDK**，
 * 能在没有 Minecraft 的进程里跑通。
 *
 * <p>几条刻意的设计：
 * <ul>
 *     <li><b>先写 {@code .part}，校验通过再原子改名。</b>模型文件的「完整性」判断只看正式文件名，
 *         所以下载途中的文件永远不可能被引擎当成模型加载。</li>
 *     <li><b>大小和 sha256 都要过</b>（没配 sha256 就只比大小）。下到 99% 断线、
 *         镜像给了个坏副本、远端悄悄换了文件 —— 这三种都能被挡住。</li>
 *     <li><b>失败一律变成 {@link Progress#problem()} 文本，不往外抛异常。</b>
 *         调用方是「点一下按钮」的界面代码，它没有能力处理 IOException，
 *         抛出去只能变成一次崩溃；写成中文原因，玩家自己就知道该怎么办。</li>
 *     <li><b>同时只允许一个任务。</b>两个线程往同一个 {@code .part} 里写，
 *         会得到「前半段是旧数据、后半段是新数据、大小还正好对得上」的文件，
 *         只有哈希能发现 —— 这种事不该有机会发生。</li>
 *     <li><b>下载源是有序候选表</b>（{@link VoiceFile#sources}，拼法在 {@link VoiceMirrors}）：
 *         一个源失败就换下一个，{@code .part} 留着给下一个源续传；进度里会写清楚当前用的是哪个源。</li>
 * </ul>
 */
public final class VoiceModelDownloader {

    /** 连接超时：镜像站偶尔抽风，30 秒足够判断「这个源连不上」，再久就是白等。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    /** 单次请求超时：指「拿到响应头」的时限，不含后面的持续下载（大文件要下好几分钟）。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /** 读缓冲 64 KB：千兆网下够快，又不会让进度更新太稀。 */
    private static final int BUFFER = 64 * 1024;
    /** 进度更新的两个门槛，先到的那个生效：200 ms 保证界面不卡顿，256 KB 保证慢速时也有更新。 */
    private static final long PUBLISH_MS = 200L;
    private static final long PUBLISH_BYTES = 256L * 1024L;
    private static final String USER_AGENT = "warehouse-keeper-voice/1.0";
    private static final String PART_SUFFIX = ".part";
    /** 打断旧任务后等它收尾的最长时间。 */
    private static final long JOIN_MS = 2000L;

    private static final Object START_LOCK = new Object();

    private static volatile HttpClient client;
    /** 当前（最多一个）任务；任务一结束就置 null，这样结束后的 cancel() 是空操作。 */
    private static volatile Task task;
    /** 正在读的响应体：取消时直接关掉它，让 read() 立刻以异常结束（比 interrupt 可靠）。 */
    private static volatile InputStream activeStream;
    private static volatile Thread worker;
    private static volatile Progress current;
    /** 任务结束时刻（毫秒），0 表示还在跑。用来把平均速度**冻结**在结束那一刻。 */
    private static volatile long endedAt;
    private static volatile Consumer<String> sink = message -> {
    };

    private VoiceModelDownloader() {
    }

    /**
     * 一次下载任务的进度快照（不可变；界面拿到就能直接画）。
     *
     * @param modelId      正在下的模型 id，用来回查中文名
     * @param file         当前正在下的落盘文件名（没开始/已结束为空串）
     * @param fileIndex    当前文件序号，从 0 开始
     * @param fileCount    这次一共要几个文件
     * @param doneBytes    已完成字节 = 本次已跳过的 + 已完成的 + 当前 .part 已有的
     * @param totalBytes   整个模型的总字节数
     * @param startedAtMillis 任务开始时刻
     * @param running      线程还在跑
     * @param finished     任务已结束（成功/失败/取消都算）
     * @param problem      给玩家看的中文失败原因；成功为 null
     * @param source       当前在试的下载地址（失败时保留最后一个，方便排查）
     */
    public record Progress(String modelId, String file, int fileIndex, int fileCount,
                           long doneBytes, long totalBytes, long startedAtMillis,
                           boolean running, boolean finished, String problem, String source) {

        /** 0..1；总数未知（<=0）时返回 0，不返回 NaN —— 界面乘 100 画进度条，NaN 会直接画出乱码。 */
        public double ratio() {
            if (totalBytes <= 0L) {
                return 0.0D;
            }
            return Math.max(0.0D, Math.min(1.0D, (double) doneBytes / (double) totalBytes));
        }

        /**
         * 从开始到现在的平均速度（MB/s）。
         *
         * <p>结束之后用**结束时刻**算：否则界面一直刷新，「已用时间」越涨越大，
         * 平均速度会眼睁睁地往下掉，看着像卡住不动了。
         */
        public double mbps() {
            if (startedAtMillis <= 0L || doneBytes <= 0L) {
                return 0.0D;
            }
            long end = endedAt > 0L ? endedAt : System.currentTimeMillis();
            long elapsed = end - startedAtMillis;
            if (elapsed <= 0L) {
                return 0.0D;
            }
            return doneBytes / 1048576.0D / (elapsed / 1000.0D);
        }

        /** 界面用的一行中文摘要，例如 {@code SenseVoice（推荐）  42.3 / 228 MB  18%  1.8 MB/s}。 */
        public String text() {
            String name = label(modelId);
            if (problem != null && !problem.isEmpty()) {
                return name + " · 下载失败：" + problem;
            }
            StringBuilder out = new StringBuilder(96);
            out.append(name).append("  ").append(String.format(Locale.ROOT,
                    "%.1f / %d MB  %.0f%%  %.1f MB/s",
                    doneBytes / 1048576.0D, totalBytes / 1048576L, ratio() * 100.0D, mbps()));
            // 显示「当前源」：玩家选了自动/镜像后，最想知道的就是这一份到底从哪儿下来的。
            if (source != null && !source.isEmpty()) {
                out.append("  [").append(VoiceMirrors.sourceLabel(source)).append(']');
            }
            if (running && file != null && !file.isEmpty()) {
                out.append("  ").append(file);
                if (fileCount > 1) {
                    out.append('（').append(fileIndex + 1).append('/').append(fileCount).append('）');
                }
            } else if (finished) {
                out.append("  完成");
            }
            return out.toString();
        }
    }

    /** 一次任务的运行态；只有下载线程写，所以普通字段即可（Progress 快照本身是 volatile 的）。 */
    private static final class Task {
        final VoiceModel model;
        final Path dir;
        final List<VoiceFile> files;
        final long total;
        /** 下载源模式 id（见 {@link VoiceMirrors}）；拼地址时交给 {@link VoiceFile#sources}。 */
        final String mode;
        /** 自定义前缀，只有 {@link VoiceMirrors#CUSTOM} 模式下用得到。 */
        final String customBase;
        final long startedAt = System.currentTimeMillis();
        volatile boolean cancel;
        int index = -1;
        /** 本次任务里已经**完整落盘**的字节（跳过的 + 下完的）。 */
        long base;
        /** 当前 .part 已有的字节。 */
        long partial;
        String source = "";

        Task(VoiceModel model, String mode, String customBase) {
            this.model = model;
            this.dir = VoiceModelStore.dirOf(model);
            this.files = model.files();
            this.total = model.totalBytes();
            this.mode = mode;
            this.customBase = customBase;
        }

        String fileName() {
            return index >= 0 && index < files.size() ? files.get(index).name() : "";
        }
    }

    /** 内部信号：只有「玩家点了取消」会用它，和网络错误要分开处理（一个有 .part 要留，一个要换源）。 */
    private static final class Cancelled extends Exception {
        private static final long serialVersionUID = 1L;
    }

    // ------------------------------------------------------------------ 对外

    /** 日志出口；默认丢弃（这个类不许碰 slf4j，也不该逼调用方准备日志系统）。 */
    public static void setLog(Consumer<String> sink) {
        VoiceModelDownloader.sink = sink == null ? message -> {
        } : sink;
    }

    /** 有没有任务在跑。 */
    public static boolean busy() {
        Thread thread = worker;
        return thread != null && thread.isAlive();
    }

    /** 最近一次任务的进度（含已结束的那次）；从没开始过返回 null。 */
    public static Progress progress() {
        return current;
    }

    /**
     * 取消当前任务：**保留 .part**，下次接着下。
     *
     * <p>做法是把正在读的响应体直接关掉 —— 阻塞在 {@code read()} 上的线程会立刻以异常退出，
     * 比 {@code Thread.interrupt()} 可靠（后者对正在读 socket 的线程常常毫无作用）。
     */
    public static void cancel() {
        Task running = task;
        if (running == null) {
            return;
        }
        running.cancel = true;
        closeQuietly(activeStream);
        log("已请求取消下载（.part 保留，下次接着下）");
    }

    /**
     * 后台开始下载；已有任务在跑时先把它取消，等它收尾（最多 2 秒）。
     *
     * <p>等不到旧线程结束就**拒绝启动**而不是硬开第二个：两个线程往同一个 .part 里写，
     * 会产出「大小正确、内容错一半」的文件，只有哈希能发现。宁可让玩家再点一次。
     *
     * @param mode       下载源模式（{@link VoiceMirrors} 里的 id），null/认不出按官方处理
     * @param customBase 自定义前缀，只有 {@code custom} 模式用
     */
    public static void start(VoiceModel model, String mode, String customBase) {
        if (model == null) {
            return;
        }
        synchronized (START_LOCK) {
            Thread old = worker;
            if (old != null && old.isAlive()) {
                cancel();
                try {
                    old.join(JOIN_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (old.isAlive()) {
                    String problem = "上一个下载任务没有停下来，请稍后再试";
                    current = new Progress(model.id(), "", 0, model.files().size(), 0L,
                            model.totalBytes(), System.currentTimeMillis(), false, true, problem, "");
                    log("拒绝启动下载：" + problem);
                    return;
                }
            }
            endedAt = 0L;
            Task fresh = new Task(model, mode, customBase);
            task = fresh;
            current = new Progress(model.id(), "", 0, fresh.files.size(), 0L, fresh.total,
                    fresh.startedAt, true, false, null, "");
            Thread thread = new Thread(() -> run(fresh), "warehouse-keeper-voice-download");
            thread.setDaemon(true);
            worker = thread;
            thread.start();
            log("开始下载 " + model.label() + "（" + model.displaySize() + "，" + fresh.files.size()
                    + " 个文件）→ " + fresh.dir);
            log("下载源：" + VoiceMirrors.describe(mode, customBase));
            String fallback = VoiceMirrors.fallbackReason(mode, model);
            if (fallback != null) {
                log(fallback);
            }
        }
    }

    // ------------------------------------------------------------------ 内部：任务骨架

    private static void run(Task task) {
        String problem = null;
        try {
            problem = download(task);
        } catch (Cancelled cancelled) {
            problem = "已取消";
        } catch (Throwable t) {
            problem = describe(t);
            log("下载出错：" + problem);
        } finally {
            // 顺序要紧：先把结束时刻定下来（冻结平均速度），再报告，
            // 否则「完成」那一行显示的速度和上一行对不上。
            endedAt = System.currentTimeMillis();
            VoiceModelDownloader.task = null;
            activeStream = null;
            if (problem == null) {
                log("下载完成：" + task.model.label() + " → " + task.dir);
                report(task, false, true, null);
            } else {
                log("下载结束（" + problem + "）：" + task.model.label());
                report(task, false, true, problem);
            }
        }
    }

    /** @return null 表示全部文件都好了，否则是给玩家看的中文原因 */
    private static String download(Task task) throws IOException, Cancelled {
        Files.createDirectories(task.dir);
        for (int i = 0; i < task.files.size(); i++) {
            if (task.cancel) {
                throw new Cancelled();
            }
            VoiceFile file = task.files.get(i);
            task.index = i;
            task.partial = 0L;
            task.source = "";
            Path target = task.dir.resolve(file.name());
            if (isComplete(target, file)) {
                // 已经下好的文件直接跳过：重复点「下载」只会补缺失的那几个，
                // 不会把 350 MB 重新下一遍。
                task.base += file.bytes();
                report(task, true, false, null);
                log("已存在且大小正确，跳过：" + file.name());
                continue;
            }
            List<String> urls = file.sources(task.mode, task.customBase);
            if (urls.isEmpty()) {
                return "「" + file.name() + "」没有可用的下载源（仓库信息为空）";
            }
            // 魔搭只收录了 SenseVoice：选魔搭却给不出魔搭地址时，明确说一句「这个文件回退官方」，
            // 不然日志里少一家源会看起来像配置没生效。
            if (!file.hasModelScope()
                    && (VoiceMirrors.MODELSCOPE.equals(VoiceMirrors.canonical(task.mode))
                    || VoiceMirrors.AUTO.equals(VoiceMirrors.canonical(task.mode)))) {
                log(VoiceMirrors.skipModelScopeReason(file));
            }
            log("候选下载源（按顺序试）：" + String.join(" ， ", urls));
            String last = null;
            boolean ok = false;
            for (String url : urls) {
                if (task.cancel) {
                    throw new Cancelled();
                }
                task.source = url;
                report(task, true, false, null);
                try {
                    String failure = oneSource(task, file, target, url);
                    if (failure == null) {
                        ok = true;
                        break;
                    }
                    last = url + " → " + failure;
                    log("下载源失败：" + last);
                } catch (Cancelled cancelled) {
                    throw cancelled;
                } catch (Throwable t) {
                    // 网络中断/镜像抽风：记住原因换下一个源，.part 留着下次续传
                    last = url + " → " + describe(t);
                    log("下载源出错：" + last);
                }
            }
            if (!ok) {
                return "「" + file.name() + "」下载失败（最后试的是 " + (last == null ? "未知" : last) + "）";
            }
            task.base += file.bytes();
            task.partial = 0L;
            task.source = "";
            report(task, true, false, null);
        }
        return null;
    }

    /**
     * 从一个地址把**单个文件**下完：断点续传、校验、原子改名都在这里。
     *
     * @return null 表示这个源成功了，否则是失败原因（调用方据此换下一个源）
     */
    private static String oneSource(Task task, VoiceFile file, Path target, String url)
            throws IOException, Cancelled {
        Path part = target.resolveSibling(file.name() + PART_SUFFIX);
        // 最多两轮：第一轮用本地 .part 续传，遇到「续传位置不可信」就清掉 .part 再原样重来一次
        // （只有 416 / Content-Range 对不上两种情况下才会用到第二轮）。
        for (int round = 0; round < 2; round++) {
            if (task.cancel) {
                throw new Cancelled();
            }
            long have = Files.isRegularFile(part) ? Files.size(part) : 0L;
            if (have > file.bytes()) {
                log("续传文件比目标还大（" + have + " > " + file.bytes() + " 字节），删掉重来");
                Files.deleteIfExists(part);
                have = 0L;
            }
            task.partial = have;
            report(task, true, false, null);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", USER_AGENT)
                    .GET();
            if (have > 0L) {
                builder.header("Range", "bytes=" + have + "-");
            }
            HttpResponse<InputStream> response;
            try {
                response = client().send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "下载线程被中断";
            } catch (IOException e) {
                return describe(e) + (have > 0L ? "（已保留 " + have + " 字节，下次接着下）" : "");
            }
            int code = response.statusCode();
            if (code == 416) {
                // Range 起点超过了远端文件长度：.part 一定是坏的（或远端换了文件），清掉重来
                closeQuietly(response);
                Files.deleteIfExists(part);
                log("服务端说续传位置无效（HTTP 416），清掉 .part 重来");
                continue;
            }
            if (code / 100 != 2) {
                closeQuietly(response);
                return "HTTP " + code;
            }
            boolean append = code == 206 && have > 0L;
            if (code == 206) {
                long start = contentRangeStart(response.headers().firstValue("Content-Range").orElse(null));
                if (start >= 0L && start != have) {
                    closeQuietly(response);
                    Files.deleteIfExists(part);
                    log("服务端给的续传起点是 " + start + "，本地是 " + have + "，对不上：清掉 .part 重来");
                    continue;
                }
            } else if (have > 0L) {
                // 200 = 服务端没理 Range（有些镜像会这样）。从头覆盖写，绝不往 .part 后面接。
                log("这个源不支持断点续传（返回 200），这一份从头下");
                Files.deleteIfExists(part);
                task.partial = 0L;
            }
            if (append) {
                log("从 " + have + " 字节处继续下载：" + file.name());
            }
            try (InputStream in = response.body(); OutputStream out = openPart(part, append)) {
                activeStream = in;
                byte[] buffer = new byte[BUFFER];
                long since = 0L;
                long last = System.currentTimeMillis();
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (task.cancel) {
                        throw new Cancelled();
                    }
                    if (read == 0) {
                        continue;
                    }
                    out.write(buffer, 0, read);
                    task.partial += read;
                    since += read;
                    long now = System.currentTimeMillis();
                    if (since >= PUBLISH_BYTES || now - last >= PUBLISH_MS) {
                        since = 0L;
                        last = now;
                        report(task, true, false, null);
                    }
                }
            } finally {
                activeStream = null;
            }
            long actual = Files.size(part);
            if (actual != file.bytes()) {
                Files.deleteIfExists(part);
                task.partial = 0L;
                return "大小不对（下到 " + actual + " 字节，应为 " + file.bytes() + " 字节）";
            }
            String expected = file.expectedSha256();
            if (expected != null) {
                // 续传时「边下边算」需要先把 .part 里已有的部分喂进摘要，容易写漏；
                // 本地重读一遍把整份算出来，代价是一次磁盘读，换来的是不可能算错。
                String actualHash = sha256(part);
                if (!expected.equals(actualHash)) {
                    Files.deleteIfExists(part);
                    task.partial = 0L;
                    return "sha256 不匹配（下到 " + actualHash + "，应为 " + expected + "）";
                }
            }
            move(part, target);
            return null;
        }
        return "续传位置一直对不上（清掉 .part 重来一次仍然失败）";
    }

    // ------------------------------------------------------------------ 内部：小工具

    private static HttpClient client() {
        HttpClient local = client;
        if (local != null) {
            return local;
        }
        synchronized (START_LOCK) {
            if (client == null) {
                client = HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        // NORMAL：https→https 的跳转要跟（HuggingFace 的 resolve 会跳到 CDN）
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
            }
            return client;
        }
    }

    private static void report(Task task, boolean running, boolean finished, String problem) {
        current = new Progress(task.model.id(), task.fileName(), task.index, task.files.size(),
                task.base + task.partial, task.total, task.startedAt, running, finished, problem, task.source);
    }

    private static boolean isComplete(Path file, VoiceFile expected) {
        try {
            return Files.isRegularFile(file) && expected.matches(Files.size(file));
        } catch (IOException e) {
            return false;
        }
    }

    private static OutputStream openPart(Path part, boolean append) throws IOException {
        if (append) {
            return Files.newOutputStream(part, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        }
        return Files.newOutputStream(part, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    /** 先试原子改名，不支持再退化 —— 顺序不能反，否则「原子」就成了摆设。 */
    private static void move(Path part, Path target) throws IOException {
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException | UnsupportedOperationException e) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 解析 {@code Content-Range: bytes 10000-75351/75352} 里的起点；看不懂返回 -1。 */
    private static long contentRangeStart(String header) {
        if (header == null) {
            return -1L;
        }
        String text = header.trim();
        int space = text.indexOf(' ');
        if (space >= 0) {
            text = text.substring(space + 1).trim();
        }
        int dash = text.indexOf('-');
        if (dash <= 0) {
            return -1L;
        }
        try {
            return Long.parseLong(text.substring(0, dash).trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("这个 JRE 里没有 SHA-256：" + e, e);
        }
        byte[] buffer = new byte[BUFFER];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            // Character.forDigit(...,16) 给小写字母，sha256 习惯就是小写
            out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (Throwable ignored) {
            // 关掉本来就是「取消」的手段，失败也无所谓
        }
    }

    private static void closeQuietly(HttpResponse<InputStream> response) {
        if (response == null) {
            return;
        }
        try {
            closeQuietly(response.body());
        } catch (Throwable ignored) {
            // 同上
        }
    }

    private static String label(String modelId) {
        VoiceModel model = modelId == null ? null : VoiceModelCatalog.byId(modelId);
        if (model != null) {
            return model.label();
        }
        return modelId == null || modelId.isEmpty() ? "语音模型" : modelId;
    }

    /** 把异常链压成一行中文可读的原因（最多三层，够定位又不会刷屏）。 */
    private static String describe(Throwable t) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        for (Throwable cause = t; cause != null && depth < 3; cause = cause.getCause()) {
            if (depth > 0) {
                out.append(" ← ");
            }
            String message = cause.getMessage();
            out.append(cause.getClass().getSimpleName());
            if (message != null && !message.isEmpty()) {
                out.append(": ").append(message);
            }
            depth++;
        }
        return out.length() == 0 ? "未知错误" : out.toString();
    }

    private static void log(String message) {
        Consumer<String> target = sink;
        try {
            target.accept(message);
        } catch (Throwable ignored) {
            // 日志出口自己出错不能影响下载
        }
    }
}
