package com.rltrader.tradingcore.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class TradingGridTest {

    private static ZonedDateTime kst(String iso) {
        return ZonedDateTime.parse(iso + "+09:00[Asia/Seoul]");
    }

    @Test
    void gridHas76BarsFrom0905To1520() {
        assertThat(TradingGrid.offsetOf(LocalTime.of(9, 5))).hasValue(0);
        assertThat(TradingGrid.offsetOf(LocalTime.of(15, 20))).hasValue(75);
        assertThat(TradingGrid.offsetOf(LocalTime.of(9, 0))).isEmpty();   // grid 밖
        assertThat(TradingGrid.offsetOf(LocalTime.of(15, 25))).isEmpty();
        assertThat(TradingGrid.offsetOf(LocalTime.of(10, 7))).isEmpty();  // 5분 정렬 아님
    }

    @Test
    void barLabelForTruncatesToMinuteAndChecksGridAndWeekday() {
        // 2026-08-06 = 목요일. delay 20초 발화 → 라벨은 분 절단
        assertThat(TradingGrid.barLabelFor(kst("2026-08-06T09:05:20")))
                .contains(kst("2026-08-06T09:05:00"));
        assertThat(TradingGrid.barLabelFor(kst("2026-08-06T15:25:20"))).isEmpty(); // grid 밖
        assertThat(TradingGrid.barLabelFor(kst("2026-08-08T09:05:20"))).isEmpty(); // 토요일
    }

    @Test
    void barsBetweenSameDay() {
        // 같은 날 10:05 진입 → 11:00 현재: offset 12 → 23, 평일 1일
        assertThat(TradingGrid.barsBetween(
                kst("2026-08-06T10:05:00"), kst("2026-08-06T11:00:00"))).isEqualTo(11);
    }

    @Test
    void barsBetweenAcrossWeekend() {
        // 금(08-07) 15:20 진입 → 월(08-10) 09:05: 평일 2일(금·월) → (2-1)*76 + (0-75) = 1
        assertThat(TradingGrid.barsBetween(
                kst("2026-08-07T15:20:00"), kst("2026-08-10T09:05:00"))).isEqualTo(1);
    }

    @Test
    void barsBetweenMultiDay() {
        // 목(08-06) 09:05 → 금(08-07) 09:05: (2-1)*76 + 0 = 76
        assertThat(TradingGrid.barsBetween(
                kst("2026-08-06T09:05:00"), kst("2026-08-07T09:05:00"))).isEqualTo(76);
    }
}
