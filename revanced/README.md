# 패치 소스 빌드 및 검증

휴대폰에서 설치하려면 [설치 안내](../README.md)를 참고하세요. 이 문서는 `.rvp` 번들을 빌드하거나 패치 결과를 검증할 때 사용합니다.

## 빌드

Python 3와 JDK 17 이상이 필요합니다. `java`를 PATH에 등록하거나 `MILLIE_JAVA`에 Java 실행 파일 경로를 지정하세요. 외부 도구는 공개 배포 URL에서 내려받고 [tools.lock.json](tools.lock.json)의 SHA-256으로 확인합니다.

저장소 루트에서 실행합니다.

```bash
python3 revanced/build.py
```

출력은 [`dist/millie-eink-patches-0.2.5.rvp`](dist/millie-eink-patches-0.2.5.rvp)와 [`dist/SHA256SUMS`](dist/SHA256SUMS)입니다. `.rvp`에는 JVM 클래스, Android DEX, 차이 데이터, Conscrypt ARMv7 라이브러리 및 라이선스 고지가 들어갑니다. 의존성 캐시와 중간 결과는 Git에서 제외한 `work/revanced/`에 생성됩니다.

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

기본 명령은 KitKat TLS 패치만 적용한 APK를 검사합니다. EPUB touch도 선택했다면 `--touch`를 추가하세요.

```bash
python3 revanced/verify_apk.py /path/to/original.apk /path/to/patched.apk --touch
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
| [ApkResourceInstaller.java](runtime/ApkResourceInstaller.java) | 2.4의 KitKat 클래스 로더에서 APK 리소스 검색 보완 |
| [ContentEventMapper.java](runtime/ContentEventMapper.java) | 2.4 EPUB 터치 좌표용 JSON 변환기 재사용 |
| [EpubTouchCompat.java](runtime/EpubTouchCompat.java) | API 19에서 뷰어의 임시 입력창 포커스 처리 생략 |
| `payloads/**/*.delta.gz` | 원본 DEX에 적용할 copy/literal 차이 데이터 |
| [2.1 입력](payloads/inputs.properties) · [2.4 입력](payloads/2.4.0.0/inputs.properties) | 버전별로 지원하는 입력 파일의 SHA-256 |
| [SelfTest.kt](src/test/kotlin/me/crema/patches/SelfTest.kt) | 적용 결과와 잘못된 입력에 대한 검사 |

2.1용 차이 데이터는 `payloads/`에 있습니다. `classes.dex`에는 MultiDex 설치 후 TLS 초기화를 추가하고, `classes5.dex`에는 업데이트 안내 분기 수정과 Conscrypt 2.5.2의 308개 클래스를 병합했습니다.

2.4용 차이 데이터는 `payloads/2.4.0.0/`에 있습니다. 외부 `classes.dex`에는 `ApplicationMain.multiDex()` 직후의 TLS 초기화와 Conscrypt를 추가했습니다. 0.2.1부터는 KitKat에서 포장된 DEX를 읽는 클래스 로더에 APK 리소스 검색 경로도 연결합니다. 뷰어 초기화 시 Kotlin 메타데이터를 찾지 못해 발생하는 `Built-in class kotlin.Any is not found` 오류를 보완합니다. `classes3.dex.delta.gz`는 `assets/classes3.jet`을 복호화하고 ZIP에서 추출한 내부 DEX에 적용합니다. 수정한 DEX를 다시 ZIP과 기존 AES 컨테이너로 포장합니다. 전체 DEX나 APK는 번들에 포함하지 않습니다.

0.2.2는 `assets/classes.jet` 내부 DEX에도 `classes1.dex.delta.gz`를 적용합니다. `ContentEvent(String)`의 JSON 변환기 생성 호출 한 곳을 `ContentEventMapper.get()`으로 바꿉니다. 보조 클래스는 같은 내부 DEX에 병합합니다. 원본 내부 DEX의 메서드 참조가 65,535개이므로, 사용하지 않는 보조 클래스의 비공개 생성자를 제거한 뒤 65,536개 참조로 조립합니다. 컴파일용 Jackson 스텁은 병합하지 않습니다.

0.2.3은 `classes3.dex.delta.gz`에 `EpubTouchCompat`와 `EPubWebView.loadWebViewBasePage`의 호출 한 곳을 추가합니다. 기본·다운로드 라이브러리를 읽은 후 같은 변환을 적용합니다. 코드는 API 19와 확인한 함수 본문에 한정하며, 진단 코드나 전체 뷰어 JavaScript는 번들에 추가하지 않습니다.

0.2.4부터는 패치를 두 항목으로 분리했습니다. KitKat TLS는 기존 호환성 수정을 담당하며 `classes3.dex.delta.gz`에는 업데이트 안내 분기 수정만 포함합니다. 선택 항목인 EPUB touch는 KitKat TLS에 의존하며, `classes1.dex.delta.gz`와 `classes3-touch.dex.delta.gz`를 적용합니다. 후자는 원본 DEX가 아니라 호환성 패치가 수정한 DEX를 입력으로 검증합니다. 두 결과를 모두 계산한 뒤 기록하므로 잘못된 입력에서 한 자산만 덮어쓰지 않습니다. 기본 선택은 KitKat TLS만 켜져 있습니다.

DEX 변환에는 smali/baksmali 2.5.2와 API 19 설정을 사용했습니다. JVM과 Android의 ZIP 구현에 따라 포장 바이트는 달라질 수 있으므로, 내부 DEX는 별도의 SHA-256으로 검증합니다.

초기화 Java 파일은 변경 내용을 설명하는 소스이며, 현재 `build.py`는 이를 다시 컴파일하거나 차이 데이터를 재생성하지 않습니다. DEX 수정을 바꾸려면 별도로 원본과 수정 DEX를 준비한 뒤 차이 데이터를 다시 생성해야 합니다.

```bash
python3 revanced/make_delta.py original.dex modified.dex output.delta.gz
```

구현 근거는 [수정 원리](../docs/PATCH-DESIGN.md), 결과 해시는 [검증 기록](VERIFICATION.md)을 참고하세요. Manager 버전을 변경하거나 차이 데이터를 갱신하면 Android 4.4 실행과 최종 APK 검증을 다시 수행해야 합니다.

## 원격 패치 소스 배포

공개 저장소는 `hegelty/millie-on-kitkat`, 기본 브랜치는 `main`입니다. GitHub Pages는 `main`의 `/docs`를 게시하며, 사용자 지정 도메인은 `millie.hegelty.me`입니다. `docs/CNAME`과 `.nojekyll`을 함께 유지합니다.

Manager에는 `https://millie.hegelty.me/patches.json`을 등록합니다. 번들 파일은 계속 GitHub Raw에서 받으며, APK는 배포하지 않습니다. 기존 루트의 `patches.json` 주소도 다운로드 호환성을 위해 유지합니다.

