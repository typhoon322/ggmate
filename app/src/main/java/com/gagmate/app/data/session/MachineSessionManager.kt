package com.gagmate.app.data.session



import com.gagmate.app.data.api.ApiDebugLogger
import com.gagmate.app.data.api.GgboardApiClient
import com.gagmate.app.data.model.ProfileRef
import com.gagmate.app.data.protocol.*
import com.gagmate.app.data.system.DebugLogState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okio.ByteString

/**
 * Global singleton managing the WebSocket connection to the Gaggiuino machine.
 *
 * All real-time machine data flows through StateFlows exposed here.
 * ViewModels subscribe to these flows and should NOT manage their own WS connections.
 */
class MachineSessionManager {

    // ── Connection state ──────────────────────────────────────────

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // ── Real-time data sent from the machine ──────────────────────

    private val _machineState = MutableStateFlow(SystemState())
    val machineState: StateFlow<SystemState> = _machineState.asStateFlow()

    private val _sensorSnapshot = MutableStateFlow(SensorSnapshot())
    val sensorSnapshot: StateFlow<SensorSnapshot> = _sensorSnapshot.asStateFlow()

    private val _shotSnapshot = MutableStateFlow<ShotSnapshot?>(null)
    val shotSnapshot: StateFlow<ShotSnapshot?> = _shotSnapshot.asStateFlow()

    private val _currentProfiles = MutableStateFlow<List<ProfileRef>>(emptyList())
    val currentProfiles: StateFlow<List<ProfileRef>> = _currentProfiles.asStateFlow()

    // ── Internals ────────────────────────────────────────────────

    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var scope: CoroutineScope? = null
    private var reconnectJob: Job? = null
    private var host: String = ""

    companion object {
        private const val RECONNECT_DELAY_MS = 3000L
    }

    /**
     * Start the session: connect WebSocket and begin data flow.
     * Call from Application or MainActivity lifecycle.
     */
    fun start(applicationScope: CoroutineScope) {
        scope = applicationScope
        val url = GgboardApiClient.getCurrentBaseUrl()
        host = url.removePrefix("http://").removeSuffix("/")
        connect()
    }

    /**
     * Stop the session: disconnect WebSocket and cancel all coroutines.
     */
    fun stop() {
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "App closing")
        webSocket = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun connect() {
        val currentScope = scope ?: return
        _connectionState.value = ConnectionState.CONNECTING
        _errorMessage.value = null

        client = OkHttpClient.Builder()
            .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder().url("ws://$host/ws").build()
        webSocket = client?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                _connectionState.value = ConnectionState.CONNECTED
                DebugLogState.add("WS", "Connected to $host")
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                ApiDebugLogger.logResponse("WS <<", bytes.size, bytes.toByteArray().joinToString("") { b -> java.lang.String.format("%02x", b.toInt() and 0xFF) })

                ProtoCodec.decodeResponse(data)?.let { (cmd, payload) ->
                    ApiDebugLogger.logResponse("WS << $cmd", payload.size, ProtoCodec.toHexString(payload))
                    DebugLogState.add("WS <<", cmd)
                    handleMessage(cmd, payload)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = ConnectionState.ERROR
                _errorMessage.value = t.message ?: "WebSocket failure"
                DebugLogState.add("WS", "Error: ${t.message}")
                scheduleReconnect(currentScope)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                _connectionState.value = ConnectionState.DISCONNECTED
                DebugLogState.add("WS", "Closed: $reason")
            }
        })
    }

    private fun handleMessage(cmd: String, payload: ByteArray) {
        when (cmd) {
            Commands.SYS_STATE -> {
                _machineState.value = parseSysState(payload)
            }
            Commands.ACTIVE_PROFILE, Commands.PROFILE -> {
                DebugLogState.add("WS", "active_profile ${payload.size}B")
            }
            Commands.SENSOR_SNAPSHOT -> {
                parseSensorSnapshot(payload)?.let { _sensorSnapshot.value = it }
            }
            Commands.PROFILE_DICT -> {
                _currentProfiles.value = parseProfileDict(payload)
            }
            Commands.SHOT_HISTORY_INDEX -> {
                // Shot sync handled by SyncManager via REST API
            }
            Commands.SETTINGS -> {
                // Settings parsed but handled by SettingsRepository
            }
            Commands.SHOT_SNAPSHOT -> {
                _shotSnapshot.value = parseShotSnapshot(payload)
            }
        }
    }

    private fun scheduleReconnect(currentScope: CoroutineScope) {
        reconnectJob?.cancel()
        reconnectJob = currentScope.launch {
            delay(RECONNECT_DELAY_MS)
            connect()
        }
    }

    // ── Command sending ──────────────────────────────────────────

    /**
     * Select a profile on the machine.
     */
    fun selectProfile(profileId: Int) {
        val msg = Commands.buildSelectProfile(profileId)
        sendRaw(msg)
        DebugLogState.add("WS >>", "${Commands.SELECT_PROFILE} $profileId")
    }

    /**
     * Set operation mode (flush, descale, tare).
     */
    fun setOpMode(mode: Int) {
        val msg = Commands.buildOpMode(mode)
        sendRaw(msg)
    }

    /**
     * Request settings from the machine.
     */
    fun requestSettings() {
        val msg = Commands.buildGetSettings()
        sendRaw(msg)
        DebugLogState.add("WS >>", "g_settings")
    }

    /**
     * Send a raw command frame.
     */
    private fun sendRaw(data: ByteArray) {
        webSocket?.send(ByteString.of(*data))
        DebugLogState.add("WS >>", "frame ${data.size}B")
    }
}
