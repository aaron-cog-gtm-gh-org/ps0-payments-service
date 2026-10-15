package com.acme.payments;

import com.acme.commons.http.AcmeHttpClient;

public class FxRateClient {
    private final AcmeHttpClient http;

    public FxRateClient(AcmeHttpClient http) {
        this.http = http;
    }

    public FxRate getRate(String from, String to) {
        validateCurrencyCode(from);
        validateCurrencyCode(to);
        return http.get("/v1/rates/" + from + "/" + to, FxRate.class);
    }

    private static void validateCurrencyCode(String currency) {
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Currency code must be three uppercase letters");
        }
    }
}
