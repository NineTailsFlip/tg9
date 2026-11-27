package dev.tg9.tg

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.tg9.BuildConfig
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

/** The update types a [Tg.Listener] reads. Top-level rather than in [Tg], so declaring a set
 *  (TgStore.UPDATES, say) never starts [Tg]'s own initialization half-way through another's. */
fun updates(vararg types: KClass<out TdApi.Update>): Set<Class<out TdApi.Update>> =
    types.mapTo(HashSet()) { it.java }

/** The one TDLib client for the whole process, and the only place that talks to it.
 *
 *  TDLib calls back on its own thread; everything here is handed to the main thread before
 *  anything else sees it, so [TgStore], the screens and [listeners] are all single-threaded and
 *  need no locking — the same "one owner thread" rule the Nine apps follow for their providers.
 *
 *  Updates cross over in batches, not one Handler message each: TDLib sends thousands of them
 *  right after start, and the main thread is also the one reading the keypad. Updates nothing
 *  reads right now are dropped on TDLib's thread ([isUsed]), the rest are queued, and the main
 *  thread drains the queue in slices of at most [DRAIN_BUDGET_MS], yielding between slices so key
 *  presses and frames are never stuck behind a whole sync.
 *
 *  Every listener says which update types it reads ([addListener]) and gets only those, after
 *  [TgStore] has already applied them, so a listener reading the store always sees the state the
 *  update produced. What's read is [TgStore.UPDATES] plus the types of the listeners subscribed
 *  at the moment: a chat's new messages cross over only while that chat is open. Declaring the
 *  types next to the `when` that reads them, rather than in one list here, is what keeps a new
 *  listener from silently getting nothing. */
object Tg {
    private const val TAG = "TG9"
    private const val DRAIN_BUDGET_MS = 8L

    private val main = Handler(Looper.getMainLooper())
    private lateinit var appContext: Context
    private var client: Client? = null

    var authState: TdApi.AuthorizationState? = null
        private set

    var connectionState: TdApi.ConnectionState? = null
        private set

    /** The signed-in user's id once [TdApi.AuthorizationStateReady] has been reached. */
    var myUserId: Long = 0L
        private set

    val isReady: Boolean get() = authState is TdApi.AuthorizationStateReady

    /** True when signing in needs the user: a question TDLib is waiting on, or no API
     *  credentials at all. Not true while TDLib is still starting up, so a cold start doesn't
     *  flash the login screen on its way to the chat list. */
    val needsLogin: Boolean get() = !hasApiCredentials || when (authState) {
        is TdApi.AuthorizationStateWaitPhoneNumber,
        is TdApi.AuthorizationStateWaitCode,
        is TdApi.AuthorizationStateWaitPassword,
        is TdApi.AuthorizationStateWaitEmailAddress,
        is TdApi.AuthorizationStateWaitEmailCode,
        is TdApi.AuthorizationStateWaitRegistration,
        is TdApi.AuthorizationStateWaitOtherDeviceConfirmation,
        -> true
        else -> false
    }

    /** True when the build has no API credentials — the login screen says so instead of
     *  sending an always-failing request. */
    val hasApiCredentials: Boolean get() = BuildConfig.TG_API_ID != 0 && BuildConfig.TG_API_HASH.isNotBlank()

    fun interface Listener {
        fun onUpdate(update: TdApi.Update)
    }

    /** What [Tg] itself reads, besides [TgStore]. */
    private val OWN = updates(TdApi.UpdateAuthorizationState::class, TdApi.UpdateConnectionState::class)

    private class Subscription(val types: Set<Class<out TdApi.Update>>, val listener: Listener)

    private val listeners = LinkedHashMap<Listener, Subscription>()

    /** A copy of [listeners] taken on every add/remove, so dispatch neither allocates per update
     *  nor breaks when a listener removes itself (a screen finishing) from inside its callback. */
    private var listenerSnapshot: Array<Subscription> = emptyArray()

    /** Every type someone reads: [TgStore.UPDATES], [OWN] and the listeners' types. Rebuilt on the
     *  main thread on every add/remove, read on TDLib's thread by [isUsed]. */
    @Volatile
    private var wanted: Set<Class<out TdApi.Update>> = TgStore.UPDATES + OWN

