package xyz.felismp.shoparchive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import xyz.felismp.shoparchive.app.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}
