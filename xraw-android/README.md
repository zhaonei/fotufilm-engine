# Fotofilm X RAW · Android 版「X RAW Studio」（Fujifilm X-M5）

在 Android 手机上 1:1 复刻富士官方 **X RAW Studio** 的核心工作流：用 USB-OTG 把 RAF 推回机身，
由 **X-Processor 5** 做真正的 RAW 解码与色彩计算，App 只负责「传文件 + 改参数结构体 + 收图」。

> 本项目不依赖 gradle / AndroidX / Compose / aapt2：`javac → d8 → 手写二进制 AXML → zip → apksigner`，
> 因此可以在这台 aarch64 主机和 GitHub Actions 上以同一套脚本构建出**逐字节可复现**的 APK。

---

## 1. 它能做什么

| 能力 | 实现方式 |
|---|---|
| USB-OTG 识别富士相机 | `VID=0x04CB` + `interfaceClass==6`，并校验 `0xD16E==6`（USB RAW CONV. 模式） |
| 导入 RAF（单个 / 批量） | SAF 选择器 → 流式复制到 cache（不整文件进内存），并解析 RAF 头取机型做前置拦截 |
| 上传 RAF 到机身 SDRAM | `0x900C`(SendObjectInfo, objFormat=0xF802, `FUP_FILE.dat`) + `0x900D`(SendObject2) 流式上行 |
| 读回该 RAF 的机内原始设置 | `GetDevicePropValue(0xD185)` → 解析 628B 的 d185 → 回填到参数面板 |
| 改参数 | 只 patch d185 的 29 个 u32 字段（**从不从零构造**），保留 IOPCode 与 unknown 字段 |
| 预览 | `SetDevicePropValue(0xD183=0)` + 轮询 `GetObjectHandles` + `GetThumb`（~0.5–1.2s） |
| 出图 | `SetDevicePropValue(0xD183=1)` + `GetObject` → 存相册/下载；出图后 `DeleteObject` 清理 |
| 批量 | 串行队列：上传→渲染→取回→删除，单张失败隔离，不污染后续 |
| 配方库 | JSON 存于 App 私有目录，可保存/载入整套参数 |
| 自检/探测 | 导出 DeviceInfo + d185 全字段；「胶片模拟扫描」逐值渲染 20 张缩略图 |

### 参数面板（与机内菜单 / X RAW Studio 左栏对齐）

输出：图像尺寸（L/M/S × 3:2/16:9/1:1）· 图像质量（FINE/NORMAL）· 文件类型（**JPEG / TIFF 8bit / TIFF 16bit**）· 色彩空间（sRGB/AdobeRGB）
胶片模拟：20 项（PROVIA … ETERNA 漂白 / 怀旧负片 / REALA ACE，后三者为推断值，需真机扫描确认）
动态范围：DR100/200/400 · 动态范围优先（关/弱/强）
色调：高光色调 · 阴影色调（**X-Processor 5：-2.0 ~ +4.0，0.5 步进，×10 编码**）
色彩：色彩 · 锐度 · 降噪（非线性编码）· 清晰度 · 颗粒效果(关/弱/强 × 小/大) · 彩色铬效应 · 彩色 FX 蓝色 · LMO
白平衡：自动/日光/阴天/荧光灯1-3/白炽灯/水下/自定义1-3/色温 · R/B 偏移 · 按拍摄时(As Shot)
黑白：WC 暖冷 · MG 洋红绿（仅单色模拟生效）
曝光：曝光补偿 -3 ~ +3（1/3 EV）

---

## 2. 构建

```sh
./build.sh
# → out/FotofilmXRAW.apk   (debug 签名的 debug.keystore 已随仓库提供，签名稳定可覆盖升级)
```

依赖（任一来源）：
* `ANDROID_HOME` / `ANDROID_SDK_ROOT` 下的 `platforms/android-*/android.jar` 与 `build-tools/*`（d8.jar、apksigner.jar）
* 或本机 `~/dl/plat35/android-35/android.jar`、`~/dl/bt35/android-15/`（脚本自动探测）

云端：`.github/workflows/build-xraw-apk.yml`（push 到 `xraw-android-app`/`main` 或手动 `workflow_dispatch` 即出 artifact；打 tag 会附加到 Release）。

### 单元测试（无需真机）

```sh
javac --release 11 -cp "$ANDROID_HOME/platforms/android-35/android.jar" -d out/test \
  tools/selftest/SelfTest.java app/src/com/fotufilm/xraw/{D185,Recipe,UsbPtp,Ptp}.java
java -cp "$ANDROID_HOME/platforms/android-35/android.jar:out/test" SelfTest
# → ALL PASS (18)
```
覆盖：d185 解析 / 配方往返无字段漂移 / 高光阴影 ×10 / DR clamp 与 DR 优先互斥 / 颗粒复合编码 /
降噪非线性映射 / 单色识别 / 文件类型扩展名 / 1-3 EV 编码。

---

## 3. 源码结构

```
app/src/com/fotufilm/xraw/
  Ptp.java             常量：opcode / DPC / 响应码 + 人话错误文案
  UsbPtp.java          PTP over USB bulk：容器编解码、事务、流式大文件、chunk 自适应降级
  Fuji.java            富士语义层：握手/上传/d185 读写/触发渲染/取回/对象清理/心跳
  D185.java            628B 配置档：29 字段、全部取值表、X-Processor5 判定、抑制规则
  Recipe.java          UI 参数模型 ↔ d185 双向映射 + JSON 配方库
  Raf.java             RAF 头解析（机型 + 内嵌 JPEG 占位预览）
  CameraEngine.java    单线程相机作业队列（预览可合并、单张失败隔离、断线可重连）
  MediaSaver.java      出图保存（App 私有目录 + Android 10+ 入库相册/下载）
  Ui.java              暗色 UI 构件（SeekBar 行 / Spinner 行 / 卡片）
  MainActivity.java    单 Activity：顶部连接栏 / 中部预览舞台 / 参数面板 / 底部队列
tools/mkaxml.py        手写二进制 AndroidManifest（严格按 AOSP ResXMLTree，chunk 大小自洽）
tools/selftest/        JVM 自测
```

## 4. 已知边界（重要）

1. **真机未验证**：构建环境无 Android 设备（Shizuku 未授权），APK 只经过编译期、签名校验与 JVM 单测；
   USB 交互部分必须在你的手机上实测。
2. **只能渲染本机身拍的 RAF**：跨机身/跨型号相机返回 `0x2002`，App 已在导入时用 RAF 头机型做前置提示。
3. **相机必须处于 `USB RAW CONV./BACKUP RESTORE` 模式**：富士无可靠的"切换 USB 模式"PTP 命令，App 只做识别 + 引导。
4. **供电**：X-M5 在 `USB POWER SUPPLY SETTING=ON` 时会从手机取电，长跑请设为 OFF；建议带供电 OTG HUB。
5. **断线不可续传**：`0x900D` 无 offset 语义，只能整文件重传（已做重试/清理，未做续传）。
6. **三个新胶片模拟的枚举值是推断的**（ETERNA 漂白 / 怀旧负片 / REALA ACE）。请用 App 内
   「自检 → 胶片模拟扫描」在真机确认，然后把结果填进 `D185.SIM_VALUES / SIM_NAMES`。
7. **TIFF 输出**：相机侧支持（d185 idx1 = 9/11），但文件大、传输慢，某些机身/固件可能拒绝，需实测。
8. 风险提示：第三方 USB/PTP 访问可能涉及富士 SDK EULA 5.2（保修条款）。App 只操作临时 SDRAM 与临时对象，
   不写入任何相机持久设置。
