package com.ds.warehouse.voice;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * 「模型放在硬盘上哪儿、装好了没有」的唯一判断处。
 *
 * <p>为什么把 base 目录做成外部注入（{@link #init}）而不是自己去问 Fabric：
 * 这个类要能在**没有 Minecraft 的**进程里跑通（自测、离线校验工具），
 * 一旦它 import 了 {@code FabricLoader}，那些场景就只能把游戏整个拉起来。
 * 调用方（模组侧）在启动时把 {@code FabricLoader.getInstance().getConfigDir()} 传进来即可。
 *
 * <p>为什么目录布局要分「自己的目录」和「老目录」两套：
 * <ul>
 *     <li>{@code <config>/warehouse-keeper-voice/models/<id>/}：新下载的一律落这里，
 *         一个模型一个目录，删起来干净（不会碰到别名表、设置文件）。</li>
 *     <li>{@code <config>/warehouse-keeper-voice/<legacyDir>/}：0.2.1 及更早的版本把**内置**模型解压到的地方。
 *         那些版本随包发过 Paraformer 小模型、首次运行时解压，老用户硬盘上已经躺着一份完整的 78 MB；
 *         **必须认出来**，否则一次「取消内置模型」的升级就会让人白重下一遍。</li>
 * </ul>
 *
 * <p>判断「装好没有」只认**大小精确匹配**（清单里每个文件都在、且字节数一模一样）。
 * 不认「目录存在」也不认「文件大于 0」：这两种判法都会把解压/下载中断留下的半成品当成好的，
 * 之后要么加载失败，要么更糟 —— 加载成功但识别结果全错。
 */
public final class VoiceModelStore {

    private static final String ROOT_NAME = "warehouse-keeper-voice";
    private static final String MODELS_NAME = "models";

    private static volatile Path configDir;
    private static volatile Consumer<String> sink = message -> {
    };

    private VoiceModelStore() {
    }

    /**
     * 指定配置根目录，**可重复调用**（重复调用只是把同一个值再写一遍，幂等）。
     *
     * @param configDir 游戏/进程的配置根目录，也就是 {@code <game>/config} 这一层；
     *                  本类内部再拼 {@code warehouse-keeper-voice}。
     *                  模组侧直接传 {@code FabricLoader.getInstance().getConfigDir()}
     */
    public static void init(Path configDir) {
        if (configDir == null) {
            log("忽略空的配置目录（保持原来的设置）");
            return;
        }
        VoiceModelStore.configDir = configDir;
    }

    /** {@code <configDir>/warehouse-keeper-voice}：本附加包在硬盘上的家。 */
    public static Path root() {
        Path base = configDir;
        if (base == null) {
            // 宁可炸得清清楚楚，也不要退回「当前工作目录」：那可能在游戏目录之外
            // 凭空写出几百 MB 模型，玩家永远找不到、也删不掉。
            throw new IllegalStateException("语音模型目录还没初始化：请先调用 VoiceModelStore.init(configDir)");
        }
        return base.resolve(ROOT_NAME);
    }

    /** {@code root()/models}：新下载的模型都放在这里，一个 id 一个目录。 */
    public static Path modelsDir() {
        return root().resolve(MODELS_NAME);
    }

    /** 这个模型的下载目标目录（不管它现在存不存在）。 */
    public static Path dirOf(VoiceModel model) {
        return modelsDir().resolve(model.id());
    }

    /** 老版本目录；模型没有 legacyDir 时返回 null。 */
    public static Path legacyDirOf(VoiceModel model) {
        String legacy = model.legacyDir();
        return legacy == null || legacy.isEmpty() ? null : root().resolve(legacy);
    }

    /**
     * 现在**就能直接用**的目录，没有则 null。
     *
     * <p>先看自己的下载目录，再看老目录（0.2.1 及更早解压内置模型的地方），
     * 两个都按大小精确校验；都不完整就返回 null —— 调用方据此提示「需要下载」。
     */
    public static Path installedDir(VoiceModel model) {
        Path own = dirOf(model);
        if (complete(model, own)) {
            return own;
        }
        Path legacy = legacyDirOf(model);
        if (complete(model, legacy)) {
            return legacy;
        }
        return null;
    }

    /** 硬盘上有没有完整的一份（自己的下载目录，或老版本解压出来的老目录）。 */
    public static boolean installed(VoiceModel model) {
        return installedDir(model) != null;
    }

    /**
     * 硬盘上属于这个模型的字节数（用来显示「已用多少」/下载进度）。
     *
     * <p>优先数「可用的那个目录」；没有可用目录时就数下载目标目录里已有的部分文件 ——
     * 下载下到一半时界面才有东西可显示，否则永远是 0 字节，看着像卡住了。
     */
    public static long installedBytes(VoiceModel model) {
        Path dir = installedDir(model);
        if (dir == null) {
            dir = dirOf(model);
        }
        List<VoiceFile> files = model.files();
        if (files.isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        for (VoiceFile file : files) {
            try {
                Path path = dir.resolve(file.name());
                if (Files.isRegularFile(path)) {
                    sum += Files.size(path);
                }
            } catch (IOException ignored) {
                // 读不到就是 0：这里只是给界面看个大概，不值得打断界面
            }
        }
        return sum;
    }

    /**
     * 删掉这个模型在硬盘上的文件 —— 删**当前正在用的那一份**（新下载目录，或老版本解压出来的老目录）。
     *
     * <p>为什么连老目录也删：语音包不再内置任何模型，老目录里躺着的就是「这个模型的模型文件」，
     * 玩家点删除就是不想再留着它（要再用就重新下载）。
     *
     * @throws UncheckedIOException 删除本身失败（文件被别的进程占着等）
     */
    public static void delete(VoiceModel model) {
        Path own = dirOf(model);
        Path dir = installedDir(model);
        boolean legacy = false;
        if (dir == null) {
            dir = own;
        } else {
            legacy = !dir.equals(own);
        }
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try {
            List<Path> paths = new ArrayList<>();
            try (var walk = Files.walk(dir)) {
                walk.forEach(paths::add);
            }
            // 先深后浅：先删文件再删目录，否则父目录非空删不掉
            paths.sort(Comparator.reverseOrder());
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
            log("已删除模型文件：" + dir + (legacy ? "（老版本解压出来的那一份）" : ""));
        } catch (IOException e) {
            log("删除模型文件失败：" + dir + " → " + e);
            throw new UncheckedIOException("删除语音模型失败：" + dir, e);
        }
    }

    /**
     * 设置页上显示的一行中文状态。
     *
     * <p>三态是刻意分开的：「未下载」和「文件不完整」在界面上要做不同的事
     * （前者给「下载」，后者给「重新下载」），合成一句话玩家就只能自己猜。
     */
    public static String statusText(VoiceModel model) {
        if (installedDir(model) != null) {
            return "已就绪";
        }
        Path own = dirOf(model);
        Path legacy = legacyDirOf(model);
        if (Files.isDirectory(own) || (legacy != null && Files.isDirectory(legacy))) {
            return "文件不完整（可重新下载）";
        }
        return "未下载（需要 " + model.displaySize() + "）";
    }

    /** 日志出口；传 null 表示丢弃（默认就是丢弃，免得这个纯 JDK 类逼调用方配日志）。 */
    public static void setLog(Consumer<String> sink) {
        VoiceModelStore.sink = sink == null ? message -> {
        } : sink;
    }

    // ------------------------------------------------------------------ 内部

    /** 清单里每个文件都在、且字节数精确匹配，才算这个目录可用。 */
    private static boolean complete(VoiceModel model, Path dir) {
        if (dir == null) {
            return false;
        }
        List<VoiceFile> files = model.files();
        if (files.isEmpty()) {
            // 清单空着就无从校验。目录在就当可用 —— 目录表里不该出现这种模型，
            // 但真出现了也不要一律报「缺文件」，那会让人对着一个存在的目录百思不得其解。
            return Files.isDirectory(dir);
        }
        for (VoiceFile file : files) {
            Path path = dir.resolve(file.name());
            try {
                if (!Files.isRegularFile(path)) {
                    return false;
                }
                long size = Files.size(path);
                if (!file.matches(size)) {
                    log("文件大小对不上（" + path + "：" + size + " 字节，应为 " + file.bytes() + " 字节）");
                    return false;
                }
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    private static void log(String message) {
        Consumer<String> target = sink;
        try {
            target.accept(message);
        } catch (Throwable ignored) {
            // 日志出口自己出错不能影响正事
        }
    }
}
