package ro.fermapp.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ro.fermapp.app.platform.SecureStore

enum class AuthStatus { LOADING, SIGNED_OUT, SIGNED_IN }

data class AuthState(val status: AuthStatus = AuthStatus.LOADING, val session: Session? = null) {
    val role: Role? get() = session?.role
    val farmId: String? get() = session?.farm?.id
}

/** The signed-in session, kept in encrypted storage like store/auth.ts. */
object Auth {
    private const val STORAGE_KEY = "fermapp.session"
    private val state = MutableStateFlow(AuthState())
    val flow: StateFlow<AuthState> = state.asStateFlow()
    val current: AuthState get() = state.value

    fun restore() {
        if (state.value.status == AuthStatus.SIGNED_IN) return
        val stored = runCatching { SecureStore.get(STORAGE_KEY) }.getOrNull()
        val session = stored?.let { runCatching { AppJson.decodeFromString(Session.serializer(), it) }.getOrNull() }
        state.value = if (session != null) AuthState(AuthStatus.SIGNED_IN, session) else AuthState(AuthStatus.SIGNED_OUT)
    }

    fun setSession(session: Session) {
        SecureStore.set(STORAGE_KEY, AppJson.encodeToString(Session.serializer(), session))
        state.value = AuthState(AuthStatus.SIGNED_IN, session)
    }

    fun updateTokens(accessToken: String, refreshToken: String) {
        val session = state.value.session ?: return
        setSession(session.copy(accessToken = accessToken, refreshToken = refreshToken))
    }

    suspend fun signOut() {
        state.value = AuthState(AuthStatus.SIGNED_OUT)
        runCatching { SecureStore.remove(STORAGE_KEY) }
        runCatching { LocalStore.clear() }
        Queries.clear()
    }
}
