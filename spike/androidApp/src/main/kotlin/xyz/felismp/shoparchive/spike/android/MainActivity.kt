package xyz.felismp.shoparchive.spike.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import xyz.felismp.shoparchive.spike.app.setSpikeContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSpikeContent()
    }
}
