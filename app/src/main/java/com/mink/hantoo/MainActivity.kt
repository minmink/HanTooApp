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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.mink.hantoo.ui.theme.HantooTheme
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
        
    private val gson = Gson()
    private val baseUrl = "https://openapi.koreainvestment.com:9443"
    private val appKey = BuildConfig.HANTOO_APP_KEY
    private val appSecret = BuildConfig.HANTOO_APP_SECRET
    private val accountNum = BuildConfig.HANTOO_ACCOUNT_NUMBER

    private fun isServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (TradingService::class.java.name == service.service.className) return true
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
                
                var isTrading by remember { mutableStateOf(isServiceRunning(context)) }
                var holdings by remember { mutableStateOf<List<HoldingInfo>>(emptyList()) }
                var assetInfo by remember { mutableStateOf<AssetSummary?>(null) }
                var isLoading by remember { mutableStateOf(false) }
                var lastUpdateTime by remember { mutableStateOf("-") }
                var showDialog by remember { mutableStateOf(false) }
                var dialogMessage by remember { mutableStateOf("") }
                var selectedStockCode by remember { mutableStateOf<String?>(null) }
                var selectedStockName by remember { mutableStateOf<String?>(null) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { isGranted -> if (!isGranted) Log.e("HANTOO_LOG", "Permission Denied") }

                fun refreshData() {
                    isLoading = true
                    checkAndIssueToken { token ->
                        if (token != null) {
                            fetchBalance(token) { hList, summary -> 
                                holdings = hList
                                assetInfo = summary
                                isLoading = false
                                lastUpdateTime = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
                            }
                        } else { isLoading = false }
                    }
                }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    refreshData()
                }

                DisposableEffect(Unit) {
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(c: Context?, intent: Intent?) {
                            if (intent?.action == TradingService.ACTION_TRADING_STOPPED) isTrading = false
                        }
                    }
                    context.registerReceiver(receiver, IntentFilter(TradingService.ACTION_TRADING_STOPPED), 
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_NOT_EXPORTED else 0)
                    onDispose { context.unregisterReceiver(receiver) }
                }

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            isTrading = isServiceRunning(context)
                            refreshData()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                if (showDialog) {
                    AlertDialog(onDismissRequest = { showDialog = false }, confirmButton = { TextButton(onClick = { showDialog = false }) { Text("확인") } }, title = { Text("알림") }, text = { Text(dialogMessage) })
                }

                if (selectedStockCode != null) {
                    BackHandler { selectedStockCode = null; selectedStockName = null }
                    Scaffold(topBar = { TopAppBar(title = { Text(selectedStockName ?: "상세") }, navigationIcon = { IconButton(onClick = { selectedStockCode = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "") } }) }) { 
                        StockDetailWebScreen(selectedStockCode!!, Modifier.padding(it)) 
                    }
                } else {
                    Scaffold(
                        topBar = { CenterAlignedTopAppBar(title = { Text("실전 자동매매 대시보드", fontWeight = FontWeight.Bold) }) }
                    ) { innerPadding ->
                        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                            // 봇 상태 및 갱신 시간
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), Arrangement.SpaceBetween) {
                                Text("최근 갱신: $lastUpdateTime", style = MaterialTheme.typography.labelSmall)
                                Text(if(isTrading) "매매 탐색 중..." else "매매 대기", color = if(isTrading) Color(0xFF4CAF50) else Color.Gray, style = MaterialTheme.typography.labelSmall)
                            }

                            TradingControlPanel(isTrading, 
                                onStart = {
                                    isTrading = true; dialogMessage = "자동매매 가동 시작"; showDialog = true
                                    context.startForegroundService(Intent(context, TradingService::class.java))
                                },
                                onStop = {
                                    isTrading = false; dialogMessage = "자동매매 가동 중단"; showDialog = true
                                    context.stopService(Intent(context, TradingService::class.java))
                                }
                            )
                            
                            HorizontalDivider()
                            AssetSummaryPanel(assetInfo, isLoading)
                            HorizontalDivider()

                            Row(Modifier.fillMaxWidth().padding(16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                Text("보유 종목 현황", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                IconButton(onClick = { refreshData() }) { Icon(Icons.Default.Refresh, "Refresh", modifier = Modifier.size(18.dp)) }
                            }
                            
                            if (isLoading) {
                                Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                            } else if (holdings.isEmpty()) {
                                Box(Modifier.fillMaxSize(), Alignment.Center) { Text("현재 보유 중인 종목이 없습니다.") }
                            } else {
                                LazyColumn {
                                    items(holdings) { stock ->
                                        HoldingItem(stock) { selectedStockCode = stock.pdNo; selectedStockName = stock.prdtName }
                                        HorizontalDivider()
                                    }
                                }
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
        val savedAppKey = prefs.getString("saved_app_key", "")
        if (savedToken != null && savedAppKey == appKey) {
            onComplete(savedToken); return
        }
        issueAccessToken { response -> onComplete(response?.accessToken) }
    }

    private fun issueAccessToken(onResult: (TokenResponse?) -> Unit) {
        val url = "$baseUrl/oauth2/tokenP"
        val bodyMap = mapOf("grant_type" to "client_credentials", "appkey" to appKey, "appsecret" to appSecret)
        val requestBody = gson.toJson(bodyMap).toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url(url).post(requestBody).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { onResult(null) }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    val res = gson.fromJson(body, TokenResponse::class.java)
                    getSharedPreferences("hantoo_prefs", Context.MODE_PRIVATE).edit().apply {
                        putString("access_token", res.accessToken); putString("saved_app_key", appKey); apply()
                    }
                    onResult(res)
                } else { onResult(null) }
            }
        })
    }

    private fun fetchBalance(token: String, onResult: (List<HoldingInfo>, AssetSummary?) -> Unit) {
        val cano = accountNum.split("-").firstOrNull() ?: ""; val acntPrdtCd = accountNum.split("-").getOrNull(1) ?: "01"
        if (cano.isEmpty()) return
        val url = "$baseUrl/uapi/domestic-stock/v1/trading/inquire-balance".toHttpUrl().newBuilder()
            .addQueryParameter("CANO", cano).addQueryParameter("ACNT_PRDT_CD", acntPrdtCd).addQueryParameter("AFHR_FLG", "N").addQueryParameter("O_STRT_DSRT_CD", "00").addQueryParameter("INQR_DSRT_CD", "00").addQueryParameter("FUND_STTL_ICLD_YN", "N").addQueryParameter("F_P_QRD_RE_PRC_YN", "N").addQueryParameter("BENF_ETCL_ICLD_YN", "N").addQueryParameter("COST_ICLD_YN", "N").addQueryParameter("TR_OT_DSRT_CD", "00").addQueryParameter("CTX_AREA_FK100", "").addQueryParameter("CTX_AREA_NK100", "")
            .build()
        val request = Request.Builder().url(url).header("authorization", "Bearer $token").header("appkey", appKey).header("appsecret", appSecret).header("tr_id", "TTTC8434R").header("custtype", "P").build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { runOnUiThread { onResult(emptyList(), null) } }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val res = gson.fromJson(body, BalanceResponse::class.java)
                    val summary = res.output2?.firstOrNull()?.let { 
                        AssetSummary(totalAsset = it.totEvluAmt, totalProfit = it.evluPflsSmtlAmt, totalBuy = it.pchsAmtSmtlAmt)
                    }
                    runOnUiThread { onResult(res.output1 ?: emptyList(), summary) }
                } else { runOnUiThread { onResult(emptyList(), null) } }
            }
        })
    }
}

