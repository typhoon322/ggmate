package com.gagmate.app.data.session

import com.gagmate.app.data.api.ApiDebugLogger
import com.gagmate.app.data.api.GgboardApiClient
import com.gagmate.app.data.model.ProfileRef
import android.content.Context
import com.gagmate.app.data.system.SoundManager
import com.gagmate.app.data.protocol.*
import com.gagmate.app.data.system.DebugLogState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okio.ByteString
import com.google.gson.Gson
import com.google.gson.JsonObject

private val jsonGson = Gson()

/** JSON WebSocket data models (Gen3 firmware text format). */
private data class WsSensorJson(
    val brewActive: Boolean = false,
    val steamActive: Boolean = false,
    val temperature: Float = 0f,
    val targetTemperature: Float = 0f,
    val pressure: Float = 0f,
    val pumpFlow: Float = 0f,
    val weightFlow: Float = 0f,
    val weight: Float = 0f,
    val waterLvl: Int = 0
)

private data class WsShotJson(
    val timeInShot: Int = 0,
    val pressure: Float = 0f,
    val pumpFlow: Float = 0f,
    val weightFlow: Float = 0f,
    val temperature: Float = 0f,
    val shotWeight: Float = 0f,
    val waterPumped: Float = 0f,
    val targetTemperature: Float = 0f,
    val targetPumpFlow: Float = 0f,
    val targetPressure: Float = 0f
)

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
    private val _brewActive = MutableStateFlow(false)
    val brewActive: StateFlow<Boolean> = _brewActive.asStateFlow()

    private val _selectedProfileName = MutableStateFlow("")
    val selectedProfileName: StateFlow<String> = _selectedProfileName.asStateFlow()

    /** Emitted when d_prof/d_act_prof provides full profile data (name, phases). */
    private val _profileDataReceived = MutableSharedFlow<Pair<String, List<com.gagmate.app.data.model.BrewPhase>>>(extraBufferCapacity = 16)
    val profileDataReceived: SharedFlow<Pair<String, List<com.gagmate.app.data.model.BrewPhase>>> = _profileDataReceived.asSharedFlow()

    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var scope: CoroutineScope? = null
    private var reconnectJob: Job? = null
    private var host = ""
    private var reconnectAttempt = 0
    private var hasConnectedOnce = false
    private var appContext: Context? = null

    companion object {
        private const val INITIAL_RECONNECT_MS = 1000L
        private const val MAX_RECONNECT_MS = 30000L
    }

    fun start(applicationScope: CoroutineScope, context: Context? = null) { appContext = context;
        scope = applicationScope; reconnectAttempt = 0
        host = GgboardApiClient.getCurrentBaseUrl().removePrefix("https://").removePrefix("http://").removeSuffix("/")
        connect()
    }

    /** Reconnect to a new host (e.g. after Settings change). */
    fun restart(newHost: String? = null) {
        if (newHost != null) {
            host = newHost.removePrefix("http://").removeSuffix("/")
        }
        webSocket?.close(1000, "Restart")
        webSocket = null
        reconnectJob?.cancel(); reconnectJob = null
        reconnectAttempt = 0
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
        if (client == null) client = OkHttpClient.Builder().readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        webSocket = client?.newWebSocket(Request.Builder().url("ws://$host/ws").build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, res: Response) {
                reconnectAttempt = 0; _connectionState.value = ConnectionState.CONNECTED
                if (!hasConnectedOnce) { hasConnectedOnce = true; appContext?.let { SoundManager.playConnectionSuccess(it) } }
            }

            // TEXT messages: Gen3 firmware sends JSON (same format as web UI)
            override fun onMessage(ws: WebSocket, text: String) {
                ApiDebugLogger.logResponse("WS json", text.length, text.take(500))
                DebugLogState.add("WS", "json ${text.take(80)}")
                handleJsonMessage(text)
            }

            // BINARY messages: protobuf format (for profile commands etc.)
            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                val hex = data.joinToString("") { b -> java.lang.String.format("%02x", b.toInt() and 0xFF) }
                ApiDebugLogger.logResponse("WS <<", bytes.size, hex)
                val decoded = ProtoCodec.decodeResponse(data)
                if (decoded != null) {
                    val (cmd, payload) = decoded
                    DebugLogState.add("WS <<", cmd)
                    val msg = parseProtoMessage(cmd, payload)
                    handleMessage(msg)
                    _messages.tryEmit(msg)
                } else {
                    // Not a valid protobuf command — try JSON text embedded in binary frame
                    try {
                        val text = data.decodeToString()
                        if (text.contains("action")) {
                            ApiDebugLogger.logResponse("WS json", text.length, text.take(500))
                            DebugLogState.add("WS", "json_bin ${text.take(80)}")
                            handleJsonMessage(text)
                        }
                    } catch (_: Exception) { }
                }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, res: Response?) {
                _connectionState.value = ConnectionState.ERROR
                _errorMessage.value = t.message ?: "WebSocket failure"
                scheduleReconnect(currentScope)
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                _connectionState.value = ConnectionState.DISCONNECTED
                if (code != 1000) { val sc = scope; if (sc != null) scheduleReconnect(sc) }
            }
        })
    }

    private fun handleJsonMessage(text: String) {
        try {
            val obj = jsonGson.fromJson(text, JsonObject::class.java) ?: return
            val action = obj.get("action")?.asString ?: return
            val data = obj.getAsJsonObject("data") ?: return

            DebugLogState.add("WS json", action)

            when (action) {
                "sensor_data_update" -> {
                    val sensor = jsonGson.fromJson(data, WsSensorJson::class.java)
                    _sensorSnapshot.value = SensorSnapshot(
                        temperature = sensor.temperature,
                        targetTemperature = sensor.targetTemperature,
                        pressure = sensor.pressure,
                        waterLevel = sensor.waterLvl
                    )
                    _brewActive.value = sensor.brewActive
                    _machineState.value = SystemState(state = if (sensor.brewActive) 1 else 0)
                }
                "shot_data_update" -> {
                    val shot = jsonGson.fromJson(data, WsShotJson::class.java)
                    _shotSnapshot.value = ShotSnapshot(
                        timeInShot = shot.timeInShot,
                        pressure = shot.pressure,
                        flow = shot.pumpFlow,
                        temperature = shot.temperature,
                        weight = shot.shotWeight,
                        waterPumped = shot.waterPumped
                    )
                }
            }
        } catch (_: Exception) {
            // Malformed JSON - ignore
        }
    }

    private fun handleMessage(msg: ProtoMessage) { when (msg) {
        is SystemStateMsg -> _machineState.value = msg.value
        is SensorSnapshotMsg -> _sensorSnapshot.value = msg.value
        is ShotSnapshotMsg -> _shotSnapshot.value = msg.value
        is ProfileDictMsg -> { _currentProfiles.value = msg.profiles; msg.profiles.firstOrNull { it.isSelected }?.let { _selectedProfileName.value = it.name } }
        is ActiveProfileMsg -> {
            DebugLogState.add("WS", "active_profile ${msg.name}")
            if (msg.phases.isNotEmpty()) {
                _profileDataReceived.tryEmit(msg.name to msg.phases)
            }
        }
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
    fun sendGetProfile(profileId: Int) { sendRaw(Commands.buildGetProfile(profileId)) }
    fun setOpMode(mode: Int) { sendRaw(Commands.buildOpMode(mode)) }
    fun requestSettings() { sendRaw(Commands.buildGetSettings()) }
    private fun sendRaw(data: ByteArray) { webSocket?.send(ByteString.of(*data)); DebugLogState.add("WS >>", "frame ${data.size}B") }
}
