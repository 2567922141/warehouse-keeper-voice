package com.ds.warehouse.voice;

import com.ds.warehouse.api.AddonSettings;
import com.ds.warehouse.api.SimpleRow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 语音包交给**主体面板**渲染的设置页（主体「设置」页里自动出现的二级菜单）。
 *
 * <p>由 {@code fabric.mod.json} 的自定义 entrypoint 声明：
 * <pre>{@code "warehouse-keeper:addon": ["com.ds.warehouse.voice.VoiceAddonSettings"]}</pre>
 * 老版本主体（&lt;1.2.1）不读这个 key，声明了没有副作用；语音自己的 {@code /warehousevoice} 界面照旧。
 *
 * <p><b>三页，翻页由这里自己做</b>（主体只给一个二级菜单，但行数是动态的，所以「页」就是同一个
 * {@link #rows()} 按 {@link #page} 返回不同的行）：第一页常用开关 + 两个入口，第二页模型管理
 * （每模型一行：未装=下载、下载中=取消、已装=删除且两段确认），第三页 AI 归一化（开关、服务、
 * <b>API Key 直接在这里填</b>、地址、模型、<b>一键测连通性</b>、状态）。
 *
 * <p>**按功能分区**：每页里用 {@link SimpleRow#header(String)} 划出「识别模型 / 识别方式 / 模型下载 /
 * AI 功能 / 服务凭据 / 连通性 / 用量」几段，标题带浅底与分隔线 —— 之前一页塞十几行、说明文字连成一片
 * 灰墙，认不出哪个开关管什么。
 *
 * <p>**要选一个的那几行改成真下拉**（{@link SimpleRow#choice}）：主模型、副模型、下载源、AI 服务。
 * 之前是「点一下换下一个」，选项一多就得连点十几次才能绕回来。老版本主体不认识下拉，会退化成
 * 原来那套循环（{@link #choiceRow} 里两个都留着）。
 *
 * <p>API Key 那几行是 {@link Kind#TEXT}：主体 1.2.1 起把它画成**真的输入框**（回车提交，见
 * {@code WarehouseScreen.addonTextBox}），提交后立刻 {@code saveAndApply()} 落盘并生效。所以
 * 「填 Key」这件事不用再跑一趟 {@code /warehousevoice}；两边改的是同一份 {@code settings.txt}。
 * 「测试连通性」在后台线程真发一个最短请求（{@link VoiceRewrite#probe}），只显示延迟与结果，
 * **不占用每天的调用次数**。
 *
 * <p>每次 {@link #rows()} 都**重新读** {@link SherpaEngine} 的当前值，点击后立刻 {@code saveAndApply()} 落盘并生效，
 * 所以面板与语音自己的界面看到的永远是同一份 {@code settings.txt}（接口契约里那条）。
 */
public final class VoiceAddonSettings implements AddonSettings {

    /** 第一页：常用开关 + 入口。 */
    private static final int PAGE_MAIN = 0;
    /** 第二页：模型管理（下载 / 取消 / 删除）。 */
    private static final int PAGE_MODELS = 1;
    /** 第三页：AI 归一化（含 API Key 输入框）。 */
    private static final int PAGE_AI = 2;

    /**
     * 两段式删除的扳机：第一次点只是「举起枪」，第二次才真删。
     * 一个模型是几百 MB，不该被一次误点删掉。
     */
    private static volatile String pendingDeleteId = "";

    /** 停在哪个内部页；面板关掉再打开（隔了一会儿没人问 {@link #rows()}）自动回第一页。 */
    private static volatile int page = PAGE_MAIN;
    /** 上一次被问 {@link #rows()} 的时刻，与 {@link #PAGE_KEEP_MILLIS} 配对判断「面板是不是刚重开」。 */
    private static volatile long pageStamp;

    /**
     * 隔多久没问 {@link #rows()} 就算「面板重开过一次」。
     *
     * <p>面板开着时主体每 8 tick（约 0.4 秒）问一次做指纹比对（{@code WarehouseScreen.addonTick}），
     * 所以这个值要明显大于 0.4 秒：卡顿一下不该把页翻回去，取值 2.5 秒。面板关着时根本没人问，
     * 下次打开自然回到第一页。
     */
    private static final long PAGE_KEEP_MILLIS = 2500L;

    @Override
    public String id() {
        return "warehouse-keeper-voice";
    }

    @Override
    public String title() {
        return "语音取货";
    }

    @Override
    public String summary() {
        return "按住按键说话取货；主/副模型、下载源、模型下载与删除、AI 归一化与 API Key 都在这里填（和 /warehousevoice 是同一份设置）";
    }

    @Override
    public List<Row> rows() {
        long now = System.currentTimeMillis();
        if (now - pageStamp > PAGE_KEEP_MILLIS) {
            page = PAGE_MAIN;
        }
        pageStamp = now;
        return switch (page) {
            case PAGE_MODELS -> modelRows();
            case PAGE_AI -> aiRows();
            default -> mainRows();
        };
    }

    // ------------------------------------------------------------------ 第一页：常用

    /** 常用开关 + 「模型管理」/「AI 归一化」两个入口，按功能分三段。 */
    private List<Row> mainRows() {
        boolean idle = !SherpaEngine.downloading();
        List<Row> out = new ArrayList<>();
        List<VoiceModel> models = VoiceModelCatalog.all();
        // 下拉条目：名字给人看，key 给引擎用，两张表一一对应
        List<String> modelNames = new ArrayList<>();
        List<String> modelKeys = new ArrayList<>();
        for (VoiceModel model : models) {
            modelNames.add(shortName(model.id()));
            modelKeys.add(model.id());
        }
        List<String> secondaryNames = new ArrayList<>();
        List<String> secondaryKeys = new ArrayList<>();
        secondaryNames.add("关");
        secondaryKeys.add("off");
        secondaryNames.addAll(modelNames);
        secondaryKeys.addAll(modelKeys);

        out.add(headerRow("识别模型"));
        out.add(choiceRow("主模型", modelNames, modelKeys, SherpaEngine.primaryId(), idle,
                idle ? "识别就用它；在右边下拉里选一个" : "正在下载模型，先别改",
                this::setPrimaryTo, this::nextPrimary));
        out.add(choiceRow("副模型", secondaryNames, secondaryKeys, SherpaEngine.secondaryId(), idle,
                idle ? "同时识别用来对照；「关」= 只用主模型" : "正在下载模型，先别改",
                this::setSecondaryTo, this::nextSecondary));

        out.add(headerRow("识别方式"));
        out.add(SimpleRow.toggle("对照显示", SherpaEngine.compareOn(),
                "把副模型的结果也画出来", this::toggleCompare));
        out.add(SimpleRow.toggle("并行识别", SherpaEngine.parallelOn(),
                "两套模型同时跑：更快，更吃 CPU", this::toggleParallel));
        out.add(SimpleRow.toggle("预加载模型", SherpaEngine.preloadOn(),
                "进游戏先把模型读进内存，第一次说话不用等", this::togglePreload));

        out.add(headerRow("模型下载"));
        out.add(choiceRow("下载源", mirrorNames(), mirrorKeys(), SherpaEngine.mirrorMode(), true,
                "默认官方原始源；国内网络慢就换镜像", this::setMirrorTo, this::nextMirror));

        int installed = 0;
        for (VoiceModel model : models) {
            if (installedNow(model)) {
                installed++;
            }
        }
        out.add(SimpleRow.action("模型管理", installed + "/" + models.size() + " 个已装 ▸",
                "下载、取消、删除都在里面", () -> page = PAGE_MODELS));

        out.add(headerRow("AI 功能"));
        out.add(SimpleRow.action("AI 归一化", aiSwitchLabel(),
                "开关、服务、API Key、连接测试都在里面", () -> page = PAGE_AI));
        return out;
    }

    /** 下载源的候选名（{@link VoiceMirrors#label(String)} 给的人话），顺序与 {@link #mirrorKeys()} 一致。 */
    private static List<String> mirrorNames() {
        List<String> names = new ArrayList<>();
        for (String mode : mirrorKeys()) {
            names.add(VoiceMirrors.label(mode));
        }
        return names;
    }

    /** 下载源的候选 key（第一个是默认的官方原始源）。 */
    private static List<String> mirrorKeys() {
        List<String> keys = VoiceModelCatalog.mirrorChoices();
        // 老版本目录表可能给空表：留一条「官方原始源」，免得下拉是空的
        return keys.isEmpty() ? List.of("official") : keys;
    }

    // ------------------------------------------------------------------ 第二页：模型管理

    /** 一行活的「下载状态」+ 每个模型一行 + 返回。 */
    private List<Row> modelRows() {
        List<Row> out = new ArrayList<>();
        out.add(headerRow("模型下载"));
        VoiceModelDownloader.Progress progress = SherpaEngine.downloadProgress();
        boolean downloading = SherpaEngine.downloading() && progress != null;
        out.add(SimpleRow.info("下载状态", statusValue(progress, downloading), statusNote(progress, downloading)));

        // 每个模型一行，右边按钮就一个：「下载」/「取消」/「删除」（删除要点两下）。
        for (VoiceModel model : VoiceModelCatalog.all()) {
            boolean installed = installedNow(model);
            boolean thisOne = downloading && model.id().equals(progress.modelId());
            String button;
            if (thisOne) {
                button = "取消";
            } else if (installed) {
                button = pendingDeleteId.equals(model.id()) ? "确认删除" : "删除";
            } else {
                button = "下载";
            }
            String state = thisOne ? "下载中" : installed ? "已安装" : "未安装";
            out.add(SimpleRow.action(model.label(), button, modelNote(model, state), () -> act(model)));
        }
        out.add(SimpleRow.action("返回", "◂ 返回", "回到常用开关", () -> page = PAGE_MAIN));
        return out;
    }

    // ------------------------------------------------------------------ 第三页：AI 归一化

    /** AI 开关 + 服务 + **Key / 地址 / 模型输入框** + 连接测试 + 状态 + 返回，按功能分三段。 */
    private List<Row> aiRows() {
        List<Row> out = new ArrayList<>();
        out.add(headerRow("AI 功能"));
        out.add(SimpleRow.toggle("AI 归一化", SherpaEngine.rewriteOn(), aiNote(), this::toggleRewrite));
        List<String> modeKeys = VoiceRewrite.modeIds();
        List<String> modeNames = new ArrayList<>();
        for (String id : modeKeys) {
            modeNames.add(VoiceRewrite.modeLabel(id));
        }
        out.add(choiceRow("改写方式", modeNames, modeKeys, SherpaEngine.rewriteMode(), true,
                "决定什么时候问 AI：两个一起＝本地没把握才问；完全调用 AI＝每句先问；完全本地＝一次不问",
                this::setRewriteModeTo, this::nextRewriteMode));
        out.add(choiceRow("AI 服务", serviceNames(), serviceKeys(), currentServiceKey(), true,
                "本地听不出物品名时才问一次；换服务商会把模型带回默认", this::setRewriteServiceTo,
                this::nextRewriteService));

        // Key / 地址 / 模型：主体把 TEXT 行画成输入框，回车或离开这一页就提交（见 rows() 上面的说明）。
        // 盒子里的 value 只是**占位提示**：Key 显示打码预览，玩家一敲字就整段覆盖。
        out.add(headerRow("服务凭据"));
        if (!SherpaEngine.rewriteLocal()) {
            out.add(secretRow("API Key", keyHint(), "粘贴后按回车；只显示前 4 位", this::setRewriteKey));
        }
        out.add(textRow("AI 地址", urlHint(), "换服务商就改这里；回车后生效", this::setRewriteUrl));
        // 模型：常用模型给一个下拉（智谱默认 glm-4-flash-250414，实测比 glm-4.7-flash 快很多），
        // 下拉里没有的名字用下面的输入框手填。
        List<String> modelNames = modelNames();
        List<String> modelKeys = modelKeys();
        if (!modelKeys.isEmpty()) {
            out.add(choiceRow("AI 模型", modelNames, modelKeys, SherpaEngine.rewriteModel(), true,
                    "点一下换一个；下拉里没有的名字用下面那行手填", this::setRewriteModelTo, null));
        }
        out.add(textRow("自定义模型", modelHint(), "手填模型名（留空＝用该服务的默认模型）", this::setRewriteModel));
        out.add(choiceRow("AI 超时", TIMEOUT_NAMES, TIMEOUT_KEYS, String.valueOf(SherpaEngine.rewriteTimeout()),
                true, "本地听不出时最多等多久；网络慢就往上调", this::setRewriteTimeoutTo, null));
        if (!SherpaEngine.rewriteLocal() && !SherpaEngine.rewriteKey().isBlank()) {
            // 「清空」不做成「把输入框清空再提交」：玩家点一下输入框又点走就可能把 Key 洗掉。
            // 单独给一行按钮，点了才删。
            out.add(SimpleRow.action("清除 API Key", "清空", "把已经存下的 Key 删掉", this::clearRewriteKey));
        }

        // 连通性：主动发一个最短请求，量出延迟；和下面的「AI 状态」（累计用量）不是一回事。
        out.add(headerRow("连通性"));
        out.add(SimpleRow.action("测试连通性", probing ? "测试中…" : "开始测试",
                "真发一个最短请求量延迟；不计入每天的调用次数",
                !probing, this::startProbe));
        out.add(SimpleRow.info("连通性", probeValue(), probeNote()));

        out.add(headerRow("用量"));
        out.add(SimpleRow.info("AI 状态", aiStatusValue(), aiStatusNote()));
        out.add(SimpleRow.action("返回", "◂ 返回", "回到常用开关", () -> page = PAGE_MAIN));
        return out;
    }

    /** 服务商下拉的候选名（预设标签去掉括号说明）与 key（预设 id），两张表同序。 */
    private static List<String> serviceNames() {
        List<String> names = new ArrayList<>();
        for (VoiceRewrite.Preset preset : VoiceRewrite.presets()) {
            names.add(shortPresetName(preset));
        }
        return names;
    }

    private static List<String> serviceKeys() {
        List<String> keys = new ArrayList<>();
        for (VoiceRewrite.Preset preset : VoiceRewrite.presets()) {
            keys.add(preset.id());
        }
        return keys;
    }

    private static String shortPresetName(VoiceRewrite.Preset preset) {
        String label = preset.label();
        int paren = label.indexOf('（');
        return paren > 0 ? label.substring(0, paren) : label;
    }

    /**
     * 服务商下拉当前该选哪一条。
     *
     * <p>手改过 {@code rewrite.url=} 时以地址为准（{@link VoiceRewrite#presetOfUrl}）：玩家把地址改成
     * 别家的，下拉就该停在那一档，而不是嘴上还挂着「智谱」。地址没填时才看设置里选的预设 id
     * （「自定义」预设本来就没有默认地址，这时候按地址认会认成智谱，显示就错了）。
     */
    private static String currentServiceKey() {
        String url = SherpaEngine.rewriteUrl();
        return url.isBlank() ? SherpaEngine.rewritePreset() : VoiceRewrite.presetOfUrl(url).id();
    }

    // ------------------------------------------------------------------ 连通性自检

    /** 「测试连通性」的最近结果：null = 还没测过。 */
    private static volatile VoiceRewrite.Probe probeResult;
    /** 是不是正在测（后台线程里跑，面板每 0.4 秒会重新读这一行）。 */
    private static volatile boolean probing;

    /**
     * 点「开始测试」：开一条后台线程真发一个最短请求。
     *
     * <p>为什么不在主线程做：最长会阻塞到超时（几秒），客户端主线程一卡整个游戏都在掉帧。
     * 结果写在 {@link #probeResult}，面板下一次刷新（≤0.4 秒）就画出来了。
     */
    private void startProbe() {
        if (probing) {
            return;
        }
        probing = true;
        probeResult = null;
        String url = SherpaEngine.rewriteUrl();
        String model = SherpaEngine.rewriteModel();
        String key = SherpaEngine.rewriteKey();
        int timeout = SherpaEngine.rewriteTimeout();
        Thread worker = new Thread(() -> {
            VoiceRewrite.Probe result;
            try {
                result = VoiceRewrite.probe(url, model, key, timeout, VoiceRewrite.httpTransport());
            } catch (Throwable t) {
                result = new VoiceRewrite.Probe(false, 0L, "测试出错：" + t.getClass().getSimpleName());
            }
            probeResult = result;
            probing = false;
        }, "warehouse-keeper-voice-connectivity");
        worker.setDaemon(true);
        worker.start();
    }

    /** 连通性那一行的值：通/不通 + 延迟。 */
    private static String probeValue() {
        if (probing) {
            return "测试中…";
        }
        VoiceRewrite.Probe result = probeResult;
        if (result == null) {
            return "还没测过";
        }
        return (result.ok() ? "通 · " : "不通 · ") + result.millis() + " ms";
    }

    /** 连通性那一行的说明：服务商回了什么、或者为什么不通。 */
    private static String probeNote() {
        if (probing) {
            return "正在发一个最短请求，稍等（最多几秒）";
        }
        VoiceRewrite.Probe result = probeResult;
        if (result == null) {
            return "点上面的「开始测试」；测的是当前地址 + 当前 Key";
        }
        return result.detail();
    }

    /** 下载中显示百分比与文件名，否则显示「已装几个、占多大」。 */
    private static String statusValue(VoiceModelDownloader.Progress progress, boolean downloading) {
        if (downloading) {
            long percent = Math.round(progress.ratio() * 100.0D);
            String at = progress.fileCount() > 1
                    ? "（第 " + progress.fileIndex() + "/" + progress.fileCount() + " 个文件）" : "";
            return percent + "% · " + shortName(progress.modelId()) + at;
        }
        int installed = 0;
        long bytes = 0L;
        List<VoiceModel> models = VoiceModelCatalog.all();
        Map<String, Boolean> states = installedMap();
        for (VoiceModel model : models) {
            if (Boolean.TRUE.equals(states.get(model.id()))) {
                installed++;
                bytes += model.totalBytes();
            }
        }
        return "已装 " + installed + "/" + models.size() + " · 占 " + mb(bytes);
    }

    /** 下载中的提示、上次失败的原文、或者「管理在哪」的一句话。 */
    private static String statusNote(VoiceModelDownloader.Progress progress, boolean downloading) {
        if (downloading) {
            return "正在下载，点这一行的「取消」可以停下";
        }
        String problem = progress == null ? "" : progress.problem();
        if (problem != null && !problem.isEmpty()) {
            return "上次没下成：" + problem;
        }
        return "大小指下载完占多少硬盘";
    }

    private static String mb(long bytes) {
        if (bytes <= 0L) {
            return "0 MB";
        }
        return (bytes / 1048576L) + " MB";
    }

    /**
     * 一个模型行的说明：状态 + 体积 + **一句话**特点。
     *
     * <p>目录表里的 {@link VoiceModel#note()} 是给文档/语音自己的界面用的完整描述（有的句子
     * 里面又把体积写了一遍），直接贴到面板上就成了「已安装 · 358 MB · 350 MB 的 int8 大模型…」——
     * 体积说两遍。这里先把体积从句子里去掉，再砍到一句话。
     */
    private static String modelNote(VoiceModel model, String state) {
        return state + " · " + model.displaySize() + " · " + briefNote(model);
    }

    /** 去重后的那一句话：先去掉句子里的体积，再砍掉括号补充与第二个分句。 */
    private static String briefNote(VoiceModel model) {
        String size = model.displaySize();
        String text = model.note()
                .replace(size + "，", "")
                .replace(size + " 的", "")
                .replace("，" + size, "");
        int paren = text.indexOf('（');
        if (paren >= 8) {
            text = text.substring(0, paren);
        }
        int semi = text.indexOf('；');
        if (semi > 0) {
            text = text.substring(0, semi);
        }
        return text.length() > 26 ? text.substring(0, 26) + "…" : text;
    }

    /**
     * 一个模型行的点击行为，和 {@code VoiceSettingsScreen.actionButton} 一模一样：
     * 正在下这个 → 取消；已装 → 第一下举起「确认删除」、第二下真删；没装 → 开始下载。
     */
    private void act(VoiceModel model) {
        VoiceModelDownloader.Progress progress = SherpaEngine.downloadProgress();
        boolean thisOne = SherpaEngine.downloading() && progress != null && model.id().equals(progress.modelId());
        if (thisOne) {
            pendingDeleteId = "";
            SherpaEngine.cancelDownload();
            invalidateStates();
            return;
        }
        if (installedNow(model)) {
            if (pendingDeleteId.equals(model.id())) {
                pendingDeleteId = "";
                VoiceModelStore.delete(model);
                SherpaEngine.forget(model.id());
            } else {
                pendingDeleteId = model.id();
            }
            invalidateStates();
            return;
        }
        pendingDeleteId = "";
        SherpaEngine.download(model.id());
        invalidateStates();
    }

    // ------------------------------------------------------------------ 点击行为

    private void nextPrimary() {
        List<VoiceModel> models = VoiceModelCatalog.all();
        if (models.isEmpty()) {
            return;
        }
        int at = indexOf(models, SherpaEngine.primaryId());
        SherpaEngine.setPrimary(models.get((at + 1) % models.size()).id());
        SherpaEngine.saveAndApply();
    }

    /** 副模型的循环多一档「关」：关 → 模型 1 → 模型 2 → … → 关。 */
    private void nextSecondary() {
        List<VoiceModel> models = VoiceModelCatalog.all();
        String now = SherpaEngine.secondaryId();
        String next = "off";
        if (!"off".equals(now)) {
            int at = indexOf(models, now);
            if (at + 1 < models.size()) {
                next = models.get(at + 1).id();
            }
        }
        SherpaEngine.setSecondary(next);
        SherpaEngine.saveAndApply();
    }

    private void toggleCompare() {
        SherpaEngine.setCompare(!SherpaEngine.compareOn());
        SherpaEngine.saveAndApply();
    }

    private void toggleParallel() {
        SherpaEngine.setParallel(!SherpaEngine.parallelOn());
        SherpaEngine.saveAndApply();
    }

    private void togglePreload() {
        SherpaEngine.setPreload(!SherpaEngine.preloadOn());
        SherpaEngine.saveAndApply();
    }

    /** 在 {@link VoiceModelCatalog#mirrorChoices()} 里循环（第一个是默认的官方原始源）。 */
    private void nextMirror() {
        List<String> choices = VoiceModelCatalog.mirrorChoices();
        if (choices.isEmpty()) {
            return;
        }
        int at = choices.indexOf(SherpaEngine.mirrorMode());
        SherpaEngine.setMirrorMode(choices.get((at + 1) % choices.size()));
        SherpaEngine.saveAndApply();
    }

    // ------------------------------------------------------------------ 下拉选中（新面板走这几个）

    private void setPrimaryTo(String id) {
        SherpaEngine.setPrimary(id);
        SherpaEngine.saveAndApply();
    }

    private void setSecondaryTo(String id) {
        SherpaEngine.setSecondary(id);
        SherpaEngine.saveAndApply();
    }

    private void setMirrorTo(String mode) {
        SherpaEngine.setMirrorMode(mode);
        SherpaEngine.saveAndApply();
    }

    /** 换服务商：顺手把模型带回该预设的默认值，免得留着一个别家的名字。 */
    private void setRewriteServiceTo(String presetId) {
        SherpaEngine.setRewritePreset(presetId);
        SherpaEngine.setRewriteModel("");
        SherpaEngine.saveAndApply();
    }

    // ------------------------------------------------------------------ AI 归一化（第 ② 步）

    /**
     * 一个可编辑的 {@link AddonSettings.Kind#TEXT} 行。
     *
     * <p>带 {@code setter} 的那两个工厂是主体 1.2.1 **本次构建**才加的（面板里的 api 包先落的是
     * 只读的那一个）。老主体上调用会抛 {@link NoSuchMethodError}；本包声明的主体依赖是
     * {@code >=1.1.4}（那样语音包本身的功能在老主体上还能用），所以这里退成只读一行 ——
     * 老主体上玩家还可以去 {@code /warehousevoice} 填，总比整个 AI 页读不出来强。
     */
    private static Row textRow(String label, String value, String note,
                               java.util.function.Consumer<String> setter) {
        try {
            return SimpleRow.text(label, value, note, setter);
        } catch (Throwable t) {
            return SimpleRow.text(label, value, note + "｜去 /warehousevoice 填");
        }
    }

    /** 同 {@link #textRow}，只是这一行按敏感内容画（新主体上显示星号）。 */
    private static Row secretRow(String label, String value, String note,
                                 java.util.function.Consumer<String> setter) {
        try {
            return SimpleRow.secret(label, value, note, setter);
        } catch (Throwable t) {
            return SimpleRow.text(label, value, note + "｜去 /warehousevoice 填");
        }
    }

    private void toggleRewrite() {
        SherpaEngine.setRewrite(!SherpaEngine.rewriteOn());
        SherpaEngine.saveAndApply();
    }

    /** 在四个预设里换下一个；换完把模型带回该预设的默认值，免得留着一个别家的名字。 */
    private void nextRewriteService() {
        List<VoiceRewrite.Preset> presets = VoiceRewrite.presets();
        if (presets.isEmpty()) {
            return;
        }
        String now = SherpaEngine.rewritePreset();
        int at = 0;
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i).id().equals(now)) {
                at = i;
            }
        }
        VoiceRewrite.Preset next = presets.get((at + 1) % presets.size());
        SherpaEngine.setRewritePreset(next.id());
        SherpaEngine.setRewriteModel("");
        SherpaEngine.saveAndApply();
    }

    /** 面板输入框提交：Key（{@code Row.secret} 行，主体显示打码）。 */
    private void setRewriteKey(String value) {
        SherpaEngine.setRewriteKey(value);
        SherpaEngine.saveAndApply();
    }

    /** 面板输入框提交：接口地址。 */
    private void setRewriteUrl(String value) {
        SherpaEngine.setRewriteUrl(value);
        SherpaEngine.saveAndApply();
    }

    /** 面板输入框提交：模型名。 */
    private void setRewriteModel(String value) {
        SherpaEngine.setRewriteModel(value);
        SherpaEngine.saveAndApply();
    }

    /** 「AI 模型」下拉：选中即落盘（key 就是模型名本身）。 */
    private void setRewriteModelTo(String model) {
        SherpaEngine.setRewriteModel(model);
        SherpaEngine.saveAndApply();
    }

    /** 该服务商常用的模型名 / 同名 key（下拉用；认不出的服务商给空表，只显示手填那行）。 */
    private static List<String> modelNames() {
        return VoiceRewrite.models(currentServiceKey());
    }

    private static List<String> modelKeys() {
        return VoiceRewrite.models(currentServiceKey());
    }

    /** 「改写方式」下拉：选中即落盘。取值就是 {@link VoiceRewrite#modeIds()}。 */
    private void setRewriteModeTo(String modeId) {
        SherpaEngine.setRewriteMode(modeId);
        SherpaEngine.saveAndApply();
    }

    /** 老面板没有下拉时的退化动作：按 {@link VoiceRewrite#modeIds()} 的顺序换下一个。 */
    private void nextRewriteMode() {
        List<String> ids = VoiceRewrite.modeIds();
        int at = ids.indexOf(SherpaEngine.rewriteMode());
        SherpaEngine.setRewriteMode(ids.get((Math.max(0, at) + 1) % ids.size()));
        SherpaEngine.saveAndApply();
    }

    /** 「AI 超时」下拉的候选（1/2/3/5 秒）与 key（毫秒字符串）。 */
    private static final List<String> TIMEOUT_NAMES = List.of("1 秒", "2 秒", "3 秒（默认）", "5 秒");
    private static final List<String> TIMEOUT_KEYS = List.of("1000", "2000", "3000", "5000");

    /** 「AI 超时」下拉：选中即落盘。 */
    private void setRewriteTimeoutTo(String millis) {
        try {
            SherpaEngine.setRewriteTimeout(Integer.parseInt(millis.trim()));
            SherpaEngine.saveAndApply();
        } catch (NumberFormatException ignored) {
            // 下拉里的值都是本地常量，解析不了就当没点过
        }
    }

    /** 「清除 API Key」按钮：清空后落盘（等于把 settings.txt 里那行删掉）。 */
    private void clearRewriteKey() {
        SherpaEngine.setRewriteKey("");
        SherpaEngine.saveAndApply();
    }

    /** 入口那一行的按钮文字：既显示开关状态，又暗示「点进去」。 */
    private static String aiSwitchLabel() {
        return SherpaEngine.rewriteOn() ? "开 ▸" : "关 ▸";
    }

    /**
     * 分组小标题。
     *
     * <p>{@link SimpleRow#header(String)} 是主体 1.2.1 本次构建才加的工厂，老主体上调用会抛
     * {@link NoSuchMethodError}（本包声明的主体依赖是 {@code >=1.1.4}）。老主体上退化成一页普通信息行：
     * 少了那条分隔线，但每一段的分法还在，聊胜于无。
     */
    private static Row headerRow(String title) {
        try {
            return SimpleRow.header(title);
        } catch (Throwable t) {
            return SimpleRow.info(title, "", "");
        }
    }

    /**
     * 下拉选择行：新面板画一个真下拉菜单，老面板退化成原来那套「点一下换下一个」。
     *
     * <p>{@code names} 与 {@code keys} 一一对应（名字给人看，key 给引擎用）；{@code currentKey} 在当前
     * 选项里找不到时下标按 0 算，{@link #fallback} 是给老面板的循环动作（可以为 null，那就自己按下标 +1）。
     */
    private static Row choiceRow(String label, List<String> names, List<String> keys, String currentKey,
                                 boolean enabled, String note, java.util.function.Consumer<String> picker,
                                 Runnable fallback) {
        int at = keys.indexOf(currentKey);
        String shown = at >= 0 ? names.get(at) : "";
        if (!enabled) {
            // 正在下载模型这类：老样子给一个点不动的循环行（不建下拉，免得玩家在下拉里改到半路）
            return SimpleRow.cycle(label, shown, note, null);
        }
        try {
            return SimpleRow.choice(label, names, Math.max(0, at), note, index -> {
                if (index >= 0 && index < keys.size()) {
                    picker.accept(keys.get(index));
                }
            });
        } catch (Throwable t) {
            Runnable act = fallback != null ? fallback : () -> {
                if (!keys.isEmpty()) {
                    picker.accept(keys.get((Math.max(0, at) + 1) % keys.size()));
                }
            };
            return SimpleRow.cycle(label, shown, note, act);
        }
    }

    /** 开关那一行的说明：关着时把「不出网」写明白，开着时提醒 Key 的事（就在这一页填）。 */
    private static String aiNote() {
        if (!SherpaEngine.rewriteOn()) {
            return "可选：本地听不出物品名时问一次云端小模型；关着一个字节都不出网";
        }
        if (SherpaEngine.rewriteLocal()) {
            return "本机 Ollama：完全离线，不用填 Key；方式「" + SherpaEngine.rewriteModeLabel() + "」";
        }
        if (SherpaEngine.rewriteKey().isBlank()) {
            return "开着但还没填 API Key —— 翻到下面那一行填，填之前不会发请求";
        }
        return "方式「" + SherpaEngine.rewriteModeLabel()
                + "」；只把识别文字和前 8 名候选发给你选的服务，不发音频";
    }

    /**
     * 状态那一行的值。
     *
     * <p>为什么不用 {@link VoiceRewrite#shortStatus()}：那个串是给语音自己的界面写的，面板这里
     * 需要先分辨「开着但没 Key」这种一眼就该看出来的状态，而不是把「0 次 · — · 未启用」摆上去。
     */
    private static String aiStatusValue() {
        if (!SherpaEngine.rewriteOn()) {
            return "未开启";
        }
        VoiceRewrite.Usage usage = VoiceRewrite.usage();
        if (!SherpaEngine.rewriteLocal() && SherpaEngine.rewriteKey().isBlank()) {
            return "缺 API Key · 今日 " + usage.today() + " 次";
        }
        String last = usage.lastMillis() <= 0L ? "—" : usage.lastMillis() + " ms";
        return usage.today() + " 次 · " + last + " · " + VoiceRewrite.describe(usage.lastStatus());
    }

    /** 状态那一行的补充说明：上一次为什么不成功（只读内存里的计数器）。 */
    private static String aiStatusNote() {
        if (!SherpaEngine.rewriteOn()) {
            return "打开后：本地听不出时才问一次，仍由本地匹配器决定下不下单";
        }
        if (SherpaEngine.rewriteLocal()) {
            return "找本机 Ollama；没启动会退到本地结果";
        }
        if (SherpaEngine.rewriteKey().isBlank()) {
            return "还没填 API Key，不会有请求发出去；在上面那行粘贴后回车";
        }
        VoiceRewrite.Usage usage = VoiceRewrite.usage();
        return switch (usage.lastStatus()) {
            case OK -> "上次成功：AI 改写 → 本地复判通过才会下单";
            case TIMEOUT -> "上次超时/连不上，已用本地结果";
            case NO_KEY -> "没填 API Key，已跳过";
            case KEY_INVALID -> "上次 Key 无效：开关已自动关掉，检查这一页的 Key";
            case RATE_LIMITED -> "上次被限速（或本地令牌桶拦下），60 秒内不再调用";
            case DISABLED -> "开关是开的，但还没调用过";
            default -> "上次没拿到可用回答，已用本地结果";
        };
    }

    /** Key 输入框的占位提示：没填就说没填，填了给打码预览（真值只在输入框里，屏幕不留原文）。 */
    private static String keyHint() {
        String key = SherpaEngine.rewriteKey();
        if (key.isBlank()) {
            return "未填：粘贴后回车";
        }
        return "已填 " + VoiceRewrite.maskKey(key) + "，可覆盖";
    }

    /** 地址输入框的占位提示：当前真正会用的那个地址（{@code rewriteUrl()} 已按预设解析过）。 */
    private static String urlHint() {
        String url = SherpaEngine.rewriteUrl();
        return url.isBlank() ? "未填地址" : "当前 " + ellipsis(url, 12);
    }

    /** 模型输入框的占位提示：当前真正会用的模型名（没填过就是该预设的默认模型）。 */
    private static String modelHint() {
        String model = SherpaEngine.rewriteModel();
        return model.isBlank() ? "默认模型" : "当前 " + ellipsis(model, 12);
    }

    private static String ellipsis(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    // ------------------------------------------------------------------ 小工具

    /**
     * 「哪个模型已经装好了」的缓存：面板一帧里会问好几次 {@link #rows()}，而 {@code installed()} 要 stat
     * 每个模型的好几个文件 —— 不必每帧去问磁盘。最多每秒重查一次；自己删/下完就立刻作废重查。
     */
    private static volatile long stateStamp;
    private static volatile Map<String, Boolean> stateCache = Map.of();

    private static Map<String, Boolean> installedMap() {
        long now = System.currentTimeMillis();
        Map<String, Boolean> snapshot = stateCache;
        if (snapshot.isEmpty() || now - stateStamp > 1000L) {
            Map<String, Boolean> fresh = new HashMap<>();
            for (VoiceModel model : VoiceModelCatalog.all()) {
                fresh.put(model.id(), SherpaEngine.installed(model));
            }
            snapshot = fresh;
            stateCache = fresh;
            stateStamp = now;
        }
        return snapshot;
    }

    private static boolean installedNow(VoiceModel model) {
        return Boolean.TRUE.equals(installedMap().get(model.id()));
    }

    /** 刚刚删了/下完，缓存里那一条马上就是错的 —— 立即作废，下一帧重新问磁盘。 */
    private static void invalidateStates() {
        stateStamp = 0L;
    }

    private static int indexOf(List<VoiceModel> models, String id) {
        for (int i = 0; i < models.size(); i++) {
            if (models.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** 界面上用的短名：去掉标签里的括号说明，例如「SenseVoice（推荐）」→「SenseVoice」；「off」→「关」。 */
    private static String shortName(String id) {
        if (id == null || id.isBlank() || "off".equalsIgnoreCase(id)) {
            return "关";
        }
        VoiceModel model = VoiceModelCatalog.byId(id);
        if (model == null) {
            return id;
        }
        String label = model.label();
        int cut = label.indexOf('（');
        return cut > 0 ? label.substring(0, cut) : label;
    }
}
