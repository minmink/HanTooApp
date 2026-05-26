package com.mink.hantoo

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.concurrent.timer

class TradingService : Service() {
    companion object {
        const val ACTION_TRADING_STOPPED = "com.mink.hantoo.ACTION_TRADING_STOPPED"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
        
    private val gson = Gson()
    private var isRunning = false
    private var monitorTimer: Timer? = null

    private val baseUrl = "https://openapi.koreainvestment.com:9443" 
    private val appKey = BuildConfig.HANTOO_APP_KEY
    private val appSecret = BuildConfig.HANTOO_APP_SECRET
    private val accountNum = BuildConfig.HANTOO_ACCOUNT_NUMBER

    private val BUY_AMOUNT_PER_STOCK = 1_000_000.0 
    private val MAX_HOLDINGS = 5 
    private val CHECK_INTERVAL = 30000L // 30초마다 체크
    private val TRADING_FEE = 0.0016 

    private val fallbackStocks = listOf(
        Pair("005930", "삼성전자"), Pair("000660", "SK하이닉스"), Pair("035420", "NAVER"),
        Pair("005380", "현대차"), Pair("035720", "카카오"), Pair("000270", "기아"),
        Pair("068270", "셀트리온"), Pair("105560", "KB금융"), Pair("055550", "신한지주"),
        Pair("005490", "POSCO홀딩스"), Pair("032830", "삼성생명"), Pair("012330", "현대모비스"),
        Pair("066570", "LG전자"), Pair("034730", "SK"), Pair("015760", "한국전력"),
        Pair("034220", "LG디스플레이"), Pair("009150", "삼성전기"), Pair("086790", "하나금융지주"),
        Pair("010950", "S-Oil"), Pair("033780", "KT&G")
    )

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
        Log.d("HANTOO_LOG", "--------------------------------------")
        Log.d("HANTOO_LOG", "▶ 자동매매 엔진 가동 (9443 포트 모니터링 모드)")
        Log.d("HANTOO_LOG", "--------------------------------------")
    }

    private fun startForegroundService() {
        val channelId = "hantoo_trading_channel"
        val channel = NotificationChannel(channelId, "자동매매 서비스", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        startForeground(1, NotificationCompat.Builder(this, channelId)
            .setContentTitle("한투 자동매매 엔진 가동 중")
            .setContentText("실시간으로 시장을 감시하며 기회를 찾고 있습니다.")
            .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).build())
    }

    private fun startTradingLogic() {
        monitorTimer = timer(period = CHECK_INTERVAL) {
            if (isRunning) {
                val token = getSavedToken() ?: return@timer
                val now = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul"))
                val hour = now.get(Calendar.HOUR_OF_DAY)
                val minute = now.get(Calendar.MINUTE)
                val currentTimeValue = hour * 100 + minute

                if (currentTimeValue in 900..1514) {
                    executeStrategy(token)
                } else if (currentTimeValue in 1515..1529) {
                    liquidateAll(token)
                } else {
                    Log.d("HANTOO_LOG", "💤 현재는 장외 시간입니다. 대기 중...")
                }
            }
        }
    }

    private fun executeStrategy(token: String) {
        // [포트 제약 해결] 리스트 조회를 건너뛰고 바로 우량주 20개 감시 프로세스로 진입
        Log.d("HANTOO_LOG", "⏰ 전략 체크 턴 (${monitoringMap.size}개 종목 감시 중)")
        fallbackStocks.forEachIndexed { index, stock ->
            Timer().schedule(object : TimerTask() { 
                override fun run() { if (isRunning) processStock(token, stock.first, stock.second) } 
            }, index * 400L)
        }
    }

    private fun processStock(token: String, code: String, name: String) {
        fetchDetailedPrice(token, code) { currentPrice ->
            val monitorData = monitoringMap[code]
            if (monitorData == null) {
                fetchPrevDayData(token, code) { prevHigh, prevLow, todayOpen ->
                    val K = (prevHigh - prevLow) * 0.5
                    val targetPrice = todayOpen + K
                    monitoringMap[code] = MonitoringStock(code, name, todayOpen, targetPrice)
                    Log.d("HANTOO_LOG", "🔍 감시등록: [$name] 현재가: $currentPrice / 타겟: $targetPrice")
                }
            } else {
                if (!monitorData.isBought && !monitorData.isSoldToday) {
                    val target = monitorData.targetPrice
                    if (currentPrice >= target && currentPrice <= target * 1.015) {
                        if (currentBoughtCount < MAX_HOLDINGS) {
                            Log.d("HANTOO_LOG", "🚀 돌파 발견! 매수 주문: $name (현재가: $currentPrice)")
                            buyStock(token, monitorData, currentPrice)
                        }
                    } else if (currentPrice > target * 1.015) {
                        // 너무 많이 올랐을 때 로그 (사용자 궁금증 해소용)
                        // Log.d("HANTOO_LOG", "⏭️ $name: 이미 타겟가보다 너무 높음 (+1.5% 초과). 매수 제외.")
                    }
                } else if (monitorData.isBought) {
                    val netProfit = (currentPrice - monitorData.buyPrice) / monitorData.buyPrice - TRADING_FEE
                    if (!monitorData.isHalfSold && netProfit >= 0.015) sellStock(token, monitorData, "1.5% 익절")
                    else if (monitorData.isHalfSold && (netProfit >= 0.03 || netProfit <= 0.0)) sellStock(token, monitorData, "완전익절/본전")
                    else if (netProfit <= -0.015) sellStock(token, monitorData, "1.5% 손절")
                }
            }
        }
    }

