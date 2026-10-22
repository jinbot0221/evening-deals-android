# Firebase Spark 백엔드

현재 기본 백엔드는 Firebase Authentication(이메일/비밀번호) + Cloud Firestore입니다. APK에 서비스 계정 키나 Admin SDK를 넣지 않습니다. Cloud Functions, 별도 Python 서버, Cloud Storage, Cloud Scheduler는 이 경로에서 사용하지 않습니다.

## 실제 프로젝트 연결

1. Firebase 프로젝트를 Spark 요금제로 생성합니다. 표시 이름은 `저녁득템`, 프로젝트 ID는 사용 가능한 고유 ID를 선택합니다. Analytics는 필수가 아닙니다.
2. Android 앱을 패키지 **`com.portfolio.eveningdeals`**로 등록합니다. 내려받은 **`google-services.json`을 `app/google-services.json`에 둡니다**. Git에서는 제외됩니다. 이미 이전 패키지로 Firebase Android 앱을 등록했다면 같은 프로젝트에 `com.portfolio.eveningdeals` 앱을 추가하고 새 설정 파일을 받으세요. 이 파일의 Android 앱 ID·프로젝트 ID·API 키를 Gradle이 읽어 FirebaseOptions에 전달합니다.
3. Authentication → 로그인 방법에서 **이메일/비밀번호**를 활성화합니다. 이메일 열거 보호를 유지하고, 필요한 비밀번호 정책을 콘솔에서 설정합니다.
4. Firestore Database를 **Standard / Native 모드, `(default)` 데이터베이스, 프로덕션 보안 모드**로 생성합니다. 한국 사용자라면 제공되는 서울 리전(`asia-northeast3`)을 검토합니다. 리전은 생성 후 쉽게 바꿀 수 없으므로 생성 화면에서 확인합니다.
5. 로컬 PC에서 Firebase CLI 로그인 후 아래 규칙·인덱스를 배포합니다. 프로젝트 ID는 실제 값으로 바꾸세요. 이 저장소는 원격 프로젝트를 자동 생성하거나 요금제를 변경하지 않습니다.

```bash
npm ci
npx firebase login
npx firebase deploy --only firestore:rules,firestore:indexes --project YOUR_PROJECT_ID
```

CLI 대신 Firestore 콘솔 규칙 편집기에 `firebase/firestore.rules`를 붙여 넣어 게시할 수 있습니다. 복합 인덱스는 `firebase/firestore.indexes.json`을 기준으로 생성합니다. **규칙과 인덱스 배포 전에는 앱 연결 완료로 취급하지 않습니다.** 인덱스 생성은 시간이 걸릴 수 있습니다.

6. 앱에서 고객 계정을 회원가입합니다. 운영자는 가입 유형에서 **업체**를 선택하고 매장명·주소를 입력해 가입 신청합니다. Firebase 콘솔에서 해당 Auth UID를 확인하고, Firestore **`merchants/{UID}`** 문서에 `store_id: "s1"` 같은 소유 매장 ID를 직접 지정합니다. 클라이언트에서는 이 컬렉션을 수정할 수 없습니다.
7. 운영자 계정으로 다시 로그인 → 마이 → 매장 관리에서 상품을 등록합니다. 실제 프로젝트에는 데모 계정이나 샘플 상품을 자동으로 심지 않습니다. 초기 상품 목록이 비어 있는 것은 정상입니다.
8. Android Studio에서 Sync 및 실행합니다. 설정 파일이 있으면 debug도 기본적으로 실제 Firebase 프로젝트를 사용합니다. 로컬 모드는 `-PfirebaseEmulators=true`로 명시합니다.

```bash
./gradlew assembleDebug
# 물리 기기에서도 Firebase에 직접 접속하므로 Python 서버나 adb reverse가 필요 없습니다.
```

`google-services.json` 대신 루트의 `firebase.local.properties.example`을 `firebase.local.properties`로 복사해 프로젝트 ID·모바일 SDK 앱 ID·Android API 키를 설정할 수도 있습니다. 서비스 계정 JSON, 관리자 개인 키, Firebase CLI 로그인 토큰을 앱이나 Git에 넣지 마세요. 실제 프로젝트 배포는 프로젝트 권한이 있는 사용자 계정으로 수행합니다.

## 로컬 에뮬레이터

Node 20 이상과 JDK 21을 권장합니다. 설정 파일이 없을 때 앱은 `demo-evening-deals` 프로젝트의 로컬 에뮬레이터를 사용합니다. 로컬 테스트는 실제 Firebase 요금이나 원격 프로젝트에 연결하지 않습니다.

