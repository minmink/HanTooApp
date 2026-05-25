package com.mink.hantoo

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.*
import kotlin.concurrent.timer

class TradingService : Service() {
    companion object {
        const val ACTION_TRADING_STOPPED = "com.mink.hantoo.ACTION_TRADING_STOPPED"
    }

    private val client = OkHttpClient()
    private val gson = Gson()
    private var isRunning = false
    private var monitorTimer: Timer? = null
    private var isFinishNotified = false

    // 실전 매매용 설정 (확정)
    private val baseUrl = "https://openapi.koreainvestment.com:9443"
    private val appKey = BuildConfig.HANTOO_APP_KEY
    private val appSecret = BuildConfig.HANTOO_APP_SECRET
    private val accountNum = BuildConfig.HANTOO_ACCOUNT_NUMBER

    private val BUY_AMOUNT_PER_STOCK = 1_000_000.0 
    private val MAX_HOLDINGS = 5 
    private val CHECK_INTERVAL = 10000L 
    private val MONITOR_COUNT = 20 

    private val monitoringMap = mutableMapOf<String, MonitoringStock>()
    private var currentBoughtCount = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            startForegroundService()
            isRunning = true
            resetSession()
            startTradingLogic()
        }
        return START_STICKY
    }

    private fun resetSession() {
        monitoringMap.clear()
        currentBoughtCount = 0
        Log.d("Trading", "실전 세션 초기화 완료 (새로운 하루 준비)")
    }

    private fun startForegroundService() {
        val channelId = "hantoo_trading_channel"
        val channel = NotificationChannel(channelId, "자동매매 서비스", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("한투 실전 자동매매 서버 가동 중")
            .setContentText("현재 장외 대기 또는 실전 전략 실행 중...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    private fun startTradingLogic() {
        monitorTimer = timer(period = CHECK_INTERVAL) {
            if (isRunning) {
                val token = getSavedToken() ?: return@timer
                
                val now = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul"))
                val hour = now.get(Calendar.HOUR_OF_DAY)
                val minute = now.get(Calendar.MINUTE)
                val currentTimeValue = hour * 100 + minute

                // 1. 아침 8시 50분 초기화
                if (hour == 8 && minute == 50) {
                    resetSession()
                    isFinishNotified = false
                }

                // 2. 09:00 ~ 15:19: 실전 매매 가동
                if (currentTimeValue in 900..1519) {
                    executeStrategy(token)
                    isFinishNotified = false 
                } 
                // 3. 15:20 ~ 15:29: 실전 전량 청산
                else if (currentTimeValue in 1520..1529) {
                    liquidateAll(token)
                }
                // 4. 15:30 이상: 대기 모드 및 알림
                else if (currentTimeValue >= 1530 && !isFinishNotified) {
                    showFinalNotification()
                    isFinishNotified = true
                }
            }
        }
    }

    private fun showFinalNotification() {
        val channelId = "hantoo_trading_channel"
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("실전 자동매매 마감")
            .setContentText("오늘의 실전 매매가 종료되었습니다. 내일 9시에 자동 재개됩니다.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(2, notification)
    }

    private fun executeStrategy(token: String) {
        val url = HttpUrl.Builder()
            .scheme("https").host("openapi.koreainvestment.com").port(9443)
            .addPathSegments("uapi/domestic-stock/v1/ranking/trade-value")
            .addQueryParameter("FID_COND_MRKT_DIV_CODE", "J")
            .addQueryParameter("FID_COND_SCR_DIV_CODE", "20176")
            .addQueryParameter("FID_INPUT_ISCD", "0000")
            .addQueryParameter("FID_DIV_CLS_CODE", "0")
            .addQueryParameter("FID_BLNG_CLS_CODE", "0")
            .addQueryParameter("FID_TRGT_CLS_CODE", "111111111")
            .addQueryParameter("FID_TRGT_EXLS_CLS_CODE", "000000")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("authorization", "Bearer $token")
            .header("appkey", appKey).header("appsecret", appSecret)
            .header("tr_id", "FHPST01760000").header("custtype", "P").build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                val data = gson.fromJson(body, TradeValueResponse::class.java)
                data.output?.take(MONITOR_COUNT)?.forEachIndexed { index, stock ->
                    Timer().schedule(object : TimerTask() {
                        override fun run() { if (isRunning) processStock(token, stock) }
                    }, index * 200L)
                }
            }
        })
    }

    private fun processStock(token: String, stock: TradeValueInfo) {
        val code = stock.code
        fetchDetailedPrice(token, code) { detail ->
            val currentPrice = detail.currentPrice.toDoubleOrNull() ?: 0.0
            val monitorData = monitoringMap[code]

            if (monitorData == null) {
                fetchPrevDayData(token, code) { prevHigh, prevLow, todayOpen ->
                    val K = prevHigh - prevLow
                    val targetPrice = todayOpen + (K * 0.5)
                    monitoringMap[code] = MonitoringStock(code, stock.name, todayOpen, targetPrice)
                    Log.d("Trading", "[${stock.name}] 실전 목표가 설정: $targetPrice")
                }
            } else {
                if (!monitorData.isBought && !monitorData.isSoldToday) {
                    if (currentPrice >= monitorData.targetPrice && currentBoughtCount < MAX_HOLDINGS) {
                        buyStock(token, monitorData, currentPrice)
                    }
                } else if (monitorData.isBought) {
                    val profitRate = (currentPrice - monitorData.buyPrice) / monitorData.buyPrice
                    if (profitRate >= 0.03) {
                        sellStock(token, monitorData, "실전 익절(+3%)")
                    } else if (profitRate <= -0.015) {
                        sellStock(token, monitorData, "실전 손절(-1.5%)")
                    }
                }
            }
        }
    }

    private fun fetchPrevDayData(token: String, code: String, onResult: (Double, Double, Double) -> Unit) {
        val url = "$baseUrl/uapi/domestic-stock/v1/quotations/inquire-daily-price?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=$code&FID_PERIOD_DIV_CODE=D&FID_ORG_ADJ_PRC=0"
        val request = Request.Builder().url(url)
            .header("authorization", "Bearer $token")
            .header("appkey", appKey).header("appsecret", appSecret)
            .header("tr_id", "FHKST01010400").header("custtype", "P").build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                val root = gson.fromJson(body, Map::class.java)
                val list = root["output"] as? List<Map<*, *>>
                val yesterday = list?.get(1)
                val today = list?.get(0)
                val prevHigh = yesterday?.get("stck_hgpr")?.toString()?.toDoubleOrNull() ?: 0.0
                val prevLow = yesterday?.get("stck_lwpr")?.toString()?.toDoubleOrNull() ?: 0.0
                val todayOpen = today?.get("stck_oprc")?.toString()?.toDoubleOrNull() ?: 0.0
                onResult(prevHigh, prevLow, todayOpen)
            }
        })
    }

    private fun fetchDetailedPrice(token: String, code: String, onResult: (PriceDetail) -> Unit) {
        val url = "$baseUrl/uapi/domestic-stock/v1/quotations/inquire-price?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=$code"
        val request = Request.Builder().url(url)
            .header("authorization", "Bearer $token")
            .header("appkey", appKey).header("appsecret", appSecret)
            .header("tr_id", "FHKST01010100").header("custtype", "P").build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                val root = gson.fromJson(body, Map::class.java)
                val output = root["output"] as? Map<*, *>
                if (output != null) onResult(PriceDetail(currentPrice = output["stck_prpr"].toString()))
            }
        })
    }

    private fun buyStock(token: String, stock: MonitoringStock, currentPrice: Double) {
        stock.isBought = true; stock.buyPrice = currentPrice
        currentBoughtCount++
        val quantity = (BUY_AMOUNT_PER_STOCK / currentPrice).toInt()
        stock.quantity = quantity
        if (quantity <= 0) return

        val cano = accountNum.split("-").first()
        val acntCd = accountNum.split("-").getOrNull(1) ?: "01"
        val body = mapOf("CANO" to cano, "ACNT_PRDT_CD" to acntCd, "PDNO" to stock.code, "ORD_DVSN" to "01", "ORD_QTY" to quantity.toString(), "ORD_UNPR" to "0")
        val requestBody = gson.toJson(body).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$baseUrl/uapi/domestic-stock/v1/trading/order-cash").post(requestBody)
            .header("authorization", "Bearer $token").header("appkey", appKey).header("appsecret", appSecret)
            .header("tr_id", "TTTC0802U").header("custtype", "P").build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { currentBoughtCount--; stock.isBought = false }
            override fun onResponse(call: Call, response: Response) { Log.d("Trading", "✅ 실전 매수 성공: ${stock.name}") }
        })
    }

    private fun sellStock(token: String, stock: MonitoringStock, reason: String) {
        val cano = accountNum.split("-").first()
        val acntCd = accountNum.split("-").getOrNull(1) ?: "01"
        val body = mapOf("CANO" to cano, "ACNT_PRDT_CD" to acntCd, "PDNO" to stock.code, "ORD_DVSN" to "01", "ORD_QTY" to stock.quantity.toString(), "ORD_UNPR" to "0")
        val requestBody = gson.toJson(body).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$baseUrl/uapi/domestic-stock/v1/trading/order-cash").post(requestBody)
            .header("authorization", "Bearer $token").header("appkey", appKey).header("appsecret", appSecret)
            .header("tr_id", "TTTC0801U").header("custtype", "P").build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                stock.isBought = false; stock.isSoldToday = true; currentBoughtCount--
                Log.d("Trading", "🚨 실전 매도 완료 [$reason]: ${stock.name}")
            }
        })
    }

    private fun liquidateAll(token: String) {
        monitoringMap.values.filter { it.isBought }.forEach { sellStock(token, it, "실전 장 마감 청산") }
    }

    private fun getSavedToken(): String? = getSharedPreferences("hantoo_prefs", Context.MODE_PRIVATE).getString("access_token", null)
    override fun onDestroy() { isRunning = false; monitorTimer?.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}

// Data Models
data class TradeValueResponse(val output: List<TradeValueInfo>?)
data class TradeValueInfo(@SerializedName("hts_kor_isnm") val name: String, @SerializedName("mksc_shrn_iscd") val code: String, @SerializedName("stck_prpr") val price: String)
data class PriceDetail(val currentPrice: String)
data class MonitoringStock(
    val code: String, val name: String, val openPrice: Double, val targetPrice: Double,
    var isBought: Boolean = false, var isSoldToday: Boolean = false,
    var buyPrice: Double = 0.0, var quantity: Int = 0
)
