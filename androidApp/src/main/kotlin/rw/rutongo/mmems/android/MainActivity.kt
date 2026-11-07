package rw.rutongo.mmems.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import rw.rutongo.mmems.android.ui.MmemsApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as MmemsApplication).container
        setContent { MmemsApp(container) }
    }
}
