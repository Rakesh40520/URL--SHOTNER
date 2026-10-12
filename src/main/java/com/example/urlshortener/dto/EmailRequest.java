package com.example.urlshortener.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/** Body for "resend verification code" and "forgot password". */
@Getter
@Setter
public class EmailRequest {
    @NotBlank(message = "email is required")
    @Email(message = "email must be a valid email address")
    private String email;
}
