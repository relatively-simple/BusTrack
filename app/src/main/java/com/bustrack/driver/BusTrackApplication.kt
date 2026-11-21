package com.bustrack.driver

import android.app.Application
import com.google.firebase.database.FirebaseDatabase

/**
 * Application entry point.
 * Enables Firebase RTDB disk persistence so writes are queued
 * and replayed automatically if the device goes offline.
 *
 * setPersistenceEnabled() must be called before any DatabaseReference
 * is obtained — hence it lives in Application.onCreate().
 */
class BusTrackApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseDatabase.getInstance().setPersistenceEnabled(true)
    }
}
