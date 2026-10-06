package com.ds.warehouse.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

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
    static final double PINYIN_MIN = 0.60D;

    /** 中文数字。 */
    private static final Map<Character, Integer> DIGITS = Map.ofEntries(
            Map.entry('零', 0), Map.entry('〇', 0),
            Map.entry('一', 1), Map.entry('二', 2), Map.entry('两', 2), Map.entry('俩', 2),
            Map.entry('三', 3), Map.entry('四', 4), Map.entry('五', 5), Map.entry('六', 6),
            Map.entry('七', 7), Map.entry('八', 8), Map.entry('九', 9));

    /** 量词：紧跟数字时一起吃掉；「组」表示一组堆叠。 */
    private static final String UNITS = "个件块组盒桶把张只条根袋箱枚片瓶碗本支颗份堆排串卷";

    /** 口水词 / 动词：从开头反复剥掉。 */
    private static final String[] FILLERS = {
            "请帮我", "帮我", "给我来", "给我", "我想要", "我要", "来点", "来个", "来", "拿点", "拿个", "拿", "取", "要",
            "麻烦", "请", "一下", "谢谢", "多谢", "额", "啊", "嗯", "呃", "那个", "这个", "就是", "然后"
    };

    private VoiceText() {
    }

    /** 候选物品（名字来自仓库快照）。 */
    record Candidate(String id, String name, long count) {
    }

    /** 一次匹配结果：物品、最终数量、一组多少个、是否按「组」算、相似度、与第二名的差距。 */
    record Match(Candidate candidate, int count, int unitSize, boolean byStack, double score, double gap) {
    }

    /** 数量：value=数字、end=在原文中的结束位置、stack=后面跟了「组」。 */
    private record Quantity(int value, int end, int start, boolean stack) {
    }

    /** 去掉空白、口水词与「的」（「红色的玻璃」→「红色玻璃」）。 */
    static String normalize(String heard) {
        String text = heard == null ? "" : heard.replaceAll("\\s+", "");
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String filler : FILLERS) {
                if (text.startsWith(filler) && text.length() > filler.length()) {
                    text = text.substring(filler.length());
                    changed = true;
                }
            }
        }
        return text.replace("的", "");
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
        List<Match> attempts = new ArrayList<>();
        Quantity quantity = findQuantity(normalized);
        if (quantity != null) {
            if (quantity.end < normalized.length()) {
                add(attempts, candidates, normalized.substring(quantity.end), quantity, stackSizeOf);
            }
            if (quantity.start > 0) {
                add(attempts, candidates, normalized.substring(0, quantity.start), quantity, stackSizeOf);
            }
        }
        add(attempts, candidates, normalized, new Quantity(1, normalized.length(), 0, false), stackSizeOf);

        Match winner = null;
        for (Match attempt : attempts) {
            if (winner == null || attempt.score() > winner.score() + 0.05D) {
                winner = attempt;
            }
        }
        if (winner == null) {
            return null;
        }
        if (winner.score() >= STRONG || (winner.score() >= WEAK && winner.gap() >= MARGIN)) {
            return winner;
        }
        return null;
    }

    private static void add(List<Match> attempts, List<Candidate> candidates, String phrase, Quantity quantity,
                            ToIntFunction<String> stackSizeOf) {
        Match match = match(candidates, phrase, quantity, stackSizeOf);
        if (match != null) {
            attempts.add(match);
        }
    }

    private static Match match(List<Candidate> candidates, String phrase, Quantity quantity,
                               ToIntFunction<String> stackSizeOf) {
        String needle = phrase == null ? "" : phrase.replaceAll("\\s+", "");
        if (needle.isEmpty()) {
            return null;
        }
        Candidate best = null;
        double bestScore = 0.0D;
        double second = 0.0D;
        for (Candidate candidate : candidates) {
            double score = similarity(needle, candidate.name());
            if (score > bestScore) {
                second = bestScore;
                bestScore = score;
                best = candidate;
            } else if (score > second) {
                second = score;
            }
        }
        if (best == null || bestScore <= 0.0D) {
            return null;
        }
        int unitSize = stackSizeOf == null ? 64 : stackSizeOf.applyAsInt(best.id());
        int count = quantity.value();
        boolean byStack = quantity.stack() && unitSize > 1;
        if (byStack) {
            count = count * unitSize;
        }
        return new Match(best, count, unitSize, byStack, bestScore, bestScore - second);
    }

    /** 扫出句子里的第一个数字（阿拉伯数字或中文数字），并带上后面的量词。 */
    static Quantity findQuantity(String text) {
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
            boolean stack = false;
            if (end < text.length() && UNITS.indexOf(text.charAt(end)) >= 0) {
                stack = text.charAt(end) == '组';
                end++;
            }
            return new Quantity(value, end, i, stack);
        }
        return null;
    }

    /**
     * 物品短语和候选名的相似度 = 字形相似度和拼音相似度里更高的那个。
     *
     * <p>字形部分：相同 > 包含（且短的那个要覆盖长的 60% 以上）> 编辑距离 / 双字组。
     * 包含必须加覆盖率门槛，否则「金剑」会因为出现在「下季和金剑」里拿到高分，
     * 把真正想说但被听坏的「下界合金剑」挤掉；「玻璃」同理会把「红色染色玻璃」挤掉。
     */
    static double similarity(String phrase, String name) {
        return Math.max(similarityByShape(phrase, name), pinyinSimilarity(phrase, name));
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
