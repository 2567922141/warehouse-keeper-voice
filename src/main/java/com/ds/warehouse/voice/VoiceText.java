package com.ds.warehouse.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 口语解析与物品名匹配的**纯文本**部分（不依赖 Minecraft，便于离线单测）。
 *
 * <p>做三件事：去掉口水词 → 拆出数量与单位（十二个=12、一组=一组堆叠）→
 * 把剩下的字拿去和「仓库里真实存在的物品名」做模糊匹配（包含 / 编辑距离 / 双字组）。
 *
 * <p>对游戏内专有名词特别有用：识别常把「下界合金剑」听成「下界和金剑」「下季和金剑」，
 * 但只要候选名里有正确的那个，编辑距离就能把它挑出来。
 */
final class VoiceText {

    /** 相似度够高直接采用。 */
    static final double STRONG = 0.55D;
    /** 相似度一般，但明显领先第二名时也可采用。 */
    static final double WEAK = 0.35D;
    /** 「明显领先」的差距。 */
    static final double MARGIN = 0.15D;
    /** 拼音相似度至少要这么高才算数（太低说明只是碰巧押韵，不能下单）。 */
    static final double PINYIN_MIN = 0.55D;
    /**
     * 口音折叠后的相似度门槛：折叠会合并一些本来能区分的音（平翘舌、n/l、前后鼻音），
     * 所以门坎要比精确拼音高一点，只在「像得明显」时才采用。
     */
    static final double ACCENT_MIN = 0.62D;

    /** 中文数字。 */
    private static final Map<Character, Integer> DIGITS = Map.ofEntries(
            Map.entry('零', 0), Map.entry('〇', 0),
            Map.entry('一', 1), Map.entry('二', 2), Map.entry('两', 2), Map.entry('俩', 2),
            Map.entry('三', 3), Map.entry('四', 4), Map.entry('五', 5), Map.entry('六', 6),
            Map.entry('七', 7), Map.entry('八', 8), Map.entry('九', 9));

    /**
     * 量词：紧跟数字时一起吃掉；「组」表示一组堆叠。
     *
     * <p>语义提醒：除「组」以外的量词（含「箱」「袋」「桶」）现在只是**吃掉**，不改数量 ——
     * 「两箱火药」= 2 个火药。改成按箱折算会动到既有下单语义，口语归一化第 ① 步有意不改。
     */
    private static final String UNITS = "个件块组盒桶把张只条根袋箱枚片瓶碗本支颗份堆排串卷";

    /** 一次下单的硬上限：与 {@code VoicePhrase.MAX_ORDER} 保持一致（本类要能脱离 Minecraft 离线编译，不能反向引用它）。 */
    static final int ORDER_CAP = 4096;

    /**
     * 口语数量词：**按长词优先排列**（「剩下的都给我」必须排在「都」前面，否则「都」会先把后面的字吃掉）。
     */
    private static final Spoken[] SPOKEN = {
            new Spoken("剩下的都给我", Kind.ALL),
            new Spoken("剩下的都要", Kind.ALL),
            new Spoken("剩下的都", Kind.ALL),
            new Spoken("全部都要", Kind.ALL),
            new Spoken("全都给我", Kind.ALL),
            new Spoken("全部都", Kind.ALL),
            new Spoken("全要", Kind.ALL),
            new Spoken("全部", Kind.ALL),
            new Spoken("所有", Kind.ALL),
            new Spoken("都要了", Kind.ALL),
            new Spoken("都要", Kind.ALL),
            new Spoken("都给我", Kind.ALL),
            new Spoken("都", Kind.ALL),
            new Spoken("一半", Kind.HALF_ALL),
            new Spoken("半组", Kind.HALF_STACK),
            new Spoken("一组整", Kind.STACK),
            new Spoken("整组", Kind.STACK),
            new Spoken("满组", Kind.STACK),
            new Spoken("一点儿", Kind.SOME),
            new Spoken("一点", Kind.SOME),
            new Spoken("一些", Kind.SOME),
            new Spoken("若干", Kind.SOME),
            new Spoken("来点", Kind.SOME),
            new Spoken("半个", Kind.HALF_PIECE),
            new Spoken("半块", Kind.HALF_PIECE),
            new Spoken("半盒", Kind.HALF_PIECE),
            new Spoken("半件", Kind.HALF_PIECE),
    };

    /** 口水词 / 动词：从开头反复剥掉。「来点」**不在这里** —— 它是数量词（见 {@link #SPOKEN}）。 */
    private static final String[] FILLERS = {
            "请帮我", "帮我", "给我来", "给我", "我想要", "我要", "来个", "来", "拿点", "拿个", "拿", "取", "要",
            "麻烦", "请", "一下", "谢谢", "多谢", "额", "啊", "嗯", "呃", "那个", "这个", "就是", "然后"
    };

    private VoiceText() {
    }

    /** 候选物品（名字来自仓库快照）。 */
    record Candidate(String id, String name, long count) {
    }

    /** 一次匹配结果：物品、最终数量、一组多少个、是否按「组」折算、口语来源词、相似度、与第二名的差距。 */
    record Match(Candidate candidate, int count, int unitSize, boolean byStack, String source,
                 double score, double gap) {
    }

    /** 数量的语义：定值 / 一组 / 半组 / N 组半 / 分数个组 / 半件 / 全要 / 一半库存 / 少量（一组）。 */
    private enum Kind {
        COUNT, STACK, HALF_STACK, STACK_AND_HALF, FRACTION, HALF_PIECE, ALL, HALF_ALL, SOME
    }

    /** 口语数量词：说法 → 语义。 */
    private record Spoken(String word, Kind kind) {
    }

    /**
     * 数量：value=数字（{@code N 组半} 里的 N）、end=在原文中的结束位置、start=起始位置、
     * kind=语义、source=口语来源词（纯数字时为 null，回显里用来讲清「按『半组』算」）。
     *
     * <p>{@code FRACTION}（「四分之一组」）另带分子/分母，按一组多少个折算。
     */
    private record Quantity(int value, int end, int start, Kind kind, String source,
                            int numerator, int denominator) {

        Quantity(int value, int end, int start, Kind kind, String source) {
            this(value, end, start, kind, source, 0, 0);
        }

        /** 是否按「组」折算（数字 × 一组多少个）。 */
        boolean byStack() {
            return kind == Kind.STACK || kind == Kind.STACK_AND_HALF || kind == Kind.FRACTION;
        }
    }

