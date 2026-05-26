package com.mink.hantoo

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.mink.hantoo.ui.theme.HantooTheme
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.*
import javax.net.ssl.*

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    
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
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            Log.d("HANTOO_LOG", "❌ SSL 초기화 실패")
            OkHttpClient.Builder().build()
        }
    }
        
    private val gson = Gson()
    private val baseUrl = "https://openapi.koreainvestment.com:9443"
    private val appKey = BuildConfig.HANTOO_APP_KEY
    private val appSecret = BuildConfig.HANTOO_APP_SECRET

    private val masterWatchlist = listOf(
        Pair("005930", "삼성전자"), Pair("000660", "SK하이닉스"), Pair("035420", "NAVER"),
        Pair("247540", "에코프로비엠"), Pair("086520", "에코프로"), Pair("128940", "한미반도체"),
        Pair("196170", "알테오젠"), Pair("012450", "한화에어로"), Pair("068270", "셀트리온"),
        Pair("005380", "현대차"), Pair("000270", "기아"), Pair("035720", "카카오"),
        Pair("105560", "KB금융"), Pair("055550", "신한지주"), Pair("005490", "POSCO홀딩스"),
        Pair("003670", "포스코퓨처엠"), Pair("047050", "포스코인터"), Pair("034020", "두산에너빌리티"),
        Pair("373220", "LG엔솔"), Pair("051910", "LG화학"), Pair("006400", "삼성SDI"),
        Pair("066570", "LG전자"), Pair("011070", "LG이노텍"), Pair("012330", "현대모비스"),
        Pair("000100", "유한양행"), Pair("028300", "HLB"), Pair("096530", "씨젠"),
        Pair("259960", "크래프톤"), Pair("326030", "SK바이오팜"), Pair("033780", "KT&G")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        enableEdgeToEdge()
        setContent {
            HantooTheme {
                val context = LocalContext.current
                var isTrading by remember { mutableStateOf(isServiceRunning(context)) }
                var statusMessage by remember { mutableStateOf("준비 완료") }
                val stockPrices = remember { mutableStateMapOf<String, String>() }
                val targetPrices = remember { mutableStateMapOf<String, String>() }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { if (!it) Log.d("HANTOO_LOG", "❌ 알림 권한 거부") }

                fun loadStocks() {
                    statusMessage = "데이터 동기화 중..."
                    checkAndIssueToken { token ->
                        if (token != null) {
                            masterWatchlist.forEachIndexed { index, stock ->
                                Timer().schedule(object : TimerTask() {
                                    override fun run() { 
                                        fetchPriceAndTarget(token, stock.first) { cur, tgt ->
                                            stockPrices[stock.first] = cur
                                            targetPrices[stock.first] = tgt
                                            statusMessage = "${stock.second} 동기화"
                                        }
                                    }
                                }, index * 700L) // 0.7초로 간격을 더 늘려 안정성 확보
                            }
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    loadStocks()
                }

                Scaffold(
                    topBar = { 
                        CenterAlignedTopAppBar(
                            title = { Text("실전 자동매매 (30개)", fontWeight = FontWeight.Bold) },
                            actions = {
                                IconButton(onClick = { loadStocks() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "새로고침")
                                }
                            }
                        ) 
                    }
                ) { innerPadding ->
                    Column(Modifier.padding(innerPadding).fillMaxSize()) {
                        Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = if(isTrading) Color(0xFFE8F5E9) else Color(0xFFFFEBEE))) {
                            Column(Modifier.padding(16.dp)) {
                                Text("엔진: ${if(isTrading) "가동 중" else "중지됨"} | $statusMessage", fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(12.dp))
                                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                                    Button(modifier = Modifier.weight(1f), onClick = {
                                        context.startForegroundService(Intent(context, TradingService::class.java))
                                        isTrading = true
                                    }, enabled = !isTrading) { Text("가동") }
                                    Button(modifier = Modifier.weight(1f), onClick = {
                                        context.stopService(Intent(context, TradingService::class.java))
                                        isTrading = false
                                    }, enabled = isTrading) { Text("중지") }
                                }
                            }
                        }
                        
                        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                            items(masterWatchlist) { stock ->
                                val price = stockPrices[stock.first] ?: "로드 중"
                                val isError = price == "에러" || price == "실패"
                                
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { 
                                            // 클릭 시 해당 종목만 재시도
                                            stockPrices[stock.first] = "재시도 중..."
                                            checkAndIssueToken { token ->
                                                if (token != null) fetchPriceAndTarget(token, stock.first) { cur, tgt ->
                                                    stockPrices[stock.first] = cur
                                                    targetPrices[stock.first] = tgt
                                                }
                                            }
                                        },
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(stock.second, fontWeight = FontWeight.Bold)
                                        Text(
                                            text = "현재가: $price", 
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if(isError) Color.Red else Color.Unspecified
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("목표가", style = MaterialTheme.typography.labelSmall, color = Color.Red)
                                        Text(targetPrices[stock.first] ?: "-", fontWeight = FontWeight.Bold, color = Color.Red)
                                    }
                                }
                                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Color.LightGray)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun isServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (TradingService::class.java.name == service.service.className) return true
        }
        return false
    }

    private fun checkAndIssueToken(onResult: (String?) -> Unit) {
        val prefs = getSharedPreferences("hantoo_prefs", Context.MODE_PRIVATE)
        val savedToken = prefs.getString("access_token", null)
        if (savedToken != null) { onResult(savedToken); return }
        
        val bodyMap = mapOf("grant_type" to "client_credentials", "appkey" to appKey, "appsecret" to appSecret)
        val request = Request.Builder().url("$baseUrl/oauth2/tokenP").post(gson.toJson(bodyMap).toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { onResult(null) }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                val res = gson.fromJson(body, TokenBotData::class.java)
                prefs.edit().putString("access_token", res?.accessToken).apply()
                onResult(res?.accessToken)
            }
        })
    }

    private fun fetchPriceAndTarget(token: String, code: String, onResult: (String, String) -> Unit) {
        val request = Request.Builder().url("$baseUrl/uapi/domestic-stock/v1/quotations/inquire-price?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=$code").header("authorization", "Bearer $token").header("appkey", appKey).header("appsecret", appSecret).header("tr_id", "FHKST01010100").header("custtype", "P").build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { 
                runOnUiThread { onResult("실패", "실패") }
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                val root = gson.fromJson(body, Map::class.java); val out = root["output"] as? Map<*, *>
                if (out != null) {
                    val cur = out["stck_prpr"]?.toString() ?: "0"
                    val open = out["stck_oprc"]?.toString()?.toDoubleOrNull() ?: 0.0
                    runOnUiThread { onResult(cur, String.format(Locale.getDefault(), "%,.0f", open * 1.005)) }
                } else {
                    runOnUiThread { onResult("에러", "에러") }
                }
            }
        })
    }
}