    /** Updates and request results, in the order TDLib produced them: a result must not overtake
     *  an update TDLib sent before it (sendMessage's result before its updateNewMessage, say). */
    private val pending = ConcurrentLinkedQueue<Any>()
    private val drainScheduled = AtomicBoolean(false)
    private val drain = Runnable { drainPending() }

    /** Process-wide listeners that live as long as the client (notifications), as opposed to a
     *  screen's [addListener] that comes and goes with the screen. */
    private val globalListeners = mutableListOf<Subscription>()

    /** Whether a TG9 screen is in front ([setOnline]). */
    private var online = false

    fun init(context: Context) {
        if (client != null) return
        appContext = context.applicationContext
        try {
            Client.execute(TdApi.SetLogVerbosityLevel(1))
        } catch (e: Client.ExecutionException) {
            Log.w(TAG, "setLogVerbosityLevel", e)
        }
        client = Client.create(
            { obj -> enqueue(obj) },
            { e -> Log.e(TAG, "update handler", e) },
            { e -> Log.e(TAG, "default handler", e) },
        )
    }

    /** [listener] gets the updates of [types] until [removeListener]. Main thread. */
    fun addListener(types: Set<Class<out TdApi.Update>>, listener: Listener) {
        if (listener in listeners) return
        listeners[listener] = Subscription(types, listener)
        subscriptionsChanged()
    }

    fun removeListener(listener: Listener) {
        if (listeners.remove(listener) != null) subscriptionsChanged()
    }

    fun addGlobalListener(types: Set<Class<out TdApi.Update>>, listener: Listener) {
        globalListeners += Subscription(types, listener)
        subscriptionsChanged()
    }

    private fun subscriptionsChanged() {
        listenerSnapshot = listeners.values.toTypedArray()
        val all = HashSet(TgStore.UPDATES)
        all += OWN
        globalListeners.forEach { all += it.types }
        listenerSnapshot.forEach { all += it.types }
        wanted = all
    }

    /** Telegram's "online" status: on while a TG9 screen is in front, off otherwise (the
     *  background connection doesn't count). Contacts see it as "online", and TDLib uses it to
     *  decide what's worth a notification. Set by [dev.tg9.TG9Application]. */
    fun setOnline(value: Boolean) {
        if (online == value) return
        online = value
        if (isReady) sendOnline()
    }

    private fun sendOnline() = send(TdApi.SetOption("online", TdApi.OptionValueBoolean(online)))

    /** Runs [action] once TDLib is signed in and ready for requests — at once if it already is,
     *  else on [TdApi.AuthorizationStateReady] — with false instead if that hasn't happened within
     *  [timeoutMs] or sign-in needs the user. For work that can start with a cold process, like a
     *  notification's action, before TDLib has opened its database. Main thread. */
    fun whenReady(timeoutMs: Long, action: (ready: Boolean) -> Unit) {
        if (isReady) return action(true)
        if (needsLogin) return action(false)
        var done = false
        lateinit var listener: Listener
        lateinit var timeout: Runnable
        fun finish(ready: Boolean) {
            if (done) return
            done = true
            removeListener(listener)
            main.removeCallbacks(timeout)
            action(ready)
        }
        timeout = Runnable { finish(false) }
        listener = Listener { update ->
            if (update is TdApi.UpdateAuthorizationState) {
                when {
                    isReady -> finish(true)
                    needsLogin -> finish(false)
                }
            }
        }
        addListener(OWN, listener)
        main.postDelayed(timeout, timeoutMs)
    }

    /** Sends [function]; [onResult] runs on the main thread with either the result or a
     *  [TdApi.Error]. */
    fun send(function: TdApi.Function<*>, onResult: (TdApi.Object) -> Unit = {}) {
        val c = client ?: return
        c.send(function) { result -> enqueueTask { onResult(result) } }
    }

    /** Like [send], but only calls [onOk] for a result of type [T] and [onError] otherwise. */
    inline fun <reified T : TdApi.Object> request(
        function: TdApi.Function<*>,
        crossinline onError: (TdApi.Error) -> Unit = {},
        crossinline onOk: (T) -> Unit,
    ) {
        send(function) { result ->
            when (result) {
                is T -> onOk(result)
                is TdApi.Error -> onError(result)
                else -> onError(TdApi.Error(500, "Unexpected ${result.javaClass.simpleName}"))
            }
        }
    }

