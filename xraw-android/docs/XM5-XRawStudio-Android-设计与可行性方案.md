# 富士 X-M5 · Android 版「X RAW Studio」复刻方案
### 可行性评估 / 协议全文 / 架构设计 / 代码框架 / 开发路线 / 开源复用指引

> 目标：Android 手机通过 USB-OTG 连 X-M5，把 RAF 推回机身 X-Processor 5 渲染，机内参数全量可控，UI 1:1 对齐 X RAW Studio。
> 文档版本：v1.0 ｜ 适用机身：Fujifilm X-M5（X-Processor 5）｜ 平台：Android 8.0+ (minSdk 26 建议)

---

## 0. 结论速览（先看这一页）

| 问题 | 结论 |
|---|---|
| 技术上能不能做？ | **能，且已有多个可运行的开源先例**（libfuji/rawji/fudge 都能对真机跑通 RAF→JPEG 机内转换）。这不是"从零逆向"，而是"移植 + 工程化"。 |
| 所谓"富士私有 PTP 扩展协议"存不存在？ | **不存在神秘 opcode**。逆向结论（ILFforever 对 XGFXAPI.dll/FTLPTP.dll 的反汇编 + 社区抓包）是：X RAW Studio 全程只用**标准 PTP**，富士的"私有"只是**私有 DevicePropValue 编号**（`0xD0xx/0xD1xx`）+ 三个文件上传 opcode（`0x900C/0x900D/0x901D`）。 |
| 最关键的三个属性 | `0xD185` = RAW 转换配置档（≈628 字节的 d185 结构体，参数全在里面）／`0xD183` = 触发渲染（**1=全分辨率, 0=小预览**）／`0x900C+0x900D` = 上传 RAF 到机身 SDRAM |
| 最容易被忽略的硬约束 | ① 机身只接受**本机身/同型号**拍的 RAF（跨机身/跨型号返回 `0x2002`）② 一次上传占用机身 SDRAM，容量有限，必须处理"传不上去"③ Android 侧 PTP 会话是**单线程有状态**的，必须串行化 ④ OTG 供电方向问题（X-M5 会从手机取电，见 §5.6） |
| 最大风险 | 不是协议，而是**参数值编码的机型差异**（尤其新胶片模拟/新参数的枚举值）与**UI 1:1 复刻的信息量**（X RAW Studio 是桌面三栏，手机要重新做布局）。 |
| 建议落地路线 | Phase 0 用 Chrome + filmkit(WebUSB) 先在手机上验证协议可行性（半天）→ Phase 1 纯 Kotlin 打通 RAW 转换全链路 → Phase 2 预览与 UI → Phase 3 批量与配方 → Phase 4 机型能力库与加固 |
| 工期估算（单人） | 可演示 Demo 2~3 周；可自用版本 6~8 周；接近"1:1 可用"3~4 个月 |

**一句话架构**：`Compose UI (参数即状态)` → `RecipeState` → `单线程相机作业队列(CameraJobQueue)` → `FujiRawConvert` → `PtpUsbTransport(UsbManager/bulkTransfer)` → X-M5。
**应用端不解码任何 RAW、不做任何色彩计算**——它只负责"传文件 + 改 628 字节结构体 + 收 JPEG"。

---

## 1. 技术可行性评估

### 1.1 目标机型前提确认（X-M5）

* 富士官网 X-M5 兼容性表明确列出 **FUJIFILM X RAW STUDIO ✔**，说明机身固件具备 `USB RAW CONV./BACKUP RESTORE` 连接模式（X-M5 手册 D 连接设置 → CONNECTION MODE 中的选项原文即 `USB RAW CONV./BACKUP RESTORE`）。
* 该模式下相机暴露 **PTP/Still-Image 接口（USB class 6）**，而非 MTP/大容量存储（class 8）。判据：`UsbInterface.interfaceClass == 6`。
* USB 身份：Fuji `idVendor = 0x04CB`；RAW CONV / Tether 模式下一个常见 `idProduct = 0x02D7`（libfuji `docs/usb.md` 记录）。**不要硬编码 PID**——按 VID + interface class 匹配，再读 `0xD16E` 判定模式。
* X-M5 机身 USB-C 为 USB 3.2 Gen2x1；但对 PTP 会话，实测吞吐受**相机端处理能力**限制，不是链路限制。

### 1.2 协议真相：标准 PTP + 富士私有属性

反汇编结论（来源：ILFforever/fujifilm-ptp-recipes `docs/reverse-engineering/xraw-studio-call-chain.md`）：

```
FUJIFILM_X_RAW_STUDIO.exe
   └─ XRFC.dll            RAW 转换引擎（~14.5k 函数，OpenCV 基底）
        └─ XGFXAPI.dll   富士相机 SDK "XSDK"（126 exports）
             └─ FTLPTP.dll   PTP 传输层（把字节写到 USB 线上）
```
> "The transport provides FTL_PTP_VendorExtensionOperation, but the SDK routes all property reads and writes through 0x1015 / 0x1016. **There is no secret Fuji opcode.**"

也就是说：**富士的"私有协议"= 私有属性编号表 + 私有结构体（d185）**。这一点决定了本项目的工作性质：
* ✅ 传输层 100% 有标准可依（ISO 15740 + USB Still Image Class），libgphoto2/libusb 已实现 20 年。
* ⚠️ 真正的脏活是**结构体语义**：`0xD185` 里 29 个 u32 字段的取值范围、相互抑制关系、以及每台机型的枚举值表（X RAW Studio 自己也是靠 `XRFC.DAT` 这个"每机型特性表"来驱动的）。

### 1.3 开源逆向现状（这是本项目最值钱的资产）

