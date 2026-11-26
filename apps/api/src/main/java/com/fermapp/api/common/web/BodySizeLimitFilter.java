package com.fermapp.api.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fermapp.api.common.error.ErrorBody;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/** JSON bodies are limited to 2 MB, like {@code express.json({ limit: '2mb' })}: larger ones get 413 TOO_LARGE. */
public class BodySizeLimitFilter extends OncePerRequestFilter {

    public static final long MAX_JSON_BYTES = 2L * 1024 * 1024;

    private final ObjectMapper mapper;

    public BodySizeLimitFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Thrown when a chunked body grows past the limit while it is being read. */
    public static class PayloadTooLargeException extends IOException {
        public PayloadTooLargeException() {
            super("The request body is too large.");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String type = request.getContentType();
        return type == null || !type.toLowerCase().contains("json");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_JSON_BYTES) {
            response.setStatus(413);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            mapper.writeValue(response.getOutputStream(), ErrorBody.of("TOO_LARGE", "The request body is too large."));
            return;
        }
        chain.doFilter(new LimitedRequest(request), response);
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                ServletInputStream original = super.getInputStream();
                stream = new ServletInputStream() {
                    private long read;

                    private void count(long bytes) throws IOException {
                        read += bytes;
                        if (read > MAX_JSON_BYTES) {
                            throw new PayloadTooLargeException();
                        }
                    }

                    @Override
                    public int read() throws IOException {
                        int value = original.read();
                        if (value >= 0) {
                            count(1);
                        }
                        return value;
                    }

                    @Override
                    public int read(byte[] buffer, int offset, int length) throws IOException {
                        int count = original.read(buffer, offset, length);
                        if (count > 0) {
                            count(count);
                        }
                        return count;
                    }

                    @Override
                    public boolean isFinished() {
                        return original.isFinished();
                    }

                    @Override
                    public boolean isReady() {
                        return original.isReady();
                    }

                    @Override
                    public void setReadListener(ReadListener listener) {
                        original.setReadListener(listener);
                    }
                };
            }
            return stream;
        }
    }
}
