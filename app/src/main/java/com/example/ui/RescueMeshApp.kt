package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.RescueMeshApplication
import com.example.domain.model.SessionStatus
import com.example.ui.components.HomeFooterTab
import com.example.ui.components.PrototypeBottomBar
import com.example.ui.components.PrototypeTopAppBar
import com.example.ui.navigation.RescueRoute
import com.example.ui.screens.ConfigScreen
import com.example.ui.screens.EmergencyInboxScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.NearbyPeersScreen
import com.example.ui.screens.SenderSosTriggerScreen
import com.example.ui.screens.SessionActiveScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SosAlertScreen
import com.example.ui.theme.LocalRescueColors
import com.example.ui.viewmodel.ActiveEmergencyViewModel
import com.example.ui.viewmodel.HomeViewModel
import com.example.ui.viewmodel.InboxViewModel
import com.example.ui.viewmodel.PeersViewModel
import com.example.ui.viewmodel.RescueMeshViewModelFactory
import com.example.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

@Composable
fun RescueMeshApp(
    app: RescueMeshApplication,
    widthSizeClass: WindowWidthSizeClass,
    initialSessionId: String?,
    showArmedSosDialog: Boolean,
    onDismissArmedSosDialog: () -> Unit
) {
    val rescue = LocalRescueColors.current
    val factory = RescueMeshViewModelFactory(app)
    val homeVm: HomeViewModel = viewModel(factory = factory)
    val activeVm: ActiveEmergencyViewModel = viewModel(factory = factory)
    val inboxVm: InboxViewModel = viewModel(factory = factory)
    val peersVm: PeersViewModel = viewModel(factory = factory)
    val settingsVm: SettingsViewModel = viewModel(factory = factory)

    val homeState by homeVm.uiState.collectAsStateWithLifecycle()
    val activeState by activeVm.uiState.collectAsStateWithLifecycle()
    val inboxSessions by inboxVm.sessions.collectAsStateWithLifecycle()
    val peersState by peersVm.uiState.collectAsStateWithLifecycle()
    val settingsState by settingsVm.uiState.collectAsStateWithLifecycle()

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    val unreadCount = inboxSessions.count { it.unread }
    val activeIncomingAlert = inboxSessions.firstOrNull {
        !it.isOutgoing && it.status == SessionStatus.ACTIVE && !it.sessionAccepted
    }
    val anyActiveAccepted = inboxSessions.firstOrNull {
        it.status == SessionStatus.ACTIVE && it.sessionAccepted
    } ?: homeState.activeOutgoingSession

    fun navigateTopLevel(route: RescueRoute) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(Unit) {
        homeVm.navigateToSosTrigger.collect { sessionId ->
            navController.navigate(RescueRoute.SenderSosTrigger(sessionId)) {
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(Unit) {
        app.meshCoordinator.incomingAlertEvents.collect { incomingSession ->
            if (!incomingSession.isOutgoing && !incomingSession.sessionAccepted) {
                navController.navigate(RescueRoute.SosAlert(incomingSession.sessionId)) {
                    launchSingleTop = true
                }
            }
        }
    }

    LaunchedEffect(initialSessionId) {
        if (!initialSessionId.isNullOrBlank()) {
            val s = app.repository.getSession(initialSessionId)
            val route: RescueRoute = when {
                s == null -> RescueRoute.EmergencyInbox
                !s.isOutgoing && !s.sessionAccepted -> RescueRoute.SosAlert(s.sessionId)
                s.isOutgoing && !s.sessionAccepted -> RescueRoute.SenderSosTrigger(s.sessionId)
                else -> RescueRoute.SessionActive(s.sessionId)
            }
            navController.navigate(route) { launchSingleTop = true }
        }
    }

    val isHome = currentDest?.hasRoute<RescueRoute.Home>() == true
    val isNodes = currentDest?.hasRoute<RescueRoute.NearbyPeers>() == true
    val isConfig = currentDest?.hasRoute<RescueRoute.Config>() == true
    val isSosTrigger = currentDest?.hasRoute<RescueRoute.SenderSosTrigger>() == true
    val isSosAlert = currentDest?.hasRoute<RescueRoute.SosAlert>() == true
    val isSessionActive = currentDest?.hasRoute<RescueRoute.SessionActive>() == true
    val isInbox = currentDest?.hasRoute<RescueRoute.EmergencyInbox>() == true
    val isSettings = currentDest?.hasRoute<RescueRoute.Settings>() == true

    val showFooterTabs = isHome || isNodes || isConfig
    val useRail = widthSizeClass != WindowWidthSizeClass.Compact

    if (showArmedSosDialog) {
        AlertDialog(
            onDismissRequest = onDismissArmedSosDialog,
            title = { Text("Confirm Emergency SOS?") },
            text = {
                Text(
                    "RescueMesh shortcut armed. Confirm to acquire GNSS coordinates and spray an encrypted Priority-1 SOS across nearby mesh nodes."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDismissArmedSosDialog()
                        homeVm.activateSos()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = rescue.sosCrimson),
                    modifier = Modifier.testTag("confirm_shortcut_sos_button")
                ) {
                    Text("Broadcast SOS")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismissArmedSosDialog,
                    modifier = Modifier.testTag("cancel_shortcut_sos_button")
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(280.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Rescue Mesh",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "NODE #${homeState.localPeerId.take(4).uppercase()} · ${homeState.deviceAlias}",
                        style = MaterialTheme.typography.labelSmall,
                        color = rescue.emeraldActive
                    )
                }
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                NavigationDrawerItem(
                    label = { Text("Home Beacon") },
                    icon = { Icon(Icons.Filled.RadioButtonChecked, contentDescription = null) },
                    selected = isHome,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        navigateTopLevel(RescueRoute.Home)
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                anyActiveAccepted?.let { active ->
                    NavigationDrawerItem(
                        label = { Text("Active Session") },
                        icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = rescue.sosCrimson) },
                        selected = isSessionActive || isSosTrigger,
                        onClick = {
                            coroutineScope.launch { drawerState.close() }
                            val dest = if (active.isOutgoing && !active.sessionAccepted) {
                                RescueRoute.SenderSosTrigger(active.sessionId)
                            } else {
                                RescueRoute.SessionActive(active.sessionId)
                            }
                            navController.navigate(dest) { launchSingleTop = true }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }

                activeIncomingAlert?.let { incoming ->
                    NavigationDrawerItem(
                        label = { Text("Incoming SOS Alert") },
                        icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = rescue.sosCrimson) },
                        selected = isSosAlert,
                        onClick = {
                            coroutineScope.launch { drawerState.close() }
                            navController.navigate(RescueRoute.SosAlert(incoming.sessionId)) { launchSingleTop = true }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }

                NavigationDrawerItem(
                    label = { Text("Emergency Inbox") },
                    icon = { Icon(Icons.Filled.Inbox, contentDescription = null) },
                    badge = if (unreadCount > 0) ({ Badge { Text("$unreadCount") } }) else null,
                    selected = isInbox,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        navigateTopLevel(RescueRoute.EmergencyInbox)
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                NavigationDrawerItem(
                    label = { Text("Mesh Nodes") },
                    icon = { Icon(Icons.Filled.Hub, contentDescription = null) },
                    badge = if (homeState.nearbyPeerCount > 0) ({ Badge { Text("${homeState.nearbyPeerCount}") } }) else null,
                    selected = isNodes,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        navigateTopLevel(RescueRoute.NearbyPeers)
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                NavigationDrawerItem(
                    label = { Text("Config (QR & Maps)") },
                    icon = { Icon(Icons.Filled.Tune, contentDescription = null) },
                    selected = isConfig,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        navigateTopLevel(RescueRoute.Config)
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                NavigationDrawerItem(
                    label = { Text("Settings") },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    selected = isSettings,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        navigateTopLevel(RescueRoute.Settings)
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                if (!isSosAlert) {
                    val title = when {
                        isSosTrigger -> "SOS Active"
                        isSessionActive -> "Session Active"
                        isNodes -> "Nodes"
                        isConfig -> "Config"
                        isInbox -> "Emergency Inbox"
                        isSettings -> "Settings"
                        else -> "Rescue Mesh"
                    }
                    val showDot = isSosTrigger || (isSessionActive && activeState.session?.isOutgoing == false)
                    PrototypeTopAppBar(
                        title = title,
                        showLiveDot = showDot,
                        unreadInboxCount = unreadCount,
                        onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                        onOpenSettings = { navigateTopLevel(RescueRoute.Settings) }
                    )
                }
            },
            bottomBar = {
                if (showFooterTabs && !useRail) {
                    val selectedTab = when {
                        isNodes -> HomeFooterTab.NODES
                        isConfig -> HomeFooterTab.CONFIG
                        else -> HomeFooterTab.HOME
                    }
                    PrototypeBottomBar(
                        selectedTab = selectedTab,
                        nearbyCount = homeState.nearbyPeerCount,
                        onSelectTab = { tab ->
                            when (tab) {
                                HomeFooterTab.HOME -> navigateTopLevel(RescueRoute.Home)
                                HomeFooterTab.NODES -> navigateTopLevel(RescueRoute.NearbyPeers)
                                HomeFooterTab.CONFIG -> navigateTopLevel(RescueRoute.Config)
                            }
                        }
                    )
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail && showFooterTabs) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                        HomeFooterTab.entries.forEach { tab ->
                            val selected = when (tab) {
                                HomeFooterTab.HOME -> isHome
                                HomeFooterTab.NODES -> isNodes
                                HomeFooterTab.CONFIG -> isConfig
                            }
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    when (tab) {
                                        HomeFooterTab.HOME -> navigateTopLevel(RescueRoute.Home)
                                        HomeFooterTab.NODES -> navigateTopLevel(RescueRoute.NearbyPeers)
                                        HomeFooterTab.CONFIG -> navigateTopLevel(RescueRoute.Config)
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag(tab.tag)
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = RescueRoute.Home,
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 840.dp)
                    ) {
                        composable<RescueRoute.Home> {
                            HomeScreen(
                                uiState = homeState,
                                onTriggerSos = homeVm::activateSos,
                                onOpenActiveSession = { sid ->
                                    val s = homeState.activeOutgoingSession
                                    if (s != null && s.sessionAccepted) {
                                        navController.navigate(RescueRoute.SessionActive(sid))
                                    } else {
                                        navController.navigate(RescueRoute.SenderSosTrigger(sid))
                                    }
                                },
                                onOpenConfig = { navigateTopLevel(RescueRoute.Config) },
                                onStartMesh = homeVm::startMeshIfNeeded,
                                onToggleSendToAll = homeVm::toggleSendToAll,
                                onDismissBanner = homeVm::clearHomeBanner
                            )
                        }

                        composable<RescueRoute.SenderSosTrigger> { entry ->
                            val args = entry.toRoute<RescueRoute.SenderSosTrigger>()
                            LaunchedEffect(args.sessionId) {
                                activeVm.bindSession(args.sessionId, isActiveSessionScreen = false)
                            }
                            SenderSosTriggerScreen(
                                uiState = activeState,
                                onSessionAccepted = { sid ->
                                    navController.navigate(RescueRoute.SessionActive(sid)) {
                                        popUpTo(RescueRoute.SenderSosTrigger(args.sessionId)) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                },
                                onCancelSos = {
                                    activeVm.resolveEmergency(cancelled = true) {
                                        navController.popBackStack()
                                    }
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }

                        composable<RescueRoute.SosAlert> { entry ->
                            val args = entry.toRoute<RescueRoute.SosAlert>()
                            LaunchedEffect(args.sessionId) {
                                activeVm.bindSession(args.sessionId, isActiveSessionScreen = false)
                            }
                            SosAlertScreen(
                                uiState = activeState,
                                offlineMapManager = app.offlineMapManager,
                                onAcknowledgeAndAccept = {
                                    activeVm.acknowledgeAndAcceptSos { acceptedSid ->
                                        navController.navigate(RescueRoute.SessionActive(acceptedSid)) {
                                            popUpTo(RescueRoute.SosAlert(args.sessionId)) { inclusive = true }
                                            launchSingleTop = true
                                        }
                                    }
                                },
                                onDeclineAndBack = { navController.popBackStack() }
                            )
                        }

                        composable<RescueRoute.SessionActive> { entry ->
                            val args = entry.toRoute<RescueRoute.SessionActive>()
                            LaunchedEffect(args.sessionId) {
                                activeVm.bindSession(args.sessionId, isActiveSessionScreen = true)
                            }
                            SessionActiveScreen(
                                uiState = activeState,
                                offlineMapManager = app.offlineMapManager,
                                resolveImageFile = activeVm::resolveImageFile,
                                onBack = { navController.popBackStack() },
                                onToggleMic = activeVm::toggleMic,
                                onToggleSpeakerListen = activeVm::toggleSpeakerListen,
                                onSendReply = activeVm::sendReply,
                                onSendPhoto = activeVm::sendCameraImage,
                                onRefreshLocation = activeVm::refreshLocationUpdate,
                                onResolveSession = {
                                    activeVm.resolveEmergency(cancelled = false) {
                                        navController.popBackStack()
                                    }
                                },
                                onRemoteSessionClosed = activeVm::onRemoteSessionClosed,
                                onDismissBanner = activeVm::clearBanner
                            )
                        }

                        composable<RescueRoute.NearbyPeers> {
                            NearbyPeersScreen(
                                uiState = peersState,
                                onFlushQueue = peersVm::flushQueueNow,
                                onRescan = peersVm::rescanPeers,
                                onVerifyNoise = peersVm::verifyNoiseHandshake
                            )
                        }

                        composable<RescueRoute.Config> {
                            ConfigScreen(
                                uiState = settingsState,
                                onPairContactInput = settingsVm::pairFromQrOrManualInput,
                                onDeleteContact = settingsVm::deleteContact,
                                onDownloadOfflineMap = settingsVm::downloadOfflineMapRegion,
                                onClearOfflineMap = settingsVm::clearOfflineMapCache,
                                onTogglePreferInternetAudio = settingsVm::setPreferInternetAudio,
                                onSaveSignalingUrl = settingsVm::setSignalingServerUrl,
                                onDismissBanner = settingsVm::clearBanner
                            )
                        }

                        composable<RescueRoute.EmergencyInbox> {
                            EmergencyInboxScreen(
                                sessions = inboxSessions,
                                onOpenSession = { session ->
                                    val dest = when {
                                        !session.isOutgoing && !session.sessionAccepted ->
                                            RescueRoute.SosAlert(session.sessionId)
                                        session.isOutgoing && !session.sessionAccepted ->
                                            RescueRoute.SenderSosTrigger(session.sessionId)
                                        else ->
                                            RescueRoute.SessionActive(session.sessionId)
                                    }
                                    navController.navigate(dest)
                                },
                                onAcknowledgeSession = { sid ->
                                    inboxVm.acknowledgeSession(sid)
                                    navController.navigate(RescueRoute.SessionActive(sid))
                                }
                            )
                        }

                        composable<RescueRoute.Settings> {
                            SettingsScreen(
                                uiState = settingsState,
                                onUpdateAlias = settingsVm::setAlias,
                                onToggleRelay = settingsVm::setRelayParticipation,
                                onUpdateMaxHop = settingsVm::setMaxHopCount,
                                onToggleShakeSos = settingsVm::setShakeSos,
                                onToggleVolumeShortcut = settingsVm::setVolumeShortcut,
                                onToggleAutoRecordVoiceOnSos = settingsVm::setAutoRecordVoiceOnSos,
                                onToggleAutoDownloadAudio = settingsVm::setAutoDownloadAudio,
                                onToggleAutoPlaySpeakerOnSession = settingsVm::setAutoPlaySpeakerOnSession,
                                onToggleSendToAll = settingsVm::setSendToAllNodes,
                                onPermissionsGranted = homeVm::startMeshIfNeeded
                            )
                        }
                    }
                }
            }
        }
    }
}
