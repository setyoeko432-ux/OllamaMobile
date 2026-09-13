package com.ibra.ollamamobile

import android.app.Application
import android.content.SharedPreferences
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val app = mockk<Application>(relaxed = true)
    private val prefs = mockk<SharedPreferences>(relaxed = true)
    private val client = mockk<GatewayClient>()
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { app.getSharedPreferences(any(), any()) } returns prefs
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `testConnection success updates status`() = runTest {
        val vm = MainViewModel(app)
        vm.setClient(client)
        val settings = AppSettings(gatewayUrl = "http://test")
        
        coEvery { client.models(any()) } returns listOf("model1")
        
        vm.testConnection(settings)
        
        assertEquals("Gateway terhubung", vm.status.value)
        assertEquals(listOf("model1"), vm.models.value)
    }

    @Test
    fun `testConnection failure updates status with error`() = runTest {
        val vm = MainViewModel(app)
        vm.setClient(client)
        val settings = AppSettings(gatewayUrl = "http://test")
        
        coEvery { client.models(any()) } throws IOException("401 Unauthorized")
        
        vm.testConnection(settings)
        
        assertEquals("Token tidak valid", vm.status.value)
    }

    @Test
    fun `telemetry calculation TTFT`() {
        val tel = PerformanceTelemetry(
            requestStartedAt = 1000L,
            firstTokenAt = 1500L,
            completedAt = 2000L,
            tokenCount = 10
        )
        assertEquals(500L, tel.timeToFirstTokenMs)
        // Kecepatan: 10 token dalam 500ms -> 20 token/s
        assertEquals(20.0, tel.tokensPerSecond, 0.1)
    }
}
