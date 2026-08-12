package com.example.wifiguardai

import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import com.example.wifiguardai.security.SecurityEventBus
import com.example.wifiguardai.security.SecurityVpnService

class MainActivity : FlutterFragmentActivity() {
    private var pendingStartResult: MethodChannel.Result? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val ok = result.resultCode == RESULT_OK
        if (ok) SecurityVpnService.start(this)
        pendingStartResult?.success(ok)
        pendingStartResult = null
    }

    private val localNetworkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) requestVpnPermission() else {
            pendingStartResult?.success(false)
            pendingStartResult = null
        }
    }

    private fun requestVpnPermission() {
        val prepare = VpnService.prepare(this)
        if (prepare == null) {
            SecurityVpnService.start(this)
            pendingStartResult?.success(true)
            pendingStartResult = null
        } else {
            vpnPermissionLauncher.launch(prepare)
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHODS).setMethodCallHandler { call, result ->
            when (call.method) {
                "startMonitoring" -> {
                    if (pendingStartResult != null) {
                        result.error("BUSY", "Permission request already active", null)
                    } else {
                        pendingStartResult = result
                        val targets37 = applicationInfo.targetSdkVersion >= 37
                        val needsLocal = Build.VERSION.SDK_INT >= 37 && targets37 &&
                            ContextCompat.checkSelfPermission(this, LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED
                        if (needsLocal) localNetworkPermissionLauncher.launch(LOCAL_NETWORK_PERMISSION)
                        else requestVpnPermission()
                    }
                }
                "stopMonitoring" -> {
                    SecurityVpnService.stop(this)
                    result.success(null)
                }
                "startRecording" -> {
                    val label = call.argument<String>("label") ?: "Normal"
                    val path = SecurityVpnService.setRecording(this, true, label)
                    result.success(path)
                }
                "stopRecording" -> {
                    val path = SecurityVpnService.setRecording(this, false, null)
                    result.success(path)
                }
                "getStatus" -> result.success(SecurityEventBus.latest())
                "getCaptureDirectory" -> result.success(SecurityVpnService.captureDirectory(this))
                else -> result.notImplemented()
            }
        }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENTS).setStreamHandler(
            object : EventChannel.StreamHandler {
                private var listener: ((Map<String, Any?>) -> Unit)? = null

                override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                    val main = Handler(Looper.getMainLooper())
                    listener = { value -> main.post { events.success(value) } }
                    SecurityEventBus.add(listener!!)
                    events.success(SecurityEventBus.latest())
                }

                override fun onCancel(arguments: Any?) {
                    listener?.let(SecurityEventBus::remove)
                    listener = null
                }
            }
        )
    }

    companion object {
        private const val METHODS = "wifi_guard_ai/methods"
        private const val EVENTS = "wifi_guard_ai/events"
        private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
