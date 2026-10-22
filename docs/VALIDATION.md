# 검증 기록

기존 데모는 2026-10-21, Firebase Spark 전환은 2026-10-22 한국 시각 기준 작업 기록입니다.

패키지명을 `com.portfolio.eveningdeals`로 변경한 뒤 APK·Android 테스트 APK 빌드와 Lint, Android 9 Firebase 연동 시나리오 1개를 다시 통과했습니다.

## Firebase Spark 전환 검증 (2026-10-22)

현재 기본 백엔드는 Firebase Auth + Firestore입니다. 아래 기존 Python 검증 기록과 구분합니다.

- Firestore 보안 규칙: **14개 통과, 실패/건너뜀 0개**. 실제 Firestore 에뮬레이터에서 검증했으며 권한 거절을 기대하는 공격 요청도 포함합니다.
- Android SDK 연동: **1개 시나리오 통과, 실패/건너뜀 0개**, Android 9(API 28). 로그인 → 찜 저장/삭제 → 수량 선점 → 확정 및 동일 키 재요청 → 취소·재고 반환 → 테스트 결제 실패·선점 해제 → 매장 계정 상품 등록 → 고객 예약 → 매장 코드 조회·수령 확정/재시도 → 고객 후기·알림 설정 저장 → 로그아웃을 실행했습니다.
- `assembleDebug assembleDebugAndroidTest lintDebug`: 성공, Lint 오류 0개. 권장 사항 경고는 유지했습니다.
- 로컬용 APK 메타데이터: [firebase-build-result.json](firebase-build-result.json). 실제 프로젝트 설정 파일은 포함하지 않았습니다.
- 실제 Android 결과 요약: [firebase-android-test-result.json](firebase-android-test-result.json).
- 전체 설치 스크립트 재실행 성공: Node 22·SDK 준비, APK/Lint, 보안 규칙 14개, 기존 Python 회귀 테스트 12개를 확인했습니다.
- Auth 9099·Firestore 8085 시작, seed 재실행 보존, 규칙 보호된 비회원 상품 조회 및 이메일 로그인 smoke 검증을 확인했습니다.
- Firestore 에뮬레이터 원본 호스트 다운로드가 차단되어 Google Cloud SDK 공식 1.22.0 컴포넌트를 공식 SHA-256으로 검증해 사용했습니다. 원본 JAR/예상 체크섬을 수정하거나 TLS 검증을 끄지 않았습니다.

**실제 Spark 프로젝트에는 아직 연결·배포하지 않았습니다.** 프로젝트 설정 파일, 이메일 로그인 활성화, Firestore 규칙/인덱스 배포, 운영자 권한 지정이 필요합니다. 에뮬레이터는 프로덕션의 과금·할당량·인덱스 생성 상태를 검증하지 않습니다. 설정과 Spark에서 달라진 범위는 [FIREBASE_SPARK.md](FIREBASE_SPARK.md)를 참고하세요.

## 기존 Python 데모 서버

`cd server && python3 -m unittest -v` : **12개 통과**.

- 마지막 재고 1개의 동시 선점: 1건 성공, 나머지 재고 부족
- 동일 요청 재시도는 동일 주문, 본문 변경은 키 재사용 오류
- 선점 만료 거절, 결제 실패 후 수량 반환
- 고객 소유권·운영자 소유 매장·비회원 보호 API 검사
- 검증은 읽기 전용, 수령 확정은 1회, 완료 거래 후기 중복 거절
- 취소 마감·미수령, 숫자 코드 실패 제한
- 고객 가입 시 운영자 역할 지정 무시
- 서버 재초기화 후 주문 보존, 예약된 상품 변경 제한
- 날짜가 지난 샘플 대신 새 날짜 상품 추가, 과거 주문 유지
- 판매 종료 후에도 찜한 매장 유지
- 실제 HTTP 서버에서 게스트 조회 → 로그인 → 선점 → 예약 → 같은 키 재시도 → 취소 → 재고 반환, 비회원 내역 401

현재 실행 인스턴스에서 `scripts/start-server.sh`의 시작 및 중복 실행을 확인했고, `/health`와 `/deals` JSON 응답을 검증했습니다. 서버는 기본 loopback 8080 포트입니다.

