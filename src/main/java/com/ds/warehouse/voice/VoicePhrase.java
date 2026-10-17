package com.ds.warehouse.voice;

import com.ds.warehouse.client.ClientNames;
import com.ds.warehouse.client.ClientSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 第 3 期的 Minecraft 侧接线：把识别文字交给 {@link VoiceText} 解析，够自信就发取货指令。
 *
 * <p>安全阀（宁可不做也不做错）：
 * <ul>
 *     <li>没有取货权限（命令没同步到客户端）→ 不下单，直接说人话；</li>
 *     <li>匹配不到物品、相似度不够、或与第二名太接近 → 不下单，提示重说；</li>
 *     <li>数量超过上限 4096 → 不下单（和面板、命令一致）；</li>
 *     <li>按本地快照看库存不够 → 不下单并说明（服务端还会再校验一次）；</li>
 *     <li>还没收到仓库数据 → 不下单，提示先打开一次面板。</li>
 * </ul>
 *
 * <p>隐私：这里只处理文字，不碰音频；下单只发一条普通的 {@code warehouse order}，
 * 和玩家手动点「取货」发的是同一条命令。
 */
final class VoicePhrase {

    private static final Logger LOG = LoggerFactory.getLogger("warehouse-keeper-voice");

    /** 与主体模组一致的单次下单上限。 */
    private static final int MAX_ORDER = 4096;
    /** 上一句没听出来的话 + 当时最像的物品 id（用于「下次说对同一件东西就把它记住」）。 */
    private static String lastFailed;
    private static String lastFailedId;
    private static long lastFailedAt;
    /** 超过这么久就不再关联上一句失败（毫秒）。 */
    private static final long FAILED_MEMORY_MS = 90_000L;

    private VoicePhrase() {
    }

    /** 一次判定的结果：匹配到的物品、给玩家看的一句话、是否可以真的下单、是不是「没听出物品名」。 */
    private record Decision(VoiceText.Match match, String message, boolean order, boolean noMatch) {
        static Decision refuse(String message) {
            return new Decision(null, message, false, false);
        }

        /** 没听出物品名：这种情况可以换个模型的识别文本再判一次。 */
        static Decision noMatch(String message) {
            return new Decision(null, message, false, true);
        }
    }

    /**
     * 处理一次识别结果：能下单就下单，返回给玩家看的一句反馈。
     *
     * <p>两个模型是**一起工作**的：先用主模型的结果判；主模型完全没听出物品名时，拿另一个模型的
     * 结果再判一次，判出来就下单（并把主模型听错的那句话记进别名表，下次同样的错法直接命中）。
     * 权限、没收到快照、库存不够这类拒绝换哪个模型都一样，直接说人话，不会因为换个模型就蒙对。
     */
    static String handle(SherpaEngine.Line primary, List<SherpaEngine.Line> others) {
        return handle(primary, others, null);
    }

    /**
     * AI 归一化的结果（第 ② 步）：**本地复判通过**之后才有这个对象，里面全是本地算出来的数，
     * 物品与数量都以它为准（模型的 {@code amount} 只当语义提示，真正的个数在本地折算）。
     *
     * @param match  本地匹配结果（已经不是模型的原始回答）
     * @param want   最终下单个数（本地数量换算的产物，已夹到 4096 以内）
     * @param text   AI 改写后的那句中文（给玩家看「AI 帮了什么」）
     * @param itemId 模型点的物品 id（仅用于日志/提示，实际下单用 {@code match.candidate().id()}）
     */
    record Rewrite(VoiceText.Match match, int want, String text, String itemId) {
    }

