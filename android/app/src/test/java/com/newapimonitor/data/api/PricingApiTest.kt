package com.newapimonitor.data.api

import com.newapimonitor.ui.pricing.PricingFailure
import com.newapimonitor.ui.pricing.pricingFailure
import com.newapimonitor.ui.pricing.pricingSource
import com.newapimonitor.domain.Instance
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.newapimonitor.domain.ErrorKind

class PricingApiTest {
    @Test fun `parses envelope and preserves catalog units without pretending USD`() {
        val parsed = PricingParsing.catalog(Json.parseToJsonElement("""{
            "success":true,"data":{"models":[
              {"model_name":"gpt-4o","model_ratio":2,"completion_ratio":3},
              {"model_name":"fixed","model_price":0.006},
              {"model_name":"invalid","model_price":-1}
            ],"group_ratio":{"default":1,"vip":0.8},
            "usable_group":{"default":"Standard","vip":"VIP"}}} """))
        assertEquals(3, parsed.models.size)
        assertEquals(2.0, parsed.models.first { it.name == "gpt-4o" }.modelRatio!!, 0.0)
        assertNull(parsed.models.first { it.name == "invalid" }.fixedPrice)
        assertEquals("Standard", parsed.groups.first { it.id == "default" }.label)
        assertEquals(0.8, parsed.groups.first { it.id == "vip" }.ratio!!, 0.0)
    }

    @Test fun `parses alternate pricing maps and group shapes`() {
        val parsed = PricingParsing.catalog(Json.parseToJsonElement("""{
            "model_prices":{"flat":0.02,"ratio":{"modelRatio":4,"completionRatio":2}},
            "groups":{"default":"Standard"}}
        """))
        assertEquals(0.02, parsed.models.first { it.name == "flat" }.fixedPrice!!, 0.0)
        assertEquals(4.0, parsed.models.first { it.name == "ratio" }.modelRatio!!, 0.0)
        val user = PricingParsing.userGroups(Json.parseToJsonElement("""{
            "data":[{"name":"default","description":"Standard"},{"name":"premium","ratio":1.5}]}
        """))
        val merged = PricingParsing.mergeGroups(parsed.groups, user)
        assertEquals(2, merged.size)
        assertEquals(1.5, merged.first { it.id == "premium" }.ratio!!, 0.0)
    }

    @Test fun `parses NewAPI ratio maps without treating ratios as currency`() {
        val parsed = PricingParsing.catalog(Json.parseToJsonElement("""{
            "data":{"model_ratio":{"gpt-4o":2.5},"completion_ratio":{"gpt-4o":3},
            "model_price":{"fixed":0.004},"group_ratio":{"default":1}}
        }"""))
        assertEquals(2.5, parsed.models.first { it.name == "gpt-4o" }.modelRatio!!, 0.0)
        assertEquals(3.0, parsed.models.first { it.name == "gpt-4o" }.completionRatio!!, 0.0)
        assertEquals(0.004, parsed.models.first { it.name == "fixed" }.fixedPrice!!, 0.0)
        assertEquals(1.0, parsed.groups.single().ratio!!, 0.0)
    }

    @Test fun `demo and real instances have separate catalog sources`() {
        assertEquals("demo", pricingSource(true, Instance(id = "site")))
        assertEquals("none", pricingSource(false, null))
        assertEquals("site", pricingSource(false, Instance(id = "site")))
    }

    @Test fun `status errors remain distinct`() {
        assertEquals(PricingFailure.AUTH, pricingFailure(ApiException(ErrorKind.AUTH, "unauthorized", 401)))
        assertEquals(PricingFailure.FORBIDDEN, pricingFailure(ApiException(ErrorKind.HTTP, "forbidden", 403)))
        assertEquals(PricingFailure.MISSING, pricingFailure(ApiException(ErrorKind.HTTP, "not found", 404)))
        assertEquals(PricingFailure.NETWORK, pricingFailure(ApiException(ErrorKind.NETWORK, "network")))
    }
}
