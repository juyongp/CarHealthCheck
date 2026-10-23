# 차량 건강검진 (Pleos Connect 앱)

차가 보내는 데이터로 교체·점검이 필요한 부품을 3D 투시 화면에 표시하는 Pleos Connect 앱이에요.
정차 중에는 3D 모델과 상세 정보를, 주행 중이거나 화면을 반으로 나눴을 때는 가장 급한 항목 한 장만 보여 줘요.

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
| `vehicle/UsageStore.kt` | 공조·와이퍼 사용 시간, 제동 횟수, 전비 기록 누적 |

SDK에서 읽는 값: 브레이크액 수위, 와셔액 부족 경고, 변속기 오일 수위, 바퀴별 저압 경고, 누적 주행거리,
누적 전비와 충전량, 공조 전원과 팬 속도, 와이퍼 상태, 브레이크 페달 상태.
SDK에 없는 값(패드 두께, 필터 상태)은 사용 시간과 제동 횟수로 추정해요.

## 6. 제출 전 확인

- `app/build.gradle.kts`의 `applicationId`를 팀 고유 이름으로 바꾸기
- 앱 이름(`res/values/strings.xml`)과 아이콘
- 권한은 `AndroidManifest.xml`의 읽기 권한 7개와 음성 안내용 `pleos.car.permission.TTS_SERVICE`를 선언했어요.
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

3D 렌더링에 three.js r128(MIT)을 사용해요. `app/src/main/assets/web/vendor/LICENSE-three.txt` 참고.
