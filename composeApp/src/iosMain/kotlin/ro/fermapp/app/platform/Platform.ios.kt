package ro.fermapp.app.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFTypeRefVar
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import ro.fermapp.domain.Position

/** The API address comes from the FermAppApiUrl key in Info.plist. */
actual val apiOrigin: String
    get() = (NSBundle.mainBundle.objectForInfoDictionaryKey("FermAppApiUrl") as? String) ?: "http://localhost:4000"

@OptIn(ExperimentalForeignApi::class)
actual val appDataDir: Path
    get() {
        val url = NSFileManager.defaultManager.URLForDirectory(NSDocumentDirectory, NSUserDomainMask, null, true, null)
        return ((url?.path ?: "") + "/fermapp").toPath()
    }

actual val fileSystem: FileSystem get() = FileSystem.SYSTEM

actual fun platformHttpClient(): HttpClient = HttpClient(Darwin)

/** The session lives in the Keychain, as expo-secure-store kept it. */
@OptIn(ExperimentalForeignApi::class)
actual object SecureStore {
    private const val SERVICE = "ro.fermapp.mobile"

    private fun query(key: String, extra: Map<Any?, Any?> = emptyMap()): CFDictionaryRef? {
        val map = mutableMapOf<Any?, Any?>(kSecClass to kSecClassGenericPassword, kSecAttrService to SERVICE, kSecAttrAccount to key)
        map.putAll(extra)
        @Suppress("UNCHECKED_CAST")
        return CFBridgingRetain(map as Map<Any?, *>) as CFDictionaryRef?
    }

    actual fun get(key: String): String? = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query(key, mapOf(kSecReturnData to true, kSecMatchLimit to kSecMatchLimitOne)), result.ptr)
        if (status != 0) return null
        val data = CFBridgingRelease(result.value) as? NSData ?: return null
        NSString.create(data, NSUTF8StringEncoding)?.toString()
    }

    actual fun set(key: String, value: String) {
        SecItemDelete(query(key))
        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        SecItemAdd(query(key, mapOf(kSecValueData to data)), null)
    }

    actual fun remove(key: String) {
        SecItemDelete(query(key))
    }
}

actual fun shareFile(path: Path, mimeType: String) {
    val url = NSURL.fileURLWithPath(path.toString())
    val controller = UIActivityViewController(listOf(url), null)
    UIApplication.sharedApplication.keyWindow?.rootViewController?.presentViewController(controller, true, null)
}

actual suspend fun currentLocation(): Position? = IosLocation.current()