```bash
npm ci
# 쓰기 제한이 있는 클라우드에서만 캐시 경로를 준비합니다.
source scripts/env.sh        # 이 클라우드의 JDK/Android SDK 활성화
source firebase/dev-env.sh
npm run firebase:start      # Auth 9099, Firestore 8085
```

별도 터미널에서:

```bash
source firebase/dev-env.sh
npm run firebase:seed
./gradlew assembleDebug -PfirebaseEmulators=true
```

로컬 전용 seed는 `demo-*` 프로젝트와 loopback 호스트만 허용하며 기존 계정·진행 중인 재고를 덮어쓰지 않습니다. 고객 `customer@demo.kr`, 운영자 `store@demo.kr`, 비밀번호 `Demo1234!`를 만듭니다. 시드는 마감된 과거 상품·주문을 보존하면서 새 날짜 샘플을 추가할 수 있습니다.

Android 에뮬레이터의 기본 접속 주소는 `10.0.2.2`입니다. USB 실기기에서 로컬 에뮬레이터를 쓰려면 다음과 같이 실행합니다.

```bash
adb reverse tcp:9099 tcp:9099
adb reverse tcp:8085 tcp:8085
./gradlew assembleDebug -PfirebaseEmulators=true -PfirebaseEmulatorHost=127.0.0.1
```

Firestore 에뮬레이터 원본 다운로드가 차단되는 클라우드에서는 `bash firebase/setup-cloud-emulator.sh`가 대안입니다. Google Cloud SDK 공식 Firestore 에뮬레이터 1.22.0 컴포넌트를 SHA-256으로 검증한 뒤, Firebase CLI의 로컬 바이너리 지정 기능을 사용합니다. 원본 JAR와 예상 체크섬은 수정하지 않습니다. `dev-env.sh`가 이 검증된 실행 경로를 선택합니다.

에뮬레이터 데이터는 기본적으로 메모리에만 유지됩니다. 보존하려면 Firebase CLI의 export/import 기능을 사용하며, 로컬 데이터 디렉터리를 Git에 추가하지 마세요. 실제 Firestore 데이터는 서비스에 저장되어 앱·서버 재시작과 무관하게 유지됩니다.

## 데이터와 신뢰 경계

| 경로 | 내용 / 쓰기 권한 |
|---|---|
| `users/{uid}` | 닉네임·이메일·약관. 본인 생성·조회, 운영 역할 필드 금지 |
| `merchants/{uid}` | 소유 매장. 콘솔/신뢰 Admin 환경만 작성, 본인만 조회 |
| `deals/{id}` | 공개 상품. 소유 매장 운영자만 등록·편집 |
| `deals/{id}/slots/{0..stock-1}` | 공개 재고 상태·임의 선점 ID·만료 시각. 이름·이메일·픽업 코드 없음 |
| `holds/{requestKey}` | 본인 선점, 수량 1~5, 슬롯 목록, 만료. 고객 외 조회 금지 |
| `orders/{holdId}` | 가격·구성·시간 스냅샷, 상태·버전·개인 픽업 토큰. 본인/소유 매장만 조회 |
| `pickupCodes/{8자리}` | 코드와 주문 연결. 본인/소유 매장만 단건 조회, 목록·수정 금지 |
| `operations/{uid}_confirm_{key}` | 확정 요청의 영구 중복 방지. 생성 후 불변 |
| `reviews/{orderId}` | 본인 수령 완료 거래당 1회 작성 |
| `users/{uid}/favorites` | 본인 찜 매장. 판매 종료 후에도 유지 |
| `users/{uid}/settings/notifications` | 본인 알림 그룹 설정 |
| `clocks/{uid}` | 본인이 `serverTimestamp`만 기록할 수 있는 거래 시각 보정 |

Firestore 규칙은 인덱스나 클라이언트 필터가 아니라 **최종 권한 경계**입니다. 수정한 APK·REST 호출도 같은 규칙을 통과해야 합니다. 인증 토큰은 Firebase Auth SDK가 관리하며 기존 Python용 토큰을 Firebase 인증으로 사용하지 않습니다.

### 선점·확정·취소

