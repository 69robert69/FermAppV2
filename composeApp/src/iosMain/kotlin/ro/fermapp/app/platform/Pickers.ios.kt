package ro.fermapp.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImage
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UniformTypeIdentifiers.UTTypeData
import platform.darwin.NSObject
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val bytes = ByteArray(size)
    if (size > 0) bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
    return bytes
}

private fun present(controller: platform.UIKit.UIViewController) {
    UIApplication.sharedApplication.keyWindow?.rootViewController?.presentViewController(controller, true, null)
}

/** Photos come from the photo library; any other file from the Files app. */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberFilePicker(mimeTypes: List<String>, onPicked: (PickedFile) -> Unit): () -> Unit {
    val callback = rememberUpdatedState(onPicked)
    val images = mimeTypes.all { it.startsWith("image/") }
    val imageDelegate = remember {
        object : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
            override fun imagePickerController(picker: UIImagePickerController, didFinishPickingMediaWithInfo: Map<Any?, *>) {
                picker.dismissViewControllerAnimated(true, null)
                val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage ?: return
                val data = UIImageJPEGRepresentation(image, 0.7) ?: return
                callback.value(PickedFile("photo.jpg", "image/jpeg", data.toByteArray()))
            }

            override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
                picker.dismissViewControllerAnimated(true, null)
            }
        }
    }
    val documentDelegate = remember {
        object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
                url.startAccessingSecurityScopedResource()
                val data = NSData.dataWithContentsOfURL(url)
                url.stopAccessingSecurityScopedResource()
                if (data != null) callback.value(PickedFile(url.lastPathComponent ?: "file", "application/octet-stream", data.toByteArray()))
            }
        }
    }
    return {
        if (images) {
            present(UIImagePickerController().apply { delegate = imageDelegate })
        } else {
            present(UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeData), asCopy = true).apply { delegate = documentDelegate })
        }
    }
}

actual fun openUrl(url: String) {
    val target = NSURL.URLWithString(url) ?: return
    UIApplication.sharedApplication.openURL(target, emptyMap<Any?, Any>(), null)
}
