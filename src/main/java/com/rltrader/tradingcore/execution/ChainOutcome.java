package com.rltrader.tradingcore.execution;

import com.rltrader.tradingcore.domain.PortfolioState;
import java.math.BigDecimal;

/**
 * runChain 결과 — after는 DB 커밋값의 double 투영(spec §1), 누계(notional/qty)는
 * 이 decision의 attempt 전체(prior + current) 합산 총계(spec §3 체인 누계 3분리).
 * hadTimeout: 이 runChain 호출 안에서 attempt가 fill-timeout(취소 경로)을 한 번이라도 거쳤는지 —
 * true면 호출부(Task 13 LiveCycleOrchestrator)가 POST_ORDER 강제 대사를 트리거한다(Task 12는
 * 필드만 세팅, 배선은 Task 13).
 */
public record ChainOutcome(
        PortfolioState after, BigDecimal totalFilledNotional, long totalFilledQty,
        boolean liquidationCarried, boolean hadTimeout) {}
