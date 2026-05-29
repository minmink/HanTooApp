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
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
import java.util.concurrent.TimeUnit
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
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            Log.e("HANTOO_LOG", "❌ SSL 초기화 실패")
            OkHttpClient.Builder().build()
        }
    }
        
    private val gson = Gson()
    private val baseUrl = "https://openapi.koreainvestment.com:9443"
    private val appKey = BuildConfig.HANTOO_APP_KEY
    private val appSecret = BuildConfig.HANTOO_APP_SECRET
    private val accountNum = BuildConfig.HANTOO_ACCOUNT_NUMBER

    private val krWatchlist = listOf(
        Pair("005930", "삼성전자"), Pair("000660", "SK하이닉스"), Pair("128940", "한미반도체"),
        Pair("373220", "LG엔솔"), Pair("247540", "에코프로비엠"), Pair("086520", "에코프로"),
        Pair("003670", "POSCO퓨처엠"), Pair("018260", "삼성SDS"), Pair("066570", "LG전자"),
        Pair("005380", "현대차"), Pair("000270", "기아"), Pair("012330", "현대모비스")
    )

    private val usWatchlist = listOf(
        Pair("DELL", "델 테크놀로지스"), Pair("MU", "마이크론 테크놀로지")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HantooTheme {
                val context = LocalContext.current
                var selectedTab by remember { mutableIntStateOf(0) }
                val tabs = listOf("국내 주식", "미국 테스트")
                
                var isTrading by remember { mutableStateOf(isServiceRunning(context)) }
                val stockPrices = remember { mutableStateMapOf<String, String>() }
                val targetPrices = remember { mutableStateMapOf<String, String>() }
                
                var selectedStockCode by remember { mutableStateOf<String?>(null) }
                var selectedStockName by remember { mutableStateOf<String?>(null) }

                fun startDataUpdate(token: String) {
                    (krWatchlist + usWatchlist).forEachIndexed { index, stock ->
                        Timer().schedule(object : TimerTask() {
                            override fun run() { 
                                val isUS = index >= krWatchlist.size
                                fetchCurrentPrice(token, stock.first, isUS) { p -> stockPrices[stock.first] = p }
                                if (!isUS) {
                                    fetchPriceAndTarget(token, stock.first) { _, tgt -> targetPrices[stock.first] = tgt }
                                }
                            }
                        }, index * 800L)
                    }
                }

                LaunchedEffect(Unit) {
                    checkAndIssueToken { token -> if (token != null) startDataUpdate(token) }
                }

                if (selectedStockCode != null) {
                    BackHandler { selectedStockCode = null; selectedStockName = null }
                    Scaffold(
                        topBar = { 
                            TopAppBar(
                                title = { Text(selectedStockName ?: "상세보기") },
                                navigationIcon = {
                                    IconButton(onClick = { selectedStockCode = null }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로가기")
                                    }
                                }
                            )
                        }
                    ) { StockDetailWebScreen(selectedStockCode!!, Modifier.padding(it)) }
                } else {
                    Scaffold(
                        topBar = { 
                            Column {
                                CenterAlignedTopAppBar(title = { Text("Hantoo 통합 대시보드", fontWeight = FontWeight.Bold) })
                                TabRow(selectedTabIndex = selectedTab) {
                                    tabs.forEachIndexed { index, title ->
                                        Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title) })
                                    }
                                }
                            }
                        }
                    ) { innerPadding ->
                        Column(Modifier.padding(innerPadding).fillMaxSize()) {
                            if (selectedTab == 0) {
                                TradingControlPanel(isTrading, onStart = {
                                    context.startForegroundService(Intent(context, TradingService::class.java))
                                    isTrading = true
                                }, onStop = {
                                    context.stopService(Intent(context, TradingService::class.java))
                                    isTrading = false
                                })
                                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                                    items(krWatchlist) { stock ->
                                        StockRowWithTarget(
                                            name = stock.second, 
                                            price = stockPrices[stock.first] ?: "로드 중...", 
                                            target = targetPrices[stock.first] ?: "-",
                                            onClick = {
                                                selectedStockCode = stock.first
                                                selectedStockName = stock.second
                                            }
                                        )
                                        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Color.LightGray)
                                    }
                                }
                            } else {
                                Text("🇺🇸 미장 실전 테스트 (1주 주문)", Modifier.padding(16.dp), color = Color.Red, fontWeight = FontWeight.Bold)
                                LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
                                    items(usWatchlist) { stock ->
                                        USStockRow(stock.second, stock.first, stockPrices[stock.first] ?: "로드 중...", onBuy = {
                                            checkAndIssueToken { token -> if (token != null) buyUSStock(token, stock.first, stockPrices[stock.first] ?: "0") }
                                        })
                                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun isServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (TradingService::class.java.name == service.service.className) return true
        }
        return false
    }

    private fun checkAndIssueToken(forceRefresh: Boolean = false, onComplete: (String?) -> Unit) {
        val prefs = getSharedPreferences("hantoo_prefs", Context.MODE_PRIVATE)
        val savedToken = if (forceRefresh) null else prefs.getString("access_token", null)
        if (savedToken != null) { onComplete(savedToken); return }
        
        val url = "$baseUrl/oauth2/tokenP"
        val bodyMap = mapOf("grant_type" to "client_credentials", "appkey" to appKey, "appsecret" to appSecret)
        client.newCall(Request.Builder().url(url).post(gson.toJson(bodyMap).toRequestBody("application/json".toMediaType())).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { onComplete(null) }
            override fun onResponse(call: Call, response: Response) {
                val res = gson.fromJson(response.body?.string(), TokenBotData::class.java)
                prefs.edit().putString("access_token", res?.accessToken).apply()
                onComplete(res?.accessToken)
            }
        })
    }

    private fun fetchPriceAndTarget(token: String, code: String, onResult: (String, String) -> Unit) {
        val request = Request.Builder().url("$baseUrl/uapi/domestic-stock/v1/quotations/inquire-price?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=$code").header("authorization", "Bearer $token").header("appkey", appKey).header("appsecret", appSecret).header("tr_id", "FHKST01010100").header("custtype", "P").build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                val root = gson.fromJson(body, Map::class.java) ?: return
                val out = root["output"] as? Map<*, *>
                if (out != null) {
                    val cur = out["stck_prpr"]?.toString() ?: "0"
                    val open = out["stck_oprc"]?.toString()?.toDoubleOrNull() ?: 0.0
                    runOnUiThread { onResult(cur, String.format(Locale.getDefault(), "%,.0f", open * 1.005)) }
                }
            }
        })
    }

    private fun fetchCurrentPrice(token: String, code: String, isUS: Boolean, onResult: (String) -> Unit) {
        val url = if (isUS) "$baseUrl/uapi/overseas-stock/v1/quotations/price?AUTH=&EXCD=${if(code=="MU") "NAS" else "NYS"}&SYMB=$code"
        else "$baseUrl/uapi/domestic-stock/v1/quotations/inquire-price?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=$code"
        
        client.newCall(Request.Builder().url(url).header("authorization", "Bearer $token").header("appkey", appKey).header("appsecret", appSecret).header("tr_id", if(isUS) "HHDFS00000300" else "FHKST01010100").build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { Log.e("HANTOO_LOG", "❌ 가격 조회 통신 실패: $code") }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                try {
                    val root = gson.fromJson(body, Map::class.java) ?: return
                    val out = root["output"] as? Map<*, *>
                    if (out != null) {
                        val price = out[if(isUS) "last" else "stck_prpr"]?.toString() ?: "0"
                        runOnUiThread { onResult(price) }
                    }
                } catch (e: Exception) {
                    Log.e("HANTOO_LOG", "❌ 가격 파싱 에러 ($code): ${e.message}")
                }
            }
        })
    }

    private fun buyUSStock(token: String, symbol: String, price: String, isRetry: Boolean = false, useVT: Boolean = false) {
        val cano = accountNum.split("-").first(); val acntCd = accountNum.split("-").getOrNull(1) ?: "01"
        val exchCd = if(symbol=="MU") "NASD" else "NYSE"
        val trId = if(useVT) "VTTT1002U" else "TTTT1002U"
        
        val body = mapOf("CANO" to cano, "ACNT_PRDT_CD" to acntCd, "OVRS_EXCG_CD" to exchCd, "PDNO" to symbol, "ORD_DVSN" to "00", "ORD_QTY" to "1", "ORD_UNPR" to price.replace(",", ""))
        val request = Request.Builder().url("$baseUrl/uapi/overseas-stock/v1/trading/order").post(gson.toJson(body).toRequestBody("application/json".toMediaType())).header("authorization", "Bearer $token").header("appkey", appKey).header("appsecret", appSecret).header("tr_id", trId).header("custtype", "P").build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { Log.e("HANTOO_LOG", "❌ 주문 실패: ${e.message}") }
            override fun onResponse(call: Call, response: Response) {
                val resBody = response.body?.string() ?: ""
                Log.e("HANTOO_LOG", "🚀 해외 주문 응답: $resBody")
                if (resBody.contains("EGW00123") && !isRetry) {
                    checkAndIssueToken(forceRefresh = true) { buyUSStock(it!!, symbol, price, true, useVT) }
                } else if (resBody.contains("IGW00036") && !useVT) {
                    buyUSStock(token, symbol, price, isRetry, true)
                }
            }
        })
    }
}

