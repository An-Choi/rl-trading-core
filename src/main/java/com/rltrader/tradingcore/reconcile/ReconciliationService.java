package com.rltrader.tradingcore.reconcile;

import com.rltrader.tradingcore.config.TradingProperties;
import com.rltrader.tradingcore.domain.OrderSide;
import com.rltrader.tradingcore.domain.OrderStatus;
import com.rltrader.tradingcore.domain.PortfolioState;
import com.rltrader.tradingcore.execution.ChainNext;
import com.rltrader.tradingcore.execution.OrderFinalizationService;
import com.rltrader.tradingcore.execution.TransitionMode;
import com.rltrader.tradingcore.halt.HaltService;
import com.rltrader.tradingcore.kis.BrokerPort;
import com.rltrader.tradingcore.kis.KisBalance;
import com.rltrader.tradingcore.kis.KisOrderSnapshot;
import com.rltrader.tradingcore.kis.KisTransportException;
import com.rltrader.tradingcore.ledger.AlertRecorder;
import com.rltrader.tradingcore.ledger.DecisionEntity;
import com.rltrader.tradingcore.ledger.DecisionRepository;
import com.rltrader.tradingcore.ledger.ExecutionAdjustmentEntity;
import com.rltrader.tradingcore.ledger.ExecutionAdjustmentRepository;
import com.rltrader.tradingcore.ledger.OrderEntity;
import com.rltrader.tradingcore.ledger.OrderEventEntity;
import com.rltrader.tradingcore.ledger.OrderEventRepository;
import com.rltrader.tradingcore.ledger.OrderRepository;
import com.rltrader.tradingcore.ledger.PortfolioSnapshotEntity;
import com.rltrader.tradingcore.ledger.PortfolioSnapshotRepository;
import com.rltrader.tradingcore.portfolio.PortfolioManager;
import com.rltrader.tradingcore.scheduler.CycleAdmissionGate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 2레벨 대사(spec §6) — 주문 레벨(비종결 관찰 점프 + 지연 체결 정정) / 잔고 레벨(shares·cash).
 * OrderLifecycleService가 아니라 공유 컴포넌트(OrderFinalizationService)만 소비한다 — 순환 의존
 * 차단(P1-3, 브리프 Interfaces 블록). terminal 소유권은 finalization 경로가 유지하되, CANCELLED→
 * FILLED 지연 체결 정정만 spec §3이 명시한 단일 예외로 이 클래스가 직접 커밋한다
 * (correctCancelledToFilled — 아래 상세 주석).
 */
