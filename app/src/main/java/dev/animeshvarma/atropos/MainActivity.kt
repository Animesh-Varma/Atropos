package dev.animeshvarma.atropos

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import dev.animeshvarma.atropos.ui.screens.AtroposAppOrchestrator
import dev.animeshvarma.atropos.ui.theme.AtroposTheme

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: AtroposViewModel

    // 1. Make permissions reactive using Compose State!
    private var hasPermissions by mutableStateOf(false)

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // 2. Update state when user clicks "Allow"
        hasPermissions = permissions.entries.all { it.value }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        viewModel = ViewModelProvider(this)[AtroposViewModel::class.java]

        // 3. Check existing permissions immediately on app launch
        hasPermissions = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        // If not granted, request them right away
        if (!hasPermissions) {
            permissionLauncher.launch(requiredPermissions)
        }

        setContent {
            AtroposTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // 4. Gate the UI!
                    // Do not load the camera or the orchestrator until permissions = true
                    if (hasPermissions) {
                        AtroposAppOrchestrator(viewModel)
                    } else {
                        // Show a loading/warning screen while the system popup is visible
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Camera and Audio permissions are required to record.")
                        }
                    }
                }
            }
        }
    }
}