package com.ds.warehouse.voice;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 第 ② 步：**可选的** AI 归一化器（计划书 §5）。本地匹配听不懂时才把这句口语交给一个
 * OpenAI 兼容的小模型改写成标准口令，改写结果**再交给本地 {@link VoiceText} 复判**，本地认了才下单。
 *
 * <p>三条铁律：
 * <ol>
 *     <li><b>默认关闭</b>：{@code rewrite=off} 时一个字节都不出网（{@link #rewrite} 直接返回
 *         {@link Status#DISABLED}，连解析都不做）；</li>
 *     <li><b>AI 不直接下单</b>：本类只回「改写后的句子」，下单永远由本地匹配器决定；</li>
 *     <li><b>只发文字</b>：出网内容仅限于 ASR 文字 + 本地前 8 名候选（id / 中文名 / 库存），
 *         没有音频、玩家名、坐标、整份仓库清单。</li>
 * </ol>
 *
 * <p><b>刻意不 import 任何 Minecraft 类</b>：这个类要能脱离游戏用 {@code javac} 直接编，
 * 离线单测（{@code experiments/voice-rewrite}）才跑得起来。MC 侧的粘合（开关落盘、候选快照、
 * 线程切换）放在 {@link VoiceSession} / {@link VoicePhrase} 里。
 *
 * <p>失败矩阵（§5.5）：超时 / 连不上 = 记 debug 日志并退回本地结果；HTTP 401 = 提示一次并把开关
 * 置回 off；HTTP 429 = 退避 60 秒；{@code item=none} 或不在闭集内 = 当没答；{@code amount} 非法
 * = 当没答（**绝不**回退成 1 个）。
 */
final class VoiceRewrite {

    private VoiceRewrite() {
    }

    // ------------------------------------------------------------------ 常量

    /** 一次识别最多问一次。默认 3000 毫秒（面板可选 1/2/3/5 秒，范围 500–10000）。 */
    static final int DEFAULT_TIMEOUT_MS = 3000;
    static final int MIN_TIMEOUT_MS = 500;
    static final int MAX_TIMEOUT_MS = 10000;
    /** 候选最多给 8 条：一句话要好几件东西时，两件目标的候选都得在里面（§5.2）。 */
    static final int MAX_CANDIDATES = 8;
    /** 一次回答里最多认几件东西（和 {@link VoiceText#MAX_CLAUSES} 对齐：再多就是玩家说的太复杂）。 */
    static final int MAX_ORDERS = 4;
    /** 回显最长 60 字，超了截断（§5.1）。 */
    static final int MAX_REWRITE = 60;
    /** amount 里 N 的范围（1–4096）。 */
    static final int MAX_AMOUNT = 4096;
    /** 单次下单上限，和面板/命令一致；「全部」按它夹紧。 */
    static final int ORDER_CAP = 4096;

    // ------------------------------------------------------------------ 改写方式（AI 开关打开后怎么用）

    /** 两个一起：本地先认，本地没把握才问 AI（默认，也最省额度）。 */
    static final String MODE_BOTH = "both";
    /** 完全使用本地规则：一次都不问 AI（保留服务/Key 配置，随时能切回来）。 */
    static final String MODE_LOCAL = "local";
    /** 完全调用 AI 改写：每句都先问 AI，AI 答不上或答得不对才退回本地。 */
    static final String MODE_AI = "ai";
    /** 下拉里的顺序就是设置页里点一下换下一个的顺序。 */
    private static final List<String> MODES = List.of(MODE_BOTH, MODE_LOCAL, MODE_AI);

    /** 设置页下拉用的全部取值（顺序固定）。 */
    static List<String> modeIds() {
        return MODES;
    }

    /** 把 settings.txt / 下拉里写的值认成三个 id 之一；认不出（含空串）返回 {@code null}。 */
    static String canonicalMode(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "both", "hybrid", "两个一起", "本地优先", "一起" -> MODE_BOTH;
            case "local", "只用本地", "完全本地", "本地规则", "本地" -> MODE_LOCAL;
            case "ai", "always", "只用ai", "完全ai", "全ai", "ai改写" -> MODE_AI;
            default -> null;
        };
    }

    /** 给玩家看的名字；认不出的值按默认（两个一起）显示。 */
    static String modeLabel(String id) {
        String canonical = canonicalMode(id);
        if (canonical == null) {
            canonical = MODE_BOTH;
        }
        return switch (canonical) {
            case MODE_LOCAL -> "完全使用本地规则";
            case MODE_AI -> "完全调用 AI 改写";
            default -> "两个一起（本地优先）";
        };
    }

    /** 令牌桶：低于各家免费层（智谱约 30 次/分），超限就静默跳过第 3 层。 */
    static final int PER_MINUTE = 20;
    static final int PER_DAY = 200;
    /** 429 之后 60 秒内不再调用。 */
    static final long BACKOFF_MS = 60_000L;
    /** 进程内 LRU 缓存条数。 */
    static final int CACHE_SIZE = 500;

    /** 契约里的三个字段，顺序不敏感，但必须恰好三个、各出现一次。 */
    private static final String K_ITEM = "item";
    private static final String K_AMOUNT = "amount";
    private static final String K_REWRITE = "rewrite";
    /**
     * amount 白名单：{@code 全部} / {@code N个} / {@code N组} / {@code 半组} / {@code X分之Y组}，
     * N 为 1–4096 的整数，分数按一组多少个本地折算（{@code 四分之一组} = 16 个）。
     *
     * <p>N 同时接受阿拉伯数字与常用中文数字：计划书 §5.1 的格式串写的是 {@code N个/N组}，而人（和小模型）
     * 说「一组」远比「1组」自然 —— 实测最常见的回答就是「一组 / 两组 / 半组」。中文数字只是 N 的另一种写法，
     * 词表本身没有放宽：仍然是这些量词，仍然在本地换算。
     */
    private static final Pattern AMOUNT = Pattern.compile(
            "^(全部|([0-9一二三四五六七八九十百千两零]{1,8})个|([0-9一二三四五六七八九十百千两零]{1,8})组|半组"
                    + "|([0-9一二三四五六七八九十]{1,3})分之([0-9一二三四五六七八九十]{1,3})组)$");

    // ------------------------------------------------------------------ 预设服务商（§7）

    /** 一个预设：名字 + 接口地址 + 默认模型。{@code keyRequired=false} 表示本地服务（Ollama）不用填 key。 */
    record Preset(String id, String label, String url, String model, boolean local) {
    }

    /** 预设清单，第一个是默认（智谱 BigModel）。顺序就是设置页里点一下就换的顺序。 */
    private static final List<Preset> PRESETS = List.of(
            new Preset("zhipu", "智谱 BigModel（默认）", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash-250414", false),
            new Preset("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1", "Qwen/Qwen3-8B", false),
            new Preset("ollama", "本地 Ollama", "http://127.0.0.1:11434/v1", "qwen3:0.6b", true),
            new Preset("custom", "自定义", "", "", false));

    /**
     * 各家常用的模型名：设置页「AI 模型」下拉用。
     *
     * <p>智谱默认给 {@code glm-4-flash-250414}：实测它比 glm-4.7-flash 快得多，而这一层只需要
     * 把一句口语对到候选里的某一件东西，用不上更强的模型。玩家也可以在「自定义模型」里手填别的。
     */
    private static final java.util.Map<String, List<String>> MODELS = java.util.Map.of(
            "zhipu", List.of("glm-4-flash-250414", "glm-4-flash", "glm-4.7-flash"),
            "siliconflow", List.of("Qwen/Qwen3-8B", "Qwen/Qwen3-4B", "Qwen/Qwen2.5-7B-Instruct"),
            "ollama", List.of("qwen3:0.6b", "qwen3:4b"));

    /** 某个服务商常用的模型名（认不出的服务商给空表，设置页就只显示手填那一行）。 */
    static List<String> models(String presetId) {
        for (Preset preset : PRESETS) {
            if (preset.id().equalsIgnoreCase(presetId)) {
                return MODELS.getOrDefault(preset.id(), List.of());
            }
        }
        return List.of();
    }

    static List<Preset> presets() {
        return PRESETS;
    }

    /** 默认预设 id（智谱）。 */
    static String defaultPresetId() {
        return PRESETS.get(0).id();
    }

    static Preset preset(String id) {
        if (id != null) {
            for (Preset preset : PRESETS) {
                if (preset.id().equalsIgnoreCase(id)) {
                    return preset;
                }
            }
        }
        return PRESETS.get(0);
    }

    /**
     * 认地址 → 预设。玩家手改 {@code rewrite.url=} 之后，设置页得显示对的那一家；
     * 认不出来就是「自定义」。
     */
    static Preset presetOfUrl(String url) {
        String clean = normalizeUrl(url);
        if (clean.isEmpty()) {
            return preset(defaultPresetId());
        }
        for (Preset preset : PRESETS) {
            if (!preset.url().isEmpty() && normalizeUrl(preset.url()).equalsIgnoreCase(clean)) {
                return preset;
            }
        }
        return preset("custom");
    }

    /** 地址去掉末尾斜杠。 */
    static String normalizeUrl(String url) {
        String clean = url == null ? "" : url.trim();
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }

    /** settings.txt 注释里那句「可选值」，按清单自动生成（和 {@code modelIds()} 一个路子）。 */
    static String presetIds() {
        StringBuilder builder = new StringBuilder();
        for (Preset preset : PRESETS) {
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append(preset.id());
        }
        return builder.toString();
    }

    /** 认 id，也认中文标签和地址（手改文件时写「硅基流动」或那家的地址都行）；认不出返回 null。 */
    static String canonicalPresetId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        for (Preset preset : PRESETS) {
            if (preset.id().equalsIgnoreCase(text) || preset.label().equalsIgnoreCase(text)) {
                return preset.id();
            }
        }
        Preset byUrl = presetOfUrl(text);
        // presetOfUrl 认不出来会给「自定义」，那种情况不算认得出 —— 别把任何手写地址都当成某一家的别名
        return byUrl.id().equals("custom") ? null : byUrl.id();
    }

    /** 按 id/标签/地址找预设；找不到返回 null（{@link #preset} 找不到会给默认值，两者用途不同）。 */
    static Preset findPreset(String raw) {
        String id = canonicalPresetId(raw);
        return id == null ? null : preset(id);
    }

    /**
     * 当前真正会用的地址：填了就用填的（去掉末尾斜杠），没填就用预设的默认地址。
     *
     * <p>「自定义」预设没有默认地址 ⇒ 返回空串，调用方把这个当成「还没配好」。
     */
    static String url(String presetId, String explicit) {
        String clean = normalizeUrl(explicit);
        return clean.isEmpty() ? preset(presetId).url() : clean;
    }

    /** 模型名 + 它是不是跟当前服务商搭得上（只用来提前提示，不参与下单判断）。 */
    record ModelPick(String model, boolean fromPreset, boolean valid) {
    }

    /**
     * 当前真正会用的模型名：填了就用填的，没填用预设默认的。
     *
     * <p>{@code valid} 只在「玩家手填了一个跟服务商明显不搭的模型名」时为 false ——
     * 例如在智谱填了 {@code qwen3:0.6b}。这不是安全判断，决策者始终是服务端：
     * 我们只是**提前**说一句人话，不让玩家对着一个 400 猜半天。名字里带空格或像 URL 的
     * 一律算不搭（多半是把地址填串行了）。
     */
    static ModelPick model(String presetId, String explicit, String unreachableHint) {
        Preset current = preset(presetId);
        String chosen = explicit == null ? "" : explicit.trim();
        boolean fromPreset = chosen.isEmpty();
        if (fromPreset) {
            return new ModelPick(current.model(), true, !current.model().isBlank());
        }
        boolean looksLikeUrl = chosen.startsWith("http:") || chosen.startsWith("https:")
                || chosen.contains("://");
        boolean hasSpace = chosen.chars().anyMatch(Character::isWhitespace);
        String lower = chosen.toLowerCase(Locale.ROOT);
        boolean matches = switch (current.id()) {
            case "zhipu" -> lower.startsWith("glm") || lower.startsWith("charglm");
            case "siliconflow" -> lower.startsWith("qwen") || lower.startsWith("glm")
                    || lower.startsWith("deepseek") || lower.contains("/");
            default -> true;
        };
        return new ModelPick(chosen, false, !looksLikeUrl && !hasSpace && matches);
    }

    /** 接口地址换算成 {@code /chat/completions} 全路径（玩家填到 v4 或 v1 都行）。 */
    static String endpoint(String baseUrl) {
        String base = normalizeUrl(baseUrl);
        if (base.isEmpty()) {
            base = PRESETS.get(0).url();
        }
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        return base + "/chat/completions";
    }

    // ------------------------------------------------------------------ 契约解析（§5.1，纯函数）

    /**
     * 一条候选（本类自己的轻量视图，**故意不复用** {@code VoiceText.Candidate}：
     * 这个类要能脱离 MC 单独编译，离线单测才好跑）。库存只在算「全部」时用得到。
     */
    record Candidate(String id, String name, long count) {
    }

    /**
     * 模型回答里的一条合法结果。
     *
     * @param itemId   候选闭集里的物品 id
     * @param amount   原文数量词（{@code 全部 / N个 / N组 / 半组}），回显与日志用
     * @param count    数量语义解析出来的**实际个数**（{@code 全部} 已经按库存夹紧）
     * @param byStack  是否按「组」折算
     * @param rewrite  给玩家看的改写句子（已按 60 字截断）
     */
    record Answer(String itemId, String amount, int count, boolean byStack, String rewrite, int overrideCount) {
        /** 复判时最终要下单的数量：带明确数量就用它（已夹到 4096），否则用契约里算出来的。 */
        int orderCount() {
            return overrideCount > 0 ? overrideCount : count;
        }
    }

    /**
     * 严格契约解析（**纯函数**，离线单测直接调）。
     *
     * <p>合法形状只有一行、恰好三个字段：{@code item=…; amount=…; rewrite=…}。
     * 缺字段、多字段、换行、JSON、Markdown 包装、闭集外的 id、非法 amount 一律返回 {@code null}
     * （调用方当「没答」处理）。用最笨的 {@code split} 解析，不引 JSON 库。
     *
     * @param raw        模型原样回答
     * @param candidates 候选闭集（本地前 5 名）；{@code item} 必须落在里面
     * @param stackOf    物品 id → 一组多少个；{@code null} 时按 64 算（离线单测用）
     */
    static Answer parse(String raw, List<Candidate> candidates, ToIntFunction<String> stackOf) {
        return parse(raw, candidates, stackOf, 0);
    }

    /**
     * 同上，但可以带一个**本地解析出来的明确数量** {@code overrideCount}。
     *
     * <p>用途：本地匹配器已经把改写句里的数量解析出来了（例如「半组」→32），
     * 这份算术比模型给的 {@code amount} 更可信 —— 契约仍然要合法，数量以本地的为准。
     */
    static Answer parse(String raw, List<Candidate> candidates, ToIntFunction<String> stackOf, int overrideCount) {
        if (raw == null || candidates == null || candidates.isEmpty()) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        // 多行 = 不是单件形状（多件走 parseOrders；JSON/Markdown 也都带换行）
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                return null;
            }
        }
        return parseLine(text, candidates, stackOf, overrideCount);
    }

    /**
     * 多件契约解析（**纯函数**）：模型一次可能要认好几件东西，回答是**一到四行**，
     * 每行都是一条和单件完全相同的契约：{@code item=…; amount=…; rewrite=…}。
     *
     * <p>宽容但**整体作废**的规则（宁可不帮，也不半懂半猜）：
     * <ul>
     *     <li>空行、``` 代码围栏、行首的列表序号（{@code 1.} / {@code - }）忽略；</li>
     *     <li>夹带解释、字段缺失/重复、闭集外 id、非法 amount、{@code item=none}、同一件物品出现两次、
     *         超过 {@link #MAX_ORDERS} 行 → 整个回答作废（返回 {@code null}）；</li>
     *     <li>合法但只有一行时返回单元素列表（和 {@link #parse} 等价）。</li>
     * </ul>
     */
    static List<Answer> parseOrders(String raw, List<Candidate> candidates, ToIntFunction<String> stackOf) {
        if (raw == null || candidates == null || candidates.isEmpty()) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        String[] lines = text.split("\n");
        List<Answer> answers = new ArrayList<>(lines.length);
        List<String> seen = new ArrayList<>(lines.length);
        for (String line : lines) {
            String clean = stripListMarker(line.trim());
            if (clean.isEmpty() || clean.equals("```")) {
                continue;
            }
            Answer answer = parseLine(clean, candidates, stackOf, 0);
            if (answer == null) {
                return null; // 有一行不合法：整个回答作废
            }
            if (seen.contains(answer.itemId())) {
                return null; // 同一件东西出现两次：数量语义含糊，作废
            }
            seen.add(answer.itemId());
            answers.add(answer);
        }
        if (answers.isEmpty() || answers.size() > MAX_ORDERS) {
            return null;
        }
        return List.copyOf(answers);
    }

    /** 去掉行首的 Markdown 列表序号：{@code 1. } / {@code 2、} / {@code - } / {@code * } / {@code · }。 */
    private static String stripListMarker(String line) {
        String text = line;
        int at = 0;
        while (at < text.length() && (text.charAt(at) == '-' || text.charAt(at) == '*'
                || text.charAt(at) == '·')) {
            at++;
        }
        if (at > 0 && at < text.length() && Character.isWhitespace(text.charAt(at))) {
            text = text.substring(at).trim();
        }
        int digits = 0;
        while (digits < text.length() && Character.isDigit(text.charAt(digits))) {
            digits++;
        }
        if (digits > 0 && digits < text.length()) {
            char next = text.charAt(digits);
            if (next == '.' || next == '、' || next == ')' || next == '）' || next == ':' || next == '：') {
                return text.substring(digits + 1).trim();
            }
        }
        return text;
    }

    /**
     * 单行契约解析（**纯函数**）：字段顺序不敏感，但必须恰好三个、各出现一次。
     *
     * @return 合法结果；任何一处不合法都返回 {@code null}
     */
    private static Answer parseLine(String text, List<Candidate> candidates,
                                    ToIntFunction<String> stackOf, int overrideCount) {
        String[] fields = text.split(";");
        if (fields.length != 3) {
            return null;
        }
        String itemId = null;
        String amount = null;
        String rewrite = null;
        for (String field : fields) {
            int at = field.indexOf('=');
            if (at <= 0) {
                return null;
            }
            String key = field.substring(0, at).trim().toLowerCase(Locale.ROOT);
            String value = field.substring(at + 1).trim();
            switch (key) {
                case K_ITEM -> {
                    if (itemId != null) {
                        return null; // 同一个字段出现两次：多字段
                    }
                    itemId = value;
                }
                case K_AMOUNT -> {
                    if (amount != null) {
                        return null;
                    }
                    amount = value;
                }
                case K_REWRITE -> {
                    if (rewrite != null) {
                        return null;
                    }
                    rewrite = value;
                }
                default -> {
                    // 多出来的字段（例如 model=...）：整个结果作废
                    return null;
                }
            }
        }
        if (itemId == null || amount == null || rewrite == null) {
            return null;
        }
        // item=none：模型自己说「不确定」，当没答（不是错误）
        if (itemId.equalsIgnoreCase("none")) {
            return null;
        }
        Candidate candidate = resolveItem(itemId, candidates);
        if (candidate == null) {
            return null; // 闭集外：幻觉，作废
        }
        Matcher matcher = AMOUNT.matcher(amount);
        if (!matcher.matches()) {
            return null; // 非法 amount 决不回退成 1 个
        }
        if (rewrite.isEmpty() || rewrite.length() > MAX_REWRITE) {
            rewrite = rewrite.isEmpty() ? "" : rewrite.substring(0, MAX_REWRITE);
        }
        if (rewrite.isEmpty()) {
            // 回显空白的合法回答没有意义（玩家看不到「AI 帮了什么」），当没答
            return null;
        }
        int count = resolveAmount(amount, stackSizeOf(candidate.id(), stackOf), candidate.count());
        if (count <= 0) {
            return null;
        }
        return new Answer(candidate.id(), amount, count, amount.endsWith("组"), rewrite,
                overrideCount > 0 ? Math.min(overrideCount, ORDER_CAP) : 0);
    }

    /**
     * 把模型回的 {@code item=} 解析成候选里的那一条（必须落在候选闭集内）。
     *
     * <p>三种写法都认：
     * <ol>
     *     <li>{@code #3}：候选表里的第 3 条（从 1 开始）。带时长/等级/颜色的变体行名字很长，
     *         让模型抄编号比抄整段 id 稳，也不容易把 {@code potion@…} 抄错；</li>
     *     <li>完整 id 原样（{@code minecraft:potion@192d3b}）；</li>
     *     <li>基础 id（{@code minecraft:potion}）：只有候选里**唯一**一行是这个基础 id 时才认；
     *         有多行（不同时长/等级）时分不清是哪一件，宁可作废也不猜。</li>
     * </ol>
     */
    private static Candidate resolveItem(String itemId, List<Candidate> candidates) {
        String value = itemId == null ? "" : itemId.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.startsWith("#")) {
            int index = number(value.substring(1));
            return index >= 1 && index <= candidates.size() ? candidates.get(index - 1) : null;
        }
        Candidate exact = find(candidates, value);
        if (exact != null) {
            return exact;
        }
        Candidate unique = null;
        for (Candidate candidate : candidates) {
            if (!baseId(candidate.id()).equalsIgnoreCase(value)) {
                continue;
            }
            if (unique != null) {
                return null; // 基础 id 对应好几行（不同属性）：分不清，别猜
            }
            unique = candidate;
        }
        return unique;
    }

    /** 变体键的基础 id：{@code minecraft:potion@192d3b} → {@code minecraft:potion}。 */
    private static String baseId(String itemId) {
        int at = itemId == null ? -1 : itemId.indexOf('@');
        return at > 0 ? itemId.substring(0, at) : itemId;
    }

    /**
     * 数量换算（**在本地做**，不问模型要数字）：{@code 全部}→{@code min(库存,4096)}、
     * {@code N个}→N、{@code N组}→{@code N*stackSize}、{@code 半组}→{@code max(1,stackSize/2)}、
     * {@code X分之Y组}→{@code stackSize*Y/X}（向下取整，至少 1 个）。
     *
     * @return 实际个数；{@code amount} 非法时返回 {@code -1}（调用方必须当失败，**不能**回退成 1）
     */
    static int resolveAmount(String amount, int stackSize, long stock) {
        if (amount == null) {
            return -1;
        }
        Matcher matcher = AMOUNT.matcher(amount);
        if (!matcher.matches()) {
            return -1;
        }
        int stack = Math.max(1, Math.min(stackSize, ORDER_CAP));
        String word = matcher.group(1);
        if (word.equals("全部")) {
            long available = Math.min(Math.max(0L, stock), ORDER_CAP);
            return (int) Math.max(1L, available);
        }
        if (word.equals("半组")) {
            return Math.max(1, stack / 2);
        }
        if (matcher.group(4) != null) {
            int denominator = number(matcher.group(4));
            int numerator = number(matcher.group(5));
            if (denominator < 2 || numerator < 1 || numerator > denominator) {
                return -1;
            }
            return (int) Math.min(ORDER_CAP,
                    Math.max(1L, (long) stack * numerator / denominator));
        }
        String digits = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
        if (digits == null) {
            return -1;
        }
        int n = number(digits);
        if (n < 1 || n > MAX_AMOUNT) {
            return -1;
        }
        long total = matcher.group(3) != null ? (long) n * stack : n;
        return (int) Math.min(Math.max(1L, total), ORDER_CAP);
    }

    // ------------------------------------------------------------------ 提示词（§5.2）

    /**
     * {@code amount} 里的那个 N：阿拉伯数字或常用中文数字（一~四千零九十六）。
     *
     * <p>只认「数值 + 十/百/千」这种写法，认不出就返回 {@code -1}（调用方必须当失败，**不能**回退成 1）。
     * 上限 10240 只是为了让后面的 1–4096 检查去拒，避免 {@code 十千} 这类怪写法绕过去。
     */
    private static int number(String digits) {
        if (digits == null || digits.isEmpty()) {
            return -1;
        }
        boolean ascii = true;
        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) < '0' || digits.charAt(i) > '9') {
                ascii = false;
                break;
            }
        }
        if (ascii) {
            try {
                return Integer.parseInt(digits);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        int total = 0;
        int current = 0;
        int lastUnit = Integer.MAX_VALUE;
        for (int i = 0; i < digits.length(); i++) {
            int value = digit(digits.charAt(i));
            if (value < 0) {
                return -1;
            }
            if (value < 10) {
                current = value;
                continue;
            }
            int unit = value;
            // 小单位必须写在大单位右边（四千零九十六），单位一大一小说明是「十千」这类怪写法
            if (unit >= lastUnit) {
                return -1;
            }
            lastUnit = unit;
            total += (current == 0 ? 1 : current) * unit;
            current = 0;
        }
        total += current;
        return total > 0 && total <= 10240 ? total : -1;
    }

    /** 单个汉字数字 → 数值：一~九 1–9、十 10、百 100、千 1000、两 2（两 由调用方按位置处理）。 */
    private static int digit(char c) {
        return switch (c) {
            case '一', '壹' -> 1;
            case '二', '两', '贰' -> 2;
            case '三', '叁' -> 3;
            case '四', '肆' -> 4;
            case '五', '伍' -> 5;
            case '六', '陆' -> 6;
            case '七', '柒' -> 7;
            case '八', '捌' -> 8;
            case '九', '玖' -> 9;
            case '十', '拾' -> 10;
            case '百', '佰' -> 100;
            case '千', '仟' -> 1000;
            case '零', '〇' -> 0;
            default -> -1;
        };
    }

    /** 系统提示固定不变，便于各家做前缀缓存（智谱/硅基流动都支持，能显著降延迟）。 */
    static String systemPrompt() {
        return "你是仓库取货口令的归一化器。只输出最终结果，不要解释：\n"
                + "玩家一句话只说一件东西时，输出一行：\n"
                + "item=<候选编号，如 #3>; amount=<全部|N个|N组|半组|X分之Y组>; rewrite=<改写后的中文>\n"
                + "玩家一句话说好几件东西时，每件输出一行（最多 4 行），每行格式同上，"
                + "rewrite 只写这一件东西的说法：\n"
                + "item=<候选编号，如 #5>; amount=<全部|N个|N组|半组|X分之Y组>; rewrite=<这一件的说法>\n"
                + "规则：item 填候选表里的编号（#1 是第一条，就是列表里那个数字）；候选名字里带"
                + "时长、等级、颜色、附魔、药水效果的，玩家说了哪个属性就选名字里含那个属性的那一行，"
                + "名字不同就是不同的东西，绝不能选错；某一件不确定就整句回 item=none; rewrite=<原文>；"
                + "不要解释、不要多余文字、不要给每行加序号。";
    }

    /** 候选清单 + 玩家原话（候选只给前 8 名，不给整份仓库清单）。 */
    static String userPrompt(String text, List<Candidate> candidates) {
        StringBuilder builder = new StringBuilder("候选：\n");
        int limit = Math.min(candidates.size(), MAX_CANDIDATES);
        for (int i = 0; i < limit; i++) {
            Candidate candidate = candidates.get(i);
            builder.append('#').append(i + 1).append(' ').append(candidate.id()).append(' ')
                    .append(candidate.name()).append("（库存 ").append(candidate.count()).append("）\n");
        }
        return builder.append("\n玩家说：").append(text).toString();
    }

    /**
     * OpenAI 兼容的请求体（{@code temperature=0}）。
     *
     * <p>{@code max_tokens} 默认 64（单件旧行为，离线单测断言这个值）；多件回答一行不够装，
     * 由 {@link #tokenBudget(String)} 按句子里的件数放大到最多 256。
     */
    static String requestBody(String model, String text, List<Candidate> candidates) {
        return requestBody(model, text, candidates, 64);
    }

    /** 同上，但可以指定 {@code max_tokens}（多件请求用）。 */
    static String requestBody(String model, String text, List<Candidate> candidates, int maxTokens) {
        int budget = maxTokens < 64 ? 64 : Math.min(maxTokens, 256);
        return "{\"model\":" + json(model)
                + ",\"temperature\":0,\"max_tokens\":" + budget + ",\"stream\":false,\"messages\":["
                + "{\"role\":\"system\",\"content\":" + json(systemPrompt()) + "},"
                + "{\"role\":\"user\",\"content\":" + json(userPrompt(text, candidates)) + "}]}";
    }

    /** 从 OpenAI 兼容回答里取 {@code choices[0].message.content}；取不到返回 null。 */
    static String extractContent(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        int at = body.indexOf("\"content\"");
        while (at >= 0) {
            int colon = body.indexOf(':', at + 9);
            if (colon < 0) {
                return null;
            }
            int i = colon + 1;
            while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
                i++;
            }
            if (i < body.length() && body.charAt(i) == '"') {
                return jsonString(body, i);
            }
            at = body.indexOf("\"content\"", colon);
        }
        return null;
    }

    /** 极简 JSON 字符串转义（本项目不引 JSON 库）。 */
    private static String json(String value) {
        StringBuilder builder = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        return builder.append('"').toString();
    }

    /** 从 {@code body[quote]} 那个引号开始读一个 JSON 字符串（支持常见转义）。 */
    private static String jsonString(String body, int quote) {
        StringBuilder builder = new StringBuilder();
        for (int i = quote + 1; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\') {
                if (i + 1 >= body.length()) {
                    return builder.toString();
                }
                char next = body.charAt(++i);
                switch (next) {
                    case 'n' -> builder.append('\n');
                    case 't' -> builder.append('\t');
                    case 'r' -> builder.append('\r');
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'u' -> {
                        if (i + 4 < body.length()) {
                            try {
                                builder.append((char) Integer.parseInt(body.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException e) {
                                return builder.toString();
                            }
                        }
                    }
                    default -> builder.append(next);
                }
                continue;
            }
            if (c == '"') {
                return builder.toString();
            }
            builder.append(c);
        }
        return builder.toString();
    }

    // ------------------------------------------------------------------ 状态、用量与日志

    /** 一次调用的结果。{@link #OK} 之外都表示「AI 没帮上忙，用本地结果」。 */
    enum Status {
        /** 改动成功，{@link Result#override} 可以交给本地复判。 */
        OK,
        /** 开关关着：一个字节都没出网。 */
        DISABLED,
        /** 没填 Key（云端服务商）。 */
        NO_KEY,
        /** 限速/额度拦下了（令牌桶或 429 退避）。 */
        RATE_LIMITED,
        /** 超时 / 连不上 / DNS 失败。 */
        TIMEOUT,
        /** HTTP 401：Key 无效或未填。调用方应把开关置回 off。 */
        KEY_INVALID,
        /** 其它 HTTP 错误或回答不合法（含 item=none、闭集外、非法 amount）。 */
        NO_ANSWER
    }

    /**
     * 一次调用的完整结果：状态、延迟、给玩家看的回显句，以及**本地复判要用的契约结果**。
     *
     * @param status   状态；只有 {@link Status#OK} 才可能有结果
     * @param millis   这次调用花了多久
     * @param override 给玩家看的「AI 帮了什么」：单件 = 模型改写句（沿用旧行为）；
     *                 多件 = 各件改写句用「，」连起来（只用于显示，绝不当命令）
     * @param answers  本地校验过闭集/数量后的契约结果（单件 1 条、多件 1–4 条）；
     *                 真正下什么单由 {@code VoicePhrase} 用它再走一遍本地匹配决定
     */
    record Result(Status status, long millis, String override, List<Answer> answers) {
        static final Result DISABLED = new Result(Status.DISABLED, 0L, null, List.of());

        boolean ok() {
            return status == Status.OK && answers != null && !answers.isEmpty();
        }
    }

    /** 给设置页读的用量回显（「今天已用 N 次 / 上次延迟 X ms / 上次结果：成功|超时|错误」）。 */
    record Usage(int today, long lastMillis, Status lastStatus) {
    }

    private static final Object LOCK = new Object();
    /** 令牌桶：最近一分钟 / 最近一天用掉了几次 + 上次 429 的时刻。 */
    private static long[] minuteStamps = new long[PER_MINUTE];
    private static int minuteAt;
    private static long dayStamp;
    private static int dayCount;
    private static long backoffUntil;
    private static long lastMillis;
    private static Status lastStatus = Status.DISABLED;
    /** 归一化原文 + 候选签名 + 服务商 → 改写句子（LRU 500 条，进程内存）。 */
    private static final Map<String, String> CACHE =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > CACHE_SIZE;
                }
            };
    /** 出网开关的只读镜像：给 UI 用，不参与判断（判断看调用方传来的 {@code enabled}）。 */
    private static volatile boolean enabledFlag;
    /** 401 时记一句话等主线程取走（「Key 无效」只提示一次）。 */
    private static volatile String notice = "";

    /** 日志出口：产品里注入 MC logger，离线单测里用默认的空实现（本类不能 import MC）。 */
    interface Log {
        void line(String message);
    }

    private static volatile Log log = message -> {
    };

    /** HTTP 出口：产品里是 JDK {@code HttpClient}，离线单测里换成假 provider。 */
    interface Transport {
        /**
         * @return 状态码 + 正文；连不上/超时抛 {@link IOException}
         */
        Reply send(String endpoint, String apiKey, String body, int timeoutMs) throws IOException, InterruptedException;
    }

    record Reply(int code, String body) {
    }

    static void setLog(Log sink) {
        if (sink != null) {
            log = sink;
        }
    }

    static void setEnabled(boolean value) {
        enabledFlag = value;
    }

    static boolean enabled() {
        return enabledFlag;
    }

    /** 取走攒下的那句提示（没有就是空串；只提示一次）。 */
    static String consumeNotice() {
        String value = notice;
        notice = "";
        return value;
    }

    /** 设置页状态行读它。 */
    static Usage usage() {
        synchronized (LOCK) {
            return new Usage(dayCount, lastMillis, lastStatus);
        }
    }

    /** 「上次结果」的短文案。 */
    static String describe(Status status) {
        return switch (status) {
            case OK -> "成功";
            case TIMEOUT -> "超时";
            case DISABLED -> "未启用";
            case NO_KEY -> "没填 Key";
            case RATE_LIMITED -> "限速跳过";
            case KEY_INVALID -> "Key 无效";
            default -> "错误";
        };
    }

    /**
     * Key 的回显：**只显示前 4 位**，其余全用星号（§6.3）。
     *
     * <p>不足 4 位时一位都不显示（只说「已填」）—— 位太少时露出来的那几位就是很大一部分信息。
     * 星号按原文长度给一个上限，免得一个超长 key 把设置页那一行撑爆。
     */
    static String maskKey(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String text = key.trim();
        if (text.length() < 4) {
            return "****";
        }
        int stars = Math.min(8, Math.max(4, text.length() - 4));
        return text.substring(0, 4) + "*".repeat(stars);
    }

    /** 设置页/面板状态行的一句话：「今天已用 N 次 · 上次 X ms · 成功」。 */
    static String statusLine() {
        Usage usage = usage();
        String last = usage.lastMillis() <= 0L ? "—" : usage.lastMillis() + " ms";
        return "今天已用 " + usage.today() + " 次 · 上次 " + last + " · " + describe(usage.lastStatus());
    }

    /** 面板 INFO 行用的短值：「12 次 · 380 ms · 成功」。 */
    static String shortStatus() {
        Usage usage = usage();
        String last = usage.lastMillis() <= 0L ? "—" : usage.lastMillis() + " ms";
        return usage.today() + " 次 · " + last + " · " + describe(usage.lastStatus());
    }

    /** 仅供离线单测：把限速/缓存/统计清干净。 */
    static void resetForTest() {
        synchronized (LOCK) {
            minuteStamps = new long[PER_MINUTE];
            minuteAt = 0;
            dayStamp = 0L;
            dayCount = 0;
            backoffUntil = 0L;
            lastMillis = 0L;
            lastStatus = Status.DISABLED;
            CACHE.clear();
            notice = "";
        }
    }

    // ------------------------------------------------------------------ 连通性自检

    /** 「测试连通性」的结果：通没通、花了多久、一句人话（面板把它画在状态行里）。 */
    record Probe(boolean ok, long millis, String detail) {
    }

    /**
     * 只试一次连通性：真发一个最短的请求（{@code ping}），量出延迟，并看服务商怎么回。
     *
     * <p>和 {@link #rewrite} 的区别：**不碰令牌桶、不写统计、不进缓存** —— 玩家点「测试」是想知道
     * 「这个地址 + 这个 Key 现在通不通」，不该顺带吃掉一次调用额度，也不该把「今天已用 N 次」改掉。
     * 连不上、超时、401/403、429、其它 HTTP 码各自给一句能看懂的话。
     *
     * <p>**不要在客户端主线程调用**（最长会阻塞到 {@code timeoutMs}）。
     *
     * @param baseUrl   接口地址（空则用默认预设）
     * @param model     模型名（空则用该预设的默认模型）
     * @param apiKey    Key（本地地址可以为空）
     * @param timeoutMs 超时（500–3000，越界夹紧）
     * @param transport 传输层（产品用 {@link #httpTransport()}，离线单测用假的）
     */
    static Probe probe(String baseUrl, String model, String apiKey, int timeoutMs, Transport transport) {
        long began = System.currentTimeMillis();
        String key = apiKey == null ? "" : apiKey.trim();
        String url = normalizeUrl(baseUrl);
        String chosenModel = model == null ? "" : model.trim();
        if (url.isEmpty()) {
            url = PRESETS.get(0).url();
        }
        if (chosenModel.isEmpty()) {
            chosenModel = presetOfUrl(url).model();
            if (chosenModel.isEmpty()) {
                chosenModel = PRESETS.get(0).model();
            }
        }
        boolean local = isLocal(url);
        if (key.isEmpty() && !local) {
            return new Probe(false, 0L, "还没填 API Key");
        }
        int timeout = clampTimeout(timeoutMs);
        String body = "{\"model\":" + json(chosenModel)
                + ",\"temperature\":0,\"max_tokens\":8,\"stream\":false,\"messages\":["
                + "{\"role\":\"user\",\"content\":\"ping\"}]}";
        try {
            Reply reply = transport.send(endpoint(url), key, body, timeout);
            long millis = System.currentTimeMillis() - began;
            if (reply == null) {
                return new Probe(false, millis, "服务商没有回应");
            }
            if (reply.code() == 401 || reply.code() == 403) {
                return new Probe(false, millis, "Key 无效或没权限（HTTP " + reply.code() + "）");
            }
            if (reply.code() == 429) {
                return new Probe(false, millis, "被限速（HTTP 429），过一会儿再试");
            }
            if (reply.code() / 100 != 2) {
                return new Probe(false, millis, "HTTP " + reply.code() + "：" + preview(reply.body()));
            }
            String content = extractContent(reply.body());
            if (content == null || content.isBlank()) {
                return new Probe(true, millis, "通了（服务商没回文字，但连接与鉴权都正常）");
            }
            return new Probe(true, millis, "通了，回答：" + preview(content));
        } catch (java.net.http.HttpTimeoutException e) {
            return new Probe(false, System.currentTimeMillis() - began, "超时（超过 " + timeout + " 毫秒）");
        } catch (IOException e) {
            return new Probe(false, System.currentTimeMillis() - began,
                    "连不上：" + e.getClass().getSimpleName() + (local ? "（本地服务没启动也是这个现象）" : ""));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Probe(false, System.currentTimeMillis() - began, "被中断");
        }
    }

    // ------------------------------------------------------------------ 主入口

    /** 一次调用带的全部设置（调用方现读现传，不缓存 —— 玩家改完设置下一句就生效）。 */
    record Options(boolean enabled, String baseUrl, String model, String apiKey, int timeoutMs,
                   boolean localHint) {
        /** 只有开关，其余走默认（离线单测最常用的构造）。 */
        static Options of(boolean enabled) {
            return new Options(enabled, "", "", "", DEFAULT_TIMEOUT_MS, false);
        }
    }

    /**
     * 把「本地听不懂的一句话」交给云端小模型归一化。**绝不在主线程调用**（会阻塞到超时）。
     *
     * @param text       ASR 原文（已由本地判过 {@code noMatch}）
     * @param candidates 本地前 N 名候选（只会把前 5 名发出去）
     * @param options    开关 + 地址 + 模型 + key + 超时；{@code enabled=false} 直接返回
     *                   {@link Status#DISABLED}，**连解析都不做**（一个字节都不出网）
     * @param transport  传输层（产品用 {@link #httpTransport()}，离线单测用假的）
     * @param stackOf    物品 id → 一组多少个（MC 侧传 {@code VoicePhrase::itemStackSize}；可传 null = 64）
     * @return 状态 + 延迟 + 可选的改写句子
     */
    static Result rewrite(String text, List<Candidate> candidates, Options options, Transport transport,
                          ToIntFunction<String> stackOf) {
        if (options == null) {
            return Result.DISABLED;
        }
        return rewrite(text, candidates, options.enabled(), options.baseUrl(), options.model(),
                options.apiKey(), options.timeoutMs(), transport, stackOf);
    }

    /**
     * 把「本地听不懂的一句话」交给云端小模型归一化。**绝不在主线程调用**（会阻塞到超时）。
     *
     * @param text        ASR 原文（已由本地判过 {@code noMatch}）
     * @param candidates  本地前 N 名候选（只会把前 5 名发出去），需带 stackSize
     * @param enabled     开关；{@code false} 直接返回 {@link Status#DISABLED}，不发请求
     * @param baseUrl     接口地址（空则用默认预设）
     * @param model       模型名（空则用默认预设）
     * @param apiKey      Key（本地 Ollama 可以为空）
     * @param timeoutMs   超时（500–3000，越界夹紧）
     * @param transport   传输层（产品用 {@link #httpTransport()}，单测用假的）
     * @param stackOf     物品 id → 一组多少个（MC 侧传 {@code VoicePhrase::itemStackSize}；离线单测可传 null = 64）
     * @return 状态 + 延迟 + 可选的改写句子
     */
    static Result rewrite(String text, List<Candidate> candidates, boolean enabled, String baseUrl, String model,
                          String apiKey, int timeoutMs, Transport transport, ToIntFunction<String> stackOf) {
        long began = System.currentTimeMillis();
        if (!enabled) {
            return Result.DISABLED;
        }
        enabledFlag = true;
        if (text == null || text.isBlank() || candidates == null || candidates.isEmpty()) {
            return finish(Status.NO_ANSWER, began, null);
        }
        String key = apiKey == null ? "" : apiKey.trim();
        String url = normalizeUrl(baseUrl);
        String chosenModel = model == null ? "" : model.trim();
        if (url.isEmpty()) {
            url = PRESETS.get(0).url();
        }
        if (chosenModel.isEmpty()) {
            chosenModel = presetOfUrl(url).model();
            if (chosenModel.isEmpty()) {
                chosenModel = PRESETS.get(0).model();
            }
        }
        boolean local = isLocal(url);
        if (key.isEmpty() && !local) {
            // 云端服务商没 key：白跑一趟不如直接说清楚（设置页会给一行提示）
            return finish(Status.NO_KEY, began, null);
        }
        if (!allow()) {
            return finish(Status.RATE_LIMITED, began, null);
        }
        int timeout = clampTimeout(timeoutMs);
        List<Candidate> top = candidates.size() > MAX_CANDIDATES ? candidates.subList(0, MAX_CANDIDATES) : candidates;
        List<Candidate> pool = new ArrayList<>(top);

        String cacheKey = text.trim() + '\u0000' + url + '\u0000' + chosenModel + '\u0000' + signature(pool);
        synchronized (LOCK) {
            String hit = CACHE.get(cacheKey);
            if (hit != null) {
                List<Answer> cached = parseOrders(hit, pool, stackOf);
                // 缓存里的 item 可能已经不在候选里了（仓库变了）：那种就当作没缓存，重新问
                if (cached != null) {
                    return finish(Status.OK, began, override(cached), cached);
                }
                CACHE.remove(cacheKey);
            }
        }

        String body = requestBody(chosenModel, text.trim(), pool, tokenBudget(text.trim()));
        Reply reply;
        try {
            reply = transport.send(endpoint(url), key, body, timeout);
        } catch (java.net.http.HttpTimeoutException e) {
            log.line("AI 改写超时（" + timeout + " 毫秒）：" + text.length() + " 个字的句子，退回本地结果");
            return finish(Status.TIMEOUT, began, null);
        } catch (IOException e) {
            log.line("AI 改写连不上：" + e.getClass().getSimpleName()
                    + "（本地 Ollama 没启动也是这个现象），退回本地结果");
            return finish(Status.TIMEOUT, began, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return finish(Status.TIMEOUT, began, null);
        }
        if (reply == null) {
            return finish(Status.NO_ANSWER, began, null);
        }
        if (reply.code() == 401 || reply.code() == 403) {
            // Key 无效：提示一次并把开关置回 off（避免每句都白跑），由调用方落盘
            notice = "AI 归一化：API Key 无效或未填，已自动关掉这个开关";
            log.line("AI 改写 HTTP " + reply.code() + "：Key 无效或没权限，开关已自动置回 off");
            return finish(Status.KEY_INVALID, began, null);
        }
        if (reply.code() == 429) {
            synchronized (LOCK) {
                backoffUntil = System.currentTimeMillis() + BACKOFF_MS;
            }
            log.line("AI 改写被限速（HTTP 429）：60 秒内不再调用，退回本地结果");
            return finish(Status.RATE_LIMITED, began, null);
        }
        if (reply.code() / 100 != 2) {
            log.line("AI 改写 HTTP " + reply.code() + "：退回本地结果");
            return finish(Status.NO_ANSWER, began, null);
        }
        String content = extractContent(reply.body());
        List<Answer> answers = parseOrders(content, pool, stackOf);
        if (answers == null) {
            log.line("AI 改写回答不合法（当没答）：" + preview(content));
            return finish(Status.NO_ANSWER, began, null);
        }
        synchronized (LOCK) {
            CACHE.put(cacheKey, content.trim());
        }
        return finish(Status.OK, began, override(answers), answers);
    }

    /**
     * 一条或多条合法回答 → 给玩家看的回显句。
     *
     * <p>单件时沿用旧行为（模型改写句本身）；多件时把各件的改写句用「，」连起来 ——
     * 这只是**显示**，真正下什么单由调用方拿 {@link Result#answers()} 再走本地复判。
     */
    static String override(List<Answer> answers) {
        if (answers == null || answers.isEmpty()) {
            return "";
        }
        if (answers.size() == 1) {
            return override(answers.get(0));
        }
        StringBuilder builder = new StringBuilder();
        for (Answer answer : answers) {
            if (builder.length() > 0) {
                builder.append("，");
            }
            String text = override(answer);
            builder.append(text.length() > MAX_REWRITE ? text.substring(0, MAX_REWRITE) : text);
        }
        return builder.toString();
    }

    /**
     * 这次回答最多要多少 token：单件 64（沿用旧行为，离线单测也断言着它）；
     * 多件每多一件加 64，最多 256 —— 两行契约（id + 数量 + 改写句）64 个 token 装不下。
     */
    static int tokenBudget(String text) {
        List<String> clauses = VoiceText.clauses(VoiceText.normalize(text));
        int lines = clauses.size() >= 2 ? Math.min(clauses.size(), MAX_ORDERS) : 1;
        return Math.min(256, 64 * lines);
    }

    /** 把一条合法回答变成「本地能复判的句子」。 */
    static String override(Answer answer) {
        String text = answer.rewrite() == null ? "" : answer.rewrite().trim();
        if (!text.isEmpty()) {
            return text;
        }
        return amountPhrase(answer.amount()) + " " + answer.itemId();
    }

    /** 数量词 → 口语说法（合成句子用）。 */
    static String amountPhrase(String amount) {
        return amount == null || amount.isEmpty() ? "一组" : amount;
    }

    // ------------------------------------------------------------------ 内部

    private static Result finish(Status status, long began, String override) {
        return finish(status, began, override, List.of());
    }

    private static Result finish(Status status, long began, String override, List<Answer> answers) {
        long millis = System.currentTimeMillis() - began;
        synchronized (LOCK) {
            lastStatus = status;
            lastMillis = millis;
            if (status == Status.OK || status == Status.NO_ANSWER || status == Status.TIMEOUT
                    || status == Status.KEY_INVALID) {
                dayCount++;
            }
        }
        return new Result(status, millis, override, answers == null ? List.of() : answers);
    }

    /** 超时夹到 500–10000；≤0（没配）给默认值。包内可见：{@link SherpaEngine} 读设置时也要用同一把尺子。 */
    static int clampTimeout(int timeoutMs) {
        if (timeoutMs <= 0) {
            return DEFAULT_TIMEOUT_MS;
        }
        return Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, timeoutMs));
    }

    /** 本地地址（Ollama）不需要 key，也永远不会 401。 */
    private static boolean isLocal(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("127.0.0.1") || lower.contains("localhost") || lower.contains("[::1]");
    }

    /** 候选签名：库存变了就不复用缓存（否则「全部」会按旧库存算）。 */
    private static String signature(List<Candidate> candidates) {
        StringBuilder builder = new StringBuilder();
        for (Candidate candidate : candidates) {
            builder.append(candidate.id()).append(':').append(candidate.count()).append(';');
        }
        return builder.toString();
    }

    /** 一组多少个：拿不到按 64（和本地匹配器的默认值一致）。 */
    private static int stackSizeOf(String itemId, ToIntFunction<String> stackOf) {
        if (stackOf == null) {
            return 64;
        }
        try {
            int size = stackOf.applyAsInt(itemId);
            return size > 0 ? Math.min(size, ORDER_CAP) : 64;
        } catch (Throwable t) {
            return 64;
        }
    }

    private static Candidate find(List<Candidate> candidates, String id) {
        for (Candidate candidate : candidates) {
            if (candidate.id().equalsIgnoreCase(id)) {
                return candidate;
            }
        }
        return null;
    }

    private static String preview(String text) {
        if (text == null) {
            return "（空回答）";
        }
        String clean = text.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() > 80 ? clean.substring(0, 80) + "…" : clean;
    }

    /**
     * 令牌桶：一分钟 20 次、一天 200 次；429 退避期内直接拒绝。
     *
     * <p>只在**真的要发请求**前调用。滑动窗口用时间戳环，够简单也够准。
     */
    static boolean allow() {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (now < backoffUntil) {
                return false;
            }
            // 跨天重置
            long midnight = now / 86_400_000L;
            if (dayStamp != midnight) {
                dayStamp = midnight;
                dayCount = 0;
                minuteAt = 0;
                minuteStamps = new long[PER_MINUTE];
            }
            if (dayCount >= PER_DAY) {
                return false;
            }
            for (int i = 0; i < PER_MINUTE; i++) {
                int at = (minuteAt + i) % PER_MINUTE;
                long stamp = minuteStamps[at];
                if (stamp != 0L && now - stamp > 60_000L) {
                    minuteStamps[at] = 0L;
                }
            }
            int used = 0;
            for (long stamp : minuteStamps) {
                if (stamp != 0L) {
                    used++;
                }
            }
            if (used >= PER_MINUTE) {
                return false;
            }
            minuteStamps[minuteAt] = now;
            minuteAt = (minuteAt + 1) % PER_MINUTE;
            return true;
        }
    }

    private static volatile Transport shared;

    /**
     * 产品用的传输层：JDK {@code HttpClient}，打开即用、超时由请求控制。
     * 写法与 {@link VoiceModelDownloader} 里那份一致（那里是下载，这里是 POST 一段 JSON）。
     */
    static Transport httpTransport() {
        Transport current = shared;
        if (current == null) {
            current = new JdkTransport();
            shared = current;
        }
        return current;
    }

    private static final class JdkTransport implements Transport {

        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(800L))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        @Override
        public Reply send(String endpoint, String apiKey, String body, int timeoutMs)
                throws IOException, InterruptedException {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (apiKey != null && !apiKey.isEmpty()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body());
        }
    }
}