| 项目 | 语言/形态 | 覆盖范围 | 对本项目的价值 | 成熟度 |
|---|---|---|---|---|
| **gphoto/libgphoto2** (`camlibs/ptp2`) | C | 通用 PTP 栈 + Fuji 属性表（`PTP_DPC_FUJI_*`）、`ptp.h` 里 Fuji opcode 注释 | **属性编号字典的母本**；USB 容器读写实现可直接抄逻辑 | 工业级 |
| **petabyt/libpict** | C | 完整 ISO 15740 PTP 客户端，libusb 后端，含 Android/iOS 验证的 PTP/IP | 最干净的 PTP 传输层参考实现（"单 buffer 零分配"设计） | Apache-2.0，活跃 |
| **petabyt/libfuji**（含 `fp/` 子库） | C | **富士全套**：PTP/IP + USB、`fuji_process_raf()` 完整 RAW 转换链路、`d185` 编解码、FP1/FP2/FP3(XML) 配方解析 | ⭐ **本项目 90% 协议逻辑的现成答案**，建议整体移植 | 活跃，MIT |
| **petabyt/fudge**（Codeberg） | Java + 原生 C + Lua | 真·Android App（USB-OTG 连接富士相机；C 层用 read/write/ioctl 直操 USB） | **Android 侧 USB-OTG 工程范式的直接范本** | 已上架 Play/F-Droid |
| **pinpox/rawji** | Python + PyUSB | 完整 RAW 转换 CLI，参数齐全，注释详实（对 d185 字段语义有大量实测注释） | **最好的"可读协议文档"**，代码 600 行，建议先通读；其字段抑制关系注释是黄金 | 2025 新建，已在 X-T30/X100F/X-T3/X-E5 实测 |
| **eggricesoy/filmkit** | TypeScript + WebUSB | 浏览器版：连相机、读写 C1–C7 配方、RAW 转换 + 实时预览、预设库 | ① **Phase 0 验证工具**（Android Chrome 支持 WebUSB，半天即可验证你的手机+线+X-M5 通不通）② UI/交互设计的绝佳参考 | 活跃，支持 X100VI/X-T5/X-H2 |
| **ILFforever/fujifilm-ptp-recipes** | 文档 | C1–C7 配方槽协议、属性全表、EXIF MakerNote 映射、**X RAW STUDIO 二进制逆向笔记** | **本项目最权威的书面资料**，尤其 `docs/reverse-engineering/` | 文档型，持续更新 |

**逆向空白点（需要你自己补的）**：
1. `d185` 的部分字段语义未完全定死（字段 23/27/28 为 unknown；HDR 与 DR 共享同一字段）。
2. **新型胶片模拟的枚举值未收录**：libfuji 的表只到 `Eterna Bleach Bypass = 18 (guess)`，`Nostalgic Neg` 在 libfuji 里被粗暴映射回 Classic Chrome（即**值未知**）；REALA ACE、以及 X-M5 上可能存在的更新的模拟，都需要实测枚举（方法见 §7.2）。
3. 机型能力矩阵：X RAW Studio 靠 `XRFC.DAT` 决定"这台机器给哪些参数/哪些模拟"，我们必须自建（方法见 §7.3）。
4. **断点续传**：`0x900D` 未观察到支持 offset 参数（PTP 标准 `SendPartialObject` 富士分支亦未见），所以大文件中途断开大概率只能**整文件重传**（见 §5.4）。

### 1.4 已确认的协议事实清单（可直接照抄实现）

#### 1.4.1 USB / PTP 基础参数

| 项 | 值 |
|---|---|
| VID | `0x04CB`（FUJIFILM） |
| 接口类 | `6` (Still Image / PTP)，需 bulk OUT + bulk IN，可选 interrupt IN（事件） |
| 容器 | 小端；`length(4) + type(2) + code(2) + transId(4) + params[]/data` |
| 容器类型 | 1=Command, 2=Data, 3=Response, 4=Event |
| 常用 chunk | 命令阶段 512B~64KB；数据阶段实测可用 512KB（rawji）甚至更大（libfuji 在发送 RAF 前把 `max_packet_size` 提到 `261632`，注释 "Windows 11 does this"） |
| 超时 | 命令 5s；数据阶段要放大到 20~60s |

#### 1.4.2 用到的 opcode

| opcode | 名称 | 用途 |
|---|---|---|
| `0x1001` | GetDeviceInfo | 机型/序列号/支持的 opcode 与 DPC 列表 |
| `0x1002` | OpenSession | 会话开始（`0x201E`=已开会话，视为成功） |
| `0x1003` | CloseSession | 会话结束 |
| `0x1004/0x1005` | GetStorageIDs / GetStorageInfo | 列存储（RAW CONV 模式下有虚拟存储） |
| `0x1007` | GetObjectHandles | **轮询"渲染好了没"** |
| `0x1008/0x1009` | GetObjectInfo / GetObject | 取回 JPEG/TIFF |
| `0x100A` | GetThumb | **预览加速神器**：640×480 JPEG ≈ 40KB，比全图快 3~4 倍 |
| `0x100B` | DeleteObject | 清掉相机端临时对象（**必须做**，否则 SDRAM 越攒越满） |
| `0x1014/0x1015/0x1016` | GetDevicePropDesc / Get / Set DevicePropValue | **参数控制的主通道** |
| `0x900C` | `PTP_OC_FUJI_SendObjectInfo` | 上传前的对象声明（4 参/3 参，富士变体） |
| `0x900D` | `PTP_OC_FUJI_SendObject2` | 上传 RAF 数据本体 |
| `0x901D` | `PTP_OC_FUJI_SendObject` | 另一条写文件通道（旧 FPUPDATE 工具用，可作 fallback） |

#### 1.4.3 关键属性（DevicePropValue）

| 属性 | 名称 | 类型 | 说明 |
|---|---|---|---|
| `0xD16E` | USBMode | u16 | **5=tether, 6=RAW CONV., 8=webcam**；连上先读它校验模式 |
| `0xD001` | FilmSimulation | u16 | 实时/当前拍摄态；配方槽路径用 `0xD192` |
| `0xD185` | **RawConvProfile** | bytes(≈628) | ⭐ **d185 配置档**，读=该 RAF 的原始机内设置，写=应用新参数 |
| `0xD183` | **StartRawConversion** | u16 | 写 `1`=全分辨率渲染；写 `0`=小预览渲染 |
| `0xD184` | IOPCode | str | 机型能力/校验码（写 d185 时要原样保留） |
| `0xD186/0xD187` | TetherRAW Condition/Compatibility Code | str | 兼容性校验，可用来做机型能力判定 |
| `0xD18C` | 配方槽选择器 | u16 | 1–7 = C1–C7（**仅 X-Trans V 验证过**，X-M5 需实测） |
| `0xD18D` | 配方名字 | PTP String | UTF-16LE 前缀长度 |
| `0xD18E–0xD1A4` | 配方属性块 | 混合 u16/i16 | 见 ILFforever 属性表 |
| `0xD20E` | FreeSDRAMImages | u16 | 机身剩余可缓存张数（上传前可用作容量预检） |
| `0xD242` / `0xD36A/0xD36B` | 电量 | — | 批量处理前必须检查；也可当**心跳包** |

#### 1.4.4 完整 RAW 转换时序（核心 30 行）

以下为 libfuji `fuji_process_raf()` + rawji `fuji_usb.py` 交叉验证后的时序，是本项目的**主流程图**：

