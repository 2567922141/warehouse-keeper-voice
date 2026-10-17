package com.ds.warehouse.voice;

import java.util.List;
import java.util.Locale;

/**
 * 一个模型目录里**必须落盘的单个文件**：有多大、对不对、从哪儿下。
 *
 * <p>为什么随包分发的内置模型也要写这份清单：内置模型第一次运行时要从 jar 解压到硬盘，
 * 解压中断会留下半个文件。老代码只看「文件存在且大于 0 字节」，于是半个模型被当成好的，
 * 之后每次都加载失败而且永不自愈（见 {@code SherpaEngine.ensureFile} 注释里踩过的坑）。
 * 有了确切字节数，任何地方都能一眼判断「这份文件是不是完整的」，不需要相信「有没有 .part」。
 *
 * <p>{@code sha256} 允许为空：那样下载时只比大小、不做校验。留这个口子不是鼓励省掉校验，
 * 而是因为 HuggingFace 上 {@code tokens.txt} 这类小文件不在 LFS 里，仓库 API 不给哈希，
 * 只能下下来自己算；以后新增条目时可以先把它跑通，再把实测的哈希补上。
 *
 * @param name       落盘文件名（也是下载时的默认远端文件名）
 * @param bytes      确切字节数
 * @param sha256     期望的 sha256，可为 null（见上文）
 * @param repo       HuggingFace 仓库名（{@code owner/name}）
 * @param remoteName 远端文件名，可为 null（那就与 {@link #name} 相同）
 * @param msRepo     魔搭仓库名，可为 null（表示魔搭没有这个文件，只能回退官方）
 * @param msRemote   魔搭上的文件名，可为 null（那就与 {@link #name} 相同）；
 *                   魔搭的文件路径与 HuggingFace 不一定一样，所以要单独存而不是复用 remoteName
 */
public record VoiceFile(String name, long bytes, String sha256, String repo, String remoteName,
                        String msRepo, String msRemote) {

    /** 没有魔搭镜像的文件（魔搭没收录、或还没核对过）走这个构造器。 */
    public VoiceFile(String name, long bytes, String sha256, String repo, String remoteName) {
        this(name, bytes, sha256, repo, remoteName, null, null);
    }

    /**
     * 按下载源模式给出的有序候选地址，调用方从头试到尾（前面的越快/越顺手，后面的兜底）。
     *
     * <p>地址拼法不在这里，在 {@link VoiceMirrors}：它是下载源的唯一权威清单。
     * 这样「设置页能选哪些源」和「实际去哪些地址下」不会各写一份、各改各的。
     *
     * @param mode       下载源 id（{@code official} / {@code hfm} / {@code modelscope} / {@code auto} /
     *                   {@code custom}），认不出按默认的官方处理
     * @param customBase {@code custom} 模式的前缀，可为 null/空（那就只用默认的两个源）
     * @return 去重后的地址表；{@code repo} 为空时返回空表（表示这个文件没地方下，而不是地址写错）
     */
    public List<String> sources(String mode, String customBase) {
        return VoiceMirrors.sources(this, mode, customBase);
    }

    /**
     * 这个文件在魔搭上有没有对应的一份。
     *
     * <p>{@code modelscope} / {@code auto} 模式下用它决定「先试魔搭还是直接走官方」：
     * 没镜像的文件绝不能拼一个不存在的魔搭地址去试（那只会白等一轮超时）。
     */
    public boolean hasModelScope() {
        return VoiceMirrors.hasModelScope(this);
    }

    /** 魔搭上的完整地址；没有镜像返回 null。 */
    public String modelScopeUrl() {
        return VoiceMirrors.modelScopeUrl(this);
    }

    /** 文件大小是否正好是这个数（不是「至少」，多一点少一点都算坏文件）。 */
    public boolean matches(long size) {
        return size == bytes;
    }

    /** 有没有可用的 sha256 校验值。 */
    public boolean hasSha256() {
        return expectedSha256() != null;
    }

    /** 规范化后的 sha256（去空白 + 转小写），没有则返回 null；调用方比较时不用再自己归一化。 */
    public String expectedSha256() {
        if (sha256 == null) {
            return null;
        }
        String text = sha256.trim().toLowerCase(Locale.ROOT);
        return text.isEmpty() ? null : text;
    }
}
