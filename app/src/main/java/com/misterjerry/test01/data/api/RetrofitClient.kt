package com.misterjerry.test01.data.api

import com.misterjerry.test01.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private const val BASE_URL = "https://api.openai.com/"

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val apiKey = BuildConfig.OPENAI_API_KEY
        // Safe logging for debug
        android.util.Log.d("RetrofitClient", "API Key present: ${apiKey.isNotBlank()}, Prefix: ${if (apiKey.length > 3) apiKey.take(3) else "TooShort"}")
        
        val requestBuilder = original.newBuilder()
            .header("Authorization", "Bearer $apiKey")
            .method(original.method, original.body)
        val request = requestBuilder.build()
        chain.proceed(request)
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .addInterceptor(authInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val instance: ChatGptService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ChatGptService::class.java)
    }
}
