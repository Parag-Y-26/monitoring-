package com.edgepulse.monitor

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.edgepulse.monitor.security.LocalSafFolder
import com.edgepulse.monitor.ui.EdgePulseScreen
import com.edgepulse.monitor.ui.EdgePulseTheme
import com.edgepulse.monitor.ui.MonitoringScreen
import com.edgepulse.monitor.ui.ItemViewModel
import com.edgepulse.monitor.ui.ItemSetupScreen
import com.edgepulse.monitor.ui.ApiUsageScreen
import com.edgepulse.monitor.ui.EdgeAppShell
import com.edgepulse.monitor.ui.EdgeDestination
import com.edgepulse.monitor.monitoring.MonitoringForegroundService
import com.edgepulse.monitor.monitoring.MonitoringRuntime
import com.edgepulse.monitor.ui.SetupViewModel
import com.edgepulse.monitor.ui.auth.CloudSetupActions
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var monitoringRuntime: MonitoringRuntime
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = ViewModelProvider(this)[SetupViewModel::class.java]
        val itemModel = ViewModelProvider(this)[ItemViewModel::class.java]
        setContent {
            val state by model.state.collectAsState()
            val monitor by monitoringRuntime.state.collectAsState()
            val items by itemModel.state.collectAsState()
            val trainedLabels = remember { assets.open("models/coco_labels.txt").bufferedReader().use { it.readLines().filter(String::isNotBlank) } }
            val labels = remember(state.selectedTarget?.name) { trainedLabels + (state.selectedTarget?.name ?: "Reference item") }
            var destinationName by rememberSaveable { mutableStateOf(EdgeDestination.MONITOR.name) }
            val destination = EdgeDestination.valueOf(destinationName)
            BackHandler(enabled = destination != EdgeDestination.MONITOR) { destinationName = EdgeDestination.MONITOR.name }
            EdgePulseTheme {
                DisposableEffect(monitor.active || monitor.starting) {
                    if (monitor.active || monitor.starting) enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                        navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
                    onDispose { enableEdgeToEdge() }
                }
                val cameraPhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview(), itemModel::acceptCameraPhoto)
                val photoPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (granted) {
                        try { cameraPhoto.launch(null) }
                        catch (_: Exception) { itemModel.showMessage("Camera app is unavailable. Choose an image instead.") }
                    } else itemModel.showMessage("Allow camera access to take a reference photo, or choose an existing image.")
                }
                val imagePhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) itemModel.loadPhoto(uri) }
                val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    if (hasMonitoringPermissions()) beginMonitoring()
                    else model.showMessage("Camera and notification permission are required. Allow them in Android app settings, then tap Start monitoring.")
                }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri != null) {
                        try {
                            LocalSafFolder.requireLocalTree(uri)
                            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            model.acceptFolder(uri)
                        } catch (_: Exception) { model.showMessage("Choose a local device, SD-card or USB folder and allow access.") }
                    }
                }
                val stopMonitoring = { startService(Intent(this@MainActivity, MonitoringForegroundService::class.java).setAction(MonitoringForegroundService.STOP)); Unit }
                EdgeAppShell(destination, { destinationName = it.name },
                    sessionAway = destination != EdgeDestination.MONITOR && (monitor.active || monitor.starting || state.activeSessionId != null),
                    onReturn = { destinationName = EdgeDestination.MONITOR.name }, onStop = stopMonitoring,
                    immersive = destination == EdgeDestination.MONITOR && (monitor.active || monitor.starting)) {
                if (destination == EdgeDestination.SETTINGS) EdgePulseScreen(state, CloudSetupActions(model::chooseProvider, model::connect, model::refreshModels,
                    model::chooseModel, model::verifyModel, model::disconnect, model::retryConnection, model::cancel), model::prepareDevice,
                    onChooseFolder = { picker.launch(DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents")) },
                    onExportAudit = model::exportAudit, onExportBenchmark = model::exportBenchmark,
                    onCancel = model::cancel, onMessageDismiss = model::clearMessage,
                    onUsage = { destinationName = EdgeDestination.USAGE.name }, embedded = true)
                else if (destination == EdgeDestination.USAGE) ApiUsageScreen(items.usage, { destinationName = EdgeDestination.MONITOR.name }, embedded = true)
                else if (destination == EdgeDestination.ITEMS) ItemSetupScreen(items, itemModel, trainedLabels, { destinationName = EdgeDestination.MONITOR.name },
                    onCamera = { if (!items.monitoring) {
                        if (ContextCompat.checkSelfPermission(this@MainActivity,Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            try { cameraPhoto.launch(null) }
                            catch (_: Exception) { itemModel.showMessage("Camera app is unavailable. Choose an image instead.") }
                        } else photoPermission.launch(Manifest.permission.CAMERA)
                    } }, onPhoto = { if (!items.monitoring) imagePhoto.launch("image/*") },
                    onSettings = { destinationName = EdgeDestination.SETTINGS.name }, embedded = true)
                else MonitoringScreen(state, monitor, { destinationName = EdgeDestination.SETTINGS.name }, onStart = {
                    if (hasMonitoringPermissions()) beginMonitoring()
                    else permissions.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.CAMERA))
                }, onStop = stopMonitoring,
                    onSurface = monitoringRuntime::attachSurface, overlay = monitoringRuntime.overlay, labels = labels, onRescan = monitoringRuntime::requestRescan,
                    onItems = { destinationName = EdgeDestination.ITEMS.name }, onUsage = { destinationName = EdgeDestination.USAGE.name })
                }
            }
        }
    }
    private fun hasMonitoringPermissions(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    override fun onResume() {
        super.onResume()
        monitoringRuntime.setRotation(if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: 0 else windowManager.defaultDisplay.rotation)
    }
    private fun beginMonitoring() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        monitoringRuntime.starting()
        try { ContextCompat.startForegroundService(this, Intent(this, MonitoringForegroundService::class.java)) }
        catch (_: Exception) { monitoringRuntime.stopped("Camera service could not start. Keep Drishti visible and check camera access.") }
    }
}
