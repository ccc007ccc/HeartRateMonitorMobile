package com.example.heart_rate_monitor_mobile

import com.example.heart_rate_monitor_mobile.domain.VersionComparator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {

    @Test
    fun `detects newer versions`() {
        assertTrue(VersionComparator.isNewer("v2.2", "2.1"))
        assertTrue(VersionComparator.isNewer("2.1.1", "2.1"))
        assertTrue(VersionComparator.isNewer("v3.0", "v2.9.9"))
        assertTrue(VersionComparator.isNewer("2.10", "2.9"))
    }

    @Test
    fun `same or older versions are not newer`() {
        assertFalse(VersionComparator.isNewer("v2.1", "2.1"))
        assertFalse(VersionComparator.isNewer("2.0", "2.1"))
        assertFalse(VersionComparator.isNewer("v1.8", "v2.2"))
    }

    @Test
    fun `prerelease ranks below release of same number`() {
        assertFalse(VersionComparator.isNewer("2.2-beta1", "2.2"))
        assertTrue(VersionComparator.isNewer("2.2", "2.2-beta1"))
    }

    @Test
    fun `malformed input never crashes`() {
        assertFalse(VersionComparator.isNewer("", "2.1"))
        assertFalse(VersionComparator.isNewer("abc", "2.1"))
    }
}
