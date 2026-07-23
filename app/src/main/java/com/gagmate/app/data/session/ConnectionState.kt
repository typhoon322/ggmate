package com.gagmate.app.data.session

/**
 * WebSocket connection state exposed to UI.
 *
 * - DISCONNECTED: socket closed (normal/intentional), not currently trying.
 * - CONNECTING:   opening a fresh socket.
 * - CONNECTED:    socket open and frames flowing.
 * - RECONNECTING: transient drop detected, auto-retry in progress (backoff).
 * - ERROR:        gave up after max retries (user must reconfigure/reconnect).
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}
