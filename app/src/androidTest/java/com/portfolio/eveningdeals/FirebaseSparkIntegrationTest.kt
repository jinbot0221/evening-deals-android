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
    fun signupTypesKeepMerchantPending() = runBlocking {
        check(BuildConfig.FIREBASE_EMULATORS)
        val api = FirebaseSparkBackend(ApplicationProvider.getApplicationContext())
        for (type in listOf("customer", "merchant")) {
            val signup = api.request("/auth/signup", "POST", obj(
                "nickname" to "가입 검증", "email" to "signup-${requestKey()}@demo.kr",
                "password" to "Demo1234!", "terms" to true, "privacy" to true,
                "account_type" to type, "store_name" to "신규 테스트 매장", "store_address" to "서울 성동구 성수동 1",
            ))
            val user = signup.getJSONObject("user")
            assertEquals(type, user.getString("account_type"))
            assertEquals("customer", user.getString("role"))
            if (type == "merchant") {
                assertEquals("신규 테스트 매장", user.getString("store_name"))
                try {
                    api.request("/merchant/deals")
                    fail("Pending merchant must not access store management")
                } catch (e: IllegalStateException) {
                    assertTrue(e.message.orEmpty().contains("운영자"))
                }
            }
            api.request("/auth/logout", "POST", obj())
        }
    }

    @Test
    fun productInputExplainsInvalidFieldsBeforeFirestoreWrite() {
        val now = System.currentTimeMillis() / 1000.0
        val valid = obj(
            "title" to "오늘의 빵 세트", "description" to "갓 구운 빵 세 가지 구성입니다.",
            "allergens" to "밀, 우유", "original" to 10000, "price" to 5000,
            "stock" to 3, "start" to now + 60, "end" to now + 3600,
        )
        validateDealInput(valid, now)
        val invalid = listOf(
            Triple("title", "빵", "상품명"),
            Triple("description", "빵 세트", "구성 안내"),
            Triple("allergens", "  ", "알레르기"),
            Triple("price", 11000, "할인가"),
            Triple("stock", 100, "총수량"),
            Triple("end", now - 1, "종료 시간"),
        )
        for ((field, value, message) in invalid) {
            try {
                validateDealInput(org.json.JSONObject(valid.toString()).put(field, value), now)
                fail("Invalid $field must be rejected")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message.orEmpty().contains(message))
            }
        }
        try {
            validateDealInput(valid, now, existingStock = 4)
            fail("Existing stock must stay fixed")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("총수량"))
        }
    }

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
