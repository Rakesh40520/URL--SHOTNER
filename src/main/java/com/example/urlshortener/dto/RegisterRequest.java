package com.example.urlshortener.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterRequest {

    @NotBlank(message = "name is required")
    private String name;

    @NotBlank(message = "email is required")
    @Email(message = "email must be a valid email address")
    private String email;

    @NotBlank(message = "password is required")
    @Size(min = 8, message = "password must be at least 8 characters")
    private String password;

    // Compared against password in AuthService.register() - a mismatch
    // throws IllegalArgumentException, which GlobalExceptionHandler already
    // maps to 400.
    @NotBlank(message = "confirmPassword is required")
    private String confirmPassword;
}
