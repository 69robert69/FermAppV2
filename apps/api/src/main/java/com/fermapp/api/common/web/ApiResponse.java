package com.fermapp.api.common.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriUtils;
import java.nio.charset.StandardCharsets;

/**
 * Successful responses have the form {@code { "data": ... }}, as in the TypeScript router.
 * These helpers fix the status codes: 200 for reads and actions, 201 for creations,
 * 202 for accepted telemetry and 204 when there is nothing to return.
 */
public record ApiResponse<T>(T data) {

    public static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok(new ApiResponse<>(data));
    }

    public static <T> ResponseEntity<ApiResponse<T>> created(T data) {
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(data));
    }

    public static <T> ResponseEntity<ApiResponse<T>> accepted(T data) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ApiResponse<>(data));
    }

    public static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().build();
    }

    /**
     * A file download, with the filename encoded as in the original
     * {@code Content-Disposition: attachment; filename*=UTF-8''...}.
     */
    public static ResponseEntity<byte[]> file(byte[] content, String contentType, String filename, boolean inline) {
        String disposition = (inline ? "inline" : "attachment") + "; filename*=UTF-8''"
                + UriUtils.encode(filename, StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.parseMediaType(contentType))
                .body(content);
    }
}
