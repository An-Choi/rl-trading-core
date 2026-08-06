package com.rltrader.tradingcore.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * 5분 결정 grid: 09:05~15:20 KST, 76개 (spec §2).
 *
 * barsBetween은 spec §2 산식 그대로:
 *   (주말 제외 평일 수 - 1) × 76 + (당일 grid offset 차)
 * 공휴일 캘린더는 비목표 — 공휴일 하루당 +76 과대 계산을 수용한다 (clip이 상한).
 */
public final class TradingGrid {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    public static final LocalTime FIRST_BAR = LocalTime.of(9, 5);
    public static final LocalTime LAST_BAR = LocalTime.of(15, 20);
    public static final int BARS_PER_DAY = 76;

    private TradingGrid() {}

    public static OptionalInt offsetOf(LocalTime barLabel) {
        if (barLabel.isBefore(FIRST_BAR) || barLabel.isAfter(LAST_BAR)
                || barLabel.getMinute() % 5 != 0 || barLabel.getSecond() != 0) {
            return OptionalInt.empty();
        }
        long minutes = java.time.Duration.between(FIRST_BAR, barLabel).toMinutes();
        return OptionalInt.of((int) (minutes / 5));
    }

    /** 발화 시각(분 절단) → grid 소속·평일이면 bar 라벨. */
    public static Optional<ZonedDateTime> barLabelFor(ZonedDateTime firedAt) {
        ZonedDateTime inKst = firedAt.withZoneSameInstant(KST);
        if (isWeekend(inKst.toLocalDate())) {
            return Optional.empty();
        }
        ZonedDateTime label = inKst.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        return offsetOf(label.toLocalTime()).isPresent() ? Optional.of(label) : Optional.empty();
    }

    public static long barsBetween(ZonedDateTime entryBar, ZonedDateTime currentBar) {
        ZonedDateTime entry = entryBar.withZoneSameInstant(KST);
        ZonedDateTime current = currentBar.withZoneSameInstant(KST);
        if (current.isBefore(entry)) {
            throw new IllegalArgumentException(
                    "currentBar " + currentBar + " precedes entryBar " + entryBar);
        }
        int entryOffset = offsetOf(entry.toLocalTime()).orElseThrow(
                () -> new IllegalArgumentException("entry bar not on grid: " + entryBar));
        int currentOffset = offsetOf(current.toLocalTime()).orElseThrow(
                () -> new IllegalArgumentException("current bar not on grid: " + currentBar));
        long weekdays = countWeekdaysInclusive(entry.toLocalDate(), current.toLocalDate());
        return (weekdays - 1) * BARS_PER_DAY + (currentOffset - entryOffset);
    }

    private static boolean isWeekend(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    private static long countWeekdaysInclusive(LocalDate from, LocalDate to) {
        long count = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (!isWeekend(d)) {
                count++;
            }
        }
        return count;
    }
}
