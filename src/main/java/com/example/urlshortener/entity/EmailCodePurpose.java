package com.example.urlshortener.entity;

/** What an emailed one-time code is for. A code for one purpose can never be used for another. */
public enum EmailCodePurpose {
    VERIFY_EMAIL,
    RESET_PASSWORD
}
