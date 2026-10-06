package com.ds.warehouse.voice;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 汉字 → 无声调拼音（只带物品名里用得到的字，随语音包分发，约 7 KB）。
 *
 * <p>用途：识别经常把游戏内专有名词听成「同音/近音」的别的词——「安山岩」被听成「鞍山人」
 * 「鞍山言」，「下界合金剑」被听成「下季和金剑」。这种错字在字形上完全不像，但**读音很接近**，
 * 所以除了字形相似度，还要按拼音再比一次。
 *
 * <p>数据来源：mozillazg/pinyin-data（MIT/CC 系的开放拼音数据），只抽取物品名用到的汉字。
 */
final class VoicePinyin {

    private static final String RESOURCE = "/assets/warehouse-keeper-voice/pinyin.txt";

    private static volatile Map<Character, String> table;

    private VoicePinyin() {
    }

    /** 一行一串「音节」，用空格分隔；没有拼音的字（数字/英文）保留原样，便于比较。 */
    static String of(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        Map<Character, String> map = table();
        StringBuilder out = new StringBuilder(text.length() * 4);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (i > 0) {
                out.append(' ');
            }
            String pinyin = map.get(c);
            out.append(pinyin != null ? pinyin : String.valueOf(c));
        }
        return out.toString();
    }

    /** 音节数组形式，便于按音节算编辑距离/双字组。 */
    static String[] syllables(String text) {
        String pinyin = of(text);
        return pinyin.isEmpty() ? new String[0] : pinyin.split(" ");
    }

    private static Map<Character, String> table() {
        Map<Character, String> local = table;
        if (local != null) {
            return local;
        }
        Map<Character, String> loaded = new HashMap<>(2048);
        try (InputStream in = VoicePinyin.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String text = line.trim();
                        if (text.isEmpty() || text.startsWith("#")) {
                            continue;
                        }
                        int space = text.indexOf(' ');
                        if (space <= 0 || space + 1 >= text.length()) {
                            continue;
                        }
                        loaded.put(text.charAt(0), text.substring(space + 1).trim());
                    }
                }
            }
        } catch (Throwable ignored) {
            // 读不到就当没有拼音表：退回纯字形比较
        }
        table = loaded;
        return loaded;
    }
}