    /**
     * 同上，但先试一次 **AI 归一化后的文本**（第 ② 步 VoiceRewrite）。
     *
     * <p>只在本地的原句「没听出物品名」时才用得上：AI 只是把口语改写成更标准的说法，
     * 最后仍然要过一遍完全相同的本地匹配器（闭集 + 相似度 + 库存），本地不认就不发命令。
     *
     * @param rewrite AI 复判结果；{@code null} 表示没调/没答/本地不认
     */
    static String handle(SherpaEngine.Line primary, List<SherpaEngine.Line> others, Rewrite rewrite) {
        String heard = primary.text();
        Decision decision = decide(heard, true);
        SherpaEngine.Line rescuedBy = null;
        boolean byAi = rewrite != null && rewrite.match() != null;
        if (byAi) {
            // AI 契约在 VoiceSession 里已经过了一遍本地复判（matchRewrite + AIPinnedItemId + mentions），
            // 这里直接按它下单：玩家选「完全调用 AI 改写」时就是要 AI 这一份结果。
            decision = new Decision(rewrite.match(), "", true, false);
        }
        if (!decision.order() && decision.noMatch()) {
            for (SherpaEngine.Line other : others) {
                String text = other.text();
                if (text == null || text.isEmpty() || text.equals(heard)) {
                    continue;
                }
                Decision alt = decide(text, true);
                if (alt.order() && alt.match() != null) {
                    decision = alt;
                    rescuedBy = other;
                    // 主模型这句话以后就该听懂了
                    VoiceAlias.remember(heard, alt.match().candidate().id());
                    break;
                }
            }
            if (rescuedBy == null && !byAi) {
                return decision.message();
            }
        }
        VoiceText.Match match = decision.match();
        if (match == null) {
            return decision.message();
        }
        VoiceText.Candidate item = match.candidate();
        int want = byAi ? Math.max(1, rewrite.want()) : Math.max(1, match.count());
        send("warehouse order " + item.id() + " " + want);
        LOG.info("语音下单：物品={} 数量={} 相似度={}{}", item.id(), want,
                String.format(Locale.ROOT, "%.2f", match.score()),
                byAi ? "（AI 归一化：" + rewrite.text() + "）"
                        : rescuedBy == null ? "" : "（" + rescuedBy.label() + " 顶上）");
        String note = byAi ? "（AI）" : rescuedBy == null ? "" : "（记住了）";
        return "已取货 " + want + " 个 " + item.name() + note;
    }

    /** 只判断、不下单：显示另一个模型「会取什么」用，**不写任何状态**（别名表/上一句失败）。 */
    static String preview(String heard) {
        List<String> clauses = VoiceText.clauses(VoiceText.normalize(heard));
        if (clauses.size() >= 2) {
            Split split = splitLocal(clauses, false);
            List<String> parts = new ArrayList<>(split.orders().size());
            for (Rewrite order : split.orders()) {
                if (order == null || order.match() == null) {
                    parts.add("（没听出来）");
                } else {
                    parts.add(order.want() + " 个 " + order.match().candidate().name());
                }
            }
            return "多件：" + String.join(" + ", parts);
        }
        return decide(heard, false).message();
    }

    /**
     * 这句话是不是「一句话要好几件东西」（能按「和／还有／、」拆出两段以上）。
     *
     * <p>给 {@link VoiceSession} 决定走单件流程还是 {@link #handleMulti} 用。
     */
    static boolean isMulti(String heard) {
        return VoiceText.clauses(VoiceText.normalize(heard)).size() >= 2;
    }

    /**
     * 多件句子的落点：一句话里说了好几件东西（用「和／还有／、」连起来）时走这里。
     *
     * <p>优先级**本地优先**（本地认全了就不麻烦 AI，也不信 AI）：
     * <ol>
     *     <li>本地把每一件都认出来了 → 直接按本地结果下单（和单件流程同一套安全阀）；</li>
     *     <li>本地漏了但 AI 归一化给出了合法多件契约（{@code aiOrders}）→ 按 AI 的契约下单，
     *         但每条契约都已经在 {@link VoiceSession} 里**又过了一遍本地复判**，这里只负责执行；</li>
     *     <li>AI 也没帮上 → 换另一个识别模型的结果再拆一次；还不行就按本地认出来的那些下，
     *         并告诉玩家还有几件没听出来。</li>
     * </ol>
     * 任何情况下都**不会**把没认出来的东西猜着下单。
     */
    static String handleMulti(SherpaEngine.Line primary, List<SherpaEngine.Line> others,
                              List<Rewrite> aiOrders) {
        String heard = primary == null || primary.text() == null ? "" : primary.text();
        if (!canOrder()) {
            return "没有取货权限（找管理员要 take 权限）";
        }
        if (!ClientSnapshot.hasData()) {
            return "还没收到仓库数据（先打开一次仓库面板）";
        }
        if (candidates().isEmpty()) {
            return "仓库里没有可取的东西";
        }
        List<String> clauses = VoiceText.clauses(VoiceText.normalize(heard));
        if (clauses.size() > VoiceText.MAX_CLAUSES) {
            return "一次最多说 " + VoiceText.MAX_CLAUSES + " 件东西，请分开说";
        }
        if (clauses.size() < 2) {
            // 拆句结果和调用方判断不一致（极端情况）：老老实实退回单件流程
            return handle(primary, others, aiOrders == null || aiOrders.isEmpty() ? null : aiOrders.get(0));
        }
        Split local = splitLocal(clauses, true);
        if (local.complete()) {
            return execute(local.orders(), "");
        }
        List<Rewrite> ai = aiOrders == null ? List.of() : aiOrders;
        if (!ai.isEmpty()) {
            // 本地已认出来的那几件 + AI 补齐的那几件一起下：只信 AI 会丢掉本地已经确定的那件
            // （例如本地听出「背刺附魔书」、AI 只回了「绿色床」一行）。同一件东西只算一次，数量以本地为准。
            List<Rewrite> merged = new ArrayList<>(local.matched() + ai.size());
            Set<String> ids = new LinkedHashSet<>();
            for (Rewrite order : local.orders()) {
                if (order != null && order.match() != null && ids.add(order.match().candidate().id())) {
                    merged.add(order);
                }
            }
            for (Rewrite order : ai) {
                if (order != null && order.match() != null && ids.add(order.match().candidate().id())) {
                    merged.add(order);
                }
            }
            String note = "（AI）"
                    + (merged.size() < clauses.size()
                            ? "（还有 " + (clauses.size() - merged.size()) + " 件没对上："
                                    + missReasons(clauses, merged) + "）" : "");
            return execute(merged, note);
        }
        // AI 没帮上：换另一个识别模型的文本再拆一次（只在它能整句认全时才用）
        for (SherpaEngine.Line other : others) {
            String text = other.text();
            if (text == null || text.isEmpty() || text.equals(heard)) {
                continue;
            }
            List<String> altClauses = VoiceText.clauses(VoiceText.normalize(text));
            if (altClauses.size() != clauses.size()) {
                continue;
            }
            Split alt = splitLocal(altClauses, false);
            if (alt.complete()) {
                String note = "（记住了）";
                return execute(alt.orders(), note);
            }
        }
        if (local.matched() > 0) {
            return execute(local.orders(),
                    "（还有 " + local.missing() + " 件没对上：" + missReasons(clauses, local.orders()) + "）");
        }
        return "没取货：" + missReasons(clauses, local.orders()) + "——每件分开说一遍";
    }