```
1  OpenSession(1)
2  GetDeviceInfo                       → 校验 manufacturer/model
3  GetDevicePropValue(0xD16E)          → 必须 == 6，否则提示用户改 CONNECTION MODE
4  [可选] GetDevicePropValue(0xD20E)   → 剩余 SDRAM 张数预检
5  [可选] GetObjectHandles → DeleteObject(全部)   清理上次残留
──────────────────── 上传（每张 RAF 只做一次） ────────────────────
6  SetDevicePropValue? 
   SendObjectInfo(0x900C, params=[0,0,0],
       data = ObjectInfo{ objFormat=0xF802, size=fileSize, filename="FUP_FILE.dat" })
7  SendObject2(0x900D, data = RAF 全文件字节流)   ← 大文件，分 chunk 写
──────────────────── 预览/渲染（可反复，不必重传） ────────────────
8  GetDevicePropValue(0xD185)  → 得到 ≈628B 的 d185（= 这张 RAF 的原始机内设置）
9  在本地 patch 29 个 u32 字段（胶片模拟/WB/DR/高光阴影/色彩/锐度/清晰度/NR/颗粒/LMO…）
10 SetDevicePropValue(0xD185, data = patch 后的 d185)
11 SetDevicePropValue(0xD183, data = u16(1 全分辨率 | 0 预览))
12 轮询 GetObjectHandles(storage=0xFFFFFFFF, fmt=0, parent=0) 每 50–100ms
      直到返回 count ≥ 1
13 GetThumb(handle)   → 预览阶段用这个（~40KB, 快 3~4×）
   GetObject(handle)  → 出片阶段用这个（全质量 JPEG / TIFF）
14 DeleteObject(handle)            ← 必须，否则下次轮询会拿到旧图
15 回到 9（改参数再渲染）；换图则回到 5
──────────────────── 收尾 ────────────────────
16 CloseSession（先清本地状态再发，吞掉异常）
```

> ⭐ **架构级优化点（X RAW Studio 的痛点，我们能做得更好）**：第 6~7 步的 RAF 上传是**最贵的一步**（30~60MB）。因此"一张图 = 一次上传 + N 次改档 + N 次渲染"。拖动滑杆时**绝不能重传 RAF**。这也是 filmkit README 里自称"迭代比 X RAW STUDIO 更快"的原因。

#### 1.4.5 d185 结构体（本地 patch 的关键）

```
offset 0x000 : u16  propCount = 0x1D (29)
offset 0x002 : u8   strLen
offset 0x003 : UTF-16LE  IOPCode 字符串（如 "000C1234"），以 0x0000 结尾
offset 0x201 : 29 × u32 属性值（大端？否，小端 u32）
总长 ≈ 0x201 + 29*4 = 0x275 (629) 字节；libfuji 要求 buffer ≥ 0x274
```

字段索引（libfuji `fp/src/d185.c` `enum D185Props`，**已实测**）：

| idx | 字段 | 备注/取值 |
|---|---|---|
| 0 | ShootingCondition | 拍摄条件位域 |
| 1 | FileType | `JPG=0x?`、`TIF_8=…`、`TIF_16=…`（libfuji fp_file_type） |
| 2 | ImageSize | `L3x2=0x7`、M/S 各比例，见 `fp_image_size` |
| 3 | ImageQuality | `FINE=0x2`、NORMAL |
| 4 | ExposureBias | 1/3 档编码表 `fp_exposure_bias`（`ZERO_EV=0x0`） |
| 5 | DynamicRange | `100/200/400`（也承载 HDR：200/400/800/800PLUS）——**只能选 ≤ RAF 拍摄时的 DR** |
| 6 | WideDRange (DR Priority) | `OFF=0, WEAK=1, STRONG=2, HDR800PLUS=3`；非 OFF 时**抑制** idx5，且**拒绝** idx15/16（高光/阴影被清 0） |
| 7 | **FilmSimulation** | `Provia=1, Velvia=2, Astia=3, ClassicChrome=11, ProNegStd=5, ProNegHi=4?, Monochrome=6, Ye=7, R=8, G=9, Sepia=10, Acros*=12~15, Eterna=16, ClassicNeg=17, EternaBleachBypass=18(guess)`；**新模拟需实测** |
| 8 | GrainEffect + Size | 复合字段：`OFF=1, WEAK=2, STRONG=3` + `LARGE 加 0x2` |
| 9 | ChromeEffect (Color Chrome) | OFF/WEAK/STRONG |
| 10 | WB Shoot Condition | "as shot" 用；优先级 WBShootCond > WhiteBalance > ColorTemp |
| 11 | WhiteBalanceMode | 同 `0x5005` 枚举；`0x8007` = 色温模式 |
| 12/13 | WB Shift R / B | `fp_range_wb_shift`（±9 步进） |
| 14 | ColorTemperature | **仅当 WB=0x8007 时写**，否则会被忽略/报错 |
| 15 | HighlightTone | ×10 编码（-2.0 → -20 等）；DR Priority 非 OFF 时会被清 0 |
| 16 | ShadowTone | 同上 |
| 17 | Color | `fp_range`（-4..+4） |
| 18 | Sharpness | `fp_range` |
| 19 | NoiseReduction | `fp_noise_reduction`（注意非线性编码） |
| 20 | LensModulationOptimizer | ON/OFF |
| 21 | ColorSpace | sRGB / AdobeRGB |
| 22 | BlackImageTone | 单色时为 **WC 暖冷偏移**（±180，0.1 步进）；彩色时为 WB 相关的"随机"校准值——**必须原样保留，不要手写** |
| 23 | unknown | 原样保留 |
| 24 | ColorChromeFXBlue | OFF/WEAK/STRONG |
| 25 | MonochromaticColor_RG | 单色时 MG 偏移（±180） |
| 26 | Clarity | `fp_clarity` |
| 27/28 | unknown | 原样保留 |

> **实现铁律**：本 App 绝不"从零构造" d185。流程恒定是 **读回相机给的 d185 → 只 patch 用户改动的索引 → 原样回写**。这样 IOPCode、unknown 字段、机型差异全部自动继承，是"一次实现、多机型可用"的关键。

### 1.5 硬约束与坑（必须在产品设计阶段吸收）

