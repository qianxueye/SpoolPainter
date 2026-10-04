# POS 适配维护与上游升级

当前建议保留应用级硬件适配层，不另做系统 NFC HAL。设备 Android 11 未声明 `android.hardware.nfc`，`dumpsys nfc` 也没有标准服务；现有读卡与打印来自厂家 `com.pos.service`。应用通过可选共享库和反射调用，不打包厂家 JAR，不需要 root。源码中的三个接口是维护边界：`PosNfcTransport`、`PrinterPort`、`PaperMotion`。库存、二维码、标签内容和中文界面位于应用层。

## 驱动方案的选择

| 方案 | 当前用途 | 边界 |
|---|---|---|
| 当前应用内适配层 | 一个耗材终端，直接复用厂家驱动；改动最小 | 保留接口、成对读卡生命周期与硬件互斥 |
| 独立 Android 库/AAR | 将来复用到多个客户端源码 | 需抽出通用 UID/页面读写与位图打印 DTO；当前接口还引用应用模型，尚不是独立 AAR |
| 独立硬件服务 APK | 多个不同应用同时共享设备 | 需版本化 Binder/Messenger/AIDL 协议、调用方权限、跨应用队列及断连处理；当前未实现 |
| 系统 NFC HAL/内核驱动 | 让 Android 标准 NFC 框架认识硬件 | 需要 OEM 系统/厂商组件与芯片资料，普通 APK 不能完成系统注册；当前设备保留数据、未解锁 |

后续抽库时，通用硬件层只接收原始 UID、受限页面操作、打印位图和受限电机计数，不接收 Spoolman 模型、品牌名称或任意原始系统命令。NDEF/OpenSpool 解析、标签排版、厘米校准与库存操作留在应用层。多个应用共用时，应由一个受保护服务独占硬件，不能把所有厂家 SECURITY 能力直接暴露给客户端。

参考：[Android 绑定服务](https://developer.android.com/develop/background-work/services/bound-services)、[AOSP HIDL 服务架构](https://source.android.com/docs/core/architecture/hidl-cpp)。这两层都不改变天线位置或耦合条件。

## NFC 验证边界

NTAG216 与拓竹标签侧面读取已经确认；25 mm NTAG216 正面读取没有解决，银行卡正面可读。已有临时发射/接收参数对照未改善正面，结束后读回确认恢复原值。组 0x3D/0x3E 是 Type A/Type B 配置，不是已确认的前后天线编号。天线接口返回的固定幅度/相位值不能当成实际场强。

正常适配采用 PICC → MIFARE 的打开顺序和反向关闭顺序，避免绕过触摸屏/RF 共存处理；当前无后台持续轮询。没有已确认的天线选择 API 或控制器型号读出，不再把猜测寄存器当作正式驱动。若要求 25 mm 标签稳定正面读写，下一步需要厂家确认实际芯片、匹配 RF 固件和天线结构，或实测/调整硬件耦合；当前不宣称软件已经修复。NFC 提示使用“读卡区”，不把侧面作为强制默认。

## 上游线路与最小接线点

基底固定为 `v2.4.1`（a082666），来自上游 `v2` 线路。核查时上游 `main` 仍是 2.3.1 稳定线路，不能盲目把它当成新版本覆盖。本次核查的 `upstream/v2` 为 032356b，仅比基底增加文档。

多数适配文件是新增文件。真正需要合并审查的接线点主要是 `MainActivity` 导航、`NfcRepository` 的可选 transport/生命周期、Hilt 模块、AndroidManifest/build 配置和中文 UI 修改。当前中文替换仍触及若干上游组件，未来更新这些组件时可能冲突，不能承诺任意版本零冲突。不要重新定义系统 NFC Tag，也不要覆盖上游标准 Android NFC 路径。

## 可重放补丁包

导出前保持工作区干净。导出的提交序列保留作者和二进制素材，含应用代码、测试、许可和文档，不含厂家固件、JAR、签名密钥或设备数据。

```sh
python3 scripts/pos_patchset.py export --repo . --base v2.4.1 --head HEAD --out /tmp/SpoolPainter-POS-patches
```

在完整上游仓库中获取新版本，明确指定正确的版本线路和全新任务分支：

```sh
git fetch upstream --tags
python3 /tmp/SpoolPainter-POS-patches/pos_patchset.py apply \
  --repo /path/to/SpoolPainter \
  --bundle /tmp/SpoolPainter-POS-patches \
  --onto upstream/v2 \
  --branch codex/pos-next-release
```

如果是直接从上游 clone，远程可能叫 `origin`，应使用 `origin/v2` 或明确的新版本标签。脚本拒绝脏工作区、已有任务分支和早于/偏离基底的版本，使用 `git am --3way`；冲突保留在新分支供审查，不自动 reset、删除或改写历史。解决后 `git am --continue`；放弃时由维护者明确执行 `git am --abort`。

每次升级完成后运行单元测试、lint、APK 构建和本机读卡/打印验收。沿用当前设备安装的签名密钥，才能覆盖安装保留数据。升级前确认没有活动打印；未知结果的打印/回抽不得自动重放。不要使用不同签名的 CI APK 覆盖本地安装。

## 当前 PR 依赖

合并顺序为 #1 POS 基础适配 → #2 回抽校准 → #3 UI 收尾 → 本维护工具 PR。每个包独立提交、测试、可审查/回退。父 PR 合并后，应检查子 PR 的基底与完整差异，再执行最终验证；不改写已发布历史。打印走纸已获机主验收；正面 NFC 的硬件限制与未完成的实际写卡验收仍明确记录。
