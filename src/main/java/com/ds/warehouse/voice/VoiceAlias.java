package com.ds.warehouse.voice;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 「听到的说法 → 物品 id」的别名表，把**以后才会出现**的偏差变成一个可以自我修复的东西。
 *
 * <p>两个来源：
 * <ul>
 *     <li><b>自动记住</b>：某句听错但被模糊匹配救回来时（相似度够高），把这句里的**物品名部分**
 *         和它最终对应的物品写进表里；下次同一件东西的任何说法直接命中，不用再猜。
 *         键只用物品名（挖掉「十个」「一组」这类数量与口水词），所以「十个金币」的教训对
 *         「五个金币」也生效；整句只剩口水词/量词的退化说法（「给」「给我」「一根」）一律不记，
 *         否则说出一两个字就会被自动下单（0.1.0 时代留下的脏键由 {@link #load()} 清掉并备份）。</li>
 *     <li><b>玩家手改</b>：表就在 {@code config/warehouse-keeper-voice/aliases.txt}，
 *         一行 {@code 听到的说法=物品id}，改完重进游戏生效。以后遇到任何我没想到的口音/说法，
 *         自己加一行就行，不用等我改版本。</li>
 * </ul>
 *
 * <p>隐私：这里只存识别出来的**文字**（玩家自己打/说的口令），不含音频。
 */
final class VoiceAlias {

    private static final Logger LOG = LoggerFactory.getLogger("warehouse-keeper-voice");

    /** 表最多留这么多行，避免无限膨胀。 */
    private static final int MAX_LINES = 200;

    private static Map<String, String> map;
    private static boolean dirty;

    private VoiceAlias() {
    }

    /**
     * 按「听到的说法」查物品 id；查不到返回 null。
     *
     * <p>先拿整句查（玩家手写的、以及老版本记下的整句键都还在这里），查不到再用**物品名部分**
     * 查一次 —— 自动记住的键都只含物品名，所以「十个金币」学到的教训对「五个金币」也生效。
     */
    static synchronized String lookup(String normalizedHeard) {
        Map<String, String> table = load();
        String direct = table.get(normalizedHeard);
        if (direct != null) {
            return direct;
        }
        String phrase = VoiceText.itemPhrase(normalizedHeard);
        if (!phrase.equals(normalizedHeard)) {
            return table.get(phrase);
        }
        return null;
    }

    /** 记住一条：heard 是识别原话（会做同样的归一化），itemId 是仓库里真实存在的物品。 */
    static synchronized void remember(String heard, String itemId) {
        String key = VoiceText.itemPhrase(VoiceText.normalize(heard));
        if (itemId == null || itemId.isEmpty()) {
            return;
        }
        // 卫生门槛：整句只剩口水词/量词的退化说法（「给」「给我」「一根」）一律不记 ——
        // 0.1.0 时代把它们写进过别名表，结果任何人说出对应的一两个字就会被自动下单。
        // 老文件里的这类脏键由 load() 清掉（见 migrate）。
        if (!VoiceText.hasSubstance(key)) {
            LOG.info("这句话里没有物品名成分（{} 个字），不记别名", key.length());
            return;
        }
        Map<String, String> table = load();
        if (itemId.equals(table.get(key))) {
            return;
        }
        table.put(key, itemId);
        dirty = true;
        save(table);
        // 只记字数和物品 id：识别出的原话不进游戏日志（日志不在 config 目录下）
        LOG.info("记住语音别名：{} 个字 → {}", key.length(), itemId);
    }

    private static Map<String, String> load() {
        if (map != null) {
            return map;
        }
        Map<String, String> loaded = new LinkedHashMap<>();
        Path file = file();
        int spoiled = 0;
        try {
            if (Files.isRegularFile(file)) {
                for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String line = raw.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    int eq = line.indexOf('=');
                    if (eq <= 0 || eq + 1 >= line.length()) {
                        continue;
                    }
                    String key = VoiceText.normalize(line.substring(0, eq));
                    String value = line.substring(eq + 1).trim();
                    if (key.isEmpty() || value.isEmpty()) {
                        continue;
                    }
                    if (!VoiceText.hasSubstance(key)) {
                        spoiled++;
                        LOG.info("丢弃一条退化别名（没有物品名成分）：{} 个字", key.length());
                        continue;
                    }
                    loaded.put(key, value);
                    // 老键带着数量（「十个金币」）：再按物品名部分收一条，让它对别的数量也生效
                    String phrase = VoiceText.itemPhrase(key);
                    if (!phrase.isEmpty() && !phrase.equals(key) && VoiceText.hasSubstance(phrase)) {
                        loaded.putIfAbsent(phrase, value);
                    }
                }
                if (spoiled > 0) {
                    // 清掉的键先留个备份再重写文件：玩家的手写内容绝不无声消失
                    Path backup = file.resolveSibling("aliases.txt.bak");
                    if (!Files.isRegularFile(backup)) {
                        Files.copy(file, backup);
                    }
                    LOG.warn("别名表里有 {} 条退化键（说出一两个字就会被下单），已移除；原文备份在 {}",
                            spoiled, backup);
                    dirty = true;
                }
            }
        } catch (Throwable t) {
            LOG.warn("读取语音别名表失败：{}", t.toString());
        }
        map = loaded;
        if (dirty) {
            save(loaded);
        }
        return map;
    }

    private static void save(Map<String, String> table) {
        if (!dirty) {
            return;
        }
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            StringBuilder out = new StringBuilder();
            out.append("# 语音别名表：一行一条「听到的说法=物品id」。\n")
                    .append("# 看到语音经常把某样东西听成别的词，就在这里加一行；改完重进游戏生效。\n")
                    .append("# 自动记住的那几条也会写在这里（可以直接改或删）。\n")
                    .append("# 例：鞍山人=minecraft:andesite\n");
            int written = 0;
            for (Map.Entry<String, String> entry : table.entrySet()) {
                if (written++ >= MAX_LINES) {
                    break;
                }
                out.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
            }
            Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
            dirty = false;
        } catch (Throwable t) {
            LOG.warn("写入语音别名表失败：{}", t.toString());
        }
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("warehouse-keeper-voice")
                .resolve("aliases.txt");
    }

    /** 给界面用：当前表里有多少条。 */
    static synchronized int size() {
        return load().size();
    }

    /** 供界面显示用的一句话（含文件路径）。 */
    static String describe() {
        return "语音别名表：" + size() + " 条（" + file().toAbsolutePath() + "）";
    }

    /** 调试/自检用：把整张表列出来（不含音频）。 */
    static synchronized List<String> entries() {
        return load().entrySet().stream()
                .map(e -> e.getKey() + " → " + e.getValue())
                .toList();
    }

    static String key(String heard) {
        return VoiceText.normalize(heard).toLowerCase(Locale.ROOT);
    }
}
