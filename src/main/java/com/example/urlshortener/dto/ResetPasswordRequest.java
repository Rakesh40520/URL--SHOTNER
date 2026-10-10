package com.example.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ResetPasswordRequest {

    @NotBlank(message = "email is required")
    @Email(message = "must be a well-formed email address")
    @Schema(example = "you@example.com")
    private String email;

    @NotBlank(message = "otp is required")
    @Size(min = 6, max = 6, message = "OTP must be 6 digits")
    @Schema(example = "123456")
    private String otp;

    @NotBlank(message = "newPassword is required")
    @Size(min = 8, message = "password must be at least 8 characters")
    @Schema(example = "Secret123!")
    private String newPassword;

    @NotBlank(message = "confirmNewPassword is required")
    @Schema(example = "Secret123!")
    private String confirmNewPassword;
}
