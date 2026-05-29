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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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
        const val CHANNEL_ID = "hantoo_v3_channel"
    }

    private val client: OkHttpClient by lazy {
        try {
            val trustAllCerts = arrayOf<TrustManager>(@SuppressLint("CustomX509TrustManager") object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())
            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            Log.e("HANTOO_LOG", "❌ 봇 SSL 초기화 실패")
            OkHttpClient.Builder().build()
        }
    }
        
    private val gson = Gson()
    private var isRunning = false
    private var monitorTimer: Timer? = null

    private val baseUrl = "https://openapi.koreainvestment.com:9443" 
    private val appKey = BuildConfig.HANTOO_APP_KEY
    private val appSecret = BuildConfig.HANTOO_APP_SECRET
    private val accountNum = BuildConfig.HANTOO_ACCOUNT_NUMBER

    private val monitoringStocks = mutableMapOf<String, MonitoringBotData>()
    private var currentBoughtCount = 0
    private val MAX_BUY_COUNT = 5 // 동시에 최대 5종목만 운용

    private var activeWatchlist = mutableListOf<Pair<String, String>>(
        Pair("005930", "삼성전자"), Pair("000660", "SK하이닉스"), Pair("128940", "한미반도체"),
        Pair("373220", "LG엔솔"), Pair("247540", "에코프로비엠"), Pair("086520", "에코프로"),
        Pair("003670", "POSCO퓨처엠"), Pair("018260", "삼성SDS"), Pair("066570", "LG전자"),
        Pair("005380", "현대차"), Pair("000270", "기아"), Pair("012330", "현대모비스")
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            setupForeground()
            startTradingLogic()
        }
        return START_STICKY
    }

    private fun startTradingLogic() {
        Log.e("HANTOO_LOG", "▶ 자동화 엔진 [주도주 모드] 가동")
        
        monitorTimer = timer(period = 60000L) {
            if (!isRunning) return@timer
            val now = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul"))
            val timeValue = now.get(Calendar.HOUR_OF_DAY) * 100 + now.get(Calendar.MINUTE)
            
            // 8:50 토큰 갱신
            if (timeValue == 850) issueTokenInternally()

            val token = getSavedToken() ?: return@timer

            // 9:00 장 시작 시 종목 리스트 최신화 시도
            if (timeValue == 900 && now.get(Calendar.MINUTE) == 0) refreshDailyRanking(token)

            // 장중 실시간 감시 (9:00 ~ 15:14)
            if (timeValue in 900..1514) {
                activeWatchlist.forEachIndexed { index, stock ->
                    Timer().schedule(object : TimerTask() {
                        override fun run() { checkStockStrategy(token, stock.first, stock.second) }
                    }, index * 1500L) // 종목당 1.5초 간격 (TPS 방어)
                }
            } 
            // 15:15 전량 청산
            else if (timeValue in 1515..1525) {
                liquidateAll(token)
            }
        }
    }

    private fun checkStockStrategy(token: String, code: String, name: String) {
        val url = "$baseUrl/uapi/domestic-stock/v1/quotations/inquire-price?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=$code"
        val request = Request.Builder().url(url).header("authorization", "Bearer $token").header("appkey", appKey).header("appsecret", appSecret).header("tr_id", "FHKST01010100").header("custtype", "P").build()
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                val root = gson.fromJson(body, Map::class.java); val out = root["output"] as? Map<*, *> ?: return
                
                val currentPrice = out["stck_prpr"].toString().toDoubleOrNull() ?: 0.0
                val openPrice = out["stck_oprc"].toString().toDoubleOrNull() ?: 0.0
                
                val stockData = monitoringStocks[code]
                if (stockData == null) {
                    fetchTargetPrice(token, code, openPrice) { target ->
                        monitoringStocks[code] = MonitoringBotData(code, name, openPrice, target)
                        Log.d("HANTOO_LOG", "🔍 [$name] 분석등록: 현재 $currentPrice / 목표 $target")
                    }
                } else {
                    if (!stockData.isBought && !stockData.isSoldToday) {
                        if (currentPrice >= stockData.targetPrice && currentBoughtCount < MAX_BUY_COUNT) {
                            buyStock(token, stockData, currentPrice)
                        }
                    }
                }
            }
        })
    }

    private fun fetchTargetPrice(token: String, code: String, todayOpen: Double, onResult: (Double) -> Unit) {
        val url = "$baseUrl/uapi/domestic-stock/v1/quotations/inquire-daily-price?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=$code&FID_PERIOD_DIV_CODE=D&FID_ORG_ADJ_PRC=0"
        val request = Request.Builder().url(url).header("authorization", "Bearer $token").header("appkey", appKey).header("appsecret", appSecret).header("tr_id", "FHKST01010400").header("custtype", "P").build()
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                val root = gson.fromJson(body, Map::class.java); val list = root["output"] as? List<Map<*, *>>
                if (list != null && list.size >= 2) {
                    val prevHigh = list[1]["stck_hgpr"].toString().toDoubleOrNull() ?: 0.0
                    val prevLow = list[1]["stck_lwpr"].toString().toDoubleOrNull() ?: 0.0
                    val k = 0.5
                    val target = todayOpen + ((prevHigh - prevLow) * k)
                    onResult(target)
                }
            }
        })
    }

    private fun buyStock(token: String, stock: MonitoringBotData, price: Double) {
        val cano = accountNum.split("-").first()
        val acntCd = accountNum.split("-").getOrNull(1) ?: "01"
        val qty = (1_000_000 / price).toInt()
        if (qty <= 0) return

        val body = mapOf("CANO" to cano, "ACNT_PRDT_CD" to acntCd, "PDNO" to stock.code, "ORD_DVSN" to "01", "ORD_QTY" to qty.toString(), "ORD_UNPR" to "0")
        val request = Request.Builder().url("$baseUrl/uapi/domestic-stock/v1/trading/order-cash").post(gson.toJson(body).toRequestBody("application/json".toMediaType())).header("authorization", "Bearer $token").header("appkey", appKey).header("appsecret", appSecret).header("tr_id", "TTTC0802U").header("custtype", "P").build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val res = gson.fromJson(response.body?.string(), OrderBotData::class.java)
                if (res?.rt_cd == "0") {
                    stock.isBought = true; stock.buyPrice = price; currentBoughtCount++
                    Log.e("HANTOO_LOG", "✅ [${stock.name}] 매수 성공! 가격: $price")
                } else {
                    Log.e("HANTOO_LOG", "❌ [${stock.name}] 매수 실패: ${res?.msg1}")
                }
            }
        })
    }

    private fun issueTokenInternally() {
        val bodyMap = mapOf("grant_type" to "client_credentials", "appkey" to appKey, "appsecret" to appSecret)
        val request = Request.Builder().url("$baseUrl/oauth2/tokenP").post(gson.toJson(bodyMap).toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val res = gson.fromJson(response.body?.string(), TokenBotData::class.java)
                getSharedPreferences("hantoo_prefs", Context.MODE_PRIVATE).edit().putString("access_token", res?.accessToken).apply()
                Log.e("HANTOO_LOG", "✅ 토큰 자동 갱신 완료")
            }
        })
    }

    private fun liquidateAll(token: String) {
        val cano = accountNum.split("-").firstOrNull() ?: ""
        val url = "$baseUrl/uapi/domestic-stock/v1/trading/inquire-balance?CANO=$cano&ACNT_PRDT_CD=01&AFHR_FLG=N&O_STRT_DSRT_CD=00&INQR_DSRT_CD=00&FUND_STTL_ICLD_YN=N&F_P_QRD_RE_PRC_YN=N&BENF_ETCL_ICLD_YN=N&COST_ICLD_YN=N&TR_OT_DSRT_CD=00&CTX_AREA_FK100=&CTX_AREA_NK100="
        val request = Request.Builder().url(url).header("authorization", "Bearer $token").header("appkey", appKey).header("appsecret", appSecret).header("tr_id", "TTTC8434R").header("custtype", "P").build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val res = gson.fromJson(response.body?.string(), BalanceBotData::class.java)
                res?.output1?.filter { (it.hldgQty.toIntOrNull() ?: 0) > 0 }?.forEach { stock ->
                    Log.e("HANTOO_LOG", "🚨 장 마감 청산: ${stock.prdtName} 전량 매도 시도")
                }
            }
        })
    }

    private fun refreshDailyRanking(token: String) {
        val url = "$baseUrl/uapi/domestic-stock/v1/ranking/trade-value?FID_COND_MRKT_DIV_CODE=J&FID_COND_SCR_DIV_CODE=20176&FID_INPUT_ISCD=0000&FID_DIV_CLS_CODE=0&FID_BLNG_CLS_CODE=0&FID_TRGT_CLS_CODE=111111111&FID_TRGT_EXLS_CLS_CODE=000000&FID_INPUT_PRICE_1=0&FID_INPUT_PRICE_2=0&FID_VOL_CNT=0"
        val request = Request.Builder().url(url).header("authorization", "Bearer $token").header("appkey", appKey).header("appsecret", appSecret).header("tr_id", "FHPST01760000").header("custtype", "P").build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val resBody = response.body?.string() ?: return
                try {
                    val data = gson.fromJson(resBody, RankingBotData::class.java)
                    val stocks = data.output?.take(30)
                    if (!stocks.isNullOrEmpty()) {
                        activeWatchlist.clear()
                        stocks.forEach { activeWatchlist.add(Pair(it.code, it.name)) }
                        Log.e("HANTOO_LOG", "🔥 오늘의 전장 30개 선정 완료")
                    }
                } catch (e: Exception) {}
            }
        })
    }

    private fun setupForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "자동매매 서비스", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("24시간 자동매매 서버 가동 중").setSmallIcon(android.R.drawable.ic_media_play).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun getSavedToken(): String? = getSharedPreferences("hantoo_prefs", Context.MODE_PRIVATE).getString("access_token", null)
    override fun onDestroy() { isRunning = false; monitorTimer?.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