    /** 去掉空白、口水词与「的」（「红色的玻璃」→「红色玻璃」），并把独段罗马数字等级折成阿拉伯数字。 */
    static String normalize(String heard) {
        String text = heard == null ? "" : heard.replaceAll("\\s+", "");
        return romanToDigits(stripFillers(text, false).replace("的", ""));
    }

    /** 独段的罗马数字（附魔等级：深海探索者 III）。只在前后都不是字母时才算，免得命中 int 这种词。 */
    private static final java.util.regex.Pattern ROMAN =
            java.util.regex.Pattern.compile("(?<![A-Za-z])[IVX]{1,4}(?![A-Za-z])");

    /**
     * 把罗马数字等级折成阿拉伯数字：「深海探索者 III」→「深海探索者3」。
     *
     * <p>玩家嘴上说的是「深海探索者三」或「深海探索者3」，而服务端名字里写的是「III」；
     * 不折算的话这两种写法在相似度里永远对不上，附魔书这种带等级的变体就永远听不出来。
     */
    private static String romanToDigits(String text) {
        if (text.indexOf('I') < 0 && text.indexOf('V') < 0 && text.indexOf('X') < 0) {
            return text;
        }
        return ROMAN.matcher(text).replaceAll(m -> String.valueOf(roman(m.group())));
    }

    private static int roman(String value) {
        int out = 0;
        for (int i = 0; i < value.length(); i++) {
            int digit = switch (value.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                default -> 0;
            };
            int next = i + 1 < value.length() ? switch (value.charAt(i + 1)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                default -> 0;
            } : 0;
            out += digit < next ? -digit : digit;
        }
        return out;
    }

