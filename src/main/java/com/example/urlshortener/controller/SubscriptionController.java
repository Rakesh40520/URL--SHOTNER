package com.example.urlshortener.controller;

import com.example.urlshortener.dto.ChangePlanRequest;
import com.example.urlshortener.dto.PlanResponse;
import com.example.urlshortener.dto.SubscriptionResponse;
import com.example.urlshortener.entity.User;
import com.example.urlshortener.service.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "Subscriptions", description = "Plans, usage and plan changes")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    @Operation(summary = "List all subscription plans (public)")
    @GetMapping("/api/v1/plans")
    public ResponseEntity<List<PlanResponse>> plans() {
        return ResponseEntity.ok(subscriptionService.listPlans());
    }

    @Operation(summary = "Current user's plan and this month's usage")
    @GetMapping("/api/v1/subscription")
    public ResponseEntity<SubscriptionResponse> mine(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(subscriptionService.getMySubscription(currentUser));
    }

    @Operation(summary = "Switch the current user's plan (demo checkout - no payment is taken)")
    @PostMapping("/api/v1/subscription")
    public ResponseEntity<SubscriptionResponse> change(@Valid @RequestBody ChangePlanRequest request,
                                                        @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(subscriptionService.changePlan(currentUser, request.getPlan()));
    }

    @Operation(summary = "Download all of the current user's links as CSV (Pro/Business)")
    @GetMapping("/api/v1/urls/my/export")
    public ResponseEntity<byte[]> export(HttpServletRequest httpRequest, @AuthenticationPrincipal User currentUser) {
        String baseUrl = ServletUriComponentsBuilder.fromRequestUri(httpRequest)
                .replacePath(null).replaceQuery(null).build().toUriString();
        byte[] body = subscriptionService.exportCsv(currentUser, baseUrl).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"my-dispatches.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(body);
    }
}
