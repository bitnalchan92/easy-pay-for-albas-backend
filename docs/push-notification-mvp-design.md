# 푸시 알림 MVP 설계

## 결론

구현 가능하다. 앱인토스의 기능성 메시지 API를 백엔드에서 mTLS로 호출하고, 수신자는 이미 DB에 저장된 토스 `userKey`로 식별한다. FCM 토큰이나 별도 기기 등록 기능은 필요 없다.

다만 API 호출 전에 앱인토스 콘솔에서 기능성 캠페인 생성, 템플릿 코드 발급, 문구 검수 승인이 끝나야 한다. 승인 전에는 테스트 메시지도 보낼 수 없다.

공식 문서:

- [기능성 메시지 API](https://developers-apps-in-toss.toss.im/smart-message/develop.html)
- [스마트 발송 콘솔 및 문구 가이드](https://developers-apps-in-toss.toss.im/smart-message/intro.html)
- [mTLS 연동](https://developers-apps-in-toss.toss.im/development/integration-process.html)

## MVP 이벤트

| 이벤트 | 발생 조건 | 수신자 | 템플릿 변수 | 이동 URL |
| --- | --- | --- | --- | --- |
| 알바생 가입 요청 | 초대코드로 `pending` 알바생이 **새로 생성됨** | 근무지 사장 `owner_toss_user_key` | 알바생 이름, 근무지명 | `intoss://easyalbapay/owner/workers` |
| 근무일지 승인 요청 | `submitted` 근무일지가 생성됨 | 근무지 사장 `owner_toss_user_key` | 알바생 이름, 근무일 | `intoss://easyalbapay/owner/worklogs` |
| 근무일지 처리 결과 | `submitted`에서 `approved` 또는 `rejected`로 변경됨 | 알바생 `toss_user_key` | 근무일, 처리 결과 | `intoss://easyalbapay/worker/worklogs` |
| 급여 지급 완료 | 실제 `payouts` 행이 생성됨 | 알바생 `toss_user_key` | 지급 기간, 지급액 | `intoss://easyalbapay/worker/payroll` |

수신자 `userKey`가 없는 사장 직접 등록 알바생은 알림을 건너뛴다. 앱인토스 푸시로 식별할 수 없는 사용자이기 때문이다.

## 콘솔 템플릿 초안

제목은 공백 포함 7자, 본문은 25자 제한을 지킨다. 최종 문구와 변수명은 콘솔 검수 결과에 맞춘다.

| 설정 키 | 제목 | 본문 초안 |
| --- | --- | --- |
| `TOSS_PUSH_WORKER_JOIN_TEMPLATE` | 가입 요청 | `#{workerName}님이 가입을 요청했어요.` |
| `TOSS_PUSH_WORKLOG_SUBMITTED_TEMPLATE` | 일지 승인 | `#{workerName}님이 일지를 등록했어요.` |
| `TOSS_PUSH_WORKLOG_REVIEWED_TEMPLATE` | 일지 결과 | `#{workDate} 일지가 #{reviewResult}됐어요.` |
| `TOSS_PUSH_PAYOUT_COMPLETED_TEMPLATE` | 급여 지급 | `#{period} 급여 #{amount}원이 지급됐어요.` |

네 알림은 광고나 재방문 유도가 아닌 고용·근무·지급 상태 전달이므로 기능성 메시지로 신청한다. 지급 완료는 공식 면제 예시인 결제 완료와 같은 거래 알림에 가깝다. 나머지 세 템플릿의 알림 동의문 필요 여부는 콘솔 문구 검수 결과를 따른다. 검수에서 요구할 때만 프론트에 `requestNotificationAgreement`를 추가한다.

## 백엔드 설계

### 1. DB RPC가 알림 정보를 함께 반환

상태 변경과 수신자 조회를 한 트랜잭션에서 확정하기 위해 기존 RPC 반환값에 내부용 `notification` 객체를 추가한다.

- `join_worker_by_toss`: 새 행을 삽입한 경우에만 `notification` 반환
- `create_active_worklog`: 생성된 일지와 사장 `userKey` 반환
- `transition_active_worklog`: 실제 처리 결과와 알바생 `userKey` 반환
- `pay_worker_period`: `payout`이 생성된 경우에만 지급액과 알바생 `userKey` 반환

컨트롤러 응답에서는 `notification`을 제거한다. RPC는 `service_role` 전용이므로 프론트가 수신자 `userKey`를 볼 필요가 없다.

이 방식이면 초대코드 재입력이나 지급 API 재호출이 이미 처리된 이벤트를 다시 알리지 않는다. 별도 이벤트 테이블과 디바이스 토큰 테이블은 만들지 않는다.

### 2. 기존 mTLS 클라이언트 재사용

현재 `TossClient`와 `tossRestClient`가 토스 로그인용 mTLS 설정을 이미 제공한다. 여기에 아래 호출만 추가한다.

```text
POST /api-partner/v1/apps-in-toss/messenger/send-message
Header: x-toss-user-key: {userKey}
Body: { "templateSetCode": "...", "context": { ... } }
```

공식 문서의 헤더 표는 `x-toss-user-key`지만 같은 페이지의 curl 예시는 `x-user-key`로 서로 다르다. 구현은 스펙 표의 `x-toss-user-key`를 사용하고 테스트 메시지 API로 먼저 검증한다.

`PushService` 하나가 템플릿 코드 선택, 컨텍스트 생성, 실패 로그 정책을 담당한다. 새 HTTP 라이브러리나 메시지 브로커는 추가하지 않는다.

### 3. 전달 보장

MVP는 DB 변경 성공 후 같은 요청 흐름에서 푸시를 한 번 시도한다.

- 푸시 실패 때문에 가입·승인·지급 API를 실패시키지 않는다.
- 로그에는 이벤트 종류와 토스 오류 코드만 남기고 `userKey`, 이름, 금액은 남기지 않는다.
- 토스 응답은 HTTP 상태뿐 아니라 `resultType`도 검사한다.
- 사용자 식별키별 분당 10회 제한을 전제로 한다.

재시도 큐와 transactional outbox는 MVP에서 제외한다. 일시적 토스 장애 때 알림 한 건이 유실되는 문제가 실제로 확인되면 `notification_outbox`와 재시도 작업을 추가한다.

## 변경 범위

백엔드:

- Supabase migration 1개: 네 RPC의 내부 알림 반환 계약 수정
- `AlbapayProperties`, `application.yml`, `.env.example`: 템플릿 코드 4개
- `TossClient`: 기능성 메시지 API 호출
- `PushService`: best-effort 발송
- `AuthController`, `WorklogController`, `PayoutService`: 성공한 상태 변경 뒤 발송
- 단위 테스트: 중복 미발송, 수신자 없음, Toss 실패 시 본 요청 성공, 네 템플릿 컨텍스트

프론트:

- 콘솔 이동 URL이 기존 React Router 경로로 정상 진입하는지 실기기 확인
- 콘솔 검수에서 요구한 템플릿에 한해 알림 동의 UI 추가

## 구현 순서

1. 콘솔에서 기능성 캠페인 4개를 만들고 템플릿 코드와 동의문 요구 여부를 확정한다.
2. DB RPC 반환 계약과 테스트를 구현한다.
3. Toss 메시지 클라이언트와 best-effort 발송을 구현한다.
4. 테스트 메시지 API로 mTLS, 헤더, 템플릿 변수를 확인한다.
5. 네 사용자 흐름과 이동 URL을 앱인토스 테스트 번들에서 검증한다.

Codex와 Claude는 2번의 DB/RPC 변경과 3번의 Java 연동을 나눠 구현한 뒤 서로의 변경을 교차 검토한다. 1번의 콘솔 승인 값이 없으면 실제 발송 검증은 진행할 수 없다.