    /** 多件拆句的结果：小句列表 + 与之一一对应的本地取货（本地没认出来的那一件是 {@code null}）。 */
    private record Split(List<String> clauses, List<Rewrite> orders, double worst) {
        int matched() {
            int count = 0;
            for (Rewrite order : orders) {
                if (order != null) {
                    count++;
                }
            }
            return count;
        }

        int missing() {
            return clauses.size() - matched();
        }

        boolean complete() {
            return !clauses.isEmpty() && missing() == 0;
        }

        /** 每一件都认出来、而且**每一件都有把握**（都到满分线）才算「本地真听懂了」。 */
        boolean confident() {
            return complete() && worst >= FULL_SCORE;
        }
    }

    /** 把每件小句各自走一遍本地匹配（{@code commit=false} 时不写别名/上一句失败）。 */
    private static Split splitLocal(List<String> clauses, boolean commit) {
        List<Rewrite> orders = new ArrayList<>(clauses.size());
        double worst = 1.0D;
        for (String clause : clauses) {
            Decision decision = decide(clause, commit);
            if (decision.order() && decision.match() != null) {
                VoiceText.Match match = decision.match();
                int want = Math.min(MAX_ORDER, Math.max(1, match.count()));
                orders.add(new Rewrite(match, want, clause, match.candidate().id()));
                worst = Math.min(worst, match.score());
            } else {
                orders.add(null);
                worst = 0.0D;
            }
        }
        return new Split(List.copyOf(clauses), List.copyOf(orders), worst);
    }

