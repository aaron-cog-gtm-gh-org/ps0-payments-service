package com.acme.payments;

import com.acme.commons.http.AcmeHttpClient;

public class LedgerClient {
    private final AcmeHttpClient http;

    public LedgerClient(AcmeHttpClient http) {
        this.http = http;
    }

    public PostingReceipt post(LedgerPosting posting) {
        return http.post("/v1/postings", posting, PostingReceipt.class);
    }
}
