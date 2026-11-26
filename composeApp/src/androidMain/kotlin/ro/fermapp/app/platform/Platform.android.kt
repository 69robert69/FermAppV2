package ro.fermapp.app.platform

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.FileProvider
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.suspendCancellableCoroutine
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import ro.fermapp.domain.Position
import ro.fermapp.mobile.BuildConfig
import kotlin.coroutines.resume

/** Set once by FermApplication. */
@SuppressLint("StaticFieldLeak")
object AndroidContext {
    lateinit var app: Context
}

actual val apiOrigin: String get() = BuildConfig.API_URL

actual val appDataDir: Path get() = (AndroidContext.app.filesDir.toOkioPath() / "fermapp")

actual val fileSystem: FileSystem get() = FileSystem.SYSTEM

actual fun platformHttpClient(): HttpClient = HttpClient(OkHttp)

actual object SecureStore {
    private val prefs: SharedPreferences by lazy {
        val key = MasterKey.Builder(AndroidContext.app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            AndroidContext.app,
            "fermapp.secure",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    actual fun get(key: String): String? = prefs.getString(key, null)
    actual fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
    actual fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

actual fun shareFile(path: Path, mimeType: String) {
    val context = AndroidContext.app
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", path.toFile())
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@SuppressLint("MissingPermission")
actual suspend fun currentLocation(): Position? {
    val context = AndroidContext.app
    val granted = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    if (!granted) return null
    val client = LocationServices.getFusedLocationProviderClient(context)
    return suspendCancellableCoroutine { continuation ->
        val cancel = CancellationTokenSource()
        client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancel.token)
            .addOnSuccessListener { location -> continuation.resume(location?.let { Position(it.longitude, it.latitude) }) }
            .addOnFailureListener { continuation.resume(null) }
        continuation.invokeOnCancellation { cancel.cancel() }
    }
}
