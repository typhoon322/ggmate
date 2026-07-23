package com.gagmate.app.data.protocol

import java.nio.ByteBuffer
 
/**
 * Gaggiuino v3 WebSocket command definitions.
 *
 * Commands sent TO the machine (c_*) and received FROM the machine (d_*).
 */
object Commands {

    // ── Outgoing (client → machine) ────────────────────────────────

    /** Select a profile by its numeric ID. Payload: varint field 1 = profileId */
    const val SELECT_PROFILE = "c_upd_act_prof_id"

    /** Update the active profile's parameters. Payload: full profile protobuf */
    const val UPDATE_ACTIVE_PROFILE = "c_upd_act_prof"

    /** Set operation mode. 1=normal, 2=flush, 3=descale, 8=tare */
    const val SET_OP_MODE = "c_opmode"

    /** Request current settings from the machine. No payload. */
    /** Request a specific profile's full data (with phases). */
    const val GET_PROFILE = "g_prof"

    const val GET_SETTINGS = "g_settings"

    /** Tare scales command (c_tare_pend — different from c_opmode mode=8) */
    const val TARE_PEND = "c_tare_pend"

    // ── Incoming (machine → client) ───────────────────────────────

    const val SYS_STATE = "d_sys_state"
    const val ACTIVE_PROFILE = "d_act_prof"
    const val PROFILE = "d_prof"
    const val SENSOR_SNAPSHOT = "d_sensor_snap"
    const val PROFILE_DICT = "d_prof_dict"
    const val SHOT_HISTORY_INDEX = "d_shot_hist_index"
    const val SETTINGS = "d_settings"
    const val SHOT_SNAPSHOT = "d_shot_snap"

    // ── Command builders ──────────────────────────────────────────

    /**
     * Build payload for SELECT_PROFILE (c_upd_act_prof_id).
     * @param profileId The numeric profile ID to select.
     */
    fun selectProfilePayload(profileId: Int): ByteArray =
        ProtoCodec.encodeVarintField(1, profileId.toLong())

    /**
     * Build payload for SET_OP_MODE (c_opmode).
     * @param mode 1=normal, 2=flush, 3=descale, 8=tare
     */
    fun opModePayload(mode: Int): ByteArray =
        ProtoCodec.encodeVarintField(1, mode.toLong())

    /** Flush mode constant */
    const val MODE_FLUSH = 2

    /** Descale mode constant */
    const val MODE_DESCALE = 3

    /** Standby mode — halts any running operation (incl. a brew). */
    const val MODE_STANDBY = 0

    /**
     * Normal/brew-ready mode. Entering this arms the machine so a shot can be
     * pulled on the brew switch. There is no dedicated "start shot" WS command
     * in the Gaggiuino v3 opmode set, so this is the closest real control.
     */
    const val MODE_NORMAL = 1

    // ── Full command builders ─────────────────────────────────────

    /** Build the complete command frame for profile selection. */
    fun buildSelectProfile(profileId: Int): ByteArray =
        ProtoCodec.buildCommand(SELECT_PROFILE, selectProfilePayload(profileId))

    /** Build the complete command frame for setting operation mode. */
    fun buildOpMode(mode: Int): ByteArray =
        ProtoCodec.buildCommand(SET_OP_MODE, opModePayload(mode))

    /** Build the complete command frame for getting settings. */
    /** Build the complete command frame for getting a specific profile. */
    fun buildGetProfile(profileId: Int): ByteArray {
        val payload = ProtoCodec.encodeVarintField(1, profileId.toLong())
        return ProtoCodec.buildCommand(GET_PROFILE, payload)
    }

    fun buildGetSettings(): ByteArray =
        ProtoCodec.buildCommand(GET_SETTINGS)
 
    /**
     * Build the complete command frame for TARE (c_tare_pend).
     * Payload: field 2 varint = 1 (encoded as 1001)
     */
    fun buildTareCommand(): ByteArray {
        val payload = ProtoCodec.encodeVarintField(2, 1L)
        return ProtoCodec.buildCommand(TARE_PEND, payload)
    }

    /**
     * Build the complete command frame for setting normal op mode (c_opmode with no payload).
     * This cancels descale/flush and returns to standby mode.
     */
    fun buildNormalOpMode(): ByteArray =
        ProtoCodec.buildCommand(SET_OP_MODE, null)

    /** Build the complete command frame for updating the active profile (c_upd_act_prof). */
    fun buildUpdateActiveProfileCmd(payload: ByteArray): ByteArray =
        ProtoCodec.buildCommand(UPDATE_ACTIVE_PROFILE, payload)

