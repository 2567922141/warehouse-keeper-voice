package com.ds.warehouse.voice;

/**
 * 组件感知变体的两个纯文本小工具。
 *
 * <p>带组件的物品（药水／附魔书／烟花／染色装备…）在仓库快照里每种组件组合各占一行，靠
 * 语言无关的**变体键**区分（主体 {@code index.Variants.keyOf}，形状 {@code minecraft:potion@192d3b}）。
 * 语音侧要用到两件事，都是纯字符串处理：
 * <ul>
 *   <li>{@link #name(String)}：服务端把变体行的名字拼成「真实名字 · 组件注解」
 *       （主体 {@code SnapshotSync.rowName}），语音匹配只要前面那段；</li>
 *   <li>{@link #itemId(String)}：算「一组多少个」时要把变体键还原成物品 id，
 *       否则药水／工具会被当成默认 64 个。</li>
 * </ul>
 *
 * <p>这个类**不 import 任何 Minecraft 类**（和 {@code VoiceText}／{@code VoiceRewrite} 一样），
 * 所以离线 harness 能直接编产品源码来测它——变体键与名字的规则只写在这里一份，不复制。
 */
final class VoiceVariants {

    /** 主体 {@code SnapshotSync.rowName} 用的分隔符：「真实名字」+ 这个 + 「组件注解」。 */
    static final String NOTE_SEP = " · ";

    private VoiceVariants() {
    }

    /**
     * 变体行的「干净名字」：{@link #NOTE_SEP} 前面那段并去掉首尾空白。
     *
     * <p>注解那段（「药水: Slowness IV 0:20」「附魔书: Sharpness V」）是给人看的细节，
     * 拿它去匹配只会污染相似度。取不到干净名字（空串、只有注解、null）时返回 {@code null}，
     * 由调用方退回物品 id 的中文名。
     *
     * <p>名字以分隔符**开头**也算「只有注解」（服务端两边的名字都取不到时就是这样），
     * 所以判断用 {@code cut >= 0} 而不是 {@code > 0}。
     */
    static String name(String rowName) {
        if (rowName == null) {
            return null;
        }
        int cut = rowName.indexOf(NOTE_SEP);
        String clean = (cut >= 0 ? rowName.substring(0, cut) : rowName).trim();
        return clean.isEmpty() ? null : clean;
    }

    /**
     * 变体键 → 物品 id：砍掉第一个 {@code @} 及其后面那段。普通 id（没有 {@code @}）原样返回。
     *
     * <p>{@code @} 不会出现在物品 id 里（只允许 {@code [a-z0-9_.-]} 与命名空间），所以这个
     * 判断不会有歧义。
     */
    static String itemId(String variantKey) {
        if (variantKey == null) {
            return null;
        }
        int at = variantKey.indexOf('@');
        return at > 0 ? variantKey.substring(0, at) : variantKey;
    }
}
