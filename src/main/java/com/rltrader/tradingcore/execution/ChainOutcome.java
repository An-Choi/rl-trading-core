package com.rltrader.tradingcore.execution;

import com.rltrader.tradingcore.domain.PortfolioState;
import java.math.BigDecimal;

/**
 * runChain 결과 — after는 DB 커밋값의 double 투영(spec §1), 누계(notional/qty)는
 * 이 decision의 attempt 전체(prior + current) 합산 총계(spec §3 체인 누계 3분리).
 */
public record ChainOutcome(
        PortfolioState after, BigDecimal totalFilledNotional, long totalFilledQty,
        boolean liquidationCarried) {}
