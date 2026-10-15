# 데이터 저장 구조 설계안 (코드 변경 없음, 선택 후 구현)

2026-10-16. 법률 자문이 아니다. 법적 항목은 "확인 필요"로만 적었다.

## 1. 지금 상태 (코드에서 확인함)

| 데이터 | 위치 | 백업 대상? |
|---|---|---|
| 프로필·운동 기록·루틴·진행 설정 | SharedPreferences (`workout-log`, `finished-workouts`, `exercise-history`, `workout-energy` 등 십수 개) | 예 |
| 워치 원본 센서(.kcs) | `filesDir/captures` | 예 |
| 촬영 영상·관절 좌표(frames.jsonl)·확인 횟수 | `filesDir/calibration-captures/<id>` | **예** |
| 검증된 패턴 | `filesDir/reviewed-patterns`, `pattern-benchmarks` | 예 |
| AI 코치 기록, 운동 리뷰 | `noBackupFilesDir` (`ai-coach.json`, `workout-reviews.json` 등) | 아니오 |
| ChatGPT 로그인 정보 | `noBackupFilesDir/chatgpt-accounts.enc` (Keystore 암호화) | 아니오 |

- `AndroidManifest.xml`은 `allowBackup="true"`이고 `dataExtractionRules`가 없다(targetSdk 35). 그래서 영상까지 자동 백업·기기 이전 후보다. Google 자동 백업은 앱당 25MB 한도가 있어 영상은 한도를 넘기면 백업 전체가 건너뛰어질 수 있다(공식 문서). 실제로 폰에서 어떻게 동작하는지는 시험하지 않았다.
- Health Connect 연동 없음. 서버 계정 없음. 보정 증거는 선택 내보내기 ZIP(`CalibrationEvidenceExport`)만 있다.
- 위 표는 `grep`으로 찾은 저장 경로 기준이다. 모든 저장소를 빠짐없이 확인하지는 않았다.

## 2. 외부 사례 (웹 조사)

조사한 만큼만 적는다.

