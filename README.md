# Warehouse Keeper Voice

## 声明

这是一个 100% Vibe Coding 项目，所有代码及代码审查均有 AI 负责，人工负责在真机环境下，在真实的游戏存档中测试。

AI 有可能犯错，仅供 vibe coding 学习用途，请勿用于工业生产以及其他重要行业中。若出现任何损失，后果自负！！！

This is a 100% Vibe Coding project. All code and code reviews are handled by AI, while humans are responsible for testing on real devices with real game saves.

AI can make mistakes. It is intended for vibe coding and learning purposes only — do not use it in industrial production or any other critical field. Any losses are your own responsibility!!!

## 简介

Warehouse Keeper 的可选语音取货附加包。玩家按住指定按键说出取货需求（例如「红色染色玻璃 十二个」），
本附加包在本机离线完成语音识别，将其解析为仓库取货指令并交由搬运工送货。

本附加包为纯客户端模组，服务器无需安装；未安装本附加包时，主体模组的行为不受任何影响。

## 功能

- **完全离线识别**：语音识别在本机 CPU 上完成，不联网，不调用任何云服务。
- **模型全部按需下载**：**语音包不内置任何模型**（包体约 8 MB，只有引擎），四个模型都在设置页里
  自己选、自己下：SenseVoice（默认，约 228 MB，对多口音、远场、噪声更稳）、Paraformer 小
  （约 78 MB，最省 CPU 与内存）、Zipformer-CTC（约 350 MB）、Paraformer 完整版（约 217 MB）。
  模型从 `huggingface.co` 下载（设置页另一个入口 `hf-mirror.com` 只是转发到同一个站，
  不是独立源），支持断点续传，下载后校验大小与 sha256。
- **多模型接续**：可同时运行主、副两个模型，两条识别结果均参与匹配；主模型未能识别物品名时，
  另一模型的结果自动接续。
- **面向近讲/远讲的录音归一化**：录音先做高通滤波（滤掉桌面/空调低频轰鸣），再按「人声段 RMS」
  归一化（而不是按整段最大电平），并单独判断信噪比；贴着麦克风说与隔着两米说，喂给模型的电平
  基本一致 —— 这是早期版本「只认某一个人的声音」的主要原因。
- **面向游戏物品名的匹配**：在仓库实际持有的物品范围内进行闭集匹配，综合字形相似度与拼音
  （含同音与近似音）判定，支持口语化说法与数量单位（个 / 组）。
- **自动纠错记忆**：经模糊匹配或另一模型纠正成功的说法会写入本机别名表，后续可直接命中；
  别名表亦支持手工编辑。
- **严格的库存语义**：仓库中不存在该物品、数量不足或超出单次下单上限时一律不下单，并给出说明。
- **音频不落盘**：录音仅以 PCM 形式驻留内存，识别完成后立即清零，不写入任何音频文件。

## 环境要求

