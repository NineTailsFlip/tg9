package dev.tg9.tg

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tg holds a main-thread Handler, so it needs Robolectric's Looper even though nothing here
 *  touches TDLib — and a plain Application, since TG9Application starts the native client. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class TgUpdateFilterTest {
    @Test
    fun keepsWhatTg9Reads() {
        assertTrue(Tg.isUsed(TdApi.UpdateNewMessage()))
        assertTrue(Tg.isUsed(TdApi.UpdateChatPosition()))
        assertTrue(Tg.isUsed(TdApi.UpdateAuthorizationState()))
        assertTrue(Tg.isUsed(TdApi.UpdateNotificationGroup()))
        assertTrue(Tg.isUsed(TdApi.UpdateFile()))
    }

    @Test
    fun dropsTheRest() {
        assertFalse(Tg.isUsed(TdApi.UpdateOption()))
        assertFalse(Tg.isUsed(TdApi.UpdateSupergroup()))
        assertFalse(Tg.isUsed(TdApi.UpdateChatFolders()))
    }
}
