package com.ds.warehouse.voice;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Warehouse Keeper 的可选语音附加包（纯客户端）。
 *
 * <p>设计前提：不装它，主体模组一点变化都没有；装了它，也只给「自己这台客户端」
 * 加一个语音输入方式 —— 识别在本机离线做，最终仍然是发一条普通的
 * {@code /warehouse order …} 指令，和服务端权限、搬运工流程完全一致。
 *
 * <p>只注册一个按键（默认 V，按住说话）。「聊天栏里是否多显示另一个模型那一行」由
 * {@code config/warehouse-keeper-voice/settings.txt} 的 {@code compare=} 控制，不占用任何快捷键 ——
 * 主体模组默认用 B 打开仓库面板，附加包不再抢这个键。
 */
public class VoiceMod implements ClientModInitializer {

    private static final Logger LOG = LoggerFactory.getLogger("warehouse-keeper-voice");

    private static KeyMapping talkKey;

    @Override
    public void onInitializeClient() {
        talkKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.warehouse-keeper-voice.talk",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_V,
                KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            VoiceSession.idleTick();
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
}