@Component
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final Set<OrderStatus> NON_TERMINAL =
            EnumSet.complementOf(EnumSet.of(OrderStatus.FILLED, OrderStatus.CANCELLED,
                    OrderStatus.REJECTED, OrderStatus.ABORTED));
    private static final int SCALE = 4;

    private final BrokerPort broker;
    private final OrderRepository orders;
    private final DecisionRepository decisions;
    private final OrderFinalizationService finalization;
    private final OrderEventRepository events;
    private final ExecutionAdjustmentRepository adjustments;
    private final PortfolioSnapshotRepository snapshots;
    private final PortfolioManager portfolio;
    private final HaltService halts;
    private final AlertRecorder alerts;
    private final CycleAdmissionGate gate;
    private final ReconciliationRepository reconciliations;
    private final TradingProperties tradingProps;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public ReconciliationService(BrokerPort broker, OrderRepository orders,
            DecisionRepository decisions, OrderFinalizationService finalization,
            OrderEventRepository events, ExecutionAdjustmentRepository adjustments,
            PortfolioSnapshotRepository snapshots, PortfolioManager portfolio, HaltService halts,
            AlertRecorder alerts, CycleAdmissionGate gate, ReconciliationRepository reconciliations,
            TradingProperties tradingProps, TransactionTemplate tx, Clock clock,
            ObjectMapper strictMapper, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.broker = broker;
        this.orders = orders;
        this.decisions = decisions;
        this.finalization = finalization;
        this.events = events;
        this.adjustments = adjustments;
        this.snapshots = snapshots;
        this.portfolio = portfolio;
        this.halts = halts;
        this.alerts = alerts;
        this.gate = gate;
        this.reconciliations = reconciliations;
        this.tradingProps = tradingProps;
        this.tx = tx;
        this.clock = clock;
        this.mapper = strictMapper;
        this.jdbc = jdbc;
    }

    /**
     * 진입점. PERIODIC만 CycleAdmissionGate를 탄다 — 실패 시 기록 없이 즉시 반환(브리프 계약).
     * POST_ORDER/STARTUP/RECONNECT는 호출자가 이미 단일 진행을 보장한다고 가정한다
     * (POST_ORDER는 사이클 완료 직후, 같은 스케줄러 tick 안에서 순차 호출 — 별도 게이트 불필요).
     */
    public void reconcile(Trigger trigger) {
        if (trigger == Trigger.PERIODIC) {
            if (!gate.tryAcquire()) {
                return;   // 사이클 진행 중 — 기록 없이 skip (브리프 계약)
            }
            try {
                runBothLevels(trigger);
            } finally {
                gate.release();
            }
        } else {
            runBothLevels(trigger);
        }
    }

    private void runBothLevels(Trigger trigger) {
        boolean adjustmentApplied = reconcileOrders(trigger);
        reconcileBalance(trigger, adjustmentApplied);
    }

    /**
     * 주문 레벨만 — Task 14 boot recovery(spec §7 ⑤) 전용 공개 진입점. 부팅 중 artifact 판정(⑦)이
     * 끝나기 전에는 잔고 레벨(reconcileBalance)을 부르면 안 된다: 원본 스냅숏(복구 전, artifact
     * 불일치일 수 있는 상태)의 로컬 shares를 KIS와 비교해 버리면 아직 확정되지 않은 mismatch를
     * RECON_UNEXPLAINED로 오판·HALT할 수 있다(⑦이 내려야 할 판정을 ⑤가 가로채는 순서 오류).
     * ⑨(최종 대사)는 여전히 {@link #reconcile(Trigger)}(order+balance 둘 다)를 그대로 쓴다 —
     * reconcileOrders는 매 호출마다 "ORDER" 레벨 reconciliations 행을 남기므로(공유 로직 재사용,
     * 별도 무기록 경로 신설 안 함) 정상 라이브 기동은 STARTUP 트리거로 이 행이 두 번(⑤에서 한 번,
     * ⑨에서 재확인 한 번) 남는다 — 둘 다 감사 기록으로 유효하므로 억제하지 않는다.
     */
    public void reconcileOrdersOnly(Trigger trigger) {
        reconcileOrders(trigger);
    }

    // ---------------------------------------------------------------- 주문 레벨

    /**
     * 비종결 주문마다 KIS 조회 → 관찰 점프로 확정, CANCELLED 주문마다 지연 체결 정정 여부 확인.
     * @return 이번 호출에서 adjustment를 하나라도 적용했는지 — 잔고 레벨의 "설명 가능" 판정에 쓰인다.
     */
    private boolean reconcileOrders(Trigger trigger) {
        if (trigger == Trigger.STARTUP) {
            // Task 14, spec §7 ⑤ — kis_order_no가 아직 없는(NEW·SUBMIT_UNKNOWN) 비종결 주문은
            // 아래 메인 루프의 조회 대상이 아니다(주문번호가 없으면 broker.orderSnapshot을 부를
            // 수 없다). 크래시 시점이 "제출 직후·커밋 직전"이었을 수 있으므로(부재는 증명이
            // 아니다 — spec §3), 먼저 dailyOrders 차집합으로 채택을 시도한다: 성공하면
            // kis_order_no가 채워져 바로 아래 메인 루프가 그대로 관찰 점프로 마무리한다(중복
            // 구현 없이 흡수) — 실패(미발견·모호)면 재전송 없이 즉시 HALT + 기동 중단.
            resolveOrphanedOrders();
        }
        List<Map<String, Object>> detail = new ArrayList<>();
        boolean anySync = false;

        for (OrderEntity order : orders.findByStatusIn(NON_TERMINAL)) {
            if (order.getKisOrderNo() == null) {
                continue;   // 아직 제출조차 확인 안 된 주문(NEW) — 조회 대상 아님
            }
            KisOrderSnapshot snap = fetchSnapshot(order);
            if (snap == null) {
                continue;
            }
            // Fix A(2차 리뷰) — org no가 아직 없는(복구·채택된) 주문을 이 조회로 관찰하면 엔티티에
            // 반영한다. 저장은 여기서 직접 하지 않는다 — 아래 세 분기(finalizeObservedFill/
            // finalizeObservedTerminalRemainder/requestCancel의 취소 가드)가 곧바로 이어지며,
            // 앞의 둘은 finalizeChain의 기존 orders.save에 얹혀가고, requestCancel은 이 값을 바로
            // 사용한다(새 tx 없음, 브리프 지시).
            if (order.getKrxFwdgOrdOrgno() == null && snap.krxFwdgOrdOrgno() != null) {
                order.setKrxFwdgOrdOrgno(snap.krxFwdgOrdOrgno());
            }
            // 리뷰 수정(Important) — FILLED는 filled_qty == qty일 때만이다(ck_orders_filled_terminal
            // CHECK가 이를 강제). 이전엔 brokerTerminal && !cancelled만으로 FILLED 판정해, IOC/FOK 등
            // 브로커가 "더 이상 진행 안 함"을 잔량 미체결인 채 종결로 보고하는 경우 DB CHECK 위반으로
            // tx가 그대로 롤백됐다 — 전량 체결이 아니면 항상 아래 "종결·잔량 있음" 경로로 보낸다.
            boolean fullyFilled = snap.cumulativeFilledQty() == order.getQty();
            if (fullyFilled) {
                finalizeObservedFill(order, snap);
                detail.add(Map.of("orderId", order.getId(), "action", "FINALIZED_FILLED"));
                anySync = true;
            } else if (snap.brokerTerminal()) {
                finalizeObservedTerminalRemainder(order, snap);
                detail.add(Map.of("orderId", order.getId(), "action", "FINALIZED_CANCELLED"));
                anySync = true;
            } else {
                // 미체결 잔존 — 브로커에서 여전히 살아있는 주문. 취소 요청으로 수렴시킨다
                // (취소 확정은 이후 폴링/대사 회차가 관찰 점프로 마무리한다).
                requestCancel(order);
                detail.add(Map.of("orderId", order.getId(), "action", "CANCEL_REQUESTED"));
                anySync = true;
            }
        }

        boolean adjustmentApplied = reconcileCancelledAdjustments(detail);
        anySync = anySync || adjustmentApplied;

        record(trigger, "ORDER", anySync ? "SYNCED" : "CLEAN", Map.of("orders", detail));
        return adjustmentApplied;
    }

    private KisOrderSnapshot fetchSnapshot(OrderEntity order) {
        try {
            return broker.orderSnapshot(order.getKisOrderNo(), order.getTradingDate()).orElse(null);
        } catch (KisTransportException e) {
            log.warn("[RECON] snapshot fetch failed for order {} — skip this pass", order.getId(), e);
            return null;
        }
    }

    /**
     * Fix A(2차 리뷰) 취소 시점 가드 — OrderLifecycleService.cancelOrThrow와 동일 규칙: 공식
     * 정정취소 계약상 KRX_FWDG_ORD_ORGNO는 필수·비공백이므로 없으면 공백을 전송하지 않는다.
     * 대사 경로는 폴링 루프가 아니라 주기 회차이므로 예외를 던지지 않고(그러면 이 회차의 나머지
     * 주문 처리가 막힌다) HALT만 발생시키고 다음 회차로 넘어간다 — 주문은 비종결로 남는다.
     */
    private void requestCancel(OrderEntity order) {
        if (order.getKrxFwdgOrdOrgno() == null || order.getKrxFwdgOrdOrgno().isBlank()) {
            halts.raise("CANCEL_ORGNO_MISSING", Map.of(
                    "orderId", order.getId(), "kisOrderNo", order.getKisOrderNo()));
            log.error("[RECON] cancel skipped for order {} — krxFwdgOrdOrgno missing (HALT raised)",
                    order.getId());
            return;
        }
        try {
            broker.cancelOrder(order.getKisOrderNo(), order.getQty() - order.getFilledQty(),
                    order.getKrxFwdgOrdOrgno());
        } catch (KisTransportException e) {
            log.warn("[RECON] cancel request failed for order {} — retry next pass", order.getId(), e);
        }
    }

    /**
     * Task 14, spec §7 ⑤ — kis_order_no가 아직 없는 비종결 주문(NEW·SUBMIT_UNKNOWN)의 해소.
     * Task 11 resolveSubmitUnknown(OrderLifecycleService)과 동일한 차집합 절차를 재사용한다(브리프
     * 지시 — "동일한 해소 절차", 별도 매칭 규칙 신설 금지): 그 주문의 tradingDate 기준
     * dailyOrders 조회 → 로컬 orders가 이미 아는(그 날짜의) kis_order_no를 뺀 차집합.
     * 1개 = 채택(kis_order_no 기록 + RECOVERY 전이로 ACKED 커밋) — 이후 이 메서드를 호출한
     * reconcileOrders의 메인 루프가 그대로 이어받아 관찰 점프(FILLED/CANCELLED)로 마무리한다
     * (finalizeObservedFill/finalizeObservedTerminalRemainder 재사용, 중복 구현 없음).
     * 0개 또는 2개 이상 = 미발견·모호 — 재전송 없이 즉시 HaltService.raise + 예외로 기동을 중단한다.
     * 폴링 반복은 하지 않는다(Task 11의 submitUnknownMaxLookups 루프는 진행 중인 라이브 체인의
     * "잠시 후 나타날 수 있음"을 기다리는 것이지만, 부팅은 새 프로세스 시작 시점의 일회성
     * 판정이다 — 여기서 발견 안 되면 사람이 봐야 한다).
     */
    private void resolveOrphanedOrders() {
        List<OrderEntity> orphans = orders.findByStatusIn(NON_TERMINAL).stream()
                .filter(o -> o.getKisOrderNo() == null)
                .toList();
        for (OrderEntity order : orphans) {
            Set<String> known = knownKisOrderNos(order.getTradingDate());
            List<KisOrderSnapshot> daily = broker.dailyOrders(order.getTradingDate());
            List<KisOrderSnapshot> unknown = daily.stream()
                    .filter(s -> s.kisOrderNo() != null && !known.contains(s.kisOrderNo()))
                    .toList();
            if (unknown.size() == 1) {
                KisOrderSnapshot adopted = unknown.get(0);
                order.setKisOrderNo(adopted.kisOrderNo());
                // Fix A(2차 리뷰) — 제출 ack 없이 부팅 시점 조회로만 알게 된 주문이므로 org no의
                // 유일한 출처는 이 스냅숏이다.
                order.setKrxFwdgOrdOrgno(adopted.krxFwdgOrdOrgno());
                order.setSubmittedAt(OffsetDateTime.now(clock));
                finalization.transition(order, OrderStatus.ACKED,
                        Map.of("kisOrderNo", adopted.kisOrderNo(), "adoptedAtBoot", true),
                        TransitionMode.RECOVERY);
            } else {
                String reason = unknown.isEmpty() ? "not found at boot"
                        : "ambiguous: " + unknown.size() + " unmatched orders";
                halts.raise("SUBMIT_UNKNOWN", Map.of(
                        "decisionId", order.getDecisionId(), "orderId", order.getId(),
                        "tradingDate", order.getTradingDate().toString(), "reason", reason));
                throw new IllegalStateException(
                        "boot recovery: unresolved order " + order.getId() + " (" + reason + ")");
            }
        }
    }

    /** 당일(그 주문의 tradingDate) 로컬 orders가 아는(non-null) kis_order_no 집합 — 차집합의 기준. */
    private Set<String> knownKisOrderNos(LocalDate tradingDate) {
        Set<String> known = new HashSet<>();
        for (OrderEntity o : orders.findAll()) {
            if (tradingDate.equals(o.getTradingDate()) && o.getKisOrderNo() != null) {
                known.add(o.getKisOrderNo());
            }
        }
        return known;
    }

    /**
     * 관찰 점프(FILLED, filled_qty == qty) — 공유 finalization 경로, RECOVERY 모드(spec §6
     * Interfaces 블록). FILLED는 체인이 항상 끝나는 경우이므로 OrderLifecycleService.finalizeFilled와
     * 동일하게 chain-end 3분기(BUY→units+1, SELL→units=0·entryBarTs=null)를 그대로 적용한다 —
     * 리뷰 수정: 이전엔 units를 전혀 건드리지 않아, 대사가 관찰한 ghost fill이 정상 체결 경로와
     * 다른 포트폴리오 상태로 이어지는 회귀가 있었다.
     */
    private void finalizeObservedFill(OrderEntity order, KisOrderSnapshot snap) {
        KisBalance balance = balanceOrNull();
        if (balance == null) {
            return;   // 잔고 확정 실패 — 이번 회차는 건너뛰고 비종결 유지 (다음 대사가 재시도)
        }
        long delta = snap.cumulativeFilledQty() - order.getAccountedFilledQty();
        PortfolioState after = applyObservedFill(order, delta, balance, ChainEnd.of(order));
        // RECOVERY 모드에서 ctx는 finalizeChain 내부에서 절대 역참조되지 않는다
        // (OrderFinalizationService.saveSnapshot의 RECOVERY 분기는 기존 snapshot의 artifact_id를
        // 보존하거나 decision의 artifact_id로 대체한다 — ctx.artifactId() 호출 경로는 NORMAL 전용).
        finalization.finalizeChain(order, OrderStatus.FILLED, snap, after,
                ChainNext.markCompleted(), null, TransitionMode.RECOVERY);
    }

    /**
     * 관찰 점프(종결·잔량 있음) — brokerTerminal이지만 filled_qty == qty가 아닌 모든 경우
     * (명시적 취소 확정뿐 아니라 IOC/FOK 등 브로커가 잔량을 남긴 채 더 이상 진행하지 않는 경우도
     * 포함, Important 리뷰 수정). 이 메서드는 reconcileOrders의 fullyFilled==false 분기에서만
     * 호출되므로 remaining(=qty−filled)은 side와 무관하게 항상 > 0이다. OrderLifecycleService의
     * 체인 종료 분기(§9~10)와 동일한 side별 give-up 시맨틱을 대사 경로에서도 재현한다(Critical
     * 리뷰 수정):
     *
     * <ul>
     *   <li>SELL(잔량은 항상 > 0): 이월(liquidation) — ChainNext.liquidation()으로
     *       liquidationPending=true, execution_status는 IN_PROGRESS 유지, units/entryBarTs는
     *       보존(ChainEnd.NOT_ENDING — 체인이 끝나지 않았다). LiquidationService.resume()이
     *       다음 세션에 재개. SELL이 여기서 "전량 소진"으로 체인을 끝내는 경로는 존재하지 않는다
     *       — filled==qty였다면 fullyFilled 분기가 먼저 finalizeObservedFill(FILLED)로 보냈을
     *       것이다(그 경로의 SELL 처리는 ChainEnd.SELL_CLOSE로 별도 커버).</li>
     *   <li>BUY(잔량 무관, 항상 여기서 체인 종료): add 포기 — ChainNext.markCompleted() +
     *       decisionTotalQty 기준 PARTIAL_UNIT/EXECUTION_FAILED 알림, decisionTotalQty > 0이면
     *       units +1(첫 진입 시 entryBarTs를 decision의 원 bar_ts로 세팅 — OrderLifecycleService.
     *       applyFill과 동일 규칙, 과거 bar로 재진입 판정하지 않는다).</li>
     * </ul>
     *
     * decisionTotalQty는 OrderLifecycleService와 동일하게 OrderRepository.sumAccountedFilledQtyByDecisionId
     * 를 재사용한다(재도출 금지, 브리프 지시) — 단, 이 order 자신의 accounted는 아직 canonical로
     * 갱신되기 전(finalizeChain 내부에서 갱신됨)이므로 합계에서 자신의 stale 값을 빼고 canonical로
     * 대체해야 한다.
     */
    private void finalizeObservedTerminalRemainder(OrderEntity order, KisOrderSnapshot snap) {
        KisBalance balance = balanceOrNull();
        if (balance == null) {
            return;
        }
        long delta = snap.cumulativeFilledQty() - order.getAccountedFilledQty();
        long remaining = order.getQty() - snap.cumulativeFilledQty();
        long decisionTotalQty = orders.sumAccountedFilledQtyByDecisionId(order.getDecisionId())
                - order.getAccountedFilledQty() + snap.cumulativeFilledQty();

        if (order.getSide() == OrderSide.SELL) {
            // 이 메서드는 fullyFilled(filled_qty == qty)가 아닐 때만 호출된다(reconcileOrders) —
            // 즉 remaining = qty - filled_qty는 이 지점에서 항상 > 0이다(SELL이 remaining == 0으로
            // 도달할 방법이 없다: filled == qty였다면 fullyFilled 분기가 먼저 finalizeObservedFill로
            // 보냈을 것이다). 그래서 SELL은 여기서 무조건 이월(liquidation) — 체인이 끝나지 않는다.
            PortfolioState after = applyObservedFill(order, delta, balance, ChainEnd.NOT_ENDING);
            finalization.finalizeChain(order, OrderStatus.CANCELLED, snap, after,
                    ChainNext.liquidation(), null, TransitionMode.RECOVERY);
            return;
        }

        // 이 지점 도달 = BUY 체인 종료(add 포기, 잔량 유무 무관 — 재시도는 정상 경로 전담).
        // decisionTotalQty > 0(체인 전체에 걸쳐 조금이라도 체결이 있었음)일 때만 units+1 — 0이면
        // (완전 포기) chain-end지만 진입 판정 자체가 성립하지 않으므로 NOT_ENDING과 동일하게 취급
        // (OrderLifecycleService.applyFill의 decisionTotalQty<=0 조기 반환과 동일 규칙).
        ChainEnd chainEnd = decisionTotalQty > 0 ? ChainEnd.BUY_ENTRY : ChainEnd.NOT_ENDING;
        PortfolioState after = applyObservedFill(order, delta, balance, chainEnd);
        finalization.finalizeChain(order, OrderStatus.CANCELLED, snap, after,
                ChainNext.markCompleted(), null, TransitionMode.RECOVERY);

        ZonedDateTime barTs = decisionBarTs(order.getDecisionId());
        if (decisionTotalQty > 0) {
            alerts.record("PARTIAL_UNIT", barTs, Map.of(
                    "decisionId", order.getDecisionId(), "filledQty", decisionTotalQty,
                    "orderedQty", order.getQty(), "source", "RECONCILE"));
        } else {
            alerts.record("EXECUTION_FAILED", barTs, Map.of(
                    "decisionId", order.getDecisionId(), "orderedQty", order.getQty(),
                    "source", "RECONCILE"));
        }
    }

    /** decision의 원 bar_ts(첫 unit 진입 시각 세팅용, alert 채널용) — OffsetDateTime → ZonedDateTime(KST). */
    private ZonedDateTime decisionBarTs(long decisionId) {
        DecisionEntity d = decisions.findById(decisionId).orElseThrow();
        return d.getBarTs().atZoneSameInstant(com.rltrader.tradingcore.domain.TradingGrid.KST);
    }

    /**
     * OrderLifecycleService.applyFill의 chainEnding 3분기를 그대로 재현하는 열거값 — 리뷰 수정
     * (Important, round 3): 이전엔 "BUY chain-end → units+1"만 재현하고 "SELL chain-end → units=0
     * ·entryBarTs=null(무조건 리셋)"이 빠져 있었다. applyFill과 동일한 이름의 분기를 여기 열거값
     * 이름에 그대로 반영해 두 구현이 나중에 다시 벌어지지 않게 한다(공유 헬퍼로 완전히 합치지 않은
     * 이유는 OrderLifecycleService.applyFill이 barTs/currentAttemptQty 등 대사에는 없는 인자에
     * 의존해 시그니처를 공유하기 어렵기 때문 — 대신 이 3-state 규칙만 별도로 추출해 양쪽이 같은
     * 이름의 분기를 갖도록 맞췄다. fix report에 이 선택 이유를 기록).
     */
    private enum ChainEnd {
        /** 체인이 이번 호출로 끝나지 않음(중간 이월 등) — units/entryBarTs 완전 보존. */
        NOT_ENDING,
        /** BUY 체인 종료, 첫 진입이면 entryBarTs를 decision 원 bar_ts로 세팅 — units +1. */
        BUY_ENTRY,
        /** SELL 체인 종료(전량 청산) — units=0, entryBarTs=null 무조건 리셋(OrderLifecycleService.
         *  applyFill의 isSell && chainEnding 분기와 동일, deltaQty·decisionTotalQty와 무관). */
        SELL_CLOSE;

        /** FILLED 관찰(항상 체인 종료)의 side별 매핑 — BUY_ENTRY/SELL_CLOSE 둘 중 하나. */
        static ChainEnd of(OrderEntity order) {
            return order.getSide() == OrderSide.BUY ? BUY_ENTRY : SELL_CLOSE;
        }
    }

    private PortfolioState applyObservedFill(OrderEntity order, long deltaQty, KisBalance balance,
            ChainEnd chainEnd) {
        PortfolioState before = portfolio.current();
        double signedDelta = deltaQty <= 0 ? 0
                : (order.getSide() == OrderSide.SELL ? -deltaQty : deltaQty);
        double newShares = before.shares() + signedDelta;
        double cash = balance.cashBalance().doubleValue();
        return switch (chainEnd) {
            case NOT_ENDING -> new PortfolioState(before.units(), newShares, cash, before.entryBarTs());
            case SELL_CLOSE -> new PortfolioState(0, newShares, cash, null);
            case BUY_ENTRY -> {
                ZonedDateTime entry = before.units() == 0
                        ? decisionBarTs(order.getDecisionId()) : before.entryBarTs();
                yield new PortfolioState(before.units() + 1, newShares, cash, entry);
            }
        };
    }

    private KisBalance balanceOrNull() {
        try {
            return broker.balance();
        } catch (KisTransportException e) {
            log.warn("[RECON] balance fetch failed — skip this order's finalization this pass", e);
            return null;
        }
    }

    /**
     * 지연 체결 정정 — CANCELLED인데 KIS 누적 체결이 accounted를 초과하는 경우(spec §3 명시 규칙).
     * delta > 0일 때만 lock 하에: adjustment INSERT + orders.accounted 갱신 + snapshot delta 반영,
     * 한 tx. 같은 스냅숏으로 재실행하면 delta==0이 되어 아무 것도 하지 않는다(멱등).
     */
    private boolean reconcileCancelledAdjustments(List<Map<String, Object>> detail) {
        boolean applied = false;
        for (OrderEntity cancelled : orders.findByStatusIn(List.of(OrderStatus.CANCELLED))) {
            if (cancelled.getKisOrderNo() == null) {
                continue;
            }
            KisOrderSnapshot snap = fetchSnapshot(cancelled);
            if (snap == null || snap.cumulativeAvgPrice() == null) {
                continue;
            }
            long canonicalQty = snap.cumulativeFilledQty();
            if (canonicalQty <= cancelled.getAccountedFilledQty()) {
                continue;   // delta <= 0 — 이미 반영됐거나 관찰값이 뒤처짐, 정정 대상 아님
            }
            BigDecimal toNotional = snap.cumulativeAvgPrice()
                    .multiply(BigDecimal.valueOf(canonicalQty))
                    .setScale(SCALE, RoundingMode.HALF_UP);
            boolean didApply = applyAdjustmentTx(cancelled.getId(), canonicalQty, toNotional);
            if (didApply) {
                applied = true;
                detail.add(Map.of("orderId", cancelled.getId(), "action", "ADJUSTMENT"));
            }
        }
        return applied;
    }

    /**
     * lock 하 재확인 + adjustment INSERT + orders.accounted 갱신 + snapshot delta + (정정 후
     * filled==qty면) correctCancelledToFilled — 전부 한 tx. 커밋 성공 직후 메모리 갱신
     * (portfolio.apply)까지 이 메서드가 책임진다(spec §2 "커밋 후 메모리" 규칙, finalization과 동일 패턴).
     *
     * <p>Fix 2 — chain-completion semantics (스펙 §4: clear 체인은 잔량0·이월·HALT 외로 끝나지
     * 않는다 — 전량 도달 = 잔량 0 종료). correctCancelledToFilled는 order 상태만 고치던 이전
     * 구현은 여기서 끝났지만, 그 order가 SELL이고 이월 중(snapshot.liquidationPending=true)이면
     * 이 지연 체결이 사실은 clear 체인의 완결이다 — 상태만 고치고 units/entryBarTs/liquidationPending/
     * decision을 그대로 두면 잔량 0인데 이월 플래그가 영구히 켜진 채로 남는다(스펙 위반, 클린업
     * 경로 없음). 같은 tx에서 units=0·entryBarTs=null·liquidationPending=false·decision COMPLETED까지
     * 커밋한다.
     *
     * <p>BUY 대응 사례 — zero-fill로 give-up한(units 그대로, decisionTotal 0) BUY가 뒤늦게 이
     * 정정으로 decisionTotal 0→양수가 되면 "units=0인데 shares>0"이라는 계약에 없는 조합이
     * 생긴다(OrderLifecycleService.applyFill의 chain-end 규칙상 이 조합은 정상 경로에서 나올 수
     * 없다). 같은 tx에서 units를 보정(+1, 최초 진입이면 entryBarTs도 decision bar로 세팅)하고
     * PARTIAL_UNIT 알림을 남긴다.
     */
    private boolean applyAdjustmentTx(long orderId, long canonicalQty, BigDecimal toNotional) {
        record Result(boolean applied, PortfolioState after, boolean sellChainCompleted,
                       boolean buyUnitCorrected, long decisionId) {}
        Result result = tx.execute(status -> {
            OrderEntity locked = orders.findByIdForUpdate(orderId).orElseThrow();
            long delta = canonicalQty - locked.getAccountedFilledQty();
            if (delta <= 0) {
                return new Result(false, null, false, false, 0);   // 동시 대사 회차가 이미 반영 — 멱등
            }
            BigDecimal fromNotional = locked.getAccountedFillNotional();
            BigDecimal notionalDelta = toNotional.subtract(fromNotional).setScale(SCALE, RoundingMode.HALF_UP);

            ExecutionAdjustmentEntity adj = new ExecutionAdjustmentEntity();
            adj.setOrderId(orderId);
            adj.setFromFilledQty(locked.getAccountedFilledQty());
            adj.setToFilledQty(canonicalQty);
            adj.setFromFillNotional(fromNotional);
            adj.setToFillNotional(toNotional);
            adj.setQtyDelta(delta);
            adj.setNotionalDelta(notionalDelta);
            adj.setReason("RECONCILE_LATE_FILL");
            adjustments.save(adj);

            long decisionTotalQtyBefore = orders.sumAccountedFilledQtyByDecisionId(locked.getDecisionId());
            long decisionTotalQtyAfter = decisionTotalQtyBefore - locked.getAccountedFilledQty() + canonicalQty;

            locked.setAccountedFilledQty(canonicalQty);
            locked.setAccountedFillNotional(toNotional);
            locked.setFilledQty(Math.max(locked.getFilledQty(), canonicalQty));
            orders.save(locked);

            PortfolioState before = portfolio.current();
            double signedDelta = locked.getSide() == OrderSide.SELL ? -delta : delta;
            double newShares = before.shares() + signedDelta;

            boolean reachedFull = canonicalQty == locked.getQty();
            PortfolioSnapshotEntity snapshotEntity = snapshots.findById(locked.getSymbol()).orElseThrow();
            boolean sellChainCompletes = reachedFull && locked.getSide() == OrderSide.SELL
                    && snapshotEntity.isLiquidationPending();
            boolean buyUnitNeedsCorrection = reachedFull && locked.getSide() == OrderSide.BUY
                    && before.units() == 0 && decisionTotalQtyBefore <= 0 && decisionTotalQtyAfter > 0;

            PortfolioState after;
            if (sellChainCompletes) {
                after = new PortfolioState(0, newShares, before.cash(), null);
            } else if (buyUnitNeedsCorrection) {
                ZonedDateTime entry = decisionBarTs(locked.getDecisionId());
                after = new PortfolioState(1, newShares, before.cash(), entry);
            } else {
                after = new PortfolioState(before.units(), newShares, before.cash(), before.entryBarTs());
            }

            snapshotEntity.setShares(after.shares());
            snapshotEntity.setUpdatedAt(OffsetDateTime.now(clock));
            if (sellChainCompletes) {
                snapshotEntity.setUnits(0);
                snapshotEntity.setEntryBarTs(null);
                snapshotEntity.setLiquidationPending(false);
            } else if (buyUnitNeedsCorrection) {
                snapshotEntity.setUnits(1);
                snapshotEntity.setEntryBarTs(after.entryBarTs().toOffsetDateTime());
            }
            snapshots.save(snapshotEntity);

            if (reachedFull) {
                correctCancelledToFilled(locked);
            }
            if (sellChainCompletes) {
                var d = decisions.findById(locked.getDecisionId()).orElseThrow();
                d.setExecutionStatus("COMPLETED");
                decisions.save(d);
            }
            return new Result(true, after, sellChainCompletes, buyUnitNeedsCorrection, locked.getDecisionId());
        });
        if (result.applied()) {
            portfolio.apply(result.after());   // 커밋 성공 직후에만 메모리 반영 (spec §2)
            if (result.buyUnitCorrected()) {
                ZonedDateTime barTs = decisionBarTs(result.decisionId());
                alerts.record("PARTIAL_UNIT", barTs, Map.of(
                        "decisionId", result.decisionId(), "source", "RECONCILE_LATE_FILL_UNIT_CORRECTION"));
            }
        }
        return result.applied();
    }

    /**
     * spec §3 명시 규칙 — CANCELLED(terminal)→FILLED 정정. 상태머신 우회가 아니다: 일반 관찰
     * 점프(RECOVERY jump)는 "마지막 커밋 상태 → 관찰 상태" 전방 직행만 허용하고 terminal에서는
     * 출발할 수 없다(OrderStateMachine.RECOVERY 맵에 CANCELLED가 출발점으로 없음) — 그러나 지연
     * 체결 정정은 예외적으로 이미 종결된 CANCELLED 주문이 사실은 전량 체결이었음을 뒤늦게
     * 발견하는 경우이므로, 이 adjustment 경로에서만 직접 상태를 갱신한다(OrderStateMachine을
     * 거치지 않음 — 브리프가 명시적으로 지시한 별도 메서드). order_events에도 그 사실을 남긴다.
     * applyAdjustmentTx의 트랜잭션 안에서만 호출되어야 한다(별도 tx 아님 — 원자성 보장).
     */
    private void correctCancelledToFilled(OrderEntity order) {
        OrderEventEntity e = new OrderEventEntity();
        e.setOrderId(order.getId());
        e.setFromStatus(OrderStatus.CANCELLED.name());
        e.setToStatus(OrderStatus.FILLED.name());
        e.setDetail(mapper.writeValueAsString(Map.of(
                "note", "late-fill adjustment correction — spec §3 explicit exception, not a state machine bypass",
                "reason", "RECONCILE_LATE_FILL")));
        events.save(e);
        order.setStatus(OrderStatus.FILLED);
        order.setTerminalAt(OffsetDateTime.now(clock));
        orders.save(order);
    }

    // ---------------------------------------------------------------- 잔고 레벨

    /**
     * shares: 로컬(PortfolioManager) == accounting_ledger 합(±side) == KIS holdingQty.
     * cash: 로컬 cash vs KIS 예수금 — 불일치면 무조건(설명 가능성 무관) 절대값 SYNC(spec §6).
     * shares 불일치는 비종결 주문 존재 또는 이번 회차 adjustment 적용(explained)이면 SYNCED,
     * 그렇지 않으면 HaltService.raise("RECON_UNEXPLAINED", ...) + UNEXPLAINED 기록.
     */
    private void reconcileBalance(Trigger trigger, boolean adjustmentAppliedThisPass) {
        KisBalance kis;
        try {
            kis = broker.balance();
        } catch (KisTransportException e) {
            log.warn("[RECON] balance fetch failed — balance-level reconciliation skipped this pass", e);
            record(trigger, "BALANCE", "UNEXPLAINED", Map.of("reason", "balance fetch failed"));
            return;
        }

        PortfolioState local = portfolio.current();
        BigDecimal ledgerSum = ledgerShareSum();
        BigDecimal localShares = BigDecimal.valueOf(local.shares());
        BigDecimal kisShares = BigDecimal.valueOf(kis.holdingQty());

        boolean sharesClean = localShares.compareTo(ledgerSum) == 0 && localShares.compareTo(kisShares) == 0;
        String sharesResult;
        if (sharesClean) {
            sharesResult = "CLEAN";
        } else {
            boolean explainable = adjustmentAppliedThisPass || !orders.findByStatusIn(NON_TERMINAL).isEmpty();
            if (explainable) {
                sharesResult = "SYNCED";
            } else {
                sharesResult = "UNEXPLAINED";
                halts.raise("RECON_UNEXPLAINED", Map.of(
                        "localShares", localShares, "ledgerSum", ledgerSum, "kisHoldingQty", kisShares));
            }
        }

        BigDecimal localCash = BigDecimal.valueOf(local.cash());
        BigDecimal kisCash = kis.cashBalance();
        boolean cashClean = localCash.compareTo(kisCash) == 0;
        String cashResult = "CLEAN";
        if (!cashClean) {
            syncCashFromBalance(kisCash);
            cashResult = "SYNCED";
        }

        String overall = worst(sharesResult, cashResult);
        record(trigger, "BALANCE", overall, Map.of(
                "shares", Map.of("result", sharesResult, "local", localShares,
                        "ledger", ledgerSum, "kis", kisShares),
                "cash", Map.of("result", cashResult, "local", localCash, "kis", kisCash)));
    }

    /**
     * cash 불일치 → 예수금 절대값으로 snapshot·메모리 동기화(단방향 로컬←KIS, spec §6). 한 tx.
     *
     * <p>snapshot 행이 없으면(최초 live 기동 — 아직 어떤 finalization도 없음) DB는 건드리지 않고
     * **메모리만** 동기화한다. 행 생성은 finalization의 소유(첫 거래에서 생성)이고, 여기서 행을
     * 만들면 artifact provenance 등 finalization의 규칙을 대사가 복제하게 된다. 재기동 시 flat
     * 복원 → 이 동기화가 다시 도는 것은 멱등·무해하다. (2026-08-13 기동 스모크 실측 결함 수정 —
     * 이전 코드는 행 존재를 전제(orElseThrow)해 최초 기동을 죽였다.)
     */
    private void syncCashFromBalance(BigDecimal kisCash) {
        PortfolioState before = portfolio.current();
        PortfolioState next = new PortfolioState(before.units(), before.shares(),
                kisCash.doubleValue(), before.entryBarTs());
        var existing = snapshots.findById(tradingProps.symbol());
        if (existing.isEmpty()) {
            portfolio.apply(next);   // 커밋할 것이 없으므로 메모리 직접 갱신
            return;
        }
        PortfolioState after = tx.execute(status -> {
            PortfolioSnapshotEntity snapshotEntity = existing.get();
            snapshotEntity.setCash(next.cash());
            snapshotEntity.setUpdatedAt(OffsetDateTime.now(clock));
            snapshots.save(snapshotEntity);
            return next;
        });
        portfolio.apply(after);   // 커밋 성공 직후 메모리 갱신 (spec §2)
    }

    /** accounting_ledger는 order_id로만 연결된다 — 이 심볼의 orders에 조인해 합산(단일 심볼 전제, 미래 다심볼 대비). */
    private BigDecimal ledgerShareSum() {
        BigDecimal sum = jdbc.queryForObject("""
            SELECT COALESCE(SUM(l.qty_delta), 0) FROM accounting_ledger l
            JOIN orders o ON o.id = l.order_id WHERE o.symbol = ?""",
                BigDecimal.class, tradingProps.symbol());
        return sum == null ? BigDecimal.ZERO : sum;
    }

    private String worst(String a, String b) {
        if ("UNEXPLAINED".equals(a) || "UNEXPLAINED".equals(b)) {
            return "UNEXPLAINED";
        }
        if ("SYNCED".equals(a) || "SYNCED".equals(b)) {
            return "SYNCED";
        }
        return "CLEAN";
    }

    private void record(Trigger trigger, String level, String result, Map<String, Object> detail) {
        ReconciliationEntity e = new ReconciliationEntity();
        e.setTriggerSource(trigger.name());
        e.setLevel(level);
        e.setResult(result);
        e.setDetail(mapper.writeValueAsString(detail));
        reconciliations.save(e);
    }
}
