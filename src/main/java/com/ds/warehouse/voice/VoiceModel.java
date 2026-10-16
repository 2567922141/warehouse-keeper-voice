package com.ds.warehouse.voice;

import java.util.List;

/**
 * 一个「可安装的语音模型」的元数据：叫什么、干什么用、从哪来、要哪些文件。
 *
 * <p><b>语音包不内置任何模型</b>：目录表里的每个模型都要玩家自己在设置页下载。
 * 这样做的好处是包体小（约 8 MB，只有 sherpa 的 Java 类与 Windows 原生库），
 * 玩家只为自己真正要用的那一份模型花流量与磁盘。
 *
 * <p>这个类型刻意做成 record：它是**纯数据**，相等性只应该由「是不是同一个 id」决定
 * （{@link VoiceModelCatalog#byId} 也用 id 找人），自己写 equals/hashCode 只会多出
 * 一堆没人维护的样板，还容易漏字段。
 *
 * @param id        稳定标识，小写 ASCII + 连字符（如 {@code sense-voice}）；设置文件里存的就是它，
 *                  **改名等于让老用户的设置失效**，所以一旦发布就不能再动
 * @param label     界面上显示的中文名（如 {@code SenseVoice（推荐）}）
 * @param note      一句话特点，用作设置页副标题（说明为什么不选它/为什么推荐它）
 * @param kind      网络结构类型
 * @param legacyDir 老版本已经解压好的目录名，相对 {@code <config>/warehouse-keeper-voice/}，可为 null。
 *                  留着它的唯一目的是**别让老用户把几百 MB 重下一遍**（0.2.1 及更早把内置的
 *                  Paraformer 小模型解压在这里，升级后那一份照样算「已装」）
 * @param files     需要落盘的文件清单（含每个文件的字节数与 sha256，用来判断装得对不对）
 */
public record VoiceModel(String id, String label, String note, VoiceKind kind,
                         String legacyDir, List<VoiceFile> files) {

    /** 把清单收成只读副本：清单是共享数据，调用方改不动它才不会出现「界面上少了一个文件」。 */
    public VoiceModel {
        files = files == null ? List.of() : List.copyOf(files);
    }

    /** 要落盘的总字节数：下载进度、界面上的「需要多少空间」都用它。 */
    public long totalBytes() {
        long sum = 0L;
        for (VoiceFile file : files) {
            sum += file.bytes();
        }
        return sum;
    }

    /** 按落盘文件名找清单里的一项；找不到返回 null。 */
    public VoiceFile file(String name) {
        for (VoiceFile file : files) {
            if (file.name().equals(name)) {
                return file;
            }
        }
        return null;
    }

    /**
     * 界面上的体积文案，如 {@code 78 MB} / {@code 228 MB}。
     *
     * <p>用 1 MB = 1048576 字节（和资源管理器、下载工具显示的口径一致），保留整数：
     * 差几 MB 对玩家没有意义，反而是「228 MB」比「228.5 MB」更好读、也不会因为
     * 目录表里的字节数微调而让界面文案跟着抖动。
     */
    public String displaySize() {
        return Math.round(totalBytes() / 1048576.0D) + " MB";
    }
}
