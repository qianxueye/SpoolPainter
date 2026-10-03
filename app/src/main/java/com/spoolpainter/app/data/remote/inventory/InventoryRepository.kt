package com.spoolpainter.app.data.remote.inventory

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.spoolpainter.app.data.local.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

class InventoryFailure(message: String, val unknownOutcome: Boolean = false) : IOException(message)

data class InventorySnapshot(val url: String, val version: String, val spools: List<JsonObject>, val filaments: List<JsonObject>, val vendors: List<JsonObject>)

@Singleton
class InventoryRepository @Inject constructor(private val settings: SettingsRepository, client: OkHttpClient) {
    private val http = client.newBuilder().retryOnConnectionFailure(false).build()
    private val mutations = Mutex()
    private val gson = GsonBuilder().serializeNulls().create()

    private suspend fun connection(): Pair<String, InventoryApi> {
        val url = settings.awaitSettings().url.trim().trimEnd('/')
        require(url.startsWith("http://") || url.startsWith("https://")) { "请先在设置中配置 Spoolman HTTP/HTTPS 地址" }
        val api = Retrofit.Builder().baseUrl("$url/").client(http)
            .addConverterFactory(GsonConverterFactory.create(gson)).build().create(InventoryApi::class.java)
        return url to api
    }
    private suspend fun <T> request(write: Boolean = false, block: suspend () -> Response<T>): T {
        val response = try { block() } catch (e: IOException) {
            throw InventoryFailure(if (write) "请求中断，保存结果未知。请刷新核对后再操作，切勿直接重复提交。${e.message.orEmpty()}" else "无法连接服务器：${e.message.orEmpty()}", write)
        } catch (e: JsonParseException) {
            throw InventoryFailure(if (write) "服务器响应无法解析，保存结果未知；请刷新核对。" else "服务器响应无法解析：${e.message.orEmpty()}", write)
        }
        if (!response.isSuccessful) {
            // A server/gateway failure cannot prove a dispatched mutation rolled back.
            val unknown = write && response.code() >= 500
            val detail = runCatching { response.errorBody()?.string().orEmpty().take(1600) }
                .getOrElse { "错误详情读取失败：${it.message.orEmpty()}" }
            val guidance = if (unknown) "保存结果未知，请刷新核对后再操作，切勿直接重复提交。" else ""
            throw InventoryFailure("HTTP ${response.code()}：$guidance$detail", unknown)
        }
        return response.body() ?: throw InventoryFailure("服务器返回空响应", write)
    }
    private suspend fun all(api: InventoryApi, entity: String): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        var offset = 0
        while (true) {
            val page = request { api.list(entity, 500, offset, if (entity == "spool") true else null) }
            result.addAll(page)
            if (page.size < 500) return result.distinctBy { it.recordId() }
            offset += page.size
            check(offset < 100_000) { "库存超过本机安全分页上限，请在服务器管理界面操作" }
        }
    }
    suspend fun snapshot(): InventorySnapshot {
        val (url, api) = connection()
        val info = request { api.info() }
        return InventorySnapshot(url, info.text("version"), all(api, "spool"), all(api, "filament"), all(api, "vendor"))
    }
    suspend fun get(entity: String, id: Int, expectedUrl: String? = null): JsonObject =
        (expectedUrl?.let { sameServer(it) } ?: connection().second).let { request { it.get(entity, id) } }
    suspend fun fields(entity: String, expectedUrl: String? = null): List<JsonObject> =
        (expectedUrl?.let { sameServer(it) } ?: connection().second).let { request { it.fields(entity) } }
    suspend fun verifyServer(expectedUrl: String) {
        check(settings.awaitSettings().url.trim().trimEnd('/') == expectedUrl.trimEnd('/')) {
            "服务器地址已改变，请刷新库存后重新操作"
        }
    }
    private suspend fun sameServer(expectedUrl: String): InventoryApi {
        val (url, api) = connection()
        check(url == expectedUrl.trimEnd('/')) { "服务器地址已改变，请刷新库存后重新操作" }
        return api
    }
    suspend fun save(entity: String, baseline: JsonObject?, requested: JsonObject, expectedUrl: String): JsonObject = mutations.withLock {
        val api = sameServer(expectedUrl)
        if (baseline == null) request(true) { api.create(entity, requested) }
        else {
            val id = baseline.recordId()
            val fresh = request { api.get(entity, id) }
            val patch = InventoryPatch.fromFresh(baseline, fresh, requested)
            if (patch.size() == 0) fresh else request(true) { api.patch(entity, id, patch) }
        }
    }
    suspend fun delete(entity: String, baseline: JsonObject, expectedUrl: String) = mutations.withLock {
        val api = sameServer(expectedUrl)
        val fresh = request { api.get(entity, baseline.recordId()) }
        check(fresh == baseline) { "记录已发生变化；请刷新并重新确认删除" }
        request(true) { api.delete(entity, baseline.recordId()) }
        Unit
    }
    suspend fun weight(baseline: JsonObject, grams: Double, measure: Boolean, expectedUrl: String): JsonObject = mutations.withLock {
        require(grams.isFinite() && grams >= 0) { "重量必须是非负有限数值（克）" }
        val api = sameServer(expectedUrl)
        val id = baseline.recordId()
        val fresh = request { api.get("spool", id) }
        listOf("remaining_weight", "used_weight", "spool_weight", "filament_id").forEach {
            check(InventoryPatch.value(fresh, it) == InventoryPatch.value(baseline, it)) { "重量或耗材已变化，请刷新后重新称重" }
        }
        if (measure) {
            val tare = fresh.number("spool_weight")
            require(tare != null) { "请先在编辑中明确设置此卷的空盘重量，再提交毛重称量" }
            require(grams >= tare) { "毛重不能小于空盘重量 ${tare}g" }
            request(true) { api.measure(id, JsonObject().apply { addProperty("weight", grams) }) }
        } else {
            require(grams > 0) { "消耗重量必须大于零" }
            request(true) { api.use(id, JsonObject().apply { addProperty("use_weight", grams) }) }
        }
    }
    suspend fun lookup(uid: String, expectedUrl: String? = null): List<JsonObject> {
        val api = expectedUrl?.let { sameServer(it) } ?: connection().second
        val all = all(api, "spool")
        val ids = InventoryUids.index(all)[InventoryUids.normalize(uid)].orEmpty()
        return all.filter { it.recordId() in ids }
    }
    suspend fun bind(baseline: JsonObject, input: String, remove: Boolean, expectedUrl: String): JsonObject = mutations.withLock {
        val uid = InventoryUids.normalize(input)
        val api = sameServer(expectedUrl)
        val id = baseline.recordId()
        val records = all(api, "spool")
        val index = InventoryUids.index(records)
        val owners = index[uid].orEmpty()
        check(owners.size <= 1 && (remove || owners.all { it == id })) { "UID 已关联到库存 ${owners.sorted().joinToString { "#$it" }}；为避免错绑，本机拒绝修改" }
        val fresh = request { api.get("spool", id) }
        check(InventoryPatch.value(fresh.child("extra"), "card_uids") == InventoryPatch.value(baseline.child("extra"), "card_uids")) { "此卷 UID 已发生变化，请刷新后重试" }
        val current = InventoryUids.decode(fresh)
        val next = if (remove) current.filterNot { it == uid } else (current + uid).distinct()
        if (next == current) return@withLock fresh
        val result = request(true) { api.patch("spool", id, JsonObject().apply { add("extra", JsonObject().apply { addProperty("card_uids", InventoryUids.encoded(next)) }) }) }
        val after = try { InventoryUids.index(all(api, "spool"))[uid].orEmpty() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { throw InventoryFailure("UID 已提交但复查失败，请刷新核对：${e.message}", true) }
        if (!(if (remove) id !in after else after == setOf(id))) {
            throw InventoryFailure("保存后的复查发现 UID 并发冲突，请刷新并人工检查；本机不会自动改动其他卷", true)
        }
        result
    }
}
