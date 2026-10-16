package com.ds.warehouse.voice;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Warehouse Keeper 的可选语音附加包（纯客户端）。
 *
 * <p>设计前提：不装它，主体模组一点变化都没有；装了它，也只给「自己这台客户端」
 * 加一个语音输入方式 —— 识别在本机离线做，最终仍然是发一条普通的
 * {@code /warehouse order …} 指令，和服务端权限、搬运工流程完全一致。
 *
 * <p>只注册一个按键（默认 V，按住说话）和一个客户端命令（{@code /warehousevoice}，打开语音设置页）。
 * 「聊天栏里是否多显示另一个模型那一行」由 {@code config/warehouse-keeper-voice/settings.txt} 的
 * {@code compare=} 控制，不占用任何快捷键 —— 主体模组默认用 B 打开仓库面板，附加包不再抢这个键。
 */
public class VoiceMod implements ClientModInitializer {

    private static final Logger LOG = LoggerFactory.getLogger("warehouse-keeper-voice");

    private static KeyMapping talkKey;

    /** 命令登记的「要打开设置页」：不在命令回调里直接开，真正的打开推到下一 tick，见 {@link #openSettings()}。 */
    private static boolean openPending;

    @Override
    public void onInitializeClient() {
        talkKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.warehouse-keeper-voice.talk",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_V,
                KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            VoiceSession.idleTick();
            // 命令登记的设置页：等聊天界面自己关掉之后再开，不然会被它顶掉（原因见 openSettings()）。
            if (openPending) {
                openPending = false;
                openSettingsNow();
            }
            if (talkKey == null) {
                return;
            }
            // 按住说话：看的是按键「是否正被按住」，不是点击计数 ——
            // 长按会不断产生按键重复事件，用计数会在按住期间反复开关。
            if (talkKey.isDown()) {
                VoiceSession.press();
            } else {
                VoiceSession.release();
            }
            if (VoiceSession.recording()) {
                VoiceSession.tick();
            }
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> VoiceSession.abort());

        // 客户端命令（本机自己的设置，不该要求服务器给权限）：
        //   /warehousevoice          打开语音设置页
        //   /warehousevoice status   把当前配置打到聊天栏（截图/报错时用得上）
        //   /wvoice                  同上，图省事
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("warehousevoice")
                    .executes(ctx -> openSettings())
                    .then(ClientCommands.literal("status").executes(ctx -> {
                        ctx.getSource().sendFeedback(Component.literal(statusLine()));
                        return 1;
                    })));
            dispatcher.register(ClientCommands.literal("wvoice").executes(ctx -> openSettings()));
        });

        // 进游戏后在后台把要用的模型都装进内存（只读硬盘上已有的模型文件，不会重新解压）：
        // 这样第一次按住 V 就能直接识别，不用先等模型加载。
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> SherpaEngine.preloadAsync());

        // 调试用自检：加 JVM 参数 -Dwarehousekeeper.voice.selftest=true 时，
        // 启动后在后台线程把识别引擎准备好并打一条日志（不碰麦克风、不产生任何音频）。
        // 平时不会触发，玩家完全无感；用于开发/支持时确认「模型能加载」。
        if (Boolean.getBoolean("warehousekeeper.voice.selftest")) {
            Thread probe = new Thread(() -> {
                long began = System.currentTimeMillis();
                String problem = SherpaEngine.prepare();
                LOG.info("[自检] 主模型 {}：{}（用时 {} 毫秒）", SherpaEngine.primaryLabel(),
                        problem == null ? "就绪" : "失败 " + problem, System.currentTimeMillis() - began);
                if (problem != null) {
                    return;
                }
                // 造一段 1.5 秒测试音，把两个模型都真跑一遍（只验证能加载、能解码）
                int rate = MicCapture.SAMPLE_RATE;
                byte[] pcm = new byte[rate * 3];
                for (int i = 0; i < pcm.length / 2; i++) {
                    short value = (short) (Math.sin(2 * Math.PI * 440 * i / rate) * 9000);
                    pcm[i * 2] = (byte) (value & 0xFF);
                    pcm[i * 2 + 1] = (byte) ((value >> 8) & 0xFF);
                }
                long decodeBegan = System.currentTimeMillis();
                java.util.List<SherpaEngine.Line> lines = SherpaEngine.transcribe(pcm, pcm.length);
                StringBuilder summary = new StringBuilder();
                for (SherpaEngine.Line line : lines) {
                    summary.append(line.label()).append('=')
                            .append(line.problem() == null ? line.text().length() + "字" : "失败 " + line.problem())
                            .append(' ');
                }
                java.util.Arrays.fill(pcm, (byte) 0);
                LOG.info("[自检] 跑了 {} 个模型：{}（用时 {} 毫秒）",
                        lines.size(), summary.toString().trim(), System.currentTimeMillis() - decodeBegan);
            }, "warehouse-keeper-voice-selftest");
            probe.setDaemon(true);
            probe.start();
        }
    }

    /**
     * 命令要打开设置页 —— 这里只「登记」，真正的打开推到下一 tick（{@link #openSettingsNow()}）。
     *
     * <p>26.3 的 {@code ChatScreen.keyPressed} 按回车时是：先 {@code handleChatInput(...)}（客户端命令在这里执行），
     * <b>之后</b>才在 {@code closeOnSubmit} 为真时 {@code Gui.setScreen(null)} 把聊天界面关掉
     * （字节码顺序：invokevirtual handleChatInput → getfield closeOnSubmit → invokevirtual Gui.setScreen）。
     * 如果在命令回调里同步开界面，就会被紧接着的 {@code setScreen(null)} 顶掉 ——
     * 表现就是「设置页闪一下又回到游戏」（0.2.0 的实测现象）。
     */
    private static int openSettings() {
        openPending = true;
        return 1;
    }

    /** 真正打开设置页；记下当前那一页，关掉设置页时还回原来那一页。 */
    private static void openSettingsNow() {
        Minecraft client = Minecraft.getInstance();
        Screen parent = client.gui == null ? null : client.gui.screen();
        client.setScreenAndShow(new VoiceSettingsScreen(parent));
    }

    /** 一行中文摘要，给 {@code /warehousevoice status} 用。 */
    private static String statusLine() {
        String secondary = SherpaEngine.secondaryId();
        return "语音：主模型 " + VoiceSettingsScreen.shortName(SherpaEngine.primaryId())
                + "，副模型 " + ("off".equals(secondary) ? "关" : VoiceSettingsScreen.shortName(secondary))
                + "，对照 " + (SherpaEngine.compareOn() ? "开" : "关")
                + "，并行 " + (SherpaEngine.parallelOn() ? "开" : "关")
                + "，预加载 " + (SherpaEngine.preloadOn() ? "开" : "关")
                + "，镜像 " + SherpaEngine.mirror()
                + "；/warehousevoice 打开设置页";
    }
}
