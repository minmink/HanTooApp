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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.mink.hantoo.BuildConfig
import com.mink.hantoo.ui.theme.HantooTheme
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val client = OkHttpClient()
    private val gson = Gson()
    private val baseUrl = "https://openapivts.koreainvestment.com:29443"
    private val appKey = BuildConfig.HANTOO_APP_KEY
    private val appSecret = BuildConfig.HANTOO_APP_SECRET
    private val accountNum = BuildConfig.HANTOO_ACCOUNT_NUMBER

    private fun isServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (TradingService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HantooTheme {
                val context = LocalContext.current
                val lifecycleOwner = LocalLifecycleOwner.current
                
                var topTabSelected by remember { mutableIntStateOf(0) }
                var isTrading by remember { mutableStateOf(isServiceRunning(context)) }
                var kospiStocks by remember { mutableStateOf<List<StockInfo>>(emptyList()) }
                var kosdaqStocks by remember { mutableStateOf<List<StockInfo>>(emptyList()) }
                var holdings by remember { mutableStateOf<List<HoldingInfo>>(emptyList()) }
                var isLoading by remember { mutableStateOf(false) }
                var showDialog by remember { mutableStateOf(false) }
                var dialogMessage by remember { mutableStateOf("") }
                
                var selectedStockCode by remember { mutableStateOf<String?>(null) }
                var selectedStockName by remember { mutableStateOf<String?>(null) }

                // 1. 브로드캐스트 리시버로 실시간 동기화
                DisposableEffect(Unit) {
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            if (intent?.action == TradingService.ACTION_TRADING_STOPPED) {
                                isTrading = false
                            }
                        }
                    }
                    val filter = IntentFilter(TradingService.ACTION_TRADING_STOPPED)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                    } else {
                        @Suppress("UnspecifiedRegisterReceiverFlag")
                        context.registerReceiver(receiver, filter)
                    }
                    onDispose { context.unregisterReceiver(receiver) }
                }

                // 2. 앱 화면으로 돌아올 때마다 상태 강제 재확인 (보조 장치)
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            isTrading = isServiceRunning(context)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    if (!isGranted) Log.e("MainActivity", "Notification permission denied")
                }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    isLoading = true
                    checkAndIssueToken { token ->
                        token?.let {
                            fetchVolumeRanking(it, "J") { list -> kospiStocks = list.take(10) }
                            fetchVolumeRanking(it, "Q") { list -> kosdaqStocks = list.take(10) }
                            fetchBalance(it) { list -> holdings = list }
                        }
                        isLoading = false
                    }
                }

                LaunchedEffect(topTabSelected) {
                    if (topTabSelected == 1) {
                        isLoading = true
                        checkAndIssueToken { token ->
                            token?.let {
                                fetchBalance(it) { list -> 
                                    holdings = list
                                    isLoading = false
                                }
                            } ?: run { isLoading = false }
                        }
                    }
                }

                if (showDialog) {
                    AlertDialog(onDismissRequest = { showDialog = false },
                        confirmButton = { TextButton(onClick = { showDialog = false }) { Text("확인") } },
                        title = { Text("알림") }, text = { Text(dialogMessage) })
                }

                if (selectedStockCode != null) {
                    BackHandler { selectedStockCode = null; selectedStockName = null }
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = { Text(selectedStockName ?: "종목 상세") },
                                navigationIcon = {
                                    IconButton(onClick = { selectedStockCode = null; selectedStockName = null }) {
                                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                                    }
                                }
                            )
                        }
                    ) { innerPadding ->
                        StockDetailWebScreen(code = selectedStockCode!!, modifier = Modifier.padding(innerPadding))
                    }
                } else {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        topBar = {
                            Column {
                                Spacer(modifier = Modifier.height(32.dp))
                                TabRow(selectedTabIndex = topTabSelected) {
                                    Tab(selected = topTabSelected == 0, onClick = { topTabSelected = 0 }) {
                                        Text("Home", modifier = Modifier.padding(16.dp))
                                    }
                                    Tab(selected = topTabSelected == 1, onClick = { topTabSelected = 1 }) {
                                        Text("보유 종목", modifier = Modifier.padding(16.dp))
                                    }
                                }
                            }
                        }
                    ) { innerPadding ->
                        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                            if (topTabSelected == 0) {
                                HomeScreen(
                                    isTrading = isTrading,
                                    kospiStocks = kospiStocks,
                                    kosdaqStocks = kosdaqStocks,
                                    onStart = {
                                        isTrading = true
                                        dialogMessage = "자동매매를 시작합니다."
                                        showDialog = true
                                        val serviceIntent = Intent(context, TradingService::class.java)
                                        context.startForegroundService(serviceIntent)
                                    },
                                    onStop = {
                                        isTrading = false
                                        dialogMessage = "자동매매를 중단합니다."
                                        showDialog = true
                                        val serviceIntent = Intent(context, TradingService::class.java)
                                        context.stopService(serviceIntent)
                                    },
                                    isLoading = isLoading,
                                    onStockClick = { code, name -> selectedStockCode = code; selectedStockName = name }
                                )
                            } else {
                                HoldingsScreen(holdings = holdings, isLoading = isLoading, onStockClick = { code, name -> selectedStockCode = code; selectedStockName = name })
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkAndIssueToken(onComplete: (String?) -> Unit) {
        val prefs = getSharedPreferences("hantoo_prefs", Context.MODE_PRIVATE)
        val savedToken = prefs.getString("access_token", null)
        val expiredTimeStr = prefs.getString("expired_time", null)
        if (savedToken != null && expiredTimeStr != null) {
            try {
                val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                val expiredTime = LocalDateTime.parse(expiredTimeStr, formatter)
                if (LocalDateTime.now().isBefore(expiredTime.minusMinutes(10))) {
                    onComplete(savedToken); return
                }
            } catch (e: Exception) {}
        }
        issueAccessToken { response -> onComplete(response?.accessToken) }
    }

    private fun issueAccessToken(onResult: (TokenResponse?) -> Unit) {
        val url = "$baseUrl/oauth2/tokenP"
        val bodyMap = mapOf("grant_type" to "client_credentials", "appkey" to appKey, "appsecret" to appSecret)
        val requestBody = gson.toJson(bodyMap).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(requestBody).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { onResult(null) }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    val res = gson.fromJson(body, TokenResponse::class.java)
                    getSharedPreferences("hantoo_prefs", Context.MODE_PRIVATE).edit().apply {
                        putString("access_token", res.accessToken); putString("expired_time", res.accessTokenExpired); apply()
                    }
                    onResult(res)
                } else { onResult(null) }
            }
        })
    }

    private fun fetchVolumeRanking(token: String, marketCode: String, onResult: (List<StockInfo>) -> Unit) {
        val url = HttpUrl.Builder()
            .scheme("https").host("openapivts.koreainvestment.com").port(29443)
            .addPathSegments("uapi/domestic-stock/v1/ranking/volume")
            .addQueryParameter("FID_COND_MRKT_DIV_CODE", marketCode)
            .addQueryParameter("FID_COND_SCR_DIV_CODE", "20171")
            .addQueryParameter("FID_INPUT_ISCD", "0000")
            .addQueryParameter("FID_DIV_CLS_CODE", "0")
            .addQueryParameter("FID_BLNG_CLS_CODE", "0")
            .addQueryParameter("FID_TRGT_CLS_CODE", "111111111")
            .addQueryParameter("FID_TRGT_EXLS_CLS_CODE", "000000")
            .addQueryParameter("FID_INPUT_PRICE_1", "0")
            .addQueryParameter("FID_INPUT_PRICE_2", "0")
            .addQueryParameter("FID_VOL_CNT", "0")
            .addQueryParameter("FID_INPUT_DATE_1", "0")
            .build()
        val request = Request.Builder().url(url).header("authorization", "Bearer $token")
            .header("appkey", appKey).header("appsecret", appSecret)
            .header("tr_id", "FHPST01710000").header("custtype", "P")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    val rankingRes = gson.fromJson(body, RankingResponse::class.java)
                    onResult(rankingRes.output ?: emptyList())
                }
            }
        })
    }

    private fun fetchBalance(token: String, onResult: (List<HoldingInfo>) -> Unit) {
        val cano = accountNum.split("-").firstOrNull() ?: ""
        val acntPrdtCd = accountNum.split("-").getOrNull(1) ?: "01"
        if (cano.isEmpty()) return
        val url = HttpUrl.Builder()
            .scheme("https").host("openapivts.koreainvestment.com").port(29443)
            .addPathSegments("uapi/domestic-stock/v1/trading/inquire-balance")
            .addQueryParameter("CANO", cano).addQueryParameter("ACNT_PRDT_CD", acntPrdtCd)
            .addQueryParameter("AFHR_FLG", "N").addQueryParameter("O_STRT_DSRT_CD", "00")
            .addQueryParameter("INQR_DSRT_CD", "00").addQueryParameter("FUND_STTL_ICLD_YN", "N")
            .addQueryParameter("F_P_QRD_RE_PRC_YN", "N").addQueryParameter("BENF_ETCL_ICLD_YN", "N")
            .addQueryParameter("COST_ICLD_YN", "N").addQueryParameter("TR_OT_DSRT_CD", "00")
            .addQueryParameter("CTX_AREA_FK100", "").addQueryParameter("CTX_AREA_NK100", "")
            .build()
        val request = Request.Builder().url(url).header("authorization", "Bearer $token")
            .header("appkey", appKey).header("appsecret", appSecret)
            .header("tr_id", "VTRP6447R").header("custtype", "P").build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { onResult(emptyList()) }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    val res = gson.fromJson(body, BalanceResponse::class.java)
                    onResult(res.output1 ?: emptyList())
                } else { onResult(emptyList()) }
            }
        })
    }
}

