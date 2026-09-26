package com.yuelin.service.verification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Local-only sender. It avoids requiring real SMTP credentials for development and demos.
 */
@Slf4j
@Component
@Profile("dev")
public class DevelopmentVerificationCodeSender implements VerificationCodeSender {

    @Override
    public void send(String recipient, String code) {
        log.info("[DEV ONLY] verification code for {} is {}", mask(recipient), code);
    }

    private String mask(String recipient) {
        if (recipient == null || recipient.length() < 4) {
            return "***";
        }
        int at = recipient.indexOf('@');
        if (at > 1) {
            return recipient.charAt(0) + "***" + recipient.substring(at);
        }
        return recipient.substring(0, 2) + "***" + recipient.substring(recipient.length() - 2);
    }
}
