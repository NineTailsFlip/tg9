package dev.tg9.tg

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Test

class StorageTest {
    @Test
    fun theOptimizerIsOnWithTg9sLimits() {
        val options = Storage.options().associate { it.name to it.value }
        assertEquals(true, (options["use_storage_optimizer"] as TdApi.OptionValueBoolean).value)
        assertEquals(200L * 1024, (options["storage_max_files_size"] as TdApi.OptionValueInteger).value)
        assertEquals(3 * 24 * 3600L, (options["storage_max_time_from_last_access"] as TdApi.OptionValueInteger).value)
    }

    @Test
    fun clearingKeepsNothingButTdlibsDefaults() {
        val clear = Storage.clearAll()
        assertEquals(0L, clear.size)
        assertEquals(0, clear.ttl)
        assertEquals(0, clear.count)
        assertEquals(0, clear.immunityDelay)
        // Null: TDLib's default types, which leave avatars, stickers and thumbnails alone.
        assertEquals(null, clear.fileTypes)
    }

    @Test
    fun sizes() {
        assertEquals("0 KB", Storage.size(0))
        assertEquals("1 KB", Storage.size(1))
        assertEquals("1.5 MB", Storage.size(1536L * 1024))
        assertEquals("84 MB", Storage.size(84L * 1024 * 1024 + 5))
        assertEquals("84 MB in 312 files", Storage.label(TdApi.StorageStatisticsFast(84L * 1024 * 1024, 312, 0, 0, 0)))
        assertEquals("0 KB in 1 file", Storage.label(TdApi.StorageStatisticsFast(0, 1, 0, 0, 0)))
    }
}