    private fun buyStock(token: String, stock: MonitoringStock, currentPrice: Double) {
        stock.isBought = true; stock.buyPrice = currentPrice; currentBoughtCount++
        val quantity = (BUY_AMOUNT_PER_STOCK / currentPrice).toInt()
        if (quantity <= 0) return
        val cano = accountNum.split("-").first(); val acntCd = accountNum.split("-").getOrNull(1) ?: "01"
        val body = mapOf("CANO" to cano, "ACNT_PRDT_CD" to acntCd, "PDNO" to stock.code, "ORD_DVSN" to "01", "ORD_QTY" to quantity.toString(), "ORD_UNPR" to "0")
        client.newCall(Request.Builder().url("$baseUrl/uapi/domestic-stock/v1/trading/order-cash").post(gson.toJson(body).toRequestBody("application/json".toMediaType())).header("authorization", "Bearer $token").header("appkey", appKey).header("appsecret", appSecret).header("tr_id", "TTTC0802U").header("custtype", "P").build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { currentBoughtCount--; stock.isBought = false }
            override fun onResponse(call: Call, response: Response) { Log.d("HANTOO_LOG", "✅ 실전 매수 성공: ${stock.name}") }
        })
    }

    private fun sellStock(token: String, stock: MonitoringStock, reason: String) {
        val cano = accountNum.split("-").first(); val acntCd = accountNum.split("-").getOrNull(1) ?: "01"
        val body = mapOf("CANO" to cano, "ACNT_PRDT_CD" to acntCd, "PDNO" to stock.code, "ORD_DVSN" to "01", "ORD_QTY" to "1", "ORD_UNPR" to "0")
        client.newCall(Request.Builder().url("$baseUrl/uapi/domestic-stock/v1/trading/order-cash").post(gson.toJson(body).toRequestBody("application/json".toMediaType())).header("authorization", "Bearer $token").header("appkey", appKey).header("appsecret", appSecret).header("tr_id", "TTTC0801U").header("custtype", "P").build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                stock.isBought = false; stock.isSoldToday = true; currentBoughtCount--
                Log.d("HANTOO_LOG", "🚨 실전 매도 완료 ($reason): ${stock.name}")
            }
        })
    }

    private fun liquidateAll(token: String) { monitoringMap.values.filter { it.isBought }.forEach { sellStock(token, it, "장 마감 청산") } }

    private fun fetchPrevDayData(token: String, code: String, onResult: (Double, Double, Double) -> Unit) {
        val url = "$baseUrl/uapi/domestic-stock/v1/quotations/inquire-daily-price?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=$code&FID_PERIOD_DIV_CODE=D&FID_ORG_ADJ_PRC=0"
        client.newCall(Request.Builder().url(url).header("authorization", "Bearer $token").header("appkey", appKey).header("appsecret", appSecret).header("tr_id", "FHKST01010400").header("custtype", "P").build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                val root = gson.fromJson(body, Map::class.java); val list = root["output"] as? List<Map<*, *>>
                if (list != null && list.size >= 2) {
                    val prev = list[1]; val today = list[0]
                    onResult(prev["stck_hgpr"].toString().toDouble(), prev["stck_lwpr"].toString().toDouble(), today["stck_oprc"].toString().toDouble())
                }
            }
        })
    }

    private fun fetchDetailedPrice(token: String, code: String, onResult: (Double) -> Unit) {
        val url = "$baseUrl/uapi/domestic-stock/v1/quotations/inquire-price?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=$code"
        client.newCall(Request.Builder().url(url).header("authorization", "Bearer $token").header("appkey", appKey).header("appsecret", appSecret).header("tr_id", "FHKST01010100").header("custtype", "P").build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                try {
                    val root = gson.fromJson(body, Map::class.java); val output = root["output"] as? Map<*, *>
                    if (output != null) onResult(output["stck_prpr"].toString().toDouble())
                } catch (e: Exception) {}
            }
        })
    }

    private fun getSavedToken(): String? = getSharedPreferences("hantoo_prefs", Context.MODE_PRIVATE).getString("access_token", null)
    override fun onDestroy() { isRunning = false; monitorTimer?.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}

data class MonitoringStock(val code: String, val name: String, val openPrice: Double, val targetPrice: Double, var isBought: Boolean = false, var isSoldToday: Boolean = false, var buyPrice: Double = 0.0, var isHalfSold: Boolean = false)