- 수량마다 하나의 슬롯을 사용합니다. 거래 시 선택 슬롯을 읽고 같은 트랜잭션에서 선점 문서와 슬롯들을 기록합니다. 마지막 슬롯의 경합은 한 고객만 커밋됩니다.
- `getAfter()` 규칙이 선점 문서, 실제 슬롯, 주문, 요청 키, 숫자 코드의 일치를 확인합니다. 선점만 만들기·금액 바꾸기·슬롯 일부만 바꾸기는 거절됩니다. 수량 5개의 확정도 Firestore 규칙 문서 접근 한도 안에서 테스트했습니다.
- 선점은 최대 5분과 픽업 종료 중 빠른 시점입니다. Android는 통신 여유를 두어 약 4분 55초로 요청합니다. 만료된 `held` 슬롯은 새 트랜잭션에서 바로 재확보할 수 있으므로 TTL/정리 서버가 필요 없습니다. 오래된 선점 문서 자체를 자동 삭제하지는 않습니다.
- 확정은 선점 ID당 주문 1건입니다. 확정 키는 불변 작업 문서로 묶으며 다른 선점에 재사용할 수 없습니다. 테스트 실패는 슬롯을 반환하고 주문을 생성하지 않습니다. 실제 PG나 외부 결제는 호출하지 않습니다.
- 취소는 **Firestore `request.time` < 픽업 시작 − 30분**에서만 가능하며 모든 슬롯 반환을 동시에 요구합니다.
- 수령 확정은 소유 매장의 운영자만 **시작 이상·종료 미만**에 대기 상태와 버전을 다시 검사해 1회 처리합니다. 고객은 완료 버튼이나 직접 쓰기로 수령 상태를 바꿀 수 없습니다.
- 종료 후 미수령 전환은 본인 내역 조회 시 수행하는 지연 처리입니다. 상시 백그라운드 작업을 실행하지 않으며, 미수령 재고를 자동 재판매하지 않습니다.
- 고객 기기의 시각을 바꿔도 최종 거래 경계는 Firestore 서버 규칙으로 판정합니다. 로그인 전 탐색과 목록의 표시 시각은 기기 기준의 참고값입니다.

### Spark에 맞춘 범위

- 상품당 **총수량 1~99**, 거래당 1~5개입니다. 총수량은 등록 후 고정합니다. 원가·가격은 정수 원 단위이며 최대 원가 1억원으로 제한해 앱 정수 범위를 보호합니다.
- 한 번이라도 선점된 상품은 구성·가격·픽업 시간을 영구 잠급니다. 모든 선점·예약이 사라졌음을 신뢰 서버에서 집계하지 않으므로, 변경이 필요하면 새 상품을 등록합니다. 판매 중지는 기존 주문을 보존하면서 신규 선점만 차단합니다.
- 상품 최대 20개, 최근 고객/매장 주문 30개, 찜 30개를 제한 조회합니다. 지역·카테고리 필터는 불러온 상품 목록에 적용합니다. 현재는 다음 페이지 UI를 제공하지 않습니다.
- 무한 실시간 리스너나 자동 반복 폴링을 사용하지 않습니다. 상품 목록은 슬롯 상태까지 조회하므로 재고 수에 비례해 읽기 비용이 늘어납니다. 초기 3개 상품(재고 5+8+3)은 목록 한 번에 약 19개의 문서 읽기가 필요합니다. 캐시·재시도·개인 내역 조회에는 추가 사용량이 생깁니다.
- **Cloud Storage 업로드를 사용하지 않습니다.** 음식 사진은 APK에 포함합니다.
- **Cloud Functions/예약 서버/FCM 발송을 사용하지 않습니다.** 앱 알림함은 본인이 조회할 수 있는 최근 거래의 현재 상태를 표시합니다. 독립된 이벤트 기록·읽음 동기화·미읽음 배지·시간 예약 푸시를 구현한 것으로 취급하지 않습니다. 알림 설정은 저장되지만 이 버전은 기기 푸시를 보내지 않습니다.
- 숫자 코드 조회는 소유 매장 인증으로 제한합니다. 별도 서버가 없으므로 기존 Python 버전의 `5회 실패/분` 같은 신뢰 서버 속도 제한은 제공하지 않습니다. Firebase 인증/사용량 제한과 소유권 규칙을 사용합니다. 카메라 QR 스캔은 아직 미구현입니다.
- 비밀번호 재설정 메일은 Firebase Auth로 요청하며 새 비밀번호 입력은 Firebase가 호스팅하는 보안 링크 화면을 사용합니다. 에뮬레이터에서는 실제 이메일이 발송되지 않습니다.
- Google·카카오·Apple 소셜 로그인은 이번 전환에서 추가하지 않았습니다.

