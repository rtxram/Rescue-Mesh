package com.example.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
sealed interface RescueRoute {
    @Serializable
    data object Home : RescueRoute

    @Serializable
    data class SenderSosTrigger(val sessionId: String) : RescueRoute

    @Serializable
    data class SosAlert(val sessionId: String) : RescueRoute

    @Serializable
    data class SessionActive(val sessionId: String) : RescueRoute

    @Serializable
    data object NearbyPeers : RescueRoute

    @Serializable
    data object Config : RescueRoute

    @Serializable
    data object EmergencyInbox : RescueRoute

    @Serializable
    data object Settings : RescueRoute
}