    /**
     * Read the temperature setpoint (profile field 4, wire type 5 float) from a
     * profile protobuf payload. Returns null if the field is not found.
     */
    fun readProfileTemperature(payload: ByteArray): Float? {
        val tag4wt5 = ((4 shl 3) or 5).toByte()  // 0x25
        var i = 0
        while (i < payload.size) {
            if (payload[i] == tag4wt5 && i + 5 <= payload.size) {
                return ByteBuffer.wrap(payload, i + 1, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).float
            }
            val tag = payload[i].toInt() and 0xFF
            val wt = tag and 0x07
            i++
            when (wt) {
                0 -> {
                    while (i < payload.size && (payload[i].toInt() and 0x80) != 0) i++
                    i++
                }
                2 -> {
                    var len = 0L; var shift = 0
                    while (i < payload.size) {
                        val b = payload[i].toInt() and 0xFF
                        len = len or ((b and 0x7F).toLong() shl shift)
                        shift += 7; i++
                        if (b and 0x80 == 0) break
                    }
                    i += len.toInt()
                }
                5 -> i += 4
                else -> i = payload.size
            }
        }
        return null
    }

    /**
     * Read the target weight (profile field 3, inner field 2, wire type 5 float) from a
     * profile protobuf payload. Returns null if the field is not found.
     */
    fun readProfileTargetWeight(payload: ByteArray): Float? {
        val g = extractProfileGlobals(payload)
        return g.targetWeight
    }

    /**
     * All global setpoints read from a profile protobuf payload:
     *  - waterTemperature   (field 4, wt5 float)            °C
     *  - targetWeight       (field 3 inner field 2, wt5)    g   — global stop condition
     *  - targetPumpPressure (field 3 inner field 3, wt5)    bar
     *  - targetPumpFlow     (field 3 inner field 4, wt5)     ml/s
     *  - targetTime         (field 3 inner field 5, wt5)    s
     * Any absent field yields null so callers can keep the previously known value.
     */
    data class ProfileGlobals(
        val waterTemperature: Float? = null,
        val targetWeight: Float? = null,
        val targetPumpPressure: Float? = null,
        val targetPumpFlow: Float? = null,
        val targetTime: Float? = null
    )

    fun extractProfileGlobals(payload: ByteArray): ProfileGlobals {
        var waterTemp: Float? = null
        var targetWeight: Float? = null
        var targetPressure: Float? = null
        var targetFlow: Float? = null
        var targetTime: Float? = null
        var i = 0
        while (i < payload.size) {
            val tag = payload[i].toInt() and 0xFF
            // field 4, wire type 5 → waterTemperature (float)
            if (tag == ((4 shl 3) or 5)) {
                if (i + 5 <= payload.size)
                    waterTemp = ByteBuffer.wrap(payload, i + 1, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).float
                i += 5; continue
            }
            // field 3, wire type 2 → GlobalStopConditions (nested message)
            if (tag == ((3 shl 3) or 2)) {
                i++
                var len = 0L; var shift = 0
                while (i < payload.size) {
                    val b = payload[i].toInt() and 0xFF
                    len = len or ((b and 0x7F).toLong() shl shift); shift += 7; i++
                    if (b and 0x80 == 0) break
                }
                val end = i + len.toInt()
                while (i < end) {
                    val innerTag = payload[i].toInt() and 0xFF
                    val fn = innerTag shr 3
                    val wt = innerTag and 0x07
                    i++
                    if (wt == 5) {
                        if (i + 4 <= end) {
                            val f = ByteBuffer.wrap(payload, i, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).float
                            when (fn) {
                                2 -> targetWeight = f
                                3 -> targetPressure = f
                                4 -> targetFlow = f
                                5 -> targetTime = f
                            }
                        }
                        i += 4
                    } else if (wt == 0) {
                        while (i < end && (payload[i].toInt() and 0x80) != 0) i++
                        i++
                    } else if (wt == 2) {
                        var l = 0L; var s = 0
                        while (i < end) {
                            val b = payload[i].toInt() and 0xFF
                            l = l or ((b and 0x7F).toLong() shl s); s += 7; i++
                            if (b and 0x80 == 0) break
                        }
                        i += l.toInt()
                    } else { i = end }
                }
                continue
            }
            // skip any other field
            val wt = tag and 0x07
            i++
            when (wt) {
                0 -> { while (i < payload.size && (payload[i].toInt() and 0x80) != 0) i++; i++ }
                2 -> {
                    var len = 0L; var shift = 0
                    while (i < payload.size) {
                        val b = payload[i].toInt() and 0xFF
                        len = len or ((b and 0x7F).toLong() shl shift); shift += 7; i++
                        if (b and 0x80 == 0) break
                    }
                    i += len.toInt()
                }
                5 -> i += 4
                else -> i = payload.size
            }
        }
        return ProfileGlobals(waterTemp, targetWeight, targetPressure, targetFlow, targetTime)
    }

