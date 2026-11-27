package dev.tg9.tg

import dev.tg9.chat.ChatTimeline
import org.drinkless.tdlib.TdApi
import org.junit.After
import org.junit.Assert.assertEquals
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
    private val subscribed = mutableListOf<Tg.Listener>()

    private fun subscribe(types: Set<Class<out TdApi.Update>>, listener: Tg.Listener) {
        Tg.addListener(types, listener)
        subscribed += listener
    }

    @After
    fun unsubscribe() = subscribed.forEach(Tg::removeListener)

    @Test
    fun theStoreAndSignInAreAlwaysRead() {
        assertTrue(Tg.isUsed(TdApi.UpdateChatPosition()))
        assertTrue(Tg.isUsed(TdApi.UpdateFile()))
        assertTrue(Tg.isUsed(TdApi.UpdateUser()))
        assertTrue(Tg.isUsed(TdApi.UpdateAuthorizationState()))
        assertTrue(Tg.isUsed(TdApi.UpdateConnectionState()))
    }

    @Test
    fun whatNobodyReadsIsDropped() {
        assertFalse(Tg.isUsed(TdApi.UpdateOption()))
        assertFalse(Tg.isUsed(TdApi.UpdateSupergroup()))
        assertFalse(Tg.isUsed(TdApi.UpdateChatFolders()))
    }

    @Test
    fun aChatsMessagesAreReadOnlyWhileItIsOpen() {
        assertFalse(Tg.isUsed(TdApi.UpdateNewMessage()))
        val listener = Tg.Listener {}
        subscribe(ChatTimeline.UPDATES, listener)
        assertTrue(Tg.isUsed(TdApi.UpdateNewMessage()))
        assertTrue(Tg.isUsed(TdApi.UpdateDeleteMessages()))

        Tg.removeListener(listener)
        assertFalse(Tg.isUsed(TdApi.UpdateNewMessage()))
        assertFalse(Tg.isUsed(TdApi.UpdateDeleteMessages()))
    }

    @Test
    fun aListenerGetsOnlyTheTypesItAskedFor() {
        val got = mutableListOf<TdApi.Update>()
        subscribe(updates(TdApi.UpdateChatTitle::class)) { got += it }
        Tg.dispatch(TdApi.UpdateChatTitle(1, "a"))
        Tg.dispatch(TdApi.UpdateChatPosition())
        Tg.dispatch(TdApi.UpdateUserStatus())
        assertEquals(listOf(TdApi.UpdateChatTitle::class.java), got.map { it.javaClass })
    }

    @Test
    fun aListenerCanUnsubscribeFromInsideItsCallback() {
        var calls = 0
        lateinit var listener: Tg.Listener
        listener = Tg.Listener {
            calls++
            Tg.removeListener(listener)
        }
        subscribe(updates(TdApi.UpdateChatTitle::class), listener)
        Tg.dispatch(TdApi.UpdateChatTitle(1, "a"))
        Tg.dispatch(TdApi.UpdateChatTitle(1, "b"))
        assertEquals(1, calls)
    }
}
