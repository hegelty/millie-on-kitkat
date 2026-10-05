# 패치 소스 빌드 및 검증

휴대폰에서 설치하려면 [설치 안내](../README.md)를 참고하세요. 이 문서는 `.rvp` 번들을 빌드하거나 패치 결과를 검증할 때 사용합니다.

## 빌드

Python 3와 JDK 17 이상이 필요합니다. `java`를 PATH에 등록하거나 `MILLIE_JAVA`에 Java 실행 파일 경로를 지정하세요. 외부 도구는 공개 배포 URL에서 내려받고 [tools.lock.json](tools.lock.json)의 SHA-256으로 확인합니다.

저장소 루트에서 실행합니다.

```bash
python3 revanced/build.py
```

출력은 [`dist/millie-eink-patches-0.2.0.rvp`](dist/millie-eink-patches-0.2.0.rvp)와 [`dist/SHA256SUMS`](dist/SHA256SUMS)입니다. `.rvp`에는 JVM 클래스, Android DEX, 차이 데이터, Conscrypt ARMv7 라이브러리 및 라이선스 고지가 들어갑니다. 의존성 캐시와 중간 결과는 Git에서 제외한 `work/revanced/`에 생성됩니다.

이미 포함된 차이 데이터로 번들을 빌드할 때는 APK가 필요하지 않습니다. 패치 적용 검증에는 정당한 권한으로 획득한 원본 APK가 필요합니다.

```bash
python3 revanced/build.py \
  --test-apk /path/to/millie-app-2.1.0.0.apk \
  --test-apk /path/to/millie-app-2.4.0.0.apk \
  --reject-apk /path/to/millie-app-2.5.0.0.apk
```

`--test-apk`는 버전별로 반복해서 지정할 수 있고, `--reject-apk`는 선택 사항입니다. 이 검사는 두 버전의 기준 결과, 2.4의 암호화된 내부 DEX, 입력 변조, 재패치, 추가 DEX, 손상된 차이 데이터 및 암호화 자산을 확인합니다. 내부 버전이 2.4로 표시되는 2.5 파일도 거부해야 합니다. APK는 빌드나 테스트 결과물로 배포하지 않습니다.

## APK 내용 검증

Manager에서 직접 생성한 APK를 PC로 옮긴 뒤 실행합니다. Python 3와 OpenSSL이 필요합니다.

```bash
python3 revanced/verify_apk.py /path/to/original.apk /path/to/patched.apk
```

지원 원본의 해시, ZIP CRC, DEX 헤더·체크섬과 기준 결과, 변경 파일 범위, 네이티브 수정 및 암호화 자산 내부의 무결성 기록을 검사합니다. 2.4에서는 `classes3.jet` 내부의 단일 DEX와 업데이트 안내 분기의 수정 범위도 검사합니다. APK 서명 검증은 Android SDK의 `apksigner`로 별도 수행합니다.

```bash
apksigner verify --verbose /path/to/patched.apk
```

검증 실패 시 해당 APK를 설치하거나 배포하지 마세요. CLI 6.0.0은 패치가 예외로 실패한 뒤에도 APK를 생성할 수 있으므로, 출력 파일이 생겼다는 사실만으로 성공을 판단하면 안 됩니다. 패치 성공 로그와 최종 내용 검증을 함께 확인하세요.

## 소스와 차이 데이터

| 파일 | 역할 |
|---|---|
| [MilliePatch.kt](src/main/kotlin/me/crema/patches/MilliePatch.kt) | ReVanced 연결 및 원시 리소스 입출력 |
| [MillieCore.kt](src/main/kotlin/me/crema/patches/MillieCore.kt) | 입력 검사, DEX 차이 적용, 자산 갱신 |
| [TlsInstaller.java](runtime/TlsInstaller.java) | 앱에 추가하는 TLS 초기화 코드 |
| `payloads/**/*.delta.gz` | 원본 DEX에 적용할 copy/literal 차이 데이터 |
| [2.1 입력](payloads/inputs.properties) · [2.4 입력](payloads/2.4.0.0/inputs.properties) | 버전별로 지원하는 입력 파일의 SHA-256 |
| [SelfTest.kt](src/test/kotlin/me/crema/patches/SelfTest.kt) | 적용 결과와 잘못된 입력에 대한 검사 |

