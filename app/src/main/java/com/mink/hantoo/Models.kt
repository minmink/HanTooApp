package com.mink.hantoo

import com.google.gson.annotations.SerializedName

/**
 * [최종 통합 데이터 모델]
 * 중복 선언 에러를 막기 위해 모든 클래스 이름에 'Bot'을 붙여 유니크하게 만들었습니다.
 */

data class TokenBotData(@SerializedName("access_token") val accessToken: String)

data class BalanceBotData(
    val output1: List<HoldingBotData>?,
    val output2: List<AssetBotData>?,
    val rt_cd: String?,
    val msg1: String?
)

data class HoldingBotData(
    @SerializedName("pd_no") val pdNo: String,
    @SerializedName("prdt_name") val prdtName: String,
    @SerializedName("hldg_qty") val hldgQty: String,
    @SerializedName("pchs_avg_pric") val pchsAvgPric: String,
    @SerializedName("prpr") val prpr: String,
    @SerializedName("evlu_pfls_amt") val evluPflsAmt: String,
    @SerializedName("evlu_pfls_rt") val evluPflsRt: String
)

data class AssetBotData(
    @SerializedName("tot_evlu_amt") val totEvluAmt: String,
    @SerializedName("evlu_pfls_smtl_amt") val evluPflsSmtlAmt: String,
    @SerializedName("pchs_amt_smtl_amt") val pchsAmtSmtlAmt: String
)

data class RankingBotData(val output: List<StockRankData>?)

data class StockRankData(
    @SerializedName("hts_kor_isnm") val name: String,
    @SerializedName("mksc_shrn_iscd") val code1: String?,
    @SerializedName("stck_shrn_iscd") val code2: String?,
    @SerializedName("stck_prpr") val price: String,
    @SerializedName("prdy_ctrt") val changeRate: String
) {
    val code: String get() = code1 ?: code2 ?: ""
}

data class OrderBotData(val rt_cd: String?, val msg1: String?)

data class MonitoringBotData(
    val code: String, 
    val name: String, 
    val openPrice: Double, 
    val targetPrice: Double, 
    var isBought: Boolean = false, 
    var isSoldToday: Boolean = false, 
    var buyPrice: Double = 0.0, 
    var isHalfSold: Boolean = false
)
