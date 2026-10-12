package com.example.urlshortener.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** Registration no longer logs you in: the email address has to be verified first. */
@Getter
@AllArgsConstructor
public class RegisterResponse {
    private String message;
    private String email;
    private boolean verificationRequired;
}