## Android

- `./gradlew --no-daemon assembleDebug lintDebug`: **BUILD SUCCESSFUL**.
- Lint: **오류 0, 경고 9**. 사진 리소스 폴더 관련 권장 사항 3개와 KTX 문법 권장 사항 6개입니다. 검사·assertion을 비활성화하지 않았습니다.
- `bash scripts/cloud-setup.sh`: 재실행 성공. SDK 확인 → APK/Lint → 서버 테스트 12개까지 완료했습니다.
- `apksigner verify --verbose`: APK Signature Scheme v2 검증 성공, 서명자 1명.
- 산출물: `/workspace/artifacts/evening-deals-legacy-debug.apk` (18,811,781 bytes).
- SHA-256: `5342629192126dca5b398c1ac205f29e1cde73059194b0c2ebb5bcffd804f926`.

클라우드에 KVM이 없어 Android 30 x86_64 에뮬레이터를 소프트웨어 모드로 실행했습니다. 첫 부팅 및 설치가 느리므로 실행 검증은 빌드 검증과 별도로 기록합니다.

## 외부 연동

실결제는 없습니다. OAuth 공급자 로그인·메일 발송·FCM·카메라 스캔은 미구현/미설정이며 검증 통과로 계산하지 않습니다. 설정이 필요한 기능을 누르면 안내를 표시합니다. 앱의 샘플 음식 사진은 APK에 포함됩니다.


### 에뮬레이터 실행: Android 9에서 기본 화면 확인

Android 9(API 28) x86 에뮬레이터에 동일한 APK를 설치해 성공 응답을 확인했습니다. 실제 서버 상품 조회와 발견 → 상품 상세 → 수량 선택 화면 이동을 확인하고 720 × 1520px 원본 스크린샷 4장을 저장했습니다.

- [비회원 홈](screenshots/home.png)
- [상품 목록: 발견 탭 스크롤 상태](screenshots/discover.png)
- [상품 상세](screenshots/detail.png)
- [로그인 전 수량 선택·합계 미리보기](screenshots/quantity.png)

앱 코드 변경 없이 기존 APK를 사용했습니다. 로그인·예약 확정·매장 수령 전체 UI 흐름과 접근성 검수는 추가로 필요합니다.

앞서 사용했던 Android 30 에뮬레이터는 시스템 프로세스의 `Lost network stack` 오류로 설치가 실패했습니다. 해당 환경 오류 기록은 `emulator-system-error.txt`에 보존했으며, 이후 Android 9 에뮬레이터로 전환해 위 범위를 확인했습니다.

환경 설정 초안에는 전체 `install_script`와 `start_skill`, `images.unsplash.com` 허용 항목을 저장했습니다. 현재 인스턴스에서 설치/시작 스크립트를 확인했으며, 환경 게시 및 새 작업 복원은 실행하지 않았습니다.

매장 전용 홈(상품 관리·들어온 예약·마이)을 추가했습니다. Android Firebase 연동 테스트에서 새 고객 예약이 매장 예약 목록에 포함되고 목록의 모든 항목이 해당 매장 소유인지 확인했습니다. APK 빌드·Lint·Android 연동 1개 및 기존 Python 12개 테스트를 통과했습니다.

상품 입력 검증과 비활성 사진 영역을 추가한 뒤 Android 테스트 2개(입력 거절 안내·Firebase 상품 등록/예약/수령)를 통과했습니다. 실제 Firebase 프로젝트의 거절 원인은 해당 입력과 배포 설정 확인이 추가로 필요합니다.

일반/업체 회원가입 구분과 가격 제한 없음 필터를 추가했습니다. Firestore 보안 규칙 17개, Android 테스트 3개(기존 거래 시나리오·상품 입력·고객 및 업체 가입)를 통과했습니다. 업체 가입 유형과 매장명/주소가 저장되지만 승인 전 매장 관리 권한은 부여되지 않음을 검증했습니다. 공유 Firebase SDK를 여러 어댑터가 사용할 때 에뮬레이터 설정이 중복되지 않도록 수정했습니다. 실제 프로젝트에는 새 규칙 배포가 필요합니다.
