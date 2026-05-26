package com.mink.hantoo

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import okhttp3.*
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.TimeUnit
import javax.net.ssl.*
import kotlin.concurrent.timer

class TradingService : Service() {
    companion object {
        const val ACTION_TRADE_OCCURRED = "com.mink.hantoo.ACTION_TRADE_OCCURRED"
        const val CHANNEL_ID = "hantoo_service_channel"
    }

    private var isRunning = false
    private var monitorTimer: Timer? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("HANTOO_LOG", "▶ TradingService Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("HANTOO_LOG", "▶ 봇 사냥 시작 (onStartCommand)")
        
        if (!isRunning) {
            isRunning = true
            setupForeground()
            
            monitorTimer = timer(period = 60000L) {
                if (isRunning) {
                    Log.d("HANTOO_LOG", "⏰ [봇 생존신호] 시장 감시 중... (${System.currentTimeMillis()})")
                }
            }
        }
        return START_STICKY
    }

    private fun setupForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "봇 서비스", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("자동매매 가동 중")
            .setContentText("시장을 감시하고 있습니다.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        Log.d("HANTOO_LOG", "■ 봇 사냥 종료")
        isRunning = false
        monitorTimer?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