    /**
     * 这句话里除了「物品名 + 数量」之外**还留着数字**吗？（「要一本深海探索者3附魔书」这种详细要求）
     *
     * <p>留着数字通常意味着玩家在说等级／型号之类的细节，本地匹配器吃不下 —— 这种句子值得问一次 AI。
     */
    static boolean hasLooseDigit(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return false;
        }
        String phrase = itemPhrase(normalized);
        for (int i = 0; i < phrase.length(); i++) {
            char c = phrase.charAt(i);
            if (c >= '0' && c <= '9') {
                return true;
            }
        }
        return false;
    }

    /**
     * 剥掉开头的口水词 / 动词。
     *
     * @param aggressive {@code false}：整句就是一个口水词时**保留**它（「给我」原样留着，
     *                   免得归一化之后变成空串让玩家看到「没听清」）；{@code true}：连它一起剥掉
     *                   （判断"这句话有没有实物名"时用 —— 「给我」不该算一个物品名）
     */
    private static String stripFillers(String text, boolean aggressive) {
        boolean changed = true;
        while (changed) {
            changed = false;
            // 句首已经是口语数量词（「来点」「半组」「全部」…）时就别再剥口水词：
            // FILLERS 里的「来」会把「来点圆石」剥成「点圆石」，数量词就丢了。
            if (!startsWithSpoken(text)) {
                for (String filler : FILLERS) {
                    if (text.startsWith(filler) && (aggressive || text.length() > filler.length())
                            && !startsWithSpoken(text) && !cutsSpoken(text, filler.length())) {
                        text = text.substring(filler.length());
                        changed = true;
                    }
                }
            }
            String trimmed = stripTail(text, aggressive);
            if (!trimmed.equals(text)) {
                text = trimmed;
                changed = true;
            }
        }
        return text;
    }

    /** 句首是不是一个口语数量词：是的话就轮不到口水词来剥。 */
    private static boolean startsWithSpoken(String text) {
        for (Spoken s : SPOKEN) {
            if (text.startsWith(s.word())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把前 {@code len} 个字当口水词剥掉，会不会把某个口语数量词切成两半。
     *
     * <p>例：「给我来点钻石」里的「给我来」正好切在「来点」中间；这种 filler 要跳过，
     * 让循环退而剥更短的「给我」，剩下的「来点钻石」才会被数量词认出来。
     */
    private static boolean cutsSpoken(String text, int len) {
        for (Spoken s : SPOKEN) {
            int i = text.indexOf(s.word());
            if (i > 0 && i < len && i + s.word().length() > len) {
                return true;
            }
        }
        return false;
    }

    /** 句尾口水词（「…还有吗」「…吧」）。 */
    private static final String[] TAIL_FILLERS = {
            "还有吗", "有没有", "有吗", "行吗", "好吗", "谢谢", "吧", "呢", "吗", "啊"
    };

    /** 剥掉句尾的口水词；{@code aggressive=false} 时整句就是一个口水词就留着（同 {@link #stripFillers}）。 */
    private static String stripTail(String text, boolean aggressive) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String filler : TAIL_FILLERS) {
                if (text.endsWith(filler) && (aggressive || text.length() > filler.length())) {
                    text = text.substring(0, text.length() - filler.length());
                    changed = true;
                    break;
                }
            }
        }
        return text;
    }

    /**
     * 从归一化后的口语句子里挖出**物品名部分**：去掉口水词、数量与量词。
     *
     * <p>别名表用这个当键（见 {@code VoiceAlias}）。用整句当键的话，「十个金币」学到的教训
     * 对「五个金币」一点用都没有；挖掉数量之后，同一件东西的各种说法才会落到同一个键上。
     */
    static String itemPhrase(String normalized) {
        String text = normalized == null ? "" : normalized.replace("的", "");
        text = stripFillers(text, true);
        Quantity quantity = findQuantity(text);
        if (quantity != null) {
            String tail = text.substring(quantity.end);
            String head = text.substring(0, quantity.start);
            // 和 best() 一样：数量通常夹在中间，取更长的那一侧当物品名
            text = tail.length() >= head.length() ? tail : head;
            text = stripFillers(text, true);
        }
        // 识别经常把开头的「一」吃掉，只剩一个孤零零的量词（「个不祥之品」）：这种一律丢掉
        while (text.length() >= 3 && UNITS.indexOf(text.charAt(0)) >= 0) {
            text = text.substring(1);
        }
        return text.trim();
    }

    /**
     * 这句话里有没有「实物名」成分：挖掉口水词、数量与量词后还剩至少两个字。
     *
     * <p>这是别名表的卫生门槛：「给」「给我」「一根」这种整句只剩口水词/量词的退化说法
     * 一律不许记住 —— 否则任何人只要说出恰好归一化成「给」的两个字，就会被自动下单。
     */
    static boolean hasSubstance(String normalized) {
        String phrase = itemPhrase(normalized);
        int letters = 0;
        for (int i = 0; i < phrase.length(); i++) {
            if (Character.isLetter(phrase.charAt(i))) {
                letters++;
            }
        }
        return letters >= 2;
    }

    /** 一句话里最多认几件物品：再多就太复杂（提示词、回显、误拆风险都上来了）。 */
    static final int MAX_CLAUSES = 4;

    /**
     * 句子之间的连接词：玩家一句话要好几件东西时用它们把各件连起来
     * （「给我一本背刺附魔书和一张绿色床」）。**按长词优先**扫描，否则「再来」会被「再」先切掉、
     * 「加上」会被「加」先切掉。
     *
     * <p>这里只做**候选切分**：切出来的每一段仍要过完整的本地匹配（相似度 + 库存 + 上限），
     * 切错了最多是某一段认不出来（退回单件流程），**不会下错单**。
     */
    private static final String[] CONNECTORS = {
            "还有", "以及", "另外", "然后", "加上", "再来", "再", "跟", "和", "与", "加",
            "、", "，", "；", "。", ",", ";"
    };

    /**
     * 把一句「要好几件东西」的话拆成各件的小句；**不是多件句时返回空表**（调用方按单件处理）。
     *
     * <p>规则：按 {@link #CONNECTORS} 切开 → 每段重新 {@link #normalize} → 丢掉没有实物名的段
     * （「十个圆石再来一组」里的「一组」不自己成句）→ 至少剩下两段才算多件句，顺序保持玩家说的顺序。
     * 段数上限（{@link #MAX_CLAUSES}）由调用方判断：这里如实返回切出来的段，别替调用方做决定。
     */
    static List<String> clauses(String normalized) {
        String text = normalized == null ? "" : normalized;
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); ) {
            String connector = connectorAt(text, i);
            if (connector != null) {
                parts.add(text.substring(start, i));
                i += connector.length();
                start = i;
            } else {
                i++;
            }
        }
        parts.add(text.substring(start));
        List<String> out = new ArrayList<>(parts.size());
        for (String part : parts) {
            String clause = normalize(part);
            if (!clause.isEmpty() && hasSubstance(clause)) {
                out.add(clause);
            }
        }
        return out.size() >= 2 ? List.copyOf(out) : List.of();
    }

    /** {@code text[at]} 处有没有连接词；有就返回最长的那个（长词优先）。 */
    private static String connectorAt(String text, int at) {
        for (String connector : CONNECTORS) {
            if (text.startsWith(connector, at)) {
                return connector;
            }
        }
        return null;
    }

    /**
     * 在候选里找出最合适的「物品 + 数量」。
     *
     * @param stackSizeOf 物品 id → 一组多少个（拿不到就给 64）
     */
    static Match best(List<Candidate> candidates, String normalized, ToIntFunction<String> stackSizeOf) {
        if (normalized.isEmpty() || candidates.isEmpty()) {
            return null;
        }
        List<Attempt> attempts = new ArrayList<>();
        Quantity quantity = findQuantity(normalized);
        if (quantity != null) {
            if (quantity.end < normalized.length()) {
                add(attempts, candidates, normalized.substring(quantity.end), quantity, stackSizeOf);
            }
            if (quantity.start > 0) {
                add(attempts, candidates, normalized.substring(0, quantity.start), quantity, stackSizeOf);
            }
        }
        add(attempts, candidates, normalized, new Quantity(1, normalized.length(), 0, Kind.COUNT, null), stackSizeOf);

        // 分数打平时选「用上更多字」的那条读法：「不祥之兆3」的 3 是等级，整句读（数量 1）
        // 与拆数量读（数字当 3 个）分数都是 0.80 时，要选整句那条，别把等级当数量。
        Attempt winner = null;
        for (Attempt attempt : attempts) {
            if (winner == null
                    || attempt.match().score() > winner.match().score() + 0.05D
                    || (attempt.match().score() >= winner.match().score()
                        && attempt.phraseLength() > winner.phraseLength())) {
                winner = attempt;
            }
        }
        if (winner == null) {
            return null;
        }
        Match match = winner.match();
        if (match.score() >= STRONG || (match.score() >= WEAK && match.gap() >= MARGIN)) {
            return match;
        }
        return null;
    }

    /** {@link #best} 里的一条读法（短语 + 它的匹配结果）；短语长度用来在分数打平时选更完整的读法。 */
    private record Attempt(Match match, int phraseLength) {
    }

    /** 同音纠错的音节门槛（计划书 §4.2）：相似度 ≥ {@value #SYLLABLE_MIN}、与第二名差距 ≥ {@value #SYLLABLE_GAP}。 */
    static final double SYLLABLE_MIN = 0.72D;
    static final double SYLLABLE_GAP = 0.10D;

    /** 同音纠错的结果：**只用来提示与学习，绝不下单**。 */
    record Hint(Candidate candidate, double score, double gap) {
    }

    /**
     * 闭集匹配失败后的纯拼音扫描（计划书 §4.2 阶段②）：把物品短语转成音节串，与候选名做音节级比较。
     *
     * <p>门槛比 {@link #best} 松（不看字形、单独的 {@value #SYLLABLE_MIN}/{@value #SYLLABLE_GAP} 两道闸），
     * 因为它**不会下单**：命中只用来给一句「是不是想说…」的提示；玩家在 90 秒内改口说对、且指的是
     * 同一件东西时，{@code VoicePhrase} 会把上一句的错法记进别名表，下次直接命中。
     */
    static Hint hint(List<Candidate> candidates, String normalized) {
        if (normalized == null || normalized.isEmpty() || candidates.isEmpty()) {
            return null;
        }
        List<String> phrases = new ArrayList<>();
        String item = itemPhrase(normalized);
        if (!item.isEmpty()) {
            phrases.add(item);
        }
        Quantity quantity = findQuantity(normalized);
        if (quantity != null) {
            if (quantity.end < normalized.length()) {
                String tail = stripFillers(normalized.substring(quantity.end), true);
                if (!tail.isEmpty()) {
                    phrases.add(tail);
                }
            }
            if (quantity.start > 0) {
                String head = stripFillers(normalized.substring(0, quantity.start), true);
                if (!head.isEmpty()) {
                    phrases.add(head);
                }
            }
        }
        phrases.add(normalized);
        Hint winner = null;
        for (String phrase : phrases) {
            // 提示本来就是「给个机会让玩家确认」：用口音折叠后的音节，带口音也能提示出正确的那件
            String[] left = VoicePinyin.syllablesFold(phrase);
            if (left.length < 2) {
                continue;
            }
            Candidate first = null;
            double firstScore = 0.0D;
            double secondScore = 0.0D;
            for (Candidate candidate : candidates) {
                double score = 0.0D;
                for (String form : forms(candidate.name())) {
                    score = Math.max(score, syllableScore(left, VoicePinyin.syllablesFold(form)));
                }
                if (score > firstScore) {
                    secondScore = firstScore;
                    firstScore = score;
                    first = candidate;
                } else if (score > secondScore) {
                    secondScore = score;
                }
            }
            if (first != null && firstScore >= SYLLABLE_MIN && firstScore - secondScore >= SYLLABLE_GAP
                    && (winner == null || firstScore > winner.score())) {
                winner = new Hint(first, firstScore, firstScore - secondScore);
            }
        }
        return winner;
    }

    /** 音节级相似度（无声调）：完全同音算 1，否则取编辑距离与二元组 Dice 里更高的那个。 */
    private static double syllableScore(String[] left, String[] right) {
        int longest = Math.max(left.length, right.length);
        if (longest == 0) {
            return 0.0D;
        }
        if (java.util.Arrays.equals(left, right)) {
            return 1.0D;
        }
        if (longest < 3) {
            // 太短的词又不完全同音就不启用（同 pinyinSimilarity 的闸）：提示也要少而准
            return 0.0D;
        }
        double edit = 1.0D - arrayLevenshtein(left, right) / (double) longest;
        double dice = arrayDice(left, right);
        return Math.max(edit, dice * 0.95D) * 0.98D;
    }

    /** 最像的候选（失败时记下「当时最像的是哪一件」，用来判断跨句记忆是不是同一件东西）。 */
    static Candidate closestCandidate(List<Candidate> candidates, String normalized) {
        Quantity quantity = findQuantity(normalized);
        String phrase = normalized;
        if (quantity != null && quantity.end() < normalized.length()) {
            phrase = normalized.substring(quantity.end());
        }
        Candidate best = null;
        double bestScore = 0.0D;
        for (Candidate candidate : candidates) {
            double score = similarityToForms(phrase, candidate.name());
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * 别名命中时用：别名只负责定位**物品**，数量必须从这句话里重新解析。
     *
     * <p>别名表的键是「物品名部分」（{@code VoiceAlias.remember} 用 {@link #itemPhrase} 挖掉数量与
     * 口水词之后当 key），数量不在键里，所以必须现算 —— 否则「红色玻璃 12 个」会永远只取 1 个。
     */
    static Match byAlias(Candidate candidate, String normalized, ToIntFunction<String> stackSizeOf) {
        Quantity quantity = findQuantity(normalized);
        int unitSize = stackSizeOf == null ? 64 : stackSizeOf.applyAsInt(candidate.id());
        boolean byStack = quantity != null && quantity.byStack() && unitSize > 1;
        int count = quantity == null ? 1 : resolveCount(quantity, unitSize, candidate.count());
        return new Match(candidate, count, unitSize, byStack,
                quantity == null ? null : quantity.source(), 1.0D, 1.0D);
    }

    private static void add(List<Attempt> attempts, List<Candidate> candidates, String phrase, Quantity quantity,
                            ToIntFunction<String> stackSizeOf) {
        Match match = match(candidates, phrase, quantity, stackSizeOf);
        if (match != null) {
            String needle = phrase == null ? "" : phrase.replaceAll("\\s+", "");
            attempts.add(new Attempt(match, needle.length()));
        }
    }

    private static Match match(List<Candidate> candidates, String phrase, Quantity quantity,
                               ToIntFunction<String> stackSizeOf) {
        String needle = phrase == null ? "" : phrase.replaceAll("\\s+", "");
        if (needle.isEmpty()) {
            return null;
        }
        Candidate best = null;
        Candidate secondBest = null;
        double bestScore = 0.0D;
        double second = 0.0D;
        for (Candidate candidate : candidates) {
            // 句尾数字是等级（「不祥之兆1」）却和这一行的等级对不上：宁可当没听出来，也不换一个等级给
            if (levelConflict(needle, candidate.name())) {
                continue;
            }
            double score = similarityToForms(needle, candidate.name());
            if (score > bestScore) {
                second = bestScore;
                secondBest = best;
                bestScore = score;
                best = candidate;
            } else if (score > second) {
                second = score;
                secondBest = candidate;
            }
        }
        if (best == null || bestScore <= 0.0D) {
            return null;
        }
        // 只差注解（等级/时长）的几行、分数又打平（玩家只说「迟缓药水」没带等级）：分不出是哪一瓶，
        // 就**不下单**、交给提示让玩家补一句等级 —— 悄悄挑其中一瓶就是用户 m44678 说的「区分不了」。
        if (secondBest != null && bestScore - second <= 0.001D
                && comparable(baseName(best.name())).equals(comparable(baseName(secondBest.name())))) {
            return null;
        }
        int unitSize = stackSizeOf == null ? 64 : stackSizeOf.applyAsInt(best.id());
        int count = resolveCount(quantity, unitSize, best.count());
        return new Match(best, count, unitSize, quantity.byStack() && unitSize > 1,
                quantity.source(), bestScore, bestScore - second);
    }

    /**
     * 候选名的**比较用**写法：去掉空格与「·」这类分隔符，再把罗马数字等级折成阿拉伯数字。
     *
     * <p>服务端给变体行的名字是「附魔书 · 深海探索者 III」，玩家嘴里是「深海探索者3附魔书」——
     * 不折一下，两边光是写法就要扣掉一大截相似度，本来说得明白的话反而匹配不上。
     */
    static String comparable(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        return romanToDigits(name.replace("·", "").replaceAll("\\s+", ""));
    }

    /**
     * 变体行名字的**干净名字**：服务端给带属性的行拼的是「真实名字 · 组件注解」，
     * 注解那段（有效时长/等级/颜色/药水效果）不是物品名本身。
     *
     * <p>玩家说「迟缓药水」时，要拿它和「迟缓药水」比，而不是被后面「 · 药水：迟缓 IV 0:20」
     * 稀释覆盖率——否则明明说对了，在「是不是同一件东西」那道闸上反而被判成没对上。
     */
    static String baseName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        int cut = name.indexOf(" · ");
        return (cut > 0 ? name.substring(0, cut) : name).trim();
    }

    /** 变体注解里「等级 III」这类写法的等级数字（已折成阿拉伯数字）；没有就返回空串。 */
    private static final Pattern LEVEL = Pattern.compile("(?:等级|级)\\s*([0-9]{1,3})");

    /**
     * 变体行名字里的**等级数字**：服务端把「凶兆等级 III」这类组件注解拼在「 · 」后面
     * （见主模组 {@code Variants.note}）。玩家说「不祥之兆3」时，等级来自这句话，不在物品名里。
     */
    static String levelDigits(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        int cut = name.indexOf(" · ");
        if (cut < 0 || cut + 3 >= name.length()) {
            return "";
        }
        Matcher matcher = LEVEL.matcher(romanToDigits(name.substring(cut + 3)));
        return matcher.find() ? matcher.group(1) : "";
    }

    /**
     * 候选名参与比较的**所有写法**：整名 / 干净名字 / 干净名字+等级数字。
     *
     * <p>用户 m44160：有些名字本身不长，玩家自然只说短的那部分 ——「药水箭」（真名「药箭 · 药水：…」）、
     * 「不祥之兆3」（真名「不祥之瓶 · 凶兆等级 III」）。只拿整名去比，这些说法永远对不上；
     * 把「 · 」前的干净名字、以及「干净名字 + 等级」也当候选写法，短说法就能落到对的那一行上。
     */
    static List<String> forms(String name) {
        String full = comparable(name);
        List<String> out = new ArrayList<>(3);
        if (full.isEmpty()) {
            return out;
        }
        out.add(full);
        String base = comparable(baseName(name));
        if (!base.isEmpty() && !base.equals(full)) {
            out.add(base);
        }
        String level = levelDigits(name);
        if (!base.isEmpty() && !level.isEmpty()) {
            String withLevel = base + level;
            if (!out.contains(withLevel)) {
                out.add(withLevel);
            }
        }
        // 注解里所有数字都当一种写法：「迟缓药水4」（等级 IV）、「烟花火箭2」（飞行 2）、
        // 「附魔书3」（背刺 III）。罗马数字已折成阿拉伯数字，所以玩家说哪种都行。
        for (String digit : noteDigits(name)) {
            String withDigit = base + digit;
            if (!base.isEmpty() && !out.contains(withDigit)) {
                out.add(withDigit);
            }
        }
        return out;
    }

    /** 注解里出现的数字（等级、飞行时间、药水时长…），按出现顺序去重；不含 0（时长里的 0:20 只看 20）。 */
    private static final Pattern NUMBER = Pattern.compile("[0-9]{1,3}");

    /**
     * 变体行注解里的**全部数字**：服务端注解是「凶兆等级 IV」「药水：迟缓 IV 0:20」「烟花：飞行 2」，
     * 玩家说「不祥之兆4」「迟缓药水4」「火箭2」时数字来自这里。罗马数字已折成阿拉伯数字。
     */
    static List<String> noteDigits(String name) {
        if (name == null || name.isEmpty()) {
            return List.of();
        }
        int cut = name.indexOf(" · ");
        if (cut < 0 || cut + 3 >= name.length()) {
            return List.of();
        }
        Matcher matcher = NUMBER.matcher(romanToDigits(name.substring(cut + 3)));
        List<String> out = new ArrayList<>(4);
        while (matcher.find()) {
            String digit = matcher.group();
            if (!digit.equals("0") && !out.contains(digit) && out.size() < 4) {
                out.add(digit);
            }
        }
        return out;
    }

    /** 去掉句尾数字（阿拉伯数字或一个中文数字）后的短语：那是等级/型号或数量，不属于物品名。 */
    private static String stripTrailingDigits(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int end = text.length();
        int start = end;
        while (start > 0 && Character.isDigit(text.charAt(start - 1))) {
            start--;
        }
        if (start < end) {
            return text.substring(0, start);
        }
        char last = text.charAt(end - 1);
        return DIGITS.containsKey(last) ? text.substring(0, end - 1) : text;
    }

    /**
     * 短语与候选名的最大相似度：{@link #forms} 里每种写法都试一遍（短说法就是这么对上的）。
     */
    static double similarityToForms(String needle, String name) {
        double best = 0.0D;
        for (String form : forms(name)) {
            best = Math.max(best, similarity(needle, form));
        }
        // 句尾数字是等级/型号时，数字本身必须是这一行写着的那个：字形与拼音都把数字当噪声
        // （「迟缓药水4」和「迟缓药水1」的拼音完全一样、字形也几乎一样），不单独比数字，
        // 两行就永远打平。写对的按干净名字给分，写不对的降权 —— 别拿另一瓶顶替。
        String said = trailingDigits(needle);
        if (said.isEmpty()) {
            return best;
        }
        String stem = stripTrailingDigits(needle);
        List<String> digits = noteDigits(name);
        if (stem.isEmpty() || digits.isEmpty()) {
            // 名字上没有注解数字：句尾数字是数量（「石头3」），保持原相似度
            return best;
        }
        if (digits.contains(said)) {
            return Math.max(best, similarity(stem, comparable(baseName(name))));
        }
        return best * 0.70D;
    }

    /**
     * 「分不出来」的变体：同一个干净名字下、分数又打平的几行（玩家只说「迟缓药水」「附魔书」，
     * 而仓库里有好几瓶/好几本）。返回排在前面的几行，用来提示玩家补一句等级，而不是随便挑一件。
     */
    static List<Candidate> ambiguous(List<Candidate> candidates, String normalized) {
        String phrase = itemPhrase(normalized == null ? "" : normalized);
        if (phrase.isEmpty()) {
            phrase = normalized == null ? "" : normalized.replaceAll("\\s+", "");
        }
        if (phrase.isEmpty() || candidates.isEmpty()) {
            return List.of();
        }
        Candidate top = null;
        Candidate second = null;
        double topScore = 0.0D;
        double secondScore = 0.0D;
        for (Candidate candidate : candidates) {
            double score = similarityToForms(phrase, candidate.name());
            if (score > topScore) {
                secondScore = topScore;
                second = top;
                topScore = score;
                top = candidate;
            } else if (score > secondScore) {
                secondScore = score;
                second = candidate;
            }
        }
        if (top == null || second == null || topScore - secondScore > 0.01D) {
            return List.of();
        }
        String base = comparable(baseName(top.name()));
        if (!base.equals(comparable(baseName(second.name())))) {
            return List.of();
        }
        List<Candidate> ties = new ArrayList<>(4);
        for (Candidate candidate : candidates) {
            if (ties.size() >= 3) {
                break;
            }
            if (comparable(baseName(candidate.name())).equals(base)
                    && topScore - similarityToForms(phrase, candidate.name()) <= 0.01D
                    && !ties.contains(candidate)) {
                ties.add(candidate);
            }
        }
        return ties;
    }

    /** 一句话/一个短语**结尾**的数字（阿拉伯数字或一个中文数字）：可能是等级，也可能是数量。 */
    static String trailingDigits(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int end = text.length();
        int start = end;
        while (start > 0 && Character.isDigit(text.charAt(start - 1))) {
            start--;
        }
        if (start < end) {
            return text.substring(start, end);
        }
        char last = text.charAt(end - 1);
        Integer digit = DIGITS.get(last);
        return digit == null ? "" : String.valueOf(digit);
    }

    /**
     * 句尾数字与候选行的等级**对不上**（「不祥之兆1」而货架上的那行是「凶兆等级 III」）：
     * 这种句子不能拿别的等级顶替。没有等级信息（两边一方为空）就不算冲突。
     */
    static boolean levelConflict(String text, String name) {
        String said = trailingDigits(text);
        String have = levelDigits(name);
        return !said.isEmpty() && !have.isEmpty() && !said.equals(have);
    }

    /**
     * 把数量语义折算成**实际个数**。
     *
     * @param unitSize 一组多少个
     * @param stock    仓库里这件东西有多少个（「全部」「一半」按它算，并夹在 {@link #ORDER_CAP} 以内）
     */
    private static int resolveCount(Quantity quantity, int unitSize, long stock) {
        int stack = Math.max(1, unitSize);
        long available = Math.min(Math.max(1L, stock), ORDER_CAP);
        return switch (quantity.kind()) {
            // 「两组」= 两整组（findCounted 把 N 组记成 value=N 的 STACK；「整组/满组」的 value=1）
            case STACK -> (int) Math.min(ORDER_CAP, (long) Math.max(1, quantity.value()) * stack);
            case HALF_STACK -> Math.max(1, stack / 2);
            case STACK_AND_HALF -> Math.max(1, quantity.value()) * stack + Math.max(1, stack / 2);
            case FRACTION -> Math.max(1, (int) ((long) stack * quantity.numerator()
                    / Math.max(1, quantity.denominator())));
            case HALF_PIECE -> 1;
            case ALL -> (int) available;
            case HALF_ALL -> (int) Math.max(1L, available / 2);
            case SOME -> stack;
            case COUNT -> Math.max(1, quantity.value());
        };
    }

    /**
     * 扫出句子里的数量：先看口语数量词（半组/全部/来点…），再看数字 + 量词。
     *
     * <p>两者都出现时取**更靠前**的那个；位置相同取口语词（它通常更长、更完整，
     * 例如「一组整」要按口语词吃干净，别把「整」留给物品名）。
     */
    static Quantity findQuantity(String text) {
        Quantity fraction = findFraction(text);
        Quantity spoken = findSpoken(text);
        Quantity counted = findCounted(text);
        if (fraction != null && (spoken == null || fraction.start() <= spoken.start())
                && (counted == null || fraction.start() <= counted.start())) {
            return fraction;
        }
        if (spoken == null) {
            return counted;
        }
        if (counted == null || spoken.start() <= counted.start()) {
            return spoken;
        }
        return counted;
    }

    /**
     * 「四分之一组」「八分之三组」「三分之一组」这类分数个组：{@code 分母 分之 分子 组}。
     *
     * <p>必须先于 {@link #findCounted} 认出整段，否则「四分之一组」会被拆成数字 4 + 后缀「分之一组」，
     * 既算错数量又把「分之一组」留在物品名里。折算成个数时按本地一组多少个做整数除法（64 的 1/4 = 16）。
     */
    private static Quantity findFraction(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (!text.startsWith("分之", i) || i == 0) {
                continue;
            }
            int from = i;
            while (from > 0 && isNumberChar(text.charAt(from - 1))) {
                from--;
            }
            if (from == i) {
                continue;
            }
            int to = i + 2;
            int after = to;
            while (after < text.length() && isNumberChar(text.charAt(after))) {
                after++;
            }
            if (after == to || after >= text.length() || text.charAt(after) != '组') {
                continue;
            }
            int denominator = parseNumber(text.substring(from, i));
            int numerator = parseNumber(text.substring(to, after));
            if (denominator < 2 || denominator > 100 || numerator < 1 || numerator > denominator) {
                continue;
            }
            return new Quantity(0, after + 1, from, Kind.FRACTION, text.substring(from, after + 1),
                    numerator, denominator);
        }
        return null;
    }

    /** 分数写法里允许的字符：阿拉伯数字与中文数字。 */
    private static boolean isNumberChar(char c) {
        return Character.isDigit(c) || DIGITS.containsKey(c) || c == '十' || c == '百' || c == '两' || c == '零';
    }

    /** 把一段中文/阿拉伯数字读成整数；读不出返回 -1。 */
    private static int parseNumber(String text) {
        if (text == null || text.isEmpty()) {
            return -1;
        }
        boolean ascii = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                ascii = false;
                break;
            }
        }
        if (ascii) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        int value = 0;
        int pending = 0;
        boolean any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (DIGITS.containsKey(c)) {
                pending = DIGITS.get(c);
                any = true;
            } else if (c == '两') {
                pending = 2;
                any = true;
            } else if (c == '十') {
                value += (pending == 0 ? 1 : pending) * 10;
                pending = 0;
                any = true;
            } else if (c == '百') {
                value += (pending == 0 ? 1 : pending) * 100;
                pending = 0;
                any = true;
            } else {
                return -1;
            }
        }
        return any ? value + pending : -1;
    }

    /** 口语数量词：扫所有位置，命中词表里排最前的那个（词表已按长词优先排好）。 */
    private static Quantity findSpoken(String text) {
        for (int i = 0; i < text.length(); i++) {
            for (Spoken spoken : SPOKEN) {
                if (text.startsWith(spoken.word(), i)) {
                    return new Quantity(1, i + spoken.word().length(), i, spoken.kind(), spoken.word());
                }
            }
        }
        return null;
    }

    /** 数字（阿拉伯数字或中文数字）+ 量词。 */
    private static Quantity findCounted(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isDigit(c) && !DIGITS.containsKey(c) && c != '十' && c != '百') {
                continue;
            }
            int end = i;
            int value = 0;
            int pending = 0;
            boolean any = false;
            while (end < text.length()) {
                char d = text.charAt(end);
                if (Character.isDigit(d)) {
                    if (pending > 100_000) {
                        // 识别出十几位数字时别让 int 溢出（溢出变负会被后面夹成「1 个」）
                        break;
                    }
                    pending = pending * 10 + Character.digit(d, 10);
                    any = true;
                    end++;
                } else if (DIGITS.containsKey(d)) {
                    pending = DIGITS.get(d);
                    any = true;
                    end++;
                } else if (d == '十') {
                    value += (pending == 0 ? 1 : pending) * 10;
                    pending = 0;
                    any = true;
                    end++;
                } else if (d == '百') {
                    value += (pending == 0 ? 1 : pending) * 100;
                    pending = 0;
                    any = true;
                    end++;
                } else {
                    break;
                }
            }
            if (!any) {
                continue;
            }
            value += pending;
            if (value <= 0) {
                continue;
            }
            if (end < text.length() && UNITS.indexOf(text.charAt(end)) < 0
                    && i > 0 && isHan(text.charAt(i - 1)) && isHan(text.charAt(end))) {
                // 数字夹在两个字中间（「深海探索者3附魔书」「锋利5钻石剑」）：它是名字的一部分
                // （等级 / 型号），不是数量。不然会在这里切断，把「附魔书」当成整个物品名。
                continue;
            }
            Kind kind = Kind.COUNT;
            if (end < text.length() && UNITS.indexOf(text.charAt(end)) >= 0) {
                if (text.charAt(end) == '组') {
                    // 「N 组半」= N 组 + 半组（「一组半」= 1.5 组）
                    if (end + 1 < text.length() && text.charAt(end + 1) == '半') {
                        return new Quantity(value, end + 2, i, Kind.STACK_AND_HALF, null);
                    }
                    kind = Kind.STACK;
                }
                end++;
            }
            return new Quantity(value, end, i, kind, null);
        }
        return null;
    }

    /** 是不是汉字（用来判断数字有没有夹在名字中间）。 */
    private static boolean isHan(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    /**
     * 物品短语和候选名的相似度 = 字形相似度和拼音相似度里更高的那个。
     *
     * <p>字形部分：相同 > 包含（且短的那个要覆盖长的 60% 以上）> 编辑距离 / 双字组。
     * 包含必须加覆盖率门槛，否则「金剑」会因为出现在「下季和金剑」里拿到高分，
     * 把真正想说但被听坏的「下界合金剑」挤掉；「玻璃」同理会把「红色染色玻璃」挤掉。
     */
    static double similarity(String phrase, String name) {
        return Math.max(Math.max(similarityByShape(phrase, name), pinyinSimilarity(phrase, name)),
                accentSimilarity(phrase, name));
    }

    /**
     * 「这句话说的就是这件东西」的证据：字形上整名相同／整名被包含，或者**发音对得上**（同音纠错）。
     *
     * <p>这是「不拿相近的另一件东西顶替」那道闸（{@code VoicePhrase#decide}）。相似度只看「像不像」，
     * 像「橡木原木」和「橡木木板」这种只差一个字的名字能拿到 0.75 的高分，但它们是**两件东西**，
     * 用相似度直接下单就会把木板当原木给玩家。判「是不是同一个」比判「像不像」严格：
     * 名字能对上（相等/包含）或发音几乎相同才算；只是字形相近（「原」和「板」读音差很远）不算。
     */
    static boolean plausible(String phrase, String name) {
        if (phrase == null || name == null) {
            return false;
        }
        String spoken = comparable(phrase);
        if (spoken.isEmpty() || name.isEmpty()) {
            return false;
        }
        String target = comparable(name);
        if (target.isEmpty()) {
            return false;
        }
        if (sameItem(spoken, target)) {
            return true;
        }
        // 带属性/附魔行：再和「 · 」前面的干净名字比一次（时长、等级那些注解不该算进覆盖率）
        String base = comparable(baseName(name));
        return !base.isEmpty() && !base.equals(target) && sameItem(spoken, base);
    }

    /** 同一个名字的字面证据（两边都已 {@link #comparable}）。 */
    private static boolean sameItem(String spoken, String target) {
        if (target.equals(spoken)) {
            return true;
        }
        if (spoken.length() >= 2 && target.contains(spoken)
                && spoken.length() / (double) target.length() >= 0.6D) {
            return true;
        }
        if (target.length() >= 2 && spoken.contains(target)
                && target.length() / (double) spoken.length() >= 0.6D) {
            return true;
        }
        // 同音纠错：pinyinSimilarity 已经把「不到 PINYIN_MIN 就返回 0」这道闸做在里面了
        if (pinyinSimilarity(spoken, target) > 0.0D) {
            return true;
        }
        // 口音：整串音节折叠后完全一致才算同一件（不拿「只是相近」的另一件顶替）
        return foldedEqual(spoken, target);
    }

    /**
     * 玩家原话里（可能是一句话几件东西）有没有一段**确实在说这件东西**。
     *
     * <p>给 AI 契约复判用：模型说「这句要 oak_planks」时，除了「改写句和 oak_planks 对得上」，
     * 还要看**玩家自己说的原话**里有没有一件东西和它同源。否则模型把「橡木原木」理解成「橡木木板」
     * 也会被当成正确答案，等于把 AI 的错当成了本地确认。
     */
    static boolean mentions(String heard, String name) {
        String normalized = normalize(heard);
        if (normalized.isEmpty() || name == null || name.isEmpty()) {
            return false;
        }
        String target = name;
        List<String> clauses = clauses(normalized);
        if (clauses.size() >= 2) {
            for (String clause : clauses) {
                String phrase = itemPhrase(clause);
                if (!phrase.isEmpty() && plausible(phrase, target)) {
                    return true;
                }
            }
            return false;
        }
        String phrase = itemPhrase(normalized);
        return !phrase.isEmpty() && plausible(phrase, target);
    }

    /** 只看字形。 */
    static double similarityByShape(String phrase, String name) {
        if (phrase.isEmpty() || name == null || name.isEmpty()) {
            return 0.0D;
        }
        if (name.equals(phrase)) {
            return 1.0D;
        }
        double coverage;
        if (name.contains(phrase)) {
            coverage = phrase.length() / (double) name.length();
            if (coverage >= 0.6D) {
                return 0.80D + 0.18D * coverage;
            }
        } else if (phrase.contains(name)) {
            coverage = name.length() / (double) phrase.length();
            if (coverage >= 0.6D) {
                return 0.80D + 0.18D * coverage;
            }
        }
        double edit = 1.0D - levenshtein(phrase, name) / (double) Math.max(phrase.length(), name.length());
        double dice = dice(phrase, name);
        return Math.max(edit, dice * 0.95D);
    }

    /**
     * 拼音相似度：按音节比较（无声调）。
     *
     * <p>只要**两边较长的一侧 ≥3 个音节**就启用：识别经常漏掉尾巴（「深板岩」被听成「身板」），
     * 那是 2 对 3，如果卡「两边都 ≥3」就救不回来。同时保留两道闸避免误下单：
     * 太短的词（「东西」对「龙息」）仍然不启用；得分必须 ≥ {@value #PINYIN_MIN}
     * （所以「石头」不会去匹配「石头台阶」这种尾巴差很多的）。
     */
    static double pinyinSimilarity(String phrase, String name) {
        String[] left = VoicePinyin.syllables(phrase);
        String[] right = VoicePinyin.syllables(name);
        int longest = Math.max(left.length, right.length);
        if (longest == 0) {
            return 0.0D;
        }
        String a = String.join(" ", left);
        String b = String.join(" ", right);
        if (a.equals(b)) {
            // 完全同音（沙粒→沙砾、红纱→红沙）：即使只有两个音节也认，这是最可靠的一种证据
            return 1.0D;
        }
        if (longest < 3) {
            // 音节太少又不完全同音就不启用，免得「东西」撞上「龙息」这种误下单
            return 0.0D;
        }
        double edit = 1.0D - arrayLevenshtein(left, right) / (double) longest;
        double diceScore = arrayDice(left, right);
        double score = Math.max(edit, diceScore * 0.95D) * 0.98D;
        return score >= PINYIN_MIN ? score : 0.0D;
    }

    /**
     * 口音容忍的拼音相似度：先按 {@link VoicePinyin#fold} 把平翘舌、n/l、r/l、f/h、前后鼻音折掉再比。
     *
     * <p>只用来**排序和提示**（{@link #similarity}、{@link #hint}）；「是不是同一件东西」的闸
     * （{@link #sameItem}）不用这个分数，而要求折叠后整串音节完全一致，免得相近的物品被顶上来。
     */
    static double accentSimilarity(String phrase, String name) {
        String[] left = VoicePinyin.syllablesFold(phrase);
        String[] right = VoicePinyin.syllablesFold(name);
        int longest = Math.max(left.length, right.length);
        if (longest == 0) {
            return 0.0D;
        }
        String a = String.join(" ", left);
        String b = String.join(" ", right);
        if (a.equals(b)) {
            // 只差口音（四头→石头、南冰→蓝冰）：和精确同音一样可靠，但留一点点分差，让精确写法优先
            return 0.99D;
        }
        if (longest < 3) {
            // 两个音节又只是「折叠后像」：区分度太低，不参与排序
            return 0.0D;
        }
        double edit = 1.0D - arrayLevenshtein(left, right) / (double) longest;
        double diceScore = arrayDice(left, right);
        double score = Math.max(edit, diceScore * 0.95D) * 0.96D;
        return score >= ACCENT_MIN ? score : 0.0D;
    }

    /** 折叠后整串音节是否完全一致（两边都已 {@link #comparable}）。 */
    private static boolean foldedEqual(String spoken, String target) {
        String[] left = VoicePinyin.syllablesFold(spoken);
        if (left.length < 2) {
            return false;
        }
        return java.util.Arrays.equals(left, VoicePinyin.syllablesFold(target));
    }

    private static double arrayDice(String[] a, String[] b) {
        if (a.length < 2 || b.length < 2) {
            return 0.0D;
        }
        List<String> left = new ArrayList<>();
        for (int i = 0; i + 1 < a.length; i++) {
            left.add(a[i] + " " + a[i + 1]);
        }
        List<String> right = new ArrayList<>();
        for (int i = 0; i + 1 < b.length; i++) {
            right.add(b[i] + " " + b[i + 1]);
        }
        int hits = 0;
        List<String> copy = new ArrayList<>(right);
        for (String gram : left) {
            if (copy.remove(gram)) {
                hits++;
            }
        }
        return 2.0D * hits / (left.size() + right.size());
    }

    private static int arrayLevenshtein(String[] a, String[] b) {
        int[] previous = new int[b.length + 1];
        int[] current = new int[b.length + 1];
        for (int j = 0; j <= b.length; j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length; i++) {
            current[0] = i;
            for (int j = 1; j <= b.length; j++) {
                int cost = a[i - 1].equals(b[j - 1]) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length];
    }

    private static double dice(String a, String b) {
        List<String> left = bigrams(a);
        List<String> right = bigrams(b);
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0D;
        }
        List<String> copy = new ArrayList<>(right);
        int hits = 0;
        for (String gram : left) {
            if (copy.remove(gram)) {
                hits++;
            }
        }
        return 2.0D * hits / (left.size() + right.size());
    }

    private static List<String> bigrams(String text) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i + 1 < text.length(); i++) {
            out.add(text.substring(i, i + 2));
        }
        return out;
    }

    static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    /** 调试用：列出最像的几个候选及字形/拼音分别得了多少分（离线探针用，不参与下单）。 */
    static List<String> topMatches(List<Candidate> candidates, String normalized, int limit) {
        Quantity quantity = findQuantity(normalized);
        String phrase = normalized;
        if (quantity != null && quantity.end() < normalized.length()) {
            phrase = normalized.substring(quantity.end());
        }
        List<double[]> scores = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Candidate candidate : candidates) {
            double shape = similarityByShape(phrase, candidate.name());
            double pinyin = pinyinSimilarity(phrase, candidate.name());
            scores.add(new double[]{Math.max(shape, pinyin), shape, pinyin});
            names.add(candidate.name());
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> Double.compare(scores.get(b)[0], scores.get(a)[0]));
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, order.size()); i++) {
            int index = order.get(i);
            rows.add(String.format(java.util.Locale.ROOT, "%s shape=%.2f pinyin=%.2f total=%.2f",
                    names.get(index), scores.get(index)[1], scores.get(index)[2], scores.get(index)[0]));
        }
        return rows;
    }

    /** 最像的几个候选名字（用于「没听清，是不是想说…」提示）。 */
    static List<String> closestNames(List<Candidate> candidates, String normalized, int limit) {
        List<String> names = new ArrayList<>();
        for (String row : topMatches(candidates, normalized, limit)) {
            int cut = row.indexOf(" shape=");
            names.add(cut > 0 ? row.substring(0, cut) : row);
        }
        return names;
    }
}
