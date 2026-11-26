package com.fermapp.api.config;

import com.fermapp.api.realtime.SocketIoHandler;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Serves socket.io at /socket.io/ on the API port. socket.io did not check the Origin of
 * WebSocket connections (mobile apps send none), so neither does this endpoint; clients
 * authenticate with their token in the CONNECT packet.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final SocketIoHandler handler;

    public WebSocketConfig(SocketIoHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/socket.io/", "/socket.io").setAllowedOriginPatterns("*");
    }

    @PreDestroy
    void close() {
        handler.shutdown();
    }
}
