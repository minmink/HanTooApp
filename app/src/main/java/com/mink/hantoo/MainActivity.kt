package com.mink.hantoo

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.mink.hantoo.BuildConfig
import com.mink.hantoo.ui.theme.HantooTheme
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val client = OkHttpClient()
    private val gson = Gson()
    private val baseUrl = "https://openapivts.koreainvestment.com:29443"
    private val appKey = BuildConfig.HANTOO_APP_KEY
    private val appSecret = BuildConfig.HANTOO_APP_SECRET
    private val accountNum = BuildConfig.HANTOO_ACCOUNT_NUMBER

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HantooTheme {
                var topTabSelected by remember { mutableIntStateOf(0) } // 0: Home, 1: 보유 종목
                var isTrading by remember { mutableStateOf(false) }
                var kospiStocks by remember { mutableStateOf<List<StockInfo>>(emptyList()) }
                var kosdaqStocks by remember { mutableStateOf<List<StockInfo>>(emptyList()) }
                var holdings by remember { mutableStateOf<List<HoldingInfo>>(emptyList()) }
                var isLoading by remember { mutableStateOf(false) }
                var showDialog by remember { mutableStateOf(false) }
                var dialogMessage by remember { mutableStateOf("") }
                
                // 상세 화면용 상태
                var selectedStockCode by remember { mutableStateOf<String?>(null) }
                var selectedStockName by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(Unit) {
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

                // 탭이 바뀔 때 잔고 다시 조회
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
                    // 뒤로가기 처리
                    BackHandler {
                        selectedStockCode = null
                        selectedStockName = null
                    }

                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = { Text(selectedStockName ?: "종목 상세") },
                                navigationIcon = {
                                    IconButton(onClick = { 
                                        selectedStockCode = null
                                        selectedStockName = null
                                    }) {
                                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                                    }
                                }
                            )
                        }
                    ) { innerPadding ->
                        StockDetailWebScreen(
                            code = selectedStockCode!!,
                            modifier = Modifier.padding(innerPadding)
                        )
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
                                    // 서비스 시작
                                    val serviceIntent = Intent(this@MainActivity, TradingService::class.java)
                                    startForegroundService(serviceIntent)
                                },
                                onStop = {
                                    isTrading = false
                                    dialogMessage = "자동매매를 중단합니다."
                                    showDialog = true
                                    // 서비스 중지
                                    val serviceIntent = Intent(this@MainActivity, TradingService::class.java)
                                    stopService(serviceIntent)
                                },
                                    isLoading = isLoading,
                                    onStockClick = { code, name ->
                                        selectedStockCode = code
                                        selectedStockName = name
                                    }
                                )
                            } else {
                                HoldingsScreen(
                                    holdings = holdings, 
                                    isLoading = isLoading,
                                    onStockClick = { code, name ->
                                        selectedStockCode = code
                                        selectedStockName = name
                                    }
                                )
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
                    onComplete(savedToken)
                    return
                }
            } catch (e: Exception) {}
        }
        
        issueAccessToken { response ->
            onComplete(response?.accessToken)
        }
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
                        putString("access_token", res.accessToken)
                        putString("expired_time", res.accessTokenExpired)
                        apply()
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

        val request = Request.Builder()
            .url(url)
            .header("authorization", "Bearer $token")
            .header("appkey", appKey)
            .header("appsecret", appSecret)
            .header("tr_id", "FHPST01710000")
            .header("custtype", "P")
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
            .addQueryParameter("CANO", cano)
            .addQueryParameter("ACNT_PRDT_CD", acntPrdtCd)
            .addQueryParameter("AFHR_FLG", "N")
            .addQueryParameter("O_STRT_DSRT_CD", "00")
            .addQueryParameter("INQR_DSRT_CD", "00")
            .addQueryParameter("FUND_STTL_ICLD_YN", "N")
            .addQueryParameter("F_P_QRD_RE_PRC_YN", "N")
            .addQueryParameter("BENF_ETCL_ICLD_YN", "N")
            .addQueryParameter("COST_ICLD_YN", "N")
            .addQueryParameter("TR_OT_DSRT_CD", "00")
            .addQueryParameter("CTX_AREA_FK100", "").addQueryParameter("CTX_AREA_NK100", "")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("authorization", "Bearer $token")
            .header("appkey", appKey)
            .header("appsecret", appSecret)
            .header("tr_id", "VTRP6447R")
            .header("custtype", "P")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("API", "Balance failed", e)
                onResult(emptyList())
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    val res = gson.fromJson(body, BalanceResponse::class.java)
                    onResult(res.output1 ?: emptyList())
                } else {
                    onResult(emptyList())
                }
            }
        })
    }
}

