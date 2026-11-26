package ro.fermapp.mobile

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import ro.fermapp.app.App
import ro.fermapp.app.platform.AndroidContext

class FermApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidContext.app = this
    }
}

class MainActivity : ComponentActivity() {
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Location helps you find and draw parcels on the map.
        askLocation.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
        setContent { App() }
    }
}
