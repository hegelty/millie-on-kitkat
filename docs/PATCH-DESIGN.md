# 수정 원리와 적용 범위

## 대상과 목적

밀리 e-ink `2.1.0.0`과 `2.4.0.0` (`kr.co.millie.eink`)을 Android 4.4 / ARMv7 환경에서 사용하기 위한 호환 패치입니다. 구형 Android의 TLS 구현과 앱 초기화·재서명 과정에서 발생하는 실행 문제를 보완합니다.

로그인 인증서와 호스트 이름 검증은 유지합니다. 구독 상태, 계정 인증, 도서 이용 권한을 변경하는 코드는 추가하지 않습니다. 설치 및 이용 조건은 [README](../README.md)를 참고하세요.

## TLS 초기화

`ApplicationMain.attachBaseContext`에서 MultiDex 설치가 끝난 뒤 `TlsInstaller.install()`을 호출합니다. OkHttp가 통신 환경을 선택하기 전에 Conscrypt 2.5.2를 보안 공급자로 등록해, 시스템 TLS 구현을 직접 바꾸지 않고 앱 내부의 통신 호환성을 보완합니다.

초기화 코드는 [TlsInstaller.java](../revanced/runtime/TlsInstaller.java)에 있습니다. 2.1에서는 Conscrypt 클래스를 기존 `classes5.dex`에 병합하고 외부 DEX 다섯 개를 유지합니다. 2.4에서는 `ApplicationMain.multiDex()` 직후에 TLS를 초기화하며 Conscrypt와 초기화 코드를 외부 `classes.dex`에 병합합니다. 두 버전 모두 ARMv7용 `libconscrypt_jni.so`를 추가하고 Android 4.4에서 읽을 수 있는 DEX 035 형식을 사용합니다.

## 2.4의 KitKat 리소스 검색

2.4는 앱 코드를 별도 `DexClassLoader`로 읽습니다. KitKat에서 이 로더의 부모는 부트 클래스 로더이며, APK를 가리키는 `PathClassLoader`는 자식입니다. 따라서 앱 코드가 자신의 클래스 로더로 APK 내부 Kotlin 메타데이터를 요청하면 파일이 존재해도 찾지 못합니다. EPUB 뷰어 초기화에서 `Built-in class kotlin.Any is not found` 오류를 재현했습니다.

0.2.1의 [ApkResourceInstaller.java](../revanced/runtime/ApkResourceInstaller.java)는 API 21 미만에서만 이 검색 경로를 보완합니다. Kotlin 리소스가 이미 보이면 아무것도 바꾸지 않습니다. 리소스가 보이지 않으면 설치 APK의 `sourceDir`을 가리키는 `URLClassLoader`를 별도 DEX 로더와 기존 부모 사이에 넣습니다. 이 로더는 APK ZIP의 리소스를 제공하며 APK의 DEX를 새로 로드하지 않습니다. 연결 전후에 Kotlin 메타데이터가 열리는지 확인하고, 실패하면 `MillieResources` 로그를 남깁니다.

원본 Kotlin 메타데이터 일곱 개도 입력 해시 검사에 포함합니다. 계정·구독·도서 인증 경로에는 변경을 가하지 않습니다. 이 수정은 재현한 뷰어 초기화 오류에 대한 보완이며, 실기기의 모든 도서 열기·독서 동작을 보장하지 않습니다.

## 패치 선택과 적용 순서

0.2.4부터 **Millie e-ink KitKat TLS**와 **Millie e-ink EPUB touch**를 별도 항목으로 제공합니다. KitKat TLS는 기본 선택이며 기존 실행 호환성 수정만 적용합니다. EPUB touch는 기본적으로 선택하지 않으며 2.4.0.0만 지원합니다.

EPUB touch는 KitKat TLS에 의존합니다. ReVanced가 호환성 패치를 먼저 적용한 뒤 터치 패치를 실행하므로, 사용자가 나열한 순서에 영향을 받지 않습니다. 터치 패치는 `classes.jet`과 `classes3.jet`만 수정하며, 각 델타가 기대하는 입력 해시를 검사합니다. 두 패치를 함께 적용한 결과는 통합 번들 0.2.3의 수정 내용과 같습니다.

