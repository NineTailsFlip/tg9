package dev.tg9.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthTextTest {
    @Test
    fun phoneNumbersBecomeInternational() {
        assertEquals("+79001234567", AuthText.normalizePhone("+7 900 123-45-67"))
        assertEquals("+79001234567", AuthText.normalizePhone("79001234567"))
        assertEquals("+41791234567", AuthText.normalizePhone("0041 79 123 45 67"))
        assertEquals("+4915112345678", AuthText.normalizePhone("(49) 151 1234 5678"))
    }
}
