package cl.guardiania.agent.data

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

object ApiFactory {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun normalizeBaseUrl(raw: String): String {
        val candidate = raw.trim().trimEnd('/') + "/"
        val url = candidate.toHttpUrlOrNull()
        require(url != null && (url.scheme == "http" || url.scheme == "https")) {
            "La dirección debe empezar con http:// o https://"
        }
        return candidate
    }

    fun create(baseUrl: String): GuardianApi {
        return Retrofit.Builder()
            .baseUrl(normalizeBaseUrl(baseUrl))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GuardianApi::class.java)
    }
}
