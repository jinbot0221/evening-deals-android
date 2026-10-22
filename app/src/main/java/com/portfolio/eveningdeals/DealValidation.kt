package com.portfolio.eveningdeals

import org.json.JSONObject

/** Mirrors public product constraints; Firestore rules still enforce every write. */
internal fun validateDealInput(body: JSONObject, nowSeconds: Double, existingStock: Int? = null) {
    require(body.getString("title").trim().length in 2..50) { "상품명은 2~50자로 입력해 주세요." }
    require(body.getString("description").trim().length in 10..500) { "구성 안내는 10~500자로 입력해 주세요." }
    require(body.getString("allergens").trim().length in 1..200) { "알레르기 성분은 1~200자로 입력해 주세요. 없으면 '없음'을 입력하세요." }
    val original = body.getLong("original")
    val price = body.getLong("price")
    require(original in 1L..100_000_000L) { "원가는 1~100,000,000원으로 입력해 주세요." }
    require(price in 1L..original) { "할인가는 1원 이상, 원가 이하로 입력해 주세요." }
    val stock = body.getLong("stock")
    require(stock in 1L..99L) { "총수량은 1~99개로 입력해 주세요." }
    require(existingStock == null || stock == existingStock.toLong()) { "등록한 상품의 총수량은 변경할 수 없어요. 새 상품으로 등록해 주세요." }
    val start = body.getDouble("start")
    val end = body.getDouble("end")
    require(start.isFinite() && end.isFinite() && start < end) { "픽업 종료 시간은 시작 시간보다 늦어야 해요." }
    require(end > nowSeconds) { "픽업 종료 시간이 지났어요. 날짜와 시간을 확인해 주세요." }
}
