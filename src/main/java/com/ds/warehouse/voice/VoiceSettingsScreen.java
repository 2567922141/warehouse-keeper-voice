package com.ds.warehouse.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 语音设置页：选主模型、选副模型、下载/删除模型、开关对照与并行、切换下载源。
 *
 * <p>为什么做成一个独立的 Screen 而不是别的模组的配置页：语音包是独立模组，它不该依赖仓库
 * 模组的界面代码（仓库模组反过来也不该知道语音模型的存在）；而 Fabric 又没有「往别人配置页里
 * 插一行」的通用入口，所以自带一页最省事，配一个客户端命令 {@code /warehousevoice} 打开。
 *
 * <p><b>布局纪律（这个仓库的硬约束：任何面板改动都不许文字重叠/溢出）</b>：
 * <ul>
 *     <li>所有几何只在 {@link #layout()} 里算一次，{@code init()} 与绘制都读同一批字段 ——
 *         不存在「按钮按 A 算法放、文字按 B 算法画」这种错位。</li>
 *     <li>每行的高度是常量 {@link #ROW_H}，行与行之间固定 {@link #GAP}；需要的高度先算出来，
 *         装不下就进 {@link #tiny} 模式（只显示标题 + 一句人话 + 关闭），而不是让控件压在一起。</li>
 *     <li>所有文字都过 {@link #fit(String, int)} 按可用宽度截断 —— 模型名是中文长串（
 *         「SenseVoice（推荐）」），不同 GUI Scale 下宽度差异很大，不截断必然溢出。</li>
 *     <li>面板背景/边框/小节标题画在 {@code extractBackground}（在控件之下），状态文字画在
 *         {@code extractRenderState}（在控件之上）—— 顺序反了就会出现「文字被按钮盖住」。</li>
 * </ul>
 */
public class VoiceSettingsScreen extends Screen {

    // ---- 几何常量：改这里就够了，别在绘制代码里写魔数 ----
    /** 面板与屏幕边缘的最小留白。 */
    private static final int MARGIN = 8;
    /** 面板内边距。 */
    private static final int PAD = 8;
    /** 一行控件的高度。 */
    private static final int ROW_H = 18;
    /** 行与行之间的空隙。 */
    private static final int GAP = 2;
    /** 小节标题占的高度（比按钮矮，因为它只有一行字）。 */
    private static final int HEAD_H = 11;
    /** 状态文字行高。 */
    private static final int LINE_H = 10;
    /** 面板最大宽度；窗口更宽时也不摊大，免得眼睛要横扫一整屏。 */
    private static final int MAX_W = 420;
    /** 进度条高度。 */
    private static final int BAR_H = 6;
    /** 装不下的判定下限：面板高度小于这个值就只显示提示（再挤也没法看了）。 */
    private static final int MIN_PANEL_H = 120;

    // ---- 「AI 归一化」分区的行号（第 ② 步；加行就改 AI_ROWS 与 buildAiRows 里的 switch） ----
    private static final int AI_SWITCH = 0;
    private static final int AI_MODE = 1;
    private static final int AI_SERVICE = 2;
    private static final int AI_MODEL = 3;
    private static final int AI_KEY = 4;
    private static final int AI_STATUS = 5;
    private static final int AI_ROWS = 6;

    // ---- 配色（ARGB） ----
    private static final int C_SCRIM = 0xC0101010;
    private static final int C_PANEL = 0xF0181818;
    private static final int C_BORDER = 0xFF6E6E6E;
    private static final int C_TITLE = 0xFFFFFFFF;
    private static final int C_HEAD = 0xFF9ECFFF;
    private static final int C_TEXT = 0xFFE0E0E0;
    private static final int C_BAR_BG = 0xFF2A2A2A;
    private static final int C_BAR_FG = 0xFF3FB950;
    private static final int C_GOOD = 0xFF7EE787;
    private static final int C_BAD = 0xFFFF9C9C;

    /** 打开设置页之前的那一页；为 null 表示关闭即回到游戏（命令打开时就是这样）。 */
    private final Screen parent;
    private List<VoiceModel> models = List.of();
    /** 底部那行给玩家看的反馈；空串表示还没话说。 */
    private String status = "";
    /** 有改动还没落盘。关闭界面时会自动保存，所以不存在「改了忘记保存」。 */
    private boolean dirty;
    /** 删除是危险操作：第一次点变「确认删除」，再点才真删。 */
    private String pendingDeleteId = "";
    /** 上一帧的下载状态，用来抓「下载刚刚结束」这个瞬间。 */
    private boolean wasDownloading;

    // ---- layout() 算出来的几何 ----
    private int px;
    private int py;
    private int pw;
    private int ph;
    /** 面板装不下内容：只画提示和关闭按钮。 */
    private boolean tiny;
    private int titleY;
    private int primaryHeadY;
    private int primaryFirstY;
    private int secondaryHeadY;
    private int secondaryY;
    private int optionsHeadY;
    private int optionsY1;
    private int optionsY2;
    /** AI 归一化分区的小节标题与 5 行的 y（索引见 {@link #AI_ROW_*}）。 */
    private int aiHeadY;
    private final int[] aiRowY = new int[AI_ROWS];
    private int barY;
    private int statusY;
    private int bottomY;

    public VoiceSettingsScreen(Screen parent) {
        super(Component.literal("语音设置"));
        this.parent = parent;
    }

    /** 下载是后台线程，界面不该把游戏暂停掉（暂停了 tick 不跑，玩家看不到进度变化）。 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        models = SherpaEngine.catalog();
        layout();
        wasDownloading = SherpaEngine.downloading();
        if (status.isEmpty()) {
            status = "当前主模型：" + shortName(SherpaEngine.primaryId())
                    + "；下载源：" + SherpaEngine.mirrorLabel()
                    + "；AI 归一化：" + (SherpaEngine.rewriteOn()
                            ? "开（" + SherpaEngine.rewriteModeLabel() + "）" : "关");
            String fallback = SherpaEngine.mirrorFallback(
                    VoiceModelCatalog.byId(SherpaEngine.primaryId()));
            if (fallback != null) {
                status = status + "（" + fallback + "）";
            }
        }
        if (tiny) {
            addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                    .bounds(px + PAD, py + ph - PAD - ROW_H, Math.max(20, pw - 2 * PAD), ROW_H)
                    .build());
            return;
        }
        buildPrimaryRows();
        buildSecondaryRow();
        buildOptionRows();
        buildAiRows();
        buildBottomRow();
    }

    /** 主模型：一行一个模型，左边整行是「选它」，右边是「下载 / 删除」。 */
    private void buildPrimaryRows() {
        int rowW = pw - 2 * PAD;
        int actionW = Math.min(72, Math.max(46, rowW / 4));
        String primary = SherpaEngine.primaryId();
        for (int i = 0; i < models.size(); i++) {
            VoiceModel model = models.get(i);
            int y = primaryFirstY + i * (ROW_H + GAP);
            boolean chosen = model.id().equals(primary);
            String label = fit((chosen ? "✔ " : "") + model.label() + " · " + stateShort(model),
                    rowW - actionW - GAP - 6);
            Button choose = Button.builder(Component.literal(label), b -> {
                SherpaEngine.setPrimary(model.id());
                dirty = true;
                pendingDeleteId = "";
                // V-04：主模型可以指到没下载的模型（保存不拦），但必须当场说明白会怎样 ——
                // 以前只回一句「已改成 X」，玩家之后每次识别都悄悄走回退，语义不清。
                status = VoiceModelStore.installed(model)
                        ? "主模型改成「" + model.label() + "」，点「保存并应用」后生效"
                        : "主模型已指向「" + model.label() + "」，但它还没下载：先点右边「下载」，"
                                + "下载完成前识别会自动改用已装的模型";
                rebuildWidgets();
            }).bounds(px + PAD, y, rowW - actionW - GAP, ROW_H).build();
            addRenderableWidget(choose);
            addRenderableWidget(actionButton(model, px + PAD + rowW - actionW, y, actionW));
        }
    }

    /** 一行小按钮：左起「关」，然后每个模型一个。选中的那个前面带 ✔（不用颜色区分，色盲也能看出来）。 */
    private void buildSecondaryRow() {
        int rowW = pw - 2 * PAD;
        int count = models.size() + 1;
        int w = Math.max(20, (rowW - GAP * (count - 1)) / count);
        String secondary = SherpaEngine.secondaryId();
        addRenderableWidget(Button.builder(
                        Component.literal(("off".equals(secondary) ? "✔ " : "") + "关"), b -> {
                            SherpaEngine.setSecondary("off");
                            dirty = true;
                            pendingDeleteId = "";
                            status = "副模型已关掉，点「保存并应用」后生效";
                            rebuildWidgets();
                        })
                .bounds(px + PAD, secondaryY, w, ROW_H).build());
        for (int i = 0; i < models.size(); i++) {
            VoiceModel model = models.get(i);
            int x = px + PAD + (i + 1) * (w + GAP);
            boolean chosen = model.id().equals(secondary);
            String label = fit((chosen ? "✔ " : "") + shortLabel(model), w - 6);
            addRenderableWidget(Button.builder(Component.literal(label), b -> {
                SherpaEngine.setSecondary(model.id());
                dirty = true;
                pendingDeleteId = "";
                status = "副模型改成「" + model.label() + "」，点「保存并应用」后生效";
                rebuildWidgets();
            }).bounds(x, secondaryY, w, ROW_H).build());
        }
    }

    /** 三个开关一行 + 下载源一行。 */
    private void buildOptionRows() {
        int rowW = pw - 2 * PAD;
        int w = Math.max(20, (rowW - 2 * GAP) / 3);
        addRenderableWidget(toggle("对照：", SherpaEngine.compareOn(), w, px + PAD, optionsY1, v -> {
            SherpaEngine.setCompare(v);
            status = "对照已" + (v ? "开" : "关") + "，点「保存并应用」后生效";
        }));
        addRenderableWidget(toggle("并行：", SherpaEngine.parallelOn(), w, px + PAD + w + GAP, optionsY1, v -> {
            SherpaEngine.setParallel(v);
            status = "并行已" + (v ? "开" : "关") + "，点「保存并应用」后生效";
        }));
        addRenderableWidget(toggle("预加载：", SherpaEngine.preloadOn(), w, px + PAD + 2 * (w + GAP), optionsY1, v -> {
            SherpaEngine.setPreload(v);
            status = "预加载已" + (v ? "开" : "关") + "，点「保存并应用」后生效";
        }));
        String label = fit("下载源：" + SherpaEngine.mirrorLabel() + "（点一下换）", rowW - 6);
        addRenderableWidget(Button.builder(Component.literal(label), b -> cycleMirror())
                .bounds(px + PAD, optionsY2, rowW, ROW_H).build());
    }

    private void buildBottomRow() {
        int rowW = pw - 2 * PAD;
        int half = Math.max(20, (rowW - GAP) / 2);
        addRenderableWidget(Button.builder(Component.literal("保存并应用"), b -> {
            SherpaEngine.saveAndApply();
            dirty = false;
            status = "已保存。主模型：" + shortName(SherpaEngine.primaryId())
                    + "；副模型：" + (("off".equals(SherpaEngine.secondaryId())) ? "关"
                    : shortName(SherpaEngine.secondaryId()));
            rebuildWidgets();
        }).bounds(px + PAD, bottomY, half, ROW_H).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(px + PAD + half + GAP, bottomY, rowW - half - GAP, ROW_H).build());
    }

    /**
     * 一行的右边按钮：正在下的是「取消」、已装好的是「删除」（两段式）、没装的是「下载」。
     */
    private Button actionButton(VoiceModel model, int x, int y, int w) {
        VoiceModelDownloader.Progress progress = SherpaEngine.downloadProgress();
        boolean thisOne = SherpaEngine.downloading() && progress != null
                && model.id().equals(progress.modelId());
        String label;
        if (thisOne) {
            label = "取消";
        } else if (VoiceModelStore.installed(model)) {
            label = pendingDeleteId.equals(model.id()) ? "确认删除" : "删除";
        } else {
            label = "下载";
        }
        return Button.builder(Component.literal(fit(label, w - 6)), b -> {
            VoiceModelDownloader.Progress now = SherpaEngine.downloadProgress();
            boolean running = SherpaEngine.downloading() && now != null && model.id().equals(now.modelId());
            if (running) {
                SherpaEngine.cancelDownload();
                status = "已取消下载「" + model.label() + "」";
            } else if (VoiceModelStore.installed(model)) {
                if (!pendingDeleteId.equals(model.id())) {
                    // 第一次点只是举起扳机：几百 MB 的东西不该被一次误点删掉
                    pendingDeleteId = model.id();
                    status = "再点一下「确认删除」才会真的删掉「" + model.label() + "」";
                } else {
                    VoiceModelStore.delete(model);
                    SherpaEngine.forget(model.id());
                    pendingDeleteId = "";
                    status = "已删除「" + model.label() + "」的模型文件";
                }
            } else {
                pendingDeleteId = "";
                SherpaEngine.download(model.id());
                status = "开始下载「" + model.label() + "」（" + model.displaySize() + "）";
            }
            rebuildWidgets();
        }).bounds(x, y, w, ROW_H).build();
    }

    private Button toggle(String name, boolean on, int w, int x, int y, Consumer<Boolean> apply) {
        // 开关的文案把状态写在名字里，比「高亮=开」清楚：玩家不用猜颜色代表什么
        return Button.builder(Component.literal(fit(name + (on ? "开" : "关"), w - 6)), b -> {
            apply.accept(!on);
            dirty = true;
            pendingDeleteId = "";
            rebuildWidgets();
        }).bounds(x, y, w, ROW_H).build();
    }

    private void cycleMirror() {
        List<String> choices = VoiceModelCatalog.mirrorChoices();
        if (choices.isEmpty()) {
            return;
        }
        int index = choices.indexOf(SherpaEngine.mirrorMode());
        String next = choices.get((index + 1) % choices.size());
        SherpaEngine.setMirrorMode(next);
        dirty = true;
        pendingDeleteId = "";
        status = "下载源改成「" + VoiceMirrors.label(next) + "」，点「保存并应用」后生效";
        String fallback = SherpaEngine.mirrorFallback(VoiceModelCatalog.byId(SherpaEngine.primaryId()));
        if (fallback != null) {
            status = status + "；" + fallback;
        }
        rebuildWidgets();
    }

    /**
     * AI 归一化分区（第 ② 步）：开关 / 服务 / 模型 / Key / 状态。
     *
     * <p>这一版只做到「能填 key、能换服务、能看状态」；{@code rewrite.url} 与
     * {@code rewrite.model} 的自定义值仍走手改 settings.txt（重进游戏生效）—— 见计划书 §6.1。
     * 先把主链路做对，输入框留到下一步，免得设置页里塞一整排文本框。
     */
    private void buildAiRows() {
        int rowW = pw - 2 * PAD;
        // 行 1：总开关。关着时一个字节都不出网，所以这一行的状态要写得最明白。
        boolean on = SherpaEngine.rewriteOn();
        addRenderableWidget(toggle("AI 归一化：", on, rowW, px + PAD, aiRowY[AI_SWITCH], v -> {
            SherpaEngine.setRewrite(v);
            status = v
                    ? "AI 归一化已开：当前方式「" + SherpaEngine.rewriteModeLabel()
                            + "」，仍由本地匹配器决定下不下单；点「保存并应用」后生效"
                    : "AI 归一化已关：不会再有任何请求出网，点「保存并应用」后生效";
        }));

        // 行 2：改写方式（完全本地 / 两个一起 / 完全 AI）。点一下按顺序换下一个。
        int modeW = Math.max(20, (rowW - 3 * GAP) * 2 / 3);
        addRenderableWidget(Button.builder(
                        Component.literal(fit("方式：" + SherpaEngine.rewriteModeLabel() + "（点一下换）", modeW - 6)),
                        b -> cycleRewriteMode())
                .bounds(px + PAD, aiRowY[AI_MODE], modeW, ROW_H).build());
        addRenderableWidget(Button.builder(Component.literal("说明"), b -> {
            status = "方式只决定「什么时候问 AI」：两个一起＝本地没把握才问；"
                    + "完全调用 AI＝每句先问一次（AI 答不上才用本地）；完全使用本地规则＝一次都不问。"
                    + "改完点「保存并应用」生效。";
            dirty = true;
            pendingDeleteId = "";
            rebuildWidgets();
        }).bounds(px + PAD + modeW + GAP, aiRowY[AI_MODE],
                rowW - modeW - GAP, ROW_H).build());

        // 行 3：服务商循环（智谱 → 硅基流动 → 本地 Ollama → 自定义）。
        VoiceRewrite.Preset current = VoiceRewrite.preset(SherpaEngine.rewritePreset());
        int serviceW = Math.max(20, (rowW - 3 * GAP) * 2 / 3);
        addRenderableWidget(Button.builder(
                        Component.literal(fit("服务：" + current.label() + "（点一下换）", serviceW - 6)),
                        b -> cycleRewriteService())
                .bounds(px + PAD, aiRowY[AI_SERVICE], serviceW, ROW_H).build());
        addRenderableWidget(Button.builder(Component.literal("地址 ↗"), b -> {
            // 自定义服务要先有地址才谈得上调用：这里只提示去手改，不假装能在这行里填
            status = "自定义服务在 settings.txt 里改 rewrite.url=（现在是「"
                    + (SherpaEngine.rewriteUrl().isEmpty() ? "空白，还没填" : SherpaEngine.rewriteUrl())
                    + "」）；手改后重进游戏生效";
            dirty = true;
            pendingDeleteId = "";
            rebuildWidgets();
        }).bounds(px + PAD + serviceW + GAP, aiRowY[AI_SERVICE],
                rowW - serviceW - GAP, ROW_H).build());

        // 行 3：模型名 + 换一个（在预设自带的可选模型之间循环）。
        String model = SherpaEngine.rewriteModel();
        int modelW = Math.max(20, (rowW - 3 * GAP) * 2 / 3);
        addRenderableWidget(Button.builder(
                        Component.literal(fit("模型：" + model, modelW - 6)), b -> cycleRewriteModel())
                .bounds(px + PAD, aiRowY[AI_MODEL], modelW, ROW_H).build());
        String valid = SherpaEngine.rewriteModelLooksValid() ? "对标 ✓"
                : "名字可能和服务对不上（照样能存，调用会由服务端判）";
        addRenderableWidget(Button.builder(Component.literal(fit(valid, rowW - modelW - GAP - 6)), b -> {
            status = "模型名以服务端为准；这里只在你填的和当前服务明显不搭时提一句。";
            rebuildWidgets();
        }).bounds(px + PAD + modelW + GAP, aiRowY[AI_MODEL],
                rowW - modelW - GAP, ROW_H).build());

        // 行 4：Key —— 点开输入界面，回显只显示前 4 位。
        String key = SherpaEngine.rewriteKey();
        String keyText = key.isBlank() ? "Key：未填（点这里填）"
                : "Key：" + VoiceRewrite.maskKey(key) + "（点这里改）";
        addRenderableWidget(Button.builder(Component.literal(fit(keyText, rowW - 6)), b ->
                        Minecraft.getInstance().setScreenAndShow(new VoiceKeyScreen(this)))
                .bounds(px + PAD, aiRowY[AI_KEY], rowW, ROW_H).build());

        // 行 5：状态（只读）—— 已用次数 / 上次延迟 / 上次结果。
        Button statusRow = Button.builder(
                        Component.literal(fit("状态：" + VoiceRewrite.statusLine(), rowW - 6)), b -> {
                            status = "AI 归一化统计："
                                    + (SherpaEngine.rewriteOn() ? "开关是开的" : "开关是关的，不会有请求出网");
                            rebuildWidgets();
                        })
                .bounds(px + PAD, aiRowY[AI_STATUS], rowW, ROW_H).build();
        statusRow.active = false;
        addRenderableWidget(statusRow);
    }

    /** 在三种改写方式之间循环；顺序就是 {@link VoiceRewrite#modeIds()}。 */
    private void cycleRewriteMode() {
        List<String> ids = VoiceRewrite.modeIds();
        String now = SherpaEngine.rewriteMode();
        int index = Math.max(0, ids.indexOf(now));
        String next = ids.get((index + 1) % ids.size());
        SherpaEngine.setRewriteMode(next);
        dirty = true;
        pendingDeleteId = "";
        rebuildWidgets();
        String detail = switch (next) {
            case VoiceRewrite.MODE_LOCAL -> "一次都不问 AI，只听本地规则（服务/Key 仍留着，随时切回来）";
            case VoiceRewrite.MODE_AI -> "每句都能问一次 AI 改写（AI 答不上才用本地结果）";
            default -> "本地优先，本地没把握时才问一次 AI";
        };
        status = "改写方式改成「" + VoiceRewrite.modeLabel(next) + "」：" + detail + "；点「保存并应用」后生效";
    }

    /** 在四个预设里换下一个；换完顺手把「模型」一起带过去（不然会留着一个别家的名字）。 */
    private void cycleRewriteService() {
        List<VoiceRewrite.Preset> presets = VoiceRewrite.presets();
        String now = SherpaEngine.rewritePreset();
        int index = 0;
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i).id().equals(now)) {
                index = i;
            }
        }
        VoiceRewrite.Preset next = presets.get((index + 1) % presets.size());
        SherpaEngine.setRewritePreset(next.id());
        SherpaEngine.setRewriteModel("");
        dirty = true;
        pendingDeleteId = "";
        rebuildWidgets();
        if (next.url().isEmpty()) {
            status = "服务改成「" + next.label() + "」：还要在 settings.txt 里填 rewrite.url= 与 rewrite.model=，"
                    + "手改后重进游戏生效";
        } else {
            status = "服务改成「" + next.label() + "」（默认模型 " + next.model() + "），点「保存并应用」后生效";
        }
    }

    /** 在「本服务的预设模型 + 留空（用默认）」之间循环。 */
    private void cycleRewriteModel() {
        String now = SherpaEngine.rewriteModel();
        List<String> options = aiModelOptions();
        int index = options.indexOf(now);
        String next = options.get((index + 1) % options.size());
        SherpaEngine.setRewriteModel(next);
        dirty = true;
        pendingDeleteId = "";
        rebuildWidgets();
        status = next.isEmpty()
                ? "模型已改回「用服务默认的」，点「保存并应用」后生效"
                : "模型改成「" + next + "」，点「保存并应用」后生效";
    }

    /** 当前服务下能点着选的模型名；第一项是空串 = 用预设默认。 */
    private static List<String> aiModelOptions() {
        List<String> options = new ArrayList<>();
        options.add("");
        VoiceRewrite.Preset preset = VoiceRewrite.preset(SherpaEngine.rewritePreset());
        if (preset.id().equals("zhipu")) {
            options.add("glm-4.7-flash");
            options.add("glm-4-flash-250414");
            options.add("glm-4-flash");
        } else if (preset.id().equals("ollama")) {
            options.add("qwen3:0.6b");
            options.add("qwen3:4b");
        } else if (preset.id().equals("siliconflow")) {
            options.add("Qwen/Qwen3-8B");
            options.add("Qwen/Qwen3-4B");
        }
        return options;
    }

    /**
     * 几何的唯一来源。内容高度先按行数算出来，装不下就缩面板并进 tiny 模式 ——
     * 「算得下就摆、算不下就退化」比「硬摆然后重叠」安全。
     */
    private void layout() {
        int needed = 2 * PAD + HEAD_H + GAP
                + (HEAD_H + GAP) + models.size() * (ROW_H + GAP)
                + (HEAD_H + GAP) + (ROW_H + GAP)
                + (HEAD_H + GAP) + 2 * (ROW_H + GAP)
                + (HEAD_H + GAP) + AI_ROWS * (ROW_H + GAP)
                + (BAR_H + LINE_H + GAP)
                + (ROW_H + GAP);
        pw = Math.min(MAX_W, Math.max(160, width - 2 * MARGIN));
        ph = Math.min(needed, Math.max(MIN_PANEL_H, height - 2 * MARGIN));
        tiny = ph < needed;
        px = (width - pw) / 2;
        py = (height - ph) / 2;

        int y = py + PAD;
        titleY = y;
        y += HEAD_H + GAP;
        primaryHeadY = y;
        y += HEAD_H + GAP;
        primaryFirstY = y;
        y += models.size() * (ROW_H + GAP);
        secondaryHeadY = y;
        y += HEAD_H + GAP;
        secondaryY = y;
        y += ROW_H + GAP;
        optionsHeadY = y;
        y += HEAD_H + GAP;
        optionsY1 = y;
        y += ROW_H + GAP;
        optionsY2 = y;
        y += ROW_H + GAP;
        aiHeadY = y;
        y += HEAD_H + GAP;
        for (int i = 0; i < AI_ROWS; i++) {
            aiRowY[i] = y;
            y += ROW_H + GAP;
        }
        barY = y;
        statusY = y + BAR_H + 1;
        y += BAR_H + LINE_H + GAP;
        bottomY = y;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, C_SCRIM);
        g.fill(px, py, px + pw, py + ph, C_PANEL);
        border(g, px, py, px + pw, py + ph, C_BORDER);
        if (tiny) {
            g.text(this.font, fit("语音设置", pw - 2 * PAD), px + PAD, titleY, C_TITLE);
            g.text(this.font, fit("窗口太小，把界面缩放调小一点再开", pw - 2 * PAD),
                    px + PAD, titleY + HEAD_H + GAP, C_TEXT);
            return;
        }
        g.text(this.font, fit("语音设置（关掉界面会自动保存）", pw - 2 * PAD), px + PAD, titleY, C_TITLE);
        g.text(this.font, fit("主模型 —— 平时就用它听", pw - 2 * PAD), px + PAD, primaryHeadY, C_HEAD);
        g.text(this.font, fit("副模型 —— 主模型没听出时再问它一次", pw - 2 * PAD),
                px + PAD, secondaryHeadY, C_HEAD);
        g.text(this.font, fit("识别选项", pw - 2 * PAD), px + PAD, optionsHeadY, C_HEAD);
        g.text(this.font, fit("AI 归一化（可选，默认关；关着时一个字节都不出网）", pw - 2 * PAD),
                px + PAD, aiHeadY, C_HEAD);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        if (tiny) {
            return;
        }
        VoiceModelDownloader.Progress progress = SherpaEngine.downloadProgress();
        boolean running = SherpaEngine.downloading();
        int left = px + PAD;
        int right = px + pw - PAD;
        if (running && progress != null) {
            g.fill(left, barY, right, barY + BAR_H, C_BAR_BG);
            int filled = left + (int) Math.round((right - left) * progress.ratio());
            if (filled > left) {
                g.fill(left, barY, filled, barY + BAR_H, C_BAR_FG);
            }
            g.text(this.font, fit(progress.text(), right - left), left, statusY, C_TEXT);
            return;
        }
        // 「玩家自己点的取消」不算失败：文字已经写成「已取消下载」，就不该再标成红的
        String problem = progress == null ? null : progress.problem();
        boolean bad = problem != null && !problem.isEmpty() && !problem.contains("取消");
        g.text(this.font, fit(status, right - left), left, statusY, bad ? C_BAD : C_GOOD);
    }

    /** 下载是后台线程：只在「刚结束」那一瞬间刷一次按钮与提示，不是每帧都重建控件。 */
    @Override
    public void tick() {
        boolean running = SherpaEngine.downloading();
        if (running != wasDownloading) {
            wasDownloading = running;
            if (!running) {
                VoiceModelDownloader.Progress progress = SherpaEngine.downloadProgress();
                if (progress != null && progress.finished()
                        && (progress.problem() == null || progress.problem().isEmpty())) {
                    SherpaEngine.afterDownload(progress.modelId());
                }
                String notice = SherpaEngine.consumeNotice();
                String problem = progress == null ? null : progress.problem();
                if (notice != null && !notice.isEmpty()) {
                    status = notice;
                } else if (problem != null && problem.contains("取消")) {
                    // 玩家自己点的取消不算「下载没成功」，别把提示写成失败
                    status = "已取消下载";
                } else if (problem != null && !problem.isEmpty()) {
                    status = "下载没成功：" + problem;
                } else {
                    status = "下载结束了";
                }
            }
            rebuildWidgets();
        }
    }

    /** 关掉界面时把未保存的改动落地 —— 玩家不该因为「忘了点保存」而丢掉选择。 */
    @Override
    public void onClose() {
        if (dirty) {
            SherpaEngine.saveAndApply();
            dirty = false;
        }
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    private static String stateShort(VoiceModel model) {
        // 「已装」= 硬盘上有完整的一份（新下载的，或老版本解压出来的）；一个模型都不内置
        if (VoiceModelStore.installed(model)) {
            return "已装";
        }
        return "未下载 " + model.displaySize();
    }

    static String shortName(String id) {
        VoiceModel model = VoiceModelCatalog.byId(id);
        return model == null ? (id == null ? "?" : id) : shortLabel(model);
    }

    /** 副模型那一行要放下 5 个按钮，全名放不下：砍掉「（推荐）」这类注解，再长就硬截。 */
    private static String shortLabel(VoiceModel model) {
        String text = model.label();
        int cut = text.indexOf('（');
        if (cut > 0) {
            text = text.substring(0, cut);
        }
        text = text.trim();
        return text.length() > 10 ? text.substring(0, 10) : text;
    }

    /** 逐字截断到可用宽度。字体量宽是唯一可靠的依据：中文一个字大约两个 ASCII 宽，但字体不一定这么算。 */
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
        if (this.font.width(cut + "…") <= max) {
            return cut + "…";
        }
        // 连「一个字 + 省略号」都放不下时，宁可退成单个字，再放不下就干脆不画 —— 绝不画到可用宽度之外。
        return this.font.width(cut.substring(0, 1)) <= max ? cut.substring(0, 1) : "";
    }

    /** 26.3 的 fill 收的是两个角坐标，不是宽高；这里用四次 fill 画边框，避免再次踩「把宽高当右下角」。 */
    private static void border(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        g.fill(x0, y0, x1, y0 + 1, color);
        g.fill(x0, y1 - 1, x1, y1, color);
        g.fill(x0, y0 + 1, x0 + 1, y1 - 1, color);
        g.fill(x1 - 1, y0 + 1, x1, y1 - 1, color);
    }
}
