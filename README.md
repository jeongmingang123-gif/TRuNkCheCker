# TRuNkCheCker

트렁크에 실은 짐과 살아있는 생물을 기억해 두었다가, 차에서 내릴 때 두고 내리지 않도록 알려주는 Pleos Connect 차량용 앱입니다.

## 주요 기능

- **트렁크·문 4개 실시간 감지**: 트렁크와 운전석·조수석·뒷좌석 좌우 문의 열림·닫힘을 차량 그림에 애니메이션으로 표시
- **짐 기록**: 8종 3D 아이콘 선택 또는 직접 입력, 기기 안에 저장되어 앱을 다시 켜도 유지
- **하차 리마인드**: 정차 중 운전석 문이 열리면 트렁크에 남은 짐을 안내
- **생물 우선 경고**: 반려동물·강아지·고양이 등 생물을 자동 인식해 싣는 순간 안전 안내, 하차 시 가장 먼저 닫을 수 없는 경고 표시
- **트렁크 속 확인·꺼내기**: 차량 그림의 트렁크를 누르면 짐 목록 확인 및 꺼내기
- **주행 흔들림 추정**: 차량 속도 변화로 급가속·급감속을 기록하고 하차 시 짐 쏠림 가능성 안내
- **주행 시작 안전 경고**: 주행 중 문·트렁크 열림 또는 안전벨트 미착용 시 경고

## APK 파일 위치

`apk/강정민_TrunkChecker_release.apk`

## 실행 방법

### 1. 에뮬레이터 준비
1. Android Studio에서 Pleos Connect Emulator v2.1.0 AVD를 **Cold boot**로 실행합니다.
2. 홈 화면이 완전히 표시될 때까지 기다립니다.

### 2. APK 설치 및 권한 부여
```bash
adb install -r apk/강정민_TrunkChecker_release.apk
adb shell pm grant io.github.jeongmingang.hdapp android.car.permission.CAR_SPEED
```
설치 후 에뮬레이터 하단 독 또는 앱 목록에서 **TrunkChecker**를 실행합니다.
(앱 첫 실행 시 속도 권한 요청 팝업이 뜨면 **허용**을 눌러도 됩니다.)

### 3. 차량 신호 조작 (Pleos Connect Virtual Console)
```bash
adb forward tcp:8088 tcp:8088
```
PC 브라우저에서 `http://127.0.0.1:8088/` 에 접속합니다.

| 확인할 기능 | Virtual Console 조작 |
| --- | --- |
| 트렁크·문 감지 | Emulation Controls → Convenience에서 트렁크·문 열기/닫기 |
| 하차 리마인드 | 짐을 기록한 뒤 기어 P, 속도 0 상태에서 운전석 문 열기 |
| 주행 흔들림 | 기어 D에서 ACCEL·BRAKE를 강하게 조작한 뒤 기어 P로 정차 |

### 4. 소스에서 빌드
1. Android Studio에서 프로젝트를 엽니다.
2. Gradle Sync 후 실행 대상으로 Pleos Connect Emulator를 선택하고 Run 합니다.

## 테스트 환경 및 필요한 설정

| 항목 | 내용 |
| --- | --- |
| 개발 도구 | Android Studio Rabbit 2 (2026.2.2 Patch 1) |
| 언어 | Kotlin |
| minSdk | 28 |
| Pleos SDK | Vehicle SDK 2.3.2 (`ai.pleos.playground:Vehicle:2.3.2`) |
| Maven 저장소 | `https://nexus-playground.pleos.ai/repository/maven-releases/` |
| 추가 설정 | `app/build.gradle.kts`에 `useLibrary("android.car")` |
| 네트워크·계정·서버 | 사용하지 않음 (데이터는 기기 내부 SharedPreferences에만 저장) |

### 사용 권한

| 권한 | 용도 |
| --- | --- |
| `pleos.car.permission.CAR_DOORS` | 트렁크·문 열림 상태 읽기 |
| `android.car.permission.CAR_SPEED` | 차량 속도 읽기 (주행 흔들림 추정, 주행 시작 판단) |
| `android.car.permission.READ_CAR_SEAT_BELTS` | 안전벨트 착용 여부 읽기 |

차량 제어 권한(`CONTROL_*`)과 비공개 권한(`PRIVATE_*`)은 사용하지 않습니다.

## Pleos Connect 에뮬레이터 버전 및 화면 규격

| 항목 | 내용 |
| --- | --- |
| 에뮬레이터 | Pleos Connect Emulator v2.1.0 |
| Android | 14 (API 34) |
| 화면 규격 | 2560 × 1440 (가로) |
| AVD 설정 | Automotive, Cold boot, RAM 4GB |

## 알려진 제한 사항

- **안전벨트**: 현재 에뮬레이터 환경에서는 착용 신호를 받지 못해 "신호 없음"으로 표시됩니다. 차량 권한이 승인되면 연동되도록 구현되어 있습니다.
- **주행 중 화면**: 주행 중에는 차량 시스템의 운전자 주의 분산 방지 정책에 따라 앱 화면이 가려집니다. 앱은 화면이 가려진 동안에도 차량 신호를 계속 기록하며, 주행 중 짐 추가·삭제는 막혀 있습니다.
- **급가속·급감속 기준값**: ±3.0 m/s²는 앱 자체 기준값이며 실차 검증이 필요합니다.
- **짐 인식**: 짐 목록은 사용자가 직접 기록한 것으로, 앱이 트렁크 안의 물건이나 생물을 스스로 감지하지는 않습니다.