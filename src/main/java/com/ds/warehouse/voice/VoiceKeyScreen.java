package com.ds.warehouse.voice;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 填 AI 服务商的 API Key 的小界面（从语音设置页的「AI Key」那一行点进来）。
 *
 * <p>为什么单独开一页而不是把输入框塞进设置页：设置页的行是「一行一个控件」的布局纪律
 * （见 {@link VoiceSettingsScreen} 的类注释），而 key 是一串要能改、能删、能从网页粘贴几十个字符的东西，
 * 塞在那一行里既看不见也改不动。这一页只管一件事，关掉就回原界面。
 *
 * <p><b>安全纪律</b>：
 * <ul>
 *     <li>输入框里显示的是**打码版**（前 4 位 + 星号，见 {@link VoiceRewrite#maskKey}），
 *         不把整串 key 画在屏幕上 —— 录屏/截图时不会漏出去；</li>
 *     <li>key **明文**存在 {@code config/warehouse-keeper-voice/settings.txt}（§6.3 拍板的方案，
 *         方便玩家自己手改）；这一页上写清楚这件事，不让玩家以为它是加密存的；</li>
 *     <li>只有点了「保存」才落盘；「清空」把 key 置空。</li>
 * </ul>
 */
public class VoiceKeyScreen extends Screen {

    // ---- 几何常量：和 VoiceSettingsScreen 用同一套纪律（只在这里算，别在绘制里写魔数） ----
    private static final int MARGIN = 8;
    private static final int PAD = 8;
    private static final int ROW_H = 18;
    private static final int GAP = 2;
    private static final int HEAD_H = 11;
    private static final int LINE_H = 10;
    private static final int MAX_W = 420;

    private static final int C_SCRIM = 0xC0101010;
    private static final int C_PANEL = 0xF0181818;
    private static final int C_BORDER = 0xFF6E6E6E;
    private static final int C_TITLE = 0xFFFFFFFF;
    private static final int C_HEAD = 0xFF9ECFFF;
    private static final int C_TEXT = 0xFFE0E0E0;
    private static final int C_GOOD = 0xFF7EE787;
    private static final int C_BAD = 0xFFFF9C9C;

    /** 关掉之后回到哪一页（语音设置页）。 */
    private final Screen parent;
    /** 输入框里当前**真实的**内容（显示出来的是打码版）。 */
    private String value = "";
    private String status = "";
    private boolean bad;

    private int px;
    private int py;
    private int pw;
    private int ph;
    private int titleY;
    private int labelY;
    private int boxY;
    private int hintY1;
    private int hintY2;
    private int statusY;
    private int buttonY;

    private EditBox box;

    public VoiceKeyScreen(Screen parent) {
        super(Component.literal("AI API Key"));
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        this.value = SherpaEngine.rewriteKey();
        if (this.status.isEmpty()) {
            this.status = this.value.isEmpty() ? "现在没填 Key" : "当前已填：";
            this.bad = false;
        }
        layout();
        int rowW = this.pw - 2 * PAD;

        this.box = new EditBox(this.font, this.px + PAD, this.boxY, rowW, ROW_H, Component.literal("API Key"));
        // 各家 key 都在 200 字符以内；限长可以挡住「粘贴了一整段网页文字」
        this.box.setMaxLength(200);
        this.box.setValue(masked(this.value));
        this.box.setResponder(this::onTyped);
        addRenderableWidget(this.box);

        int third = Math.max(20, (rowW - 2 * GAP) / 3);
        addRenderableWidget(Button.builder(Component.literal("保存"), b -> save())
                .bounds(this.px + PAD, this.buttonY, third, ROW_H).build());
        addRenderableWidget(Button.builder(Component.literal("清空"), b -> clear())
                .bounds(this.px + PAD + third + GAP, this.buttonY, third, ROW_H).build());
        addRenderableWidget(Button.builder(Component.literal("取消"), b -> onClose())
                .bounds(this.px + PAD + 2 * (third + GAP), this.buttonY, rowW - 2 * (third + GAP), ROW_H).build());
        // 光标直接落在输入框里，进来就能打（不用先点一下）
        setInitialFocus(this.box);
    }

    /** 输入框里显示的永远是打码版；真值存在 {@link #value}。 */
    private static String masked(String key) {
        String mask = VoiceRewrite.maskKey(key);
        return mask.isEmpty() ? "" : mask;
    }

    /**
     * 输入框内容变了：把**新打进来的那段真字符**接回 {@link #value}。
     *
     * <p>EditBox 里显示的是打码串，所以不能直接 {@code setValue(box.getValue())}（那会把星号当成真的 key）。
     * 这里按「打码串的前缀长度 × 每个星号」反推：前面没变、尾部多出来/少掉的字符就是玩家真的动了的部分。
     */
    private void onTyped(String shown) {
        String prefix = masked(this.value);
        if (shown.equals(prefix)) {
            return;
        }
        if (shown.startsWith(prefix)) {
            // 在后面接着打
            this.value = this.value + shown.substring(prefix.length());
        } else if (prefix.startsWith(shown)) {
            // 退格删掉了一部分（含删到星号上）：按显示长度缩回去
            int keep = Math.max(0, Math.min(this.value.length(), shown.length()));
            this.value = this.value.substring(0, keep);
        } else {
            // 整段被替换（全选粘贴/剪切）：把显示串里非星号的部分当新内容，比丢掉整串强
            StringBuilder fresh = new StringBuilder();
            for (int i = 0; i < shown.length(); i++) {
                char c = shown.charAt(i);
                if (c != '*') {
                    fresh.append(c);
                }
            }
            this.value = fresh.toString();
        }
    }

    private void save() {
        SherpaEngine.setRewriteKey(this.value);
        SherpaEngine.saveAndApply();
        this.bad = false;
        this.status = this.value.isEmpty() ? "已清空：没填 Key" : "已保存（明文存在 settings.txt 里，只显示前 4 位）";
        // 保存完就把打码版回写进输入框，免得屏幕上留着玩家刚敲进去的那串原文
        if (this.box != null) {
            this.box.setValue(masked(this.value));
        }
    }

    private void clear() {
        this.value = "";
        if (this.box != null) {
            this.box.setValue("");
        }
        SherpaEngine.setRewriteKey("");
        SherpaEngine.saveAndApply();
        this.bad = false;
        this.status = "已清空：没填 Key";
    }

    /** 一页就这几行，装不下是不可能的（真的太小就只画提示，绝不让控件叠在一起）。 */
    private void layout() {
        int needed = 2 * PAD + HEAD_H + GAP + LINE_H + GAP + ROW_H + GAP + 2 * LINE_H + GAP + ROW_H;
        this.pw = Math.min(MAX_W, Math.max(160, this.width - 2 * MARGIN));
        this.ph = Math.min(needed, Math.max(90, this.height - 2 * MARGIN));
        this.px = (this.width - this.pw) / 2;
        this.py = (this.height - this.ph) / 2;

        int y = this.py + PAD;
        this.titleY = y;
        y += HEAD_H + GAP;
        this.labelY = y;
        y += LINE_H + GAP;
        this.boxY = y;
        y += ROW_H + GAP;
        this.hintY1 = y;
        y += LINE_H;
        this.hintY2 = y;
        y += LINE_H + GAP;
        this.statusY = y;
        y += LINE_H + GAP;
        this.buttonY = y;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, C_SCRIM);
        g.fill(this.px, this.py, this.px + this.pw, this.py + this.ph, C_PANEL);
        border(g, this.px, this.py, this.px + this.pw, this.py + this.ph, C_BORDER);
        int rowW = this.pw - 2 * PAD;
        g.text(this.font, fit("AI 归一化 · API Key", rowW), this.px + PAD, this.titleY, C_TITLE);
        g.text(this.font, fit("Key（只显示前 4 位；整串存本机明文）", rowW), this.px + PAD, this.labelY, C_HEAD);
        VoiceRewrite.Preset preset = VoiceRewrite.preset(SherpaEngine.rewritePreset());
        String keyHint = preset.local()
                ? "本地 Ollama 不用填 Key（填了也只会发给 127.0.0.1）"
                : "服务：" + preset.label() + "　地址：" + SherpaEngine.rewriteUrl();
        g.text(this.font, fit(keyHint, rowW), this.px + PAD, this.hintY1, C_TEXT);
        g.text(this.font, fit("Key 明文存在 config/warehouse-keeper-voice/settings.txt，只为方便你自己手改",
                rowW), this.px + PAD, this.hintY2, C_TEXT);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int rowW = this.pw - 2 * PAD;
        g.text(this.font, fit(this.status, rowW), this.px + PAD, this.statusY, this.bad ? C_BAD : C_GOOD);
    }

    /** 关掉就回语音设置页（没保存的改动就丢着不管 —— 这一页只有 key 一个值，误触代价只是白填一次）。 */
    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(this.parent);
    }

    /** 逐字截断到可用宽度（和设置页同一套做法：字体量宽是唯一可靠依据）。 */
    private String fit(String text, int max) {
        if (text == null || text.isEmpty() || max <= 0) {
            return "";
        }
        if (this.font.width(text) <= max) {
            return text;
        }
        String cut = text;
        while (cut.length() > 1 && this.font.width(cut + "…") > max) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return this.font.width(cut + "…") <= max ? cut + "…" : "";
    }

    private static void border(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        g.fill(x0, y0, x1, y0 + 1, color);
        g.fill(x0, y1 - 1, x1, y1, color);
        g.fill(x0, y0 + 1, x0 + 1, y1 - 1, color);
        g.fill(x1 - 1, y0 + 1, x1, y1 - 1, color);
    }

    /** 只看一眼是不是「本机服务」（给提示文案用）；认不出就当云端。 */
    static boolean looksLocal() {
        String url = SherpaEngine.rewriteUrl().toLowerCase(Locale.ROOT);
        return url.contains("127.0.0.1") || url.contains("localhost") || url.contains("0.0.0.0");
    }

    /** 未使用但保留：一行里显示「已填/未填」的短文案（两边界面共用同一套说法）。 */
    static String keyState(String key) {
        if (key == null || key.isBlank()) {
            return "未填";
        }
        return "已填 " + VoiceRewrite.maskKey(key);
    }
}