| # | 约束 | 后果 | 对策 |
|---|---|---|---|
| C1 | **只接受本机身拍的 RAF**；跨机身/跨型号上传→ `0x2002` | 用户从文件管理器随便拖一张网图/别的相机的 RAF 会失败 | 上传前读 RAF 内嵌型号+序列号，与 GetDeviceInfo 比对，**本地先拦截并给出人话提示** |
| C2 | RAF 上传占用机身 **SDRAM**，容量有限 | 大文件可能传不上去或后续渲染失败 | 上传前读 `0xD20E`；失败时提示"换更小/更少张"；每张处理后立刻 `DeleteObject` |
| C3 | 会话**单线程有状态**，transactionId 必须连续 | 并发调用 = 全乱 | 全 App 唯一 `PtpSession` + `Mutex` + 单线程 `CameraJobQueue`（见 §3.3） |
| C4 | **断线无恢复**：传输中拔线，PTP 会话作废，`0x900D` 无 offset | 只能整文件重传 | 见 §5.4 防断连设计（保活/看门狗/整文件重试/幂等清理） |
| C5 | Android `bulkTransfer` 单次上限因设备而异（一般 16KB 稳，64KB~512KB 多数可用） | 大 chunk 可能返回 -1/短包 | chunk 自适应降级：512K→64K→16K→4K，成功后锁定 |
| C6 | OTG 供电：X-M5 手册里 `USB POWER SUPPLY SETTING` 为 ON 时会**从主机取电** | 手机电量暴跌 / 供电不足导致掉线 | 引导用户设 `USB POWER SUPPLY SETTING = OFF`；批量处理建议带供电的 OTG HUB |
| C7 | 相机 `AUTO POWER OFF` 会掐会话 | 批量跑到一半相机睡了 | App 侧心跳 + 提示用户关闭自动关机（X-M5 手册明确要求 RAW CONV 时关 AUTO POWER OFF） |
| C8 | 渲染耗时 1~6s/张（机身 CPU 固定） | UI 卡死风险 | 预览走 `GetThumb` + 取消旧任务 + 结果缓存（§5.3） |
| C9 | 新胶片模拟枚举值未知 | 选了没效果/报错 | 能力探测 + 探测表（§7.2），UI 只展示探测到的项 |
| C10 | 富士口头声明第三方 USB/PTP 访问适用其 SDK EULA 5.2（可能影响保修） | 法律/售后风险 | 首次启动风险告知；不修改任何持久化设置（只动 SDRAM 与临时对象）；提供"只读模式" |

### 1.6 逐条需求可行性打分

| 需求 | 判定 | 说明 / 主要工作量 |
|---|---|---|
| ① 设备连接与识别（OTG 自动识别 + 切 RAW CONV 模式） | ✅ 完全可行（App **不能**替用户切模式，只能识别+引导） | 富士没有"切换 USB 模式"的 PTP 命令可用（`0xD15D` SetUSBMode 存在但不可靠且危险），必须引导用户在机内菜单选 `USB RAW CONV./BACKUP RESTORE`。检测到 class=8 或 `0xD16E≠6` 时给图文引导。**这是"1:1 复刻"必须诚实的一处差异** |
| ② 文件导入 + 高速写入 SDRAM | ✅ 可行 | SAF 导入 + `ContentResolver.openFileDescriptor`（不要 copy 到内存）+ 文件流式上行。速度瓶颈在相机，实测 30MB 约 2~4s |
| ③ 机内参数完整控制 | ✅ 可行（部分需实测） | 胶片模拟/WB/DR/高光阴影/色彩/锐度/清晰度/NR/颗粒/LMO 全部落在 d185 29 字段内；**新模拟枚举**与 **TIFF 输出**需实测确认 |
| ④ 实时预览 + 全分辨率渲染 | ✅ 可行且能做得比官方快 | 预览 = `D183=0` + `GetThumb`（~0.5–1.2s）；更高质量的"准预览" = d185 里把 ImageSize 设 `S3x2`/`M3x2` 再 `D183=1`；全图 = `D183=1` + `GetObject` |
| ⑤ 批量处理 | ✅ 可行 | 串行队列 + 前台服务 + 通知进度；每张"上传→渲染→取回→删除"，失败单张隔离 |
| ⑥ 元数据读取 + Recipe 管理 | ✅ 可行（读取有两条路） | 在线：读回 `0xD185` 解析出全参数（**最准**）。离线：解析 RAF 的 EXIF MakerNote（富士专有 IFD，`FUJIFILM` 魔数，需自写 TIFF/IFD 解析）。Recipe 格式：直接复用 FP1/FP2/FP3 XML（X RAW Studio 用户档案格式）+ 自有 JSON + Fuji X Weekly 风格文本，导入 FilmKit 预设 |
| ⑦ UI 1:1 暗色三栏 | ⚠️ 需重新设计（桌面→手机） | 见 §6：横屏/平板做真三栏；竖屏用"预览上 + 参数抽屉 + 队列底栏"的等价布局 |

---

## 2. Android 端 USB PTP 核心代码框架

### 2.1 分层

```
┌─────────────────────────────────────────────────────────────┐
│ UI (Jetpack Compose, dark)   ParamPanel / PreviewStage / Queue│
└───────────────────────────┬─────────────────────────────────┘
                            │ StateFlow<RecipeState>, Intent
┌───────────────────────────▼─────────────────────────────────┐
│ CameraRepository           能力表 / 会话状态 / 错误翻译       │
│   └ CameraJobQueue (单线程串行 + Mutex + 取消 + 重试)         │
└───────────────────────────┬─────────────────────────────────┘
┌───────────────────────────▼─────────────────────────────────┐
│ FujiRawConvert        富士语义层：上传/改档/渲染/取回          │
│   ├ D185Profile       d185 codec（只 patch，不重构）          │
│   └ FujiCaps          机型能力探测与缓存                      │
└───────────────────────────┬─────────────────────────────────┘
┌───────────────────────────▼─────────────────────────────────┐
│ PtpUsbTransport       标准 PTP：容器/事务/session/chunk I/O    │
│   └ UsbDeviceConnection / bulkTransfer / UsbRequest           │
└───────────────────────────┬─────────────────────────────────┘
                        X-M5 (class 6, VID 0x04CB)
```

对应本方案交付的三个 Kotlin 文件：
* `PtpUsbTransport.kt` —— 传输层（容器编解码、事务、chunk 自适应、流式大文件上行）
* `FujiRawConvert.kt` —— 富士语义层（完整 1.4.4 时序）
* `D185Profile.kt` —— d185 编解码 + 参数抑制规则

（完整代码见同目录 `code-skeleton/`。下面是关键设计要点，正文不重复贴全量代码。）

### 2.2 传输层要点（`PtpUsbTransport`）

1) **设备发现与权限**
```kotlin
val usb = ctx.getSystemService(UsbManager::class.java)
val dev = usb.deviceList.values.firstOrNull {
    it.vendorId == 0x04CB && it.getInterface(0).interfaceClass == 6
}
// 权限：Android 12+ 必须显式 FLAG_IMMUTABLE
usb.requestPermission(dev, PendingIntent.getBroadcast(...,
    Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName),
    PendingIntent.FLAG_MUTABLE).intentSender)     // 需要携带 package，故用 MUTABLE + setPackage
```
2) **接口与端点**：取 `interfaceClass==6` 的接口；在该接口下按 `direction + type==BULK` 找 in/out；interrupt IN 先不用（初版不读事件端点，避免 stall）。
3) **claim**：`connection.claimInterface(iface, true)`（force=true，Android host 模式下通常无内核驱动，但仍要 force 兜底）。
4) **容器事务状态机**：`COMMAND → [DATA OUT] → RESPONSE` / `COMMAND → DATA IN → RESPONSE`，`transactionId` 单调自增，响应容器 tid 必须匹配（不匹配=协议错乱，直接重建会话）。
5) **接收**：先读进一个 64KB 缓冲，解析 `length`，再循环补足，**不能假设一次 bulkTransfer 读满一个容器**。
6) **上行大文件**：先发 12 字节 DATA 容器头（声明 `12 + fileSize`），再从 `FileInputStream` 逐 chunk 写入；**绝不把 60MB 读进 ByteArray**。
7) **chunk 自适应**：起始 512KB，若 `bulkTransfer` 返回 <0 或短写，降级 64K→16K→4K 并记忆。这样在"某些手机 USB 控制器不支持大 URB"时自动兼容。
8) **超时**：命令 5s；数据阶段 30~120s；`bulkTransfer` 超时返回 -1 是**常规现象**（相机懒得回 ZLP），在数据阶段应视为"该 chunk 可能已写入"的模糊态 → 只在命令/响应阶段当错误处理，数据阶段用"总进度核对"策略。

