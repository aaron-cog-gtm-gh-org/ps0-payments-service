package com.acme.payments;

import com.acme.commons.http.AcmeHttpClient;
import java.util.Map;

public class LedgerClient {
    private final AcmeHttpClient http;

    public LedgerClient(AcmeHttpClient http) {
        this.http = http;
    }

    public PostingReceipt post(LedgerPosting posting) {
        return http.post("/v1/postings", posting, PostingReceipt.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> balance(String accountId) {
        return http.get("/v1/accounts/" + accountId + "/balance", Map.class);
    }
}
