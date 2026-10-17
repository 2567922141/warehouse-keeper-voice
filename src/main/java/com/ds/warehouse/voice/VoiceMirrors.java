package com.ds.warehouse.voice;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 下载源注册表：把「一个模型有哪些下载地址」收在<b>一个</b>地方。
 *
 * <p>0.3.1 之前地址是散着拼的：{@link VoiceFile#sources} 自己写死两个前缀，设置页自己写死
 * 「官方 / hf-mirror」两个选项，两边各改各的。现在设置文件里只有两样东西 ——
 * {@code mirrorMode}（选项 id）与 {@code mirror}（自定义前缀，同时兼容老版本写的镜像根地址），
 * 界面只轮换 id，候选地址一律由这里算。以后魔搭换了域名、或者要加第三家镜像，
 * 只需要改这个文件，界面和下载器都不用动。
 *
 * <p>老配置（只有 {@code mirror=} 没有 {@code mirrorMode=}）由 {@link #modeOf} 按地址反推，
 * 不会因为升级而丢掉玩家已经选好的源。
 *
 * <p>本类不碰 Minecraft / Fabric，也不做网络请求：它只描述「有哪些地址、按什么顺序试」。
 */
public final class VoiceMirrors {

    /** HuggingFace 官方原始源。默认值。 */
    public static final String OFFICIAL = "official";
    /** hf-mirror 转发站。 */
    public static final String HFM = "hfm";
    /** 魔搭 ModelScope。 */
    public static final String MODELSCOPE = "modelscope";
    /** 自动：按候选顺序依次试（有魔搭镜像就先魔搭）。 */
    public static final String AUTO = "auto";
    /** 自定义前缀。 */
    public static final String CUSTOM = "custom";

    static final String OFFICIAL_BASE = "https://huggingface.co";
    static final String HFM_BASE = "https://hf-mirror.com";
    /** 魔搭的文件地址形态：{@code /models/<owner>/<name>/resolve/master/<文件>}。 */
    static final String MODELSCOPE_BASE = "https://www.modelscope.cn/models";
    /** 魔搭仓库页根，用于把老配置里的这个地址认出来。 */
    static final String MODELSCOPE_SITE = "https://www.modelscope.cn";

    /** 出包默认：官方原始源（见 {@link VoiceModelCatalog#defaultMirror()} 里记的拍板）。 */
    public static final String DEFAULT_MODE = OFFICIAL;

    /**
     * 界面上的一个下载源选项。
     *
     * @param id    设置文件里写的东西，{@link #canonical} 认它
     * @param label 按钮上显示的中文名
     */
    public record Option(String id, String label) {
    }

    /** 顺序即界面轮换顺序：第一个是默认值。每一条后面的注释是「为什么提供它 / 它有什么坑」。 */
    private static final List<Option> OPTIONS = List.of(
            // 原始下载源，需要能连上 huggingface.co。出包默认值（见 VoiceModelCatalog.defaultMirror）。
            new Option(OFFICIAL, "HuggingFace 官方"),
            // 国内可达的转发镜像；实测会 308 跳回官方，不是独立的一份源。
            new Option(HFM, "hf-mirror"),
            // 大陆直连：四个模型都已实测收录（见 VoiceModelCatalog 的 MS_REPO_* 映射表）。
            // 新增没核对过的模型才会回退官方，理由由 fallbackReason 说明。
            new Option(MODELSCOPE, "魔搭 ModelScope"),
            // 按顺序试：魔搭（有镜像时）→ hf-mirror → 官方。
            new Option(AUTO, "自动"),
            // 手填网址前缀，按「前缀/repo/resolve/main/文件名」拼；前缀要写进 mirror= 这个键。
            new Option(CUSTOM, "自定义前缀"));

    private VoiceMirrors() {
    }

    /** 全部选项，顺序即界面轮换顺序。 */
    public static List<Option> options() {
        return OPTIONS;
    }

    /** 选项 id 列表，与 {@link #options()} 同序。 */
    public static List<String> ids() {
        List<String> out = new ArrayList<>(OPTIONS.size());
        for (Option option : OPTIONS) {
            out.add(option.id());
        }
        return List.copyOf(out);
    }

    /**
     * 把设置文件里读到的字符串收敛成选项 id（去空白 + 忽略大小写）。
     *
     * @return 认得出返回 id；认不出返回 null，由调用方决定怎么兜底（**不要**在这里静默成默认值，
     *         否则设置文件里的错别字会变成「下载源悄悄换了一个」这种查不出来的怪事）
     */
    public static String canonical(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        for (Option option : OPTIONS) {
            if (option.id().equals(value)) {
                return option.id();
            }
        }
        return null;
    }

    /** 选项的中文名；认不出就把原样返回（好让设置页显示「设置文件里到底写了什么」）。 */
    public static String label(String mode) {
        Option option = find(mode);
        if (option != null) {
            return option.label();
        }
        return mode == null || mode.isBlank() ? label(DEFAULT_MODE) : mode.trim();
    }

    /** 界面上要显示的当前下载源：自定义模式带上前缀，好让玩家一眼看出自己填的对不对。 */
    public static String describe(String mode, String customBase) {
        String id = canonical(mode);
        String label = label(mode);
        if (CUSTOM.equals(id)) {
            String base = normalizeBase(customBase);
            return base.isEmpty() ? label + "（空）" : label + "（" + base + "）";
        }
        return label;
    }

    /** 默认下载源根地址（官方）。 */
    public static String defaultBase() {
        return OFFICIAL_BASE;
    }

    /**
     * 老配置（只有 {@code mirror=}，没有 {@code mirrorMode=}）按地址反推模式。
     *
     * <p>认出官方 / hf-mirror / 魔搭这三个已知根地址就能把老玩家选好的源原样接过来；
     * 其他地址按 {@link #CUSTOM} 处理 —— 老代码里非空 {@code mirror} 本来就是「当自定义前缀拼」，
     * 行为不变。空值返回 null，表示「设置里没写过源，用默认的」。
     */
    public static String modeOf(String mirrorUrl) {
        String value = normalizeBase(mirrorUrl);
        if (value.isEmpty()) {
            return null;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.equals(OFFICIAL_BASE)) {
            return OFFICIAL;
        }
        if (lower.equals(HFM_BASE)) {
            return HFM;
        }
        if (lower.equals(MODELSCOPE_SITE) || lower.equals(MODELSCOPE_BASE)) {
            return MODELSCOPE;
        }
        return CUSTOM;
    }

    /**
     * 保存设置时 {@code mirror=} 该写什么。
     *
     * <p>为什么不是「直接把模式写进去」：{@code mirror} 这个键老版本也认，而且老版本只认<b>地址</b>。
     * 万一玩家回退到 0.3.0，我们希望它读到的是一个它能用的地址，而不是把 {@code modelscope}
     * 当成前缀拼出 {@code modelscope/csukuangfj/...} 这种诡异地址。所以这里挑一个「对老版本也说得通」
     * 的地址写进去，新模式本身由 {@code mirrorMode=} 承载。
     */
    public static String persistedBase(String mode, String customBase) {
        String id = canonical(mode);
        if (id == null) {
            id = DEFAULT_MODE;
        }
        return switch (id) {
            case CUSTOM -> normalizeBase(customBase);
            case HFM -> HFM_BASE;
            // official / modelscope / auto：老版本读到官方地址就是它的默认行为，最安全。
            default -> OFFICIAL_BASE;
        };
    }

    /**
     * 一个文件在指定模式下要依次尝试的地址（去重、有序）。
     *
     * @param mode       下载源 id，认不出按 {@link #DEFAULT_MODE} 处理
     * @param customBase 自定义前缀，仅 {@link #CUSTOM} 模式用
     * @return 候选地址表；{@code repo} 为空时返回空表（表示这个文件没地方下，而不是地址写错）
     */
    static List<String> sources(VoiceFile file, String mode, String customBase) {
        String repository = normalizeRepo(file.repo());
        if (repository.isEmpty()) {
            return List.of();
        }
        String remote = file.remoteName() == null || file.remoteName().isBlank()
                ? file.name() : file.remoteName().trim();
        String path = repository + "/resolve/main/" + remote;
        String id = canonical(mode);
        if (id == null) {
            id = DEFAULT_MODE;
        }
        LinkedHashSet<String> out = new LinkedHashSet<>(4);
        switch (id) {
            case OFFICIAL -> {
                out.add(OFFICIAL_BASE + "/" + path);
                out.add(HFM_BASE + "/" + path);
            }
            case HFM -> {
                out.add(HFM_BASE + "/" + path);
                out.add(OFFICIAL_BASE + "/" + path);
            }
            case MODELSCOPE -> {
                String mirror = modelScopeUrl(file);
                if (mirror != null) {
                    out.add(mirror);
                }
                // 没有魔搭镜像的文件（或魔搭临时挂了）就退回官方，绝不拼一个不存在的魔搭地址。
                out.add(OFFICIAL_BASE + "/" + path);
            }
            case AUTO -> {
                String mirror = modelScopeUrl(file);
                if (mirror != null) {
                    out.add(mirror);
                }
                out.add(HFM_BASE + "/" + path);
                out.add(OFFICIAL_BASE + "/" + path);
            }
            case CUSTOM -> {
                String base = normalizeBase(customBase);
                if (!base.isEmpty()) {
                    out.add(base + "/" + path);
                }
                // 前缀是玩家手填的，可能拼不对；后面两个默认源兜底，不然一个错字就永远下不动。
                out.add(HFM_BASE + "/" + path);
                out.add(OFFICIAL_BASE + "/" + path);
            }
            default -> {
                out.add(OFFICIAL_BASE + "/" + path);
                out.add(HFM_BASE + "/" + path);
            }
        }
        return List.copyOf(out);
    }

    /**
     * 这个文件在魔搭上的地址；没有对应仓库（或地址不全）返回 null。
     *
     * <p>魔搭用 {@code resolve/master/}（默认分支叫 master 不叫 main），
     * 而且魔搭上的文件路径与 HuggingFace 不一定同名，所以 {@code msRepo} / {@code msRemote} 单独存。
     */
    static String modelScopeUrl(VoiceFile file) {
        String repo = normalizeRepo(file.msRepo());
        if (repo.isEmpty()) {
            return null;
        }
        String remote = file.msRemote() == null || file.msRemote().isBlank()
                ? file.name() : file.msRemote().trim();
        return MODELSCOPE_BASE + "/" + repo + "/resolve/master/" + remote;
    }

    /** 这个文件在魔搭有没有镜像（modelScope 模式下用它决定「能不能优先走魔搭」）。 */
    static boolean hasModelScope(VoiceFile file) {
        return modelScopeUrl(file) != null;
    }

    /**
     * 整个模型在魔搭是否齐全（所有文件都有魔搭地址）。
     *
     * <p>只给界面提示用：只要有一个文件没镜像，那个文件就必须回退官方，
     * 所以「不全」等于「这个模型要回退」。
     */
    public static boolean modelScopeCovered(VoiceModel model) {
        if (model == null || model.files().isEmpty()) {
            return false;
        }
        for (VoiceFile file : model.files()) {
            if (!hasModelScope(file)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 给界面/日志用的一句「为什么要回退」，没有回退时返回 null。
     *
     * <p>四个模型都在魔搭实测收录过；只有将来新增了没核对过的模型，本模式下才必须回退官方。
     * 与其让玩家以为「选了魔搭就会从魔搭下」，不如直接说清楚。话刻意写得短：它要挤在设置页那一行状态里。
     *
     * @param model 要检查的模型；null（例如主模型 id 认不出来）时返回 null —— 宁可不说，
     *              也不要报一句「这个模型要回退」而玩家其实用的是 SenseVoice
     */
    public static String fallbackReason(String mode, VoiceModel model) {
        String id = canonical(mode);
        if (!MODELSCOPE.equals(id) && !AUTO.equals(id)) {
            return null;
        }
        if (model == null || modelScopeCovered(model)) {
            return null;
        }
        return "魔搭没有这个模型的镜像，回退官方";
    }

    /** 单个文件级别的一句话提示，用于下载日志。 */
    static String skipModelScopeReason(VoiceFile file) {
        return "魔搭没有「" + file.name() + "」的镜像，这个文件用其他源下载";
    }

    /** 进度条上显示「当前源」用的短名。 */
    static String sourceLabel(String url) {
        String value = normalizeBase(url);
        if (value.isEmpty()) {
            return "?";
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith(HFM_BASE)) {
            return "hf-mirror";
        }
        if (lower.startsWith(MODELSCOPE_SITE)) {
            return "魔搭";
        }
        if (lower.startsWith(OFFICIAL_BASE)) {
            return "官方";
        }
        String host = lower;
        int scheme = host.indexOf("://");
        if (scheme >= 0) {
            host = host.substring(scheme + 3);
        }
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        return host.isEmpty() ? "自定义" : host;
    }

    private static Option find(String mode) {
        String id = canonical(mode);
        if (id == null) {
            return null;
        }
        for (Option option : OPTIONS) {
            if (option.id().equals(id)) {
                return option;
            }
        }
        return null;
    }

    private static String normalizeRepo(String repo) {
        if (repo == null) {
            return "";
        }
        String value = repo.trim();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String normalizeBase(String url) {
        if (url == null) {
            return "";
        }
        String value = url.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
