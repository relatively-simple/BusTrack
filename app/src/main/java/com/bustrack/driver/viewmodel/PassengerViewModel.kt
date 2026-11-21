package com.bustrack.driver.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ── Data Models ──

data class BusSummary(
    val routeId: String,
    val busId: String,
    val displayName: String,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val speedKmh: Int = 0,
    val statusCode: Int = 1, // 1=Active/On Time, 2=Delayed, 3=Broken Down
    val delayTime: Int = 0,
    val distanceKm: Double = 0.0,
    val etaMinutes: Int = 0
)

enum class BusApproach { COMING, ARRIVING, MISSED, UNKNOWN }

data class TrackingState(
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val speedKmh: Int = 0,
    val statusCode: Int = 1,
    val delayTime: Int = 0,
    val etaMinutes: Int = 0,
    val distanceKm: Double = 0.0,
    val approach: BusApproach = BusApproach.UNKNOWN,
    val hasData: Boolean = false,
    val lastUpdateMillis: Long = 0L
)

class PassengerViewModel : ViewModel() {

    companion object {
        private const val TAG = "PassengerVM"
        // Target Bus Stop (Downtown Station)
        const val TARGET_STOP_LAT = 7.2915
        const val TARGET_STOP_LNG = 80.6350
        const val TARGET_STOP_NAME = "Downtown Station"
    }

    // ── Exposed State ──
    private val _busList = MutableStateFlow<List<BusSummary>>(emptyList())
    val busList: StateFlow<List<BusSummary>> = _busList.asStateFlow()

    private val _selectedBus = MutableStateFlow<BusSummary?>(null)
    val selectedBus: StateFlow<BusSummary?> = _selectedBus.asStateFlow()

    private val _selectedBusId = MutableStateFlow<String?>(null)
    val selectedBusId: StateFlow<String?> = _selectedBusId.asStateFlow()

    private val _trackingState = MutableStateFlow(TrackingState())
    val trackingState: StateFlow<TrackingState> = _trackingState.asStateFlow()

    private val _isConnected = MutableStateFlow(true)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // ── Firebase References ──
    private val liveRef: DatabaseReference =
        FirebaseDatabase.getInstance().getReference("live")
    private var discoveryListener: ValueEventListener? = null
    private var trackingListener: ValueEventListener? = null
    private var trackingRef: DatabaseReference? = null

    // ── Approach Detection Metrics ──
    private var closestDistanceKm: Double = Double.MAX_VALUE
    private var initialDistanceKm: Double? = null
    private var previousDistanceKm: Double? = null

    init {
        startDiscovery()
    }

