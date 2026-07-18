package com.gagmate.app.data.session

import com.gagmate.app.data.api.ApiDebugLogger
import com.gagmate.app.data.api.GgboardApiClient
import com.gagmate.app.data.model.ProfileRef
import com.gagmate.app.data.protocol.*
import com.gagmate.app.data.system.DebugLogState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okio.ByteString

class MachineSessionManager {
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _messages = MutableSharedFlow<ProtoMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<ProtoMessage> = _messages.asSharedFlow()

    private val _machineState = MutableStateFlow(SystemState())
    val machineState: StateFlow<SystemState> = _machineState.asStateFlow()
    private val _sensorSnapshot = MutableStateFlow(SensorSnapshot())
    val sensorSnapshot: StateFlow<SensorSnapshot> = _sensorSnapshot.asStateFlow()
    private val _shotSnapshot = MutableStateFlow<ShotSnapshot?>(null)
    val shotSnapshot: StateFlow<ShotSnapshot?> = _shotSnapshot.asStateFlow()
    private val _currentProfiles = MutableStateFlow<List<ProfileRef>>(emptyList())
    val currentProfiles: StateFlow<List<ProfileRef>> = _currentProfiles.asStateFlow()
    private val _selectedProfileName = MutableStateFlow("")
    val selectedProfileName: StateFlow<String> = _selectedProfileName.asStateFlow()

    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var scope: CoroutineScope? = null
    private var reconnectJob: Job? = null
    private var host = ""
    private var reconnectAttempt = 0

    companion object {
        private const val INITIAL_RECONNECT_MS = 1000L
        private const val MAX_RECONNECT_MS = 30000L
    }

    fun start(applicationScope: CoroutineScope) {
        scope = applicationScope; reconnectAttempt = 0
        host = GgboardApiClient.getCurrentBaseUrl().removePrefix("http://").removeSuffix("/")
        connect()
    }

    fun stop() {
        reconnectJob?.cancel(); reconnectJob = null
        webSocket?.close(1000, "App closing"); webSocket = null
        client?.dispatcher?.executorService?.shutdown(); client = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun connect() {
        val currentScope = scope ?: return
        _connectionState.value = ConnectionState.CONNECTING; _errorMessage.value = null
        client = OkHttpClient.Builder().readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        webSocket = client?.newWebSocket(Request.Builder().url("ws://$host/ws").build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, res: Response) { reconnectAttempt = 0; _connectionState.value = ConnectionState.CONNECTED }
            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                val hex = data.joinToString("") { b -> java.lang.String.format("%02x", b.toInt() and 0xFF) }
                ApiDebugLogger.logResponse("WS <<", bytes.size, hex)
                ProtoCodec.decodeResponse(data)?.let { (cmd, payload) ->
                    DebugLogState.add("WS <<", cmd)
                    val msg = parseProtoMessage(cmd, payload)
                    handleMessage(msg)
                    _messages.tryEmit(msg)
                }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, res: Response?) {
                _connectionState.value = ConnectionState.ERROR
                _errorMessage.value = t.message ?: "WebSocket failure"
                scheduleReconnect(currentScope)
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        })
    }

    private fun handleMessage(msg: ProtoMessage) { when (msg) {
        is SystemStateMsg -> _machineState.value = msg.value
        is SensorSnapshotMsg -> _sensorSnapshot.value = msg.value
        is ShotSnapshotMsg -> _shotSnapshot.value = msg.value
        is ProfileDictMsg -> { _currentProfiles.value = msg.profiles; msg.profiles.firstOrNull { it.isSelected }?.let { _selectedProfileName.value = it.name } }
        is ActiveProfileMsg -> DebugLogState.add("WS", "active_profile ${msg.name}")
        is UnknownMsg -> DebugLogState.add("WS", "unhandled: ${msg.command}")
        else -> {}
    } }

    private fun scheduleReconnect(currentScope: CoroutineScope) {
        reconnectJob?.cancel()
        val delayMs = if (reconnectAttempt == 0) INITIAL_RECONNECT_MS
            else minOf(INITIAL_RECONNECT_MS * (1L shl minOf(reconnectAttempt - 1, 5)), MAX_RECONNECT_MS)
        reconnectJob = currentScope.launch { delay(delayMs); reconnectAttempt++; connect() }
    }

    fun selectProfile(profileId: Int) { sendRaw(Commands.buildSelectProfile(profileId)) }
    fun setOpMode(mode: Int) { sendRaw(Commands.buildOpMode(mode)) }
    fun requestSettings() { sendRaw(Commands.buildGetSettings()) }
    private fun sendRaw(data: ByteArray) { webSocket?.send(ByteString.of(*data)); DebugLogState.add("WS >>", "frame ${data.size}B") }
}
