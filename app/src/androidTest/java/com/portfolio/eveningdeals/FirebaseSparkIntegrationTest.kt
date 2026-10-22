package com.portfolio.eveningdeals

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Executes the actual Android adapter against local Auth + Firestore with deployed rules. */
@RunWith(AndroidJUnit4::class)
class FirebaseSparkIntegrationTest {
    @Test
    fun sparkCustomerAndMerchantLifecycle() = runBlocking {
        check(BuildConfig.FIREBASE_EMULATORS) {
            "Integration test must never target a real Firebase project."
        }
        val api = FirebaseSparkBackend(ApplicationProvider.getApplicationContext())
        suspend fun call(
            path: String,
            body: org.json.JSONObject? = null,
            method: String = if (body == null) "GET" else "POST",
        ) = api.request(path, method, body)
        suspend fun login(email: String) =
            call("/auth/login", obj("email" to email, "password" to "Demo1234!"))
        assertTrue(call("/deals").rows().isNotEmpty())
        login("customer@demo.kr")
        assertEquals("customer", call("/me").getString("role"))
        call("/favorites/stores", obj("storeId" to "s1"))
        assertTrue(call("/favorites/stores").getJSONArray("items").toString().contains("s1"))
        call("/favorites/stores/s1", method = "DELETE")
        val h =
            call(
                "/stock-holds",
                obj("dealId" to "d1", "quantity" to 2, "requestKey" to requestKey()),
            )
        val confirm =
            obj(
                "holdId" to h.getString("id"),
                "requestKey" to requestKey(),
                "policyAgreed" to true,
                "paymentMode" to "success",
            )
        val order = call("/pickup-orders", confirm)
        assertEquals(12000, order.getInt("total"))
        assertEquals(order.getString("id"), call("/pickup-orders", confirm).getString("id"))
        call("/pickup-orders/${order.getString("id")}/cancel", obj("requestKey" to requestKey()))
        assertEquals(
            "cancelled",
            call("/pickup-orders/${order.getString("id")}").getString("status"),
        )
        val failHold =
            call(
                "/stock-holds",
                obj("dealId" to "d1", "quantity" to 1, "requestKey" to requestKey()),
            )
        val failure =
            obj(
                "holdId" to failHold.getString("id"),
                "requestKey" to requestKey(),
                "policyAgreed" to true,
                "paymentMode" to "failure",
            )
        assertTrue(call("/pickup-orders", failure).getBoolean("paymentFailed"))
        assertTrue(call("/pickup-orders", failure).getBoolean("paymentFailed"))
        call("/auth/logout", obj())
        login("store@demo.kr")
        assertEquals("merchant", call("/me").getString("role"))
        val now = call("/health").getDouble("serverTime")
        val deal =
            call(
                "/merchant/deals",
                obj(
                    "title" to "연동 검증 빵 세트",
                    "original" to 10000,
                    "price" to 5000,
                    "stock" to 3,
                    "description" to "에뮬레이터에서 실제 트랜잭션으로 검증하는 구성입니다.",
                    "allergens" to "밀, 우유",
                    "start" to now - 60,
                    "end" to now + 3600,
                ),
            )
        call("/auth/logout", obj())
        login("customer@demo.kr")
        val pickupHold =
            call(
                "/stock-holds",
                obj("dealId" to deal.getString("id"), "quantity" to 1, "requestKey" to requestKey()),
            )
        val pickup =
            call(
                "/pickup-orders",
                obj(
                    "holdId" to pickupHold.getString("id"),
                    "requestKey" to requestKey(),
                    "policyAgreed" to true,
                    "paymentMode" to "success",
                ),
            )
        assertEquals(8, pickup.getString("code").length)
        call("/auth/logout", obj())
        login("store@demo.kr")
        val incoming = call("/merchant/orders").rows()
        assertTrue(incoming.any { it.getString("id") == pickup.getString("id") })
        assertTrue(incoming.all { it.getString("store_id") == "s1" })
        val verified =
            call("/merchant/pickups/verify", obj("numericCode" to pickup.getString("code")))
        assertEquals("waiting_pickup", verified.getString("status"))
        val complete =
            obj(
                "pickupOrderId" to verified.getString("id"),
                "expectedVersion" to verified.getInt("version"),
                "requestKey" to requestKey(),
            )
        assertTrue(call("/merchant/pickups/complete", complete).getBoolean("ok"))
        assertTrue(call("/merchant/pickups/complete", complete).getBoolean("ok"))
        call("/auth/logout", obj())
        login("customer@demo.kr")
        val received = call("/pickup-orders/${pickup.getString("id")}")
        assertEquals("picked_up", received.getString("status"))
        assertEquals("", received.getString("token"))
        call(
            "/reviews",
            obj(
                "pickupOrderId" to received.getString("id"),
                "rating" to 5,
                "content" to "실제 안드로이드 SDK의 예약과 수령 연동을 검증했어요.",
            ),
        )
        assertTrue(call("/pickup-orders/${pickup.getString("id")}").getBoolean("reviewed"))
        call(
            "/me/notification-preferences",
            obj(
                "pickup_updates" to true,
                "reminders" to false,
                "completion" to true,
                "merchant_ops" to false,
            ),
            "PATCH",
        )
        assertFalse(call("/me/notification-preferences").getBoolean("reminders"))
        assertTrue(call("/notifications").rows().isNotEmpty())
        call("/auth/logout", obj())
        assertFalse(api.hasSession)
    }
}
