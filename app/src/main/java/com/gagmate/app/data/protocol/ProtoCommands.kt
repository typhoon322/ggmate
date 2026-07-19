package com.gagmate.app.data.protocol

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

    // ── Incoming (machine → client) ───────────────────────────────

    const val SYS_STATE = "d_sys_state"
    const val ACTIVE_PROFILE = "d_act_prof"
    const val PROFILE = "d_prof"
    const val SENSOR_SNAPSHOT = "d_sensor_sna"
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

    /** Tare scales constant */
    const val MODE_TARE = 8

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
}
