package com.gagmate.app.data.api

import okhttp3.*
import com.gagmate.app.data.model.ProfileRef
import okio.ByteString
import java.nio.ByteBuffer
import com.gagmate.app.data.api.ApiDebugLogger
import com.gagmate.app.data.system.DebugLogState
import com.gagmate.app.data.protocol.ProtoCodec

// ── WebSocket client ───────────────────────────────────────────────

class GaggiuinoV3Client(private val host: String, private val port: Int = 80) {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()
    private var webSocket: WebSocket? = null
    private var listener: Listener? = null

    interface Listener {
        fun onConnected(); fun onDisconnected()
        fun onMessage(cmd: String, payload: ByteArray)
        fun onSysState(state: SystemState)
        fun onSensorSnapshot(snapshot: SensorSnapshot)
        fun onProfileDictReceived(profiles: List<ProfileRef>)
        fun onShotHistoryIndex(shorts: List<ShotIndexEntry>)
        fun onSettingsReceived(settings: Map<String, String>)
        fun onProfileUpdated(name: String)
        fun onError(error: String)
        fun onShotSnapshot(snapshot: ShotSnapshot)
    }

    data class SystemState(val state: Int = 0, val mode: Int = 0, val uptime: Int = 0,
                           val serial: String = "", val hwType: String = "")
    data class SensorSnapshot(val temperature: Float = 0f, val targetTemperature: Float = 0f,
                              val pressure: Float = 0f, val waterLevel: Int = 0)
    
    /** Live shot snapshot during brewing. */
    data class ShotSnapshot(
        val timeInShot: Int = 0,
        val pressure: Float = 0f,
        val flow: Float = 0f,
        val temperature: Float = 0f,
        val weight: Float = 0f,
        val waterPumped: Float = 0f
    )
    data class ShotIndexEntry(val id: Int = 0, val profileName: String = "", val timestamp: Long = 0L)

