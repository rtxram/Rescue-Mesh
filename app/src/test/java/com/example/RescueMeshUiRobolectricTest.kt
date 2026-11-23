package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RescueMeshUiRobolectricTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `home screen renders SOS button and navigates across prototype tabs and settings`() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sos_hold_button").assertIsDisplayed()
        composeRule.onNodeWithTag("network_status_card").assertIsDisplayed()
        composeRule.onNodeWithTag("send_to_all_toggle_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Send to All Nearby").assertIsDisplayed()
        composeRule.onNodeWithText("BROADCAST").assertIsDisplayed()
        composeRule.onNodeWithText("Alerts every RescueMesh node within mesh range").assertDoesNotExist()

        composeRule.onNodeWithTag("nav_peers").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("flush_queue_button").assertIsDisplayed()
        composeRule.onNodeWithTag("rescan_peers_button").assertIsDisplayed()
        composeRule.onNodeWithTag("active_peers_badge").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_config").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("config_list").performScrollToNode(hasTestTag("scan_contact_qr_button"))
        composeRule.onNodeWithTag("scan_contact_qr_button").assertIsDisplayed()
        composeRule.onNodeWithTag("config_list").performScrollToNode(hasTestTag("download_osm_tiles_button"))
        composeRule.onNodeWithTag("download_osm_tiles_button").assertIsDisplayed()

        composeRule.onNodeWithTag("header_settings_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("relay_toggle").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("unlimited_hops_toggle").performScrollTo().assertIsDisplayed()
    }
}
