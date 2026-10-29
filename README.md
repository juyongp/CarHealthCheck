# 차량 건강검진 (Pleos Connect 앱)

차가 보내는 데이터로 교체·점검이 필요한 부품을 3D 투시 화면에 표시하는 Pleos Connect 앱이에요.
정차 중에는 3D 모델과 상세 정보를, 주행 중이거나 화면을 반으로 나눴을 때는 가장 급한 항목 한 장만 보여 줘요.

- **정비 계획**: 내 주행 패턴(하루 주행거리, 1 km당 공조·와이퍼 사용, 제동 습관)으로 부품마다 다음 교체 날짜를 예측하고,
  앞으로 90일 타임라인과 예상 비용을 보여 줘요. 가까운 시기의 정비는 한 번의 정비소 방문으로 묶어 제안해요.
- **정비소 진단서**: 다음 방문 때 할 일을 QR 코드로 보여 줘요. 휴대폰 카메라로 찍으면 진단 내용이 글자로 옮겨져서
  정비사에게 보여 주거나 메시지로 보낼 수 있어요. 차대번호나 위치는 담지 않아요.
- **차에 물어보기**: "차 상태 어때?", "다음 정비 언제야?", "장거리 가도 돼?"처럼 말로 물으면 화면과 같은 데이터로 답하고
  차량 음성으로 읽어 줘요(Pleos STT·TTS SDK, 차량 온디바이스 엔진). 주행 중에는 요약 카드의 **물어보기** 버튼으로,
  정차 중에는 3D 화면 위 **차에 물어보기**로 써요. 시끄럽거나 말하기 어려울 때는 질문 버튼을 눌러도 돼요.
- **충전 습관 코칭**: 충전량(SoC) 변화로 충전 횟수, 100% 가까이 채운 횟수, 10% 아래까지 쓴 횟수를 기록하고
  배터리 수명에 좋은 습관을 알려 줘요(고전압 배터리 항목). 앱이 꺼져 있던 동안 한 충전도 다음에 켤 때 충전량이 올라간 걸로 잡아요.
- **교체 기록**: 마지막 교체 시기를 입력하면 그날의 주행거리를 기준으로 남은 수명과 다음 시기를 다시 계산해요.
  - 와셔액·브레이크액은 차가 확인해요. 부족 경고가 켜졌다가 꺼지면 앱이 "차량 확인" 기록을 자동으로 남겨요
    (앱이 꺼져 있던 동안 채웠어도 다음에 켤 때 알아채요). 경고가 켜지기 전에 미리 채운 경우는 알 수 없어요.
  - 센서가 없는 부품은 "직접 입력"으로 구분해 정비 기록과 진단서에 표시해요.
  - 잘못 누르지 않도록: 기록 직후 7초 동안 **되돌리기**, 30일(와셔액은 7일) 안에 같은 부품을 또 기록하면 다시 묻기,
    정비 계획의 최근 정비 기록에서 **지우기**(두 번 눌러 확인). 지우면 바로 앞 기록으로 다시 계산해요.

## 1. 준비

- 최신 Android Studio (Android SDK Platform 35, 내장 JDK 17)
- Pleos Connect Emulator 2.1.0 이상
  1. Android Studio > Tools > SDK Manager > **SDK Update Sites** 탭에서 `+`
     - Name: `Pleos Connect System Image`
     - URL (Mac): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
  2. **SDK Platforms** 탭 > Show Package Details > API 34의 Pleos Connect system image 설치
  3. Tools > Device Manager > Create device > Form Factor `Automotive` > `Pleos Connect v2.1.0`
     - Advanced Settings: Default boot `Cold`, RAM `4 GB` / System image: `API 34`, `Pleos Connect v2.1.0 APIs`

## 2. 열고 실행하기

1. Android Studio > **Open** > `AndroidStudioProjects/CarHealthCheck`
2. Gradle 동기화를 기다려요. 처음에는 Pleos Vehicle SDK를 `nexus-playground.pleos.ai`에서 내려받아요.
3. Pleos Connect 에뮬레이터를 켜고 **Run ▶**

## 3. APK 만들기

- 시험용: **Build > Build App Bundle(s) / APK(s) > Build APK(s)**
  → `app/build/outputs/apk/debug/app-debug.apk`
- 제출용(서명된 APK): 터미널에서 `./gradlew assembleRelease`
  (또는 Android Studio의 Build Variants에서 `release`를 고르고 **Build APK(s)**)
  → `app/build/outputs/apk/release/app-release.apk`
  - 서명 키는 프로젝트 밖 `~/AndroidStudioProjects/CarHealthCheck-signing/` 폴더에 있어요
    (`carhealth-release.jks`와 비밀번호가 든 `keystore.properties`).
    `local.properties`의 `release.signing`이 이 파일을 가리켜서 자동으로 서명돼요.
  - 이 폴더는 꼭 따로 백업하세요. 잃어버리면 같은 앱으로 업데이트할 수 없어요. 제출물에는 넣지 마세요.
  - 서명 인증서 SHA-256: `70:C9:BC:58:2F:69:CE:F1:E3:6D:F7:BD:DD:15:CE:0E:8B:24:BF:6F:80:06:72:46:55:6A:CE:B8:45:0E:11:E9`

## 4. 시연 팁

- 차량에 연결되면 차에서 받은 값으로 화면이 바뀌어요. 에뮬레이터 값이 모두 정상이면
  화면 오른쪽 위 **예시 데이터로 보기**를 눌러 시연 시나리오(앞 브레이크 패드 교체 등)를 보여 줄 수 있어요.
