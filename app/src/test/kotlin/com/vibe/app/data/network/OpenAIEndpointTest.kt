package com.vibe.app.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAIEndpointTest {

    @Test
    fun `complete chat completions endpoint is kept exactly`() {
        val endpoint = "https://gateway.example/custom/inference?mode=agent"

        assertEquals(endpoint, endpoint.toChatCompletionsEndpoint(isCompleteEndpoint = true))
    }

    @Test
    fun `legacy base URL still gets standard endpoint path`() {
        assertEquals(
            "https://gateway.example/api/v1/chat/completions",
            "https://gateway.example/api/".toChatCompletionsEndpoint(),
        )
    }
}
