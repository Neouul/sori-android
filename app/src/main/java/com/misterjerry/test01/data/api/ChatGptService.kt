package com.misterjerry.test01.data.api

import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

interface ChatGptService {
    @POST("v1/chat/completions")
    suspend fun getChatCompletion(@Body request: ChatRequest): ChatResponse
}