- 음성 인식은 에뮬레이터의 마이크로도 동작해요(Pleos Connect 에뮬레이터를 오디오와 함께 켜야 해요. `-no-audio`로 켜면 아무 소리도 안 들어가요).
- 시연 순서 추천: 진단 스캔 → 앞 브레이크 패드(제동 습관 설명) → 점수 아래 **정비 계획** 카드
  → "한 번에 하세요" 묶음 제안 → **정비소에 보여 줄 진단서**(휴대폰으로 QR 찍기) → 에어컨 필터 **교체 기록** 입력 후 다음 시기가 바뀌는 것 보여 주기
  → 와셔액 **직접 보충 방법** > **다 채웠어요**(예시 데이터에서는 차의 경고가 꺼지는 것을 흉내 내서 "차량 확인"으로 자동 기록)
- 주행 상태 바꾸기 (Virtual Console)
  1. 터미널에서 `adb forward tcp:8088 tcp:8088`
  2. 브라우저에서 `http://127.0.0.1:8088/` 접속 → 속도나 기어를 바꾸면 앱이 주행 중 요약 카드로 바뀌어요.
- 차량 서비스가 없는 환경에서는 **주행 화면 미리보기** 버튼으로 주행 화면을 볼 수 있어요.

## 5. 구조

| 파일 | 하는 일 |
| --- | --- |
| `app/src/main/assets/web/index.html` | 화면 (3D 모델, 점검 목록, 상세 카드, 주행 중 카드) |
| `MainActivity.kt` | WebView와 자바스크립트 브리지, 음성 안내(Pleos TTS SDK, 차량 음성 엔진 사용) |
| `vehicle/VehicleReader.kt` | Vehicle SDK에서 값 읽기 (5초마다) |
| `vehicle/DrivingStateMonitor.kt` | 플랫폼의 주행 중 제한 신호(CarUxRestrictions) |
| `VoiceInput.kt` | 차에 물어보기: Pleos STT SDK로 질문 한 번 듣기(듣는 중, 부분 결과, 최종 결과, 시간 초과) |
| `vehicle/UsageStore.kt` | 공조·와이퍼 사용 시간, 제동 횟수, 앱이 지켜본 주행거리, 날짜별 주행거리·전비, 교체 기록 누적 |

SDK에서 읽는 값: 브레이크액 수위, 와셔액 부족 경고, 변속기 오일 수위, 출력 제한 경고, 타이어 저압 경고 기준값,
누적 주행거리(`getCurrentDistanceDriven`, km), 누적 전비와 충전량, 공조 전원과 팬 속도(차가 지원하는 좌석 영역으로),
와이퍼 상태, 브레이크 페달 상태.

SDK에 없는 값(패드 두께, 필터 상태)은 이렇게 추정해요.
- 앱이 켜져 있는 동안 1 km당 공조·와이퍼 사용 시간과 제동 횟수를 재요. 기록이 적을 때는 평균값과 섞어 써요.
- 교체 기록의 주행거리부터 지금까지 달린 거리에 곱해서, 앱을 켜지 않은 동안의 사용량도 계산에 넣어요.
- 에어컨 필터는 300시간 또는 1년, 와이퍼는 100시간 또는 1년, 타이어 위치 교환은 1만 km 또는 1년,
  브레이크 패드는 앞 7만 km·뒤 11만 km를 제동 습관(평균 1 km당 0.6회 대비)으로 나눈 거리가 기준이에요.
- 실제 타이어 공기압은 공개 API로 읽을 수 없어서(privileged 권한), 타이어 항목은 위치 교환 주기를 보여 줘요.

## 6. 제출 전 확인

- `app/build.gradle.kts`의 `applicationId`를 팀 고유 이름으로 바꾸기
- 앱 이름(`res/values/strings.xml`)과 아이콘
- 권한은 `AndroidManifest.xml`의 읽기 권한 5개(`CAR_SAFETY`, `CAR_DRIVE`, `CAR_ENERGY`, `CAR_CLIMATE`, `CAR_WINDOWS`,
  모두 normal), 음성 안내용 `pleos.car.permission.TTS_SERVICE`, 음성으로 묻기용 `pleos.car.permission.STT_SERVICE`를 선언했어요.
  음성 인식은 차량 엔진이 마이크를 쓰므로 `RECORD_AUDIO`는 필요 없어요. 이름은 Vehicle SDK의 `PleosPermission`과 같아요.
  Pleos TTS SDK가 `INTERNET` 권한을 자동으로 함께 넣어요. 승인되지 않은 권한을 선언하면 심사에서 떨어질 수 있으니 승인 목록과 비교해 보세요.
- `distractionOptimized`(주행 중 표시) 선언이 공모전·심사 정책에 맞는지 확인하기
- `versionCode` / `versionName`

## 7. 문제 해결

- 동기화 중 `Could not find ai.pleos.playground:Vehicle:2.3.2`
  → `gradle/libs.versions.toml`의 `vehicleSdk`를 `"2.3.1"`로 바꿔요.
- 화면에 "차량 연결 안 됨"이 보임
  → Pleos Connect 에뮬레이터에서 실행했는지 확인하세요. 일반 휴대폰 에뮬레이터에는 Vehicle Service가 없어요.
- 어떤 항목이 "데이터 없음"
  → Logcat에서 `CarHealth`로 필터링하면 SDK 호출 오류를 볼 수 있어요.

## 라이선스

3D 렌더링에 three.js r128(MIT), 진단서 QR 코드에 qrcode-generator 1.4.4(MIT)를 사용해요.
`app/src/main/assets/web/vendor/LICENSE-three.txt`, `LICENSE-qrcode.txt` 참고.