### 2.3 富士语义层骨架（`FujiRawConvert`）

```kotlin
class FujiRawConvert(private val t: PtpUsbTransport) {

    /** 连上后的自检：机型、模式、剩余 SDRAM、遗留对象清理 */
    suspend fun handshake(): FujiSession

    /** 上传一张 RAF 到机身 SDRAM（0x900C + 0x900D）——每张只做一次 */
    suspend fun uploadRaf(src: RafSource, onProgress: (Long, Long) -> Unit)

    /** 读回该 RAF 的机内原始参数（0xD185） */
    suspend fun readProfile(): D185Profile

    /** 应用用户参数（只 patch 改动字段） */
    suspend fun writeProfile(p: D185Profile)

    /** 渲染：full=false 用 D183=0 + GetThumb 拿预览；full=true 用 D183=1 + GetObject 拿全图 */
    suspend fun render(full: Boolean, timeoutMs: Long, onStage: (String) -> Unit): ByteArray
}
```
其中 `render()` 的关键细节：
* 写 `0xD183` 用 **DATA 阶段传 u16**（不是用参数传，`SetDevicePropValue` 是 data-out 事务）。
* 轮询用 `GetObjectHandles(0xFFFFFFFF, 0, 0)`，解读 `u32 count + handles[]`；**先清空历史对象**再触发渲染，否则会取到上一次的图（这是最容易踩、最难查的一类 bug）。
* 取回后立刻 `DeleteObject`；失败也要 delete（finally）。
* 超时兜底：`D183=1` 后 30s 无对象 → 判定失败，发一次 `D183=0` 试探是否相机仍响应，用于区分"相机忙"与"会话死"。

### 2.4 d185 编解码铁律（`D185Profile`）

```kotlin
val raw: ByteArray = fuji.readProfile().rawCopy()   // 相机给的原始 628B
val p = D185Profile.parse(raw)
p.setU32(D185.FILM_SIMULATION, 17)                  // Classic Negative
p.setU32(D185.SHADOW_TONE, -20)                     // -2.0（×10 编码）
p.clampAndSanitize()                                // 抑制规则：DR Priority / 单色跳过色彩 / 色温只在 WB=0x8007
fuji.writeProfile(p.bytes)
```

`clampAndSanitize()` 实现的抑制规则（来自 rawji 的实测注释，**照抄即可**）：
* `WideDRange(6) != OFF` → 不写 `DynamicRange(5)`；且 `HighlightTone(15)`、`ShadowTone(16)` 置 0（相机会自己清，别对抗）。
* 单色类模拟（6,7,8,9,10,12,13,14,15）→ 跳过 `ChromeEffect(9)`、`ColorChromeFXBlue(24)`、`Color(17)`、`WBShiftR/B(12,13)`；改用 `BlackImageTone(22)`=WC、`MonochromaticColor_RG(25)`=MG。
* `WhiteBalanceMode(11) != 0x8007` → 不写 `ColorTemperature(14)`。
* `DynamicRange(5)` **不得高于原 RAF 的拍摄 DR**（超了相机拒绝）；本地先 clamp。
* `GrainEffect(8)` 是复合值：`(effect) + (size==LARGE ? 0x2 : 0)`。

---

## 3. 完整功能模块架构图

### 3.1 模块（Gradle 多模块，可单模块起步）

```
:app                  Compose 壳 / DI / 导航 / 前台服务
:core:model           Recipe, D185, FilmSim, 能力表（纯 Kotlin，无 Android 依赖，可单测）
:core:ptp             PtpUsbTransport, Container, PtpException, ChunkTuner
:core:fuji            FujiRawConvert, D185Profile, FujiCaps, RafInspector(型号/序列号/内嵌JPEG)
:core:transfer        CameraJobQueue, Job 状态机, 重试/退避, 看门狗, 电源锁
:core:recipe          Recipe 仓库(Room), FP1/FP2/FP3 XML 导入导出, MakerNote 解析
:core:media           SAF 导入、MediaStore 保存、EXIF 读写、缩略图
:feature:connect      连接向导（含"请把相机设为 USB RAW CONV."图文）
:feature:edit         参数面板（与 X RAW Studio 左栏 1:1 对齐的控件表）
:feature:preview      预览舞台（本地内嵌 JPEG 先出图 → 相机预览替换）
:feature:batch        批量队列、进度、失败重试
:feature:recipe       配方库、相机 C1–C7 读写（需实测）
```

### 3.2 数据流

```
SAF RAF ──► RafInspector ──► 本地内嵌 JPEG（立刻显示）
                    │                 │
                    └─ 型号/序列号比对（C1 本地拦截）
                                      ▼
RecipeState(用户参数) ──► CameraJobQueue ──► FujiRawConvert ──► X-M5
      ▲                                   │
      │                                   ▼
   UI 双向绑定 ◄──── PreviewCache(key = rafHash + paramHash) ◄── JPEG/TIFF
```

### 3.3 相机作业队列（本项目最重要的架构决策）

相机是**单会话、有状态、慢**的设备，因此：

```
[UploadJob(raf)]  →  [RenderJob(params, preview)]  →  [RenderJob(final)] →  [SaveJob]
        │                     ▲   取消旧 job（滑杆连续拖动时）
        └─ 结果：sessionId（相机端"当前已装载的 RAF"）
```

* **串行执行器 + `Mutex`**：任何时刻最多一个 PTP 事务；
* **可取消 + 合并**：`RenderJob` 带 `paramsHash`，若队列中已有未执行的 Render，直接替换参数（debounce 400ms）；
* **sessionId 复用**：参数变化只跑 RenderJob，不跑 UploadJob；
* **失败隔离**：单张失败 → 记录 + `DeleteObject` 清理 + 继续下一张；
* **看门狗**：每 15s 读一次 `0xD242` 电量作为心跳，连续 2 次失败 → 判定断连 → 迁移到"重连"状态。

---

## 4. 开发步骤路线（含验收标准）