@Composable fun TradingControlPanel(isTrading: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = if(isTrading) Color(0xFFE8F5E9) else Color(0xFFFFEBEE))) {
        Row(Modifier.padding(16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text(if (isTrading) "국내 엔진 가동 중" else "국내 엔진 정찰 중지", fontWeight = FontWeight.Bold)
            Button(onClick = if (isTrading) onStop else onStart, colors = ButtonDefaults.buttonColors(containerColor = if(isTrading) Color.Red else Color(0xFF4CAF50))) { Text(if (isTrading) "중지" else "가동") }
        }
    }
}

@Composable fun StockRowWithTarget(name: String, price: String, target: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 4.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Column {
            Text(name, fontWeight = FontWeight.Bold)
            Text("현재가: ${if(price=="로드 중...") price else String.format(Locale.getDefault(), "%,.0f원", price.toDoubleOrNull()?:0.0)}", style = MaterialTheme.typography.bodySmall)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("매수 목표가", style = MaterialTheme.typography.labelSmall, color = Color.Red)
            Text(target, fontWeight = FontWeight.Bold, color = Color.Red)
        }
    }
}

@Composable fun USStockRow(name: String, symbol: String, price: String, onBuy: () -> Unit) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Column {
            Text(name, fontWeight = FontWeight.Bold); Text("$symbol | $price $", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        Button(onClick = onBuy, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) { Text("1주 매수") }
    }
}

@SuppressLint("SetJavaScriptEnabled") @Composable fun StockDetailWebScreen(code: String, modifier: Modifier = Modifier) { AndroidView(factory = { context -> WebView(context).apply { webViewClient = WebViewClient(); settings.javaScriptEnabled = true; loadUrl("https://m.stock.naver.com/domestic/stock/$code/total") } }, modifier = modifier.fillMaxSize()) }
