package ro.fermapp.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import org.jetbrains.compose.resources.decodeToImageBitmap
import ro.fermapp.app.data.Api
import ro.fermapp.app.data.Auth
import ro.fermapp.app.theme.FarmColors

/** An image served by the API (a parcel photo, a satellite map), downloaded with the session token. */
@Composable
fun AuthImage(path: String, description: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = runCatching {
            val response = Api.http.get("${Api.apiUrl}$path") {
                Auth.current.session?.accessToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }
            if (response.status.isSuccess()) response.readRawBytes().decodeToImageBitmap() else null
        }.getOrNull()
    }
    Box(modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(FarmColors.ridge)) {
        bitmap?.let { Image(it, description, Modifier.fillMaxSize(), contentScale = contentScale) }
    }
}
