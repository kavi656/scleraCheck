package com.scleracheck

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.scleracheck.data.Prefs
import com.scleracheck.ui.CheckScreen
import com.scleracheck.ui.ClinicianScreen
import com.scleracheck.ui.DisclaimerScreen
import com.scleracheck.ui.HistoryScreen
import com.scleracheck.ui.HomeScreen
import com.scleracheck.ui.ScleraTheme

sealed interface Screen {
    data object Disclaimer : Screen
    data object Home : Screen
    data class Check(val baseline: Boolean) : Screen
    data object History : Screen
    data object Clinician : Screen
}

class MainActivity : ComponentActivity() {
    /** Volume up/down act as the shutter while a check is guiding (see Shutter). */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val vol = keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
        if (vol && com.scleracheck.ui.Shutter.listener != null) {
            if (event?.repeatCount == 0) com.scleracheck.ui.Shutter.press() // ignore auto-repeat while held
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = Prefs(this)
        setContent {
            ScleraTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var screen by remember { mutableStateOf<Screen>(if (prefs.disclaimerAccepted) Screen.Home else Screen.Disclaimer) }
                    if (screen != Screen.Home && screen != Screen.Disclaimer) BackHandler { screen = Screen.Home }
                    when (val s = screen) {
                        Screen.Disclaimer -> DisclaimerScreen { prefs.disclaimerAccepted = true; screen = Screen.Home }
                        Screen.Home -> HomeScreen(
                            onCheck = { screen = Screen.Check(false) },
                            onHistory = { screen = Screen.History },
                            onClinician = { screen = Screen.Clinician },
                        )
                        is Screen.Check -> CheckScreen(baseline = s.baseline, onExit = { screen = if (s.baseline) Screen.Clinician else Screen.Home })
                        Screen.History -> HistoryScreen(onBack = { screen = Screen.Home })
                        Screen.Clinician -> ClinicianScreen(
                            onBack = { screen = Screen.Home },
                            onRecordBaseline = { screen = Screen.Check(true) },
                            onHistory = { screen = Screen.History },
                        )
                    }
                }
            }
        }
    }
}
