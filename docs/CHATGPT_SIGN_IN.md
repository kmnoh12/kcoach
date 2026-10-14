# ChatGPT 계정 연결

2026-10-13. 메뉴 오른쪽 위 설정 → ChatGPT 계정에서 연결 화면으로 이동하고 기본 브라우저를 연다.
로그인·계정 선택·구독 한도 사용 동의 확인·사용량 관리·로그아웃과 작업 모델·추론 강도 설정을 구현했다.
모델 목록 조회를 위한 OAuth 토큰 갱신도 연결했다.
Responses API 작업 실행, GPT 운동 분석, GPT 개인 보정은 아직 연결하지 않았다.
로그인 자체가 모델 요청을 발생시키지 않는다.

## 공식 계약

- [Registration and sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)
- [Accounts and sessions](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions)
- [Consent recovery](https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery#chatgpt-plan-use-isnt-enabled)
- [UI/UX guidelines](https://developers.openai.com/siwc/ui-ux-guidelines)

개인 로컬 프로젝트용 dynamic registration으로 `dynamic_agent_client`에서 시작한다.
일반 웹사이트의 사전 발급 client ID/secret을 대신 만들어 넣지 않는다.
브라우저를 열기 전에 `127.0.0.1`의 임의 포트에 `/auth/callback` 수신기를 연다.
등록 응답에서 받은 issued client ID와 정확히 같은 callback URI로 코드를 교환한다.
같은 계정에 다시 연결할 때 issued client ID와 설치별 host ID를 재사용한다.
신규 연결 검증이 끝나기 전에는 기존 계정을 교체하지 않는다.

매 로그인마다 state, nonce, PKCE verifier를 새로 생성하며 유효 시간은 10분이다.
잘못된 state의 요청은 실제 로그인 시도를 소비하지 않는다.
정상 callback은 하나만 받으며 완료/취소/만료 시 listener를 닫는다.
브라우저의 `kcoach://chatgpt/return`은 앱 화면 복귀 전용이다. 이 경로로 인증 코드나 토큰을 받지 않는다.

공개 OIDC discovery의 issuer/endpoints를 확인하고 JWKS로 RS256 ID token 서명을 검증한다.
issuer, audience, sub, exp, iat, nbf, nonce 및 기존 계정의 subject를 확인한다.
한도 사용 가능 표시는 요청 scope나 callback scope가 아닌 **token response의 실제 granted scope**를 따른다.
identity-only 연결 뒤 사용자가 한도 사용을 요청할 때만 `prompt=consent`를 추가한다.
일반 재로그인마다 consent를 강제하지 않는다.

## 저장과 로그아웃

인증 정보와 설치 host ID는 Android Keystore AES-256-GCM으로 암호화하고,
`noBackupFilesDir/chatgpt-accounts.enc`에 AtomicFile로 저장한다.
토큰, 코드, verifier 및 ID token 원문을 로그/Compose UI 모델에 넣지 않는다.
앱 백업·기기 이전에 인증 정보와 host ID를 포함하지 않는다.
저장된 등록은 계정별 client ID와 subject로 분리한다. 이메일만으로 합치지 않는다.

로그아웃은 discovery의 revocation endpoint에서 refresh session 해제를 시도한 다음
선택한 계정의 access/refresh/ID token을 로컬에서 지운다.
네트워크 해제가 확인되지 않아도 로컬 토큰을 지우고 사용자에게 그 차이를 알린다.
계정 등록 메타데이터와 마지막 granted scopes는 재로그인용으로 유지한다.
scope가 남아 있어도 access token이 없으면 한도 사용 허용 상태로 표시하지 않는다.
운동 기록·보정 기록은 이 저장소에 없으며 로그아웃에서 변경하지 않는다.

## 검증 경계

`ChatGptAuthTest`는 로컬 HTTP callback을 실제로 열고, 가짜 OpenAI endpoint의
서명된 테스트 JWT로 등록 → 코드 교환 → 검증 → 암호화 저장 → 로그아웃을 실행한다.
실제 OpenAI 계정 로그인 성공이나 서버의 Android 클라이언트 허용을 증명하지 않는다.
Robolectric 저장 검사는 주입한 AES 키로 암호화/재읽기를 확인한다.
실제 폰의 Android Keystore, 기본 브라우저 왕복, 네 계정의 동의/등록은 실기기에서 확인해야 한다.

브라우저 이동 중 Activity 재생성은 앱 단위 인증 객체가 listener를 유지한다.
Android가 앱 **프로세스**를 종료하면 listener와 미완료 state/nonce는 복구하지 않는다.
그 경우 앱으로 돌아와 다시 연결해야 한다. 임시 코드를 새 시도에 재사용하지 않는다.
구독 한도 상태는 마지막 성공한 연결의 granted scope이며 서버의 실시간 잔여 한도가 아니다.
모델 목록 조회에서는 만료 직전의 토큰을 갱신하고 401이면 갱신 후 한 번만 재시도한다.
갱신 거부/재시도 후 401은 로컬 인증 정보를 비우고 재연결을 안내한다.
갱신 토큰은 직렬로 교환하고 교체된 인증 정보를 암호화 저장한 뒤 모델 조회에 사용한다.
토큰 교환 이후 ID-token 검증/로컬 저장이 실패하면 다시 연결해야 할 수 있다.
실제 모델 실행의 스트리밍·한도 초과·취소 처리는 아직 구현하지 않았다.

## 작업 모델과 추론 강도

[공식 모델 조회](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)에 따라
선택 계정의 OAuth access token으로 `GET https://api.openai.com/v1/models`를 호출한다.
`models`에서 `visibility: list`인 모델을 서버 순서대로 모두 표시한다.
다른 계정이나 Codex 설치본의 캐시를 이 계정의 지원 목록으로 대체하지 않는다.

추론 강도는 반환된 `supported_reasoning_levels`와 `default_reasoning_level`을 읽는다.
공식 app-server의 camelCase 표현인 `supportedReasoningEfforts`/`defaultReasoningEffort`도 읽을 수 있다.
값을 제한된 고정 모델명·강도 목록으로 잘라내지 않으며, 서버가 새 강도를 주면 그 값을 표시한다.
metadata가 없을 때는 임의의 low/high/ultra를 추가하지 않고 모델 기본값만 제공한다.
지원되지 않는 기본값이나 잘못된 형식은 지원 정보로 사용하지 않는다.

선택한 model slug와 명시적 effort는 계정별로 기존 암호화 저장소에 저장한다.
`null` effort는 모델 기본값이며 요청에서 reasoning을 생략한다. 문자열 `none`과 다르다.
모델 변경 시 이전 effort를 새 모델이 지원하지 않으면 기본값으로 바꾸고 안내한다.
서버 목록에서 사라진 모델은 설정을 몰래 다른 모델로 교체하지 않으며 다시 선택하도록 한다.
지원 정보를 불러오지 못한 상태나 유효하지 않은 선택에서는 작업 옵션을 만들 수 없다.

`selectedWorkRequestOptions()`는 선택한 `model`과 선택적인 `reasoning.effort`,
SIWC 필수인 `store: false`, `stream: true`를 만든다. 공통 `performWork()` 실행기가 이 옵션으로
공개 Responses API를 호출하도록 연결됐다. 완료 이벤트의 최종 답변만 성공 처리하고,
실패·미완료·한도 오류·취소·중단은 실패 처리한다. 추론 자동 재시도는 하지 않는다.
실제 계정 호출과 상담/리뷰 화면의 작업 실행 연결은 아직 미검증·미구현이다.
공통 호출기는 연결 15초·읽기 무응답 60초·총 10분을 상한으로 두고 사용자가 취소할 수 있다.
이는 상담용 기초 코드의 제한이며, 보정 워커 계획의 45초 전경 제한은 해당 작업 연결 시 별도로 구현해야 한다.
SettingsUiTest의 모델 이름은 테스트 fixture이며 실제 계정 지원 목록의 증거가 아니다.

현재 카메라 비교는 `CalibrationCameraActivity`와 `CalibrationReviewActivity`에 연결됐다.
촬영·영상 저장·센서 원본·사람 확인·개인 패턴 후보의 소프트웨어 흐름은 검사됐지만,
실제 폰 카메라와 Watch8 측정은 아직 검증 전이다.

로그인 아이콘은 [OpenAI SIWC DevKit 공식 mark](https://github.com/openai/sign-in-with-chatgpt-devkit/blob/main/assets/brand/chatgpt-button-black-21.svg)의
path를 Android VectorDrawable로 변환했다. 마크 사용에는 OpenAI branding 지침이 적용된다.
