package com.bustrack.driver.ui

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.animation.LinearInterpolator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bustrack.driver.R
import com.bustrack.driver.ui.theme.CardBorder
import com.bustrack.driver.ui.theme.CardDark
import com.bustrack.driver.ui.theme.CardElevated
import com.bustrack.driver.ui.theme.NeonCyan
import com.bustrack.driver.ui.theme.NeonGreen
import com.bustrack.driver.ui.theme.NeonRed
import com.bustrack.driver.ui.theme.NeonYellow
import com.bustrack.driver.ui.theme.TextDim
import com.bustrack.driver.ui.theme.TextMuted
import com.bustrack.driver.ui.theme.TextPrimary
import com.bustrack.driver.ui.theme.TextSecondary
import com.bustrack.driver.viewmodel.BusApproach
import com.bustrack.driver.viewmodel.PassengerViewModel
import com.bustrack.driver.viewmodel.TrackingState
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.plugins.annotation.Symbol
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions

@Composable
fun PassengerMapScreen(
    viewModel: PassengerViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.trackingState.collectAsState()
    val selectedBusId by viewModel.selectedBusId.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapLibreMapRef = remember { mutableMapOf<String, MapLibreMap>() }
    val symbolManagerRef = remember { mutableMapOf<String, SymbolManager>() }
    val busSymbolRef = remember { mutableMapOf<String, Symbol>() }
    val stopSymbolRef = remember { mutableMapOf<String, Symbol>() }
    val currentAnimatorRef = remember { mutableMapOf<String, ValueAnimator>() }

    val busBitmap = remember { createBusMarkerBitmap(0xFF00FF87.toInt(), 0x3300FF87) }
    val stopBitmap = remember { createStopMarkerBitmap(0xFF00E5FF.toInt(), 0x3300E5FF) }

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply {
            getMapAsync { mapLibreMap ->
                mapLibreMapRef["map"] = mapLibreMap
                val styleUrl = context.getString(R.string.map_style_url)
                
                // Immediately position camera on the target arrival station
                val initialStation = LatLng(PassengerViewModel.TARGET_STOP_LAT, PassengerViewModel.TARGET_STOP_LNG)
                mapLibreMap.cameraPosition = CameraPosition.Builder()
                    .target(initialStation)
                    .zoom(14.5)
                    .build()

                mapLibreMap.setStyle(styleUrl) { style ->
                    style.addImage("bus-marker", busBitmap)
                    style.addImage("stop-marker", stopBitmap)
                    val symbolManager = SymbolManager(this, mapLibreMap, style)
                    symbolManager.iconAllowOverlap = true
                    symbolManager.iconIgnorePlacement = true
                    symbolManagerRef["manager"] = symbolManager

                    // Place the target stop marker immediately
                    val stopSymbol = symbolManager.create(
                        SymbolOptions()
                            .withLatLng(initialStation)
                            .withIconImage("stop-marker")
                            .withIconSize(1.3f)
                    )
                    stopSymbolRef["stop"] = stopSymbol
                }
            }
        }
    }

    // MapView Lifecycle & Resource Teardown
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> {
                    currentAnimatorRef["animator"]?.cancel()
                    mapView.onDestroy()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            currentAnimatorRef["animator"]?.cancel()
            mapView.onDestroy()
        }
    }

    // Smooth LERP animation & camera focus
    LaunchedEffect(state.lat, state.lng, state.hasData) {
        if (state.hasData && state.lat != 0.0 && state.lng != 0.0) {
            val map = mapLibreMapRef["map"]
            val manager = symbolManagerRef["manager"]
            if (map != null && manager != null) {
                val targetLatLng = LatLng(state.lat, state.lng)

                // Smoothly follow the bus with the camera
                map.animateCamera(CameraUpdateFactory.newLatLng(targetLatLng), 1200)

                val currentSymbol = busSymbolRef["bus"]
                if (currentSymbol == null) {
                    val symbol = manager.create(
                        SymbolOptions()
                            .withLatLng(targetLatLng)
                            .withIconImage("bus-marker")
                            .withIconSize(1.1f)
                    )
                    busSymbolRef["bus"] = symbol
                } else {
                    // Cancel any active animation and start smooth LERP from current position
                    currentAnimatorRef["animator"]?.cancel()
                    val startLatLng = currentSymbol.latLng
                    val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 4500
                        interpolator = LinearInterpolator()
                        addUpdateListener { animation ->
                            val f = animation.animatedValue as Float
                            val lat = startLatLng.latitude + (targetLatLng.latitude - startLatLng.latitude) * f
                            val lng = startLatLng.longitude + (targetLatLng.longitude - startLatLng.longitude) * f
                            currentSymbol.latLng = LatLng(lat, lng)
                            manager.update(currentSymbol)
                        }
                    }
                    currentAnimatorRef["animator"] = animator
                    animator.start()
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Full screen dark CARTO map
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // ── Top Navigation & Header Overlay ──
        TopMapBar(
            busTitle = selectedBusId ?: "Bus Tracking",
            hasData = state.hasData,
            onBack = onBack,
            onCenterBus = {
                val map = mapLibreMapRef["map"]
                if (map != null && state.hasData) {
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(LatLng(state.lat, state.lng), 15.5),
                        800
                    )
                }
            },
            onCenterStation = {
                val map = mapLibreMapRef["map"]
                if (map != null) {
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            LatLng(PassengerViewModel.TARGET_STOP_LAT, PassengerViewModel.TARGET_STOP_LNG),
                            15.0
                        ),
                        800
                    )
                }
            }
        )

        // ── Bottom Telemetry Sheet ──
        if (state.hasData) {
            TelemetryBottomSheet(
                state = state,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            )
        } else {
            // Signal acquisition card
            SignalAcquiringCard(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            )
        }
    }
}