### Phase 0 · 协议可行性验证（0.5~1 天，**先做这个，别写 Kotlin**）
1. 手机装 Chrome，打开 filmkit（WebUSB）或自写 30 行 HTML；
2. 相机设 `CONNECTION MODE = USB RAW CONV./BACKUP RESTORE`、`USB POWER SUPPLY SETTING = OFF`、`AUTO POWER OFF = OFF`；
3. OTG 线接上 → 授权 → 上传一张本机 RAF → 看到 JPEG。
* 验收：**手机能出图**。若这一步失败，先排查线材（建议用带数据线的原装线；华强北"充电线"无数据线是头号杀手）、OTG 转接、相机供电。
* 产出：一份 `device-info.json`（你机器的 opcode/prop 列表），后面做能力表要用。

### Phase 1 · 纯 Kotlin 打通全链路（1~2 周）
* `PtpUsbTransport` + `FujiRawConvert` 最小版：handshake → upload → readProfile → writeProfile(不改) → D183=1 → GetObject 存文件。
* 验收：命令行/调试页点一下，能把 `example.RAF` 转成 JPEG 且肉眼一致。
* 风险点：chunk 大小、超时、`0x900C` 参数个数（3 vs 4）、`0x900D` 后是否需要延时。

### Phase 2 · 预览与参数控制（2 周）
* `D185Profile` 全字段 patch + 抑制规则；`D183=0` + `GetThumb` 预览；debounce + 缓存。
* 验收：拖滑杆 → 1s 内出预览；连续拖 20 次不崩、不累积旧图（验证 DeleteObject/清对象逻辑）。
* 里程碑：**参数面板 20 个控件全部真实生效**（用同一张 RAF 逐一对比）。

### Phase 3 · UI 1:1 复刻（2 周）
* Compose 暗色三栏（§6）；自适应手机竖屏；参数分组顺序与 X RAW Studio 左栏一致。
* 验收：老 X RAW Studio 用户 3 分钟能上手（可用性走查）。

### Phase 4 · 批量 + 前台服务（1.5 周）
* WorkManager/前台服务 + 通知进度；串行队列；单张失败隔离；电量/SDRAM 预检；屏幕常亮 + partial wakelock。
* 验收：20 张批量，中途锁屏、切后台、来电话，全部完成且无脏对象残留。

### Phase 5 · 配方与元数据（2 周）
* Recipe 模型 + Room；FP1/FP2/FP3 XML 导入导出（复用 libfuji `fp/` 的格式定义）；FilmKit JSON 导入；EXIF MakerNote 离线读参数；相机 C1–C7 读写（实测后再开）。
* 验收：导入一份 Fuji X Weekly 配方 → 一键出图 → 与手工录入一致。

### Phase 6 · 加固与机型化（持续）
* 能力探测表（§7.3）、错误码→人话映射、崩溃恢复、PTP debug 日志导出（用户可分享 pcap 给社区）、只读模式。

### 需要单独做的"逆向小工具"（开发期自用）
1. **`Ptpdump`** 页面：枚举 `0x1001` 支持的 opcode/DPC 全表并导出 JSON（能力表原料）。
2. **`SimSweeper`**：对同一张 RAF，遍历 filmSim 值 1..40，各渲染一张 64px 缩略图拼成网格 → 一次性摸清旧/新/隐藏模拟的枚举值（§7.2）。
3. **`PropScraper`**：遍历 `0xD000–0xD3FF` 全部属性，记录可读值/类型/范围（顺带发现未知属性）。

---

## 5. 关键技术专题

### 5.1 为什么纯 Kotlin 足够，不必上 libusb/JNI
* 需求是 bulk 传输，`UsbDeviceConnection.bulkTransfer` 完全够；fudge 用 ioctl 是为了追求极限与跨平台复用，不必照抄。
* 保留后路：若某机型上 Java 层吞吐不达标，可在 `:core:ptp` 后面加一个 `PtpTransport` 接口，用 JNI + libusb（`UsbDeviceConnection.getFileDescriptor()` 把 fd 传给 libusb）实现第二后端，上层代码零改动。**一开始就把传输层抽象成接口**，这是唯一的"未来保险"。

### 5.2 实时预览的三级策略（性能关键）
| 级别 | 手段 | 延迟 | 用途 |
|---|---|---|---|
| L0 | RAF 内嵌 JPEG（本地解析，无需相机） | ~50ms | 打开文件瞬间出图；参数无关 |
| L1 | `D183=0` + `GetThumb`（640×480） | 0.5~1.2s | 滑杆实时预览（默认） |
| L2 | d185 里 `ImageSize=S3x2` + `D183=1` | 1.5~3s | "更准的预览"（近似全图渲染，含清晰度/颗粒的真实表现） |
| L3 | 全分辨率 + `GetObject` | 2~6s | 最终出片 |

> 注意：L1/L2 的**颗粒(Clarity/Grain)在小图上表现与全图不同**，UI 里要提示"预览仅供参考"，或提供"高保真预览"开关（走 L2）。

### 5.3 缓存与去重
`PreviewCache.key = sha256(rafId, paramsHash, level)`，LRU 上限按内存 1/8。批量时同一套参数跨多张图，参数侧的 `paramsHash` 只算一次。

### 5.4 大文件（30–60MB）稳定传输与防断连
1. **流式上行**：`FileInputStream` → 512KB buffer → `bulkTransfer` 循环；进度按字节回报（UI 必须可见，否则用户会拔线）。
2. **chunk 自适应降级**（C5），成功后锁定该值。
3. **整文件重试**（C4）：失败 → `CloseSession` → 重新 `OpenSession` → **重放整个上传**（最多 3 次，指数退避 0.5/1/2s）。PTP 无 offset 语义，不要幻想断点续传。
4. **幂等清理**：每次连上都先 `GetObjectHandles` 全删；上传失败后也尝试清理，避免 SDRAM 泄漏导致"第二张必失败"。
5. **保活**：15s 心跳（读电量属性）；长任务持续持有 `PARTIAL_WAKE_LOCK` + `FLAG_KEEP_SCREEN_ON`；前台服务 + 常驻通知。
6. **USB 侧监听**：`ACTION_USB_DEVICE_DETACHED` 广播 → 立刻标记会话死亡、停止队列、UI 显示"线断了，点这里重连"；`ACTION_USB_DEVICE_ATTACHED` → 自动拉起连接向导。
7. **不去 detach kernel driver**：Android host 模式下 PTP 接口通常无绑定驱动；若 `claimInterface` 失败，再尝试 `setInterface`/重开连接。
8. **不要用 1.5m 以上长线**（富士手册明确要求 ≤1.5m），并在首启提示。

### 5.5 错误码 → 人话 映射（示例）
| PTP 响应 | 含义 | UI 文案 |
|---|---|---|
| `0x2001` | OK | — |
| `0x2002` | GeneralError | "相机拒绝了这张 RAF（通常不是本机拍摄，或格式不符）" |
| `0x2003` | SessionNotOpen | "连接已失效，正在重连…" |
| `0x2019` | DevicePropNotSupported | "该机型不支持这个参数" |
| `0x201C` | 观察到：DR Priority 激活时写 DR | "请先把『动态范围优先』设为 OFF" |
| `0x201E` | SessionAlreadyOpen | 视为成功 |
| timeout / -1 | USB 层 | "相机无响应：检查线材/供电/自动关机" |

