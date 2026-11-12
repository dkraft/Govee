package dev.offgrid.govee.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneUploadTest {
    @Test
    fun boundAddressIsNotUploaded() {
        assertFalse(PhoneUpload.shouldKeep("AA:BB:CC:DD:EE:FF", setOf("AA:BB:CC:DD:EE:FF")))
    }

    @Test
    fun unboundAddressIsUploaded() {
        assertTrue(PhoneUpload.shouldKeep("AA:BB:CC:DD:EE:FF", setOf("11:22:33:44:55:66")))
    }

    @Test
    fun emptyBoundSetUploadsEverySensor() {
        assertTrue(PhoneUpload.shouldKeep("AA:BB:CC:DD:EE:FF", emptySet()))
    }
}