2.1용 차이 데이터는 `payloads/`에 있습니다. `classes.dex`에는 MultiDex 설치 후 TLS 초기화를 추가하고, `classes5.dex`에는 업데이트 안내 분기 수정과 Conscrypt 2.5.2의 308개 클래스를 병합했습니다.

2.4용 차이 데이터는 `payloads/2.4.0.0/`에 있습니다. 외부 `classes.dex`에는 `ApplicationMain.multiDex()` 직후의 TLS 초기화와 Conscrypt를 추가했습니다. `classes3.dex.delta.gz`는 `assets/classes3.jet`을 복호화하고 ZIP에서 추출한 내부 DEX에 적용합니다. 수정한 DEX를 다시 ZIP과 기존 AES 컨테이너로 포장합니다. 전체 DEX나 APK는 번들에 포함하지 않습니다.

DEX 변환에는 smali/baksmali 2.5.2와 API 19 설정을 사용했습니다. JVM과 Android의 ZIP 구현에 따라 포장 바이트는 달라질 수 있으므로, 내부 DEX는 별도의 SHA-256으로 검증합니다.

초기화 Java 파일은 변경 내용을 설명하는 소스이며, 현재 `build.py`는 이를 다시 컴파일하거나 차이 데이터를 재생성하지 않습니다. DEX 수정을 바꾸려면 별도로 원본과 수정 DEX를 준비한 뒤 차이 데이터를 다시 생성해야 합니다.

```bash
python3 revanced/make_delta.py original.dex modified.dex output.delta.gz
```

구현 근거는 [수정 원리](../docs/PATCH-DESIGN.md), 결과 해시는 [검증 기록](VERIFICATION.md)을 참고하세요. Manager 버전을 변경하거나 차이 데이터를 갱신하면 Android 4.4 실행과 최종 APK 검증을 다시 수행해야 합니다.

## 원격 패치 소스 배포

공개 저장소는 `hegelty/millie-on-kitkat`, 기본 브랜치는 `main`을 기준으로 합니다. 저장소 루트의 [patches.json](../patches.json)이 Manager에 등록하는 소스이며, `download_url`은 같은 저장소의 `.rvp` 번들을 가리킵니다. 별도 API 서버나 APK 배포는 필요하지 않습니다.

`patches.json`은 Manager 2.6.0의 [ReVancedAsset 형식](https://github.com/ReVanced/revanced-manager/blob/v2.6.0/app/src/main/java/app/revanced/manager/network/dto/ReVancedAsset.kt)을 사용합니다. `created_at`은 시간대 접미사가 없는 ISO 8601 날짜·시간이며, 현재 값은 번들의 빌드 기준 시각입니다.

새 번들을 배포할 때는 번들 파일과 `SHA256SUMS`를 갱신하고, `patches.json`의 `version`, `created_at`, `download_url`도 함께 수정합니다. Manager는 `version` 값으로 업데이트 여부를 비교하므로 같은 버전의 파일만 덮어쓰지 마세요. 생성한 APK와 서명 키는 업로드하지 않습니다.

## 참고 자료와 라이선스

- [ReVanced Manager 2.6.0](https://github.com/ReVanced/revanced-manager/releases/tag/v2.6.0)
- [ReVanced Patcher 22.0.0 API](https://github.com/ReVanced/revanced-patcher/blob/v22.0.0/patcher/src/commonMain/kotlin/app/revanced/patcher/patch/Patch.kt)
- [ReVanced Library 4.0.1 패키징 구현](https://github.com/ReVanced/revanced-library/blob/v4.0.1/library/src/commonMain/kotlin/app/revanced/library/ApkUtils.kt)
- [Conscrypt 라이선스](licenses/Conscrypt-LICENSE) 및 [고지](licenses/Conscrypt-NOTICE)

사용 조건과 면책조항은 [README](../README.md)에 있습니다.