    /** 把各件的取货请求合并后真的发命令（同一件东西说两遍就数量相加）。 */
    private static String execute(List<Rewrite> orders, String note) {
        Map<String, VoiceText.Match> matches = new LinkedHashMap<>();
        Map<String, Integer> wants = new LinkedHashMap<>();
        for (Rewrite order : orders) {
            if (order == null || order.match() == null) {
                continue;
            }
            String id = order.match().candidate().id();
            matches.putIfAbsent(id, order.match());
            wants.merge(id, Math.max(1, order.want()), Integer::sum);
        }
        List<String> sent = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : wants.entrySet()) {
            VoiceText.Match match = matches.get(entry.getKey());
            VoiceText.Candidate item = match.candidate();
            int want = entry.getValue();
            if (want > MAX_ORDER) {
                refused.add("「" + item.name() + "」超过上限 " + MAX_ORDER + " 个");
                continue;
            }
            if (item.count() < want) {
                refused.add("「" + item.name() + "」只有 " + item.count() + " 个（要 " + want + " 个）");
                continue;
            }
            send("warehouse order " + item.id() + " " + want);
            LOG.info("语音下单（多件）：物品={} 数量={} 相似度={}", item.id(), want,
                    String.format(Locale.ROOT, "%.2f", match.score()));
            sent.add(want + " 个 " + item.name());
        }
        StringBuilder message = new StringBuilder();
        if (sent.isEmpty()) {
            message.append("没有下单");
        } else {
            message.append("已取货：").append(String.join("、", sent));
        }
        if (note != null && !note.isEmpty()) {
            message.append(note);
        }
        for (String line : refused) {
            message.append("；").append(line);
        }
        return message.toString();
    }

    /** 多件回显用：各件 AI 改写句连起来（只用于显示，绝不当命令）。 */
    private static String overrideOf(List<Rewrite> orders) {
        StringBuilder builder = new StringBuilder();
        for (Rewrite order : orders) {
            if (order == null || order.text() == null || order.text().isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("，");
            }
            builder.append(order.text().trim());
        }
        return builder.toString();
    }

    /** 截断到最多 {@code max} 个字符（提示里别把整句 ASR 原文塞给玩家）。 */
    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String clean = text.trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "…";
    }

    /** 候选闭集的**不可变快照**：主线程取好再交给识别 worker，避免 worker 去读客户端快照。 */
    static List<VoiceText.Candidate> candidatesSnapshot() {
        return List.copyOf(candidates());
    }

    /**
     * 只判一次、**不写任何状态**（别名表、上一句失败都不动），给识别 worker 用：
     * worker 里先看看本地认不认，不认才值得花一次网络调用去问 AI。
     */
    static boolean peek(String heard) {
        List<String> clauses = VoiceText.clauses(VoiceText.normalize(heard));
        if (clauses.size() >= 2) {
            return splitLocal(clauses, false).complete();
        }
        Decision decision = decide(heard, false);
        return decision.order() && decision.match() != null;
    }

    /** 本地对一句话的判断：认没认出来、像到什么程度、是不是「带细节的详细要求」。 */
    record Local(boolean recognized, double score, boolean detailed) {
    }

    /** 本地匹配的满分线：低于它说明这句话里有本地没吃透的部分（通常是等级／型号之类的细节）。 */
    static final double FULL_SCORE = 0.95D;

    /**
     * 给 AI 归一化决定「这句值不值得问一次」用的本地判断（只判、不写状态）。
     *
     * <p>认不出来 → 值得问。认出来了但**带着本地吃不下的细节**（「要一本深海探索者3附魔书」
     * 这种带等级／型号的详细要求）→ 也值得问：玩家要的是具体那一种，本地只认到「附魔书」
     * 这一步等于没听全，直接下单会拿错那一本。
     */
    static Local inspect(String heard) {
        List<String> clauses = VoiceText.clauses(VoiceText.normalize(heard));
        if (clauses.size() >= 2) {
            // 多件句：本地**每一件都有把握**才不问 AI；漏了件 OR 有一件只是「勉强够线」时，
            // 都值得问一次（「橡木原木」被凑成「橡木木板」这种正要靠 AI 兜底）。
            Split split = splitLocal(clauses, false);
            boolean all = split.complete();
            return new Local(all, all ? split.worst() : 0.0D, !split.confident());
        }
        Decision decision = decide(heard, false);
        boolean recognized = decision.order() && decision.match() != null;
        double score = recognized ? decision.match().score() : 0.0D;
        boolean detailed = VoiceText.hasLooseDigit(VoiceText.normalize(heard))
                || (recognized && score < FULL_SCORE);
        return new Local(recognized, score, detailed);
    }

    /**
     * 把 AI 归一化器的回答**在本地复判一次**（第 ② 步的落点）。
     *
     * <p>这是「AI 不直接下单」这条铁律的兑现处：模型给的那句中文必须和玩家自己说的话走
     * **完全一样的**本地匹配（闭集候选 + 相似度 + 库存 + 上限），本地认了才有返回值。
     * 认不出就返回 {@code null}，调用方照原样给玩家本地那句「没听出物品名」的提示 ——
     * 模型犯错的最坏结果只是「没帮上忙」。
     *
     * <p>数量不采信模型的回答：取「本地语料折算出的数」与「模型 {@code amount} 折算出的数」
     * 里更小的那个（人话：「三组」被改写成「来一组」时，按本地那句一组走）。
     * 两个数都由本地算，模型的乘法永远不参与。
     *
     * <p><b>只在识别 worker 线程调用</b>，不碰界面、不发命令。
     *
     * @param heard     ASR 原文（用来给玩家回显「AI 帮忙把这句话听成了什么」）
     * @param sentence  模型改写后的句子，或 {@code item + amount} 合成句
     * @param wanted    {@link VoiceRewrite.Answer#count()}：模型 amount 在本地折算出的个数；≤0 表示没有
     * @return 复判通过的 {@link Rewrite}；本地不认则 null
     */
    static Rewrite matchRewrite(String heard, String sentence, int wanted) {
        return matchRewrite(heard, sentence, wanted, null);
    }

    /**
     * 同上，外加 AI 契约里点名的物品 id：模型明确说「这句要的是 X」时，按 X 定位，
     * 但 X 仍要过和玩家原话一模一样的两道闸——闭集内、且这句文字和 X 对得上（{@link #plausible}）。
     * 这样「橡木原木」不会被本地模糊匹配挑成「橡木木板」；模型点名和本地都对不上就照没听出处理。
     */
    static Rewrite matchRewrite(String heard, String sentence, int wanted, String pinnedItemId) {
        if (sentence == null || sentence.isBlank()) {
            return null;
        }
        Decision decision = decide(heard, sentence, null, pinnedItemId, false);
        if (!decision.order() || decision.match() == null) {
            return null;
        }
        VoiceText.Match match = decision.match();
        // 模型点名的那件，必须能在**玩家原话**里找到同源的一段；否则就是模型把「原木」听成了「木板」，
        // 本地再自洽也不能下单（模型犯错的最坏结果只能是「没帮上忙」）。
        if (pinnedItemId != null && !VoiceText.mentions(heard, match.candidate().name())) {
            return null;
        }
        int local = Math.max(1, match.count());
        int want = wanted > 0 ? Math.min(local, Math.max(1, wanted)) : local;
        return new Rewrite(match, Math.min(MAX_ORDER, want), sentence.trim(), match.candidate().id());
    }

    private static Decision decide(String heard, boolean commit) {
        return decide(heard, heard, null, null, commit);
    }

    /**
     * 用 {@code override}（AI 归一化后的句子）重判一次：别名表仍查**原句**，物品定位与数量解析
     * 全用改写句。{@code heard} 保留下来只为了在提示与日志里显示玩家原来说的是什么。
     */
    private static Decision decide(String heard, String text0, List<VoiceText.Candidate> snapshot, String pinnedItemId,
                                   boolean commit) {
        if (!canOrder()) {
            return Decision.refuse("没有取货权限（命令没同步给你）：让管理员在仓库面板「权限」页给你 take 权限，或 /op 你");
        }
        if (!ClientSnapshot.hasData()) {
            return Decision.refuse("还没收到仓库数据，先打开一次仓库面板再试");
        }
        List<VoiceText.Candidate> candidates = snapshot != null ? snapshot : candidates();
        if (candidates.isEmpty()) {
            return Decision.refuse("仓库里没有可取的东西");
        }
        String text = VoiceText.normalize(text0);
        if (text.isEmpty()) {
            return Decision.refuse("没听清，请再说一次");
        }
        // ① 先查别名表（自动记住的 + 玩家手写的），命中就直接用——这样「以后才会出现的偏差」
        //    只要被纠正过一次，之后就一直是对的。别名只负责**定位物品**，数量仍从这句话里解析
        //    （别名键只含物品名，不含数量；数量永远从这一句里现算）。
        VoiceText.Match match = null;
        // 别名表命中的是玩家自己教过的说法，直接信；模糊匹配/模型点名要再过一道「同一件东西」的闸
        boolean trusted = false;
        String aliasId = VoiceAlias.lookup(text);
        if (aliasId != null) {
            VoiceText.Candidate aliased = find(candidates, aliasId);
            if (aliased != null) {
                match = VoiceText.byAlias(aliased, text, VoicePhrase::itemStackSize);
                trusted = match != null;
            }
        }
        // ①' AI 明确点名了物品（item=…）时**优先**按它定位，但必须落在闭集内、且句子本身也对得上：
        //     模型说 item=X 而这句文字和 X 一点都不像（相似度低于 WEAK）时不信它。
        if (match == null && pinnedItemId != null) {
            VoiceText.Candidate pinned = find(candidates, pinnedItemId);
            if (pinned != null) {
                VoiceText.Match byName = VoiceText.byAlias(pinned, text, VoicePhrase::itemStackSize);
                if (byName != null && byName.score() >= VoiceText.WEAK) {
                    match = byName;
                }
            }
        }
        // ② 再走闭集模糊匹配（字形 + 拼音）
        if (match == null) {
            match = VoiceText.best(candidates, text, VoicePhrase::itemStackSize);
        }
        // ②' 「相近」不等于「同一件」：仓库里只有「橡木木板」时，说「橡木原木」不能拿木板顶替
        //     （两件东西只差一个字，相似度却能有 0.75）。别名表命中（玩家自己教过）直接信；
        //     其余凡是没到满分线、又拿不出「同一个东西」的证据的，一律当没听出来——宁可这单不下，
        //     也不要下成另一件东西：玩家要的是原木，给一箱木板毫无用处。
        boolean substituted = false;
        if (match != null && !trusted && match.score() < FULL_SCORE) {
            String phrase = VoiceText.itemPhrase(text);
            // 「不祥之兆3」里的 3 是等级：说 1 却只有 3 的货时，不能拿别的等级顶替
            if (phrase.isEmpty()
                    || !VoiceText.plausible(phrase, match.candidate().name())
                    || VoiceText.levelConflict(text, match.candidate().name())) {
                match = null;
                substituted = true;
            }
        }
        if (match == null) {
            // 记住这句没听出来的话，以及**当时最像的是哪一件**：只有下次说对、且指的还是同一件
            // 东西时才写进别名表（否则「下界合金剑」失败后说「石头」会把剑永久绑成石头）。
            if (commit) {
                lastFailed = text;
                lastFailedAt = System.currentTimeMillis();
                VoiceText.Candidate closest = VoiceText.closestCandidate(candidates, text);
                lastFailedId = closest == null ? null : closest.id();
            }
            if (substituted) {
                return Decision.noMatch(missText(candidates, text, heard));
            }
            // 同一个干净名字下有好几种变体（两瓶药水、两本附魔书、两瓶不祥之瓶）：玩家没说是哪一种，
            // 分不出来就**列出来让他补一句等级**，绝不能悄悄挑其中一瓶（用户 m44678「区分不了」）。
            List<VoiceText.Candidate> ties = VoiceText.ambiguous(candidates, text);
            if (!ties.isEmpty()) {
                StringBuilder sb = new StringBuilder("仓库里有 ")
                        .append(ties.size()).append(" 种差不多的东西，说清楚是哪一种：");
                for (int i = 0; i < ties.size(); i++) {
                    sb.append(i == 0 ? "" : i == ties.size() - 1 ? "、或者 " : "、");
                    sb.append(ties.get(i).name());
                }
                return Decision.noMatch(sb.toString());
            }
            // 阶段②：闭集匹配失败后再做一轮**纯拼音**扫描，只给提示、绝不下单（计划书 §4.2）。
            // 玩家在 90 秒内改口说对、且指的是同一件东西时，上面记下的 lastFailed 会被写进别名表。
            VoiceText.Hint hint = VoiceText.hint(candidates, text);
            if (hint != null) {
                String phrase = VoiceText.itemPhrase(text);
                return Decision.noMatch("没听出「" + clip(phrase.isEmpty() ? heard : phrase, 24)
                        + "」；是想说「" + hint.candidate().name() + "」吗（同音 "
                        + String.format(Locale.ROOT, "%.2f", hint.score()) + "）？");
            }
            return Decision.noMatch(missText(candidates, text, heard));
        }
        // 靠模糊匹配救回来、而且**匹配得比较有把握**（达到 STRONG）才记进别名表。
        // 只在 WEAK 档勉强够线的那种（0.35~0.55）不记：猜错了会永久绑错物品，比不记更糟。
        if (commit && match.score() >= VoiceText.STRONG && match.score() < 0.95D) {
            VoiceAlias.remember(heard, match.candidate().id());
        }
        // 上一句没听出来、这一句说对了，**且两次指的是同一件东西**时，才把上一句的错法也记住。
        // 不校验同一件东西的话，上一句无关的话会被永久绑到这次的物品上（以后直接下错单）。
        if (commit && lastFailed != null && lastFailedId != null
                && lastFailedId.equals(match.candidate().id())
                && System.currentTimeMillis() - lastFailedAt <= FAILED_MEMORY_MS
                && !lastFailed.equals(text)) {
            VoiceAlias.remember(lastFailed, match.candidate().id());
        }
        if (commit) {
            lastFailed = null;
            lastFailedId = null;
        }
        int want = Math.max(1, match.count());
        String what = want + " 个 " + match.candidate().name();
        if (want > MAX_ORDER) {
            return Decision.refuse("一次最多 " + MAX_ORDER + " 个。");
        }
        if (match.candidate().count() < want) {
            return Decision.refuse("「" + match.candidate().name() + "」只有 "
                    + match.candidate().count() + " 个（要 " + want + " 个）。");
        }
        return new Decision(match, "取货 " + what
                + String.format(Locale.ROOT, "（相似度 %.2f）", match.score()), true, false);
    }

    /**
     * 「没对上」的准确说法：能听出物品名时，说**仓库里可能没有这件东西**（顺便报最接近的），
     * 而不是一律报「没听出物品名」——玩家明明说出一个词、只是仓库里没有，报成「没识别」
     * 会让人以为语音根本没听见（用户 m42418 反馈）。
     */
    private static String missText(List<VoiceText.Candidate> candidates, String text, String heard) {
        String normalized = VoiceText.normalize(text);
        String phrase = VoiceText.itemPhrase(normalized);
        String names = String.join(" / ", VoiceText.closestNames(candidates, text, 3));
        if (!phrase.isEmpty() && VoiceText.hasSubstance(normalized)) {
            return "没找到「" + clip(phrase, 24) + "」（可能仓库没有，或没听准）；最接近：" + names;
        }
        return "没听出「" + clip(heard, 24) + "」；最接近：" + names;
    }

    /** 多件句里没对上的一段：能看出是在说物品名就说「仓库可能没有」，看不出才说「没听清」。 */
    private static String clauseMiss(String clause) {
        String normalized = VoiceText.normalize(clause);
        String phrase = VoiceText.itemPhrase(normalized);
        if (!phrase.isEmpty() && VoiceText.hasSubstance(normalized)) {
            return "「" + clip(phrase, 16) + "」没对上";
        }
        return "「" + clip(clause, 16) + "」没听清";
    }

    /** 多件句里没认出来的那几件，逐件说原因（一件都没认出来时也是这句话）。 */
    private static String missReasons(List<String> clauses, List<Rewrite> orders) {
        List<String> reasons = new ArrayList<>(clauses.size());
        for (int i = 0; i < clauses.size(); i++) {
            if (i < orders.size() && orders.get(i) != null) {
                continue;
            }
            reasons.add(clauseMiss(clauses.get(i)));
        }
        return String.join("、", reasons);
    }

    /**
     * AI 归一化后的**复判**（§5.1 最后一道闸）：物品与数量都必须在本地重新算一遍，
     * 本地不认就不下单 —— {@code overrideCount} 是本地从改写句里解析出来的明确数量（例如「半组」→32）。
     *
     * <p>刻意走这条独立的小路，而不是改 {@link #decide(String, boolean)}：这条路不允许
     * 「候选快照为空时自己去读客户端快照」，也**不受玩家说错话就记别名**的影响。
     *
     * @return {@code null} 表示本地不认这个改写结果（调用方当没答）
     */
    static VoiceText.Match matchOverride(String heard, String override, String pinnedItemId,
                                         List<VoiceText.Candidate> snapshot, int overrideCount) {
        try {
            Decision decision = decide(heard, override, snapshot, pinnedItemId, false);
            if (!decision.order() || decision.match() == null) {
                return null;
            }
            VoiceText.Match match = decision.match();
            int want = overrideCount > 0 ? overrideCount : Math.max(1, match.count());
            if (want > MAX_ORDER) {
                return null;
            }
            VoiceText.Candidate candidate = find(snapshot, match.candidate().id());
            if (candidate == null || candidate.count() < want) {
                return null; // 没库存 / 不在快照里：本地不认
            }
            if (match.count() == want) {
                return match;
            }
            return new VoiceText.Match(match.candidate(), want, match.unitSize(), match.byStack(),
                    match.source(), match.score(), match.gap());
        } catch (Throwable t) {
            LOG.debug("AI 归一化复判异常，按不认处理：{}", t.toString());
            return null;
        }
    }

    /** 在候选里按物品 id 找一个（别名表里存的是 id）。 */
    private static VoiceText.Candidate find(List<VoiceText.Candidate> candidates, String itemId) {
        for (VoiceText.Candidate candidate : candidates) {
            if (candidate.id().equals(itemId)) {
                return candidate;
            }
        }
        return null;
    }

    /** 一条取货指令，和玩家在面板点「取货」发的是同一条命令。 */
    private static void send(String command) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.connection == null) {
            return;
        }
        mc.player.connection.sendCommand(command);
    }

    /**
     * 客户端命令树里有没有 {@code warehouse order}。
     *
     * <p>没权限的玩家看到的是「错误的命令参数」这种看不懂的提示（命令节点根本没同步给他），
     * 所以先在这里判断一下，直接说人话。
     */
    private static boolean canOrder() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.player.connection == null) {
                return false;
            }
            var warehouse = mc.player.connection.getCommands().getRoot().getChild("warehouse");
            return warehouse != null && warehouse.getChild("order") != null;
        } catch (Throwable t) {
            // 拿不到命令树时保守拒绝（fail-closed）：发一条注定报错的命令，不如直接说人话
            LOG.debug("命令树查询失败，按无权限处理：{}", t.toString());
            return false;
        }
    }

    /**
     * 仓库里真实有的物品（按 id 合并各区域数量）。
     *
     * <p>带组件的物品（药水／附魔书／烟花…）在快照里每种组件组合**各占一行**，用
     * {@code item.variant}（语言无关的变体键）区分。不区分的话「迟缓药水」这种说法永远匹配不到
     * 东西——快照里只有「药水」那一行。所以：有变体键的行按**变体键**建候选，名字取变体行的**完整**
     * 服务端名字（「迟缓药水 · 药水: Slowness IV 0:20」「附魔书 · 深海探索者 III」），下单时把变体键
     * 填进取货框——服务端 {@code Porter.resolveEx} 第一步就认它，不会拿另一种药水顶包。
     */
    private static List<VoiceText.Candidate> candidates() {
        Map<String, long[]> merged = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, String> plainIds = new LinkedHashMap<>();
        for (ClientSnapshot.Region region : ClientSnapshot.regions()) {
            for (ClientSnapshot.Item item : region.items) {
                if (item.id == null || item.id.isEmpty()) {
                    continue;
                }
                boolean variant = item.variant != null && !item.variant.isEmpty();
                String key = variant ? item.variant : item.id;
                merged.computeIfAbsent(key, k -> new long[1])[0] += Math.max(0L, item.count);
                if (variant) {
                    plainIds.putIfAbsent(key, item.id);
                    if (!names.containsKey(key)) {
                        // 变体行用**完整**名字（「附魔书 · 深海探索者 III」），不用「 · 」前面那段：
                        // 附魔书这类东西同族多行都叫「附魔书」，只留前段的话本地和 AI 都分不出是哪一本，
                        // 玩家说的「深海探索者3」就永远落不到具体那一行上。
                        String full = item.name == null ? null : item.name.trim();
                        String clean = full == null || full.isEmpty() ? VoiceVariants.name(item.name) : full;
                        if (clean != null) {
                            names.put(key, clean);
                        }
                    }
                }
            }
        }
        List<VoiceText.Candidate> out = new ArrayList<>(merged.size());
        for (Map.Entry<String, long[]> entry : merged.entrySet()) {
            String key = entry.getKey();
            String name = names.get(key);
            if (name == null || name.isEmpty()) {
                name = localizedName(plainIds.getOrDefault(key, key));
            }
            if (name != null && !name.isEmpty()) {
                out.add(new VoiceText.Candidate(key, name, entry.getValue()[0]));
            }
        }
        return out;
    }

    /**
     * 物品在本机语言下的名字。
     *
     * <p>快照里的 {@code item.name} 是**服务端**算出来的名字，而专用服务端只有 en_us 语言表，
     * 所以那个名字往往是英文（Cobblestone）；玩家嘴里说的是中文（圆石），拿英文名字去匹配
     * 永远匹配不上。这里改用客户端自己的语言表（{@link ClientNames}），语义和面板显示一致；
     * 客户端名字取不到时才退回服务端名字（顺便兼容英文玩家）。
     */
    private static String localizedName(String itemId) {
        String local = ClientNames.item(itemId);
        if (local != null && !local.isEmpty()) {
            return local;
        }
        for (ClientSnapshot.Region region : ClientSnapshot.regions()) {
            for (ClientSnapshot.Item item : region.items) {
                if (itemId.equals(item.id) && item.name != null && !item.name.isEmpty()) {
                    return item.name;
                }
            }
        }
        return itemId;
    }

    /** 物品 id → 一组多少个（取不到按 64）。 */
    /**
     * 物品 id → 一组多少个（64/16/1…）。
     *
     * <p>包内可见：识别 worker 里算 AI 的 {@code amount}（「N组」= N × 一组）也要用同一个数，
     * 不能两边各猜一个（离线单测里传 null 就是「一律 64」）。
     *
     * <p>候选里也可能是**变体键**（`minecraft:potion@192d3b`，见 {@link #candidates()}）：
     * 先剥掉 `@` 后面那段再查物品，否则药水会被当成默认 64——「半组迟缓药水」就会算成 32 个。
     */
    static int itemStackSize(String itemId) {
        try {
            Identifier id = Identifier.tryParse(VoiceVariants.itemId(itemId));
            if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                Item item = BuiltInRegistries.ITEM.getValue(id);
                if (item != null) {
                    int size = new ItemStack(item).getMaxStackSize();
                    if (size > 0) {
                        return size;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 取不到就用默认值
        }
        return 64;
    }
}
