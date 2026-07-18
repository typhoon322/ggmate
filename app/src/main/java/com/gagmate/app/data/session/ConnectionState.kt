package com.gagmate.app.data.session

/** WebSocket connection state exposed to UI. */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}
