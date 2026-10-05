package com.example.urlshortener.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@AllArgsConstructor
@Builder
public class ReportResponse {
    private String shortCode;
    private int reportCount;
    // True if this report pushed the link over app.report.auto-disable-threshold
    // and it just got auto-flagged as a result.
    private boolean autoFlagged;
}
