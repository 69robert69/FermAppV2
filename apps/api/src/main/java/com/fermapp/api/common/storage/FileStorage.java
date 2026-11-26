package com.fermapp.api.common.storage;

import com.fermapp.api.config.AppProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Uploaded files on local disk, under UPLOAD_DIR (lib/storage.ts). */
@Component
public class FileStorage {

    /** The real image type, read from the content, not from the file name. */
    public record ImageType(String contentType, String extension) {
    }

    private final Path root;

    public FileStorage(AppProperties properties) {
        this.root = Path.of(properties.uploadDir()).toAbsolutePath().normalize();
    }

    private static String latin1(byte[] buffer, int from, int to) {
        return new String(buffer, from, to - from, StandardCharsets.ISO_8859_1);
    }

    public static ImageType detectImageType(byte[] buffer) {
        if (buffer.length > 3 && (buffer[0] & 0xff) == 0xff && (buffer[1] & 0xff) == 0xd8 && (buffer[2] & 0xff) == 0xff) {
            return new ImageType("image/jpeg", "jpg");
        }
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        if (buffer.length > 8 && Arrays.equals(Arrays.copyOf(buffer, 8), png)) {
            return new ImageType("image/png", "png");
        }
        if (buffer.length > 12 && latin1(buffer, 0, 4).equals("RIFF") && latin1(buffer, 8, 12).equals("WEBP")) {
            return new ImageType("image/webp", "webp");
        }
        if (buffer.length > 12 && latin1(buffer, 4, 8).equals("ftyp") && latin1(buffer, 8, 12).matches("^(heic|heix|mif1|msf1).*")) {
            return new ImageType("image/heic", "heic");
        }
        return null;
    }

    private Path resolve(String key) {
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return target;
    }

    public String save(String folder, String extension, byte[] content) {
        String key = folder + "/" + UUID.randomUUID() + "." + extension;
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        return key;
    }

    public byte[] load(String key) throws IOException {
        return Files.readAllBytes(resolve(key));
    }

    public void remove(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException | IllegalArgumentException ignored) {
            // A missing file is not an error, as before.
        }
    }
}
