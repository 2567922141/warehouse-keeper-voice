package com.ds.warehouse.voice;

import java.util.LinkedHashSet;
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
 */
public record VoiceFile(String name, long bytes, String sha256, String repo, String remoteName) {

    /**
     * 有序的候选下载地址，调用方从头试到尾（前面的越快/越顺手，后面的兜底）。
     *
     * @param mirrorBase 用户选的镜像根地址，可为 null/空（那就只用默认的两个源）；
     *                   末尾多写的 {@code /} 会被去掉，避免拼出 {@code //} 这种地址
     * @return 去重后的地址表；{@code repo} 为空时返回空表（表示这个文件没地方下，而不是地址写错）
     */
    public List<String> sources(String mirrorBase) {
        String repository = repo == null ? "" : repo.trim();
        if (repository.isEmpty()) {
            return List.of();
        }
        String remote = remoteName == null || remoteName.isBlank() ? name : remoteName.trim();
        String path = repository + "/resolve/main/" + remote;
        LinkedHashSet<String> out = new LinkedHashSet<>(3);
        String mirror = mirrorBase == null ? "" : mirrorBase.trim();
        while (mirror.endsWith("/")) {
            mirror = mirror.substring(0, mirror.length() - 1);
        }
        if (!mirror.isEmpty()) {
            out.add(mirror + "/" + path);
        }
        // 顺序即尝试顺序：hf-mirror 在国内可达，huggingface.co 只在有代理时能通，放最后。
        out.add("https://hf-mirror.com/" + path);
        out.add("https://huggingface.co/" + path);
        return List.copyOf(out);
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
