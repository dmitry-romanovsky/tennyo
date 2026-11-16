package app.tennyo.mobile.link.pairing

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceDisplayNameTest {
    @Test
    fun joinsManufacturerAndModel() {
        assertEquals("Samsung SM-G991B", androidDeviceDisplayName("samsung", "SM-G991B"))
    }

    @Test
    fun capitalizesLowercaseModelWords() {
        assertEquals("Motorola Moto G Power", androidDeviceDisplayName("motorola", "moto g power"))
    }

    @Test
    fun keepsModelThatHasCapitals() {
        assertEquals("Google Pixel 8a", androidDeviceDisplayName("google", "Pixel 8a"))
    }

    @Test
    fun doesNotRepeatManufacturerLeadingTheModel() {
        assertEquals("Motorola Edge", androidDeviceDisplayName("motorola", "Motorola Edge"))
    }

    @Test
    fun appendsAndroidVersion() {
        assertEquals("Google Pixel 8 Android 15", androidDeviceDisplayName("google", "Pixel 8", "15"))
    }

    @Test
    fun collapsesWhitespace() {
        assertEquals("Google Pixel 8", androidDeviceDisplayName("  google ", "Pixel \t 8"))
    }

    @Test
    fun usesWhicheverPartIsPresent() {
        assertEquals("Pixel 8", androidDeviceDisplayName(null, "Pixel 8"))
        assertEquals("Motorola", androidDeviceDisplayName("motorola", " "))
    }

    @Test
    fun fallsBackWhenBothPartsAreBlank() {
        assertEquals("Android device", androidDeviceDisplayName("", " "))
        assertEquals("Android device Android 16", androidDeviceDisplayName("", " ", "16"))
    }
}
