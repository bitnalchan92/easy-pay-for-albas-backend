# 알바페이 백엔드

알바페이 Apps in Toss 프론트엔드의 독립 Spring Boot API 서버입니다. Supabase 데이터 접근, 사업주·알바생 인증, 근무·급여 CRUD, 국세청 사업자 검증, 토스 로그인을 서버에서 처리합니다.

- 운영 API: `https://easy-pay-for-albas-backend-production.up.railway.app`
- 상태 확인: `GET /health`
- 프론트엔드: https://github.com/bitnalchan92/easy-pay-for-albas

## 로컬 실행

Java 17이 필요합니다.

```bash
cp .env.example .env
# .env에 실제 값을 입력
set -a
source .env
set +a
./gradlew bootRun
```

기본 주소는 `http://localhost:8080`입니다.

```bash
curl http://localhost:8080/health
./gradlew test
```

`/health`는 Supabase까지 조회하므로 `200` 응답이면 애플리케이션과 DB 연결이 모두 정상입니다.

## API

| 메서드 | 경로 | 용도 |
| --- | --- | --- |
| GET | `/health` | 서버·Supabase 상태 확인 |
| GET | `/data` | 사업장·알바생·근무일지·지급 내역 조회 |
| POST | `/auth/owner-login` | 사업주 로그인 |
| POST | `/auth/worker-join` | 알바생 사업장 가입 |
| POST | `/workplaces` | 사업장 생성 |
| POST, PATCH | `/workers` | 알바생 생성·수정 |
| POST, PATCH | `/worklogs` | 근무일지 생성·수정 |
| POST | `/payouts` | 급여 지급 내역 생성 |
| POST | `/verify-biz` | 국세청 사업자 상태 검증 |
| POST | `/toss/login/exchange` | 토스 인가 코드 교환·로그인 |
| POST | `/toss/login/refresh` | 토스 AccessToken 갱신 |
| POST | `/toss/login/disconnect` | 토스 로그인 연결 해제 |
| GET, POST | `/toss/login/callback` | 토스 연결 해제 콜백 |

## 환경변수

전체 목록은 [`.env.example`](.env.example)을 기준으로 관리합니다.

| 구분 | 변수 |
| --- | --- |
| Supabase | `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY` |
| 국세청 | `NTS_API_KEY` |
| 토스 mTLS | `TOSS_MTLS_CERT`, `TOSS_MTLS_KEY`, 선택 `TOSS_MTLS_CA` |
| 토스 복호화 | `TOSS_LOGIN_DECRYPTION_KEY`, `TOSS_LOGIN_DECRYPTION_AAD` |
| 세션·콜백 | `TOSS_SESSION_SECRET`, `TOSS_CALLBACK_BASIC_USER`, `TOSS_CALLBACK_BASIC_PASSWORD` |
| CORS | `CORS_ALLOWED_ORIGINS` |

`TOSS_MTLS_CERT`와 `TOSS_MTLS_KEY`에는 PEM 파일 전체를 Base64로 인코딩한 값을 넣습니다. 개인키는 PKCS8 `BEGIN PRIVATE KEY` 형식이어야 합니다. 비밀값과 인증서 파일은 Git에 커밋하지 않습니다.

## Railway 배포

Railway는 루트의 `Dockerfile`을 자동으로 사용하며, `main` 푸시 시 재배포합니다. 별도 `RAILPACK_JDK_VERSION`이나 `NO_CACHE` 설정은 필요하지 않습니다.

1. Railway 서비스 Variables에 `.env.example`의 운영 값을 등록합니다.
2. Region은 Singapore를 사용합니다.
3. Healthcheck Path를 `/health`로 설정합니다.
4. 공개 도메인을 생성하고 `/health`가 `200`인지 확인합니다.
5. 프론트 빌드의 `VITE_API_BASE_URL`을 운영 API 주소로 설정합니다.
6. `CORS_ALLOWED_ORIGINS`를 실제 프론트 Origin으로 제한합니다.

```bash
curl -i https://easy-pay-for-albas-backend-production.up.railway.app/health
```

## 토스 로그인 운영 설정

앱인토스 콘솔의 연결 끊기 콜백은 다음처럼 등록합니다.

- URL: `https://easy-pay-for-albas-backend-production.up.railway.app/toss/login/callback`
- Method: `POST`
- Basic Auth: Railway의 `TOSS_CALLBACK_BASIC_USER`, `TOSS_CALLBACK_BASIC_PASSWORD`와 동일한 값

토스 mTLS 인증서·개인키, 복호화 키, AAD는 서버에서만 사용합니다. 인증서나 콜백 비밀번호가 노출되면 즉시 폐기하거나 교체합니다.

## 테스트 현황

현재 자동화 테스트는 Spring 애플리케이션 컨텍스트 기동 1건입니다. 배포 후 `/health`와 프론트 연동 흐름을 별도로 확인해야 합니다.
