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

    private val baseUrl = "https://openapi.koreainvestment.com:9443"
    private val appKey = BuildConfig.HANTOO_APP_KEY
    private val appSecret = BuildConfig.HANTOO_APP_SECRET
    private val accountNum = BuildConfig.HANTOO_ACCOUNT_NUMBER

    private val BUY_AMOUNT_PER_STOCK = 1_000_000.0 
    private val MAX_HOLDINGS = 5 
    private val CHECK_INTERVAL = 10000L 
    private val MONITOR_COUNT = 20 
    private val TRADING_FEE = 0.0016 

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
        Log.d("Trading", "실전 세션 초기화 완료")
    }

    private fun startForegroundService() {
        val channelId = "hantoo_trading_channel"
        val channel = NotificationChannel(channelId, "자동매매 서비스", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = createNotification("한투 자동매매 서버 대기 중", "장 시작(09:00)을 기다리고 있습니다.")
        startForeground(1, notification)
    }

    private fun createNotification(title: String, content: String): Notification {
        return NotificationCompat.Builder(this, "hantoo_trading_channel")
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun startTradingLogic() {
        monitorTimer = timer(period = CHECK_INTERVAL) {
            if (isRunning) {
                val token = getSavedToken() ?: return@timer
                val now = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul"))
                val hour = now.get(Calendar.HOUR_OF_DAY)
                val minute = now.get(Calendar.MINUTE)
                val currentTimeValue = hour * 100 + minute

                // 1. 아침 8시 50분: 초기화 및 대기 알림
                if (hour == 8 && minute == 50) {
                    resetSession()
                    isFinishNotified = false
                    updateNotification("장 시작 대기 중", "09:00에 실전 매매를 시작합니다.")
                }

                // 2. 09:00 ~ 15:14: 실전 매매 가동
                if (currentTimeValue in 900..1514) {
                    executeStrategy(token)
                    isFinishNotified = false 
                    updateNotification("실전 매매 가동 중", "조건에 맞는 종목을 탐색하고 있습니다.")
                } 
                // 3. 15:15 ~ 15:29: 실전 전량 청산
                else if (currentTimeValue in 1515..1529) {
                    liquidateAll(token)
                    updateNotification("장 마감 청산 중", "모든 보유 종목을 매도하고 있습니다.")
                }
                // 4. 15:30 ~ 익일 08:49: 장외 대기 (종료 안 함!)
                else {
                    if (!isFinishNotified && currentTimeValue >= 1530) {
                        showTodaySummaryNotification()
                        isFinishNotified = true
                    }
                    updateNotification("장외 대기 모드", "내일 아침 09:00에 자동으로 재개됩니다.")
                }
            }
        }
    }

    private fun updateNotification(title: String, content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, createNotification(title, content))
    }

    private fun showTodaySummaryNotification() {
        val notification = NotificationCompat.Builder(this, "hantoo_trading_channel")
            .setContentTitle("실전 자동매매 마감")
            .setContentText("오늘의 매매가 종료되었습니다. 봇은 밤새 대기 후 내일 9시에 재개합니다.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(2, notification)
    }

    private fun executeStrategy(token: String) {
        val url = HttpUrl.Builder()
            .scheme("https").host("openapi.koreainvestment.com").port(9443)
            .addPathSegments("uapi/domestic-stock/v1/ranking/fluctuation")
            .addQueryParameter("FID_COND_MRKT_DIV_CODE", "J")
            .addQueryParameter("FID_COND_SCR_DIV_CODE", "20175")
            .addQueryParameter("FID_INPUT_ISCD", "0000")
            .addQueryParameter("FID_RANK_SORT_CLS_CODE", "0")
            .addQueryParameter("FID_BLNG_CLS_CODE", "0")
            .addQueryParameter("FID_TRGT_CLS_CODE", "111111111")
            .addQueryParameter("FID_TRGT_EXLS_CLS_CODE", "000000")
            .addQueryParameter("FID_INPUT_PRICE_1", "0")
            .addQueryParameter("FID_INPUT_PRICE_2", "0")
            .addQueryParameter("FID_VOL_CNT", "0")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("authorization", "Bearer $token")
            .header("appkey", appKey).header("appsecret", appSecret)
            .header("tr_id", "FHPST01750000").header("custtype", "P").build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                val data = gson.fromJson(body, FluctuationResponse::class.java)
                data.output?.take(MONITOR_COUNT)?.forEachIndexed { index, stock ->
                    val rate = stock.changeRate.toDoubleOrNull() ?: 0.0
                    if (rate in 2.0..10.0) {
                        Timer().schedule(object : TimerTask() {
                            override fun run() { if (isRunning) processStock(token, stock) }
                        }, index * 200L)
                    }
                }
            }
        })
    }

    private fun processStock(token: String, stock: FluctuationInfo) {
        val code = stock.code
        fetchDetailedPrice(token, code) { detail ->
            val currentPrice = detail.currentPrice.toDoubleOrNull() ?: 0.0
            val monitorData = monitoringMap[code]

            if (monitorData == null) {
                fetchPrevDayData(token, code) { prevHigh, prevLow, todayOpen ->
                    val K = prevHigh - prevLow
                    val targetPrice = todayOpen + (K * 0.5)
                    monitoringMap[code] = MonitoringStock(code, stock.name, todayOpen, targetPrice)
                }
            } else {
                if (!monitorData.isBought && !monitorData.isSoldToday) {
                    val isBreakout = currentPrice >= monitorData.targetPrice
                    val isNotTooExpensive = currentPrice <= monitorData.targetPrice * 1.015
                    if (isBreakout && isNotTooExpensive && currentBoughtCount < MAX_HOLDINGS) {
                        buyStock(token, monitorData, currentPrice)
                    }
                } else if (monitorData.isBought) {
                    val grossProfitRate = (currentPrice - monitorData.buyPrice) / monitorData.buyPrice
                    val netProfitRate = grossProfitRate - TRADING_FEE
                    
                    if (!monitorData.isHalfSold && netProfitRate >= 0.015) {
                        sellStock(token, monitorData, "1차 순수익 익절(+1.5%)", isPartial = true)
                    } else if (monitorData.isHalfSold) {
                        if (netProfitRate >= 0.03) {
                            sellStock(token, monitorData, "2차 순수익 익절(+3.0%)", isPartial = false)
                        } else if (netProfitRate <= 0.0) {
                            sellStock(token, monitorData, "순수익 본전 탈출(0.0%)", isPartial = false)
                        }
                    }
                    if (netProfitRate <= -0.015) {
                        sellStock(token, monitorData, "순손실 손절(-1.5%)", isPartial = false)
                    }
                }
            }
        }
    }

    private fun buyStock(token: String, stock: MonitoringStock, currentPrice: Double) {
        stock.isBought = true; stock.buyPrice = currentPrice
        currentBoughtCount++
        val quantity = (BUY_AMOUNT_PER_STOCK / currentPrice).toInt()
        stock.quantity = quantity
        stock.remainingQuantity = quantity
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
            override fun onResponse(call: Call, response: Response) { Log.d("Trading", "✅ 실전 매수: ${stock.name}") }
        })
    }

    private fun sellStock(token: String, stock: MonitoringStock, reason: String, isPartial: Boolean) {
        val sellQty = if (isPartial) stock.quantity / 2 else stock.remainingQuantity
        if (sellQty <= 0) return

        val cano = accountNum.split("-").first()
        val acntCd = accountNum.split("-").getOrNull(1) ?: "01"
        val body = mapOf("CANO" to cano, "ACNT_PRDT_CD" to acntCd, "PDNO" to stock.code, "ORD_DVSN" to "01", "ORD_QTY" to sellQty.toString(), "ORD_UNPR" to "0")
        val requestBody = gson.toJson(body).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$baseUrl/uapi/domestic-stock/v1/trading/order-cash").post(requestBody)
            .header("authorization", "Bearer $token").header("appkey", appKey).header("appsecret", appSecret)
            .header("tr_id", "TTTC0801U").header("custtype", "P").build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                if (isPartial) {
                    stock.isHalfSold = true; stock.remainingQuantity -= sellQty
                    Log.d("Trading", "🌗 $reason: ${stock.name}")
                } else {
                    stock.isBought = false; stock.isSoldToday = true; currentBoughtCount--
                    Log.d("Trading", "🚨 $reason: ${stock.name}")
                }
            }
        })
    }

    private fun liquidateAll(token: String) {
        monitoringMap.values.filter { it.isBought }.forEach { sellStock(token, it, "장 마감 청산", isPartial = false) }
    }

    private fun fetchPrevDayData(token: String, code: String, onResult: (Double, Double, Double) -> Unit) {
        val url = "$baseUrl/uapi/domestic-stock/v1/quotations/inquire-daily-price?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=$code&FID_PERIOD_DIV_CODE=D&FID_ORG_ADJ_PRC=0"
        val request = Request.Builder().url(url).header("authorization", "Bearer $token")
            .header("appkey", appKey).header("appsecret", appSecret).header("tr_id", "FHKST01010400").header("custtype", "P").build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                val root = gson.fromJson(body, Map::class.java); val list = root["output"] as? List<Map<*, *>>
                val yesterday = list?.get(1); val today = list?.get(0)
                val prevHigh = yesterday?.get("stck_hgpr")?.toString()?.toDoubleOrNull() ?: 0.0
                val prevLow = yesterday?.get("stck_lwpr")?.toString()?.toDoubleOrNull() ?: 0.0
                val todayOpen = today?.get("stck_oprc")?.toString()?.toDoubleOrNull() ?: 0.0
                onResult(prevHigh, prevLow, todayOpen)
            }
        })
    }

    private fun fetchDetailedPrice(token: String, code: String, onResult: (PriceDetail) -> Unit) {
        val url = "$baseUrl/uapi/domestic-stock/v1/quotations/inquire-price?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=$code"
        val request = Request.Builder().url(url).header("authorization", "Bearer $token")
            .header("appkey", appKey).header("appsecret", appSecret).header("tr_id", "FHKST01010100").header("custtype", "P").build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                val root = gson.fromJson(body, Map::class.java); val output = root["output"] as? Map<*, *>
                if (output != null) onResult(PriceDetail(currentPrice = output["stck_prpr"].toString()))
            }
        })
    }

    private fun getSavedToken(): String? = getSharedPreferences("hantoo_prefs", Context.MODE_PRIVATE).getString("access_token", null)
    override fun onDestroy() { isRunning = false; monitorTimer?.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}

data class FluctuationResponse(val output: List<FluctuationInfo>?)
data class FluctuationInfo(@SerializedName("hts_kor_isnm") val name: String, @SerializedName("stck_shrn_iscd") val code: String, @SerializedName("stck_prpr") val price: String, @SerializedName("prdy_ctrt") val changeRate: String)
data class PriceDetail(val currentPrice: String)
data class MonitoringStock(
    val code: String, val name: String, val openPrice: Double, val targetPrice: Double,
    var isBought: Boolean = false, var isSoldToday: Boolean = false,
    var buyPrice: Double = 0.0, var quantity: Int = 0,
    var remainingQuantity: Int = 0, var isHalfSold: Boolean = false
)
