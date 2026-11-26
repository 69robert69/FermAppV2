package ro.fermapp.app.platform

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState

private fun displayName(uri: Uri): String {
    val resolver = AndroidContext.app.contentResolver
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) return cursor.getString(0) ?: "file"
    }
    return uri.lastPathSegment ?: "file"
}

@Composable
actual fun rememberFilePicker(mimeTypes: List<String>, onPicked: (PickedFile) -> Unit): () -> Unit {
    val callback = rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val resolver = AndroidContext.app.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@rememberLauncherForActivityResult
        callback.value(PickedFile(displayName(uri), resolver.getType(uri) ?: "application/octet-stream", bytes))
    }
    return { launcher.launch(mimeTypes.toTypedArray()) }
}

actual fun openUrl(url: String) {
    AndroidContext.app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