    // ── Bus Discovery ──
    // Listens to /live/ and enumerates all routes and their child buses dynamically.
    fun startDiscovery() {
        stopDiscovery()
        Log.d(TAG, "Starting discovery on node: ${liveRef.path}")

        discoveryListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                _isConnected.value = true
                val buses = mutableListOf<BusSummary>()
                // snapshot represents /live/, its children are routeIds
                for (routeNode in snapshot.children) {
                    val routeId = routeNode.key ?: continue

                    // routeNode's children are busIds
                    for (busNode in routeNode.children) {
                        val busId = busNode.key ?: continue

                        val csv = try {
                            busNode.getValue(String::class.java)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to cast child value to String for bus $busId", e)
                            null
                        }

                        if (csv.isNullOrBlank()) continue

                        try {
                            val parts = csv.split(",")
                            if (parts.size >= 4) {
                                val lat = parts[0].toDoubleOrNull() ?: 0.0
                                val lng = parts[1].toDoubleOrNull() ?: 0.0
                                val speed = parts[2].toIntOrNull() ?: 0
                                val status = parts[3].toIntOrNull() ?: 1
                                val delay = if (parts.size >= 5) parts[4].toIntOrNull() ?: 0 else 0

                                val distance = haversineKm(lat, lng, TARGET_STOP_LAT, TARGET_STOP_LNG)
                                val eta = calculateEtaMinutes(distance, speed)

                                buses.add(
                                    BusSummary(
                                        routeId = routeId,
                                        busId = busId,
                                        displayName = formatBusId(routeId, busId),
                                        lat = lat,
                                        lng = lng,
                                        speedKmh = speed,
                                        statusCode = status,
                                        delayTime = delay,
                                        distanceKm = distance,
                                        etaMinutes = eta
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Skipping bus $busId due to parsing error: ${e.message}")
                        }
                    }
                }
                // Sort buses by estimated arrival time so the closest bus is first
                _busList.value = buses.sortedBy { it.distanceKm }
                Log.d(TAG, "Discovered ${buses.size} buses across all routes.")
            }

            override fun onCancelled(error: DatabaseError) {
                _isConnected.value = false
                Log.e(TAG, "Discovery cancelled: ${error.message}")
            }
        }
        liveRef.addValueEventListener(discoveryListener!!)
    }

    private fun stopDiscovery() {
        discoveryListener?.let { liveRef.removeEventListener(it) }
        discoveryListener = null
    }

    // ── Bus Selection & Tracking ──
    fun selectBus(routeId: String, busId: String) {
        stopTracking()
        val formatted = formatBusId(routeId, busId)
        _selectedBusId.value = formatted
        _selectedBus.value = _busList.value.firstOrNull { it.routeId == routeId && it.busId == busId }

        // Reset tracking metrics
        closestDistanceKm = Double.MAX_VALUE
        initialDistanceKm = null
        previousDistanceKm = null
        _trackingState.value = TrackingState()

        trackingRef = liveRef.child(routeId).child(busId)
        trackingListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val csv = snapshot.getValue(String::class.java) ?: return
                try {
                    val parts = csv.split(",")
                    if (parts.size >= 4) {
                        val lat = parts[0].toDoubleOrNull() ?: 0.0
                        val lng = parts[1].toDoubleOrNull() ?: 0.0
                        val speed = parts[2].toIntOrNull() ?: 0
                        val status = parts[3].toIntOrNull() ?: 1
                        val delay = if (parts.size >= 5) parts[4].toIntOrNull() ?: 0 else 0

                        val distanceKm = haversineKm(lat, lng, TARGET_STOP_LAT, TARGET_STOP_LNG)
                        val eta = calculateEtaMinutes(distanceKm, speed)
                        val approach = determineApproach(distanceKm)

                        if (distanceKm < closestDistanceKm) {
                            closestDistanceKm = distanceKm
                        }
                        if (initialDistanceKm == null) {
                            initialDistanceKm = distanceKm
                        }
                        previousDistanceKm = distanceKm

                        _trackingState.value = TrackingState(
                            lat = lat,
                            lng = lng,
                            speedKmh = speed,
                            statusCode = status,
                            delayTime = delay,
                            etaMinutes = eta,
                            distanceKm = distanceKm,
                            approach = approach,
                            hasData = true,
                            lastUpdateMillis = System.currentTimeMillis()
                        )
                        Log.d(TAG, "Tracking: dist=${String.format("%.2f", distanceKm)}km, eta=${eta}min, approach=$approach")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing tracking CSV: $csv", e)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Tracking cancelled: ${error.message}")
            }
        }
        trackingRef!!.addValueEventListener(trackingListener!!)
    }

    fun deselectBus() {
        stopTracking()
        _selectedBusId.value = null
        _selectedBus.value = null
        _trackingState.value = TrackingState()
        closestDistanceKm = Double.MAX_VALUE
        initialDistanceKm = null
        previousDistanceKm = null
    }

    private fun stopTracking() {
        trackingListener?.let { trackingRef?.removeEventListener(it) }
        trackingListener = null
        trackingRef = null
    }

    // ── Resilient Approach Detection ──
    private fun determineApproach(currentDistanceKm: Double): BusApproach {
        // 1. If within 180m, bus is at or pulling into station
        if (currentDistanceKm <= 0.18) {
            return BusApproach.ARRIVING
        }

        // 2. If the bus previously came close to the station (within 300m)
        // and is now moving away by more than 150m buffer, it has missed/passed the stop
        if (closestDistanceKm <= 0.30 && (currentDistanceKm - closestDistanceKm) >= 0.15) {
            return BusApproach.MISSED
        }

        // 3. If the bus started far away and is consistently moving further away (> 400m from start)
        val initial = initialDistanceKm
        if (initial != null && currentDistanceKm > initial + 0.40 && currentDistanceKm > closestDistanceKm + 0.30) {
            return BusApproach.MISSED
        }

        // 4. Default: heading towards destination
        return BusApproach.COMING
    }

    // ── Haversine Formula ──
    private fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    private fun calculateEtaMinutes(distanceKm: Double, speedKmh: Int): Int {
        val effectiveSpeed = if (speedKmh > 5) speedKmh else 20
        return ((distanceKm / effectiveSpeed) * 60).toInt().coerceAtLeast(1)
    }

    // ── Helpers ──
    fun formatBusId(routeId: String, busId: String): String {
        val r = routeId.replace("_", " ")
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        val b = busId.replace("_", " ")
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        return "$r • $b"
    }

    // ── Memory Safety ──
    override fun onCleared() {
        super.onCleared()
        stopDiscovery()
        stopTracking()
        Log.d(TAG, "ViewModel cleared, all Firebase listeners removed.")
    }
}
