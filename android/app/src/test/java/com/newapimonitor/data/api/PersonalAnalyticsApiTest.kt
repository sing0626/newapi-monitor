package com.newapimonitor.data.api

import com.newapimonitor.domain.DataFlowSelf
import com.newapimonitor.domain.FlowItem
import com.newapimonitor.domain.LogSelfStat
import com.newapimonitor.domain.SiteNotice
import com.newapimonitor.domain.TopUpInfo
import com.newapimonitor.domain.TopUpRecord
import com.newapimonitor.domain.UserSubscription
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalAnalyticsApiTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parseNotice_handlesStringAndObject() {
        val strJson = json.parseToJsonElement("\"公告：伺服器將於今晚維護\"")
        assertEquals("公告：伺服器將於今晚維護", ResponseParsing.parseNotice(strJson))

        val objJson = json.parseToJsonElement("""
            {
                "success": true,
                "data": {
                    "notice": "歡迎來到 New API 監控系統"
                }
            }
        """.trimIndent())
        assertEquals("歡迎來到 New API 監控系統", ResponseParsing.parseNotice(objJson))

        val contentObjJson = json.parseToJsonElement("""
            {
                "data": {
                    "content": "系統已平穩運行 99.9% 時間"
                }
            }
        """.trimIndent())
        assertEquals("系統已平穩運行 99.9% 時間", ResponseParsing.parseNotice(contentObjJson))

        val directContentJson = json.parseToJsonElement("""
            {
                "content": "直接內容字串"
            }
        """.trimIndent())
        assertEquals("直接內容字串", ResponseParsing.parseNotice(directContentJson))

        val nullJson = json.parseToJsonElement("null")
        assertEquals("", ResponseParsing.parseNotice(nullJson))
    }

    @Test
    fun parseLogSelfStat_handlesStandardAndDirect() {
        val wrappedJson = json.parseToJsonElement("""
            {
                "success": true,
                "data": {
                    "quota": 1250000.5,
                    "token_used": 9876543,
                    "rpm": 42,
                    "tpm": 18500
                }
            }
        """.trimIndent())
        val stat1 = ResponseParsing.parseLogSelfStat(wrappedJson)
        assertEquals(1250000.5, stat1.quota, 0.001)
        assertEquals(9876543L, stat1.tokenUsed)
        assertEquals(42L, stat1.rpm)
        assertEquals(18500L, stat1.tpm)

        val directJson = json.parseToJsonElement("""
            {
                "quota": 500000,
                "token_used": 12000,
                "rpm": 5,
                "tpm": 600
            }
        """.trimIndent())
        val stat2 = ResponseParsing.parseLogSelfStat(directJson)
        assertEquals(500000.0, stat2.quota, 0.001)
        assertEquals(12000L, stat2.tokenUsed)
        assertEquals(5L, stat2.rpm)
        assertEquals(600L, stat2.tpm)

        val emptyJson = json.parseToJsonElement("{}")
        val stat3 = ResponseParsing.parseLogSelfStat(emptyJson)
        assertEquals(0.0, stat3.quota, 0.001)
        assertEquals(0L, stat3.tokenUsed)
        assertEquals(0L, stat3.rpm)
        assertEquals(0L, stat3.tpm)
    }

    @Test
    fun parseDataFlowSelf_handlesAllDimensions() {
        val flowJson = json.parseToJsonElement("""
            {
                "success": true,
                "data": {
                    "users": [
                        {"name": "alice", "value": 150000.0},
                        {"username": "bob", "quota": 250000.0}
                    ],
                    "models": [
                        {"name": "gpt-4o", "value": 300000.0},
                        {"model_name": "claude-3-5-sonnet", "count": 120000.0}
                    ],
                    "tokens": [
                        {"name": "default-key", "value": 500000.0}
                    ],
                    "groups": [
                        {"name": "vip", "value": 850000.0}
                    ]
                }
            }
        """.trimIndent())
        val flow = ResponseParsing.parseDataFlowSelf(flowJson)
        assertEquals(2, flow.users.size)
        assertEquals("alice", flow.users[0].name)
        assertEquals(150000.0, flow.users[0].value, 0.001)
        assertEquals("bob", flow.users[1].name)
        assertEquals(250000.0, flow.users[1].value, 0.001)

        assertEquals(2, flow.models.size)
        assertEquals("gpt-4o", flow.models[0].name)
        assertEquals(300000.0, flow.models[0].value, 0.001)
        assertEquals("claude-3-5-sonnet", flow.models[1].name)
        assertEquals(120000.0, flow.models[1].value, 0.001)

        assertEquals(1, flow.tokens.size)
        assertEquals("default-key", flow.tokens[0].name)
        assertEquals(500000.0, flow.tokens[0].value, 0.001)

        assertEquals(1, flow.groups.size)
        assertEquals("vip", flow.groups[0].name)
        assertEquals(850000.0, flow.groups[0].value, 0.001)
    }

    @Test
    fun parseTopUpInfo_handlesFlagsAndMethods() {
        val topUpJson = json.parseToJsonElement("""
            {
                "success": true,
                "data": {
                    "min_topup": 5.0,
                    "enable_online_topup": true,
                    "enable_exchange_topup": false,
                    "pay_methods": ["alipay", "wxpay", "stripe"]
                }
            }
        """.trimIndent())
        val info = ResponseParsing.parseTopUpInfo(topUpJson)
        assertEquals(5.0, info.minTopUp!!, 0.001)
        assertTrue(info.enableOnlineTopUp)
        assertFalse(info.enableExchangeTopUp)
        assertEquals(listOf("alipay", "wxpay", "stripe"), info.payMethods)
    }

    @Test
    fun parseTopUpRecords_handlesRecordArray() {
        val recordsJson = json.parseToJsonElement("""
            {
                "success": true,
                "data": [
                    {
                        "id": 1001,
                        "amount": 25.5,
                        "create_time": 1727000000,
                        "status": "success",
                        "pay_type": "stripe"
                    },
                    {
                        "id": 1002,
                        "amount": 10.0,
                        "create_time": 1727003600,
                        "status": "pending",
                        "pay_type": "alipay"
                    }
                ]
            }
        """.trimIndent())
        val records = ResponseParsing.parseTopUpRecords(recordsJson)
        assertEquals(2, records.size)
        assertEquals(1001L, records[0].id)
        assertEquals(25.5, records[0].amount, 0.001)
        assertEquals(1727000000L, records[0].createTime)
        assertEquals("success", records[0].status)
        assertEquals("stripe", records[0].payType)

        assertEquals(1002L, records[1].id)
        assertEquals(10.0, records[1].amount, 0.001)
        assertEquals("pending", records[1].status)
    }

    @Test
    fun parseUserSubscription_handlesActiveAndNull() {
        val subJson = json.parseToJsonElement("""
            {
                "success": true,
                "data": {
                    "plan_name": "VIP 月度訂閱",
                    "expire_time": 1735689600,
                    "status": "active"
                }
            }
        """.trimIndent())
        val sub = ResponseParsing.parseUserSubscription(subJson)
        assertNotNull(sub)
        assertEquals("VIP 月度訂閱", sub?.planName)
        assertEquals(1735689600L, sub?.expireTime)
        assertEquals("active", sub?.status)

        val nullJson = json.parseToJsonElement("""
            {
                "success": true,
                "data": null
            }
        """.trimIndent())
        assertNull(ResponseParsing.parseUserSubscription(nullJson))
    }
}
