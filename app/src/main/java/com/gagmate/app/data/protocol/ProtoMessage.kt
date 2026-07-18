package com.gagmate.app.data.protocol

import com.gagmate.app.data.model.ProfileRef

// Regular classes, not data classes — Kotlin 1.9.x KAPT has a bug
// (KT-55947) where data class subtypes cause stub generation failure.
sealed class ProtoMessage
class SystemStateMsg(val value: SystemState) : ProtoMessage()
class SensorSnapshotMsg(val value: SensorSnapshot) : ProtoMessage()
class ShotSnapshotMsg(val value: ShotSnapshot) : ProtoMessage()
class ProfileDictMsg(val profiles: List<ProfileRef>) : ProtoMessage()
class ShotHistoryIndexMsg(val entries: List<ShotIndexEntry>) : ProtoMessage()
class ActiveProfileMsg(val name: String) : ProtoMessage()
class SettingsMsg(val values: Map<String, String>) : ProtoMessage()
class UnknownMsg(val command: String, val payloadSize: Int) : ProtoMessage()

fun parseProtoMessage(cmd: String, payload: ByteArray): ProtoMessage = when (cmd) {
    Commands.SYS_STATE -> SystemStateMsg(parseSysState(payload))
    Commands.SENSOR_SNAPSHOT -> parseSensorSnapshot(payload)?.let { SensorSnapshotMsg(it) } ?: UnknownMsg(cmd, payload.size)
    Commands.SHOT_SNAPSHOT -> parseShotSnapshot(payload)?.let { ShotSnapshotMsg(it) } ?: UnknownMsg(cmd, payload.size)
    Commands.PROFILE_DICT -> ProfileDictMsg(parseProfileDict(payload))
    Commands.SHOT_HISTORY_INDEX -> ShotHistoryIndexMsg(parseShotIndex(payload))
    Commands.ACTIVE_PROFILE, Commands.PROFILE -> ActiveProfileMsg(extractProfileName(payload) ?: "unknown")
    Commands.SETTINGS -> SettingsMsg(parseSettings(payload))
    else -> UnknownMsg(cmd, payload.size)
}

private fun extractProfileName(payload: ByteArray): String? {
    if (payload.isEmpty() || payload[0] != 0x0a.toByte()) return null
    var pos = 1; var len = 0L; var shift = 0
    while (pos < payload.size) {
        val b = payload[pos].toInt() and 0xFF
        len = len or ((b and 0x7F).toLong() shl shift)
        shift += 7; pos++
        if (b and 0x80 == 0) break
    }
    val end = pos + len.toInt()
    if (end > payload.size) return null
    return payload.copyOfRange(pos, end).decodeToString()
}
