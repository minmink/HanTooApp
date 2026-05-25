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
            .setContentText("종목당 100만원, 최대 5종목 감시 중...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

        startForeground(1, notification)
    }

    private fun startTradingLogic() {
        monitorTimer = timer(period = CHECK_INTERVAL) {
            if (isRunning) {
                val token = getSavedToken() ?: return@timer
                
                // 먼저 현재 실제로 몇 종목 보유 중인지 체크 (서버 데이터 기반으로 업데이트 할 수도 있음)
                // 여기서는 안전하게 매수 시도 전마다 체크
                executeStrategy(token)
            }
        }
    }

    private fun executeStrategy(token: String) {
        // 거래대금 상위 종목 조회 (코스피 기준)
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
                data.output?.forEach { stock ->
                    processStock(token, stock)
                }
            }
        })
    }

    private fun processStock(token: String, stock: TradeValueInfo) {
        val code = stock.code
        val currentPrice = stock.price.toDoubleOrNull() ?: return

        val monitorData = monitoringMap[code]
        if (monitorData == null) {
            fetchOpenPrice(token, code) { openPrice ->
                val targetPrice = openPrice * 1.02
                monitoringMap[code] = MonitoringStock(code, stock.name, openPrice, targetPrice)
                Log.d("Trading", "[${stock.name}] 감시 리스트 등록 - 시가: $openPrice, 목표가: $targetPrice")
            }
        } else if (!monitorData.isBought && currentPrice >= monitorData.targetPrice) {
            // 현재 보유 종목 수가 5개 미만일 때만 매수
            if (currentBoughtCount < MAX_HOLDINGS) {
                buyStock(token, monitorData, currentPrice)
            } else {
                Log.d("Trading", "최대 보유 종목 수($MAX_HOLDINGS) 도달로 [${stock.name}] 매수 건너뜀")
            }
        }
    }

    private fun fetchOpenPrice(token: String, code: String, onResult: (Double) -> Unit) {
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
                val json = gson.fromJson(body, Map::class.java)["output"] as? Map<*, *>
                val openPrice = (json?.get("stck_oprc") as? String)?.toDoubleOrNull()
                if (openPrice != null) onResult(openPrice)
            }
        })
    }

    private fun buyStock(token: String, stock: MonitoringStock, currentPrice: Double) {
        stock.isBought = true 
        currentBoughtCount++ // 카운트 선증가 (중복 방지)

        // 100만원치 수량 계산
        val quantity = (BUY_AMOUNT_PER_STOCK / currentPrice).toInt()
        if (quantity <= 0) {
            Log.d("Trading", "[${stock.name}] 가격이 100만원보다 비싸서 매수 불가")
            return
        }

        val cano = accountNum.split("-").first()
        val acntCd = accountNum.split("-").getOrNull(1) ?: "01"

        val body = mapOf(
            "CANO" to cano,
            "ACNT_PRDT_CD" to acntCd,
            "PDNO" to stock.code,
            "ORD_DVSN" to "01", // 시장가
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
                Log.e("Trading", "매수 실패: ${stock.name}", e)
                currentBoughtCount-- // 실패 시 카운트 복구
                stock.isBought = false
            }
            override fun onResponse(call: Call, response: Response) {
                val resBody = response.body?.string()
                Log.d("Trading", "매수 주문 결과 [${stock.name}]: $resBody")
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

data class MonitoringStock(val code: String, val name: String, val openPrice: Double, val targetPrice: Double, var isBought: Boolean = false)