    /** TDLib's thread. */
    private fun enqueue(obj: TdApi.Object) {
        val update = obj as? TdApi.Update ?: return
        if (!isUsed(update)) return
        pending.add(update)
        scheduleDrain()
    }

    /** TDLib's thread: a request's result callback, queued behind the updates before it. */
    private fun enqueueTask(task: Runnable) {
        pending.add(task)
        scheduleDrain()
    }

    private fun scheduleDrain() {
        if (drainScheduled.compareAndSet(false, true)) main.post(drain)
    }

    /** Main thread: applies queued updates until the queue is empty or the slice's budget is
     *  spent, then yields and continues in a fresh message. */
    private fun drainPending() {
        drainScheduled.set(false)
        val deadline = SystemClock.uptimeMillis() + DRAIN_BUDGET_MS
        while (true) {
            when (val item = pending.poll() ?: return) {
                is TdApi.Update -> dispatch(item)
                is Runnable -> item.run()
            }
            if (SystemClock.uptimeMillis() >= deadline) break
        }
        if (pending.isNotEmpty()) scheduleDrain()
    }

    /** Main thread; internal for tests. */
    internal fun dispatch(update: TdApi.Update) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> onAuthorizationState(update.authorizationState)
            is TdApi.UpdateConnectionState -> connectionState = update.state
            else -> {}
        }
        TgStore.apply(update)
        val type = update.javaClass
        for (s in globalListeners) if (type in s.types) s.listener.onUpdate(update)
        for (s in listenerSnapshot) if (type in s.types) s.listener.onUpdate(update)
    }

    /** Whether anything reads [update] right now (see the class comment). Anything else (options,
     *  stickers, folders, supergroup info, reactions, a closed chat's messages, ...) is dropped
     *  before it costs the main thread anything. Any thread. */
    internal fun isUsed(update: TdApi.Update): Boolean = update.javaClass in wanted

    private fun onAuthorizationState(state: TdApi.AuthorizationState) {
        authState = state
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> sendParameters()
            is TdApi.AuthorizationStateReady -> onReady()
            is TdApi.AuthorizationStateClosed -> {
                // logOut and close both end here; TDLib wants a brand-new client afterwards.
                TgStore.clear()
                myUserId = 0L
                client = null
                init(appContext)
            }
            else -> {}
        }
    }

    private fun sendParameters() {
        val dir = File(appContext.filesDir, "tdlib")
        val params = TdApi.SetTdlibParameters().apply {
            useTestDc = false
            databaseDirectory = dir.absolutePath
            filesDirectory = File(dir, "files").absolutePath
            // The database lives in app-private storage; an empty key is TDLib's documented
            // "not encrypted beyond what the OS already provides" choice.
            databaseEncryptionKey = ByteArray(0)
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = false
            apiId = BuildConfig.TG_API_ID
            apiHash = BuildConfig.TG_API_HASH
            systemLanguageCode = Locale.getDefault().language.ifBlank { "en" }
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            systemVersion = "Android ${Build.VERSION.RELEASE}"
            applicationVersion = "TG9 ${BuildConfig.VERSION_NAME}"
        }
        send(params) { result ->
            if (result is TdApi.Error) Log.e(TAG, "setTdlibParameters: ${result.code} ${result.message}")
        }
    }

    private fun onReady() {
        // Turns on TDLib's notification API: without a group count above zero it never sends
        // updateNotificationGroup, and TG9 has no other source of "this deserves a notification"
        // that already respects each chat's mute settings.
        send(TdApi.SetOption("notification_group_count_max", TdApi.OptionValueInteger(10)))
        send(TdApi.SetOption("notification_group_size_max", TdApi.OptionValueInteger(6)))
        Storage.options().forEach { send(it) }
        sendOnline()
        request<TdApi.User>(TdApi.GetMe()) { me ->
            myUserId = me.id
            TgStore.apply(TdApi.UpdateUser(me))
        }
    }

    fun logOut() = send(TdApi.LogOut())
}
