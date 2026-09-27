# KCoach

개인용 Galaxy Watch8 + Android 근비대 코치.

## 모듈

- `core`: 루틴 진행, 휴식 판정, 인클라인 걷기 에너지 추정, 관측 가능한 손목 신호 분석, 점진적 과부하
- `wear`: Wear OS 루틴 실행기, 진동, Health Services 연동 지점
- `phone`: 4분할 루틴, 자유운동, 운동 라이브러리, 기록 달력, 진행 화면, 앱 업데이트

## 현재 단계

스마트폰 앱에는 Obsidian 운동 기록을 기준으로 한 4개 기본 루틴과 실제 운동 라이브러리가 들어 있다.
실제 워치가 없어 하드웨어 센서와 Health Services 심박수는 아직 검증하지 않았다.
`core`는 기기 없이 단위 테스트할 수 있도록 Android 의존성을 분리했다.

## 실기 검증

1. Watch8의 API 36 건강 권한과 Health Services 심박수 확인
2. 가속도계와 자이로스코프의 실제 sampling rate와 batching 확인
3. 화면 꺼짐과 연결 끊김 상태에서 세트 데이터 보존 확인
4. 덤벨 컬 자체 상태 머신과 실제 횟수 비교
5. 세트 종료 후 Data Layer Asset 전송과 ACK 확인
