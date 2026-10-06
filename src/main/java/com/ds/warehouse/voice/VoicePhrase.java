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
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    /** 上一句没听出来的话（用于「下次说对了就把它记住」）。 */
    private static String lastFailed;
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
        String heard = primary.text();
        Decision decision = decide(heard);
        SherpaEngine.Line rescuedBy = null;
        if (!decision.order()) {
            if (!decision.noMatch()) {
                return decision.message();
            }
            for (SherpaEngine.Line other : others) {
                String text = other.text();
                if (text == null || text.isEmpty() || text.equals(heard)) {
                    continue;
                }
                Decision alt = decide(text);
                if (alt.order() && alt.match() != null) {
                    decision = alt;
                    rescuedBy = other;
                    // 主模型这句话以后就该听懂了
                    VoiceAlias.remember(heard, alt.match().candidate().id());
                    break;
                }
            }
            if (rescuedBy == null) {
                return decision.message();
            }
        }
        VoiceText.Match match = decision.match();
        if (match == null) {
            return decision.message();
        }
        VoiceText.Candidate item = match.candidate();
        int want = Math.max(1, match.count());
        send("warehouse order " + item.id() + " " + want);
        LOG.info("语音下单：物品={} 数量={} 相似度={}{}", item.id(), want,
                String.format(Locale.ROOT, "%.2f", match.score()),
                rescuedBy == null ? "" : "（" + rescuedBy.label() + " 顶上）");
        String note = rescuedBy == null ? ""
                : "（" + primary.label() + " 听成「" + heard + "」，" + rescuedBy.label()
                        + " 认到「" + rescuedBy.text() + "」，已记住这句）";
        return "已请求取货：" + want + " 个 " + item.name() + byStack(match) + note + "（服务器确认后会派假人）";
    }

    /** 只判断、不下单：对比模式里用来看另一个模型「会取什么」。 */
    static String preview(String heard) {
        return decide(heard).message();
    }

    private static String byStack(VoiceText.Match match) {
        return match.byStack() ? "（按一组 " + match.unitSize() + " 个算）" : "";
    }

    private static Decision decide(String heard) {
        if (!canOrder()) {
            return Decision.refuse("没有取货权限（命令没同步给你）：让管理员在仓库面板「权限」页给你 take 权限，或 /op 你");
        }
        if (!ClientSnapshot.hasData()) {
            return Decision.refuse("还没收到仓库数据，先打开一次仓库面板再试");
        }
        List<VoiceText.Candidate> candidates = candidates();
        if (candidates.isEmpty()) {
            return Decision.refuse("仓库里现在是空的，没有可取的物品");
        }
        String text = VoiceText.normalize(heard);
        if (text.isEmpty()) {
            return Decision.refuse("没听清，请再说一次");
        }
        // ① 先查别名表（自动记住的 + 玩家手写的），命中就直接用——这样「以后才会出现的偏差」
        //    只要被纠正过一次，之后就一直是对的。
        VoiceText.Match match = null;
        String aliasId = VoiceAlias.lookup(text);
        if (aliasId != null) {
            VoiceText.Candidate aliased = find(candidates, aliasId);
            if (aliased != null) {
                match = new VoiceText.Match(aliased, 1, itemStackSize(aliasId), false, 1.0D, 1.0D);
            }
        }
        // ② 再走闭集模糊匹配（字形 + 拼音）
        if (match == null) {
            match = VoiceText.best(candidates, text, VoicePhrase::itemStackSize);
        }
        if (match == null) {
            // 记住这句没听出来的话：接下来 90 秒内只要有一次说对了，就把它自动写进别名表
            lastFailed = text;
            lastFailedAt = System.currentTimeMillis();
            return Decision.noMatch("没听出物品名（" + heard + "）——最接近的是："
                    + String.join(" / ", VoiceText.closestNames(candidates, text, 3))
                    + "；说对一次我就会记住");
        }
        // 靠模糊匹配救回来的（不是完全命中）就记进别名表，下次直接命中
        if (match.score() < 0.95D) {
            VoiceAlias.remember(heard, match.candidate().id());
        }
        // 上一句没听出来、这一句说对了：把上一句的错法也记住（以后同样的错法也能听懂）
        if (lastFailed != null && System.currentTimeMillis() - lastFailedAt <= FAILED_MEMORY_MS
                && !lastFailed.equals(text)) {
            VoiceAlias.remember(lastFailed, match.candidate().id());
        }
        lastFailed = null;
        int want = Math.max(1, match.count());
        String what = want + " 个 " + match.candidate().name() + byStack(match);
        if (want > MAX_ORDER) {
            return Decision.refuse("一次最多下单 " + MAX_ORDER + " 个（" + want + " 个太多），没有下单。");
        }
        if (match.candidate().count() < want) {
            return Decision.refuse("仓库里「" + match.candidate().name() + "」只有 "
                    + match.candidate().count() + " 个，不够 " + want + " 个，没有下单。");
        }
        return new Decision(match, "取货 " + what
                + String.format(Locale.ROOT, "（相似度 %.2f）", match.score()), true, false);
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
            return true;
        }
    }

    /** 仓库里真实有的物品（按 id 合并各区域数量）。 */
    private static List<VoiceText.Candidate> candidates() {
        Map<String, long[]> merged = new LinkedHashMap<>();
        for (ClientSnapshot.Region region : ClientSnapshot.regions()) {
            for (ClientSnapshot.Item item : region.items) {
                if (item.id == null || item.id.isEmpty()) {
                    continue;
                }
                merged.computeIfAbsent(item.id, key -> new long[1])[0] += Math.max(0L, item.count);
            }
        }
        List<VoiceText.Candidate> out = new ArrayList<>(merged.size());
        for (Map.Entry<String, long[]> entry : merged.entrySet()) {
            String name = localizedName(entry.getKey());
            if (name != null && !name.isEmpty()) {
                out.add(new VoiceText.Candidate(entry.getKey(), name, entry.getValue()[0]));
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
    private static int itemStackSize(String itemId) {
        try {
            Identifier id = Identifier.tryParse(itemId);
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
