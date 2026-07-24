package com.gagmate.app.data.protocol

import java.nio.ByteOrder

import com.gagmate.app.data.model.ProfileRef

/**
 * Decoded data types from Gaggiuino v3 protobuf responses.
 *
 * These are parsed from the raw payload bytes in incoming WebSocket messages.
 */

/** System state from d_sys_state */
data class SystemState(
    val state: Int = 0,
    val mode: Int = 0,
    val uptime: Int = 0,
    val serial: String = "",
    val hwType: String = ""
)

/** Live sensor snapshot from d_sensor_sna */
data class SensorSnapshot(
    val temperature: Float = 0f,
    val targetTemperature: Float = 0f,
    val pressure: Float = 0f,
    val pumpFlow: Float = 0f,
    val weight: Float = 0f,
    val waterLevel: Int = 0
)

/** Live shot data point from d_shot_snap during an active brew */
data class ShotSnapshot(
    val timeInShot: Int = 0,
    val pressure: Float = 0f,
    val flow: Float = 0f,
    val temperature: Float = 0f,
    val weight: Float = 0f,
    val waterPumped: Float = 0f
)

/** Short shot index entry from d_shot_hist_index */
data class ShotIndexEntry(
    val id: Int = 0,
    val profileName: String = "",
    val timestamp: Long = 0L
)

/**
 * Decodes a d_sys_state payload into [SystemState].
 */
fun parseSysState(payload: ByteArray): SystemState {
    var offset = 0; var s = 0; var m = 0; var u = 0; var ser = ""; var hw = ""
    try {
        while (offset < payload.size) {
            val (tw, p1) = readVarint(payload, offset); offset = p1
            val fn = (tw shr 3).toInt(); val wt = (tw and 0x7L).toInt()
            when (wt) {
                0 -> {
                    val (v, p2) = readVarint(payload, offset); offset = p2
                    when (fn) { 1 -> s = v.toInt(); 5 -> m = v.toInt(); 6 -> u = v.toInt() }
                }
                2 -> {
                    val (len, p2) = readVarint(payload, offset); offset = p2
                    val str = payload.copyOfRange(offset, offset + len.toInt()).decodeToString(); offset += len.toInt()
                    when (fn) { 7 -> ser = str; 9 -> hw = str }
                }
                else -> offset = payload.size
            }
        }
    } catch (_: Exception) { }
    return SystemState(s, m, u, ser, hw)
}

/**
 * Decodes a d_sensor_sna payload into [SensorSnapshot].
 */
fun parseSensorSnapshot(payload: ByteArray): SensorSnapshot? {
    try {
        var off = 0; var t = 0f; var tt = 0f; var p = 0f; var wl = 0
        while (off < payload.size) {
            val (tw, p1) = readVarint(payload, off); off = p1
            val fn = (tw shr 3).toInt(); val wt = (tw and 0x7L).toInt()
            if (wt == 5 && (fn == 4 || fn == 5 || fn == 6)) {
                if (off + 4 <= payload.size) {
                    val f = readFloatLE(payload, off)
                    when (fn) { 4 -> t = f; 5 -> tt = f; 6 -> p = f }
                }
                off += 4
            } else if (wt == 0 && fn == 10) {
                val (v, p2) = readVarint(payload, off); off = p2; wl = v.toInt()
            }
        }
        return SensorSnapshot(t, tt, p, 0f, 0f, wl)
    } catch (_: Exception) { return null }
}

/**
 * Decodes a d_shot_snap payload into [ShotSnapshot].
 */
