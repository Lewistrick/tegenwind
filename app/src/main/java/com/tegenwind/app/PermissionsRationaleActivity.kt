package com.tegenwind.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tegenwind.app.ui.theme.TegenwindTheme

/** Shown by Health Connect when you ask how Tegenwind uses health data. Required for the permission dialog. */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TegenwindTheme {
                Scaffold { pad ->
                    Column(
                        Modifier.padding(pad).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("How Tegenwind uses your health data", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Tegenwind reads your heart rate and workouts from Health Connect to show them " +
                                "during rides and to estimate your fitness for arrival-time predictions. " +
                                "The data stays on this phone. It isn't uploaded or shared with anyone."
                        )
                        Button(onClick = ::finish) { Text("Close") }
                    }
                }
            }
        }
    }
}