| 组件 | 版本要求 |
| --- | --- |
| Minecraft | 26.3 |
| Fabric Loader | 0.19.3 或更高（实测 0.19.5） |
| Fabric API | 0.161.0+26.3（或兼容版本） |
| Java | 25 或更高 |
| [warehouse-keeper（主体包）](https://github.com/2567922141/warehouse-keeper) | 1.1.4 或更高 |
| 操作系统 | Windows x64（当前仅随包提供 Windows 原生库） |

> 注：该mod暂不适配 Minecraft 26.2

## 安装

1. 安装主体模组 `warehouse-keeper`（服务端与客户端均需安装）。
2. 将 `warehouse-keeper-voice-<版本>.jar` 放入客户端 `mods/` 目录。
3. 服务端无需安装本附加包。

语音下单沿用主体模组的取货权限，需由管理员在仓库面板「权限」页授予该玩家取货权限。

## 使用

| 按键 | 功能 |
| --- | --- |
| `V`（按住） | 按住说话，松开后立即识别 |
| `/warehousevoice` | 打开语音设置页（选模型、下载模型、换镜像、开关对照/并行/预加载） |
| `/warehousevoice status` | 在聊天栏打印当前语音配置（`/wvoice` 同义） |

聊天栏是否多显示另一个模型那一行，由 `config/warehouse-keeper-voice/settings.txt` 的 `compare=on|off`
控制（不占用快捷键 —— 主体模组默认用 `B` 打开仓库面板，本附加包不抢这个键）。

首次使用时会把随包附带的原生库解压到 `config/warehouse-keeper-voice/`（约 8 MB），该过程仅执行一次。
**语音模型不随包分发**：输入 `/warehousevoice` 打开设置页，在想要的模型那一行点「下载」，
模型落在 `config/warehouse-keeper-voice/models/<模型id>/`；下载完成后自动可用，一个都没下载时
按住 `V` 会提示先去设置页下载一个。

## 配置

配置文件：`config/warehouse-keeper-voice/settings.txt`

| 配置项 | 取值 | 说明 |
| --- | --- | --- |
| `primary` | `sense-voice` / `paraformer-small` / `zipformer-ctc` / `paraformer-zh` | 优先采用哪个模型的识别结果（默认 `sense-voice`；没下载时自动回退到已装的模型并在聊天栏说明） |
| `secondary` | 同上，或 `off` | 主模型没听懂时顶上的副模型；`off` 表示只跑主模型 |
| `compare` | `on` / `off` | 聊天栏是否显示另一模型的结果（不影响两个模型同时运行） |
| `parallel` | `on` / `off` | 两个模型是否并行识别 |
| `preload` | `on` / `off` | 启动后是否在后台预先装载模型 |
| `mirror` | 网址 | 下载模型用的镜像根地址，默认 `https://hf-mirror.com` |

老写法仍然认：`primary=paraformer` 等价于 `paraformer-small`，`zipformer` 等价于 `zipformer-ctc`。

别名表：`config/warehouse-keeper-voice/aliases.txt`，每行格式为 `听到的说法=物品id`，
例如 `鞍山人=minecraft:andesite`。手工修改与自动记录的内容同存于此文件。
自动记录的**键只含物品名、不含数量**（「十个金币」记成 `金币`），所以换个数量的说法同样命中；
只有一两个字的退化说法（例如恰好归一化成「给」「给我」）不再写入别名表 —— 旧版本留下的这类
条目会在加载时自动剔除，原文备份为同目录下的 `aliases.txt.bak`。

## 隐私

- 语音识别完全在本机完成；识别过程中不进行任何网络通信。
- 联网只发生在**玩家在设置页里主动点「下载模型」**时（从官方源或所选镜像下载模型文件）；
  不下载时，游戏进程不产生任何本附加包发起的请求。
- 录音数据仅以内存缓冲区形式存在，识别结束后立即清零，不写入磁盘。
- 识别文本仅显示于本机动作栏与聊天栏，不发送至服务器或其他玩家。
- 运行时写入磁盘的内容仅限 `config/warehouse-keeper-voice/` 下的模型、设置与别名文件；
  绝不写入任何世界存档目录。

## 构建

本仓库为主体模组 `warehouse-keeper` 的配套子工程，需置于该工程源码树的 `voice/` 目录下构建：

```bash
git clone https://github.com/2567922141/warehouse-keeper.git
cd warehouse-keeper
git checkout 26.3
# 将本仓库内容放入 voice/ 目录
powershell -ExecutionPolicy Bypass -File voice/fetch-libs.ps1   # 获取本地构建输入（约 8 MB）
./gradlew :voice:build
```

产物为 `voice/build/libs/warehouse-keeper-voice-<版本>.jar`，体积约 8 MB：只含 sherpa-onnx 的 Java
绑定与 Windows x64 原生库，**不含任何语音模型** —— 模型由玩家在语音设置页里按需下载。
`voice/libs/` 为本地构建输入，不纳入版本控制。

## 第三方组件

随发行包分发的组件，以及设置页里可下载的模型：

| 组件 | 来源 | 许可 |
| --- | --- | --- |
| sherpa-onnx（Java 绑定与原生库） | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | Apache-2.0 |
| ONNX Runtime | [microsoft/onnxruntime](https://github.com/microsoft/onnxruntime) | MIT |
| Paraformer 中文小模型（设置页按需下载） | k2-fsa/sherpa-onnx asr-models | 见上游 |
| SenseVoice（设置页按需下载） | [csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17](https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17) | 见上游 |
| Zipformer-CTC 中文 int8 模型（按需下载） | [csukuangfj/sherpa-onnx-zipformer-ctc-zh-int8-2025-07-03](https://huggingface.co/csukuangfj/sherpa-onnx-zipformer-ctc-zh-int8-2025-07-03) | 见上游 |
| Paraformer 中文完整版（按需下载） | [csukuangfj/sherpa-onnx-paraformer-zh-2024-03-09](https://huggingface.co/csukuangfj/sherpa-onnx-paraformer-zh-2024-03-09) | 见上游 |

## 许可证

MIT，详见 `LICENSE`。