## 2.4의 EPUB 터치 처리

0.2.2는 `ContentEvent(String)`이 이벤트마다 새 Jackson `ObjectMapper`를 만들던 호출을 [ContentEventMapper.java](../revanced/runtime/ContentEventMapper.java)의 지연 초기화·재사용 호출로 바꿉니다. 기존 Kotlin 모듈, `TypeReference`, `readValue` 호출과 예외 처리는 유지합니다. 공유 변환기는 생성 후 설정을 변경하지 않으며, getter는 동기화합니다. 이는 [Jackson의 변환기 재사용 조건](https://fasterxml.github.io/jackson-databind/javadoc/2.8/com/fasterxml/jackson/databind/ObjectMapper.html)을 따릅니다.

터치 이벤트의 시작·종료·탭은 JavaScript 브리지에서 메인 스레드로 전달된 뒤 이 생성자를 사용합니다. 물리 버튼의 페이지 이동은 이 좌표 해석을 거치지 않습니다. 0.2.2의 변경은 좌표 해석 비용을 줄이지만, 카르타 G에서 보고된 수초의 터치 지연은 해결하지 못했습니다.

0.2.3은 [EpubTouchCompat.java](../revanced/runtime/EpubTouchCompat.java)를 `classes3.jet` 내부 DEX에 추가하고, `EPubWebView.loadWebViewBasePage`에서 뷰어 JavaScript를 읽은 직후 적용합니다. 기본 라이브러리와 별도로 내려받은 라이브러리 모두 같은 경로를 거칩니다. API 19에서만, 확인된 기본 1.0.45와 다운로드 1.5.7 라이브러리의 임시 입력창 생성·focus·blur·제거 함수 본문을 빈 본문으로 바꿉니다. 정확한 코드가 한 번만 발견되는 경우에 적용하며, 미확인 코드나 모호한 입력은 그대로 둡니다.

실기기에서 이 포커스 작업은 한 번의 탭에 터치 및 마우스 이벤트로 두 번 실행됐으며, 각 호출에 약 0.8~0.9초가 걸렸습니다. 임시 입력창 처리를 생략한 시험본에서 반복 터치의 지연이 줄었고, 사용자는 물리 버튼과 비슷한 속도 및 글자 선택·해제의 정상 동작을 확인했습니다. 터치 방향·선택 알고리즘·도서 인증·화면 갱신 설정은 변경하지 않습니다. 이 수정은 2.1에는 적용하지 않으며, 후속 뷰어의 코드가 달라지면 적용되지 않을 수 있습니다.

## 시작 및 재서명 호환성

앱 시작을 막는 업데이트 안내 분기 한 곳을 수정합니다. 패치 후에는 원본과 다른 인증서로 서명되므로, 앱 내부 ARM 모듈에서 재서명으로 인한 실행 중단과 관련된 두 지점을 함께 조정합니다.

| 대상 | 2.1.0.0 | 2.4.0.0 |
|---|---|---|
| `classes.dex` | TLS 초기화 호출 및 코드 추가 | TLS 초기화 및 Conscrypt 클래스 병합 |
| 업데이트 안내 분기 | `classes5.dex` 수정, Conscrypt도 병합 | `assets/classes3.jet` 내부 DEX 수정 |
| `assets/m7a` | 재서명 호환성 두 지점 및 DEX 무결성 기록 수정 | 동일한 두 지점 및 외부 DEX 무결성 기록 수정 |
| `assets/agconfig` | `110`과 `141` 기록 갱신 | `110` 보존, `141` 갱신 |
| `lib/armeabi-v7a/libconscrypt_jni.so` | Conscrypt 네이티브 라이브러리 추가 | 동일 |

네이티브 수정은 해당 버전의 두 명령 위치에 한정하며, 원래 바이트가 예상과 다르면 패치를 중단합니다. 설치 경로에 관한 경고가 표시될 수 있으며, 경고 표시 자체를 제거하는 패치는 포함하지 않습니다.

두 버전 모두 `AndroidManifest.xml`과 `resources.arsc`를 보존합니다. 2.1은 `classes2.dex`부터 `classes4.dex`까지 보존합니다. 2.4는 외부 DEX 하나와 암호화된 DEX 자산 세 개라는 배치를 유지하며, `classes2.jet`과 `.zip` 동반 파일을 보존합니다. `classes.jet`에는 좌표 변환기 재사용을, `classes3.jet`에는 업데이트 안내 및 EPUB 터치 수정을 적용합니다.

2.4의 `classes3.jet`은 AES 컨테이너 안에 단일 `classes.dex`를 담은 ZIP입니다. 내부 DEX의 업데이트 안내 분기를 기존 계정 확인 경로로 연결하며, 도서나 계정 인증 코드를 변경하지 않습니다. 내부 ZIP 항목, DEX 길이·원본 해시·수정 결과 해시를 검사하고 다시 포장합니다.

## 무결성 기록과 서명

변경한 외부 DEX의 실제 CRC32 값을 `m7a`의 `gc1`에 반영합니다. DEX가 없는 자리는 기존 자리표시자를 유지합니다. 2.1에서는 `agconfig`의 `110`도 갱신합니다. 2.4의 `110`은 외부 DEX의 CRC 목록과 일치하지 않으므로 원본 값을 그대로 보존합니다. 자산은 기존 AES 컨테이너 형식을 유지해 다시 인코딩하고, 갱신한 `m7a`의 SHA-256을 `agconfig`의 `141` 항목에 기록합니다.

이렇게 하면 ZIP에 기록된 CRC와 실제 파일 내용이 일치하므로 Manager에서 패키징과 서명을 마칠 수 있습니다. 서명 후 APK를 다시 수정하는 후처리는 필요하지 않습니다. 수정 범위와 검증은 [MillieCore.kt](../revanced/src/main/kotlin/me/crema/patches/MillieCore.kt)에 구현돼 있습니다.

## ReVanced 적용 방식

[MilliePatch.kt](../revanced/src/main/kotlin/me/crema/patches/MilliePatch.kt)는 원시 리소스 패치로 동작합니다. 입력 파일의 SHA-256을 확인한 뒤 DEX 차이 파일을 적용하고 암호화 자산을 갱신합니다. 차이 파일에도 원본과 결과의 길이·SHA-256이 들어 있어, 다른 입력이나 손상된 결과를 거부합니다.

ReVanced가 패키징 중 DEX를 다시 기록할 수 있으므로, 변경한 DEX를 원시 리소스 출력으로 기록해 리소스 반영 단계에서 의도한 내용을 복원합니다. 수정하지 않은 파일은 원본에서 유지합니다. 다른 바이트코드 패치와 함께 적용하면 그 변경이 보존되지 않을 수 있으므로 **이 번들의 KitKat TLS와 EPUB touch만 함께 적용해야 합니다.**

Patcher 22 계열의 원시 리소스 생성자 동작에 맞춰 `ResourcePatchBuilder(PatchType.RAW_RESOURCE)`를 명시합니다. 내부 API에 의존하므로 Manager 또는 Patcher 버전을 바꿀 때는 번들 로딩, DEX 배치와 최종 서명을 다시 검증해야 합니다.

## 배포 범위와 한계

배포 대상은 패치 소스, 원본이 있어야 적용할 수 있는 차이 데이터, `.rvp` 번들 및 문서입니다. 원본·수정 APK, 원본 DEX 전체, 서명 키는 배포하지 않습니다. 차이 데이터는 변경에 필요한 바이트를 포함하므로 원본 앱에 대한 이용 권한을 대신하지 않습니다. Conscrypt의 [라이선스](../revanced/licenses/Conscrypt-LICENSE)와 [고지](../revanced/licenses/Conscrypt-NOTICE)는 별도로 적용됩니다.

매니페스트와 수정에 관련된 원본 파일의 해시로 버전을 구분합니다. 내부 버전이 2.4.0.0인 공식 서버의 `2.5.0.0` 파일도 원본 해시가 달라 거부합니다. 정확한 입력 버전만 지원하며, WebView의 TLS 구현이나 운영체제 전체의 보안을 개선하는 패치는 아닙니다. 앱과 서버의 후속 변경에 대한 호환성도 보장하지 않습니다. 현재 확인된 범위와 알려진 문제는 [검증 결과](../revanced/VERIFICATION.md)에 정리했습니다.
