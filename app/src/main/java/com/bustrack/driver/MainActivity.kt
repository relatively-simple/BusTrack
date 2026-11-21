package com.bustrack.driver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bustrack.driver.ui.BusSelectionScreen
import com.bustrack.driver.ui.PassengerMapScreen
import com.bustrack.driver.ui.theme.BusTrackTheme
import com.bustrack.driver.viewmodel.PassengerViewModel

enum class AppScreen { SELECTION, TRACKING }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BusTrackTheme {
                val viewModel: PassengerViewModel = viewModel()
                var currentScreen by remember { mutableStateOf(AppScreen.SELECTION) }

                // Native Android back gesture handling: returns to bus selection if tracking
                BackHandler(enabled = currentScreen == AppScreen.TRACKING) {
                    viewModel.deselectBus()
                    currentScreen = AppScreen.SELECTION
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF04060A)
                ) {
                    when (currentScreen) {
                        AppScreen.SELECTION -> {
                            BusSelectionScreen(
                                viewModel = viewModel,
                                onBusSelected = { routeId, busId ->
                                    viewModel.selectBus(routeId, busId)
                                    currentScreen = AppScreen.TRACKING
                                }
                            )
                        }
                        AppScreen.TRACKING -> {
                            PassengerMapScreen(
                                viewModel = viewModel,
                                onBack = {
                                    viewModel.deselectBus()
                                    currentScreen = AppScreen.SELECTION
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
