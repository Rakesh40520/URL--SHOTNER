package com.example.urlshortener.dto;

import com.example.urlshortener.entity.OtpPurpose;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SendOtpRequest {

    @NotBlank(message = "email is required")
    @Email(message = "must be a well-formed email address")
    @Schema(example = "you@example.com")
    private String email;

    @NotNull(message = "purpose is required")
    @Schema(example = "PASSWORD_RESET")
    private OtpPurpose purpose;
}