@Composable
fun HomeScreen(isTrading: Boolean, kospiStocks: List<StockInfo>, kosdaqStocks: List<StockInfo>, onStart: () -> Unit, onStop: () -> Unit, isLoading: Boolean, onStockClick: (String, String) -> Unit) {
    var marketTab by remember { mutableIntStateOf(0) }
    Column {
        TradingControlPanel(isTrading = isTrading, onStart = onStart, onStop = onStop)
        HorizontalDivider()
        TabRow(selectedTabIndex = marketTab) {
            Tab(selected = marketTab == 0, onClick = { marketTab = 0 }) { Text("코스피 TOP 10", modifier = Modifier.padding(12.dp)) }
            Tab(selected = marketTab == 1, onClick = { marketTab = 1 }) { Text("코스닥 TOP 10", modifier = Modifier.padding(12.dp)) }
        }
        if (isLoading) Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else StockList(if (marketTab == 0) kospiStocks else kosdaqStocks, onStockClick = onStockClick)
    }
}

@Composable
fun HoldingsScreen(holdings: List<HoldingInfo>, isLoading: Boolean, onStockClick: (String, String) -> Unit) {
    if (isLoading) Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    else if (holdings.isEmpty()) Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("보유한 종목이 없습니다.") }
    else {
        Column {
            val totalPurchaseAmount = holdings.sumOf { it.pchsAmt.toDoubleOrNull() ?: 0.0 }
            val totalProfitLoss = holdings.sumOf { it.evluPflsAmt.toDoubleOrNull() ?: 0.0 }
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("총 매수금액", style = MaterialTheme.typography.labelLarge)
                        Text("${String.format(Locale.getDefault(), "%,.0f", totalPurchaseAmount)}원", style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("총 평가손익", style = MaterialTheme.typography.labelLarge)
                        Text("${String.format(Locale.getDefault(), "%,.0f", totalProfitLoss)}원", style = MaterialTheme.typography.bodyLarge, color = if (totalProfitLoss >= 0) Color.Red else Color.Blue)
                    }
                }
            }
            HorizontalDivider()
            LazyColumn {
                items(holdings) { stock ->
                    ListItem(
                        modifier = Modifier.clickable { onStockClick(stock.pdNo, stock.prdtName) },
                        headlineContent = { Text("${stock.prdtName} (${stock.pdNo})") },
                        supportingContent = { 
                            Column {
                                Text("수량: ${stock.hldgQty} | 평균단가: ${stock.pchsAvgPric}원")
                                Text("매수금액: ${String.format(Locale.getDefault(), "%,.0f", stock.pchsAmt.toDoubleOrNull() ?: 0.0)}원")
                                Text("평가손익: ${String.format(Locale.getDefault(), "%,.0f", stock.evluPflsAmt.toDoubleOrNull() ?: 0.0)}원")
                            }
                        },
                        trailingContent = {
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${stock.prpr}원", style = MaterialTheme.typography.bodyLarge)
                                val profit = stock.evluPflsRt.toDoubleOrNull() ?: 0.0
                                Text("${profit}%", color = if (profit >= 0) Color.Red else Color.Blue)
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun TradingControlPanel(isTrading: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("자동매매 상태", style = MaterialTheme.typography.labelMedium)
            Text(text = if (isTrading) "운영 중" else "중지됨", style = MaterialTheme.typography.headlineSmall, color = if (isTrading) Color(0xFF4CAF50) else Color.Red)
        }
        Button(onClick = if (isTrading) onStop else onStart, colors = ButtonDefaults.buttonColors(containerColor = if (isTrading) Color.Red else Color(0xFF4CAF50))) {
            Text(if (isTrading) "매매 중지" else "매매 시작")
        }
    }
}

@Composable
fun StockList(stocks: List<StockInfo>, onStockClick: (String, String) -> Unit) {
    LazyColumn {
        items(stocks) { stock ->
            ListItem(
                modifier = Modifier.clickable { onStockClick(stock.code, stock.name) },
                headlineContent = { Text(stock.name) },
                supportingContent = { Text("거래량: ${stock.volume}") },
                trailingContent = {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${stock.price}원", style = MaterialTheme.typography.bodyLarge)
                        Text("${stock.changeRate}%", color = if (stock.changeRate.startsWith("-")) Color.Blue else Color.Red)
                    }
                }
            )
            HorizontalDivider()
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun StockDetailWebScreen(code: String, modifier: Modifier = Modifier) {
    val url = "https://m.stock.naver.com/domestic/stock/$code/total"
    AndroidView(factory = { context -> WebView(context).apply { webViewClient = WebViewClient(); settings.javaScriptEnabled = true; loadUrl(url) } }, modifier = modifier.fillMaxSize())
}

data class TokenResponse(@SerializedName("access_token") val accessToken: String, @SerializedName("access_token_token_expired") val accessTokenExpired: String)
data class RankingResponse(val output: List<StockInfo>?)
data class StockInfo(@SerializedName("hts_kor_isnm") val name: String, @SerializedName("stck_shrn_iscd") val code: String, @SerializedName("stck_prpr") val price: String, @SerializedName("acml_vol") val volume: String, @SerializedName("prdy_ctrt") val changeRate: String)
data class BalanceResponse(val output1: List<HoldingInfo>?)
data class HoldingInfo(
    @SerializedName("pd_no") val pdNo: String,
    @SerializedName("prdt_name") val prdtName: String,
    @SerializedName("hldg_qty") val hldgQty: String,
    @SerializedName("pchs_avg_pric") val pchsAvgPric: String,
    @SerializedName("pchs_amt") val pchsAmt: String,
    @SerializedName("prpr") val prpr: String,
    @SerializedName("evlu_pfls_amt") val evluPflsAmt: String,
    @SerializedName("evlu_pfls_rt") val evluPflsRt: String
)