// --- UI Components ---
@Composable
fun HomeScreen(
    isTrading: Boolean, 
    kospiStocks: List<StockInfo>, 
    kosdaqStocks: List<StockInfo>, 
    onStart: () -> Unit, 
    onStop: () -> Unit, 
    isLoading: Boolean,
    onStockClick: (String, String) -> Unit
) {
    var marketTab by remember { mutableIntStateOf(0) }
    
    Column {
        TradingControlPanel(isTrading = isTrading, onStart = onStart, onStop = onStop)
        HorizontalDivider()
        TabRow(selectedTabIndex = marketTab) {
            Tab(selected = marketTab == 0, onClick = { marketTab = 0 }) { Text("코스피 TOP 10", modifier = Modifier.padding(12.dp)) }
            Tab(selected = marketTab == 1, onClick = { marketTab = 1 }) { Text("코스닥 TOP 10", modifier = Modifier.padding(12.dp)) }
        }
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            StockList(if (marketTab == 0) kospiStocks else kosdaqStocks, onStockClick = onStockClick)
        }
    }
}

@Composable
fun HoldingsScreen(
    holdings: List<HoldingInfo>, 
    isLoading: Boolean,
    onStockClick: (String, String) -> Unit
) {
    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else if (holdings.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("보유한 종목이 없습니다.") }
    } else {
        LazyColumn {
            items(holdings) { stock ->
                ListItem(
                    modifier = Modifier.clickable { onStockClick(stock.pdNo, stock.prdtName) },
                    headlineContent = { Text("${stock.prdtName} (${stock.pdNo})") },
                    supportingContent = { Text("수량: ${stock.hldgQty} | 평균단가: ${stock.pchsAvgPric}원") },
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

@Composable
fun TradingControlPanel(isTrading: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("자동매매 상태", style = MaterialTheme.typography.labelMedium)
            Text(
                text = if (isTrading) "운영 중" else "중지됨",
                style = MaterialTheme.typography.headlineSmall,
                color = if (isTrading) Color(0xFF4CAF50) else Color.Red
            )
        }
        Button(
            onClick = if (isTrading) onStop else onStart,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isTrading) Color.Red else Color(0xFF4CAF50)
            )
        ) {
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
                        Text(
                            "${stock.changeRate}%",
                            color = if (stock.changeRate.startsWith("-")) Color.Blue else Color.Red
                        )
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
    
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                loadUrl(url)
            }
        },
        modifier = modifier.fillMaxSize()
    )
}

// --- Data Models ---
data class TokenResponse(@SerializedName("access_token") val accessToken: String, @SerializedName("access_token_token_expired") val accessTokenExpired: String)
data class RankingResponse(val output: List<StockInfo>?)
data class StockInfo(
    @SerializedName("hts_kor_isnm") val name: String,
    @SerializedName("stck_shrn_iscd") val code: String, // 종목코드
    @SerializedName("stck_prpr") val price: String,
    @SerializedName("acml_vol") val volume: String,
    @SerializedName("prdy_ctrt") val changeRate: String
)
data class BalanceResponse(val output1: List<HoldingInfo>?)
data class HoldingInfo(
    @SerializedName("pd_no") val pdNo: String,
    @SerializedName("prdt_name") val prdtName: String,
    @SerializedName("hldg_qty") val hldgQty: String,
    @SerializedName("pchs_avg_pric") val pchsAvgPric: String,
    @SerializedName("prpr") val prpr: String,
    @SerializedName("evlu_pfls_rt") val evluPflsRt: String
)
