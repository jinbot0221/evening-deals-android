package kr.evening.deals

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.Transaction
import java.security.SecureRandom
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

/**
 * Spark: all privileged transitions are checked by firestore.rules; no Admin credentials in APK.
 */
class FirebaseSparkBackend(context: Context) {
    private val app =
        FirebaseApp.getApps(context).find { it.name == "evening-spark" }
            ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .build(),
                "evening-spark",
            )
    private val auth = FirebaseAuth.getInstance(app)
    private val db = FirebaseFirestore.getInstance(app)
    private val random = SecureRandom()
    private var offsetMillis = 0L
    private var lastOwnOrders = emptyList<JSONObject>()
    private var lastOrderUid = ""
    val hasSession
        get() = auth.currentUser?.isAnonymous == false

    private val uid
        get() = auth.currentUser?.takeUnless { it.isAnonymous }?.uid ?: error("로그인이 필요해요.")

    private fun requireProject() {
        check(
            BuildConfig.FIREBASE_EMULATORS ||
                (!BuildConfig.FIREBASE_PROJECT_ID.startsWith("demo-") &&
                    BuildConfig.FIREBASE_API_KEY != "demo-api-key")
        ) {
            "Firebase 프로젝트 연결 설정이 필요해요. firebase.local.properties를 확인해 주세요."
        }
    }

    init {
        // No offline transaction success: stock/order writes require a live server acknowledgement.
        db.firestoreSettings =
            FirebaseFirestoreSettings.Builder().setPersistenceEnabled(false).build()
        if (BuildConfig.FIREBASE_EMULATORS) {
            auth.useEmulator(BuildConfig.FIREBASE_EMULATOR_HOST, 9099)
            db.useEmulator(BuildConfig.FIREBASE_EMULATOR_HOST, 8085)
        }
    }

    private fun nowMillis() = System.currentTimeMillis() + offsetMillis

    private suspend fun syncClock(): Long {
        val ref = db.collection("clocks").document(uid)
        ref.set(mapOf("time" to FieldValue.serverTimestamp())).await()
        val time = ref.get(Source.SERVER).await().getTimestamp("time")!!.toDate().time
        offsetMillis = time - System.currentTimeMillis()
        return time
    }

    private fun time(seconds: Double) = Timestamp(java.util.Date((seconds * 1000).toLong()))

    private fun epoch(value: Any?) = (value as Timestamp).toDate().time / 1000.0

    private fun json(doc: DocumentSnapshot): JSONObject =
        JSONObject().apply {
            put("id", doc.id)
            doc.data.orEmpty().forEach { (key, value) ->
                put(
                    key,
                    when {
                        value is Timestamp -> epoch(value)
                        key == "stopped" -> if (value == true) 1 else 0
                        value is List<*> -> JSONArray(value)
                        else -> value
                    },
                )
            }
        }

    private suspend fun profile(): JSONObject {
        val who = auth.currentUser ?: error("로그인이 필요해요.")
        val user = db.collection("users").document(uid).get(Source.SERVER).await()
        check(user.exists()) { "필수 약관을 확인해 회원가입을 완료해 주세요." }
        val membership = db.collection("merchants").document(uid).get(Source.SERVER).await()
        return obj(
            "id" to uid,
            "email" to who.email.orEmpty(),
            "nickname" to user.getString("nickname"),
            "role" to if (membership.exists()) "merchant" else "customer",
            "store_id" to membership.getString("store_id"),
        )
    }

    private suspend fun ownStore(): String {
        val staff = db.collection("merchants").document(uid).get(Source.SERVER).await()
        return staff.getString("store_id") ?: error("승인된 매장 운영자만 사용할 수 있어요.")
    }

    private fun occupied(slot: DocumentSnapshot, now: Long): Boolean =
        slot.exists() &&
            (slot.getString("status") == "reserved" ||
                (slot.getString("status") == "held" &&
                    slot.getTimestamp("expires")!!.toDate().time > now))

    private suspend fun dealJson(doc: DocumentSnapshot): JSONObject {
        val slots = doc.reference.collection("slots").limit(99).get(Source.SERVER).await().documents
        val confirmed = slots.count { it.getString("status") == "reserved" }
        val held = slots.count { it.getString("status") == "held" && occupied(it, nowMillis()) }
        val price = doc.getLong("price")!!.toInt()
        val original = doc.getLong("original")!!.toInt()
        return json(doc)
            .put("available", (doc.getLong("stock")!!.toInt() - confirmed - held).coerceAtLeast(0))
            .put("confirmed", confirmed)
            .put("held", held)
            .put("discount", (original - price) * 100 / original)
    }

    private suspend fun catalog(merchant: Boolean = false): JSONObject {
        val query =
            if (merchant) db.collection("deals").whereEqualTo("store_id", ownStore()).limit(20)
            else
                db.collection("deals")
                    .whereEqualTo("stopped", false)
                    .whereGreaterThan("end", Timestamp.now())
                    .orderBy("end")
                    .limit(20)
        val rows = query.get(Source.SERVER).await().documents
        return obj("items" to JSONArray(rows.map { dealJson(it) }))
    }

    private suspend fun orderJson(
        doc: DocumentSnapshot,
        includeReview: Boolean = true,
    ): JSONObject {
        val out = json(doc)
        val state = out.getString("status")
        out.put(
            "canCancel",
            state == "waiting_pickup" &&
                nowMillis() < doc.getTimestamp("start")!!.toDate().time - 1800000,
        )
        out.put(
            "reviewed",
            includeReview &&
                db.collection("reviews").document(doc.id).get(Source.SERVER).await().exists(),
        )
        if (state != "waiting_pickup") {
            out.put("code", "")
            out.put("token", "")
        }
        return out
    }

    private suspend fun orders(): JSONObject {
        val rows =
            db.collection("orders")
                .whereEqualTo("user_id", uid)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(30)
                .get(Source.SERVER)
                .await()
                .documents
        val out = mutableListOf<JSONObject>()
        val now = syncClock()
        for (doc in rows) {
            var current = doc
            if (
                doc.getString("status") == "waiting_pickup" &&
                    doc.getTimestamp("end")!!.toDate().time <= now
            ) {
                db.runTransaction { tx ->
                        val latest = tx.get(doc.reference)
                        if (latest.getString("status") == "waiting_pickup")
                            tx.update(
                                doc.reference,
                                mapOf(
                                    "status" to "no_show",
                                    "version" to latest.getLong("version")!! + 1,
                                ),
                            )
                    }
                    .await()
                current = doc.reference.get(Source.SERVER).await()
            }
            out.add(orderJson(current))
        }
        lastOwnOrders = out
        lastOrderUid = uid
        return obj("items" to JSONArray(out))
    }

    private suspend fun hold(b: JSONObject): JSONObject {
        val quantity = b.getInt("quantity")
        check(quantity in 1..5) { "수량은 1~5개만 선택해 주세요." }
        val key = b.getString("requestKey")
        check(key.length in 1..80 && !key.contains('/'))
        val id = key
        val ref = db.collection("holds").document(id)
        val previous = ref.get(Source.SERVER).await()
        if (previous.exists()) {
            check(
                previous.getString("deal_id") == b.getString("dealId") &&
                    previous.getLong("quantity") == quantity.toLong()
            ) {
                "같은 요청 키의 내용이 달라요."
            }
            return json(previous)
        }
        val now = syncClock()
        val dealRef = db.collection("deals").document(b.getString("dealId"))
        val d = dealRef.get(Source.SERVER).await()
        check(d.exists()) { "상품을 찾을 수 없어요." }
        val existing =
            dealRef.collection("slots").limit(99).get(Source.SERVER).await().documents.associateBy {
                it.id
            }
        val candidates =
            (0 until d.getLong("stock")!!.toInt())
                .map { it.toString() }
                .filter { existing[it]?.let { s -> !occupied(s, now) } ?: true }
                .take(quantity)
        check(candidates.size == quantity) { "남은 수량이 부족해요. 상품을 새로고침해 주세요." }
        db.runTransaction { tx ->
                val old = tx.get(ref)
                val latest = tx.get(dealRef)
                val slots = candidates.map { tx.get(dealRef.collection("slots").document(it)) }
                if (old.exists()) {
                    check(
                        old.getString("deal_id") == b.getString("dealId") &&
                            old.getLong("quantity") == quantity.toLong()
                    ) {
                        "같은 요청 키의 내용이 달라요."
                    }
                } else {
                    check(
                        latest.getBoolean("stopped") != true &&
                            latest.getTimestamp("end")!!.toDate().time > now
                    ) {
                        "예약이 종료됐어요."
                    }
                    check(slots.none { occupied(it, now) }) { "다른 고객이 먼저 선점했어요. 상품을 새로고침해 주세요." }
                    val expires =
                        Timestamp(
                            java.util.Date(
                                minOf(now + 295000, latest.getTimestamp("end")!!.toDate().time)
                            )
                        )
                    if (latest.getBoolean("locked") != true)
                        tx.update(dealRef, mapOf("locked" to true, "lockHoldId" to id))
                    tx.set(
                        ref,
                        mapOf(
                            "user_id" to uid,
                            "deal_id" to dealRef.id,
                            "quantity" to quantity,
                            "slots" to candidates,
                            "expires" to expires,
                            "status" to "active",
                            "requestKey" to key,
                            "createdAt" to FieldValue.serverTimestamp(),
                        ),
                    )
                    candidates.forEach { sid ->
                        tx.set(
                            dealRef.collection("slots").document(sid),
                            mapOf(
                                "index" to sid.toInt(),
                                "holdId" to id,
                                "status" to "held",
                                "expires" to expires,
                            ),
                        )
                    }
                }
            }
            .await()
        return json(ref.get(Source.SERVER).await())
    }

    private fun slots(h: DocumentSnapshot): List<String> =
        (h.get("slots") as List<*>).map { it as String }

    private fun slotRefs(h: DocumentSnapshot) =
        slots(h).map {
            db.collection("deals")
                .document(h.getString("deal_id")!!)
                .collection("slots")
                .document(it)
        }

    private fun free(tx: Transaction, slot: DocumentSnapshot) {
        tx.set(
            slot.reference,
            mapOf(
                "index" to slot.getLong("index"),
                "holdId" to "",
                "status" to "free",
                "expires" to Timestamp(java.util.Date(0)),
            ),
        )
    }

    private suspend fun releaseHold(id: String) {
        val ref = db.collection("holds").document(id)
        db.runTransaction { tx ->
                val h = tx.get(ref)
                check(h.getString("user_id") == uid)
                val all = slotRefs(h).map { tx.get(it) }
                if (h.getString("status") != "released") {
                    check(h.getString("status") == "active") { "이 선점은 이미 처리됐어요." }
                    all.filter { it.getString("holdId") == id }.forEach { free(tx, it) }
                    tx.update(ref, "status", "released")
                }
            }
            .await()
    }

    private suspend fun confirm(b: JSONObject): JSONObject {
        val id = b.getString("holdId")
        val key = b.getString("requestKey")
        val mode = b.getString("paymentMode")
        check(b.optBoolean("policyAgreed")) { "픽업 정책을 확인해 주세요." }
        if (mode == "failure") {
            releaseHold(id)
            return obj("paymentFailed" to true, "message" to "테스트 결제 실패. 선점 수량이 반환됐어요.")
        }
        check(mode in listOf("success", "delay"))
        syncClock()
        val orderRef = db.collection("orders").document(id)
        val holdRef = db.collection("holds").document(id)
        val op = db.collection("operations").document("${uid}_confirm_$key")
        val code = "%08d".format(java.util.Locale.US, random.nextInt(100000000))
        val codeRef = db.collection("pickupCodes").document(code)
        val token =
            ByteArray(32)
                .also { random.nextBytes(it) }
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        db.runTransaction { tx ->
                val operation = tx.get(op)
                val old = tx.get(orderRef)
                if (operation.exists())
                    check(operation.getString("orderId") == id) { "같은 요청 키를 다른 예약에 쓸 수 없어요." }
                if (old.exists()) {
                    check(
                        old.getString("user_id") == uid &&
                            old.getString("confirmKey") == key &&
                            old.getString("paymentMode") == mode
                    ) {
                        "예약이 이미 확정되었거나 같은 키의 내용이 달라요."
                    }
                } else {
                    val h = tx.get(holdRef)
                    val d = tx.get(db.collection("deals").document(h.getString("deal_id")!!))
                    val all = slotRefs(h).map { tx.get(it) }
                    val codeDoc = tx.get(codeRef)
                    check(!codeDoc.exists()) { "코드를 다시 발급해야 해요. 다시 시도해 주세요." }
                    check(
                        h.getString("status") == "active" &&
                            h.getTimestamp("expires")!!.toDate().time > nowMillis()
                    ) {
                        "선점이 만료됐어요. 다시 수량을 선택해 주세요."
                    }
                    check(
                        all.all { it.getString("holdId") == id && it.getString("status") == "held" }
                    ) {
                        "선점 수량이 변경됐어요."
                    }
                    val data =
                        mapOf(
                            "user_id" to uid,
                            "deal_id" to d.id,
                            "store_id" to d.getString("store_id"),
                            "holdId" to id,
                            "qty" to h.getLong("quantity"),
                            "total" to h.getLong("quantity")!! * d.getLong("price")!!,
                            "title" to d.getString("title"),
                            "store" to d.getString("store"),
                            "start" to d.getTimestamp("start"),
                            "end" to d.getTimestamp("end"),
                            "status" to "waiting_pickup",
                            "code" to code,
                            "token" to token,
                            "version" to 1,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "confirmKey" to key,
                            "paymentMode" to mode,
                        )
                    tx.set(orderRef, data)
                    tx.update(holdRef, "status", "confirmed")
                    all.forEach { tx.update(it.reference, "status", "reserved") }
                    tx.set(
                        codeRef,
                        mapOf(
                            "orderId" to id,
                            "user_id" to uid,
                            "store_id" to d.getString("store_id"),
                        ),
                    )
                    tx.set(
                        op,
                        mapOf(
                            "user_id" to uid,
                            "orderId" to id,
                            "requestKey" to key,
                            "createdAt" to FieldValue.serverTimestamp(),
                        ),
                    )
                }
            }
            .await()
        return orderJson(orderRef.get(Source.SERVER).await())
    }

    private suspend fun cancel(id: String): JSONObject {
        val ref = db.collection("orders").document(id)
        syncClock()
        db.runTransaction { tx ->
                val o = tx.get(ref)
                check(o.getString("user_id") == uid)
                if (o.getString("status") != "cancelled") {
                    val h = tx.get(db.collection("holds").document(id))
                    val all = slotRefs(h).map { tx.get(it) }
                    check(
                        o.getString("status") == "waiting_pickup" &&
                            nowMillis() < o.getTimestamp("start")!!.toDate().time - 1800000
                    ) {
                        "픽업 시작 30분 전까지만 취소할 수 있어요."
                    }
                    all.forEach {
                        check(it.getString("holdId") == id)
                        free(tx, it)
                    }
                    tx.update(
                        ref,
                        mapOf("status" to "cancelled", "version" to o.getLong("version")!! + 1),
                    )
                }
            }
            .await()
        return obj("ok" to true)
    }

    private suspend fun verify(code: String): JSONObject {
        val store = ownStore()
        check(code.matches(Regex("[0-9]{8}"))) { "8자리 코드를 입력해 주세요." }
        val lookup = db.collection("pickupCodes").document(code).get(Source.SERVER).await()
        if (!lookup.exists() || lookup.getString("store_id") != store)
            return obj("verificationFailed" to true, "message" to "코드가 잘못되었거나 다른 매장 예약이에요.")
        val o =
            db.collection("orders")
                .document(lookup.getString("orderId")!!)
                .get(Source.SERVER)
                .await()
        check(o.getString("status") == "waiting_pickup") { "이미 사용되었거나 처리된 코드예요." }
        val now = syncClock()
        check(
            now >= o.getTimestamp("start")!!.toDate().time &&
                now < o.getTimestamp("end")!!.toDate().time
        ) {
            "픽업 시간에만 확인할 수 있어요."
        }
        return json(
            o
        ) // Read only. Completion performs all permission/time/status checks again in rules.
    }

    private suspend fun complete(b: JSONObject): JSONObject {
        val store = ownStore()
        val ref = db.collection("orders").document(b.getString("pickupOrderId"))
        syncClock()
        db.runTransaction { tx ->
                val o = tx.get(ref)
                check(o.getString("store_id") == store)
                if (o.getString("status") != "picked_up") {
                    check(
                        o.getString("status") == "waiting_pickup" &&
                            o.getLong("version") == b.getLong("expectedVersion")
                    ) {
                        "예약 상태가 변경됐어요. 다시 확인해 주세요."
                    }
                    tx.update(
                        ref,
                        mapOf("status" to "picked_up", "version" to o.getLong("version")!! + 1),
                    )
                }
            }
            .await()
        return obj("ok" to true)
    }

    private suspend fun favorites(): JSONObject {
        val rows =
            db.collection("users")
                .document(uid)
                .collection("favorites")
                .limit(30)
                .get(Source.SERVER)
                .await()
                .documents
        val stores = mutableListOf<JSONObject>()
        for (r in rows) {
            val d =
                db.collection("deals").document(r.getString("dealId")!!).get(Source.SERVER).await()
            if (d.exists()) stores.add(dealJson(d))
        }
        return obj("items" to JSONArray(rows.map { it.id }), "stores" to JSONArray(stores))
    }

    private suspend fun saveDeal(id: String?, b: JSONObject): JSONObject {
        val store = ownStore()
        val ref =
            if (id == null) db.collection("deals").document()
            else db.collection("deals").document(id)
        val old = if (id == null) null else ref.get(Source.SERVER).await()
        if (old != null) check(old.getString("store_id") == store)
        val data =
            mapOf(
                "store_id" to store,
                "store" to (old?.getString("store") ?: profile().getString("nickname")),
                "title" to b.getString("title").trim(),
                "category" to (old?.getString("category") ?: "베이커리"),
                "region" to (old?.getString("region") ?: "성수동"),
                "address" to (old?.getString("address") ?: "매장 주소를 운영자에게 확인해 주세요"),
                "original" to b.getInt("original"),
                "price" to b.getInt("price"),
                "stock" to b.getInt("stock"),
                "start" to time(b.getDouble("start")),
                "end" to time(b.getDouble("end")),
                "description" to b.getString("description").trim(),
                "allergens" to b.getString("allergens").trim(),
                "stopped" to (old?.getBoolean("stopped") ?: false),
                "locked" to (old?.getBoolean("locked") ?: false),
                "lockHoldId" to (old?.getString("lockHoldId") ?: ""),
            )
        if (old?.getBoolean("locked") == true)
            check(
                listOf("title", "description", "allergens", "price", "original", "start", "end")
                    .all {
                        data[it] == old.get(it) ||
                            (data[it] is Int && (data[it] as Int).toLong() == old.get(it))
                    }
            ) {
                "한 번이라도 선점된 상품은 구성·가격·시간 변경이 제한돼요. 새 상품으로 등록해 주세요."
            }
        check(b.getInt("stock") in 1..99) { "Spark MVP는 상품당 총수량 1~99개를 지원해요." }
        ref.set(data).await()
        return dealJson(ref.get(Source.SERVER).await())
    }

    suspend fun request(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
    ): JSONObject {
        requireProject()
        val b = body ?: JSONObject()
        when (path) {
            "/health" ->
                return obj(
                    "status" to "ok",
                    "serverTime" to
                        (if (hasSession) syncClock() / 1000.0
                        else System.currentTimeMillis() / 1000.0),
                )
            "/deals" -> return catalog()
            "/auth/login" -> {
                auth
                    .signInWithEmailAndPassword(
                        b.getString("email").trim(),
                        b.getString("password"),
                    )
                    .await()
                return obj("token" to "firebase-sdk-session", "user" to profile())
            }
            "/auth/signup" -> {
                val nickname = b.getString("nickname").trim()
                check(
                    nickname.length in 2..20 && b.optBoolean("terms") && b.optBoolean("privacy")
                ) {
                    "닉네임과 필수 약관을 확인해 주세요."
                }
                check(b.getString("password").length in 8..64) { "비밀번호는 8~64자로 입력해 주세요." }
                try {
                    auth
                        .createUserWithEmailAndPassword(
                            b.getString("email").trim(),
                            b.getString("password"),
                        )
                        .await()
                } catch (e: FirebaseAuthUserCollisionException) {
                    auth
                        .signInWithEmailAndPassword(
                            b.getString("email").trim(),
                            b.getString("password"),
                        )
                        .await()
                    check(
                        !db.collection("users").document(uid).get(Source.SERVER).await().exists()
                    ) {
                        "이미 가입된 이메일이에요."
                    }
                }
                db.collection("users")
                    .document(uid)
                    .set(
                        mapOf(
                            "nickname" to nickname,
                            "email" to auth.currentUser!!.email,
                            "terms" to true,
                            "privacy" to true,
                        )
                    )
                    .await()
                return obj("token" to "firebase-sdk-session", "user" to profile())
            }
            "/auth/logout" -> {
                auth.signOut()
                lastOwnOrders = emptyList()
                lastOrderUid = ""
                return obj("ok" to true)
            }
            "/auth/password-reset-requests" -> {
                try {
                    auth.sendPasswordResetEmail(b.getString("email").trim()).await()
                } catch (e: com.google.firebase.auth.FirebaseAuthInvalidUserException) {
                    if (e.errorCode != "ERROR_USER_NOT_FOUND") throw e
                }
                return obj("message" to "가입된 이메일이라면 재설정 링크가 발송됩니다.")
            }
        }
        if (path.startsWith("/deals/"))
            return dealJson(
                db.collection("deals")
                    .document(path.substringAfterLast('/'))
                    .get(Source.SERVER)
                    .await()
            )
        uid // All remaining routes require a real Firebase Auth session, never an anonymous guest.
        when (path) {
            "/me" -> return profile()
            "/stock-holds" -> return hold(b)
            "/pickup-orders" -> return if (method == "POST") confirm(b) else orders()
            "/favorites/stores" -> {
                if (method == "POST") {
                    val d =
                        db.collection("deals")
                            .whereEqualTo("store_id", b.getString("storeId"))
                            .limit(1)
                            .get(Source.SERVER)
                            .await()
                            .documents
                            .firstOrNull() ?: error("매장을 찾을 수 없어요.")
                    db.collection("users")
                        .document(uid)
                        .collection("favorites")
                        .document(b.getString("storeId"))
                        .set(mapOf("dealId" to d.id))
                        .await()
                }
                return favorites()
            }
            "/me/notification-preferences" -> {
                val ref =
                    db.collection("users")
                        .document(uid)
                        .collection("settings")
                        .document("notifications")
                if (method == "PATCH")
                    ref.set(b.keys().asSequence().associateWith { b.getBoolean(it) }).await()
                val d = ref.get(Source.SERVER).await()
                return if (d.exists()) json(d).apply { remove("id") }
                else
                    obj(
                        "pickup_updates" to true,
                        "reminders" to true,
                        "completion" to true,
                        "merchant_ops" to true,
                    )
            }
            "/reviews" -> {
                db.collection("reviews")
                    .document(b.getString("pickupOrderId"))
                    .set(
                        mapOf(
                            "user_id" to uid,
                            "rating" to b.getInt("rating"),
                            "content" to b.getString("content"),
                            "createdAt" to FieldValue.serverTimestamp(),
                        )
                    )
                    .await()
                return obj("ok" to true)
            }
            "/merchant/deals" -> return if (method == "POST") saveDeal(null, b) else catalog(true)
            "/merchant/pickups/verify" -> return verify(b.getString("numericCode"))
            "/merchant/pickups/complete" -> return complete(b)
            "/notifications" -> {
                // Spark has no trusted scheduled worker/outbox. Derive the inbox from authorized
                // order state.
                val membership = db.collection("merchants").document(uid).get(Source.SERVER).await()
                val os =
                    if (membership.exists())
                        db.collection("orders")
                            .whereEqualTo("store_id", membership.getString("store_id"))
                            .orderBy("createdAt", Query.Direction.DESCENDING)
                            .limit(30)
                            .get(Source.SERVER)
                            .await()
                            .documents
                            .map { json(it) }
                    else if (lastOrderUid == uid) lastOwnOrders else orders().rows()
                return obj(
                    "items" to
                        JSONArray(
                            os.map { o ->
                                obj(
                                    "id" to o.getString("id") + ":" + o.getInt("version"),
                                    "order_id" to o.getString("id"),
                                    "title" to
                                        when (o.getString("status")) {
                                            "waiting_pickup" -> "픽업 예약이 완료됐어요"
                                            "picked_up" -> "상품 수령이 완료됐어요"
                                            "cancelled" -> "픽업 예약이 취소됐어요"
                                            else -> "픽업 시간이 종료됐어요"
                                        },
                                    "body" to "${o.getString("store")} · 최신 거래 상태를 확인해 주세요.",
                                    "created" to o.getDouble("createdAt"),
                                    "read" to 1,
                                )
                            }
                        )
                )
            }
        }
        if (path.startsWith("/favorites/stores/") && method == "DELETE") {
            db.collection("users")
                .document(uid)
                .collection("favorites")
                .document(path.substringAfterLast('/'))
                .delete()
                .await()
            return obj("ok" to true)
        }
        if (path.startsWith("/pickup-orders/")) {
            val id = path.split('/')[2]
            return if (path.endsWith("/cancel")) cancel(id)
            else orderJson(db.collection("orders").document(id).get(Source.SERVER).await())
        }
        if (path.startsWith("/merchant/deals/")) {
            val id = path.split('/')[3]
            if (path.endsWith("/stop")) {
                ownStore()
                db.collection("deals").document(id).update("stopped", true).await()
                return obj("ok" to true)
            }
            return saveDeal(id, b)
        }
        if (path.startsWith("/notifications/"))
            return obj("ok" to true) // Derived status has no unread event claim.
        error("지원하지 않는 Firebase 작업이에요.")
    }
}
