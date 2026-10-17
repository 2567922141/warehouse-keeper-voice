package com.ds.warehouse.voice;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 可选语音模型的**唯一权威清单**：界面上显示的、设置里能填的、能下载的，都来自这里。
 *
 * <p>为什么要有这么一个目录表，而不是让界面各处自己拼：
 * <ul>
 *     <li>界面、设置解析、下载器、磁盘检查需要的信息是同一份。分开写，早晚会出现
 *         「设置页写着 228 MB、下载器却按 239233841 字节判断」这种对不上的问题。</li>
 *     <li>{@link #all()} 的顺序就是界面顺序，改顺序只要动这里一个地方。</li>
 *     <li>老设置文件里的旧写法（{@code paraformer} / {@code zipformer}）由
 *         {@link #canonicalId} 兜住，老用户不用重新选一遍。</li>
 * </ul>
 *
 * <p>体积与 sha256 都是**实测/官方仓库给出**的确切值（不是估的）：大小用来判断文件是否完整，
 * sha256 用来判断内容有没有错。宁可写死一个确切数字，也不要「大约 78 MB」这类模糊值 ——
 * 模糊值的唯一后果就是坏文件被当成好文件。
 *
 * <p>本类不碰 Minecraft / Fabric：它只描述「有什么」。
 */
public final class VoiceModelCatalog {

    private static final String REPO_PARAFORMER_SMALL = "csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09";
    private static final String REPO_PARAFORMER_ZH = "csukuangfj/sherpa-onnx-paraformer-zh-2024-03-09";
    private static final String REPO_SENSE_VOICE = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17";
    private static final String REPO_ZIPFORMER_CTC = "csukuangfj/sherpa-onnx-zipformer-ctc-zh-int8-2025-07-03";

    /**
     * SenseVoice 在魔搭上的仓库（大陆直连的那一份）。
     *
     * <p>魔搭的仓库名与 HuggingFace <b>不是</b>同一个：HuggingFace 上是
     * {@code csukuangfj/sherpa-onnx-sense-voice-...}，魔搭上是这个。所以它是独立常量，
     * 而不是从 {@link #REPO_SENSE_VOICE} 拼出来的 —— 拼出来的地址只会在下载时变成 404。
     */
    private static final String MS_REPO_SENSE_VOICE = "poloniumrock/SenseVoiceSmallOnnx";

    /**
     * 另外三个模型在魔搭上的仓库（2026-10-16 实测，逐个核对过文件名、字节数与 sha256）。
     *
     * <p>实测方法：{@code GET .../api/v1/models/<仓库>/repo/files?Revision=master&Root=} 列文件，
     * 再用 {@code /models/<仓库>/resolve/master/<文件名>} 真下下来，把本地 sha256 与本文件里写死的值比对。
     * 结果：四个模型的 {@code model.int8.onnx} 与 {@code tokens.txt} 全部逐字节一致，
     * zipformer 的 {@code bbpe.model} 也一致 —— 换源不会让 {@link VoiceFile#matches} /
     * {@link VoiceFile#expectedSha256} 这套校验失效。
     *
     * <p>{@link #MS_REPO_ZIPFORMER_CTC} 与 HuggingFace 上的仓库同名是巧合（csukuangfj 两边都发），
     * 也是实测过的；另外两个在魔搭上是 {@code pengzhendong} 命名空间，与 HuggingFace 上的
     * {@code csukuangfj/...-2024-03-09} <b>不是同一个仓库名</b>，所以没有任何一个能从 HF 仓库名拼出来。
     */
    private static final String MS_REPO_PARAFORMER_SMALL = "pengzhendong/sherpa-onnx-paraformer-zh-small";
    private static final String MS_REPO_PARAFORMER_ZH = "pengzhendong/sherpa-onnx-paraformer-zh";
    private static final String MS_REPO_ZIPFORMER_CTC =
            "csukuangfj/sherpa-onnx-zipformer-ctc-zh-int8-2025-07-03";

    /** 默认模型：中文场景下最省事的一个（离线、体积适中、中英日韩粤都能认）。 */
    private static final String DEFAULT_ID = "sense-voice";
    /**
     * 兜底模型：设置里写的那个模型还没下载时，引擎优先退到它。
     *
     * <p>注意它自己也**不在包里**（语音包不再内置任何模型），所以「一个模型都没下载」时退无可退 ——
     * 那种情况由 {@code SherpaEngine.noteFallback()} 直接提示「先去设置页下载一个」。
     */
    private static final String FALLBACK_ID = "paraformer-small";

    /**
     * 老设置文件里的旧写法 → 现在的 id。
     *
     * <p>{@code paraformer} 仍然指向**小的那个**：老用户的 {@code settings.txt} 里写的就是它，
     * 那时候世界上还没有完整版。新加的 {@code paraformer-zh} 有自己的 id，不抢这个别名 ——
     * 否则老用户一开游戏，选中的模型会突然从 78 MB 的小模型变成 217 MB 的完整版。
     */
    private static final Map<String, String> ALIASES = Map.of(
            "paraformer", "paraformer-small",
            "zipformer", "zipformer-ctc");

    private static VoiceFile file(String name, long bytes, String sha256, String repo) {
        // remoteName 与落盘名相同：这些仓库里就是要这么下，重命名只是留给以后换仓库的余地。
        // 魔搭上没核对过（或将来新增而还没实测）的文件用这个，那个文件就只走 HF 那一套源。
        return new VoiceFile(name, bytes, sha256, repo, name);
    }

    /**
     * 有魔搭镜像的文件：远端地址与落盘名相同，但仓库换成魔搭的（见上面那四个 {@code MS_REPO_*}）。
     *
     * <p>没核对过魔搭对应文件名的条目<b>不要</b>用它 —— 猜错文件名不会报「没镜像」，
     * 而是会在魔搭上拿到一个 404 页面，被当成坏文件。目前四个模型的每个文件都实测过，所以都用了它。
     */
    private static VoiceFile fileWithModelScope(String name, long bytes, String sha256, String repo,
                                                String msRepo) {
        return new VoiceFile(name, bytes, sha256, repo, name, msRepo, name);
    }

    private static final VoiceModel PARAFORMER_SMALL = new VoiceModel(
            "paraformer-small",
            "Paraformer小",
            "78 MB，最省 CPU 与内存；速度快，认普通话最稳",
            VoiceKind.PARAFORMER,
            "sherpa-paraformer-zh-small",
            List.of(
                    fileWithModelScope("model.int8.onnx", 81828675L,
                            "3ef6c19369b912f7caf3cef8e545c5ccd1a33d9d7ec792a46668dc41c4b229ec",
                            REPO_PARAFORMER_SMALL, MS_REPO_PARAFORMER_SMALL),
                    fileWithModelScope("tokens.txt", 75352L,
                            "4b2d964e18b9cf139b473003b6698fb2ed9a2a5ec55b93daa677b28f578897aa",
                            REPO_PARAFORMER_SMALL, MS_REPO_PARAFORMER_SMALL)));

    private static final VoiceModel SENSE_VOICE = new VoiceModel(
            "sense-voice",
            "SenseVoice（推荐）",
            "一个模型认中英日韩粤，还带情感/事件标签；比小模型准，需要联网下载",
            VoiceKind.SENSE_VOICE,
            null,
            List.of(
                    fileWithModelScope("model.int8.onnx", 239233841L,
                            "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
                            REPO_SENSE_VOICE, MS_REPO_SENSE_VOICE),
                    fileWithModelScope("tokens.txt", 315894L,
                            "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
                            REPO_SENSE_VOICE, MS_REPO_SENSE_VOICE)));

    private static final VoiceModel ZIPFORMER_CTC = new VoiceModel(
            "zipformer-ctc",
            "Zipformer大",
            "350 MB 的 int8 大模型，慢一点但更稳；适合配置好、追求准确率的机器",
            VoiceKind.ZIPFORMER_CTC,
            "sherpa-zipformer-ctc-zh-int8",
            List.of(
                    fileWithModelScope("model.int8.onnx", 367074356L,
                            "e291b9c468b651e2697caa09bc684326c3addc6a019e78eb537cfd1a8248ca07",
                            REPO_ZIPFORMER_CTC, MS_REPO_ZIPFORMER_CTC),
                    fileWithModelScope("tokens.txt", 13366L,
                            "6fed8c6c248516f38e7faa19404b57413e8ce259f1cbc1fa4aebc86eac32fdfd",
                            REPO_ZIPFORMER_CTC, MS_REPO_ZIPFORMER_CTC),
                    fileWithModelScope("bbpe.model", 255180L,
                            "503204e0690eff065e30d0e01898c9ab06d0e6dc376a741eb6846198f95b2f82",
                            REPO_ZIPFORMER_CTC, MS_REPO_ZIPFORMER_CTC)));

    private static final VoiceModel PARAFORMER_ZH = new VoiceModel(
            "paraformer-zh",
            "Paraformer完整版",
            "官方标注支持普通话与多种中文方言（粤语、四川话、上海话等），217 MB，认得更准也更慢",
            VoiceKind.PARAFORMER,
            "sherpa-paraformer-zh",
            List.of(
                    fileWithModelScope("model.int8.onnx", 227330205L,
                            "90bc03034ae1bef9575f8cc798cd1519c8be8aa9e8b458a033e32017ff4d584c",
                            REPO_PARAFORMER_ZH, MS_REPO_PARAFORMER_ZH),
                    fileWithModelScope("tokens.txt", 75354L,
                            "6c0e3b35cece259829e6cb5b8d90d13db88f61ea3a2953d11898e4b2bfd7a2e2",
                            REPO_PARAFORMER_ZH, MS_REPO_PARAFORMER_ZH)));

    /** 顺序即界面顺序：推荐的在最前，后面按体积从小到大。 */
    private static final List<VoiceModel> MODELS =
            List.of(SENSE_VOICE, PARAFORMER_SMALL, ZIPFORMER_CTC, PARAFORMER_ZH);

    private VoiceModelCatalog() {
    }

    /** 全部可选模型；顺序即界面显示顺序。 */
    public static List<VoiceModel> all() {
        return MODELS;
    }

    /** 按 id 找模型；未知 id 返回 null（调用方自己决定退回默认还是报错）。 */
    public static VoiceModel byId(String id) {
        if (id == null) {
            return null;
        }
        String text = id.trim();
        for (VoiceModel model : MODELS) {
            if (model.id().equals(text)) {
                return model;
            }
        }
        return null;
    }

    /**
     * 把设置文件里写的字符串归一到 id：去首尾空白、忽略大小写、认旧写法。
     *
     * @return 认得出就返回 id，认不出返回 null（**不要**在这里偷偷退回默认模型：
     *         那会让设置文件里的错别字变成「玩家每次都跑另一个模型」这种查不出来的怪事）
     */
    public static String canonicalId(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return null;
        }
        String alias = ALIASES.get(text);
        if (alias != null) {
            return alias;
        }
        return byId(text) == null ? null : text;
    }

    /** 默认模型 id（第一次运行、设置文件里没写、或玩家选的就是它）。 */
    public static String defaultId() {
        return DEFAULT_ID;
    }

    /** 兜底模型 id：随包分发、永远可用。 */
    public static String fallbackId() {
        return FALLBACK_ID;
    }

    /**
     * 默认下载源：**官方原始源**（{@code huggingface.co}）。
     *
     * <p>拍板（2026-10-16，用户 m35362）：「默认原始下载源，可选择使用镜像下载。」
     * 所以出包默认走官方；镜像站是**可选**入口（设置页点一下换），不默认使用。
     * 老设置文件里已经写过 {@code mirror=} 的照旧生效（{@code loadSettings} 按地址反推出模式）。
     *
     * <p>V-05 实测（2026-10-16，JDK {@code HttpClient} + {@code Range} 头 + 跟随重定向）：
     * {@code hf-mirror.com} 对模型文件返回 <b>308</b> 直接跳到 {@code huggingface.co}，后者再 302 跳到
     * {@code us.aws.cdn.hf.co}。所以这个「镜像」在本机并不是独立的一份源，连不上 huggingface 时它同样不可用；
     * 好处是 Range 续传在两处都成立（两次请求都拿到 206 + 正确的 {@code Content-Range}，越界返回 416），
     * 所以 {@link VoiceModelDownloader} 的「断点续传」是**真的**在用，不是摆设。
     */
    public static String defaultMirror() {
        return VoiceMirrors.defaultBase();
    }

    /**
     * 可选的下载源 id，顺序即界面轮换顺序；设置页直接拿它当选项表。
     *
     * <p>0.3.1 起这里返回的是 {@link VoiceMirrors} 里的<b>模式 id</b>（{@code official} /
     * {@code hfm} / {@code modelscope} / {@code auto} / {@code custom}），不再是写死的地址 ——
     * 加一家镜像站只要改 {@link VoiceMirrors}，界面与下载器都不用动。
     * 第一个仍是官方原始源（默认值，见 {@link #defaultMirror()}）。
     *
     * <p>魔搭（ModelScope）是大陆直连的那一份：四个模型都在魔搭上实测收录过，每个文件的字节数与
     * sha256 都与上面对 HF 仓库写死的值一致（见那四个 {@code MS_REPO_*} 常量）。将来若新增
     * 没核对过的模型，{@link VoiceMirrors#fallbackReason} 会说明它为什么回退官方。
     * 背景与实测见计划文件 {@code docs/\计划\仓库管理模组-语音模型下载镜像-计划.md}。
     */
    public static List<String> mirrorChoices() {
        return VoiceMirrors.ids();
    }
}
