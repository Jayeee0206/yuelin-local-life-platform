package com.yuelin.service.verification;

/**
 * Delivers a login verification code through the channel selected by the runtime profile.
 */
public interface VerificationCodeSender {

    void send(String recipient, String code);
}
