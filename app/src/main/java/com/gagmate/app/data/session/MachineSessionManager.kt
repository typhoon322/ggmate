package com.gagmate.app.data.session

import com.gagmate.app.data.api.ApiDebugLogger
import com.gagmate.app.data.api.GgboardApiClient
import com.gagmate.app.data.model.ProfileRef
import android.content.Context
import android.util.Log
import com.gagmate.app.BuildConfig
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

    /**
     * Brew-active is derived from two independent signals so firmware that only
     * speaks one wire format still wakes the live curve:
     *  - JSON `sensor_data_update.brewActive` (Gen3 text firmware)
     *  - protobuf `ShotSnapshot.timeInShot > 0` (older binary firmware)
     * The exposed [brewActive] is the OR of both. A watchdog resets the
     * protobuf branch if no in-shot snapshot arrives for a few seconds (the
     * machine may simply stop emitting snapshots when the shot ends).
     */
    private var brewActiveFromJson = false
    private var brewActiveFromProtobuf = false
    private var lastShotActiveAt = 0L
    private var brewWatchdogJob: Job? = null
    private fun recomputeBrewActive() {
        _brewActive.value = brewActiveFromJson || brewActiveFromProtobuf
    }

    /** Machine operation mode from d_sys_state. 1=normal, 2=flush, 3=descale, 8=tare */
    private val _machineMode = MutableStateFlow(0)
    val machineMode: StateFlow<Int> = _machineMode.asStateFlow()

    /** Raw protobuf payload of the most recent d_act_prof/d_prof, for temperature/weight adjustments. */
    private var _activeProfileRawPayload: ByteArray? = null

    private val _selectedProfileName = MutableStateFlow("")
    val selectedProfileName: StateFlow<String> = _selectedProfileName.asStateFlow()

    /** Machine id of the currently selected profile (for REST profile-detail fetches). */
    private val _selectedProfileId = MutableStateFlow(-1)
    val selectedProfileId: StateFlow<Int> = _selectedProfileId.asStateFlow()

    /** Profile setpoint temperature read from the active profile protobuf (field 4). °C. */
    private val _activeProfileSetpointTemp = MutableStateFlow(0f)
    val activeProfileSetpointTemp: StateFlow<Float> = _activeProfileSetpointTemp.asStateFlow()

    /** Target shot weight read from the active profile protobuf (field 3 inner field 2). g. */
    private val _activeProfileTargetWeight = MutableStateFlow(0f)
    val activeProfileTargetWeight: StateFlow<Float> = _activeProfileTargetWeight.asStateFlow()

    /** Target pump pressure read from the active profile protobuf (field 3 inner field 3). bar. */
    private val _activeProfileTargetPressure = MutableStateFlow(0f)
    val activeProfileTargetPressure: StateFlow<Float> = _activeProfileTargetPressure.asStateFlow()

    /** Target pump flow read from the active profile protobuf (field 3 inner field 4). ml/s. */
    private val _activeProfileTargetFlow = MutableStateFlow(0f)
    val activeProfileTargetFlow: StateFlow<Float> = _activeProfileTargetFlow.asStateFlow()

    /** Target brew time read from the active profile protobuf (field 3 inner field 5). seconds. */
    private val _activeProfileTargetTime = MutableStateFlow(0f)
    val activeProfileTargetTime: StateFlow<Float> = _activeProfileTargetTime.asStateFlow()

    /** Emitted when d_prof/d_act_prof provides full profile data (name, phases). */
    private val _profileDataReceived = MutableSharedFlow<Pair<String, List<com.gagmate.app.data.model.BrewPhase>>>(extraBufferCapacity = 16)
    val profileDataReceived: SharedFlow<Pair<String, List<com.gagmate.app.data.model.BrewPhase>>> = _profileDataReceived.asSharedFlow()

    /** Retained phases of the currently selected/active profile, for the dashboard curve chart. */
    private val _selectedProfilePhases = MutableStateFlow<List<com.gagmate.app.data.model.BrewPhase>>(emptyList())
    val selectedProfilePhases: StateFlow<List<com.gagmate.app.data.model.BrewPhase>> = _selectedProfilePhases.asStateFlow()

    /**
     * Pending `requestProfilePhases` callers, keyed by profile name. When a
     * `d_prof`/`d_act_prof` response arrives we complete the matching deferred so
     * a caller can await the CURRENT profile definition synchronously (this is
     * the only reliable source on firmwares that do not expose REST
     * `GET /api/profile/{id}`).
     */
    private val pendingProfileDeferreds = mutableMapOf<String, CompletableDeferred<List<com.gagmate.app.data.model.BrewPhase>>>()

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
        /**
         * Stop auto-reconnecting after this many failed connection attempts
         * (1 initial + 2 retries). The UI then shows a non-blocking "can't connect"
         * banner with a manual Retry button instead of looping forever.
         */
        private const val MAX_RECONNECT_ATTEMPTS = 3
        /** How long to wait without an in-shot ShotSnapshot before clearing brewActive. */
        private const val BREW_WATCHDOG_MS = 4000L
    }

    /** Strip scheme + trailing slash so host is always ready for ws://$host/ws. */
    private fun normalizeHost(raw: String): String =
        raw.removePrefix("https://").removePrefix("http://").removeSuffix("/").trim()

    fun start(applicationScope: CoroutineScope, context: Context) {
        appContext = context.applicationContext
        scope = applicationScope
        reconnectAttempt = 0
        host = normalizeHost(GgboardApiClient.getCurrentBaseUrl())
        brewActiveFromJson = false
        brewActiveFromProtobuf = false
        lastShotActiveAt = 0L
        recomputeBrewActive()
        launchBrewWatchdog(applicationScope)
        connect()
    }

    /**
     * Resets the protobuf-derived brew flag if no in-shot [ShotSnapshot] has
     * arrived for [BREW_WATCHDOG_MS]. Covers firmware that stops emitting
     * snapshots at the end of a shot instead of sending an explicit end frame.
     */
    private fun launchBrewWatchdog(scope: CoroutineScope) {
        brewWatchdogJob?.cancel()
        brewWatchdogJob = scope.launch {
            while (isActive) {
                delay(BREW_WATCHDOG_MS)
                if (brewActiveFromProtobuf && System.currentTimeMillis() - lastShotActiveAt > BREW_WATCHDOG_MS) {
                    brewActiveFromProtobuf = false
                    recomputeBrewActive()
                }
            }
        }
    }

    /** Reconnect to a new host (e.g. after Settings change). */
    fun restart(newHost: String? = null) {
        if (newHost != null) {
            host = normalizeHost(newHost)
        }
        webSocket?.close(1000, "Restart")
        webSocket = null
        reconnectJob?.cancel(); reconnectJob = null
        reconnectAttempt = 0
        connect()
    }

    fun stop() {
        reconnectJob?.cancel(); reconnectJob = null
        brewWatchdogJob?.cancel(); brewWatchdogJob = null
        webSocket?.close(1000, "App closing"); webSocket = null
        client?.dispatcher?.executorService?.shutdown(); client = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Manual reconnect triggered by the user (e.g. tapping "Retry" in the offline
     * banner). Resets the failed-attempt counter and tries once; if it still fails,
     * auto-reconnect will give up again after 3 more attempts.
     */
    fun retryConnection() {
        reconnectJob?.cancel(); reconnectJob = null
        reconnectAttempt = 0
        webSocket?.cancel(); webSocket = null
        _connectionState.value = ConnectionState.CONNECTING
        connect()
    }

    private fun connect() {
        val currentScope = scope ?: return
        webSocket?.cancel()
        _connectionState.value = ConnectionState.CONNECTING; _errorMessage.value = null
        if (client == null) client = OkHttpClient.Builder().readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        webSocket = client?.newWebSocket(Request.Builder().url("ws://$host/ws").build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, res: Response) {
                reconnectAttempt = 0; _connectionState.value = ConnectionState.CONNECTED; _errorMessage.value = null
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
                        pumpFlow = sensor.pumpFlow,
                        weight = sensor.weight,
                        waterLevel = sensor.waterLvl
                    )
                    _brewActive.value = sensor.brewActive
                    brewActiveFromJson = sensor.brewActive
                    recomputeBrewActive()
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
                else -> {
                    // Catch-all: profile data (d_prof / d_act_prof) sometimes arrives as a JSON
                    // text frame instead of binary protobuf — this logs it so we can see it.
                    if (BuildConfig.DEBUG)
                        Log.d("GagMateWS", "handleJson: unhandled action='$action' raw=${text.take(160)}")
                }
            }
        } catch (_: Exception) {
            // Malformed JSON - ignore
        }
    }

    private fun handleMessage(msg: ProtoMessage) { when (msg) {
is SystemStateMsg -> {
    _machineState.value = msg.value
    _machineMode.value = msg.value.mode
}
        is SensorSnapshotMsg -> {
            if (com.gagmate.app.BuildConfig.DEBUG) {
                Log.d("GagMateWS", "SensorSnapshot t=${msg.value.temperature} p=${msg.value.pressure}")
            }
            _sensorSnapshot.value = msg.value
        }
        is ShotSnapshotMsg -> {
            _shotSnapshot.value = msg.value
            // Derive brew-active from the protobuf shot clock. While timeInShot
            // keeps advancing the shot is in progress; the watchdog clears the
            // flag if snapshots stop arriving (shot ended without an end frame).
            val inShot = msg.value.timeInShot > 0
            if (inShot) {
                brewActiveFromProtobuf = true
                lastShotActiveAt = System.currentTimeMillis()
            } else {
                brewActiveFromProtobuf = false
            }
            recomputeBrewActive()
        }
        is ProfileDictMsg -> {
            _currentProfiles.value = msg.profiles
            val selected = msg.profiles.firstOrNull { it.isSelected }
            if (BuildConfig.DEBUG)
                Log.d("GagMateProfile", "ProfileDict: ${msg.profiles.size} profiles, selected='${selected?.name ?: "none"}' id=${selected?.id ?: -1}")
            // Surface the selected profile name on the dashboard.
            selected?.let {
                _selectedProfileName.value = it.name
                _selectedProfileId.value = it.id
            }
            // Proactively fetch the selected profile's full payload (d_prof) so we get its
            // phases + global setpoints even if the machine never pushes d_act_prof.
            selected?.let { sendGetProfile(it.id) }
        }
        is ActiveProfileMsg -> {
            DebugLogState.add("WS", "active_profile ${msg.name}")
            if (BuildConfig.DEBUG)
                Log.d("GagMateProfile", "WS d_prof/d_act_prof received: name='${msg.name}' phases=${msg.phases.size} rawPayload=${msg.rawPayload.size}B")
            // The active profile name is authoritative — surface it on the dashboard.
            _selectedProfileName.value = msg.name
            _activeProfileRawPayload = if (msg.rawPayload.isNotEmpty()) msg.rawPayload else _activeProfileRawPayload
            if (msg.rawPayload.isNotEmpty()) {
                val g = Commands.extractProfileGlobals(msg.rawPayload)
                if (BuildConfig.DEBUG)
                    Log.d("GagMateProfile", "globals: temp=${g.waterTemperature} weight=${g.targetWeight} pres=${g.targetPumpPressure} flow=${g.targetPumpFlow} time=${g.targetTime}")
                g.waterTemperature?.let { _activeProfileSetpointTemp.value = it }
                g.targetWeight?.let { _activeProfileTargetWeight.value = it }
                g.targetPumpPressure?.let { _activeProfileTargetPressure.value = it }
                g.targetPumpFlow?.let { _activeProfileTargetFlow.value = it }
                g.targetTime?.let { _activeProfileTargetTime.value = it }
            }
            if (msg.phases.isNotEmpty()) {
                _selectedProfilePhases.value = msg.phases
                _profileDataReceived.tryEmit(msg.name to msg.phases)
                // Complete any pending synchronous request. Match by exact name;
                // if the profile name could not be extracted (msg.name == "unknown")
                // but there is exactly one outstanding request, complete it — this
                // covers the common single-detail-page case so a g_prof reply is
                // not silently dropped (and the caller does not time out empty)
                // when the firmware's d_prof name field is missing/blank.
                synchronized(pendingProfileDeferreds) {
                    val exact = pendingProfileDeferreds.remove(msg.name)
                    if (exact != null) {
                        exact.complete(msg.phases)
                    } else if (pendingProfileDeferreds.size == 1) {
                        pendingProfileDeferreds.values.first().also {
                            pendingProfileDeferreds.clear()
                            it.complete(msg.phases)
                        }
                    }
                    Unit
                }
            } else if (BuildConfig.DEBUG) {
                Log.w("GagMateProfile", "WS d_prof/d_act_prof name='${msg.name}' had 0 phases — parse may have failed")
            }
        }
        is UnknownMsg -> DebugLogState.add("WS", "unhandled: ${msg.command}")
        else -> {}
    } }

    private fun scheduleReconnect(currentScope: CoroutineScope) {
        reconnectJob?.cancel()
        reconnectAttempt++
        // Gave up after too many attempts → surface a terminal ERROR so the UI
        // can show a non-blocking "can't connect" banner with a manual Retry button,
        // instead of looping forever and popping banners repeatedly.
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = ConnectionState.ERROR
            return
        }
        val delayMs = if (reconnectAttempt == 1) INITIAL_RECONNECT_MS
            else minOf(INITIAL_RECONNECT_MS * (1L shl minOf(reconnectAttempt - 1, 5)), MAX_RECONNECT_MS)
        reconnectJob = currentScope.launch {
            _connectionState.value = ConnectionState.RECONNECTING
            delay(delayMs)
            connect()
        }
    }

    fun selectProfile(profileId: Int) { sendRaw(Commands.buildSelectProfile(profileId)) }
    fun sendGetProfile(profileId: Int) {
        val frame = Commands.buildGetProfile(profileId)
        if (BuildConfig.DEBUG) {
            val hex = frame.joinToString("") { b -> java.lang.String.format("%02x", b.toInt() and 0xFF) }
            Log.d("GagMateProfile", "sendGetProfile($profileId) frame=${frame.size}B hex=$hex")
        }
        sendRaw(frame)
    }

    /** True when the WebSocket is currently connected to the machine. */
    fun isConnected(): Boolean = connectionState.value == ConnectionState.CONNECTED

    /**
     * Request the CURRENT definition of a profile from the machine over WebSocket
     * and await the `d_prof`/`d_act_prof` response.
     *
     * This is the authoritative "now" recipe source on firmwares that do NOT
     * expose the REST `GET /api/profile/{id}` endpoint. Fire-and-forget is not
     * enough — the response arrives asynchronously, so we correlate it by the
     * profile name carried in the protobuf payload.
     *
     * @return the phase list, or empty if the machine does not respond in time
     *         or the WebSocket is not connected.
     */
    suspend fun requestProfilePhases(
        profileId: Int,
        profileName: String,
        timeoutMs: Long = 3500L
    ): List<com.gagmate.app.data.model.BrewPhase> {
        if (!isConnected()) return emptyList()
        val deferred = CompletableDeferred<List<com.gagmate.app.data.model.BrewPhase>>()
        synchronized(pendingProfileDeferreds) { pendingProfileDeferreds[profileName] = deferred }
        sendGetProfile(profileId)
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            emptyList()
        } finally {
            synchronized(pendingProfileDeferreds) {
                if (pendingProfileDeferreds[profileName] === deferred) pendingProfileDeferreds.remove(profileName)
            }
        }
    }
    fun setOpMode(mode: Int) { sendRaw(Commands.buildOpMode(mode)) }
    fun tareScale() { sendRaw(Commands.buildTareCommand()) }
    fun setNormalMode() { sendRaw(Commands.buildNormalOpMode()) }

    /**
     * Enter brew-ready (NORMAL) opmode. A shot is physically started on the
     * machine's brew switch, but this arms the machine for extraction.
     */
    fun startBrew() { sendRaw(Commands.buildOpMode(Commands.MODE_NORMAL)) }

    /**
     * Halt the current operation by returning to STANDBY opmode.
     */
    fun stopBrew() { sendRaw(Commands.buildOpMode(Commands.MODE_STANDBY)) }
    /**
     * Adjust the temperature setpoint of the active profile and send it to the machine.
     * Requires a previously received d_act_prof/d_prof to have the full profile payload.
     * @param newTemp The new temperature in °C.
     */
    fun updateActiveProfileTemperature(newTemp: Float) {
        val payload = _activeProfileRawPayload ?: return
        val modified = Commands.updateProfileTemperature(payload, newTemp)
        _activeProfileSetpointTemp.value = newTemp
        sendRaw(Commands.buildUpdateActiveProfileCmd(modified))
    }
    /**
     * Adjust the target weight of the active profile and send it to the machine.
     * Requires a previously received d_act_prof/d_prof to have the full profile payload.
     * @param newWeight The new target weight in grams.
     */
    fun updateActiveProfileWeight(newWeight: Float) {
        val payload = _activeProfileRawPayload ?: return
        val modified = Commands.updateProfileTargetWeight(payload, newWeight)
        _activeProfileTargetWeight.value = newWeight
        sendRaw(Commands.buildUpdateActiveProfileCmd(modified))
    }
    fun requestSettings() { sendRaw(Commands.buildGetSettings()) }
    private fun sendRaw(data: ByteArray) { webSocket?.send(ByteString.of(*data)); DebugLogState.add("WS >>", "frame ${data.size}B") }
}