### 5.6 X-M5 供电与物理连接（踩坑高发区）
* 相机 `USB POWER SUPPLY SETTING = ON` 时会从手机取电 → **必须 OFF**（X-M5 手册中"连接智能手机"一节的原文要求）。
* 批量长跑建议：**带 PD 供电的 USB-C HUB/扩展坞**（手机端供电，相机端数据），或用带外接电源的 OTG 线。
* 手机端建议关闭"USB 自动休眠/省电优化"，某些厂商 ROM 会在锁屏后断 USB 供电。

---

## 6. UI / UX 规格（1:1 复刻 X RAW Studio 的暗色专业布局）

### 6.1 布局
```
横屏 / 平板 (≥600dp)                       竖屏手机
┌─────────────┬──────────────────────┐    ┌────────────────────┐
│ 左栏 参数区  │  右栏 预览舞台         │    │  预览舞台 (40%)     │
│ (360dp 固定)│  ┌──────────────────┐ │    │  ┌──────────────┐  │
│ ┌─────────┐ │  │  图像 / 直方图     │ │    │  │  图像/直方图   │  │
│ │胶片模拟 ▾│ │  └──────────────────┘ │    │  └──────────────┘  │
│ │DR  100  │ │  底部: 参数摘要/信息条  │    ├────────────────────┤
│ │高光/阴影 │ ├──────────────────────┤    │  参数区（横向 Tab:  │
│ │色彩/锐度 │ │  文件队列（横向卡片）   │    │  胶片|色调|色彩|细节）│
│ │颗粒/清晰 │ └──────────────────────┘    ├────────────────────┤
│ └─────────┘ │                            │  队列（可上拉抽屉）   │
└─────────────┴──────────────────────────┘    └────────────────────┘
```

### 6.2 参数面板顺序（与 X RAW Studio 左栏一致，用户肌肉记忆）
`胶片模拟 → 动态范围 / 动态范围优先 → 颗粒效果(强弱/大小) → 色彩效果(彩色FX蓝/彩色铬效应) → 白平衡(模式/色温/R-B偏移) → 高光色调 → 阴影色调 → 色彩 → 锐度 → 降噪 → 清晰度 → 镜头调制优化器(LMO) → 图像尺寸/质量/文件类型 → 色彩空间`

### 6.3 视觉规范
* 背景 `#111214`，面板 `#1A1C1F`，分隔线 `#2A2D31`，主文字 `#E6E6E6`，次文字 `#8A8F96`，强调色 `#E8B44A`（富士金橙，仅用于激活态/进度）。
* 滑杆：细轨 + 中线归零点 + 数值右键"复位"；`-2..+2` 类参数步进 0.5，清晰度 1，色温 100K。
* 预览舞台：中性灰底（`#0C0C0D`）、双指缩放/平移、双击 fit/100%、长按对比"原图(内嵌 JPEG)"。
* 出图后：底部信息条 `X-M5 · 5568×3712 · Classic Neg · DR200 · 1.8s` —— 参数摘要正是配方分享的文案来源。

### 6.4 交互细节（比官方更好的地方）
* 滑杆拖动 → 400ms debounce → 只发 `SetProfile + D183=0 + GetThumb`；抬指立刻发；
* 队列卡片显示"已装载(无需重传)"状态徽标，让用户理解为什么第二张更快；
* 所有参数改动**即时本地持久化**（Room），断线重连后自动恢复；
* 出图成功后提供"一键复制配方文本"（Fuji X Weekly 风格）。

---

## 7. 逆向实验手册（补空白点用）

### 7.1 抓官方实现的包（最可靠的学习方式）
* **Windows**：装 USBPcap + Wireshark，过滤 `usb.transfer_type == 0x02`（bulk）→ 只看到 PTP；操作 X RAW Studio 的四种动作各抓一份：①连接读状态 ②载入 RAF ③改参数出预览 ④出全图。对比"同一张图不同参数"的两份 pcap，diff 出 d185 里变化的那 4 字节偏移 = 该参数的索引。
* **Android/无 Windows**：用 `Wireshark on Linux + usbmon`，或直接抓你自己的 App（在 transport 层打印 hex）。
* 抓包的三条黄金对照实验：单参数只改一项 / 参数互斥项组合 / 边界值（求 clamp 范围）。

### 7.2 探明新胶片模拟的枚举值（SimSweeper）
对同一张 RAF，`filmSim = 1..40` 逐值：
1. 写 d185（读回原档 → patch idx7 → 回写）；
2. `D183=0` + `GetThumb`；
3. 保存 `sim_XX.jpg`，同时 `GetDevicePropValue(0xD185)` 记录**相机回读值**（若相机把值规范化/拒绝，就说明该值非法）；
4. 拼成 8×5 网格图人工比对。
产出：`filmSim → 名称` 映射表，存进 `FujiCaps`（每机型一份，可云端更新）。

### 7.3 机型能力表（`FujiCaps`）
```
model             = "X-M5"
firmware          = "1.00"
iopCode           = "000C...."
maxFilmSim        = <实测>
supportsClarity   = true
supportsGrain     = true
drMax             = 400
hasRecipeSlots    = <实测 D18C 是否存在>
maxUploadBytes    = <实测：二分法找最大可上传 RAF/或被 0xD20E 限制>
chunkSizePreferred= 524288 | 65536 | 16384
```
获取方式：`GetDeviceInfo` 的 `DeviceProps` 列表 + 逐属性 `GetDevicePropDesc`（拿 min/max/step/enum）+ SimSweeper。**这张表是所有"隐藏/最新模拟"能力的唯一可靠来源**，也是后续支持 X-T5/X100VI 的扩展点。

---

## 8. 开源复用指引（怎么少写 80% 代码）

### 8.1 路线 A（推荐）：**照抄语义，自己写 Kotlin**
读这三个文件就够，全部是 200~600 行的纯逻辑：
* `libfuji/lib/fuji_usb.c` → `fuji_send_raf()` / `fuji_process_raf()`：**完整 RAW 转换时序**（本项目 `FujiRawConvert` 的蓝本）
* `libfuji/lib/fujiptp.h` → 全部 opcode / DPC 常量表（直接翻成 Kotlin `object FujiPtp`）
* `libfuji/fp/src/d185.c` + `data.c` → d185 编解码 + 全部取值表（直接翻成 Kotlin `D185Profile`）
* `rawji/src/rawji/fuji_usb.py` → 同上，但注释里有大量**实测的字段抑制关系**（最有价值的部分）
* `ILFforever/fujifilm-ptp-recipes/docs/*` → 配方槽协议、属性表、MakerNote 映射

