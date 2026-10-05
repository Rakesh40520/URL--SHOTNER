package com.example.urlshortener.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ReportRequest {

    // Optional - the endpoint works with just a shortCode and no body at
    // all. Capped well below typical abuse-form lengths since it's only
    // ever read by a human moderator, never rendered back into any page.
    @Size(max = 500, message = "reason must be at most 500 characters")
    private String reason;
}