Manager 2.6.0의 [변경 이력 구현](https://github.com/ReVanced/revanced-manager/blob/v2.6.0/app/src/main/java/app/revanced/manager/domain/repository/ChangelogsRepository.kt)은 소스 URL에서 도메인만 추출하고 `/v5/patches/history`를 요청합니다. 따라서 GitHub Raw URL이나 도메인 하위 경로에만 배포하면 번들 상세 화면의 변경 이력이 동작하지 않습니다. `patches.json`에 문서 링크만 추가해도 해결되지 않습니다.

[generate_site.py](generate_site.py)는 루트의 `patches.json`과 `CHANGELOG.md`를 읽어 다음 파일을 생성합니다. `build.py`도 빌드 완료 시 이 함수를 실행합니다.

- `docs/patches.json`: 최신 번들 정보와 해당 버전의 변경 이력입니다.
- `docs/v5/patches/history`: 공개 버전별 `version`, `created_at`, `description` 배열입니다.

```bash
python3 revanced/generate_site.py
python3 revanced/generate_site.py --check
```

새 번들을 배포할 때는 `CHANGELOG.md`, `patches.json`의 `version`·`created_at`·`download_url`, 번들 파일과 `SHA256SUMS`를 갱신하고 생성된 Pages 파일도 함께 커밋합니다. 날짜는 시간대 접미사가 없는 ISO 8601 형식을 사용합니다. Manager는 버전으로 업데이트 여부를 비교하므로 같은 버전의 번들을 덮어쓰지 마세요. 변경 이력이나 호스팅 정보만 수정하는 경우에는 번들을 다시 빌드할 필요가 없습니다.

## 참고 자료와 라이선스

- [ReVanced Manager 2.6.0](https://github.com/ReVanced/revanced-manager/releases/tag/v2.6.0)
- [ReVanced Patcher 22.0.0 API](https://github.com/ReVanced/revanced-patcher/blob/v22.0.0/patcher/src/commonMain/kotlin/app/revanced/patcher/patch/Patch.kt)
- [ReVanced Library 4.0.1 패키징 구현](https://github.com/ReVanced/revanced-library/blob/v4.0.1/library/src/commonMain/kotlin/app/revanced/library/ApkUtils.kt)
- [Conscrypt 라이선스](licenses/Conscrypt-LICENSE) 및 [고지](licenses/Conscrypt-NOTICE)

사용 조건과 면책조항은 [README](../README.md)에 있습니다.
