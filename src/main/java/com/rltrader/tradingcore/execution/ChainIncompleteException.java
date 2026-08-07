package com.rltrader.tradingcore.execution;

/**
 * 회계 확정(REST orderSnapshot + balance)이 실패했을 때 던진다 — 주문은 비종결(non-terminal)로
 * 남고, 복구(§7)가 재개 대상으로 집어 든다. 사이클은 이를 alert + no-op으로 처리한다(Task 12).
 */
public class ChainIncompleteException extends RuntimeException {
    public ChainIncompleteException(String message, Throwable cause) {
        super(message, cause);
    }
}
