package com.example.urlshortener.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChangePlanRequest {
    @NotBlank(message = "plan is required")
    private String plan;
}
