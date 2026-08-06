# rl-trading-core

RL 예측 서버(predict server)의 결정을 5분 grid로 소비해 **모의(dry-run) 체결**을 기록하는
Spring Boot 트레이딩 코어. **1차 릴리스는 dry-run 전용이며 실주문은 나가지 않는다** —
모든 체결은 `DryRunExecutor`가 메모리·DB 상의 포트폴리오만 갱신한다.

## 요구 사항

- Java 21
- Docker (Postgres 컨테이너용)
- 별도로 기동되는 predict 서버 (`/ready`, `/metadata`, `/predict` 제공)

## 운영 실행 순서

```bash
# 1. Postgres 기동
docker compose up -d postgres

# 2. predict 서버 기동 (별도 저장소/프로세스) — /ready가 200을 반환할 때까지 대기 가능해야 함

# 3. 트레이딩 코어 기동
./gradlew bootRun
```

기동 시퀀스(`BootSequence`, spec §4)는 다음 순서로 진행된다:

1. `/ready` 폴링 — `trading.ready-wait-max-seconds` 내에 200이 오지 않으면 기동 실패 (monotonic 타임아웃, 시장 Clock과 무관)
2. `/metadata` 조회 → `HandshakeValidator` 검증 (액션 라벨·`unit_fraction`·`max_units`·자본 상한) — 위반 시 기동 실패
3. `PortfolioManager.restore` — 기존 스냅샷 복원 (없으면 flat 시작)
4. `DecisionCycleService.initialize` — 사이클 활성화
5. 스케줄러 활성화 (`DecisionScheduler.enable()`) — 이후 5분 grid(09:05~15:20 KST, 평일)마다 `trading.delay-seconds`만큼 지연되어 발화

스케줄러는 프로세스 내 단일 인스턴스를 전제한다(`replicas=1`). 겹치는 발화는 single-flight로
건너뛰고 `SKIPPED_BUSY` 알림을 남긴다. Halted 상태(`ARTIFACT_CHANGED` 등 치명 위반) 이후에는
재기동 전까지 신규 사이클이 발화하지 않는다.

## 알림(Alert) 코드

구조화 로그(ERROR)가 필수 채널이고 `alerts` 테이블 INSERT는 best-effort다(spec §5). 코드는
DB 컬럼 제약(VARCHAR(32))에 맞춰 32자로 truncate되어 저장된다.

| 코드 | 발생 조건 |
|---|---|
| `INVALID_RESPONSE` | `/predict` 200 응답 본문이 구조·건전성 검증 실패(필드 누락·NaN·mask 길이 등) |
| `PREDICT_PARSE` | `/predict` 200 응답 본문 파싱/coercion 실패 |
| `PREDICT_STALE_DATA` | warmup(10:05 이전) 이후에도 predict가 `STALE_DATA`로 실패 (warmup 이내는 무알림) |
| `ARTIFACT_CHANGED` | 응답의 `artifact_id`/`feature_schema_version`이 핸드셰이크와 불일치 — 스케줄러 halt |
| `WRONG_BAR` | 응답의 `bar_ts`가 요청한 bar와 불일치(staleness 허용창의 직전 bar 등) |
| `MASK_VIOLATION` | `action_mask` 위반 또는 risk gate(한도·현금 부족 등) 거부 |
| `DB_ERROR` | 사이클 트랜잭션 커밋 중 DB 실패(UNIQUE 중복이 아닌 무결성 위반 포함) |
| `SKIPPED_BUSY` | single-flight 겹침으로 tick이 건너뜀 |
| `UNEXPECTED_ERROR` | `runCycle`이 열거되지 않은 `RuntimeException`을 던짐 — 상태 무변경, 스케줄러는 계속 진행 |

## Smoke 실행 (기동 검증)

운영 스케줄러 없이 지정한 bar들을 즉시 순차 실행해 배포 직후 파이프라인 전체(핸드셰이크 → 복원 →
predict 호출 → risk gate → dry-run 체결 → 원장 기록)를 검증한다.

```bash
SPRING_PROFILES_ACTIVE=smoke ./gradlew bootRun
```

- `smoke` 프로파일은 고정 `Clock`(`smoke.clock-at`)을 사용한다 — 운영 `Clock`(시스템 KST)과
  상호 배타적이며 동시에 두 개가 활성화되지 않는다.
- `smoke.mode: replay`(기본값) — `smoke.start-bar`부터 `smoke.bars`개의 bar를 순차 실행하고,
  실행이 끝나면 컨텍스트를 정상 종료한다. 도중 `HALTED`가 나오거나 `EXECUTED`가 한 건도 없으면
  예외를 던져 non-zero exit로 실패를 알린다(둘 다 검증 실패로 간주 — false positive 방지).
- `smoke.mode: restore-only` — 스냅샷 복원만 검증하고(bar 실행 없음) 상태를 로그로 출력한 뒤
  종료한다. **선행 조건**: 같은 심볼의 스냅샷이 이미 존재해야 한다(직전 `replay` 실행 필요).
  재기동 검증 목적으로 같은 bar를 다시 돌리면 `currentBar < entryBarTs`가 되어 오류가 나므로
  bar를 재실행하지 않는다.
- `warmup` 구간(09:05~10:00)은 predict 서버의 상태 부족으로 `STALE_DATA`가 기대되는 구간이라
  smoke 기본 설정은 `10:05`부터 시작한다.

## 설정

`application.yml` (`trading.*`, 운영 공통):

| 키 | 설명 | 예시 |
|---|---|---|
| `trading.symbol` | 거래 종목 코드 | `005930` |
| `trading.predict-base-url` | predict 서버 base URL | `http://127.0.0.1:8000` |
| `trading.delay-seconds` | 매 5분 bar 이후 발화 지연(초), cron 초 필드로 사용 — 0..59 | `20` |
| `trading.max-units-cap` | 아티팩트 `max_units`가 넘을 수 없는 운영 상한 | `5` |
| `trading.max-capital-cap` | 풀 포지션 명목가 상한(KRW), Spring이 소유하는 hard cap | `10000.0` |
| `trading.dry-run-initial-cash` | 신규(flat) 포트폴리오 초기 현금 | `10000.0` |
| `trading.connect-timeout-ms` | predict 서버 커넥션 타임아웃 | `5000` |
| `trading.read-timeout-ms` | `/predict` 전용 read timeout (KIS fetch 최악 케이스 고려) | `240000` |
| `trading.control-timeout-ms` | `/ready`·`/metadata` 전용 read timeout | `5000` |
| `trading.ready-wait-max-seconds` | 기동 시 `/ready` 대기 최대 시간(초) | `60` |

`application-smoke.yml` (`smoke.*`, `smoke` 프로파일 전용):

| 키 | 설명 | 예시 |
|---|---|---|
| `smoke.clock-at` | smoke 고정 Clock 시각(KST, ISO local date-time) — historical 데이터가 있는 거래일 | `2026-07-21T11:00:00` |
| `smoke.start-bar` | 실행 시작 bar (grid 상, `HH:mm`) | `10:05` |
| `smoke.bars` | 실행할 bar 개수 (마지막 bar `15:20`에서 절단) | `6` |
| `smoke.mode` | `replay`(기본) 또는 `restore-only` — enum 바인딩이라 오타는 조용히 넘어가지 않고 기동 실패 | `replay` |

## 테스트

```bash
./gradlew test
```

Postgres는 Testcontainers로 테스트마다 기동된다(로컬 `docker compose`와 별개). `test` 프로파일에서는
`StartupRunner`(운영 전용, `/ready` 블로킹)와 `SmokeRunner`가 모두 비활성화된다.
