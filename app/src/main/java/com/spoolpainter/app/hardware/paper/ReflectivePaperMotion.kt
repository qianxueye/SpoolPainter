package com.spoolpainter.app.hardware.paper

import android.app.ActivityManager
import android.content.Context
import android.os.IInterface
import android.os.PowerManager
import android.os.Process
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.UUID

/** Optional receipt-only motion; no vendor classes are compiled into or packaged with this app. */
class ReflectivePaperMotion(
    context: Context,
    private val withQuiescentHardware: suspend (suspend () -> Unit) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PaperMotion {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("receipt_paper_motion_safety", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var sequence = 0

    override fun checkNoPending() {
        val storedFault = preferences.getString("fault", "").orEmpty()
        if (preferences.getBoolean("pending", false) || storedFault.isNotEmpty()) {
            val detail = storedFault.ifBlank { "上次回抽的结束状态未保存" }
            throw MotionUnknown("已暂停所有打印：$detail。请检查纸张并由维护人员核对操作记录；重启不会自动重发。")
        }
    }

    override suspend fun retractBeforePrint() = mutex.withLock {
        checkNoPending()
        paperMotionUnavailableReason(app)?.let { throw IllegalStateException(it) }
        withQuiescentHardware {
            // NFC remains paused through capture, movement, post-cleanup, final proof and close.
            currentCoroutineContext().ensureActive()
            val operation = Operation(currentCoroutineContext()[Job])
            coroutineScope {
                val watchdog = launch { delay(10_000); operation.timedOut = true }
                try {
                    // Binder has no verified cancellation: finish cleanup before releasing the pause.
                    withContext(dispatcher + NonCancellable) { perform(operation) }
                } finally { watchdog.cancel() }
            }
        }
    }

    private fun perform(op: Operation) {
        var capture: NativeLogCapture? = null
        var failure: Throwable? = null
        val ids = reserve()
        try {
            ensureActive(op)
            capture = NativeLogCapture.start()
            ensureActive(op)
            val sdk = sdk()
            PaperProtocol.requireVersion(call(sdk, "getSpVersion") as? String)
            val mode = newByteArray()
            check((call(sdk, "getPrintMode", arrayOf(byteArrayType()), arrayOf(mode)) as Int) == 0) { "无法读取当前打印模式" }
            val modeBytes = readByteArray(mode)
            check(modeBytes.size == 1 && modeBytes[0].toInt() == 0) { "回抽只支持已经验证的四相打印模式" }
            requireQuiescent()
            ensureActive(op)
            op.cleanupAllowed = true
            op.pre = transmit(sdk, op, PaperProtocol.statusRequest(ids[0]), movement = false)
            PaperProtocol.parseStatus(unhex(op.pre!!.rxHex), ids[0]).requireReady()
            op.preVerified = true
            requireQuiescent()
            op.preProof = NativeLogAudit.auditPre(capture.checkpoint(), capture.marker, Process.myPid(), op.pre)
            saveAudit(op, op.preProof, "PRE_NATIVE_PROOF_OK")
            ensureActive(op)
            check(preferences.edit().putBoolean("pending", true).putString("operation", op.id).commit()) { "无法保存回抽状态，未发送移动请求" }
            op.feed = transmit(sdk, op, PaperProtocol.retractRequest(ids[1]), movement = true)
            PaperProtocol.successData(unhex(op.feed!!.rxHex), ids[1], 0x33)
            op.feedVerified = true
        } catch (error: Throwable) {
            failure = error
        } finally {
            // This entire IO block is NonCancellable; no new lease and no raster/heat command.
            if (op.cleanupAllowed) {
                try {
                    op.post = transmit(sdk(), op, PaperProtocol.statusRequest(ids[2]), movement = false)
                    val status = PaperProtocol.parseStatus(unhex(op.post!!.rxHex), ids[2])
                    if (op.movementSent) status.requireReady()
                    op.postVerified = true
                    requireQuiescent()
                } catch (cleanup: Throwable) {
                    op.unknownPost = true
                    if (failure == null) failure = cleanup
                }
            }
        }
        try {
            failure?.let { throw it }
            ensureActive(op)
            val completedCapture = checkNotNull(capture)
            val proof = NativeLogAudit.audit(completedCapture.finish(), completedCapture.marker, Process.myPid(), op.pre, op.feed, op.post)
            saveAudit(op, proof, "COMPLETE_NATIVE_PROOF_OK")
            ensureActive(op)
            check(preferences.getString("operation", "") == op.id && preferences.getBoolean("pending", false)) { "回抽操作记录不匹配" }
            check(preferences.edit().putBoolean("pending", false).putString("last_complete_operation", op.id).commit()) { "回抽已验证，但无法保存完成状态" }
        } catch (error: Throwable) {
            val pending = preferences.getBoolean("pending", false)
            val owned = preferences.getString("operation", "") == op.id
            val unknown = op.movementSent || op.unknownPost || (pending && !owned)
            runCatching { saveAudit(op, op.preProof, if (unknown) "MOTION_UNKNOWN" else "NOT_DISPATCHED") }
            if (unknown) {
                val detail = error.message?.replace('\n', ' ')?.replace('\r', ' ')?.take(240)
                    ?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                preferences.edit().putString("fault", "回抽结果尚未确认：$detail").commit()
                throw MotionUnknown("回抽结果尚未确认：$detail。已阻止本次打印及后续队列；请检查纸张并核对设备记录，切勿直接重试。", error)
            }
            if (pending && !preferences.edit().putBoolean("pending", false).commit()) {
                throw MotionUnknown("未发送回抽，但无法保存取消状态；已暂停打印。", error)
            }
            throw IllegalStateException("未发送回抽，未开始打印：${error.message ?: "准备检查失败"}", error)
        } finally {
            capture?.close()
        }
    }

    private fun ensureActive(op: Operation) {
        check(!op.timedOut && op.caller?.isActive != false) { "回抽检查超时或已取消" }
        check((app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) { "屏幕未处于唤醒状态，未允许继续打印" }
        val process = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(process)
        check(process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) { "应用已离开前台，未允许继续打印" }
    }

    private fun transmit(sdk: Any, op: Operation, request: ByteArray, movement: Boolean): NativeLogAudit.Transaction {
        PaperProtocol.requireWhitelistedRequest(request)
        check(request[4].toInt() != 0x33 || movement) { "移动请求路由错误" }
        val response = newByteArray()
        val start = System.currentTimeMillis()
        if (movement) {
            ensureActive(op)
            check(preferences.getBoolean("pending", false) && preferences.getString("operation", "") == op.id) { "回抽持久记录缺失" }
            op.movementSent = true // Commit point immediately before the sole movement IPC.
        }
        val result = call(sdk, "transmitRawCmd", arrayOf(Boolean::class.javaPrimitiveType!!, ByteArray::class.java, byteArrayType()), arrayOf(true, request, response)) as Int
        val end = System.currentTimeMillis()
        check(result == 0) { "设备原始传输未确认成功（$result）" }
        return NativeLogAudit.Transaction(hex(request), hex(readByteArray(response)), start, end)
    }

    private fun requireQuiescent() {
        val type = Class.forName("com.pos.sdk.printer.PosPrinter")
        val service = invoke(type.getDeclaredMethod("getService").apply { isAccessible = true }, null)
            ?: error("打印服务不可用")
        val binder = (service as IInterface).asBinder()
        check(binder.isBinderAlive && binder.pingBinder()) { "打印服务连接未通过检查" }
        val api = Class.forName("com.pos.sdk.printer.IPosPrinterService")
        check(invoke(api.getMethod("getNumberOfPrinters"), service) as Int == 1) { "打印机数量不匹配" }
        val state = invoke(api.getMethod("getPrintStateInfo", Int::class.javaPrimitiveType), service, 0)
        // The prepared manager normally supplies the idle client. Null is only the previously
        // reviewed controlled no-client case, never proof of physical inactivity.
        check(state == null || state.javaClass.getField("mState").getInt(state) == 1) { "打印客户端正在工作" }
        check(binder.isBinderAlive && binder.pingBinder()) { "打印服务连接已失效" }
    }

    private fun sdk(): Any = invoke(Class.forName("com.pos.sdk.accessory.POIGeneralAPI").getMethod("getDefault"), null)
        ?: error("POS 控制服务不可用")
    private fun byteArrayType() = Class.forName("com.pos.sdk.utils.PosByteArray")
    private fun newByteArray(): Any = byteArrayType().getConstructor().newInstance()
    private fun readByteArray(value: Any): ByteArray {
        val bytes = byteArrayType().getField("buffer").get(value) as? ByteArray ?: error("设备返回空数据")
        check(byteArrayType().getField("len").getInt(value) == bytes.size) { "设备数据长度不一致" }
        return bytes
    }
    private fun call(target: Any, name: String, types: Array<Class<*>> = emptyArray(), args: Array<Any> = emptyArray()): Any? =
        invoke(Class.forName("com.pos.sdk.accessory.POIGeneralAPI").getMethod(name, *types), target, *args)
    private fun invoke(method: Method, target: Any?, vararg args: Any): Any? = try {
        method.invoke(target, *args)
    } catch (error: InvocationTargetException) { throw error.targetException }
    private fun reserve(): IntArray { if (sequence > 251) sequence = 0; return intArrayOf(++sequence, ++sequence, ++sequence) }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it.toInt() and 255) }
    private fun unhex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun saveAudit(op: Operation, proof: NativeLogAudit.Result?, outcome: String) {
        val json = JSONObject().put("operation", op.id).put("fixed_units", -40).put("outcome", outcome)
            .put("movement_sent", op.movementSent).put("time_ms", System.currentTimeMillis())
        val rows = JSONArray()
        proof?.let { result -> json.put("native_pid", result.nativePid); result.matchedPrinterRows.forEach(rows::put) }
        json.put("matched_printer_rows", rows)
        fun add(name: String, transaction: NativeLogAudit.Transaction?, verified: Boolean) {
            if (verified && transaction != null) json.put(name, JSONObject().put("tx_hex", transaction.txHex).put("rx_hex", transaction.rxHex)
                .put("tx_ms", transaction.txMs).put("rx_ms", transaction.rxMs))
        }
        add("pre", op.pre, op.preVerified); add("feed", op.feed, op.feedVerified); add("post", op.post, op.postVerified)
        val directory = File(app.filesDir, "paper-motion-audit")
        check(directory.isDirectory || directory.mkdirs()) { "无法创建纸张操作记录" }
        val file = AtomicFile(File(directory, "${op.id}.json"))
        val out = file.startWrite()
        try { out.write(json.toString().toByteArray(Charsets.UTF_8)); out.fd.sync(); file.finishWrite(out) }
        catch (failure: Throwable) { file.failWrite(out); throw failure }
    }

    private class Operation(val caller: Job?) {
        val id = UUID.randomUUID().toString()
        @Volatile var timedOut = false
        var movementSent = false
        var cleanupAllowed = false
        var unknownPost = false
        var preVerified = false
        var feedVerified = false
        var postVerified = false
        var pre: NativeLogAudit.Transaction? = null
        var feed: NativeLogAudit.Transaction? = null
        var post: NativeLogAudit.Transaction? = null
        var preProof: NativeLogAudit.Result? = null
    }
}
