package com.example.heart_rate_monitor_mobile

import com.example.heart_rate_monitor_mobile.data.webhook.WebhookRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Webhook URL 安全校验：心率数据外发仅允许 https */
class WebhookUrlValidationTest {

    @Test
    fun `https urls are allowed`() {
        assertTrue(WebhookRepository.isUrlAllowed("https://example.com/hook"))
        assertTrue(WebhookRepository.isUrlAllowed("HTTPS://EXAMPLE.COM/HOOK"))
    }

    @Test
    fun `http and other schemes are rejected`() {
        assertFalse(WebhookRepository.isUrlAllowed("http://example.com/hook"))
        assertFalse(WebhookRepository.isUrlAllowed("ftp://example.com"))
        assertFalse(WebhookRepository.isUrlAllowed("file:///etc/passwd"))
        assertFalse(WebhookRepository.isUrlAllowed(""))
        assertFalse(WebhookRepository.isUrlAllowed("example.com/hook"))
    }
}
