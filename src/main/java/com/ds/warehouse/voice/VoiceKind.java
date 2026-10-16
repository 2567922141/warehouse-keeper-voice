package com.ds.warehouse.voice;

/**
 * 可安装语音模型的**网络结构类型**（不是某个具体模型）。
 *
 * <p>为什么要一个枚举，而不是在模型元数据里写「paraformer」这样的字符串：结构决定了
 * 加载参数（CTC 结构要额外的 {@code bbpe.model} 词表，SenseVoice 不要），以后要在
 * 引擎里按结构分支时，枚举可以被 {@code switch} 穷举，漏掉一种编译器就会报错；
 * 字符串写错了只能等到运行时加载失败才发现 —— 那已经是玩家点「按住说话」的时候了。
 *
 * <p>枚举值本身不带任何加载逻辑：谁去加载由 {@code SherpaEngine} 决定，这里只负责
 * 「叫什么」和「界面上怎么显示」。
 */
public enum VoiceKind {

    /** Paraformer：非流式（offline）结构，sherpa-onnx 里用 {@code OfflineParaformerModelConfig} 加载。 */
    PARAFORMER("Paraformer"),

    /** Zipformer-CTC：CTC 结构，除模型和词表外还要一个 {@code bbpe.model}。 */
    ZIPFORMER_CTC("Zipformer-CTC"),

    /** SenseVoice：一个模型同时认中英日韩粤，输出里还带情感/事件标签。 */
    SENSE_VOICE("SenseVoice");

    private final String label;

    VoiceKind(String label) {
        this.label = label;
    }

    /** 界面上显示的英文短标签；「小/大/推荐」这类营销词不在这里，在 {@link VoiceModel#label()} 里。 */
    public String label() {
        return label;
    }
}
