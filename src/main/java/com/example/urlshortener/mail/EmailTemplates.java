package com.example.urlshortener.mail;

import com.example.urlshortener.entity.EmailCodePurpose;
import org.springframework.web.util.HtmlUtils;

/** Subject + body text for the two code emails. Plain, inline-styled HTML that survives email clients. */
public final class EmailTemplates {

    private EmailTemplates() { }

    public record Message(String subject, String html, String text) { }

    public static Message codeEmail(EmailCodePurpose purpose, String name, String code, int minutes) {
        boolean verify = purpose == EmailCodePurpose.VERIFY_EMAIL;
        String subject = (verify ? "Verify your email" : "Reset your password") + " - your code is " + code;
        String intro = verify
                ? "Use this code to verify your email address and finish creating your account."
                : "Use this code to reset your password. If you didn't ask for this, you can ignore this email - your password won't change.";
        String greeting = name == null || name.isBlank() ? "Hi," : "Hi " + HtmlUtils.htmlEscape(name) + ",";

        String html = "<div style=\"font-family:Arial,Helvetica,sans-serif;max-width:480px;margin:0 auto;padding:24px;color:#211D18\">"
                + "<p style=\"font-size:13px;letter-spacing:2px;color:#B23A2E;margin:0 0 16px\">DISPATCH</p>"
                + "<p style=\"margin:0 0 12px\">" + greeting + "</p>"
                + "<p style=\"margin:0 0 20px\">" + intro + "</p>"
                + "<p style=\"font-size:34px;letter-spacing:10px;font-weight:bold;font-family:Courier New,monospace;"
                + "background:#F3EAD0;border:1px solid #D8C9A0;padding:16px 8px;text-align:center;margin:0 0 20px\">" + code + "</p>"
                + "<p style=\"margin:0 0 8px;font-size:14px\">This code expires in " + minutes + " minutes.</p>"
                + "<p style=\"margin:0;font-size:12px;color:#6B6258\">Never share this code with anyone.</p></div>";

        String text = (name == null || name.isBlank() ? "Hi," : "Hi " + name + ",") + "\n\n" + intro
                + "\n\nYour code: " + code + "\n\nIt expires in " + minutes + " minutes. Never share this code with anyone.\n";
        return new Message(subject, html, text);
    }
}
