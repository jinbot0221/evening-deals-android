package com.portfolio.eveningdeals

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject

fun obj(vararg pairs: Pair<String, Any?>) =
    JSONObject().apply { pairs.forEach { put(it.first, it.second) } }

fun JSONObject.rows(key: String = "items"): List<JSONObject> =
    optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()

fun requestKey() = UUID.randomUUID().toString()

class DealsViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("session", 0)
    private val firebase =
        if (BuildConfig.BACKEND == "firebase") FirebaseSparkBackend(app) else null
    private var token =
        firebase?.let { if (it.hasSession) "firebase-sdk-session" else "" }
            ?: (prefs.getString("token", "") ?: "")
    var user by mutableStateOf<JSONObject?>(null)
        private set

    var deals by mutableStateOf<List<JSONObject>>(emptyList())
        private set

    var orders by mutableStateOf<List<JSONObject>>(emptyList())
        private set

    var favoriteStores by mutableStateOf<List<JSONObject>>(emptyList())
        private set

    var favorites by mutableStateOf<Set<String>>(emptySet())
        private set

    var notifications by mutableStateOf<List<JSONObject>>(emptyList())
        private set

    var merchantDeals by mutableStateOf<List<JSONObject>>(emptyList())
        private set

    var preferences by
        mutableStateOf(
            obj(
                "pickup_updates" to true,
                "reminders" to true,
                "completion" to true,
                "merchant_ops" to true,
            )
        )
        private set

    var busy by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(false)
        private set

    var online by mutableStateOf(false)
        private set

    private var serverOffset = 0.0
    val signedIn
        get() = user != null

    val merchant
        get() = user?.optString("role") == "merchant"

    fun now() = System.currentTimeMillis() / 1000.0 + serverOffset

    private suspend fun api(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
    ): JSONObject =
        withContext(Dispatchers.IO) {
            firebase?.let { backend ->
                try {
                    return@withContext backend.request(path, method, body)
                } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
                    android.util.Log.e("EveningDealsFirebase", "$method $path: ${e.code}", e)
                    throw IllegalStateException(
                        when (e.code) {
                            com.google.firebase.firestore.FirebaseFirestoreException.Code
                                .PERMISSION_DENIED ->
                                if (method == "GET")
                                    "데이터 조회 권한이 없어요. Firebase 프로젝트의 Firestore 보안 규칙이 배포됐는지 확인해 주세요."
                                else "요청 권한 또는 재고·픽업 시간 조건을 확인해 주세요. 새로고침 후 다시 시도해 주세요."
                            com.google.firebase.firestore.FirebaseFirestoreException.Code
                                .RESOURCE_EXHAUSTED -> "Firebase 무료 사용량을 초과했어요. 잠시 후 다시 이용해 주세요."
                            com.google.firebase.firestore.FirebaseFirestoreException.Code
                                .UNAVAILABLE -> "Firebase에 연결할 수 없어요. 연결 상태를 확인해 주세요."
                            com.google.firebase.firestore.FirebaseFirestoreException.Code
                                .FAILED_PRECONDITION -> "Firebase 보안 규칙과 인덱스 배포 상태를 확인해 주세요."
                            else -> "Firebase 요청을 완료하지 못했어요. 다시 확인해 주세요."
                        }
                    )
                } catch (e: com.google.firebase.auth.FirebaseAuthException) {
                    throw IllegalStateException(
                        when (e.errorCode) {
                            "ERROR_EMAIL_ALREADY_IN_USE" -> "이미 가입된 이메일이에요."
                            "ERROR_INVALID_EMAIL" -> "이메일 형식을 확인해 주세요."
                            "ERROR_WEAK_PASSWORD" -> "더 안전한 비밀번호를 입력해 주세요."
                            "ERROR_TOO_MANY_REQUESTS" -> "요청이 많아요. 잠시 후 다시 시도해 주세요."
                            else -> "이메일·비밀번호와 Firebase 로그인 설정을 확인해 주세요."
                        }
                    )
                }
            }
            val c = URL(BuildConfig.API_URL + path).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 10000
                c.readTimeout = 15000
                c.requestMethod = method
                c.setRequestProperty("Accept", "application/json")
                if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val code = c.responseCode
                val text =
                    (if (code in 200..299) c.inputStream else c.errorStream)
                        ?.bufferedReader()
                        ?.use { it.readText() } ?: "{}"
                val result = JSONObject(text)
                if (code !in 200..299) {
                    if (code == 401 && path != "/auth/login") {
                        withContext(Dispatchers.Main) { clearSession() }
                    }
                    throw IllegalStateException(result.optString("message", "요청을 처리할 수 없어요."))
                }
                result
            } catch (e: java.io.IOException) {
                throw IllegalStateException("서버에 연결할 수 없어요. 연결 상태를 확인하고 다시 시도해 주세요.")
            } finally {
                c.disconnect()
            }
        }

    private fun clearSession() {
        token = ""
        prefs.edit().clear().apply()
        user = null
        orders = emptyList()
        favorites = emptySet()
        favoriteStores = emptyList()
        notifications = emptyList()
        merchantDeals = emptyList()
    }

    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                action()
            } catch (e: Exception) {
                error = e.message ?: "다시 시도해 주세요."
            } finally {
                busy = false
            }
        }
    }

    private suspend fun refreshAll() {
        val health = api("/health")
        serverOffset = health.getDouble("serverTime") - System.currentTimeMillis() / 1000.0
        deals = api("/deals").rows()
        online = true
        loaded = true
        if (token.isNotBlank()) {
            user = api("/me")
            orders = api("/pickup-orders").rows()
            val favoriteResponse = api("/favorites/stores")
            val a = favoriteResponse.getJSONArray("items")
            favoriteStores = favoriteResponse.rows("stores")
            favorites = (0 until a.length()).map { a.getString(it) }.toSet()
            notifications = api("/notifications").rows()
            preferences = api("/me/notification-preferences")
            merchantDeals = if (merchant) api("/merchant/deals").rows() else emptyList()
        }
    }

    init {
        refresh()
    }

    fun refresh() = run { refreshAll() }

    fun login(email: String, password: String, done: () -> Unit) = run {
        val r = api("/auth/login", "POST", obj("email" to email, "password" to password))
        token = r.getString("token")
        user = r.getJSONObject("user")
        prefs.edit().putString("token", token).apply()
        refreshAll()
        done()
    }

    fun signup(
        nick: String,
        email: String,
        password: String,
        terms: Boolean,
        privacy: Boolean,
        done: () -> Unit,
    ) = run {
        val r =
            api(
                "/auth/signup",
                "POST",
                obj(
                    "nickname" to nick,
                    "email" to email,
                    "password" to password,
                    "terms" to terms,
                    "privacy" to privacy,
                ),
            )
        token = r.getString("token")
        user = r.getJSONObject("user")
        prefs.edit().putString("token", token).apply()
        refreshAll()
        done()
    }

    fun logout(done: () -> Unit) = run {
        api("/auth/logout", "POST", obj())
        clearSession()
        done()
    }

    fun reset(email: String) = run {
        val result = api("/auth/password-reset-requests", "POST", obj("email" to email))
        error = result.optString("message", "가입된 이메일이라면 재설정 링크가 발송됩니다.")
    }

    fun favorite(d: JSONObject) = run {
        val id = d.getString("store_id")
        if (id in favorites) api("/favorites/stores/$id", "DELETE")
        else api("/favorites/stores", "POST", obj("storeId" to id))
        refreshAll()
    }

    fun hold(deal: String, quantity: Int, key: String, done: (JSONObject) -> Unit) = run {
        val h =
            api(
                "/stock-holds",
                "POST",
                obj("dealId" to deal, "quantity" to quantity, "requestKey" to key),
            )
        refreshAll()
        done(h)
    }

    fun confirm(hold: String, mode: String, key: String, done: (JSONObject) -> Unit) = run {
        // Delay simulates a lost response after commit: replay uses the SAME request key.
        val body =
            obj(
                "holdId" to hold,
                "policyAgreed" to true,
                "paymentMode" to mode,
                "requestKey" to key,
            )
        val first = api("/pickup-orders", "POST", body)
        val result =
            if (mode == "delay") {
                delay(2000)
                api("/pickup-orders", "POST", body)
            } else first
        refreshAll()
        done(result)
    }

    fun cancel(id: String, key: String, done: () -> Unit) = run {
        api("/pickup-orders/$id/cancel", "POST", obj("requestKey" to key))
        refreshAll()
        done()
    }

    fun review(id: String, rating: Int, content: String, done: () -> Unit) = run {
        api(
            "/reviews",
            "POST",
            obj("pickupOrderId" to id, "rating" to rating, "content" to content),
        )
        refreshAll()
        done()
    }

    fun verify(code: String, done: (JSONObject) -> Unit) = run {
        val r = api("/merchant/pickups/verify", "POST", obj("numericCode" to code))
        if (r.optBoolean("verificationFailed")) error = r.getString("message") else done(r)
    }

    fun complete(order: JSONObject, key: String, done: () -> Unit) = run {
        api(
            "/merchant/pickups/complete",
            "POST",
            obj(
                "pickupOrderId" to order.getString("id"),
                "expectedVersion" to order.getInt("version"),
                "requestKey" to key,
            ),
        )
        refreshAll()
        done()
    }

    fun saveDeal(id: String?, body: JSONObject, done: () -> Unit) = run {
        api(
            if (id == null) "/merchant/deals" else "/merchant/deals/$id",
            if (id == null) "POST" else "PATCH",
            body,
        )
        refreshAll()
        done()
    }

    fun stopDeal(id: String) = run {
        api("/merchant/deals/$id/stop", "PATCH", obj())
        refreshAll()
    }

    fun preference(key: String, value: Boolean) = run {
        val next = JSONObject(preferences.toString()).put(key, value)
        preferences = api("/me/notification-preferences", "PATCH", next)
    }

    fun read(n: JSONObject, done: () -> Unit) = run {
        api("/notifications/${n.getString("id")}", "PATCH", obj())
        refreshAll()
        done()
    }
}
