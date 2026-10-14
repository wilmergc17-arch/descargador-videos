package com.wilmer.descargador

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "Descargas en curso", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_RESULT, "Descargas terminadas", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    companion object {
        const val CHANNEL_PROGRESS = "progreso"
        const val CHANNEL_RESULT = "resultado"
    }
}
