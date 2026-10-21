# 검증 기록

2026-10-21 한국 시각 기준 작업 기록입니다.

## 서버

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
- 산출물: `/workspace/artifacts/evening-deals-debug.apk` (18,811,781 bytes).
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