    /**
     * Modify the temperature setpoint (profile field 4, wire type 5 float) in a profile protobuf payload.
     * Returns a new ByteArray with the temperature bytes replaced.
     */
    fun updateProfileTemperature(payload: ByteArray, newTemp: Float): ByteArray {
        val tag4wt5 = ((4 shl 3) or 5).toByte()  // 0x25
        val tempBytes = ByteBuffer.allocate(4).putFloat(newTemp).array()
        val result = payload.toMutableList()
        var i = 0
        while (i < result.size) {
            if (result[i] == tag4wt5 && i + 5 <= result.size) {
                for (j in 0..3) result[i + 1 + j] = tempBytes[j]
                break
            }
            // Skip this field
            val tag = result[i].toInt() and 0xFF
            val wt = tag and 0x07
            i++
            when (wt) {
                0 -> {
                    while (i < result.size && (result[i].toInt() and 0x80) != 0) i++
                    i++
                }
                2 -> {
                    var len = 0L; var shift = 0
                    while (i < result.size) {
                        val b = result[i].toInt() and 0xFF
                        len = len or ((b and 0x7F).toLong() shl shift)
                        shift += 7; i++
                        if (b and 0x80 == 0) break
                    }
                    i += len.toInt()
                }
                5 -> i += 4
                else -> i = result.size
            }
        }
        return result.toByteArray()
    }

    /**
     * Modify the target weight (profile field 3, inner field 2 wire type 5 float) 
     * in a profile protobuf payload.
     * Returns a new ByteArray with the weight bytes replaced.
     */
    fun updateProfileTargetWeight(payload: ByteArray, newWeight: Float): ByteArray {
        val tag3wt2 = ((3 shl 3) or 2).toByte()  // 0x1a
        val subTag2wt5 = ((2 shl 3) or 5).toByte()  // 0x15
        val weightBytes = ByteBuffer.allocate(4).putFloat(newWeight).array()
        val result = payload.toMutableList()
        var i = 0
        while (i < result.size) {
            if (result[i] == tag3wt2) {
                i++
                var len = 0L; var shift = 0
                while (i < result.size) {
                    val b = result[i].toInt() and 0xFF
                    len = len or ((b and 0x7F).toLong() shl shift)
                    shift += 7; i++
                    if (b and 0x80 == 0) break
                }
                val fieldEnd = i + len.toInt()
                while (i < fieldEnd) {
                    if (result[i] == subTag2wt5 && i + 5 <= fieldEnd) {
                        for (j in 0..3) result[i + 1 + j] = weightBytes[j]
                        break
                    }
                    val innerTag = result[i].toInt() and 0xFF
                    val innerWt = innerTag and 0x07
                    i++
                    when (innerWt) {
                        0 -> {
                            while (i < fieldEnd && (result[i].toInt() and 0x80) != 0) i++
                            i++
                        }
                        2 -> {
                            var ilen = 0L; var ishift = 0
                            while (i < fieldEnd) {
                                val ib = result[i].toInt() and 0xFF
                                ilen = ilen or ((ib and 0x7F).toLong() shl ishift)
                                ishift += 7; i++
                                if (ib and 0x80 == 0) break
                            }
                            i += ilen.toInt()
                        }
                        5 -> i += 4
                        else -> i = fieldEnd
                    }
                }
                break
            }
            // Skip this field
            val tag = result[i].toInt() and 0xFF
            val wt = tag and 0x07
            i++
            when (wt) {
                0 -> {
                    while (i < result.size && (result[i].toInt() and 0x80) != 0) i++
                    i++
                }
                2 -> {
                    var len = 0L; var shift = 0
                    while (i < result.size) {
                        val b = result[i].toInt() and 0xFF
                        len = len or ((b and 0x7F).toLong() shl shift)
                        shift += 7; i++
                        if (b and 0x80 == 0) break
                    }
                    i += len.toInt()
                }
                5 -> i += 4
                else -> i = result.size
            }
        }
        return result.toByteArray()
    }

    /** Tare scales constant (deprecated — use buildTareCommand() instead) */
    @Deprecated("Use buildTareCommand() with c_tare_pend command")
    const val MODE_TARE = 8
}
