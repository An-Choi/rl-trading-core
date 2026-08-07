package com.rltrader.tradingcore.domain;

import static com.rltrader.tradingcore.domain.OrderStatus.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OrderStateMachineTest {

    @Test
    void normal_transitions_allowed() {
        // spec §3 다이어그램의 정상 경로 전수
        OrderStateMachine.assertTransition(NEW, SUBMITTED);
        OrderStateMachine.assertTransition(NEW, SUBMIT_UNKNOWN);
        OrderStateMachine.assertTransition(NEW, ABORTED);
        OrderStateMachine.assertTransition(SUBMITTED, ACKED);
        OrderStateMachine.assertTransition(SUBMITTED, REJECTED);
        OrderStateMachine.assertTransition(SUBMIT_UNKNOWN, ACKED);
        OrderStateMachine.assertTransition(ACKED, PARTIALLY_FILLED);
        OrderStateMachine.assertTransition(ACKED, FILLED);
        OrderStateMachine.assertTransition(ACKED, CANCEL_PENDING);
        OrderStateMachine.assertTransition(PARTIALLY_FILLED, PARTIALLY_FILLED); // fill 갱신
        OrderStateMachine.assertTransition(PARTIALLY_FILLED, FILLED);
        OrderStateMachine.assertTransition(PARTIALLY_FILLED, CANCEL_PENDING);
        OrderStateMachine.assertTransition(CANCEL_PENDING, CANCEL_PENDING);     // fill 갱신 허용
        OrderStateMachine.assertTransition(CANCEL_PENDING, CANCELLED);
        OrderStateMachine.assertTransition(CANCEL_PENDING, FILLED);             // 체결 우선
    }

    @Test
    void backward_and_illegal_transitions_throw() {
        assertThatThrownBy(() -> OrderStateMachine.assertTransition(ACKED, NEW))
                .isInstanceOf(IllegalOrderTransitionException.class);
        assertThatThrownBy(() -> OrderStateMachine.assertTransition(FILLED, CANCELLED))
                .isInstanceOf(IllegalOrderTransitionException.class);
        assertThatThrownBy(() -> OrderStateMachine.assertTransition(CANCELLED, FILLED))
                .isInstanceOf(IllegalOrderTransitionException.class);  // 정정은 대사 점프가 아님 — adjustment 경로(§3)
        assertThatThrownBy(() -> OrderStateMachine.assertTransition(NEW, FILLED))
                .isInstanceOf(IllegalOrderTransitionException.class);  // 정상 경로엔 점프 없음
    }

    @Test
    void recovery_jump_allows_only_broker_observable_states() {
        OrderStateMachine.assertRecoveryJump(SUBMITTED, FILLED);        // 크래시 중 진행
        OrderStateMachine.assertRecoveryJump(NEW, ACKED);
        OrderStateMachine.assertRecoveryJump(SUBMIT_UNKNOWN, PARTIALLY_FILLED);
        OrderStateMachine.assertRecoveryJump(CANCEL_PENDING, FILLED);
        OrderStateMachine.assertRecoveryJump(SUBMITTED, REJECTED);      // 거부 응답 유실 후 관찰
        assertThatThrownBy(() -> OrderStateMachine.assertRecoveryJump(FILLED, ACKED))
                .isInstanceOf(IllegalOrderTransitionException.class);   // terminal에서 출발 금지
        assertThatThrownBy(() -> OrderStateMachine.assertRecoveryJump(ACKED, NEW))
                .isInstanceOf(IllegalOrderTransitionException.class);
        // rank만으로는 허용됐을 불가능 점프 — 명시적 허용표가 막아야 한다:
        assertThatThrownBy(() -> OrderStateMachine.assertRecoveryJump(PARTIALLY_FILLED, REJECTED))
                .isInstanceOf(IllegalOrderTransitionException.class);   // 체결된 주문이 거부될 수 없음
        assertThatThrownBy(() -> OrderStateMachine.assertRecoveryJump(ACKED, ABORTED))
                .isInstanceOf(IllegalOrderTransitionException.class);   // ack된 주문은 미송신일 수 없음
        assertThatThrownBy(() -> OrderStateMachine.assertRecoveryJump(CANCEL_PENDING, REJECTED))
                .isInstanceOf(IllegalOrderTransitionException.class);
    }

    @Test
    void terminal_set() {
        assertThat(FILLED.isTerminal()).isTrue();
        assertThat(CANCELLED.isTerminal()).isTrue();
        assertThat(REJECTED.isTerminal()).isTrue();
        assertThat(ABORTED.isTerminal()).isTrue();
        assertThat(CANCEL_PENDING.isTerminal()).isFalse();
        assertThat(SUBMIT_UNKNOWN.isTerminal()).isFalse();
    }
}
