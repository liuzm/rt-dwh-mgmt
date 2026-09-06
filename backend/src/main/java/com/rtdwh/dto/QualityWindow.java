package com.rtdwh.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Business-local, half-open interval; no implicit UTC conversion. */
public record QualityWindow(LocalDateTime start, LocalDateTime end) {
    public QualityWindow {
        if (start == null || end == null || !end.isAfter(start)
                || start.getYear() < 1000 || end.getYear() > 9999) {
            throw new IllegalArgumentException("业务窗口必须具有有效的开始、结束时间，且结束晚于开始");
        }
    }
    public static QualityWindow forDate(LocalDate date) {
        return date == null ? null : new QualityWindow(date.atStartOfDay(), date.plusDays(1).atStartOfDay());
    }
    public String key() { return "business_window:" + start + "/" + end; }
    public String predicate(String quotedColumn) {
        var format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
        return quotedColumn + " >= '" + start.format(format) + "' AND "
                + quotedColumn + " < '" + end.format(format) + "'";
    }
}