优点：无 native 构建、无 GPL 传染（libfuji 为 MIT，libgphoto2 为 LGPL —— **注意 LGPL**：若直接链接 libgphoto2 动态库需满足 LGPL 条款；仅"参考其常量/逻辑自己实现"则不受限）。
工期：约 3~5 天可完成移植。

### 8.2 路线 B：JNI 直接用 C 库
* `libpict`（Apache-2.0）+ `libfuji`（MIT）：NDK 交叉编译 aarch64，`PtpTransport` 接口背后换成 JNI 调用。
* USB 后端二选一：① libusb（需把 `UsbDeviceConnection.getFileDescriptor()` 传进去）；② 学 **fudge** 的做法——C 层直接 `read/write/ioctl` 操作 `/dev/bus/usb/...`，Java 只负责权限与生命周期。
* 适合：你想同时做一个共享内核的桌面版（fudge 就是这么干的：同一份 C 内核跑 Android/桌面/iOS）。

### 8.3 路线 C（**最快的验证捷径，但不是产品**）
Android Chrome 支持 **WebUSB**，filmkit 是纯前端 → 手机上直接开网页即可跑通 RAW 转换。
* ⚠️ 注意：**Android WebView 不支持 WebUSB**，所以不能"包一层 WebView 变 App"。这条路只用于 Phase 0 验证协议与摸清行为。

### 8.4 不能复用/要绕开的
* **gphoto2 Android 移植版**：社区有过 `libgphoto2 + libusb` 的 NDK 构建尝试，但依赖 `libgphoto2_port` 的 hotplug/udev 模型，在 Android 上没有 udev、权限模型也不同，**维护成本高于收益**。建议只取其 `ptp.c/ptp-pack.c` 的容器编解码逻辑，不整体移植。
* X RAW Studio 的 `XRFC.DAT`（每机型能力表，混淆过）：不要试图反编译它；用 §7.3 的探测法自建，反而更可控。
* **不要用富士官方 SDK（XSDK/XGFXAPI.dll）**：只有 x86_64 Windows/macOS 二进制，且 EULA 5.2 明确可能影响保修。

### 8.5 法务提醒
* 富士对第三方 USB/PTP 访问的态度：社区记录为"口头表示适用 SDK EULA 5.2（保修条款）"。产品层面建议：首启风险告知、只操作临时 SDRAM 与临时对象、不写入/不修改任何相机持久设置（除非用户明确点"C1–C7 写入"）、不绕过任何加密/版权保护。
* `libgphoto2` 是 **LGPL-2.1+**：动态链接 + 允许替换库即可合规；静态链接会传染。`libfuji/libpict` 是 MIT/Apache，友好。

---

## 9. 风险登记表与退出策略

| 风险 | 概率 | 影响 | 缓解 | 退出策略 |
|---|---|---|---|---|
| X-M5 的 d185 字段/枚举与已知机型不同 | 中 | 部分参数不生效 | Phase 1 就先做"全字段扫描 + 回读校验"工具；只 patch 不重构 | 降级为"参数子集 + 明确标注不支持项" |
| 上传大 RAF 失败率高 | 中 | 核心功能不可用 | chunk 降级、重试、0xD20E 预检、清理残留 | 提供"先删卡内旧对象再传"的一键修复 |
| 机身 SDRAM 容量小 | 中 | 批量受限 | 逐张处理、即时删除、预检 | 批量改为"小批量+自动分批" |
| 手机 ROM 断 USB 供电/杀后台 | 高 | 长任务失败 | 前台服务 + 唤醒锁 + 心跳 + 断线重连 | 提示使用带供电 HUB |
| 新胶片模拟值未知 | 高 | 功能缺失 | SimSweeper 实测（半天就能扫完） | UI 只显示已探测项 |
| 保修/EULA 争议 | 低 | 法务 | 风险告知 + 只读模式 | 提供关闭全部写操作的"仅预览"模式 |

---

## 10. 与本仓库既有工程的协同

本仓库已有 `fotufilm-engine`（Swift 色彩/胶片引擎）+ `fotufilm-android`（JNI 壳）。本项目的定位与之互补：

* **机内路径**（本文档）：走 X-M5 的 X-Processor 5，得到**富士官方色彩**——是"真值基准"。
* **本地路径**（fotufilm）：纯软件模拟 50 支胶片。
* **协同玩法**：用机内渲染结果作为 fotufilm 引擎的**回归基准**（同一 RAF，机内 Classic Neg vs 本地模拟，做 ΔE 对比）；反过来，本地引擎可在无相机时提供"离线预览"，相机连上后自动替换为机内真图。`FujiRawConvert` 与 fotufilm 引擎都可作为 `RenderBackend` 接口的两个实现，UI 无感切换。

---

## 附录 A：一键自检清单（开发第一天就用）
- [ ] 线材是**数据线**（能传文件的那种）
- [ ] 相机 `CONNECTION MODE = USB RAW CONV./BACKUP RESTORE`
- [ ] 相机 `USB POWER SUPPLY SETTING = OFF`
- [ ] 相机 `AUTO POWER OFF = OFF`
- [ ] 手机 OTG 已启用 / 用 OTG 转接头
- [ ] `UsbManager.deviceList` 能看到 VID `0x04CB`，接口 class 6
- [ ] `GetDevicePropValue(0xD16E)` 返回 6
- [ ] `example.RAF` 是**这台 X-M5** 拍的
- [ ] 每次渲染后 `DeleteObject` 已执行（否则第二张必挂）

## 附录 B：参考资料（可直接访问）
1. `github.com/petabyt/libfuji` — `lib/fuji_usb.c`（RAW 转换全流程）、`lib/fujiptp.h`（常量表）、`fp/`（d185 编解码）
2. `github.com/pinpox/rawji` — `src/rawji/fuji_usb.py`（600 行完整实现，注释含字段抑制关系）
3. `github.com/petabyt/libpict` — 标准 PTP 客户端（APache-2.0）
4. `codeberg.org/p/fudge` — Android 原生 USB-OTG 富士客户端（Java + C）
5. `github.com/eggricesoy/filmkit` — WebUSB 版（含 `QUICK_REFERENCE.md` 协议摘要）
6. `github.com/ILFforever/fujifilm-ptp-recipes` — 协议/属性/MakerNote/官方二进制逆向笔记（**最权威文档**）
7. `github.com/gphoto/libgphoto2` — `camlibs/ptp2/ptp.h`（Fuji opcode/DPC 母本）、`library.c`
8. `danielc.dev/blog/fudge1/` — 富士 PTP/IP 与 USB 模式总览（作者即 libfuji/libpict 作者）
9. 富士 X-M5 手册「Network/USB Setting Menus」与「X-M5 兼容性表」（确认 X RAW STUDIO 支持）
