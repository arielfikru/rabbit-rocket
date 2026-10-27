package io.github.arielfikru.rabbitrocket

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import io.github.arielfikru.rabbitrocket.ui.AppScreen
import io.github.arielfikru.rabbitrocket.ui.MainViewModel
import io.github.arielfikru.rabbitrocket.ui.RabbitRocketTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        io.github.arielfikru.rabbitrocket.net.NetSettings.init(this)
        DownloadStore.attach(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) handle(intent)
        setContent { RabbitRocketTheme { AppScreen(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i == null) return
        val retry = i.getLongExtra(EXTRA_RETRY, -1L)
        if (retry > 0) {
            i.removeExtra(EXTRA_RETRY)
            vm.retry(retry)
            return
        }
        val text = (if (i.action == Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT) else null)
            ?: i.dataString ?: return
        extractUrl(text)?.let {
            vm.tab = 0
            vm.editUrl(it)
            vm.check()
        }
    }

    companion object {
        const val EXTRA_RETRY = "retry"
    }
}
