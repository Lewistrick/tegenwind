package com.tegenwind.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.tegenwind.app.hrprobe.HrProbeScreen
import com.tegenwind.app.ui.rides.RidesScreen
import com.tegenwind.app.ui.ride.RideScreen
import com.tegenwind.app.ui.theme.TegenwindTheme

private enum class Tab(val label: String, @param:DrawableRes val icon: Int) {
    RIDE("Ride", R.drawable.ic_ride),
    RIDES("Rides", R.drawable.ic_rides),
    HR_TEST("HR test", R.drawable.ic_heart),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TegenwindTheme {
                var tab by rememberSaveable { mutableStateOf(Tab.RIDE) }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(
                                    selected = tab == t,
                                    onClick = { tab = t },
                                    icon = { Icon(painterResource(t.icon), contentDescription = null) },
                                    label = { Text(t.label) },
                                )
                            }
                        }
                    },
                ) { pad ->
                    Box(Modifier.padding(pad).consumeWindowInsets(pad)) {
                        when (tab) {
                            Tab.RIDE -> RideScreen()
                            Tab.RIDES -> RidesScreen()
                            Tab.HR_TEST -> HrProbeScreen()
                        }
                    }
                }
            }
        }
    }
}
