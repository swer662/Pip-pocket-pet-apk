package com.pippocket.pet

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat

private val RetroOledColorScheme = darkColorScheme(
    primary = Color(0xFF00F0FF),
    secondary = Color(0xFF00E676),
    tertiary = Color(0xFFFFB300),
    background = Color(0xFF000000),
    surface = Color(0xFF0A0E14),
    onBackground = Color(0xFFF5F7FA),
    onSurface = Color(0xFFF5F7FA)
)

class MainActivity : ComponentActivity() {

    private val petViewModel: PetViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            var hasMicPermission by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                )
            }

            val permLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { result ->
                val audioGranted = result[Manifest.permission.RECORD_AUDIO] == true
                hasMicPermission = audioGranted
                if (audioGranted) {
                    petViewModel.sensorHub.startAudioAnalysis()
                }
            }

            LaunchedEffect(Unit) {
                val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    perms.add(Manifest.permission.ACTIVITY_RECOGNITION)
                }
                permLauncher.launch(perms.toTypedArray())
                petViewModel.sensorHub.startSensors(hasMicPermission)
            }

            MaterialTheme(colorScheme = RetroOledColorScheme) {
                PipPocketPetApp(
                    viewModel = petViewModel,
                    onSetMaxBrightness = { enableMax ->
                        val params = window.attributes
                        params.screenBrightness = if (enableMax) 1.0f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        window.attributes = params
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val hasAudio = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        petViewModel.sensorHub.startSensors(hasAudio)
    }

    override fun onPause() {
        super.onPause()
        petViewModel.sensorHub.stopSensors()
    }
}
