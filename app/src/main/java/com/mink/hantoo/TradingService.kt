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
    private val client = OkHttpClient()
    private val gson = Gson()
    private var isRunning = false
    private var monitorTimer: Timer? = null

    private val baseUrl = "https://openapivts.koreainvestment.com:29443"
    private val appKey = BuildConfig.HANTOO_APP_KEY
    private val appSecret = BuildConfig.HANTOO_APP_SECRET
    private val accountNum = BuildConfig.HANTOO_ACCOUNT_NUMBER

    // 설정 값
    private val BUY_AMOUNT_PER_STOCK = 1_000_000.0 // 종목당 100만원
    private val MAX_HOLDINGS = 5 // 최대 5종목
    private val CHECK_INTERVAL = 10000L // 10초
    private val MONITOR_COUNT = 20 // 감시할 거래대금 상위 종목 수 (API 부하 방지용)

    // 종목 코드별 감시 데이터
    private val monitoringMap = mutableMapOf<String, MonitoringStock>()
    // 현재 실제 보유 종목 수 (세션 내 관리)
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
    }

    private fun startForegroundService() {
        val channelId = "hantoo_trading_channel"
        val channel = NotificationChannel(channelId, "자동매매 서비스", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("한투 자동매매 가동 중")
            .setContentText("고도화된 단타 전략(이평선/체결강도/VI) 감시 중...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

        startForeground(1, notification)
    }

    private fun startTradingLogic() {
        monitorTimer = timer(period = CHECK_INTERVAL) {
            if (isRunning) {
                val token = getSavedToken() ?: return@timer
                executeStrategy(token)
            }
        }
    }

    private fun executeStrategy(token: String) {
        // 거래대금 상위 종목 조회
        val url = HttpUrl.Builder()
            .scheme("https").host("openapivts.koreainvestment.com").port(29443)
            .addPathSegments("uapi/domestic-stock/v1/ranking/trade-value")
            .addQueryParameter("FID_COND_MRKT_DIV_CODE", "J")
            .addQueryParameter("FID_COND_SCR_DIV_CODE", "20176")
            .addQueryParameter("FID_INPUT_ISCD", "0000")
            .addQueryParameter("FID_DIV_CLS_CODE", "0")
            .addQueryParameter("FID_BLNG_CLS_CODE", "0")
            .addQueryParameter("FID_TRGT_CLS_CODE", "111111111")
            .addQueryParameter("FID_TRGT_EXLS_CLS_CODE", "000000")
            .addQueryParameter("FID_INPUT_PRICE_1", "")
            .addQueryParameter("FID_INPUT_PRICE_2", "")
            .addQueryParameter("FID_VOL_CNT", "")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("authorization", "Bearer $token")
            .header("appkey", appKey)
            .header("appsecret", appSecret)
            .header("tr_id", "FHPST01760000")
            .header("custtype", "P")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                val data = gson.fromJson(body, TradeValueResponse::class.java)
                // 상위 MONITOR_COUNT개 종목만 분석하여 API 부하 방지
                data.output?.take(MONITOR_COUNT)?.forEachIndexed { index, stock ->
                    // 각 종목 조회를 약간의 시차를 두고 실행 (burst 방지)
                    Timer().schedule(object : TimerTask() {
                        override fun run() {
                            if (isRunning) processStock(token, stock)
                        }
                    }, index * 200L) // 0.2초 간격으로 분산
                }
            }
        })
    }

    private fun processStock(token: String, stock: TradeValueInfo) {
        val code = stock.code
        
        // 1. 상세 정보 조회 (현재가, 체결강도, VI가격 등)
        fetchDetailedPrice(token, code) { detail ->
            val strength = detail.strength.toDoubleOrNull() ?: 0.0
            val currentPrice = detail.currentPrice.toDoubleOrNull() ?: 0.0
            val viPrice = detail.viPrice.toDoubleOrNull() ?: 0.0
            val openPrice = detail.openPrice.toDoubleOrNull() ?: 0.0
            
            // 2. 분봉 데이터 조회 (이평선 계산용)
            fetchMinutesChart(token, code) { candles ->
                val ma3 = candles.take(3).map { it.close.toDouble() }.average()
                val ma5 = candles.take(5).map { it.close.toDouble() }.average()
                
                val monitorData = monitoringMap[code] ?: MonitoringStock(code, stock.name, openPrice, openPrice * 1.02)
                monitoringMap[code] = monitorData

                // 3. 통합 매수 조건 판단
                val isBreakout = currentPrice >= monitorData.targetPrice // 시가 대비 2% 돌파
                val isStrongPush = strength >= 120.0 // 체결강도 120% 이상
                val isGoldenCross = ma3 > ma5 // 3/5 이평선 골든크로스
                val isBeforeVI = currentPrice < viPrice && currentPrice >= viPrice * 0.98 // VI 직전 (2% 이내)

                if (!monitorData.isBought && currentPrice > 0 && currentBoughtCount < MAX_HOLDINGS) {
                    if (isBreakout && isStrongPush && isGoldenCross && isBeforeVI) {
                        Log.d("Trading", "🔥 타점 포착! [${stock.name}] - 현재가: $currentPrice, 체결강도: $strength%")
                        buyStock(token, monitorData, currentPrice)
                    }
                }
            }
        }
    }

    private fun fetchDetailedPrice(token: String, code: String, onResult: (PriceDetail) -> Unit) {
        val url = "$baseUrl/uapi/domestic-stock/v1/quotations/inquire-price?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=$code"
        val request = Request.Builder()
            .url(url)
            .header("authorization", "Bearer $token")
            .header("appkey", appKey)
            .header("appsecret", appSecret)
            .header("tr_id", "FHKST01010100")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                val root = gson.fromJson(body, Map::class.java)
                val output = root["output"] as? Map<*, *>
                if (output != null) {
                    onResult(PriceDetail(
                        currentPrice = output["stck_prpr"].toString(),
                        openPrice = output["stck_oprc"].toString(),
                        strength = output["tck_shnu"].toString(),
                        viPrice = output["vi_cls_prc"].toString()
                    ))
                }
            }
        })
    }

    private fun fetchMinutesChart(token: String, code: String, onResult: (List<Candle>) -> Unit) {
        val url = "$baseUrl/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=$code&FID_ETC_CLS_CODE=&FID_PW_DATA_INQR_YN=N"
        val request = Request.Builder()
            .url(url)
            .header("authorization", "Bearer $token")
            .header("appkey", appKey)
            .header("appsecret", appSecret)
            .header("tr_id", "FHKST03010200")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                val root = gson.fromJson(body, Map::class.java)
                val output2 = root["output2"] as? List<Map<*, *>>
                val candles = output2?.map { 
                    Candle(close = it["stck_prpr"].toString())
                } ?: emptyList()
                onResult(candles)
            }
        })
    }

    private fun buyStock(token: String, stock: MonitoringStock, currentPrice: Double) {
        stock.isBought = true 
        currentBoughtCount++

        val quantity = (BUY_AMOUNT_PER_STOCK / currentPrice).toInt()
        if (quantity <= 0) return

        val cano = accountNum.split("-").first()
        val acntCd = accountNum.split("-").getOrNull(1) ?: "01"

        val body = mapOf(
            "CANO" to cano,
            "ACNT_PRDT_CD" to acntCd,
            "PDNO" to stock.code,
            "ORD_DVSN" to "01",
            "ORD_QTY" to quantity.toString(),
            "ORD_UNPR" to "0"
        )

        val requestBody = gson.toJson(body).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/uapi/domestic-stock/v1/trading/order-cash")
            .post(requestBody)
            .header("authorization", "Bearer $token")
            .header("appkey", appKey)
            .header("appsecret", appSecret)
            .header("tr_id", "VTRP0020U")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                currentBoughtCount--
                stock.isBought = false
            }
            override fun onResponse(call: Call, response: Response) {
                Log.d("Trading", "✅ 매수 주문 성공: ${stock.name}")
            }
        })
    }

    private fun getSavedToken(): String? {
        return getSharedPreferences("hantoo_prefs", Context.MODE_PRIVATE).getString("access_token", null)
    }

    override fun onDestroy() {
        isRunning = false
        monitorTimer?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

// Data Models
data class TradeValueResponse(val output: List<TradeValueInfo>?)
data class TradeValueInfo(
    @SerializedName("hts_kor_isnm") val name: String,
    @SerializedName("mksc_shrn_iscd") val code: String,
    @SerializedName("stck_prpr") val price: String
)
data class PriceDetail(val currentPrice: String, val openPrice: String, val strength: String, val viPrice: String)
data class Candle(val close: String)
data class MonitoringStock(val code: String, val name: String, val openPrice: Double, val targetPrice: Double, var isBought: Boolean = false)
