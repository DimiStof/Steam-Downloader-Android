package com.example.steamfetch

import android.content.Context
import android.util.Log
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.authentication.IAuthenticator
import `in`.dragonbra.javasteam.steam.authentication.IChallengeUrlChanged
import `in`.dragonbra.javasteam.steam.handlers.steamapps.License
import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.steam.handlers.steamapps.callback.LicenseListCallback
import `in`.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSProductInfoCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOffCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

enum class CodeKind { DEVICE, EMAIL }

sealed interface UiState {
    data object LoggedOut : UiState
    data class Busy(val message: String) : UiState
    data object WaitingApproval : UiState
    data class NeedCode(val kind: CodeKind, val retry: Boolean, val email: String?) : UiState
    data class Qr(val url: String) : UiState
    data class Failed(val message: String) : UiState
    data object LoggedIn : UiState
}

data class Game(val appId: Int, val name: String, val familyShared: Boolean)

/**
 * Owns the single Steam connection: login (password + Steam Guard, or QR), saved sessions
 * (several accounts), and the library, which includes games shared through Steam Family
 * Sharing (those licenses are owned by another account in your family group).
 */
object SteamManager {
    private const val TAG = "SteamManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var tokens: TokenStore
        private set
    lateinit var steamClient: SteamClient
        private set
    private lateinit var callbacks: CallbackManager
    private lateinit var steamUser: SteamUser
    private lateinit var steamApps: SteamApps
    private var initialized = false
    @Volatile private var pumping = false

    private val _ui = MutableStateFlow<UiState>(UiState.LoggedOut)
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private val _games = MutableStateFlow<List<Game>>(emptyList())
    val games: StateFlow<List<Game>> = _games.asStateFlow()
    private val _libraryLoading = MutableStateFlow(false)
    val libraryLoading: StateFlow<Boolean> = _libraryLoading.asStateFlow()
    private val _account = MutableStateFlow<String?>(null)
    val account: StateFlow<String?> = _account.asStateFlow()

    /** Licenses of the signed-in account, including Family Sharing ones. Passed to the downloader. */
    @Volatile var licenses: List<License> = emptyList()
        private set

    @Volatile private var myAccountId: Long = 0L
    @Volatile private var manualLogout = false
    @Volatile private var preferCode = false
    private var loginJob: Job? = null
    private var codeFuture: CompletableFuture<String>? = null
    @Volatile private var connectedSignal = CompletableDeferred<Unit>()

    private val licenseByPackage = ConcurrentHashMap<Int, License>()
    private val ownedApps: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val requestedApps: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val gameMap = ConcurrentHashMap<Int, Game>()

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        tokens = TokenStore(context.applicationContext)
        steamClient = SteamClient()
        callbacks = CallbackManager(steamClient)
        steamUser = steamClient.getHandler(SteamUser::class.java)!!
        steamApps = steamClient.getHandler(SteamApps::class.java)!!

        callbacks.subscribe(ConnectedCallback::class.java) { onConnected() }
        callbacks.subscribe(DisconnectedCallback::class.java) { onDisconnected(it) }
        callbacks.subscribe(LoggedOnCallback::class.java) { onLoggedOn(it) }
        callbacks.subscribe(LoggedOffCallback::class.java) { onLoggedOff(it) }
        callbacks.subscribe(LicenseListCallback::class.java) { onLicenseList(it) }
        callbacks.subscribe(PICSProductInfoCallback::class.java) { onProductInfo(it) }

