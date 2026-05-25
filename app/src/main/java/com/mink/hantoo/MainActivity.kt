package com.mink.hantoo

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HantooTheme {
                var isTrading by remember { mutableStateOf(false) }
                var kospiStocks by remember { mutableStateOf<List<StockInfo>>(emptyList()) }
                var kosdaqStocks by remember { mutableStateOf<List<StockInfo>>(emptyList()) }
                var selectedTab by remember { mutableIntStateOf(0) }
                var isLoading by remember { mutableStateOf(false) }
                var showDialog by remember { mutableStateOf(false) }
                var dialogMessage by remember { mutableStateOf("") }

                // 앱 시작 시 토큰 체크 및 데이터 로드
                LaunchedEffect(Unit) {
                    isLoading = true
                    checkAndIssueToken { token ->
                        if (token != null) {
                            fetchVolumeRanking(token, "J") { list -> kospiStocks = list.take(5) }
                            fetchVolumeRanking(token, "Q") { list -> kosdaqStocks = list.take(5) }
                        }
                        isLoading = false
                    }
                }

                if (showDialog) {
                    AlertDialog(
                        onDismissRequest = { showDialog = false },
                        confirmButton = {
                            TextButton(onClick = { showDialog = false }) { Text("확인") }
                        },
                        title = { Text("알림") },
                        text = { Text(dialogMessage) }
                    )
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                        // 1. 자동매매 제어 섹션
                        TradingControlPanel(
                            isTrading = isTrading,
                            onStart = {
                                isTrading = true
                                dialogMessage = "자동매매를 시작합니다."
                                showDialog = true
                            },
                            onStop = {
                                isTrading = false
                                dialogMessage = "자동매매를 중단합니다."
                                showDialog = true
                            }
                        )

                        HorizontalDivider()

                        // 2. 탭 메뉴 (KOSPI / KOSDAQ)
                        TabRow(selectedTabIndex = selectedTab) {
                            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                                Text("코스피 TOP 5", modifier = Modifier.padding(16.dp))
                            }
                            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                                Text("코스닥 TOP 5", modifier = Modifier.padding(16.dp))
                            }
                        }

                        // 3. 종목 리스트
                        if (isLoading) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else {
                            val displayList = if (selectedTab == 0) kospiStocks else kosdaqStocks
                            StockList(displayList)
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
                
                // 만료 10분 전인지 확인 (10분 이하로 남았으면 갱신)
                if (LocalDateTime.now().isBefore(expiredTime.minusMinutes(10))) {
                    onComplete(savedToken)
                    return
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Token parse error", e)
            }
        }
        
        // 토큰이 없거나 10분 이내로 만료될 경우 새 토큰 발급
        issueAccessToken { response ->
            onComplete(response?.accessToken)
        }
    }

    private fun issueAccessToken(onResult: (TokenResponse?) -> Unit) {
        val url = "$baseUrl/oauth2/tokenP"
        val bodyMap = mapOf(
            "grant_type" to "client_credentials",
            "appkey" to appKey,
            "appsecret" to appSecret
        )
        val requestBody = gson.toJson(bodyMap).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(requestBody).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(null)
            }

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
                } else {
                    onResult(null)
                }
            }
        })
    }

    private fun fetchVolumeRanking(token: String, marketCode: String, onResult: (List<StockInfo>) -> Unit) {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("openapivts.koreainvestment.com")
            .port(29443)
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
            override fun onFailure(call: Call, e: IOException) {
                Log.e("API", "Ranking failed", e)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    val rankingRes = gson.fromJson(body, RankingResponse::class.java)
                    onResult(rankingRes.output ?: emptyList())
                }
            }
        })
    }
}

// Data Models
data class TokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("access_token_token_expired") val accessTokenExpired: String
)

data class RankingResponse(val output: List<StockInfo>?)

data class StockInfo(
    @SerializedName("hts_kor_isnm") val name: String,
    @SerializedName("stck_prpr") val price: String,
    @SerializedName("acml_vol") val volume: String,
    @SerializedName("prdy_ctrt") val changeRate: String
)

// UI Components
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
fun StockList(stocks: List<StockInfo>) {
    LazyColumn {
        items(stocks) { stock ->
            ListItem(
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
