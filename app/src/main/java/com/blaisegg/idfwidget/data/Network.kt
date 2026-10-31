package com.blaisegg.idfwidget.data

import com.blaisegg.idfwidget.BuildConfig
import com.blaisegg.idfwidget.data.api.PrimApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

object Network {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true          // Navitia renvoie parfois des nombres en chaîne (et inversement)
        coerceInputValues = true
        explicitNulls = false
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            level = HttpLoggingInterceptor.Level.BASIC
                            redactHeader("apikey")
                        },
                    )
                }
            }
            .build()
    }

    val primApi: PrimApi by lazy {
        Retrofit.Builder()
            .baseUrl(PrimApi.BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PrimApi::class.java)
    }
}
