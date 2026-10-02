# Hermit for Android

[English](README.md)

Hermit은 PC의 데스크톱, 앱, 게임을 Android 휴대폰·태블릿·TV로 스트리밍하는 GameStream 프로토콜
클라이언트입니다. 게임뿐 아니라 원격 데스크톱 작업에도 맞춰 만들었습니다. PC 화면을 손가락으로 바로
조작하고, 어떤 언어로든 글을 입력하고, 터치 키보드에 없는 단축키를 보내고, 스트리밍 중에도 설정을 바꿀 수
있습니다.

Hermit은 다음 프로젝트와 함께 쓰도록 만들어졌습니다.

- **[Shell](https://github.com/junopark00/hermit-shell)**: Windows PC용 호스트
- **[Hermit](https://github.com/junopark00/hermit)**: Windows 클라이언트
- **Hermit for Android**: 이 저장소

Sunshine, Apollo 같은 다른 GameStream 호환 호스트에도 접속할 수 있습니다. Shell의 확장 기능이 필요한
기능은 아래에 따로 표시했습니다.

<p align="center">
  <img src="docs/images/android-pc-list.png" alt="PC 목록" width="220">
  <img src="docs/images/android-apps.png" alt="호스트의 앱 목록" width="220">
  <img src="docs/images/android-settings.png" alt="설정 화면" width="220">
</p>
<p align="center">
  <img src="docs/images/android-keypad-editor.png" alt="가상 키패드 편집기" width="220">
  <img src="docs/images/android-key-picker.png" alt="조합키와 키 사이 간격을 고르는 키 선택 창" width="220">
</p>

## 주요 기능

- **직접 터치**(크롬 원격 데스크톱 방식): 탭하면 클릭, 끌면 스크롤, 길게 누른 뒤 끌면 선택·이동,
  두 손가락 탭은 우클릭.
- **트랙패드 모드**: 정확한 포인터 이동과 속도 조절.
- **텍스트 입력창**: 화면 키보드로 문장을 통째로 입력합니다. 한글 등 유니코드 문자를 PC의 입력 언어와
  상관없이 보냅니다. 단축키 줄에서 Esc, Tab, 방향키, Ctrl+C/V/Z, Alt+Tab, Win, Ctrl+Shift+Esc,
  Ctrl+Alt+Del을 보낼 수 있습니다(Ctrl+Alt+Del은 Shell 필요).
- **가상 키패드**: 화면 조이스틱이나 십자패드와 직접 지정하는 키. 조합키, 키 사이 간격을 둔 연속 입력,
  스트림 위에서 바로 편집, 터치 방식·화질·화면 방향 등을 바꾸는 빠른 메뉴를 제공합니다.
- **스트림 설정 패널**: 뒤로 가기를 누르면 스트림을 끝내지 않고 패널이 열립니다. 오버레이, 화면 방향,
  입력 방식은 바로 바뀌고, 비트레이트는 다시 연결하지 않고 바뀝니다(Shell). 해상도, 프레임 레이트,
  코덱, HDR은 짧게 다시 연결해 적용합니다.
- **비트레이트 자동 조절**: 프레임 손실과 왕복 지연에 맞춰 조절합니다(Shell).
- **화면에 맞는 해상도**: 16:9 해상도 외에 이 기기 화면 비율에 맞춘 720p, 1080p, 1440p, 2160p(4K를
  지원하는 경우) 해상도를 목록에서 고를 수 있습니다. 예를 들어 20:9 휴대폰에서는 1600×720, 3200×1440,
  16:10 태블릿에서는 1728×1080이 나오므로, 해상도를 직접 입력하지 않아도 화면을 꽉 채울 수 있습니다.
- **두 손가락 확대**: 이 기기에서 스트림 화면을 최대 500%까지 확대하고, 확대한 화면에서도 누른 자리
  그대로 조작합니다.
- **화면 방향**: 스트림 모양에 맞춘 자동 방향, 또는 가로·세로 고정.
- **성능 오버레이**: 표시할 지표를 고를 수 있고, 추정 전체 지연과 기기의 배터리·발열 상태도
  보여줍니다.
- **세션 요약**: 스트림이 끝나면 최근 세션과 비교한 요약을 보여줍니다.
- **클립보드 동기화**: 텍스트를 양방향으로 주고받고, Shell에서는 이미지도 주고받습니다. 파일은
  동기화하지 않습니다.
- **PC 끄기·다시 시작**: PC 목록에서 원격으로 끄거나 다시 시작합니다(Shell).
- **자동 재연결**: 네트워크 문제로 끊기면 다시 연결합니다.
- **한국어·영어** 화면, Windows판 Hermit과 같은 다크 테마.

모든 기능은 [기능 안내서](docs/guide.md)(영어)에 자세히 설명되어 있습니다.

## 호환성

| 기능 | Shell | 다른 GameStream 호스트(Sunshine, Apollo 등) |
|---|---|---|
| 스트리밍, 페어링, 터치, 트랙패드, 키패드, 텍스트 입력, 확대, 오버레이 | 지원 | 지원 |
| 다시 연결 없이 비트레이트 변경 | 지원 | 다시 연결해 적용 |
| 비트레이트 자동 조절 | 지원 | 미지원 |
| Ctrl+Alt+Del | 지원 | 미지원(Windows가 일반 입력으로는 무시함) |
| 클립보드 동기화: 텍스트 | 지원 | 같은 클립보드 확장을 제공하는 호스트(예: Apollo) |
| 클립보드 동기화: 이미지 | 지원 | 미지원 |
| 클립보드 동기화: 파일 | 미지원 | 미지원 |
| PC 끄기·다시 시작 | 지원 | 미지원 |

## 요구 사항

- Android 5.0(API 21) 이상의 휴대폰, 태블릿, 크롬북, Android TV.
- 일부 기능은 더 높은 버전이 필요합니다. 두 손가락 확대는 Android 7.0 이상, 텍스트 입력창이 키보드
  바로 위에 붙는 것은 Android 11 이상(그 전에는 화면 위쪽), 오류 보고에 네이티브 크래시와 "앱 응답
  없음"이 포함되는 것은 Android 11 이상, IBM Plex 글꼴은 Android 10 이상에서 적용됩니다.
- Shell 또는 다른 GameStream 호환 호스트가 실행 중인 PC.

## 설치

1. [GitHub Releases](https://github.com/junopark00/hermit-android/releases)에서 최신
   `Hermit-android-<버전>.apk`를 내려받습니다.
2. 기기에서 파일을 엽니다. 처음에는 APK를 연 앱(브라우저, 파일 관리자 등)에 "출처를 알 수 없는 앱
   설치"를 허용해야 합니다.
3. 같은 키로 서명된 업데이트는 기존 앱 위에 설치되며 페어링과 설정이 유지됩니다. 릴리스 APK는 프로젝트의
   릴리스 키로 서명되어 있고, `apksigner verify --print-certs Hermit-android-<버전>.apk`로 인증서를
   확인할 수 있습니다. 다른 키로 서명한 APK(직접 빌드한 APK 등)로는 설치된 릴리스를 업데이트할 수
   없습니다. 먼저 삭제해야 하며, 이때 페어링과 설정이 지워집니다.

Hermit은 고유한 앱 ID(`io.github.junopark00.hermit`)를 쓰므로 다른 GameStream 클라이언트와 함께
설치해도 서로 영향을 주지 않습니다.

## 처음 연결과 페어링

1. PC에서 호스트를 실행합니다(Shell은 해당 저장소의 README 참고).
2. Hermit을 엽니다. 같은 네트워크의 PC는 자동으로 나타납니다. 다른 네트워크의 PC는 **+**를 눌러
   IP 주소나 호스트 이름을 입력합니다.
3. PC를 누르면 PIN이 표시됩니다. 호스트의 페어링 페이지(Shell: 웹 UI → 페어링)에 PIN을 입력하거나,
   **Shell 페어링 페이지 열기**를 눌러 PIN이 입력된 페이지를 브라우저에서 엽니다(브라우저가 호스트의
   자체 서명 인증서를 경고하고 Shell 웹 UI 비밀번호를 묻는 것은 정상입니다). 페어링이 끝나면 창이
   닫힙니다.
4. PC를 다시 누르면 앱 목록이 나옵니다. 앱(예: Desktop)을 누르면 스트리밍이 시작됩니다.

스트리밍 중에는 **뒤로 가기**(또는 화면 가장자리의 손잡이)로 스트림 설정 패널을 엽니다. PC 목록의
**?** 버튼은 [기능 안내서](docs/guide.md)를 엽니다.

## 원격 접속

집 밖에서 스트리밍하려면 [Tailscale](https://tailscale.com)을 권장합니다. 호스트 PC와 이 기기(Google Play)에
설치하고 같은 tailnet에 로그인한 뒤, **+**를 눌러 PC를 Tailscale 주소(`100.x.y.z`)나 MagicDNS 이름으로
추가하세요(Tailscale로는 자동 검색이 되지 않습니다). 공유기 설정은 바꿀 필요가 없고, Shell이라면 페어링과
**Shell 페어링 페이지 열기**도 Tailscale로 그대로 쓸 수 있습니다. Tailscale은 Android의 VPN 자리를 쓰므로 다른
VPN 앱과 동시에 켤 수 없습니다. 다른 방법으로는 호스트 쪽 공유기의 UPnP나 포트 포워딩이 있으며, 이때는 집에서
먼저 페어링하세요. 세 가지 방법과 포트 목록은 Shell의
[원격 접속 안내](https://github.com/junopark00/hermit-shell/blob/main/docs/remote-access.md)(영문)에 있습니다.
Wake-on-LAN은 대체로 같은 네트워크에서만 동작합니다.

Shell이 처음이라면, Shell 저장소에 호스트 설치부터 페어링, 원격 접속까지 단계별로 안내하는 AI 에이전트용 스킬이
있습니다. [AI 에이전트와 함께 설정하기](https://github.com/junopark00/hermit-shell/blob/main/README.ko.md#ai-에이전트와-함께-설정하기)를
참고하세요.

## 소스에서 빌드

필요한 것:

- JDK 21
- Android SDK(platform 37)와 NDK 29.0.14206865(`app/build.gradle`에 적힌 버전). Android Studio의
  SDK Manager로 설치할 수 있습니다.
- Git(스트리밍 코어가 서브모듈입니다)

```sh
git clone --recursive https://github.com/junopark00/hermit-android.git
cd hermit-android
# 이미 클론했다면: git submodule update --init --recursive
./gradlew assembleNonRootRelease      # Windows: gradlew.bat assembleNonRootRelease
```

APK는 `app/build/outputs/apk/nonRoot/release/`에 만들어집니다. `assembleNonRootDebug`는 릴리스와
함께 설치되는 디버그 버전(`io.github.junopark00.hermit.debug`)을 만듭니다. 네이티브 코드가
최적화되지 않으므로 스트리밍보다는 디버깅용입니다.

### 서명

저장소 최상위에 `keystore.properties`가 있으면 릴리스 빌드에 서명합니다(git에서 제외된 파일이며,
이 파일과 키 저장소는 절대 커밋하지 마세요). 파일이 없으면 서명되지 않은 APK가 만들어집니다. 직접
만든 키를 쓰세요.

```properties
storeFile=C:/path/to/your-release-key.jks
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

키는 JDK의 `keytool`로 만들 수 있습니다.

```sh
keytool -genkeypair -keystore your-release-key.jks -storetype PKCS12 -alias your-key-alias \
  -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Your Name"
```

상대 경로로 쓴 `storeFile`은 `app/` 폴더를 기준으로 찾으므로 절대 경로가 가장 간단합니다. 키
저장소와 비밀번호는 꼭 백업하세요. Android는 같은 키로 서명한 업데이트만 받아들입니다.

### Windows 도우미 스크립트

`hermit/build-android.ps1`은 JDK와 Android SDK를 찾고(`JAVA_HOME`·`ANDROID_HOME` 우선, 그다음 흔한
설치 위치), 소스 점검을 실행한 뒤 릴리스 APK를 빌드해 `build/hermit-android/`에 복사합니다.
`keystore.properties`가 없으면 `./signing`(git에서 제외)에 새 키를 만듭니다. 이 폴더를 백업하세요.

```powershell
.\hermit\build-android.ps1                  # 릴리스 APK
.\hermit\build-android.ps1 -Install         # 빌드 후 adb로 설치
.\hermit\build-android.ps1 -SmokeTest       # 설치하고 실행해 앱이 계속 동작하는지 확인
.\hermit\build-android.ps1 -DebugBuild
.\hermit\build-android.ps1 -SignerName "Your Name"   # 새로 만드는 키의 인증서 이름
```

### 점검

변경을 보내기 전에 소스 점검을 실행하세요. 컴파일은 되지만 실행 중에 비정상 종료되는 실수를 잡아
줍니다.

```sh
python hermit/check-view-types.py
python hermit/check-strings.py
python hermit/check-prefs.py
```

`root` 플레이버(Android 7.1 이하의 루팅된 기기용)는 원본 프로젝트에서 이어받았지만 배포하지 않습니다.

## 개인정보와 네트워크

Hermit에는 계정, 분석 도구, 원격 수집(telemetry)이 없습니다. 접속하는 곳은 다음과 같습니다.

- **추가했거나 로컬 네트워크에서 찾은 호스트**(mDNS 검색): 페어링, 스트리밍, 앱 목록과 앱 이미지,
  클립보드 동기화, Wake-on-LAN 패킷.
- **공개 STUN 서버 `stun.cloudflare.com`**(UDP 3478): 로컬 네트워크에서 PC를 찾았을 때. STUN은 이
  네트워크의 공인 주소만 알려 주며, Hermit은 나중에 외부에서 접속할 수 있도록 이 주소를 PC 정보와 함께
  저장합니다. 사용자나 호스트에 관한 정보는 보내지 않습니다.
- **GitHub**: **?** 버튼으로 기능 안내서를 열 때만(브라우저에서, 또는 Android TV나 브라우저가 없는
  기기에서는 Hermit에 내장된 뷰어에서).

오류 보고는 기기에 저장되며, 오류 창에서 직접 복사하거나 공유할 때만 기기 밖으로 나갑니다.

## 기여와 보안

버그 신고와 변경 제안은 [CONTRIBUTING.md](CONTRIBUTING.md), 보안 취약점의 비공개 신고는
[SECURITY.md](SECURITY.md)를 참고하세요.

## 라이선스와 감사의 말

Hermit for Android는 [GNU General Public License v3.0](LICENSE.txt)으로 배포됩니다.

Cameron Gutman, Diego Waxemberg와 Moonlight 기여자들이 만든
[Moonlight for Android](https://github.com/moonlight-stream/moonlight-android)를 수정한 버전이며,
스트리밍은 [moonlight-common-c](https://github.com/moonlight-stream/moonlight-common-c)로 합니다.
Hermit을 가능하게 해 준 Moonlight 프로젝트에 깊이 감사드립니다. 서드파티 구성 요소와 라이선스는
[NOTICE](NOTICE)에 있습니다.

## 고지

Hermit은 독립 프로젝트이며 NVIDIA 또는 Moonlight 프로젝트와 제휴하거나 그들의 보증·후원을 받지
않습니다. GameStream과 NVIDIA는 NVIDIA Corporation의 상표입니다. 그 밖의 상표는 각 소유자의
것입니다.