    fun setListener(l: Listener) { listener = l }
    fun connect() {
        val req = Request.Builder().url("ws://$host:$port/ws").build()
        client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, res: Response) { listener?.onConnected() }
            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                ApiDebugLogger.logResponse("WS <<", bytes.size, bytes.toByteArray().joinToString("") { "%02x".format(it.toInt() and 0xFF) })
                ProtoCodec.decodeResponse(data)?.let { (cmd, payload) ->
                    ApiDebugLogger.logResponse("WS << ", payload.size, payload.joinToString("") { "%02x".format(it.toInt() and 0xFF) })
                    DebugLogState.add("WS <<", cmd)
                    when (cmd) {
                        "d_sys_state" -> listener?.onSysState(parseSysState(payload))
                        "d_act_prof" -> handleDActProf(payload)
                        "d_prof" -> handleDActProf(payload)
                        "d_sensor_sna" -> parseSensorSnapshot(payload)?.let { listener?.onSensorSnapshot(it) }
                        "d_prof_dict" -> handleDProfDict(payload)
                        "d_shot_hist_index" -> handleDShotHistIndex(payload)
                        "d_settings" -> handleDSettings(payload)
                        "d_shot_snap" -> parseShotSnapshot(payload)?.let { listener?.onShotSnapshot(it) }
                    }
                    listener?.onMessage(cmd, payload)
                }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, res: Response?) {
                listener?.onError(t.message ?: "WebSocket failure")
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                listener?.onDisconnected()
            }
        })
    }
    fun disconnect() { webSocket?.close(1000, "App closing"); webSocket = null }

    // ── Commands ───────────────────────────────────────────────────

    fun selectProfile(profileId: Int) {
        send("c_upd_act_prof_id", ProtoCodec.encodeVarintField(1, profileId.toLong()))
    }
    fun setFlush(active: Boolean) {
        send("c_opmode", ProtoCodec.encodeVarintField(1, if (active) 2L else 0L))
    }
    fun setDescale() { send("c_opmode", ProtoCodec.encodeVarintField(1, 3L)) }
    fun tareScale() { send("c_opmode", ProtoCodec.encodeVarintField(1, 8L)) }
    fun requestSettings() { send("g_settings") }

    /**
     * Update the active profile's water temperature and/or weight.
     *
     * Builds the profile protobuf dynamically so the name matches
     * whatever profile is currently active.
     *
     * @param name     Profile name from /api/system/status (e.g. "Light Template")
     * @param temp     New water temperature (°C)
     * @param weight   New target weight (grams)
     */
    fun updateActiveProfile(name: String, temp: Float? = null, weight: Float? = null) {
        val profile = buildProfileBytes(name, temp ?: 94f, weight ?: 40f)
        send("c_upd_act_prof", profile)
    }

    // ── Internals ───────────────────────────────────────────────────

    private fun send(cmd: String, payload: ByteArray? = null) {
        val msg = ProtoCodec.buildCommand(cmd, payload)
        val hex = msg.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        ApiDebugLogger.logResponse("WS >> $cmd", msg.size, hex)
        DebugLogState.add("WS >>", cmd)
        webSocket?.send(ByteString.of(*msg))
    }

    /** Dynamically build a profile protobuf from its components. */
    private fun buildProfileBytes(name: String, temp: Float, weight: Float): ByteArray {
        val r = mutableListOf<Byte>()

        // field 1: profile name
        r.addAll(ProtoCodec.encodeLengthDelimited(1, name.toByteArray()).toList())

        // field 2: phases (repeated, pre-encoded with tag+length from template)
        for (phase in cachedPhases) { r.addAll(ProtoCodec.encodeLengthDelimited(2, phase).toList()) }

        // field 3: globalStopConditions
        val gsc = mutableListOf<Byte>()
        gsc.addAll(ProtoCodec.encodeVarintField(1, 60000L).toList())   // time ms
        gsc.addAll(ProtoCodec.encodeFloatField(2, weight).toList())    // weight g
        r.addAll(ProtoCodec.encodeLengthDelimited(3, gsc.toByteArray()).toList())

        // field 4: waterTemperature
        r.addAll(ProtoCodec.encodeFloatField(4, temp).toList())

        // field 5: recipe (empty)
        r.addAll(ProtoCodec.encodeLengthDelimited(5, byteArrayOf()).toList())

        // field 6: extra varint 8
        r.addAll(ProtoCodec.encodeVarintField(6, 8L).toList())

        return r.toByteArray()
    }

    private fun handleDActProf(payload: ByteArray) {
        // payload is the profile protobuf: field 1=name, field 2=phases(repeated)
        var offset = 0
        if (payload.isEmpty() || payload[0] != 0x0a.toByte()) return
        offset += 1  // skip tag
        val (nameLen, _) = readVarint(payload, offset)
        offset += nameLen.toInt()

        val phases = mutableListOf<ByteArray>()
        var name = ""
        while (offset < payload.size && payload[offset] == 0x12.toByte()) {
            offset += 1  // skip phase tag
            val (phaseLen, _) = readVarint(payload, offset)
            val rawPhase = payload.copyOfRange(offset, offset + phaseLen.toInt())
            phases.add(rawPhase)
            offset += phaseLen.toInt()
        }

        if (phases.isNotEmpty()) {
            setProfilePhases(phases)
            listener?.onProfileUpdated(name)
        }
    }

    private fun handleDProfDict(payload: ByteArray) {
        // Field 1 (repeated bytes): profile entries {id, name}
        val profiles = mutableListOf<ProfileRef>()
        var off = 0
        try {
            while (off < payload.size) {
                val (tw, p1) = readVarint(payload, off); off = p1
                val fn = (tw shr 3).toInt(); if (fn != 1) break
                val (len, p2) = readVarint(payload, off); off = p2
                val entry = payload.copyOfRange(off, off + len.toInt()); off += len.toInt()
                // Parse entry: field 1 = id (varint), field 2 = name (string)
                var eo = 0; var pid = 0; var pname = ""
                while (eo < entry.size) {
                    val (etw, ep) = readVarint(entry, eo); eo = ep
                    val efn = (etw shr 3).toInt(); val ewt = (etw and 0x7L).toInt()
                    if (ewt == 0) { val (ev, ep2) = readVarint(entry, eo); eo = ep2
                        if (efn == 1) pid = ev.toInt() }
                    else if (ewt == 2) { val (el, ep2) = readVarint(entry, eo); eo = ep2
                        if (efn == 2) pname = entry.copyOfRange(eo, eo + el.toInt()).decodeToString(); eo += el.toInt() }
                }
                profiles.add(ProfileRef(id = pid, name = pname))
            }
        } catch (_: Exception) { }
        if (profiles.isNotEmpty()) listener?.onProfileDictReceived(profiles)
    }

    private fun handleDShotHistIndex(payload: ByteArray) {
        val shots = mutableListOf<ShotIndexEntry>()
        var off = 0
        try {
            while (off < payload.size) {
                val (tw, p1) = readVarint(payload, off); off = p1
                val fn = (tw shr 3).toInt(); if (fn != 1) break
                val (len, p2) = readVarint(payload, off); off = p2
                val entry = payload.copyOfRange(off, off + len.toInt()); off += len.toInt()
                var eo = 0; var sid = 0; var sname = ""; var sts = 0L
                while (eo < entry.size) {
                    val (etw, ep) = readVarint(entry, eo); eo = ep
                    val efn = (etw shr 3).toInt(); val ewt = (etw and 0x7L).toInt()
                    if (ewt == 0) { val (ev, ep2) = readVarint(entry, eo); eo = ep2
                        when (efn) { 1 -> sid = ev.toInt(); 3 -> sts = ev.toLong() } }
                    else if (ewt == 2) { val (el, ep2) = readVarint(entry, eo); eo = ep2
                        if (efn == 2) sname = entry.copyOfRange(eo, eo + el.toInt()).decodeToString(); eo += el.toInt() }
                }
                shots.add(ShotIndexEntry(id = sid, profileName = sname, timestamp = sts))
            }
        } catch (_: Exception) { }
        if (shots.isNotEmpty()) listener?.onShotHistoryIndex(shots)
    }

    private fun handleDSettings(payload: ByteArray) {
        val map = mutableMapOf<String, String>()
        var off = 0
        try {
            while (off < payload.size) {
                val (tw, p1) = readVarint(payload, off); off = p1
                val fn = (tw shr 3).toInt(); val wt = (tw and 0x7L).toInt()
                if (wt == 2) {
                    val (len, p2) = readVarint(payload, off); off = p2
                    val val_bytes = payload.copyOfRange(off, off + len.toInt()); off += len.toInt()
                    if (fn == 1) map["profile"] = val_bytes.decodeToString()
                    else map["f${fn}"] = val_bytes.decodeToString()
                }
            }
        } catch (_: Exception) { }
        if (map.isNotEmpty()) listener?.onSettingsReceived(map)
    }

    private fun parseSensorSnapshot(payload: ByteArray): SensorSnapshot? {
        try {
            var off = 0; var t = 0f; var tt = 0f; var p = 0f; var wl = 0
            while (off < payload.size) {
                val (tw, p1) = readVarint(payload, off); off = p1
                val fn = (tw shr 3).toInt(); val wt = (tw and 0x7L).toInt()
                if (wt == 5 && (fn == 4 || fn == 5 || fn == 6)) {
                    if (off + 4 <= payload.size) {
                        val f = java.nio.ByteBuffer.wrap(payload, off, 4).getFloat()
                        when (fn) { 4 -> t = f; 5 -> tt = f; 6 -> p = f }
                    }
                    off += 4
                } else if (wt == 0 && fn == 10) {
                    val (v, p2) = readVarint(payload, off); off = p2; wl = v.toInt()
                }
            }
            return SensorSnapshot(t, tt, p, wl)
        } catch (_: Exception) { return null }
    }

    private fun parseShotSnapshot(payload: ByteArray): ShotSnapshot? {
        try {
            var off = 0
            var timeInShot = 0
            var pressure = 0f
            var flow = 0f
            var temperature = 0f
            var weight = 0f
            var waterPumped = 0f
            while (off < payload.size) {
                val (tw, p1) = readVarint(payload, off); off = p1
                val fn = (tw shr 3).toInt(); val wt = (tw and 0x7L).toInt()
                if (wt == 0) {
                    val (v, p2) = readVarint(payload, off); off = p2
                    if (fn == 1) timeInShot = v.toInt()
                } else if (wt == 5) {
                    if (off + 4 <= payload.size) {
                        val f = java.nio.ByteBuffer.wrap(payload, off, 4).getFloat()
                        when (fn) {
                            2 -> pressure = f
                            3 -> flow = f
                            4 -> temperature = f
                            5 -> weight = f
                            6 -> waterPumped = f
                        }
                    }
                    off += 4
                } else if (wt == 2) {
                    val (len, p2) = readVarint(payload, off); off = p2
                    off += len.toInt()
                }
            }
            return ShotSnapshot(timeInShot, pressure, flow, temperature, weight, waterPumped)
        } catch (_: Exception) { return null }
    }

    private fun parseSysState(payload: ByteArray): SystemState {
        var offset = 0; var s = 0; var m = 0; var u = 0; var ser = ""; var hw = ""
        try {
            while (offset < payload.size) {
                val (tw, p1) = readVarint(payload, offset); offset = p1
                val fn = (tw shr 3).toInt(); val wt = (tw and 0x7L).toInt()
                when (wt) {
                    0 -> { val (v, p2) = readVarint(payload, offset); offset = p2
                        when (fn) { 1 -> s = v.toInt(); 5 -> m = v.toInt(); 6 -> u = v.toInt() } }
                    2 -> { val (len, p2) = readVarint(payload, offset); offset = p2
                        val str = payload.copyOfRange(offset, offset + len.toInt()).decodeToString(); offset += len.toInt()
                        when (fn) { 7 -> ser = str; 9 -> hw = str } }
                    else -> offset = payload.size
                }
            }
        } catch (_: Exception) { }
        return SystemState(s, m, u, ser, hw)
    }

    private fun readVarint(data: ByteArray, offset: Int): Pair<Long, Int> {
        var value = 0L; var shift = 0; var pos = offset
        while (pos < data.size) {
            val byte = data[pos].toInt() and 0xFF; value = value or ((byte and 0x7F).toLong() shl shift)
            shift += 7; pos++; if (byte and 0x80 == 0) break
        }
        return value to pos
    }

    companion object {
        @Volatile
        private var cachedPhases: List<ByteArray> = listOf(
            byteArrayOf(0x12, 0x20, 0x12.toByte(), 0x0a, 0x15, 0x00, 0x00, 0xc0.toByte(), 0x3f, 0x18, 0x02, 0x20, 0xe0.toByte(), 0x5d, 0x1d, 0x00, 0x00, 0x80.toByte(), 0x40, 0x22, 0x0d, 0x08, 0x98.toByte(), 0x75, 0x15, 0x00, 0x00, 0x40, 0x40, 0x3d, 0x00, 0x00, 0x48, 0x42),
            byteArrayOf(0x12, 0x18, 0x08, 0x01, 0x12, 0x0f, 0x0d, 0x00, 0x00, 0xc0.toByte(), 0x3f, 0x15, 0x00, 0x00, 0xf0.toByte(), 0x40, 0x18, 0x02, 0x20, 0xc0.toByte(), 0x3e, 0x22, 0x03, 0x08, 0xc0.toByte(), 0x3e),
            byteArrayOf(0x12, 0x19, 0x08, 0x01, 0x12, 0x0e, 0x0d, 0x00, 0x00, 0xf0.toByte(), 0x40, 0x15, 0x00, 0x00, 0xb0.toByte(), 0x40, 0x20, 0x80.toByte(), 0xfa.toByte(), 0x01, 0x1d, 0x00, 0x00, 0x40, 0x40, 0x22, 0x00),
        )

        /** Update the phase data used when building profile protobufs. */
        fun setProfilePhases(phases: List<ByteArray>) {
            cachedPhases = phases
        }
    }
}
