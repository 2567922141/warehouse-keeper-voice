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
 *     <li><b>自动记住</b>：某句听错但被模糊匹配救回来时（相似度不高），把这句原话和它最终对应
 *         的物品写进表里；下次同一句（哪怕是同样的错法）直接命中，不用再猜。</li>
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

    /** 按「听到的说法」查物品 id；查不到返回 null。 */
    static synchronized String lookup(String normalizedHeard) {
        return load().get(normalizedHeard);
    }

    /** 记住一条：heard 是识别原话（会做同样的归一化），itemId 是仓库里真实存在的物品。 */
    static synchronized void remember(String heard, String itemId) {
        String key = VoiceText.normalize(heard);
        if (key.isEmpty() || itemId == null || itemId.isEmpty()) {
            return;
        }
        Map<String, String> table = load();
        if (itemId.equals(table.get(key))) {
            return;
        }
        table.put(key, itemId);
        dirty = true;
        save(table);
        LOG.info("记住语音别名：{} → {}", key, itemId);
    }

    private static Map<String, String> load() {
        if (map != null) {
            return map;
        }
        Map<String, String> loaded = new LinkedHashMap<>();
        Path file = file();
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
                    if (!key.isEmpty() && !value.isEmpty()) {
                        loaded.put(key, value);
                    }
                }
            }
        } catch (Throwable t) {
            LOG.warn("读取语音别名表失败：{}", t.toString());
        }
        map = loaded;
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