Spark 무료 할당량을 넘으면 요청이 제한될 수 있습니다. 이 코드는 Blaze 전환이나 결제 계정 연결을 수행하지 않습니다. 실사용 전 콘솔의 사용량·프로젝트 요금제를 확인하고, 공개 서비스의 남용 방지와 App Check 적용을 별도로 검토하세요. 클라이언트가 가격·재고의 신뢰 서버 역할을 대신하는 구조는 아닙니다.

공식 기준 확인: [Firebase 요금제](https://firebase.google.com/pricing), [Firestore 무료 할당량과 한도](https://firebase.google.com/docs/firestore/quotas), [원자적 작업과 보안 규칙](https://firebase.google.com/docs/firestore/manage-data/transactions), [Storage 요금제 변경 안내](https://firebase.google.com/docs/storage/faqs-storage-changes-announced-sept-2024).

## 검증 명령

```bash
source firebase/dev-env.sh
npm run test:firebase
# 실제 Auth+Firestore 로컬 서비스 및 seed가 떠 있는 상태에서 Android SDK 연동 검증
./gradlew connectedDebugAndroidTest
./gradlew assembleDebug lintDebug
```

보안 규칙 테스트는 별도의 `demo-evening-rules` 프로젝트만 초기화합니다. Android 연동 테스트는 **`firebaseEmulators=true`일 때만 실행**하도록 차단하며, 실제 프로젝트를 대상으로 실행하지 않습니다. 테스트에서 생성한 로컬 주문·상품·후기는 에뮬레이터 데이터입니다.

실제 Spark 프로젝트 생성·인증 공급자 활성화·규칙/인덱스 배포·운영 권한 지정은 콘솔 접근이 필요한 별도 단계입니다. 로컬 통과를 원격 배포 완료로 표시하지 않습니다.

## 매장 계정 화면

운영자 권한이 확인되면 홈은 상품 관리·들어온 예약·마이 탭으로 전환됩니다. 상품 등록/수정/판매 중지와 매장 예약 최근 30건 조회, 고객 방문 시 코드 검증·수령 완료가 가능합니다. 예약은 상단 새로고침으로 갱신합니다.

실제 프로젝트의 `store@demo.kr`도 자동으로 운영자가 되지 않습니다. Authentication의 사용자 UID를 복사한 뒤 Firestore `merchants` 컬렉션에 문서 ID를 해당 UID로 만들고 문자열 필드 `store_id`를 `s1`로 지정하세요. 계정의 앱 회원가입을 완료하고 로그아웃·재로그인하면 매장 화면이 열립니다. 다른 매장에는 다른 store_id를 지정합니다. 이 권한 문서는 앱에서 작성할 수 없습니다.

상품 저장 전 상품명 2~50자, 구성 안내 10~500자, 알레르기 1~200자, 원가/할인가, 총수량 1~99개, 종료 시간이 시작 이후이면서 미래인지 검사합니다. 기존 상품의 총수량은 고정입니다. 유효한 입력도 권한 거절되는 경우 실제 프로젝트의 `merchants/{UID}.store_id`와 배포된 규칙을 확인해야 합니다. 사진 영역은 안내용으로 비활성화되어 있으며 서버 사정상 업로드를 지원하지 않습니다.

## 일반 사용자·업체 회원가입

가입 유형은 일반 사용자(`customer`)와 업체(`merchant`)입니다. `users/{UID}`의 `account_type`은 가입 신청 유형이며 운영 권한을 의미하지 않습니다. 업체 신청에는 `store_name`(2~100자)과 `store_address`(2~200자)가 필요합니다. 관리자에게만 보이는 별도 신청 목록은 구현하지 않았으며, Firebase 콘솔의 users 문서에서 업체 신청 정보를 확인합니다.

승인은 콘솔에서 `merchants/{UID}`에 문자열 `store_id`를 지정하는 방식입니다. 가입 유형을 조작해도 매장 권한을 스스로 얻을 수 없습니다. 기존 계정과 기존 승인 매장 권한은 유지됩니다. 신규 업체는 가입 후 승인 대기 안내를 보고, 승인 후 다시 로그인하면 매장 관리 화면이 열립니다. 업체 가입 전에 변경된 `firebase/firestore.rules`를 실제 프로젝트에 배포하세요.

고객 상품 목록은 기본적으로 가격 제한이 없습니다. 필터에서 가격 제한 없음을 해제하면 최대 100,000원 범위에서 상한을 선택할 수 있습니다. 지역·카테고리·검색 조건은 계속 적용됩니다.
