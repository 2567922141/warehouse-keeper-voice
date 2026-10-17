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
  **下载源可选**：默认从官方 `huggingface.co` 下载，也可在设置页把「下载源」切到**魔搭
  ModelScope**（大陆直连，四个模型都实测收录过，与官方逐字节同一份）、`hf-mirror.com`
  （只是转发到官方，不是独立源）或「自动」（按顺序试）。任一源失败会自动换下一个，支持断点续传，
  下载后校验确切字节数与 sha256。
- **多模型接续**：可同时运行主、副两个模型，两条识别结果均参与匹配；主模型未能识别物品名时，
  另一模型的结果自动接续。
- **可选 AI 归一化（默认关）**：本地没听出物品名时，可把「识别出的文字 + 本地前 8 名候选
  （id、中文名、库存）」交给一个小模型改写一次，再由**本地匹配器复判** —— 只有本地也认了才下单。
  开关不开、Key 不填，一个字节都不出网；限 20 次/分、200 次/天，同一句第二次不再联网。音频、
  玩家名、坐标、仓库坐标、整份仓库清单**从不**发送。四个预设：智谱 BigModel（默认）、硅基流动、
  **本地 Ollama**（完全离线、不用 Key）、自定义。详见 [隐私](#隐私) 与
  [release-notes/RELEASE_NOTES_0.4.0.md](release-notes/RELEASE_NOTES_0.4.0.md)。
- **面向近讲/远讲的录音归一化**：录音先做高通滤波（滤掉桌面/空调低频轰鸣），再按「人声段 RMS」
  归一化（而不是按整段最大电平），并单独判断信噪比；贴着麦克风说与隔着两米说，喂给模型的电平
  基本一致 —— 这是早期版本「只认某一个人的声音」的主要原因。
- **面向游戏物品名的匹配**：在仓库实际持有的物品范围内进行闭集匹配，综合字形相似度与拼音
  （含同音与近似音）判定，支持口语化说法与数量单位：`三个钻石`、`一组圆石`、`半组圆石`、
  `三组半`、`全都给我`、`剩下的都要`、`来点钻石`、`一半`（回显会说明按哪个说法算的）。
- **说得中具体那一种**：同名不同料的物品（迟缓药水 / 喷溅型迟缓药水 / 各种附魔书）在快照里每种各占
  一行，语音候选按**变体键**建 —— 说「迟缓药水」就取那一种，不会拿另一种药水顶包（需要主体
  `warehouse-keeper` **1.2.1** 或更高）。
- **一句话能要好几件东西**：说「给我一本背刺附魔书和一张绿色床」会分别下成两条取货单。先由本地按
- **三种用法自己挑（0.4.0 新增）**：面板 AI 页的「方式」按钮可切「完全使用本地规则 / 两个一起（本地优先）/ 完全调用 AI 改写」；写进 `settings.txt` 的 `rewrite.mode=both|local|ai`，重进游戏保持。
- **报错说清原因、带属性物品能选对（0.4.0 新增）**：仓库里确实没有这件东西时提示「没找到「…」——仓库里可能没有这件东西」，不再笼统说「没听出物品名」；药水时长/强化等级、附魔等级、烟花飞行时长这类「同一物品多个属性行」，开 AI 后按候选编号（`item=#N`）选到你说的那一行，基础 id 对应多行时不瞎猜。
  「和 / 还有 / 以及 / 、 / ，」等连接词拆句、每件单独认（数量词、别名、变体照旧），全对得上就直接
  逐件下单、**不问 AI**；本地有认不出的那件才问一次 AI（回包改成每件一行、最多 4 件，每行仍要过本地
  复判，AI 永远不直接下单；补齐时本地已认出的件不会丢，两边按 id 合并后一起下单）。超过 4 件会提示分开说，一件都没听出时提示用「和／还有」把每件说清楚。
- **名字相近不会给错货**：仓库里只有「橡木木板」时你说「橡木原木」，以前会被当成同一件下单；现在只
  回「仓库里没有「橡木原木」——最接近的是：橡木木板」并**不下单**。开了 AI 时这种「相近但不像同一件」
  的句子会交给模型复判一次，模型点名的物品仍须在你的原话里找得到同源说法。
- **面板里直接管**（配主体 1.2.1）：设置 →「语音取货」页按功能**分区**（识别模型 / 识别方式 / 模型下载 / AI 功能），主模型、副模型、下载源、AI 服务都是**下拉菜单**；AI 页可直接填 / 改 / 清空 API Key，并有「**测试连通性**」按钮给出结果与延迟（不占额度）；句子说得**细**（带等级/型号，例「要一本深海探索者3附魔书」）时同样会问一次 AI，候选按相似度排序后取前 8。
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
| `/warehousevoice` | 打开语音设置页（选模型、下载模型、换下载源、开关对照/并行/预加载、**AI 归一化**） |
| `/warehousevoice status` | 在聊天栏打印当前语音配置（`/wvoice` 同义） |

说数量可以很随便：`三个钻石`、`一组圆石`、`半组圆石`（= 半堆）、`三组半`、`全都给我`、
`剩下的都要`、`来点钻石`、`一半`；回显会写明按哪个说法算的（例：「（按「半组」算，一组 64 个）」）。
装了主体模组 1.2.1 或更高时，仓库面板「设置 → 语音取货」页也能看到同一份设置：模型下载 / 删除、
下载进度、AI 归一化开关与用量；**AI 归一化页里还能直接填 / 改 / 清空 API Key**（真输入框，回车保存，只显示前 4 位）。

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
| `mirrorMode` | `official` / `hfm` / `modelscope` / `auto` / `custom` | 下载源（默认 `official` 官方原始源）。设置页里点一下就在这五个之间轮换，认不出的值记一条日志后按默认官方处理 |
| `mirror` | 网址前缀 | 只在 `mirrorMode=custom` 时用作自定义前缀；也为老版本保留一个它能用的地址。老配置只写这一个键时按地址反推模式（`https://hf-mirror.com` → hfm、`https://huggingface.co` → official、其它网址 → custom） |

老写法仍然认：`primary=paraformer` 等价于 `paraformer-small`，`zipformer` 等价于 `zipformer-ctc`。

AI 归一化（默认关，第 ② 步）：

| 配置项 | 取值 | 说明 |
| --- | --- | --- |
| `rewrite.mode` | `both` / `local` / `ai` | 三种用法：`both` 两个一起（本地优先，默认）/ `local` 完全本地（不联网）/ `ai` 完全调用 AI 改写（每句都先问一次，AI 契约优先、本地复判兜底）；中文别名「两个一起 / 本地 / 只用AI」也认 |
| `rewrite` | `on` / `off` | 是否启用 **AI 归一化**（默认 `off`）。关着时一个字节都不出网，行为与 0.3.0 一样 |
| `rewrite.preset` | `zhipu` / `siliconflow` / `ollama` / `custom` | 服务商预设（默认 `zhipu` 智谱 BigModel）；`ollama` 是本地服务，不用 Key |
| `rewrite.url` | 网址前缀 | 接口地址；留空时按预设。预设之外的自定义组合走手改本文件 |
| `rewrite.model` | 模型名 | 留空时按预设（智谱 `glm-4.7-flash`、硅基流动 `Qwen/Qwen3-8B`、本地 `qwen3:0.6b`） |
| `rewrite.key` | API Key | **明文**保存在本文件里；界面与设置页只显示前 4 位 |
| `rewrite.timeout` | 500–3000 | 单次调用超时毫秒（默认 `1000`） |

AI 归一化的触发条件是「本地没把握」：**没听出物品名，或名字只是相近但不像同一件东西**，并且开关是开着的，才会发一次请求（一次识别最多
一次、不重试）；结果**必须**通过本地匹配器的复判才会下单，所以模型出错最多是「没帮上忙」。
限额 20 次/分、200 次/天，同一句第二次不再联网（缓存 + 别名沉淀）。

下载源的五个选项与各自的坑（`hf-mirror.com` 会 308 跳回官方，不是独立备份源；魔搭为大陆直连，
没有收录的模型自动回退官方）见 [docs/使用说明.md](docs/使用说明.md) 与
[release-notes/RELEASE_NOTES_0.3.1.md](release-notes/RELEASE_NOTES_0.3.1.md)。

别名表：`config/warehouse-keeper-voice/aliases.txt`，每行格式为 `听到的说法=物品id`，
例如 `鞍山人=minecraft:andesite`。手工修改与自动记录的内容同存于此文件。
自动记录的**键只含物品名、不含数量**（「十个金币」记成 `金币`），所以换个数量的说法同样命中；
只有一两个字的退化说法（例如恰好归一化成「给」「给我」）不再写入别名表 —— 旧版本留下的这类
条目会在加载时自动剔除，原文备份为同目录下的 `aliases.txt.bak`。

## 隐私

- 语音识别完全在本机完成；识别过程中不进行任何网络通信。
- 联网只发生在两种情况：① **玩家在设置页里主动点「下载模型」**（从所选下载源下载模型文件，
  默认官方 `huggingface.co`，也可选魔搭 ModelScope / hf-mirror）；② **玩家自己打开「AI 归一化」
  开关之后**，本地匹配没把握（没听出物品名或名字只是相近）时会向该开关指定的服务商发一次请求。两种情况之外，游戏进程
  不产生任何本附加包发起的请求；**开关默认关闭**，不开就一个字节都不出网。
- 开启 AI 归一化后**发送的内容**：语音识别出的文字 + 本地打分前 8 名候选（物品 id、中文名、
  库存数）。**不发送**：音频、玩家名 / UUID、坐标、仓库坐标、整份仓库清单、聊天记录。
- API Key **以明文**保存在本机 `config/warehouse-keeper-voice/settings.txt`（和 `primary`、
  `mirror` 同一份文件，方便手改）；该文件不在存档里、不进 git。**请不要把这个文件发给别人。**
  也可改用「本地 Ollama」预设：完全不联网、不需要 Key。
- 第三方服务的条款各不相同（有的免费层明确会把内容用于改进产品），使用前请自行确认。
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

上表四个模型也可以从魔搭 ModelScope 的镜像仓库下载（`mirrorMode=modelscope` / `auto`）：
`poloniumrock/SenseVoiceSmallOnnx`、`pengzhendong/sherpa-onnx-paraformer-zh-small`、
`pengzhendong/sherpa-onnx-paraformer-zh`、`csukuangfj/sherpa-onnx-zipformer-ctc-zh-int8-2025-07-03`。
2026-10-16 复核过文件列表里的大小与 sha256，与官方仓库写死的值完全一致（逐字节同一份）；
下载后仍会按大小与 sha256 校验，所以镜像站即使换文件也不会被静默接受。

## 许可证

MIT，详见 `LICENSE`。
