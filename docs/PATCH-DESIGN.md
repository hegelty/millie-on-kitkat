# 수정 원리와 적용 범위

## 대상과 목적

밀리 e-ink `2.1.0.0` (`kr.co.millie.eink`)을 Android 4.4 / ARMv7 환경에서 사용하기 위한 호환 패치입니다. 구형 Android의 TLS 구현과 앱 초기화·재서명 과정에서 발생하는 실행 문제를 보완합니다.

로그인 인증서와 호스트 이름 검증은 유지합니다. 구독 상태, 계정 인증, 도서 이용 권한을 변경하는 코드는 추가하지 않습니다. 설치 및 이용 조건은 [README](../README.md)를 참고하세요.

## TLS 초기화

`ApplicationMain.attachBaseContext`에서 MultiDex 설치가 끝난 뒤 `TlsInstaller.install()`을 호출합니다. OkHttp가 통신 환경을 선택하기 전에 Conscrypt 2.5.2를 보안 공급자로 등록해, 시스템 TLS 구현을 직접 바꾸지 않고 앱 내부의 통신 호환성을 보완합니다.

초기화 코드는 [TlsInstaller.java](../revanced/runtime/TlsInstaller.java)에 있습니다. Conscrypt 클래스는 기존 `classes5.dex`에 병합하고 ARMv7용 `libconscrypt_jni.so`를 추가합니다. 원래의 DEX 다섯 개를 유지하며, Android 4.4에서 읽을 수 있는 DEX 035 형식을 사용합니다.

## 시작 및 재서명 호환성

앱 시작을 막는 업데이트 안내 분기 한 곳을 수정합니다. 패치 후에는 원본과 다른 인증서로 서명되므로, 앱 내부 ARM 모듈에서 재서명으로 인한 실행 중단과 관련된 두 지점을 함께 조정합니다.

| 대상 | 수정 내용 |
|---|---|
| `classes.dex` | TLS 초기화 호출 및 초기화 코드 추가 |
| `classes5.dex` | 업데이트 안내 분기 수정 및 Conscrypt 클래스 병합 |
| `assets/m7a` | 재서명 검사 결과 처리, 감지 후 종료 호출, DEX 무결성 기록 수정 |
| `assets/agconfig` | 변경된 DEX와 ARM 모듈의 무결성 기록 갱신 |
| `lib/armeabi-v7a/libconscrypt_jni.so` | Conscrypt 네이티브 라이브러리 추가 |

네이티브 수정은 해당 버전의 두 명령 위치에 한정하며, 원래 바이트가 예상과 다르면 패치를 중단합니다. 설치 경로에 관한 경고가 표시될 수 있으며, 경고 표시 자체를 제거하는 패치는 포함하지 않습니다.

`AndroidManifest.xml`, `resources.arsc`, `classes2.dex`부터 `classes4.dex`까지의 내용은 보존합니다.

## 무결성 기록과 서명

변경한 DEX의 실제 CRC32 값을 `m7a`의 `gc1`과 `agconfig`의 `110` 항목에 반영합니다. 자산은 기존 AES 컨테이너 형식을 유지해 다시 인코딩하고, 갱신한 `m7a`의 SHA-256을 `agconfig`의 `141` 항목에 기록합니다.

이렇게 하면 ZIP에 기록된 CRC와 실제 파일 내용이 일치하므로 Manager에서 패키징과 서명을 마칠 수 있습니다. 서명 후 APK를 다시 수정하는 후처리는 필요하지 않습니다. 수정 범위와 검증은 [MillieCore.kt](../revanced/src/main/kotlin/me/crema/patches/MillieCore.kt)에 구현돼 있습니다.

## ReVanced 적용 방식

[MilliePatch.kt](../revanced/src/main/kotlin/me/crema/patches/MilliePatch.kt)는 원시 리소스 패치로 동작합니다. 입력 파일의 SHA-256을 확인한 뒤 DEX 차이 파일을 적용하고 암호화 자산을 갱신합니다. 차이 파일에도 원본과 결과의 길이·SHA-256이 들어 있어, 다른 입력이나 손상된 결과를 거부합니다.

ReVanced가 패키징 중 DEX를 다시 기록할 수 있으므로 입력에서 읽은 DEX 다섯 개를 원시 리소스 출력에 포함합니다. 이 중 수정한 두 파일만 교체하며, 리소스 반영 단계에서 의도한 DEX 배치를 복원합니다. 다른 바이트코드 패치와 함께 적용하면 그 변경이 보존되지 않을 수 있으므로 **이 패치 하나만 적용해야 합니다.**

Patcher 22 계열의 원시 리소스 생성자 동작에 맞춰 `ResourcePatchBuilder(PatchType.RAW_RESOURCE)`를 명시합니다. 내부 API에 의존하므로 Manager 또는 Patcher 버전을 바꿀 때는 번들 로딩, DEX 배치와 최종 서명을 다시 검증해야 합니다.

## 배포 범위와 한계

배포 대상은 패치 소스, 원본이 있어야 적용할 수 있는 차이 데이터, `.rvp` 번들 및 문서입니다. 원본·수정 APK, 원본 DEX 전체, 서명 키는 배포하지 않습니다. 차이 데이터는 변경에 필요한 바이트를 포함하므로 원본 앱에 대한 이용 권한을 대신하지 않습니다. Conscrypt의 [라이선스](../revanced/licenses/Conscrypt-LICENSE)와 [고지](../revanced/licenses/Conscrypt-NOTICE)는 별도로 적용됩니다.

정확한 입력 버전만 지원하며, WebView의 TLS 구현이나 운영체제 전체의 보안을 개선하는 패치는 아닙니다. 앱과 서버의 후속 변경에 대한 호환성도 보장하지 않습니다. 현재 확인된 범위와 알려진 문제는 [검증 결과](../revanced/VERIFICATION.md)에 정리했습니다.
