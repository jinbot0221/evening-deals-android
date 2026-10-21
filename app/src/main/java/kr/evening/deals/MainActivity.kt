package kr.evening.deals

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.*
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.SubcomposeAsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.text.NumberFormat
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import org.json.JSONObject

val Orange = Color(0xFFE87927)
val Ink = Color(0xFF17202F)
val Muted = Color(0xFF7D8795)
val Canvas = Color(0xFFF7F8FA)
val Green = Color(0xFF24846B)

fun won(n: Int) = NumberFormat.getNumberInstance(java.util.Locale.KOREA).format(n) + "원"

fun date(t: Double, pattern: String = "M월 d일 HH:mm") =
    Instant.ofEpochSecond(t.toLong())
        .atZone(ZoneId.of("Asia/Seoul"))
        .format(DateTimeFormatter.ofPattern(pattern))

fun window(d: JSONObject) = date(d.getDouble("start")) + " – " + date(d.getDouble("end"), "HH:mm")

fun status(s: String) =
    when (s) {
        "waiting_pickup" -> "픽업 대기"
        "picked_up" -> "수령 완료"
        "cancelled" -> "취소"
        else -> "미수령"
    }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme =
                    lightColorScheme(
                        primary = Orange,
                        onPrimary = Color.White,
                        background = Canvas,
                        surface = Color.White,
                        onSurface = Ink,
                        secondary = Green,
                    ),
                typography = Typography(),
            ) {
                EveningApp()
            }
        }
    }
}

@Composable
fun Primary(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick,
        Modifier.fillMaxWidth().heightIn(min = 56.dp),
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
fun Heading(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
        if (subtitle != null) Text(subtitle, color = Muted, fontSize = 14.sp)
    }
}

@Composable
fun Tag(text: String, color: Color = Orange) {
    Text(
        text,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier =
            Modifier.clip(RoundedCornerShape(6.dp))
                .background(color.copy(alpha = .09f))
                .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        content = content,
    )
}

@Composable
fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1.5f))
    }
}

@Composable
fun Empty(title: String, body: String, action: String? = null, onClick: () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(Icons.Outlined.ShoppingBag, null, Modifier.size(48.dp), tint = Orange)
        Text(title, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        Text(body, color = Muted)
        if (action != null) OutlinedButton(onClick) { Text(action) }
    }
}

@Composable
fun Field(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    password: Boolean = false,
    numeric: Boolean = false,
    lines: Int = 1,
) {
    OutlinedTextField(
        value,
        onValueChange,
        Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = lines == 1,
        minLines = lines,
        shape = RoundedCornerShape(12.dp),
        visualTransformation =
            if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions =
            KeyboardOptions(
                keyboardType =
                    if (password) KeyboardType.Password
                    else if (numeric) KeyboardType.Number else KeyboardType.Text
            ),
    )
}

