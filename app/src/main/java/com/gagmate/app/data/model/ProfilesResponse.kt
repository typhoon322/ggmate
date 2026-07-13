package com.gagmate.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * Response wrapper from ggboard for profile list endpoint.
 */
data class ProfilesResponse(
    @SerializedName("profiles")
    val profiles: List<ShotProfile> = emptyList(),

    @SerializedName("count")
    val count: Int = 0,

    @SerializedName("active")
    val activeProfile: String? = null
)

/**
 * Generic API response wrapper used by ggboard.
 */
data class ApiResponse<T>(
    @SerializedName("success")
    val success: Boolean = false,

    @SerializedName("message")
    val message: String = "",

    @SerializedName("data")
    val data: T? = null
)
