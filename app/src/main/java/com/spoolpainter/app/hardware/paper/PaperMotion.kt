package com.spoolpainter.app.hardware.paper

import android.content.Context
import android.content.pm.PackageManager

/** A possibly executed movement must never be followed by raster dispatch or another queued job. */
class MotionUnknown(message: String, cause: Throwable? = null) : Exception(message, cause)

fun interface PaperMotion {
    suspend fun retractBeforePrint()
    fun checkNoPending() {}
}

/** Cheap UI capability check only; firmware, mode and actual native evidence are checked per job. */
fun paperMotionUnavailableReason(context: Context): String? {
    if (context.checkSelfPermission("android.permission.READ_LOGS") != PackageManager.PERMISSION_GRANTED) {
        return "尚未获准读取打印机日志；需在设备上授予 READ_LOGS 开发权限后才能使用回抽。"
    }
    if (context.checkSelfPermission("com.pos.permission.SECURITY") != PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission("com.pos.permission.PRINTER") != PackageManager.PERMISSION_GRANTED) {
        return "尚未获得设备打印和控制权限，暂时不能使用回抽。"
    }
    return try {
        Class.forName("com.pos.sdk.accessory.POIGeneralAPI", false, context.classLoader)
        null
    } catch (_: ReflectiveOperationException) {
        "此设备未提供经过验证的 POS 纸张控制服务。"
    } catch (_: LinkageError) {
        "设备 POS 控制库不可用。"
    }
}