@Composable
fun Check(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { change(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, change)
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
fun FoodImage(d: JSONObject, modifier: Modifier = Modifier) {
    SubcomposeAsyncImage(
        model =
            when (d.optString("category")) {
                "베이커리" -> R.drawable.bread
                "도시락" -> R.drawable.rice
                "샐러드" -> R.drawable.salad
                else -> d.optString("image")
            },
        contentDescription = d.optString("title"),
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxWidth(),
        loading = {
            Box(
                Modifier.fillMaxSize().background(Color(0xFFF2E7DC)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Restaurant, null, tint = Orange)
            }
        },
        error = {
            Box(
                Modifier.fillMaxSize().background(Color(0xFFF2E7DC)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Restaurant, null, Modifier.size(40.dp), tint = Orange)
                    Text(d.optString("category"), color = Orange)
                }
            }
        },
    )
}

@Composable
fun DealCard(d: JSONObject, favorite: Boolean, onFavorite: () -> Unit, onClick: () -> Unit) {
    Surface(
        onClick,
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Box {
                FoodImage(d, Modifier.height(184.dp))
                Box(Modifier.padding(12.dp)) {
                    Tag(
                        if (d.optInt("available") == 0) "품절" else "${d.optInt("available")}개 남았어요",
                        if (d.optInt("available") == 0) Muted else Orange,
                    )
                }
                IconButton(
                    onFavorite,
                    Modifier.align(Alignment.TopEnd)
                        .padding(5.dp)
                        .background(Color.White.copy(alpha = .92f), RoundedCornerShape(50)),
                ) {
                    Icon(
                        if (favorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                        "매장 찜",
                        tint = if (favorite) Orange else Ink,
                    )
                }
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d.getString("store"), color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    Text(d.optString("region"), color = Muted, fontSize = 12.sp)
                }
                Text(d.getString("title"), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${d.getInt("discount")}%",
                        color = Orange,
                        fontSize = 23.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(won(d.getInt("price")), fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Text(
                        won(d.getInt("original")),
                        fontSize = 12.sp,
                        color = Muted,
                        textDecoration = TextDecoration.LineThrough,
                    )
                }
                HorizontalDivider(color = Canvas)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(Icons.Outlined.Schedule, null, Modifier.size(16.dp), tint = Green)
                    Text("픽업 ${window(d)}", fontSize = 12.sp, color = Green)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EveningApp(vm: DealsViewModel = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var route by rememberSaveable { mutableStateOf("home") }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var selectedOrderId by rememberSaveable { mutableStateOf("") }
    var quantity by rememberSaveable { mutableIntStateOf(1) }
    var holdJson by rememberSaveable { mutableStateOf("") }
    var verifiedJson by rememberSaveable { mutableStateOf("") }
    var returnRoute by rememberSaveable { mutableStateOf("home") }
    var loginSheet by remember { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf("전체") }
    var region by rememberSaveable { mutableStateOf("성수동") }
    var maxPrice by rememberSaveable { mutableIntStateOf(20000) }
    var search by rememberSaveable { mutableStateOf("") }
    var storeFilter by rememberSaveable { mutableStateOf("") }
    var historyFilter by rememberSaveable { mutableStateOf("waiting_pickup") }
    var cancelDialog by remember { mutableStateOf(false) }
    var stopId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    val listState = rememberLazyListState()
    val selected =
        vm.deals.find { it.optString("id") == selectedId }
            ?: vm.merchantDeals.find { it.optString("id") == selectedId }
    val order = vm.orders.find { it.optString("id") == selectedOrderId }
    val context = LocalContext.current
    fun protect(action: () -> Unit) {
        if (vm.signedIn) action() else loginSheet = true
    }
    fun back() {
        route =
            when (route) {
                "detail" -> "home"
                "quantity" -> "detail"
                "confirm" -> "quantity"
                "code",
                "review" -> "home"
                "signup",
                "reset" -> "login"
                "verify",
                "edit",
                "complete" -> "merchant"
                else -> "home"
            }
        vm.error = null
    }
    BackHandler(route != "home") { back() }
    val title =
        when (route) {
            "detail" -> "상품 상세"
            "quantity" -> "수량 선택"
            "confirm" -> "예약 확인"
            "code" -> "픽업 코드"
            "filter" -> "지역과 필터"
            "login" -> "로그인"
            "signup" -> "회원가입"
            "reset" -> "비밀번호 찾기"
            "review" -> "후기 작성"
            "merchant" -> "매장 관리"
            "verify" -> "픽업 코드 확인"
            "complete" -> "수령 확인"
            "edit" -> if (editing == null) "새 상품 등록" else "상품 수정"
            "settings" -> "알림 설정"
            "inbox" -> "알림함"
            else -> "저녁득템"
        }
    Scaffold(
        containerColor = Canvas,
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold, fontSize = 21.sp) },
                navigationIcon = {
                    if (route != "home")
                        IconButton({ back() }) { Icon(Icons.Outlined.ArrowBack, "뒤로가기") }
                },
                actions = {
                    if (route == "home") {
                        Text("DEMO", color = Orange, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        IconButton({ protect { route = "inbox" } }) {
                            BadgedBox(
                                badge = {
                                    if (vm.notifications.any { it.optInt("read") == 0 }) Badge()
                                }
                            ) {
                                Icon(Icons.Outlined.Notifications, "알림함")
                            }
                        }
                    }
                    IconButton({ vm.refresh() }, enabled = !vm.busy) {
                        Icon(Icons.Outlined.Refresh, "새로고침")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Canvas),
            )
        },
        bottomBar = {
            if (route == "home")
                NavigationBar(containerColor = Color.White) {
                    val icons =
                        listOf(
                            Icons.Outlined.Explore,
                            Icons.Outlined.FavoriteBorder,
                            Icons.Outlined.ReceiptLong,
                            Icons.Outlined.PersonOutline,
                        )
                    listOf("발견", "찜", "픽업내역", "마이").forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(icons[i], t) },
                            label = { Text(t) },
                            colors =
                                NavigationBarItemDefaults.colors(
                                    indicatorColor = Orange.copy(alpha = .12f),
                                    selectedIconColor = Orange,
                                    selectedTextColor = Orange,
                                ),
                        )
                    }
                }
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Orange)
            vm.error?.let { message ->
                Surface(color = Color(0xFFFFEDE5)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            message,
                            color = Color(0xFF9A3C16),
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton({ vm.error = null }) { Icon(Icons.Outlined.Close, "안내 닫기") }
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                when (route) {
                    "home" ->
                        when (tab) {
                            0 -> {
                                val results =
                                    vm.deals.filter {
                                        (category == "전체" ||
                                            it.optString("category") == category) &&
                                            it.optString("region") == region &&
                                            it.optInt("price") <= maxPrice &&
                                            (storeFilter.isBlank() ||
                                                it.optString("store_id") == storeFilter) &&
                                            (search.isBlank() ||
                                                it.optString("title").contains(search) ||
                                                it.optString("store").contains(search))
                                    }
                                LazyColumn(
                                    state = listState,
                                    contentPadding = PaddingValues(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    item {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            TextButton(
                                                { route = "filter" },
                                                contentPadding = PaddingValues(0.dp),
                                            ) {
                                                Icon(
                                                    Icons.Outlined.LocationOn,
                                                    null,
                                                    Modifier.size(19.dp),
                                                )
                                                Text(
                                                    " $region ▾",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 16.sp,
                                                )
                                            }
                                            Spacer(Modifier.weight(1f))
                                            Text("오늘의 작은 이득", fontSize = 12.sp, color = Muted)
                                        }
                                    }
                                    item {
                                        Heading("맛있는 마감,\n기분 좋은 득템", "오늘의 맛을 가볍게, 우리 동네에서 만나요.")
                                    }
                                    item {
                                        Surface(
                                            color = Color(0xFFFFECDD),
                                            shape = RoundedCornerShape(18.dp),
                                        ) {
                                            Row(
                                                Modifier.fillMaxWidth().padding(18.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Column(
                                                    Modifier.weight(1f),
                                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                                ) {
                                                    Text(
                                                        "맛은 그대로, 가격은 절반",
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF9A4A17),
                                                    )
                                                    Text(
                                                        "남은 음식을 구하는 가장 맛있는 방법",
                                                        fontSize = 12.sp,
                                                        color = Color(0xFF9A6C4A),
                                                    )
                                                }
                                                Icon(
                                                    Icons.Outlined.LocalDining,
                                                    null,
                                                    Modifier.size(36.dp),
                                                    tint = Orange,
                                                )
                                            }
                                        }
                                    }
                                    item {
                                        OutlinedTextField(
                                            search,
                                            { search = it },
                                            Modifier.fillMaxWidth(),
                                            placeholder = {
                                                Text("매장이나 음식을 검색해 보세요", fontSize = 14.sp)
                                            },
                                            leadingIcon = { Icon(Icons.Outlined.Search, null) },
                                            singleLine = true,
                                            shape = RoundedCornerShape(14.dp),
                                        )
                                    }
                                    item {
                                        Row(
                                            Modifier.horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            listOf("전체", "베이커리", "도시락", "샐러드").forEach { c ->
                                                FilterChip(
                                                    selected = category == c,
                                                    onClick = { category = c },
                                                    label = { Text(c) },
                                                )
                                            }
                                        }
                                    }
                                    item {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                "지금 득템할 수 있어요",
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                            Spacer(Modifier.weight(1f))
                                            TextButton({ route = "filter" }) {
                                                Icon(
                                                    Icons.Outlined.Tune,
                                                    null,
                                                    Modifier.size(16.dp),
                                                )
                                                Text(" 필터")
                                            }
                                        }
                                    }
                                    if (storeFilter.isNotBlank())
                                        item {
                                            InputChip(
                                                selected = true,
                                                onClick = { storeFilter = "" },
                                                label = { Text("선택 매장만 보기 · 해제") },
                                            )
                                        }
                                    if (!vm.loaded && !vm.busy)
                                        item {
                                            Empty(
                                                "연결을 기다리고 있어요",
                                                "서버를 실행한 뒤 다시 시도해 주세요.",
                                                "다시 시도",
                                                { vm.refresh() },
                                            )
                                        }
                                    else if (vm.loaded && results.isEmpty())
                                        item {
                                            Empty(
                                                "조건에 맞는 상품이 없어요",
                                                "지역이나 가격 조건을 바꿔보세요.",
                                                "필터 초기화",
                                                {
                                                    category = "전체"
                                                    maxPrice = 20000
                                                    search = ""
                                                    storeFilter = ""
                                                    region = "성수동"
                                                },
                                            )
                                        }
                                    items(results, key = { it.getString("id") }) { d ->
                                        DealCard(
                                            d,
                                            d.optString("store_id") in vm.favorites,
                                            { protect { vm.favorite(d) } },
                                            {
                                                selectedId = d.getString("id")
                                                quantity = 1
                                                route = "detail"
                                            },
                                        )
                                    }
                                    item {
                                        Text(
                                            "가상 매장 · 테스트 결제 전용 서비스",
                                            color = Muted,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(vertical = 10.dp),
                                        )
                                    }
                                }
                            }
                            1 ->
                                Page {
                                    Heading("찜한 매장", "다시 만나고 싶은 우리 동네 맛집")
                                    if (!vm.signedIn)
                                        Empty(
                                            "좋아하는 매장을 모아보세요",
                                            "로그인하면 찜한 매장을 저장할 수 있어요.",
                                            "로그인하기",
                                            { loginSheet = true },
                                        )
                                    else {
                                        val stores = vm.favoriteStores
                                        if (stores.isEmpty())
                                            Empty(
                                                "아직 찜한 매장이 없어요",
                                                "발견에서 하트를 눌러보세요.",
                                                "발견으로",
                                                { tab = 0 },
                                            )
                                        stores.forEach { d ->
                                            Panel {
                                                FoodImage(
                                                    d,
                                                    Modifier.height(140.dp)
                                                        .clip(RoundedCornerShape(12.dp)),
                                                )
                                                Text(
                                                    d.getString("store"),
                                                    fontSize = 20.sp,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                                Text(
                                                    "${d.optString("region")} · 판매 상품 ${vm.deals.count{it.optString("store_id")==d.optString("store_id")}}개",
                                                    color = Muted,
                                                )
                                                Row {
                                                    TextButton({
                                                        storeFilter = d.getString("store_id")
                                                        tab = 0
                                                    }) {
                                                        Text("상품 보기")
                                                    }
                                                    TextButton({ vm.favorite(d) }) { Text("찜 해제") }
                                                }
                                            }
                                        }
                                    }
                                }
                            2 ->
                                Page {
                                    Heading("나의 픽업내역", "예약부터 마지막 한 입까지")
                                    if (!vm.signedIn)
                                        Empty(
                                            "내역은 로그인 후 확인해요",
                                            "비회원도 자유롭게 둘러볼 수 있어요.",
                                            "로그인하기",
                                            { loginSheet = true },
                                        )
                                    else {
                                        Row(
                                            Modifier.horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            listOf(
                                                    "waiting_pickup",
                                                    "picked_up",
                                                    "cancelled",
                                                    "no_show",
                                                )
                                                .forEach { s ->
                                                    FilterChip(
                                                        historyFilter == s,
                                                        { historyFilter = s },
                                                        label = { Text(status(s)) },
                                                    )
                                                }
                                        }
                                        val os =
                                            vm.orders.filter {
                                                it.optString("status") == historyFilter
                                            }
                                        if (os.isEmpty())
                                            Empty(
                                                "${status(historyFilter)} 내역이 없어요",
                                                "오늘의 득템을 찾아보세요.",
                                                "발견으로",
                                                { tab = 0 },
                                            )
                                        os.forEach { o ->
                                            Panel {
                                                Tag(
                                                    status(o.getString("status")),
                                                    if (o.getString("status") == "picked_up") Green
                                                    else Orange,
                                                )
                                                Text(o.getString("store"), color = Muted)
                                                Text(
                                                    o.getString("title"),
                                                    fontSize = 19.sp,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                                Text(
                                                    "${o.getInt("qty")}개 · ${won(o.getInt("total"))}"
                                                )
                                                Text(window(o), color = Muted, fontSize = 13.sp)
                                                if (o.getString("status") == "waiting_pickup")
                                                    Primary("픽업 코드 보기", !vm.busy) {
                                                        selectedOrderId = o.getString("id")
                                                        route = "code"
                                                    }
                                                if (o.getString("status") == "picked_up")
                                                    OutlinedButton(
                                                        {
                                                            selectedOrderId = o.getString("id")
                                                            route = "review"
                                                        },
                                                        enabled = !o.optBoolean("reviewed"),
                                                    ) {
                                                        Text(
                                                            if (o.optBoolean("reviewed")) "후기 작성 완료"
                                                            else "후기 남기기"
                                                        )
                                                    }
                                            }
                                        }
                                    }
                                }
                            else ->
                                Page {
                                    Heading("마이", "반가워요, 오늘도 득템하세요")
                                    Panel {
                                        Icon(
                                            Icons.Outlined.AccountCircle,
                                            null,
                                            Modifier.size(50.dp),
                                            tint = Orange,
                                        )
                                        Text(
                                            vm.user?.optString("nickname") ?: "가볍게 둘러보는 중",
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            vm.user?.optString("email") ?: "로그인하고 나만의 득템을 시작해 보세요.",
                                            color = Muted,
                                        )
                                        if (!vm.signedIn)
                                            Primary("로그인 · 회원가입") {
                                                returnRoute = "home"
                                                route = "login"
                                            }
                                    }
                                    listOf(
                                            "픽업내역" to { tab = 2 },
                                            "찜한 매장" to { tab = 1 },
                                            "알림함" to { route = "inbox" },
                                            "알림 설정" to { route = "settings" },
                                        )
                                        .forEach { (label, action) ->
                                            OutlinedButton(
                                                { protect(action) },
                                                Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                                shape = RoundedCornerShape(12.dp),
                                            ) {
                                                Text(label, Modifier.weight(1f), color = Ink)
                                                Icon(
                                                    Icons.Outlined.ChevronRight,
                                                    null,
                                                    tint = Muted,
                                                )
                                            }
                                        }
                                    if (vm.merchant) Primary("매장 관리 열기") { route = "merchant" }
                                    if (vm.signedIn)
                                        TextButton({
                                            vm.logout {
                                                route = "home"
                                                tab = 3
                                            }
                                        }) {
                                            Text("로그아웃")
                                        }
                                    Text(
                                        "저녁득템 ${BuildConfig.VERSION_NAME}\n포트폴리오 데모 · 실제 과금 없음",
                                        color = Muted,
                                        fontSize = 12.sp,
                                    )
                                }
                        }
                    "filter" ->
                        FilterPage(
                            region,
                            maxPrice,
                            category,
                            { r, p, c ->
                                region = r
                                maxPrice = p
                                category = c
                                route = "home"
                            },
                        )
                    "detail" ->
                        selected?.let { d ->
                            Column {
                                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                                    FoodImage(d, Modifier.height(260.dp))
                                    Column(
                                        Modifier.padding(20.dp),
                                        verticalArrangement = Arrangement.spacedBy(18.dp),
                                    ) {
                                        Row {
                                            Tag("${d.getInt("available")}개 남았어요")
                                            Spacer(Modifier.weight(1f))
                                            IconButton({ protect { vm.favorite(d) } }) {
                                                Icon(
                                                    if (d.getString("store_id") in vm.favorites)
                                                        Icons.Outlined.Favorite
                                                    else Icons.Outlined.FavoriteBorder,
                                                    "매장 찜",
                                                    tint = Orange,
                                                )
                                            }
                                        }
                                        Text(d.getString("store"), color = Muted)
                                        Heading(d.getString("title"))
                                        Text(
                                            won(d.getInt("original")),
                                            color = Muted,
                                            textDecoration = TextDecoration.LineThrough,
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Text(
                                                "${d.getInt("discount")}%",
                                                fontSize = 30.sp,
                                                color = Orange,
                                                fontWeight = FontWeight.Bold,
                                            )
                                            Text(
                                                won(d.getInt("price")),
                                                fontSize = 30.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                        Panel {
                                            Text("오늘의 픽업", fontWeight = FontWeight.Bold)
                                            Line("시간", window(d))
                                            Line("주소", d.getString("address"))
                                            TextButton({
                                                val uri =
                                                    Uri.parse(
                                                        "geo:0,0?q=" +
                                                            Uri.encode(d.getString("address"))
                                                    )
                                                val i = Intent(Intent.ACTION_VIEW, uri)
                                                if (
                                                    i.resolveActivity(context.packageManager) !=
                                                        null
                                                )
                                                    context.startActivity(i)
                                                else vm.error = "지도 앱이 없어요. 표시된 주소로 방문해 주세요."
                                            }) {
                                                Icon(Icons.Outlined.Map, null)
                                                Text(" 매장 위치 보기")
                                            }
                                        }
                                        Heading("알찬 구성", d.getString("description"))
                                        Panel {
                                            Text("알레르기 안내", fontWeight = FontWeight.Bold)
                                            Text(d.getString("allergens"))
                                            Text(
                                                "구성은 랜덤이 아니며, 매장에서 직접 수령해 주세요.",
                                                color = Muted,
                                                fontSize = 13.sp,
                                            )
                                        }
                                    }
                                }
                                Box(Modifier.padding(20.dp)) {
                                    Primary(
                                        if (d.optInt("available") <= 0) "품절된 상품이에요"
                                        else "${won(d.getInt("price"))} · 예약하기",
                                        d.optInt("available") > 0 && !vm.busy,
                                    ) {
                                        quantity = 1
                                        route = "quantity"
                                    }
                                }
                            }
                        }
                            ?: Empty(
                                "상품 정보를 다시 확인해 주세요",
                                "종료되었거나 판매가 중지된 상품이에요.",
                                "발견으로",
                                { route = "home" },
                            )
                    "quantity" ->
                        selected?.let { d ->
                            Page {
                                Heading("얼마나 담아볼까요?", "한 번에 최대 5개까지 예약할 수 있어요.")
                                Panel {
                                    Text(
                                        d.getString("title"),
                                        fontSize = 21.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text("개당 ${won(d.getInt("price"))}", color = Muted)
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        OutlinedIconButton({ quantity-- }, enabled = quantity > 1) {
                                            Icon(Icons.Outlined.Remove, "수량 줄이기")
                                        }
                                        Text(
                                            "${quantity}개",
                                            fontSize = 26.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        OutlinedIconButton(
                                            { quantity++ },
                                            enabled = quantity < minOf(5, d.getInt("available")),
                                        ) {
                                            Icon(Icons.Outlined.Add, "수량 늘리기")
                                        }
                                    }
                                    Line("픽업", window(d))
                                    Line("합계", won(quantity * d.getInt("price")))
                                }
                                Text(
                                    "다음 단계에서 5분 동안 수량을 선점해요. 로그인 전에는 재고를 확보하지 않아요.",
                                    color = Muted,
                                    fontSize = 14.sp,
                                )
                                var key by
                                    rememberSaveable(quantity, selectedId) {
                                        mutableStateOf(requestKey())
                                    }
                                Primary(
                                    "${won(quantity*d.getInt("price"))} · 수량 선점",
                                    !vm.busy && d.getInt("available") >= quantity,
                                ) {
                                    protect {
                                        vm.hold(d.getString("id"), quantity, key) {
                                            holdJson = it.toString()
                                            route = "confirm"
                                            key = requestKey()
                                        }
                                    }
                                }
                            }
                        } ?: Empty("상품이 종료됐어요", "다른 상품을 찾아보세요.", "발견으로", { route = "home" })
                    "confirm" ->
                        if (holdJson.isNotBlank() && selected != null)
                            ConfirmPage(
                                vm,
                                selected,
                                JSONObject(holdJson),
                                { r ->
                                    if (r.optBoolean("paymentFailed")) {
                                        vm.error = r.getString("message")
                                        route = "quantity"
                                    } else {
                                        selectedOrderId = r.getString("id")
                                        route = "code"
                                    }
                                },
                                { route = "quantity" },
                            )
                    "code" ->
                        order?.let { o ->
                            Page {
                                Heading(
                                    if (o.getString("status") == "waiting_pickup") "맛있는 만남이 기다려요"
                                    else status(o.getString("status")),
                                    "${o.getString("store")} · ${window(o)}",
                                )
                                Panel {
                                    Tag(status(o.getString("status")), Green)
                                    if (o.getString("status") == "waiting_pickup") {
                                        Qr(o.getString("token"))
                                        Text(
                                            o.getString("code").chunked(4).joinToString("  "),
                                            fontSize = 34.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 4.sp,
                                            modifier = Modifier.align(Alignment.CenterHorizontally),
                                        )
                                        Text(
                                            "매장 직원에게 코드를 보여주세요.\n직원의 수령 확인 후 완료됩니다.",
                                            color = Muted,
                                            fontSize = 14.sp,
                                        )
                                    }
                                    Text(
                                        o.getString("title"),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 20.sp,
                                    )
                                    Line("수량", "${o.getInt("qty")}개")
                                    Line("결제 금액", won(o.getInt("total")))
                                }
                                Text(
                                    "픽업 시작 30분 전까지 취소할 수 있어요. 픽업 시간이 지나면 미수령으로 처리됩니다.",
                                    color = Muted,
                                    fontSize = 13.sp,
                                )
                                if (o.getString("status") == "waiting_pickup")
                                    OutlinedButton(
                                        { cancelDialog = true },
                                        enabled = o.optBoolean("canCancel") && !vm.busy,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            if (o.optBoolean("canCancel")) "예약 취소"
                                            else "취소 가능 시간이 지났어요"
                                        )
                                    }
                                if (
                                    o.getString("status") == "picked_up" &&
                                        !o.optBoolean("reviewed")
                                )
                                    Primary("후기 남기기") { route = "review" }
                                TextButton({
                                    tab = 2
                                    route = "home"
                                }) {
                                    Text("픽업내역으로")
                                }
                            }
                        }
                            ?: Empty(
                                "내역을 새로고침해 주세요",
                                "로그인 상태와 예약 정보를 확인해 주세요.",
                                "내역으로",
                                {
                                    tab = 2
                                    route = "home"
                                    vm.refresh()
                                },
                            )
                    "login",
                    "signup",
                    "reset" -> AuthPage(route, vm, { route = it }, { route = returnRoute })
                    "review" ->
                        order?.let { o ->
                            ReviewPage(o, vm) {
                                tab = 2
                                historyFilter = "picked_up"
                                route = "home"
                            }
                        }
                    "merchant" ->
                        if (vm.merchant)
                            Page {
                                Heading("오늘의 매장", "온기 베이커리 · 운영자 전용")
                                Primary("픽업 코드 확인") { route = "verify" }
                                OutlinedButton(
                                    {
                                        editing = null
                                        route = "edit"
                                    },
                                    Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Outlined.Add, null)
                                    Text(" 새 상품 등록")
                                }
                                vm.merchantDeals.forEach { d ->
                                    Panel {
                                        Row {
                                            Text(
                                                d.getString("title"),
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Tag(
                                                if (d.optInt("stopped") == 1) "판매 중지"
                                                else if (d.optInt("available") == 0) "품절"
                                                else "판매 중"
                                            )
                                        }
                                        Line(
                                            "총수량 / 확정",
                                            "${d.getInt("stock")} / ${d.getInt("confirmed")}",
                                        )
                                        Line(
                                            "선점 / 판매 가능",
                                            "${d.getInt("held")} / ${d.getInt("available")}",
                                        )
                                        Text(window(d), color = Muted, fontSize = 13.sp)
                                        Row {
                                            TextButton({
                                                editing = d
                                                route = "edit"
                                            }) {
                                                Text("수정")
                                            }
                                            TextButton(
                                                { stopId = d.getString("id") },
                                                enabled = d.optInt("stopped") == 0,
                                            ) {
                                                Text("판매 중지")
                                            }
                                        }
                                    }
                                }
                            }
                        else Empty("운영 권한이 필요해요", "승인된 매장 계정으로 로그인해 주세요.")
                    "verify" ->
                        if (vm.merchant)
                            Page {
                                Heading("픽업 코드를 입력해 주세요", "검증만으로 수령 완료 처리되지 않아요.")
                                var code by rememberSaveable { mutableStateOf("") }
                                Panel {
                                    Icon(
                                        Icons.Outlined.QrCodeScanner,
                                        null,
                                        Modifier.size(70.dp).align(Alignment.CenterHorizontally),
                                        tint = Orange,
                                    )
                                    Text("이 버전은 숫자 코드로 확인해요.", color = Muted)
                                    Field(
                                        code,
                                        "8자리 픽업 코드",
                                        { code = it.filter(Char::isDigit).take(8) },
                                        numeric = true,
                                    )
                                    Primary("예약 확인", code.length == 8 && !vm.busy) {
                                        vm.verify(code) {
                                            verifiedJson = it.toString()
                                            route = "complete"
                                        }
                                    }
                                }
                            }
                    "complete" ->
                        if (vm.merchant && verifiedJson.isNotBlank())
                            Page {
                                val o = JSONObject(verifiedJson)
                                val key = rememberSaveable(verifiedJson) { requestKey() }
                                var checked by remember { mutableStateOf(false) }
                                Heading("이 상품을 전달하셨나요?", "실제 상품과 수량을 확인한 후 확정해 주세요.")
                                Panel {
                                    Text(
                                        o.getString("title"),
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Line("수량", "${o.getInt("qty")}개")
                                    Line("픽업", window(o))
                                    Line("코드", o.getString("code"))
                                }
                                Check("상품과 수량을 확인했어요", checked, { checked = it })
                                Primary("수령 확정", checked && !vm.busy) {
                                    vm.complete(o, key) { route = "merchant" }
                                }
                            }
                    "edit" -> if (vm.merchant) EditDeal(editing, vm) { route = "merchant" }
                    "settings" ->
                        if (vm.signedIn)
                            Page {
                                Heading("필요한 소식만 받아보세요", "알림을 꺼도 예약과 픽업은 이용할 수 있어요.")
                                Panel {
                                    listOf(
                                            "pickup_updates" to "거래 변경",
                                            "reminders" to "픽업 시간 알림",
                                            "completion" to "완료 안내",
                                        )
                                        .plus(
                                            if (vm.merchant) listOf("merchant_ops" to "매장 운영 알림")
                                            else emptyList()
                                        )
                                        .forEach { (k, label) ->
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(label, Modifier.weight(1f))
                                                Switch(
                                                    vm.preferences.optBoolean(k, true),
                                                    { vm.preference(k, it) },
                                                    enabled = !vm.busy,
                                                )
                                            }
                                        }
                                }
                                Text(
                                    "이 데모는 앱 알림함만 제공해요. FCM 프로젝트가 연결되지 않아 기기 푸시는 발송되지 않아요.",
                                    color = Muted,
                                )
                                OutlinedButton({
                                    context.startActivity(
                                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(
                                                Settings.EXTRA_APP_PACKAGE,
                                                context.packageName,
                                            )
                                    )
                                }) {
                                    Text("Android 알림 설정 열기")
                                }
                            }
                    "inbox" ->
                        if (vm.signedIn)
                            Page {
                                Heading("내 득템 소식", "최근 30일의 알림을 확인해 보세요.")
                                if (vm.notifications.isEmpty())
                                    Empty("아직 받은 알림이 없어요", "예약하면 여기서 소식을 확인할 수 있어요.")
                                vm.notifications.forEach { n ->
                                    Surface(
                                        onClick = {
                                            vm.read(n) {
                                                if (vm.merchant) {
                                                    route = "merchant"
                                                } else {
                                                    selectedOrderId = n.getString("order_id")
                                                    route = "code"
                                                }
                                            }
                                        },
                                        shape = RoundedCornerShape(16.dp),
                                        color =
                                            if (n.optInt("read") == 0) Color(0xFFFFEFE3)
                                            else Color.White,
                                    ) {
                                        Column(
                                            Modifier.padding(18.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Text(n.getString("title"), fontWeight = FontWeight.Bold)
                                            Text(
                                                n.getString("body"),
                                                fontSize = 14.sp,
                                                color = Muted,
                                            )
                                            Text(
                                                date(n.getDouble("created")),
                                                fontSize = 12.sp,
                                                color = Muted,
                                            )
                                        }
                                    }
                                }
                            }
                }
            }
        }
    }
    if (loginSheet)
        ModalBottomSheet(onDismissRequest = { loginSheet = false }) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Heading("로그인하고 이어가세요", "찜과 예약은 로그인 후 안전하게 저장돼요.")
                Primary("로그인하기") {
                    returnRoute = route
                    route = "login"
                    loginSheet = false
                }
                TextButton({ loginSheet = false }, Modifier.fillMaxWidth()) { Text("계속 둘러보기") }
                Spacer(Modifier.height(20.dp))
            }
        }
    if (cancelDialog)
        AlertDialog(
            onDismissRequest = { cancelDialog = false },
            title = { Text("예약을 취소할까요?") },
            text = { Text("테스트 결제는 실제 과금이 없으며, 취소 수량은 재고로 반환돼요.") },
            confirmButton = {
                TextButton({
                    cancelDialog = false
                    order?.let {
                        vm.cancel(it.getString("id"), requestKey()) {
                            tab = 2
                            historyFilter = "cancelled"
                            route = "home"
                        }
                    }
                }) {
                    Text("예약 취소")
                }
            },
            dismissButton = { TextButton({ cancelDialog = false }) { Text("유지하기") } },
        )
    stopId?.let { id ->
        AlertDialog(
            onDismissRequest = { stopId = null },
            title = { Text("판매를 중지할까요?") },
            text = { Text("신규 예약만 중단하며 기존 예약은 유지돼요.") },
            confirmButton = {
                TextButton({
                    stopId = null
                    vm.stopDeal(id)
                }) {
                    Text("판매 중지")
                }
            },
            dismissButton = { TextButton({ stopId = null }) { Text("돌아가기") } },
        )
    }
}

@Composable
fun FilterPage(region: String, price: Int, category: String, apply: (String, Int, String) -> Unit) {
    var r by rememberSaveable { mutableStateOf(region) }
    var p by rememberSaveable { mutableIntStateOf(price) }
    var c by rememberSaveable { mutableStateOf(category) }
    Page {
        Heading("어디서 득템할까요?", "위치 권한 없이 지역을 직접 선택할 수 있어요.")
        Field(r, "지역 검색", { r = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("성수동", "연남동", "망원동").forEach {
                FilterChip(r == it, { r = it }, label = { Text(it) })
            }
        }
        Text("카테고리", fontWeight = FontWeight.Bold)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("전체", "베이커리", "도시락", "샐러드").forEach {
                FilterChip(c == it, { c = it }, label = { Text(it) })
            }
        }
        Panel {
            Text("최대 가격 ${won(p)}", fontWeight = FontWeight.Bold)
            Slider(
                p.toFloat(),
                { p = (it / 1000).toInt() * 1000 },
                valueRange = 1000f..20000f,
                steps = 18,
            )
            Text("할인가 기준 · 픽업 마감 임박순 정렬", fontSize = 13.sp, color = Muted)
        }
        Primary("필터 적용", r.isNotBlank()) { apply(r.trim(), p, c) }
        TextButton({
            r = "성수동"
            p = 20000
            c = "전체"
        }) {
            Text("초기화")
        }
    }
}

@Composable
fun ConfirmPage(
    vm: DealsViewModel,
    d: JSONObject,
    h: JSONObject,
    done: (JSONObject) -> Unit,
    reselect: () -> Unit,
) {
    var now by remember { mutableDoubleStateOf(vm.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = vm.now()
            delay(1000)
        }
    }
    val remaining = (h.getDouble("expires") - now).toInt().coerceAtLeast(0)
    var agreed by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf("success") }
    val key = rememberSaveable(h.getString("id"), mode) { requestKey() }
    Page {
        Heading("마지막으로 확인해 주세요", "실제 결제가 없는 테스트 예약이에요.")
        Panel {
            Tag("수량 선점 ${remaining/60}:${(remaining%60).toString().padStart(2,'0')}")
            Text(d.getString("title"), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Line("매장", d.getString("store"))
            Line("픽업", window(d))
            Line("수량", "${h.getInt("quantity")}개")
            HorizontalDivider()
            Line("총 금액", won(h.getInt("quantity") * d.getInt("price")))
        }
        Text("테스트 결제 결과", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("success" to "성공", "failure" to "실패", "delay" to "응답 지연").forEach { (m, t) ->
                FilterChip(mode == m, { mode = m }, label = { Text(t) })
            }
        }
        Check("픽업 시간과 취소 정책을 확인했어요", agreed, { agreed = it })
        Text("픽업 시작 30분 전까지 취소 가능 · 시간 내 미수령 시 자동 취소되지 않아요.", color = Muted, fontSize = 13.sp)
        if (remaining == 0) {
            Text("선점이 만료됐어요. 다시 수량을 선택해 주세요.", color = Orange)
            Primary("다시 선택", !vm.busy, reselect)
        } else
            Primary("테스트 예약 확정", agreed && !vm.busy) {
                vm.confirm(h.getString("id"), mode, key, done)
            }
    }
}

@Composable
fun Qr(token: String) {
    val bitmap =
        remember(token) {
            val bits = MultiFormatWriter().encode(token, BarcodeFormat.QR_CODE, 384, 384)
            Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888).apply {
                for (y in 0 until 384) for (x in 0 until 384) setPixel(
                    x,
                    y,
                    if (bits[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE,
                )
            }
        }
    Image(
        bitmap.asImageBitmap(),
        "매장 수령용 QR 코드",
        Modifier.fillMaxWidth().height(220.dp),
        contentScale = ContentScale.Fit,
    )
}

@Composable
fun AuthPage(route: String, vm: DealsViewModel, navigate: (String) -> Unit, done: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var nick by rememberSaveable { mutableStateOf("") }
    var terms by rememberSaveable { mutableStateOf(false) }
    var privacy by rememberSaveable { mutableStateOf(false) }
    val signup = route == "signup"
    val reset = route == "reset"
    Page {
        Heading(
            if (signup) "반가워요, 득템러!" else if (reset) "비밀번호를 잊으셨나요?" else "오늘의 득템을 시작해요",
            if (reset) "가입 이메일을 입력해 주세요." else "좋은 음식을 더 기분 좋은 가격으로.",
        )
        if (
            !signup && !reset && (BuildConfig.BACKEND == "legacy" || BuildConfig.FIREBASE_EMULATORS)
        ) {
            Panel {
                Text("시연용 계정", fontWeight = FontWeight.Bold)
                Text(
                    "고객  customer@demo.kr\n매장  store@demo.kr\n비밀번호  Demo1234!",
                    color = Muted,
                    fontSize = 13.sp,
                )
                Text("매장 계정은 온기 베이커리만 관리해요.", fontSize = 12.sp, color = Muted)
            }
        }
        if (signup) Field(nick, "닉네임 (2~20자)", { nick = it.take(20) })
        Field(email, "이메일", { email = it })
        if (!reset) Field(password, "비밀번호 (8~64자)", { password = it.take(64) }, password = true)
        if (signup) {
            Field(confirm, "비밀번호 확인", { confirm = it }, password = true)
            Check("[필수] 이용약관 동의", terms, { terms = it })
            Text(
                "테스트 거래 서비스이며 매장에 방문해 수령합니다. 픽업 시작 30분 전까지 취소할 수 있습니다.",
                fontSize = 12.sp,
                color = Muted,
            )
            Check("[필수] 개인정보 처리 동의", privacy, { privacy = it })
            Text(
                "이메일과 닉네임, 거래 기록을 데모 서버에 저장합니다. 실제 개인정보 대신 테스트 정보를 사용해 주세요.",
                fontSize = 12.sp,
                color = Muted,
            )
        }
        Primary(
            if (signup) "가입 완료" else if (reset) "재설정 메일 요청" else "이메일로 로그인",
            !vm.busy &&
                email.contains('@') &&
                (reset || password.length >= 8) &&
                (!signup || (nick.trim().length >= 2 && password == confirm && terms && privacy)),
        ) {
            when {
                signup ->
                    vm.signup(nick.trim(), email, password, terms, privacy) {
                        password = ""
                        confirm = ""
                        done()
                    }
                reset -> vm.reset(email)
                else ->
                    vm.login(email, password) {
                        password = ""
                        done()
                    }
            }
        }
        if (!signup && !reset) {
            Row {
                TextButton({ navigate("signup") }) { Text("회원가입") }
                TextButton({ navigate("reset") }) { Text("비밀번호 찾기") }
            }
            HorizontalDivider()
            Text("소셜 로그인", color = Muted, fontSize = 13.sp)
            listOf("Google", "카카오", "Apple").forEach { provider ->
                OutlinedButton(
                    {
                        vm.error =
                            "$provider 로그인은 공급자 앱 등록과 인증 서버 설정 후 사용할 수 있어요. 이메일 로그인을 이용해 주세요."
                    },
                    Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("$provider 로그인 · 준비 중", color = Muted)
                }
            }
            TextButton(done, Modifier.fillMaxWidth()) { Text("로그인 없이 둘러보기") }
        }
    }
}

@Composable
fun ReviewPage(o: JSONObject, vm: DealsViewModel, done: () -> Unit) {
    var rating by rememberSaveable { mutableIntStateOf(5) }
    var text by rememberSaveable { mutableStateOf("") }
    Page {
        Heading("오늘의 득템은 어땠나요?", o.getString("store"))
        Panel {
            Text(o.getString("title"), fontWeight = FontWeight.Bold)
            Row {
                (1..5).forEach { i ->
                    IconButton({ rating = i }) {
                        Icon(
                            if (i <= rating) Icons.Outlined.Star else Icons.Outlined.StarOutline,
                            "별점 ${i}점",
                            tint = Orange,
                        )
                    }
                }
            }
            Text("${rating}점", color = Muted)
        }
        Field(text, "후기를 남겨주세요 (10~500자)", { text = it.take(500) }, lines = 5)
        Text("${text.trim().length} / 500", color = Muted)
        Primary("후기 등록", text.trim().length in 10..500 && !vm.busy && !o.optBoolean("reviewed")) {
            vm.review(o.getString("id"), rating, text.trim(), done)
        }
    }
}

@Composable
fun EditDeal(d: JSONObject?, vm: DealsViewModel, done: () -> Unit) {
    var title by rememberSaveable { mutableStateOf(d?.optString("title") ?: "") }
    var original by rememberSaveable { mutableStateOf(d?.optInt("original")?.toString() ?: "") }
    var price by rememberSaveable { mutableStateOf(d?.optInt("price")?.toString() ?: "") }
    var stock by rememberSaveable { mutableStateOf(d?.optInt("stock")?.toString() ?: "") }
    var desc by rememberSaveable { mutableStateOf(d?.optString("description") ?: "") }
    var allergens by rememberSaveable { mutableStateOf(d?.optString("allergens") ?: "") }
    val defaultDay =
        Instant.ofEpochSecond(vm.now().toLong())
            .atZone(ZoneId.of("Asia/Seoul"))
            .plusDays(1)
            .toLocalDate()
            .toString()
    var day by rememberSaveable {
        mutableStateOf(d?.let { date(it.getDouble("start"), "yyyy-MM-dd") } ?: defaultDay)
    }
    var start by rememberSaveable {
        mutableStateOf(d?.let { date(it.getDouble("start"), "HH:mm") } ?: "19:00")
    }
    var end by rememberSaveable {
        mutableStateOf(d?.let { date(it.getDouble("end"), "HH:mm") } ?: "20:00")
    }
    Page {
        Heading(if (d == null) "오늘의 상품을 소개해요" else "상품 정보를 수정해요", "예약·선점이 있으면 가격·구성·시간은 변경할 수 없어요.")
        Text("상품 사진은 기본 빵 사진을 사용해요. 사진 업로드는 후속 연동 항목입니다.", color = Muted, fontSize = 13.sp)
        Field(title, "상품명", { title = it.take(50) })
        Field(original, "원가 (원)", { original = it }, numeric = true)
        Field(price, "할인가 (원)", { price = it }, numeric = true)
        Field(stock, "총수량 (1~99, 등록 후 고정)", { stock = it }, numeric = true)
        Field(day, "픽업 날짜 (yyyy-MM-dd)", { day = it })
        Field(start, "시작 (HH:mm)", { start = it })
        Field(end, "종료 (HH:mm)", { end = it })
        Field(desc, "구성 안내 (10~500자)", { desc = it.take(500) }, lines = 3)
        Field(allergens, "알레르기 성분 (없으면 없음)", { allergens = it })
        Primary("상품 저장", !vm.busy) {
            try {
                val zone = ZoneId.of("Asia/Seoul")
                val startTime = LocalDateTime.parse("${day}T$start").atZone(zone).toEpochSecond()
                val endTime = LocalDateTime.parse("${day}T$end").atZone(zone).toEpochSecond()
                vm.saveDeal(
                    d?.getString("id"),
                    obj(
                        "title" to title,
                        "original" to original.toInt(),
                        "price" to price.toInt(),
                        "stock" to stock.toInt(),
                        "description" to desc,
                        "allergens" to allergens,
                        "start" to startTime,
                        "end" to endTime,
                    ),
                    done,
                )
            } catch (e: Exception) {
                vm.error = "가격·수량은 숫자로, 날짜·시간은 표시된 형식으로 입력해 주세요."
            }
        }
    }
}
