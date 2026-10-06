package com.example.urlshortener.dto;

/** One row of a "top N" breakdown, e.g. ("India", 12). */
public record LabelCount(String label, long count) {
}
