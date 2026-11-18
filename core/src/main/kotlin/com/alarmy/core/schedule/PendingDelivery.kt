package com.alarmy.core.schedule

import kotlinx.serialization.Serializable

@Serializable
data class PendingDelivery(
    val alarmId: String,
    val key: String,
    val fireAtMillis: Long,
    val watchdogIndex: Int = 0,
    val scheduledAtMillis: Long = fireAtMillis
)