- **Hevy(운동 기록)**: 가입은 이메일·이름·비밀번호로 한다. 처리 위치는 "운영 사무소 및 처리 관련자가 있는 곳"으로 되어 있고 정확한 저장 위치는 밝히지 않는다. 미국(Apple·Amplitude), 아일랜드(Google), 독일(Adjust) 처리가 나열돼 있다. 삭제는 이메일 요청 후 한 달 내 답변이다. HealthKit 활동 데이터는 미국에서 처리한다고 쓴다. 건강정보를 특수 범주로 따로 다루는 문구는 못 찾았다. 출처: [Hevy 개인정보 처리방침(iubenda)](https://www.iubenda.com/privacy-policy/75379905), [Hevy 정책 페이지](https://www.hevyapp.com/legal/privacy-policy/).
- **Strong(운동 기록)**: 앱스토어 라벨에 "클라우드 동기화로 자동 백업"이 있고, 건강·피트니스·연락처·구매 정보가 신원에 연결돼 수집될 수 있다고 적혀 있다. 처리방침 본문은 가져오지 못해 저장 위치·내보내기·삭제는 **확인하지 못했다**. 출처: [App Store 목록](https://apps.apple.com/app/id464254577).
- **Kemtai(카메라 폼 교정)**: 분석은 기기에서 하고 골격 선 그림도 기기에서 만든다. 원본 이미지·영상은 서버에 올리지 않고 관절·신체 부위 위치와 그 파생 정보만 올린다. 영상은 사용자가 선택·동의할 때만 보관한다. 데이터는 이스라엘·영국·EU·미국 등에서 처리될 수 있다. 출처: [Kemtai 개인정보 처리방침](https://kemtai.com/?p=3).
- 못 한 것: Fitbod, JEFIT, Peloton 등 다른 상용 앱, Strong 처리방침 본문, 한국 앱(국내 서버 사용 여부).

시사점: 폼 체크 앱 중 확인된 사례는 "영상은 기기, 관절 좌표만 서버"이고, 기록 앱은 계정+클라우드 동기화가 기본이다. K코치의 방향(영상은 폰 전용, 구조화 데이터는 좌표 수준)은 Kemtai 방식과 닮았다. 단 사례 하나다.

## 3. 사용자 추천안

1. **구조화 데이터(프로필·기록·루틴·보정 증거)**: 폰 로컬 기본 + 사용자 내보내기 + 안드로이드 자동 백업.
2. **영상**: 폰에만 두고, 보관 기간 만료 삭제를 넣고, 백업에서 제외한다. (이미 `CalibrationVideoRetention`이 있어 만료 규칙을 거기에 맞추면 된다. 현재 기간 설정은 이번에 읽지 않았다.)
3. **서버 계정**: 기기 여러 대·웹·친구 기능이 필요해질 때 도입. 구글 로그인 먼저, 카카오·네이버는 토큰 검증 서버가 필요해 후순위.

검증 결과: 위 방향에 반대할 근거는 이번 조사에서 나오지 않았다. 다만 백업 한도(25MB)와 실제 복원 동작은 폰에서 시험해야 한다.

## 4. 백업 규칙 구체안 (`dataExtractionRules`)

`AndroidManifest.xml`에 `android:dataExtractionRules="@xml/data_extraction_rules"` 추가, `res/xml/data_extraction_rules.xml` 신규. `domain="file"`은 `getFilesDir()`이다(공식 문서). `path`는 와일드카드 없이 디렉터리 단위다.

```xml
<data-extraction-rules>
  <cloud-backup disableIfNoEncryptionCapabilities="true">
    <exclude domain="file" path="calibration-captures"/>
    <exclude domain="file" path="captures"/>
  </cloud-backup>
  <device-transfer>
    <exclude domain="file" path="calibration-captures"/>
    <exclude domain="file" path="captures"/>
  </device-transfer>
</data-extraction-rules>
```

- `calibration-captures`(영상·좌표·확인 횟수)와 `captures`(워치 원본)를 클라우드·기기 이전에서 뺀다. exclude가 우선하고, include를 하나라도 쓰면 나머지가 빠지므로 include는 쓰지 않는다.
- 결정이 필요한 점: 좌표와 확인 횟수는 작아서 백업해도 되지만 같은 폴더에 영상과 섞여 있다. 영상만 빼려면 영상을 별도 하위 폴더로 옮기는 코드 변경이 먼저 필요하다(Android 규칙은 와일드카드가 없다). 간단한 안은 폴더째 제외(복원 후 보정 증거 없음)다.
- Android 11 이하(minSdk 29)용 `android:fullBackupContent` 규칙도 같은 내용으로 필요하다.
- 시험 필요: `adb shell bmgr`로 백업·복원, 기기 이전 시뮬레이션. 이번에는 하지 않았다.

## 5. 서버 계정 도입 시 부담 (일반론, 이번에 금액은 조사하지 않음)

- **비용**: 서버·DB·백업 운영비, 로그인 제공자 연동, 알림·메일. 사용자 수에 따라 달라지며 구체 금액은 **조사 못 함**.
- **개인정보처리방침**: 수집 항목·목적·보관 기간·제3자 제공·위탁·국외 이전을 적어야 한다. 서버 위치가 해외면 국외 이전 항목이 생긴다(확인 필요).
- **삭제 요청**: 앱 안 삭제 버튼과 서버 데이터·백업·로그까지 지우는 절차가 필요하다. Hevy 사례는 이메일 요청이다.
- **유출 대응**: 유출 감지·통지 절차와 암호화, 접근 로그가 필요하다. 구체 의무는 확인 필요.
- 이 앱은 지금 개발사 서버에 아무것도 저장하지 않는다는 점이 가장 큰 장점이라, 도입 시점을 늦출수록 부담이 없다.

## 6. 한국 개인정보 규정 (확인 필요 항목)

2차 자료 검색 기준이다. 조문 원문은 확인하지 못했다.

- 건강정보는 민감정보라 별도 동의가 필요하다고 설명된다. 몸 영상·관절 좌표가 건강정보나 생체정보에 해당하는지는 **확인 필요**.
- 국외 이전은 별도 동의 또는 예외 요건이 필요하고, 이전 국가·받는 자·연락처 등을 알려야 하며 2023년 개정으로 제28조의8에 모였다고 설명된다. 사용자가 본인 ChatGPT 계정으로 OpenAI에 영상·좌표를 보내는 경우 개발사가 "처리자"에 해당하는지, 사용자 본인 전송으로 보는지는 **확인 필요**.
- 참고 자료: [신김 뉴스레터](https://shinkim.com/kor/media/newsletter/pdf/2452), [IT위키 개인정보 국외 이전](https://itwiki.kr/w/%EA%B0%9C%EC%9D%B8%EC%A0%95%EB%B3%B4_%EA%B5%AD%EC%99%B8_%EC%9D%B4%EC%A0%84). 공식 근거는 법제처 원문과 개인정보보호위원회 자료로 확인해야 한다.
- 앱 배포가 개인용 사이드로드가 아니라 스토어 공개로 바뀌면 Play 데이터 안전 섹션 신고도 필요하다(확인 필요).

## 7. Health Connect

- 있음: `ExerciseSessionRecord`(종목은 `EXERCISE_TYPE_STRENGTH_TRAINING`, `WEIGHTLIFTING` 등). 데이터 타입별 읽기·쓰기 권한이 따로 있고, Play 게시 때 접근 데이터 선언이 필요하다. 출처: [Health Connect 데이터 타입](https://developer.android.com/health-and-fitness/health-connect/data-types).
- 못 찾음: 세트·반복·중량을 담는 전용 레코드. 문서 표에는 없다. `segments`로 표현 가능한지는 레퍼런스를 더 봐야 한다(미확인).
- 판단(추정): 운동 시간·심박·칼로리를 다른 앱과 공유하는 용도면 쓸 만하지만, K코치의 핵심(세트별 횟수·템포·보정 증거)은 담기 어렵다. 지금 필요성이 낮아 보여 **후순위**를 권한다. 사용자가 다른 건강 앱과 연동을 원하면 그때 쓰기 전용으로 시작한다.

## 8. 사용자에게 던질 선택지

1. 백업 제외 범위: (A) `calibration-captures`·`captures` 폴더째 제외(가장 단순) / (B) 영상을 하위 폴더로 분리해 영상만 제외하고 좌표·확인 횟수는 백업(코드 변경 있음).
2. 영상 보관 기간: 기본 며칠? (현재 값은 `CalibrationVideoRetention` 확인 후 제안 가능)
3. 서버 계정: 지금은 도입하지 않고, 필요가 생길 때 구글 로그인부터. 이대로 확정?
4. Health Connect: 후순위로 둘지, 쓰기 전용 연동을 먼저 할지.
5. 법적 항목 확인을 누가 언제 할지(공개 배포 전에는 필수 권장).
