package com.example.heart_rate_monitor_mobile

import com.example.heart_rate_monitor_mobile.data.webhook.WebhookRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Webhook URL 协议校验：http 与 https 均允许（生态大量依赖明文 http 目标，
 * 如 VRChat OSC、sleepy-project、局域网 PC），仅拦截其他协议。
 */
class WebhookUrlValidationTest {

    @Test
    fun `http and https urls are allowed`() {
        assertTrue(WebhookRepository.isUrlAllowed("https://example.com/hook"))
        assertTrue(WebhookRepository.isUrlAllowed("http://127.0.0.1:9000/avatar/parameters/Heartrate"))
        assertTrue(WebhookRepository.isUrlAllowed("HTTP://192.168.1.10/device/set"))
        assertTrue(WebhookRepository.isUrlAllowed("HTTPS://EXAMPLE.COM/HOOK"))
    }

    @Test
    fun `other schemes are rejected`() {
        assertFalse(WebhookRepository.isUrlAllowed("ftp://example.com"))
        assertFalse(WebhookRepository.isUrlAllowed("file:///etc/passwd"))
        assertFalse(WebhookRepository.isUrlAllowed(""))
        assertFalse(WebhookRepository.isUrlAllowed("example.com/hook"))
    }
}
