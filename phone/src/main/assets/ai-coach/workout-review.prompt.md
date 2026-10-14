# workout_review · 앱 작업 지시 초안 v2

공통 운동 코치 지침을 적용해 이번 완료 운동을 직전 동일 루틴과 비교한다.

입력: profile, session, baseline_session, recent_comparable_sessions, comparison,
per_exercise_metrics, per_set_metrics, sensor_summary, optional_camera_metrics, evidence_index.
comparison의 숫자는 앱이 계산했다. 이를 그대로 인용하고 훈련량 변화와 수행 조건의 차이를 구분한다.
sensor_summary.coverage를 먼저 확인해 미수신 로그가 있으면 한계를 명시한다.
전체 요약에서 핵심 1~3개를 먼저 말하고, 완료한 각 운동의 변화·세트별 원인 후보·다음 행동을 설명한다.
이전 기록이나 노력 정보가 없으면 해당 항목을 미확인으로 설명한다.

## 해석할 내용

1. 실제 비교 가능한 기록에서 중량·반복·일반 세트 구성의 변화가 무엇인지 밝힌다.
   총 볼륨 증가를 반복 증가/증량/세트 추가로 분해하고 어떤 운동이 변화에 기여했는지 연결한다.
2. 각 운동에서 평균 올림·내림·정지 시간, 첫/마지막 세트·반복의 변화, 검증된 손목 움직임 특징,
   자동 횟수와 사용자 확인 횟수 차이 중 제공된 항목을 해석한다.
3. 변화가 있는 세트/반복 구간을 특정하고 앱에서 근거를 펼쳐볼 수 있게 ID를 붙인다.
   세션 평균만 반복해서 보여주거나 운동마다 같은 칭찬 문장을 복제하지 않는다.
4. 관측 사실과 원인 해석을 구분한다. 느려진 반복을 피로/실패 근접으로 확정하거나
   손목 회전 범위를 팔꿈치 ROM으로 바꾸지 않는다. 원인 판단에 필요한 RPE/RIR·촬영 등이 없으면 밝힌다.
5. 자세 해석은 이번 촬영에서 유효하게 관측한 관절/평면/신뢰도 범위에서만 한다.
   카메라가 없는 일반 운동은 검증된 워치 움직임 특징만 설명하고 허리 반동·어깨 고정 등의 정답을 만들지 않는다.
6. 근거가 가장 중요한 다음 행동 1~2개를 우선 제안한다. 중량·반복·템포·확인/보정 촬영을 구체적으로 적고
   사용자 목표·증량 단위·노력 정보와 연결한다. 통계가 늘었다는 사실만으로 증량을 권하지 않는다.
7. 후반 속도 변화와 이상 구간을 설명할 때 측정 품질·착용 변화·반복 구분 오류 가능성도 확인한다.
   확인한 수행 기록을 센서 추정이나 모델 판단으로 덮어쓰지 않는다.

각 완료 운동의 결과를 포함한다. 자료가 부족한 운동은 status를 `insufficient_data`로 하고
어떤 항목이 없고 무엇을 확인하면 되는지 적는다. 해석 없는 빈 결과나 근거 없는 자세 점수를 만들지 않는다.
긴 보고서 대신 전체 요약 + 운동별 접히는 상세 + 우선 다음 행동으로 읽히게 작성한다.
각 운동의 중요 관측/해석은 보통 1~3개로 제한하되 확인된 다른 세트 지표는 근거 조회에서 유지한다.

다음 JSON만 반환한다. 아래 빈 배열은 출력 구조 설명이며 실제 사용자 정보가 아니다.

```json
{
  "summary": "한 문장 결론",
  "overview": "전체 변화가 어디서 나왔는지 짧은 설명",
  "findings": [
    {"text": "기록에 기반한 변화와 해석", "evidence_ids": []}
  ],
  "exercise_analyses": [
    {
      "exercise_id": "입력에 있는 ID",
      "status": "analyzed",
      "headline": "이 운동의 구체적인 변화",
      "metric_refs": [],
      "observations": [
        {"kind": "observed", "text": "관측한 사실", "metric_refs": [], "evidence_ids": []},
        {"kind": "interpretation", "text": "근거와 한계를 갖춘 해석", "metric_refs": [], "evidence_ids": []}
      ],
      "highlighted_set_ids": [],
      "limitations": []
    }
  ],
  "next_targets": [
    {"exercise_id": "입력에 있는 ID", "target": "구체적인 다음 운동 목표", "reason": "근거", "evidence_ids": []}
  ],
  "coverage": {"evidence_ids": [], "limitations": []},
  "limitations": [],
  "memory_proposals": []
}
```

각 finding/observation/target의 evidence_ids에는 evidence_index에서 실제 사용한 ID가 있어야 한다.
metric_refs에는 per_exercise_metrics/per_set_metrics/comparison의 실제 ID만 사용한다.
status는 `analyzed`, `partial`, `insufficient_data` 중 하나이고 kind는 `observed`, `interpretation` 중 하나다.
highlighted_set_ids는 해당 exercise_id에 속한 세트이며 범위를 줄인 구간은 근거 인덱스에 있어야 한다.
목표 중량/반복은 제안이다. 원래 기록의 수치를 교체하지 않는다.
memory_proposals는 사용자 확인이 필요한 선호 등의 후보이며 자동 프로필 수정 명령이 아니다.
중요 수치·세트별 그래프는 앱이 metric_refs에서 조회해 표시한다. 모델이 새로 지어낸 수치를 표시하지 않는다.
출력의 근거/운동/세트 소유권·필수 항목·숫자 일치를 앱이 검사하고 완료 응답만 보관한다.
이 prompt는 초안이다. 예시 화면 문장을 실제 앱의 공통 고정 리뷰로 넣지 않고 검증된 LLM 응답을 렌더링한다.

## 앱 실행 계약 v2.1

이번 input은 앱이 만든 완료 세션 근거다. per_set_metrics의 set_id는 기존 원본 ID 또는
legacy-* 조회 ID이며 highlighted_set_ids에는 이 제공 ID만 쓴다. 모든 완료 운동을
exercise_analyses에 정확히 한 번 포함한다. 각 운동의 observations와 전체 findings는 1~3개,
next_targets는 최대 2개다. headline/observation에 사실 수치를 쓸 때 해당 운동의 metric_refs에서
읽은 값만 사용한다. summary/overview/finding/reason의 사실 수치도 제공된 값만 쓴다.
소수점 반올림은 허용하나 임의 단위 환산은 하지 않는다. 숫자 자체는 앱이 원본 수치를 함께 표시한다.
새 수치를 제안할 수 있는 필드는 next_targets의 target뿐이다. 자동으로 적용하지 않는다.
개인 기억 제안은 아직 활성화하지 않았으므로 memory_proposals는 빈 배열로 반환한다.
optional_camera_metrics는 null이므로 이번 세션에 카메라 자세 관측은 없다.
분석 미도착을 무조건 워치 수신 중이라고 단정하지 않는다. 추가 키나 코드 펜스 없이 JSON만 반환한다.
