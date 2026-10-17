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

    /**
     * 口音折叠后的音节数组：把「普通话不标准」最常见的几组音折成同一写法，再拿去比较。
     *
     * <p>ASR 是按标准普通话认字的，说话人带口音时经常把音发成另一组「听起来很像」的音，
     * 于是认出来的字读音只是**接近**而不是完全相同，精确拼音比分就掉下去。常见的有：
     * 平翘舌（zh/z、ch/c、sh/s，例：石 shi → 四 si、深 shen → 森 sen）、
     * 鼻音边音（n/l，例：蓝 lan → 南 nan）、r/l（熔 rong → 龙 long）、f/h（例：湖 hu → 福 fu）、
     * 前后鼻音（-ng → -n，例：行 xing → 新 xin）。折叠只用于**放宽**比较，精确比较仍按原音节走。
     */
    static String[] syllablesFold(String text) {
        String pinyin = of(text);
        if (pinyin.isEmpty()) {
            return new String[0];
        }
        String[] parts = pinyin.split(" ");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = fold(parts[i]);
        }
        return parts;
    }

    /** 单个音节的口音折叠（无声调、全小写）；非拼音音节（数字/英文）原样返回。 */
    static String fold(String syllable) {
        if (syllable == null || syllable.length() < 2) {
            return syllable;
        }
        String s = syllable;
        // 声母：平翘舌、n/l、r/l、f/h
        if (s.startsWith("zh")) {
            s = "z" + s.substring(2);
        } else if (s.startsWith("ch")) {
            s = "c" + s.substring(2);
        } else if (s.startsWith("sh")) {
            s = "s" + s.substring(2);
        } else if (s.startsWith("n")) {
            s = "l" + s.substring(1);
        } else if (s.startsWith("r")) {
            s = "l" + s.substring(1);
        } else if (s.startsWith("f")) {
            s = "h" + s.substring(1);
        }
        // 韵母：前后鼻音（长的先折，别把 iang/uang 当成 ang）
        if (s.endsWith("iang")) {
            s = s.substring(0, s.length() - 4) + "ian";
        } else if (s.endsWith("uang")) {
            s = s.substring(0, s.length() - 4) + "uan";
        } else if (s.endsWith("ing")) {
            s = s.substring(0, s.length() - 3) + "in";
        } else if (s.endsWith("ang")) {
            s = s.substring(0, s.length() - 3) + "an";
        } else if (s.endsWith("eng")) {
            s = s.substring(0, s.length() - 3) + "en";
        }
        return s;
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