@Composable fun TradingControlPanel(isTrading: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = if (isTrading) Color(0xFFE8F5E9) else Color(0xFFFFEBEE))) {
        Row(Modifier.padding(16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column {
                Text("서비스 상태", style = MaterialTheme.typography.labelMedium)
                Text(if (isTrading) "실시간 매매 가동 중" else "자동매매 중지됨", 
                    style = MaterialTheme.typography.titleLarge, 
                    color = if (isTrading) Color(0xFF2E7D32) else Color(0xFFC62828),
                    fontWeight = FontWeight.Bold)
            }
            Button(onClick = if (isTrading) onStop else onStart, 
                colors = ButtonDefaults.buttonColors(containerColor = if (isTrading) Color.Red else Color(0xFF4CAF50))) {
                Text(if (isTrading) "중지" else "가동")
            }
        }
    }
}

@Composable fun AssetSummaryPanel(assetInfo: AssetSummary?, isLoading: Boolean) {
    if (assetInfo == null && !isLoading) return
    Column(Modifier.padding(16.dp)) {
        Text("계좌 요약", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            AssetCard("총 평가자산", "${String.format(Locale.getDefault(), "%,.0f", assetInfo?.totalAsset?.toDoubleOrNull() ?: 0.0)}원", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            AssetCard("총 매수금액", "${String.format(Locale.getDefault(), "%,.0f", assetInfo?.totalBuy?.toDoubleOrNull() ?: 0.0)}원", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        val profit = assetInfo?.totalProfit?.toDoubleOrNull() ?: 0.0
        AssetCard("총 평가손익", "${String.format(Locale.getDefault(), "%,.0f", profit)}원", 
            Modifier.fillMaxWidth(), 
            valueColor = if (profit >= 0) Color.Red else Color.Blue)
    }
}

@Composable fun AssetCard(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified) {
    Surface(modifier, shape = MaterialTheme.shapes.small, tonalElevation = 2.dp) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = valueColor)
        }
    }
}

