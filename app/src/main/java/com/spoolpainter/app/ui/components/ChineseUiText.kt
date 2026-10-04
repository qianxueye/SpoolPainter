package com.spoolpainter.app.ui.components

/** Translates presentation copy while preserving upstream state and test contracts. */
internal fun String.chineseUiText(): String = chineseUiLabels[this] ?: when {
    startsWith("Network error: ") -> "网络错误：" + removePrefix("Network error: ")
    startsWith("Updated spool #") -> "已更新料盘 #" + removePrefix("Updated spool #").removeSuffix(".")
    startsWith("Created spool #") -> "已创建料盘 #" + removePrefix("Created spool #")
    startsWith("Cancelled (") -> "已取消（" + removePrefix("Cancelled (").removeSuffix(")") + "）"
    startsWith("Saved spool #") -> "已保存料盘 #" + removePrefix("Saved spool #")
        .replace(". Finish with Map tag.", "。请点击关联标签以完成。")
        .replace(". Use Write to finish.", "。请点击写入以完成。")
    startsWith("Could not reach Spoolman: ") -> "无法连接 Spoolman：" + removePrefix("Could not reach Spoolman: ")
    startsWith("Spoolman returned ") -> "Spoolman 返回错误：" + removePrefix("Spoolman returned ")
    startsWith("This tag is already paired with spools ") -> "此标签已关联到料盘 " + removePrefix("This tag is already paired with spools ")
        .replace(". Fix in Spoolman first.", "。请先在 Spoolman 中修正。")
    startsWith("Tag written and paired.") || startsWith("Vendor tag linked.") ->
        replace("Tag written and paired.", "标签已写入并关联。")
            .replace("Vendor tag linked.", "厂商标签已关联。")
            .replace(" This spool now has ", " 此料盘现有关联标签：")
            .replace(" tags.", " 个。")
            .replace(" tag.", " 个。")
    else -> this
}

private val chineseUiLabels = mapOf(
    "Your Spoolman companion" to "Spoolman 库存助手",
    "Connected to your self hosted Spoolman server, SpoolPainter helps keep your filament inventory and NFC tagged spools in sync. Browse, create, and edit spools and filaments right from the app. No account and no cloud, just local network sync." to "连接自托管的 Spoolman 服务器，同步耗材库存与 NFC 料盘标签。可在应用中浏览、创建和编辑料盘及耗材，通过本地网络同步，无需账户或云服务。",
    "Vendor tag support" to "支持厂商标签",
    "Tap a supported vendor tag and SpoolPainter will automatically fill in the spool details for you. Bambu Lab, Snapmaker, Creality, QIDI, Anycubic, and Elegoo tags are supported." to "读取受支持的厂商标签即可自动填写料盘信息。支持 Bambu Lab、Snapmaker、Creality、QIDI、Anycubic 和 Elegoo 标签。",
    "Built for U1 firmware" to "适配 U1 固件",
    "Use a tag's built in serial number to link it to a spool in Spoolman. The latest Snapmaker U1 firmware can then identify the correct spool automatically for seamless spool tracking, including spools using their original vendor tags." to "使用标签自带的序列号关联 Spoolman 料盘。支持此功能的 Snapmaker U1 固件可自动识别对应料盘，包括使用原厂标签的料盘。",
    "Pairing made easy" to "轻松关联标签",
    "Pair both tags for a spool without repeating the setup. Move a tag to a different spool anytime and we'll automatically update the pairing." to "为同一料盘关联两个标签，无需重复填写信息。也可将标签重新关联到其他料盘，应用会自动更新关联。",
    "Scan a color with the camera" to "使用相机取色",
    "Point your camera at a spool and SpoolPainter samples the color for you." to "将相机对准耗材，SpoolPainter 会为你采集颜色。",
    "Fresh new look" to "全新界面",
    "SpoolPainter has been completely redesigned from the ground up with a cleaner, more modern interface. Light and dark themes, improved sorting, and more are built right in." to "提供简洁的界面、浅色和深色主题，以及改进的排序功能。",

    "Vendor tag. Press Read to load." to "检测到厂商标签，请点击读取以载入。",
    "Tag detected. Press Read to load." to "检测到标签，请点击读取以载入。",
    "Vendor tag. Write blocked." to "这是厂商标签，无法写入。",
    "Paired only. This tag is too small to write full data." to "已关联标签。标签容量不足，无法写入完整数据。",
    "Spoolman URL not configured" to "尚未配置 Spoolman 地址",
    "Update" to "更新",
    "Create spool" to "创建料盘",
    "Create filament and spool" to "创建耗材和料盘",
    "Create a spool to map this tag." to "请先创建料盘，再关联此标签。",
    "Create spool first." to "请先创建料盘。",
    "Create filament and spool first." to "请先创建耗材和料盘。",
    "Tap a tag to read" to "请将标签靠近读卡器以读取",
    "Tap a tag to write" to "请将标签靠近读卡器以写入",
    "Tap second tag to pair" to "请将第二个标签靠近读卡器以关联",
    "Verifying tag" to "正在验证标签",
    "Linking tag to spool" to "正在将标签关联到料盘",
    "Material" to "材料",
    "Brand" to "品牌",
    "Last Used" to "最近使用",
    "URL saved" to "地址已保存",
    "Bambu salt saved" to "Bambu 标签密钥已保存",
    "Creality tag key saved" to "Creality 标签密钥已保存",
    "Creality encryption key saved" to "Creality 加密密钥已保存",
    "Refreshed spool list" to "料盘列表已刷新",
    "Save a URL first" to "请先保存 Spoolman 地址",
    "Could not parse Spoolman response" to "无法解析 Spoolman 响应",
    "No tag tapped. Try again." to "未检测到标签，请重试。",
    "Pick a spool or hit Save first." to "请先选择料盘或点击保存。",
    "Tap the vendor tag again to capture its UID." to "请再次将厂商标签靠近读卡器，以读取 UID。",
    "Blank tag detected." to "检测到空白标签。",
    "Couldn't read the tag. Hold still and press Read again." to "无法读取标签。请保持标签位置，再次点击读取。",
    "Tag write failed. Try again." to "标签写入失败，请重试。",
    "Configure Spoolman in Settings." to "请在设置中配置 Spoolman。",
    "Vendor tag. Pick a spool first." to "这是厂商标签，请先选择料盘。",
    "Couldn't write to second tag. Tap Write to retry." to "无法写入第二个标签，请点击写入重试。",
    "No second tag tapped. Tap Write to retry." to "未检测到第二个标签，请点击写入重试。",
    "Tag written" to "标签已写入",
    "Vendor tag. Content unreadable." to "这是厂商标签，无法读取其内容。",
    "Couldn't write to tag. Try again." to "无法写入标签，请重试。",
)
