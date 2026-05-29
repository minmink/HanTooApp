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

    private var activeWatchlist = mutableListOf<Pair<String, String>>(
        Pair("005930", "삼성전자"), Pair("000660", "SK하이닉스"), Pair("035420", "NAVER"),
        Pair("247540", "에코프로비엠"), Pair("086520", "에코프로"), Pair("128940", "한미반도체"),
        Pair("196170", "알테오젠"), Pair("012450", "한화에어로"), Pair("068270", "셀트리온"),
        Pair("005380", "현대차"), Pair("000270", "기아"), Pair("035720", "카카오")
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
        Log.e("HANTOO_LOG", "▶ 자동화 엔진 가동 시작")
        
        monitorTimer = timer(period = 60000L) {
            if (!isRunning) return@timer
            val now = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul"))
            val timeValue = now.get(Calendar.HOUR_OF_DAY) * 100 + now.get(Calendar.MINUTE)
            
            if (timeValue == 850) issueTokenInternally()

            val token = getSavedToken() ?: return@timer
            if (timeValue in 900..905 && now.get(Calendar.MINUTE) % 5 == 0) refreshDailyRanking(token)

            if (timeValue in 900..1514) {
                Log.d("HANTOO_LOG", "⏰ 감시 중: ${activeWatchlist.size}개 종목")
                activeWatchlist.forEachIndexed { index, stock ->
                    Timer().schedule(object : TimerTask() {
                        override fun run() { checkStockStrategy(token, stock.first, stock.second) }
                    }, index * 1200L)
                }
            } else if (timeValue in 1515..1525) {
                liquidateAll(token)
            }
        }
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
                    Log.e("HANTOO_LOG", "🚨 장 마감 청산 주문: ${stock.prdtName}")
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
                val data = gson.fromJson(response.body?.string(), RankingBotData::class.java)
                val stocks = data.output?.take(30)
                if (!stocks.isNullOrEmpty()) {
                    activeWatchlist.clear()
                    stocks.forEach { activeWatchlist.add(Pair(it.code, it.name)) }
                    Log.e("HANTOO_LOG", "🔥 오늘의 전장 30개 선정 완료")
                }
            }
        })
    }

    private fun checkStockStrategy(token: String, code: String, name: String) {
        // [전략 체크 로직]
    }

    private fun setupForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "자동매매 서비스", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("24시간 자동매매 봇 가동 중").setSmallIcon(android.R.drawable.ic_media_play).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
    }

    private fun getSavedToken(): String? = getSharedPreferences("hantoo_prefs", Context.MODE_PRIVATE).getString("access_token", null)
    override fun onDestroy() { isRunning = false; monitorTimer?.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
