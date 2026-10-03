package com.spoolpainter.app.data.remote.inventory

import com.google.gson.JsonObject
import retrofit2.Response
import retrofit2.http.*

/** Raw JSON intentionally retains server extensions and nullable fields. */
interface InventoryApi {
    @GET("api/v1/info") suspend fun info(): Response<JsonObject>
    @GET("api/v1/{entity}") suspend fun list(
        @Path("entity") entity: String,
        @Query("limit") limit: Int = 500,
        @Query("offset") offset: Int = 0,
        @Query("allow_archived") archived: Boolean? = null,
    ): Response<List<JsonObject>>
    @GET("api/v1/{entity}/{id}") suspend fun get(@Path("entity") entity: String, @Path("id") id: Int): Response<JsonObject>
    @POST("api/v1/{entity}") suspend fun create(@Path("entity") entity: String, @Body body: JsonObject): Response<JsonObject>
    @PATCH("api/v1/{entity}/{id}") suspend fun patch(@Path("entity") entity: String, @Path("id") id: Int, @Body body: JsonObject): Response<JsonObject>
    @DELETE("api/v1/{entity}/{id}") suspend fun delete(@Path("entity") entity: String, @Path("id") id: Int): Response<JsonObject>
    @PUT("api/v1/spool/{id}/measure") suspend fun measure(@Path("id") id: Int, @Body body: JsonObject): Response<JsonObject>
    @PUT("api/v1/spool/{id}/use") suspend fun use(@Path("id") id: Int, @Body body: JsonObject): Response<JsonObject>
    @GET("api/v1/field/{entity}") suspend fun fields(@Path("entity") entity: String): Response<List<JsonObject>>
}
