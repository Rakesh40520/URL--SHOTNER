package com.example.urlshortener.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ShortenRequest {

    // NotBlank rejects null, "", and whitespace-only strings — covers the
    // {"url": ""} case explicitly. Size caps it well below the DB column
    // length (2048) so oversized payloads are rejected with a clear 400
    // instead of a truncation error further down. Pattern requires an
    // http/https scheme with a non-empty host, so things like "not a url",
    // "ftp://...", or "javascript:alert(1)" are all rejected.
    @NotBlank(message = "longUrl is required and must not be blank")
    @Size(max = 2048, message = "longUrl must be at most 2048 characters")
    @Pattern(
            regexp = "^(https?)://[^\\s/$.?#][^\\s]*$",
            message = "longUrl must be a valid http:// or https:// URL"
    )
    private String longUrl;

    // Optional: user-chosen alias, e.g. "my-portfolio". Length and allowed
    // characters are enforced here so nothing that would break the redirect
    // route's own {3,20} path pattern can ever reach the database.
    @Pattern(
            regexp = "^[a-zA-Z0-9_-]{3,20}$",
            message = "customAlias must be 3-20 characters: letters, digits, hyphen, or underscore only"
    )
    private String customAlias;

    // Optional: number of days until the link expires
    @Positive(message = "expiresInDays must be a positive number")
    private Integer expiresInDays;
}
