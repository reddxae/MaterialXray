package com.material.xray

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.material.xray.core.locale.notifyAppLocaleChanged
import com.material.xray.ui.navigation.MainNavigation
import com.material.xray.ui.theme.MaterialXrayTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notifyAppLocaleChanged()
        enableEdgeToEdge()
        setContent {
            MaterialXrayTheme {
                MainNavigation()
            }
        }
    }
}