@Composable fun HoldingItem(stock: HoldingInfo, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onClick() },
        headlineContent = { Text("${stock.prdtName} (${stock.pdNo})", fontWeight = FontWeight.Bold) },
        supportingContent = {
            Column {
                Text("수량: ${stock.hldgQty} | 평균가: ${stock.pchsAvgPric}원")
                Text("수익률: ${stock.evluPflsRt}%", color = if (stock.evluPflsRt.startsWith("-")) Color.Blue else Color.Red)
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text("${stock.prpr}원", fontWeight = FontWeight.Bold)
                Text("손익: ${String.format(Locale.getDefault(), "%,.0f", stock.evluPflsAmt.toDoubleOrNull() ?: 0.0)}원", 
                    color = if (stock.evluPflsAmt.startsWith("-")) Color.Blue else Color.Red)
            }
        }
    )
}

@SuppressLint("SetJavaScriptEnabled") @Composable fun StockDetailWebScreen(code: String, modifier: Modifier = Modifier) { AndroidView(factory = { context -> WebView(context).apply { webViewClient = WebViewClient(); settings.javaScriptEnabled = true; loadUrl("https://m.stock.naver.com/domestic/stock/$code/total") } }, modifier = modifier.fillMaxSize()) }

data class TokenResponse(@SerializedName("access_token") val accessToken: String)
data class BalanceResponse(val output1: List<HoldingInfo>?, val output2: List<BalanceOutput2>?)
data class HoldingInfo(@SerializedName("pd_no") val pdNo: String, @SerializedName("prdt_name") val prdtName: String, @SerializedName("hldg_qty") val hldgQty: String, @SerializedName("pchs_avg_pric") val pchsAvgPric: String, @SerializedName("prpr") val prpr: String, @SerializedName("evlu_pfls_amt") val evluPflsAmt: String, @SerializedName("evlu_pfls_rt") val evluPflsRt: String)
data class BalanceOutput2(@SerializedName("tot_evlu_amt") val totEvluAmt: String, @SerializedName("evlu_pfls_smtl_amt") val evluPflsSmtlAmt: String, @SerializedName("pchs_amt_smtl_amt") val pchsAmtSmtlAmt: String)
data class AssetSummary(val totalAsset: String, val totalProfit: String, val totalBuy: String)
data class RankingResponse(val output: List<StockInfo>?)
data class StockInfo(@SerializedName("hts_kor_isnm") val name: String, @SerializedName("mksc_shrn_iscd") val code1: String?, @SerializedName("stck_shrn_iscd") val code2: String?, @SerializedName("stck_prpr") val price: String, @SerializedName("prdy_ctrt") val changeRate: String) { val code: String get() = code1 ?: code2 ?: "" }