@Composable
private fun TopMapBar(
    busTitle: String,
    hasData: Boolean,
    onBack: () -> Unit,
    onCenterBus: () -> Unit,
    onCenterStation: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 44.dp, start = 16.dp, end = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back Button
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(Color(0xFF0C1017).copy(alpha = 0.88f))
                .border(1.dp, CardBorder, CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "‹", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }

        // Bus Route Pill
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0C1017).copy(alpha = 0.88f)),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (hasData) NeonGreen else NeonYellow)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = busTitle,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }

        // Action Buttons (Recenter)
        Row {
            // Recenter on Station
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0C1017).copy(alpha = 0.88f))
                    .border(1.dp, CardBorder, CircleShape)
                    .clickable(onClick = onCenterStation),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "📍", fontSize = 16.sp)
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Recenter on Bus
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0C1017).copy(alpha = 0.88f))
                    .border(1.dp, CardBorder, CircleShape)
                    .clickable(onClick = onCenterBus),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "🚌", fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun TelemetryBottomSheet(state: TrackingState, modifier: Modifier = Modifier) {
    val (approachLabel, approachColor, approachDesc) = when (state.approach) {
        BusApproach.COMING -> Triple("COMING", NeonCyan, "Inbound to stop")
        BusApproach.ARRIVING -> Triple("ARRIVING NOW", NeonGreen, "Pulling into station")
        BusApproach.MISSED -> Triple("MISSED", NeonRed, "Passed your stop")
        BusApproach.UNKNOWN -> Triple("LOCATING", TextDim, "Calculating trajectory")
    }

    val (statusLabel, statusColor) = when (state.statusCode) {
        1 -> Pair("On Time", NeonGreen)
        2 -> Pair("Delayed" + if (state.delayTime > 0) " (+${state.delayTime}m)" else "", NeonYellow)
        3 -> Pair("Broken Down", NeonRed)
        else -> Pair("Active", NeonCyan)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF090D14).copy(alpha = 0.94f)),
        border = BorderStroke(1.dp, CardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 24.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Approach Badge & Status Badge Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Approach Indicator
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(approachColor.copy(alpha = 0.15f))
                        .border(1.dp, approachColor.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(approachColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = approachLabel,
                            color = approachColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        )
                    }
                }

                // Schedule Status Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(statusColor.copy(alpha = 0.12f))
                        .border(1.dp, statusColor.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = statusLabel,
                        color = statusColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Big ETA Typography
            if (state.approach != BusApproach.MISSED) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "${state.etaMinutes}",
                                color = NeonGreen,
                                fontSize = 52.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = (-2).sp
                            )
                            Text(
                                text = " MIN",
                                color = NeonGreen.copy(alpha = 0.8f),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp, start = 6.dp)
                            )
                        }
                        Text(
                            text = "Estimated arrival at ${PassengerViewModel.TARGET_STOP_NAME}",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }

                    // Pulse indicator
                    LiveSignalPulse()
                }
            } else {
                Column {
                    Text(
                        text = "Bus has passed your stop",
                        color = NeonRed,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Bus moved past ${PassengerViewModel.TARGET_STOP_NAME} and is proceeding on route.",
                        color = TextDim,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Telemetry Chips Grid
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardDark)
                    .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                TelemetryChip(label = "DISTANCE", value = String.format("%.1f km", state.distanceKm))
                TelemetryChip(label = "SPEED", value = "${state.speedKmh} km/h")
                TelemetryChip(label = "STATUS", value = approachDesc)
            }
        }
    }
}

@Composable
private fun LiveSignalPulse() {
    val infiniteTransition = rememberInfiniteTransition(label = "signal")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(NeonGreen.copy(alpha = 0.1f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(NeonGreen)
                .alpha(alpha)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "LIVE",
            color = NeonGreen,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp
        )
    }
}

@Composable
private fun TelemetryChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = TextDim,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SignalAcquiringCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF090D14).copy(alpha = 0.94f)),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(NeonYellow.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "📡", fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = "Connecting to telemetry feed...",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Awaiting live GPS broadcast from driver vehicle",
                    color = TextDim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/**
 * Creates a glowing vehicle marker bitmap for MapLibre.
 */
private fun createBusMarkerBitmap(coreColor: Int, haloColor: Int): Bitmap {
    val size = 72
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Outer radar glow
    paint.color = haloColor
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

    // Dark chassis background
    paint.color = 0xFF0B1522.toInt()
    canvas.drawCircle(size / 2f, size / 2f, size / 2.8f, paint)

    // Neon accent ring
    paint.color = coreColor
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 3f
    canvas.drawCircle(size / 2f, size / 2f, size / 2.8f, paint)

    // Core neon vehicle dot
    paint.style = Paint.Style.FILL
    paint.color = coreColor
    canvas.drawCircle(size / 2f, size / 2f, size / 5f, paint)

    return bitmap
}

/**
 * Creates a destination station marker bitmap for MapLibre.
 */
private fun createStopMarkerBitmap(coreColor: Int, haloColor: Int): Bitmap {
    val size = 72
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Outer halo
    paint.color = haloColor
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

    // Dark background ring
    paint.color = 0xFF08101C.toInt()
    canvas.drawCircle(size / 2f, size / 2f, size / 2.8f, paint)

    // Cyan outline
    paint.color = coreColor
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 3.5f
    canvas.drawCircle(size / 2f, size / 2f, size / 2.8f, paint)

    // Center target dot
    paint.style = Paint.Style.FILL
    paint.color = coreColor
    canvas.drawCircle(size / 2f, size / 2f, size / 6f, paint)

    return bitmap
}