        pumping = true
        Thread({ while (pumping) callbacks.runWaitCallbacks(1000L) }, "steam-callbacks").apply {
            isDaemon = true
            start()
        }
    }

    // ---------------------------------------------------------------- login

    fun loginWithPassword(user: String, pass: String, preferCodeEntry: Boolean) {
        preferCode = preferCodeEntry
        startLogin("Connecting to Steam…") {
            val details = AuthSessionDetails().apply {
                username = user
                password = pass
                persistentSession = true
                authenticator = promptAuthenticator
            }
            val session = steamClient.authentication.beginAuthSessionViaCredentials(details).await()
            val result = session.pollingWaitForResultAsync().await()
            completeLogin(result.accountName, result.refreshToken)
        }
    }

    fun loginWithQr() {
        startLogin("Connecting to Steam…") {
            val session = steamClient.authentication.beginAuthSessionViaQR(AuthSessionDetails()).await()
            _ui.value = UiState.Qr(session.challengeUrl)
            // The QR challenge rotates every ~30 seconds; keep the displayed code current.
            session.challengeUrlChanged = IChallengeUrlChanged { _ ->
                _ui.value = UiState.Qr(session.challengeUrl)
            }
            val result = session.pollingWaitForResultAsync().await()
            completeLogin(result.accountName, result.refreshToken)
        }
    }

    fun loginSaved(account: String) {
        val token = tokens.get(account)
        if (token == null) {
            fail("No saved session for $account. Sign in again.")
            return
        }
        startLogin("Signing in as $account…") {
            _account.value = account
            logOn(account, token)
        }
    }

    fun submitCode(code: String) {
        _ui.value = UiState.Busy("Verifying code…")
        codeFuture?.complete(code.trim())
    }

    fun cancelLogin() {
        loginJob?.cancel()
        codeFuture?.cancel(true)
        manualLogout = true
        runCatching { steamClient.disconnect() }
        _ui.value = UiState.LoggedOut
    }

    fun logout(forget: Boolean) {
        manualLogout = true
        val acct = _account.value
        if (forget && acct != null) tokens.remove(acct)
        runCatching { steamUser.logOff() }
        runCatching { steamClient.disconnect() }
        licenses = emptyList()
        gameMap.clear()
        _games.value = emptyList()
        _account.value = null
        _ui.value = UiState.LoggedOut
    }

    fun forgetAccount(account: String) = tokens.remove(account)

    private fun startLogin(message: String, block: suspend () -> Unit) {
        loginJob?.cancel()
        manualLogout = false
        _ui.value = UiState.Busy(message)
        loginJob = scope.launch {
            try {
                ensureConnected()
                block()
            } catch (e: TimeoutCancellationException) {
                fail("Timed out connecting to Steam. Check your connection and try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "login failed", e)
                fail(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun completeLogin(accountName: String, refreshToken: String) {
        tokens.save(accountName, refreshToken)
        _account.value = accountName
        _ui.value = UiState.Busy("Signing in…")
        logOn(accountName, refreshToken)
    }

    private fun logOn(accountName: String, refreshToken: String) {
        steamUser.logOn(
            LogOnDetails().apply {
                username = accountName
                accessToken = refreshToken
                shouldRememberPassword = true
            },
        )
    }

    private suspend fun ensureConnected() {
        if (steamClient.isConnected) return
        connectedSignal = CompletableDeferred()
        steamClient.connect()
        withTimeout(30_000) { connectedSignal.await() }
    }

    private fun fail(message: String) {
        _ui.value = UiState.Failed(message)
    }

    private fun askCode(kind: CodeKind, retry: Boolean, email: String?): CompletableFuture<String> {
        val f = CompletableFuture<String>()
        codeFuture = f
        _ui.value = UiState.NeedCode(kind, retry, email)
        return f
    }

    private val promptAuthenticator = object : IAuthenticator {
        override fun acceptDeviceConfirmation(): CompletableFuture<Boolean> {
            // true = wait for approval in the Steam mobile app; false = ask for a code instead.
            if (!preferCode) _ui.value = UiState.WaitingApproval
            return CompletableFuture.completedFuture(!preferCode)
        }

        override fun getDeviceCode(previousCodeWasIncorrect: Boolean): CompletableFuture<String> =
            askCode(CodeKind.DEVICE, previousCodeWasIncorrect, null)

        override fun getEmailCode(email: String?, previousCodeWasIncorrect: Boolean): CompletableFuture<String> =
            askCode(CodeKind.EMAIL, previousCodeWasIncorrect, email)
    }

    // ---------------------------------------------------------------- callbacks

    private fun onConnected() {
        connectedSignal.complete(Unit)
    }

    private fun onDisconnected(cb: DisconnectedCallback) {
        if (cb.isUserInitiated || manualLogout) return
        val acct = _account.value
        val state = _ui.value
        if (state is UiState.LoggedIn && acct != null) {
            // Connection dropped while signed in: reconnect with the saved session.
            scope.launch {
                delay(3000)
                runCatching {
                    ensureConnected()
                    tokens.get(acct)?.let { logOn(acct, it) }
                }
            }
        } else if (state is UiState.Busy) {
            fail("Disconnected from Steam.")
        }
    }

    private fun onLoggedOn(cb: LoggedOnCallback) {
        if (cb.result == EResult.OK) {
            myAccountId = steamClient.steamID?.accountID?.toLong() ?: 0L
            _libraryLoading.value = true
            _ui.value = UiState.LoggedIn
        } else {
            val r = cb.result
            if (r == EResult.InvalidPassword || r == EResult.AccessDenied || r == EResult.Expired) {
                _account.value?.let { tokens.remove(it) }
                fail("Saved session expired. Please sign in again.")
            } else {
                fail("Steam sign-in failed: $r")
            }
        }
    }

    private fun onLoggedOff(cb: LoggedOffCallback) {
        if (manualLogout) return
        _games.value = emptyList()
        fail("Signed out by Steam (${cb.result}). Another device may have signed in.")
    }

    // ---------------------------------------------------------------- library (incl. Family Sharing)

    private fun onLicenseList(cb: LicenseListCallback) {
        if (cb.result != EResult.OK) return
        licenses = cb.licenseList
        licenseByPackage.clear()
        ownedApps.clear()
        requestedApps.clear()
        gameMap.clear()
        _games.value = emptyList()
        _libraryLoading.value = true

        cb.licenseList.forEach { licenseByPackage[it.packageID] = it }
        val requests = cb.licenseList.map { PICSRequest(it.packageID, it.accessToken) }
        requests.chunked(500).forEach { chunk ->
            steamApps.picsGetProductInfo(emptyList<PICSRequest>(), chunk)
        }
        scope.launch {
            delay(25_000)
            _libraryLoading.value = false
        }
    }

    private fun onProductInfo(cb: PICSProductInfoCallback) {
        val me = myAccountId
        val newApps = mutableListOf<Int>()

        // Packages: figure out which apps are in each license, and who owns the license.
        for ((pkgId, info) in cb.packages) {
            val lic = licenseByPackage[pkgId]
            val mine = lic != null && (lic.ownerAccountID.toLong() and 0xFFFFFFFFL) == me
            for (child in info.keyValues.get("appids").children) {
                val appId = child.asInteger(0)
                if (appId == 0) continue
                if (mine) ownedApps.add(appId)
                if (requestedApps.add(appId)) newApps.add(appId)
            }
        }
        newApps.chunked(250).forEach { chunk ->
            steamApps.picsGetProductInfo(chunk.map { PICSRequest(it) }, emptyList<PICSRequest>())
        }

        // Apps: names and types.
        for ((appId, info) in cb.apps) {
            val common = info.keyValues.get("common")
            val name = common.get("name").asString() ?: continue
            val type = common.get("type").asString()?.lowercase()
            if (type == "game" || type == "demo") {
                gameMap[appId] = Game(appId, name, false)
            }
        }
        publishGames()
        if (cb.apps.isNotEmpty() && !cb.isResponsePending) _libraryLoading.value = false
    }

    private fun publishGames() {
        _games.value = gameMap.values
            .map { it.copy(familyShared = it.appId !in ownedApps) }
            .sortedBy { it.name.lowercase() }
    }
}