fun parseShotSnapshot(payload: ByteArray): ShotSnapshot? {
    try {
        var off = 0
        var timeInShot = 0
        var pressure = 0f; var flow = 0f; var temperature = 0f; var weight = 0f; var waterPumped = 0f
        while (off < payload.size) {
            val (tw, p1) = readVarint(payload, off); off = p1
            val fn = (tw shr 3).toInt(); val wt = (tw and 0x7L).toInt()
            if (wt == 0) {
                val (v, p2) = readVarint(payload, off); off = p2
                if (fn == 1) timeInShot = v.toInt()
            } else if (wt == 5) {
                if (off + 4 <= payload.size) {
                    val f = readFloatLE(payload, off)
                    when (fn) {
                        2 -> pressure = f; 3 -> flow = f; 4 -> temperature = f
                        5 -> weight = f; 6 -> waterPumped = f
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

/**
 * Decodes a d_shot_hist_index payload into a list of [ShotIndexEntry].
 */
fun parseShotIndex(payload: ByteArray): List<ShotIndexEntry> {
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
                if (ewt == 0) {
                    val (ev, ep2) = readVarint(entry, eo); eo = ep2
                    when (efn) { 1 -> sid = ev.toInt(); 3 -> sts = ev.toLong() }
                } else if (ewt == 2) {
                    val (el, ep2) = readVarint(entry, eo); eo = ep2
                    if (efn == 2) sname = entry.copyOfRange(eo, eo + el.toInt()).decodeToString()
                    eo += el.toInt()
                }
            }
            shots.add(ShotIndexEntry(id = sid, profileName = sname, timestamp = sts))
        }
    } catch (_: Exception) { }
    return shots
}

/**
 * Decodes a d_prof_dict payload into a list of [ProfileRef].
 */
fun parseProfileDict(payload: ByteArray): List<ProfileRef> {
    val profiles = mutableListOf<ProfileRef>()
    var off = 0
    try {
        while (off < payload.size) {
            val (tw, p1) = readVarint(payload, off); off = p1
            val fn = (tw shr 3).toInt(); if (fn != 1) break
            val (len, p2) = readVarint(payload, off); off = p2
            val entry = payload.copyOfRange(off, off + len.toInt()); off += len.toInt()
            var eo = 0; var pid = 0; var pname = ""; var psel = false
            while (eo < entry.size) {
                val (etw, ep) = readVarint(entry, eo); eo = ep
                val efn = (etw shr 3).toInt(); val ewt = (etw and 0x7L).toInt()
                if (ewt == 0) {
                    val (ev, ep2) = readVarint(entry, eo); eo = ep2
                    when (efn) {
                        1 -> pid = ev.toInt()
                        3 -> psel = ev != 0L   // field 3 = selected (bool)
                    }
                } else if (ewt == 2) {
                    val (el, ep2) = readVarint(entry, eo); eo = ep2
                    if (efn == 2) pname = entry.copyOfRange(eo, eo + el.toInt()).decodeToString()
                    eo += el.toInt()
                }
            }
            profiles.add(ProfileRef(id = pid, name = pname, selected = if (psel) "true" else "false"))
        }
    } catch (_: Exception) { }
    return profiles
}

/**
 * Decodes a d_settings payload into a map of setting key-value pairs.
 */
fun parseSettings(payload: ByteArray): Map<String, String> {
    val map = mutableMapOf<String, String>()
    var off = 0
    try {
        while (off < payload.size) {
            val (tw, p1) = readVarint(payload, off); off = p1
            val fn = (tw shr 3).toInt(); val wt = (tw and 0x7L).toInt()
            if (wt == 2) {
                val (len, p2) = readVarint(payload, off); off = p2
                val valBytes = payload.copyOfRange(off, off + len.toInt()); off += len.toInt()
                if (fn == 1) map["profile"] = valBytes.decodeToString()
                else map["f${fn}"] = valBytes.decodeToString()
            }
        }
    } catch (_: Exception) { }
    return map
}

// ── Internal ──────────────────────────────────────────────────


/** Read a little-endian float from a byte array at the given offset. */
private fun readFloatLE(data: ByteArray, offset: Int): Float =
    java.nio.ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat()

private fun readVarint(data: ByteArray, offset: Int): Pair<Long, Int> {
    var value = 0L; var shift = 0; var pos = offset
    while (pos < data.size) {
        val byte = data[pos].toInt() and 0xFF
        value = value or ((byte and 0x7F).toLong() shl shift)
        shift += 7; pos++
        if (byte and 0x80 == 0) break
    }
    return value to pos
}


/**
 * Decode a single phase protobuf into its fields.
 *
 * Matches the Gaggiuino v3 Phase schema:
 *   field 1: type     (varint, 0=pressure / 1=flow)
 *   field 2: name     (string)
 *   field 3: target   (nested message)
 *        sub 1: start  (float)
 *        sub 2: end    (float)
 *        sub 3: curve  (varint enum — FLAT/EASE_IN/...)
 *        sub 4: time   (int32 ms)
 *   field 4: restriction (float)  field 5: waterTemp (float)  field 6: skip (bool)
 *
 * The previous parser wrongly treated the phase *name* (field 2) as the
 * nested Target, which is why phases came back with the right count but all
 * zero values.
 */
private data class PhaseInfo(
    val name: String, val type: String, val start: Float, val end: Float,
    val timeMs: Int, val variation: String
)

/** Map the Gaggiuino CurveType enum ordinal to its string name. */
private fun curveEnumToString(ordinal: Int): String = when (ordinal) {
    0 -> "FLAT"
    1 -> "EASE_IN"
    2 -> "EASE_OUT"
    3 -> "EASE_IN_OUT"
    4 -> "FAST_IN"
    5 -> "FAST_OUT"
    6 -> "FAST_IN_OUT"
    else -> "FLAT"
}

/** Normalize a curve name carried as a protobuf string field into our enum name. */
private fun normalizeCurveName(data: ByteArray, offset: Int, len: Int): String {
    val s = data.copyOfRange(offset, (offset + len).coerceAtMost(data.size)).decodeToString().trim().uppercase()
    return if (s.isBlank()) "LINEAR" else s
}

private fun decodePhaseInfo(data: ByteArray, phaseIndex: Int): PhaseInfo {
    var offset = 0
    var name = ""; var type = "pressure"
    var start = 0f; var end = 0f; var timeMs = 0; var variation = "FLAT"
    while (offset < data.size) {
        val (tag, p1) = readVarint(data, offset); offset = p1
        val fn = (tag shr 3).toInt(); val wt = (tag and 0x7).toInt()
        when {
            fn == 1 && wt == 0 -> { val (v, p2) = readVarint(data, offset); offset = p2; type = if (v.toInt() == 0) "pressure" else "flow" }
            fn == 2 && wt == 2 -> {
                val (len, p2) = readVarint(data, offset); offset = p2
                name = data.copyOfRange(offset, offset + len.toInt()).decodeToString(); offset += len.toInt()
            }
            fn == 3 && wt == 2 -> {
                val (len, p2) = readVarint(data, offset); offset = p2
                val limit = offset + len.toInt()
                while (offset < limit) {
                    val (tt, tp) = readVarint(data, offset); offset = tp
                    val tfn = (tt shr 3).toInt(); val twt = (tt and 0x7).toInt()
                    when {
                        tfn == 1 && twt == 5 -> { if (offset + 4 <= limit) start = readFloatLE(data, offset); offset += 4 }
                        tfn == 2 && twt == 5 -> { if (offset + 4 <= limit) end = readFloatLE(data, offset); offset += 4 }
                        tfn == 3 && twt == 0 -> { val (v, tp2) = readVarint(data, offset); offset = tp2; variation = curveEnumToString(v.toInt()) }
                        tfn == 3 && twt == 2 -> { val (len, tp2) = readVarint(data, offset); offset = tp2; variation = normalizeCurveName(data, offset, len.toInt()); offset += len.toInt() }
                        tfn == 4 && twt == 0 -> { val (v, tp2) = readVarint(data, offset); offset = tp2; timeMs = v.toInt() }
                        twt == 0 -> { val (_, tp2) = readVarint(data, offset); offset = tp2 }
                        twt == 2 -> { val (l, tp2) = readVarint(data, offset); offset = tp2 + l.toInt() }
                        twt == 5 -> offset += 4
                        else -> offset = limit
                    }
                }
            }
            // fields 4 (restriction float), 5 (waterTemp float), 6 (skip bool): skip
            wt == 0 -> { val (_, p2) = readVarint(data, offset); offset = p2 }
            wt == 2 -> { val (l, p2) = readVarint(data, offset); offset = p2 + l.toInt() }
            wt == 5 -> offset += 4
            else -> offset = data.size
        }
    }
    val displayName = name.ifEmpty { "Phase ${phaseIndex + 1}" }
    return PhaseInfo(displayName, type, start, end, timeMs, variation)
}

/** Parse all phases from a d_prof/d_act_prof profile payload into BrewPhase list. */
fun parseProfilePhases(payload: ByteArray): List<com.gagmate.app.data.model.BrewPhase> {
    val result = mutableListOf<com.gagmate.app.data.model.BrewPhase>()
    var offset = 0
    while (offset < payload.size) {
        val (tag, p1) = readVarint(payload, offset); offset = p1
        val fn = (tag shr 3).toInt(); val wt = (tag and 0x7).toInt()
        if (fn == 2 && wt == 2) {
            val (len, p2) = readVarint(payload, offset); offset = p2
            val pb = payload.copyOfRange(offset, offset + len.toInt()); offset += len.toInt()
            val info = decodePhaseInfo(pb, result.size)
            result.add(com.gagmate.app.data.model.BrewPhase(
                name = info.name, type = info.type,
                target = info.end, start = info.start, variation = info.variation,
                time = (info.timeMs / 1000f).coerceAtLeast(0.1f)
            ))
        } else {
            when (wt) {
                0 -> { val (_, p2) = readVarint(payload, offset); offset = p2 }
                2 -> { val (l, p2) = readVarint(payload, offset); offset = p2 + l.toInt() }
                5 -> offset += 4
                else -> offset = payload.size
            }
        }
    }
    return result
}
