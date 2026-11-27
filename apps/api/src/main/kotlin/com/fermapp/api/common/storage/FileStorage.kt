package com.fermapp.api.common.storage

import com.fermapp.api.config.AppProperties
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.springframework.stereotype.Component

/** Uploaded files on local disk, under UPLOAD_DIR (lib/storage.ts). */
@Component
class FileStorage(properties: AppProperties) {

    /** The real image type, read from the content, not from the file name. */
    @JvmRecord
    data class ImageType(val contentType: String, val extension: String)

    private val root: Path = Path.of(properties.uploadDir()).toAbsolutePath().normalize()

    private fun resolve(key: String): Path {
        val target = root.resolve(key).normalize()
        require(target.startsWith(root)) { "Invalid storage key" }
        return target
    }

    fun save(folder: String, extension: String, content: ByteArray): String {
        val key = "$folder/${UUID.randomUUID()}.$extension"
        val target = resolve(key)
        try {
            Files.createDirectories(target.parent)
            Files.write(target, content)
        } catch (error: IOException) {
            throw UncheckedIOException(error)
        }
        return key
    }

    /** Declares IOException, which the Java callers catch for a missing file. */
    @Throws(IOException::class)
    fun load(key: String): ByteArray = Files.readAllBytes(resolve(key))

    fun remove(key: String) {
        try {
            Files.deleteIfExists(resolve(key))
        } catch (ignored: IOException) {
            // A missing file is not an error, as before.
        } catch (ignored: IllegalArgumentException) {
            // Neither is a key outside the upload folder.
        }
    }

    companion object {
        private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        private val HEIC_BRANDS = Regex("^(heic|heix|mif1|msf1).*")

        private fun latin1(buffer: ByteArray, from: Int, to: Int) = String(buffer, from, to - from, StandardCharsets.ISO_8859_1)

        private fun unsigned(byte: Byte) = byte.toInt() and 0xff

        @JvmStatic
        fun detectImageType(buffer: ByteArray): ImageType? = when {
            buffer.size > 3 && unsigned(buffer[0]) == 0xff && unsigned(buffer[1]) == 0xd8 && unsigned(buffer[2]) == 0xff ->
                ImageType("image/jpeg", "jpg")
            buffer.size > 8 && buffer.copyOf(8).contentEquals(PNG) -> ImageType("image/png", "png")
            buffer.size > 12 && latin1(buffer, 0, 4) == "RIFF" && latin1(buffer, 8, 12) == "WEBP" -> ImageType("image/webp", "webp")
            buffer.size > 12 && latin1(buffer, 4, 8) == "ftyp" && HEIC_BRANDS.matches(latin1(buffer, 8, 12)) ->
                ImageType("image/heic", "heic")
            else -> null
        }
    }
}
