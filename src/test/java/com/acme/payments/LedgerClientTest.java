package com.acme.payments;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.commons.http.AcmeHttpClient;
import com.acme.commons.http.AcmeHttpException;
import com.acme.commons.http.HttpClientFactory;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LedgerClientTest {
    private WireMockServer server;
    private AcmeHttpClient http;
    private LedgerClient client;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        http = HttpClientFactory.create(server.baseUrl());
        client = new LedgerClient(http);
    }

    @AfterEach
    void tearDown() {
        http.close();
        server.stop();
    }

    @Test
    void postsJsonAndParsesReceipt() {
        server.stubFor(post(urlEqualTo("/v1/postings"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(equalToJson("""
                        {
                          "reference": "pay-123",
                          "debitAccount": "customer-1",
                          "creditAccount": "merchant-2",
                          "amount": 1363.00,
                          "currency": "CAD",
                          "narrative": "Settlement pay-123"
                        }
                        """))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"postingId\":\"post-456\",\"status\":\"accepted\"}")));

        PostingReceipt receipt = client.post(new LedgerPosting("pay-123", "customer-1",
                "merchant-2", new BigDecimal("1363.00"), "CAD", "Settlement pay-123"));

        assertThat(receipt).isEqualTo(new PostingReceipt("post-456", "accepted"));
        server.verify(postRequestedFor(urlEqualTo("/v1/postings")));
    }

    @Test
    void throwsForUnprocessableEntity() {
        server.stubFor(post(urlEqualTo("/v1/postings"))
                .willReturn(aResponse().withStatus(422)));

        assertThatThrownBy(() -> client.post(samplePosting()))
                .isInstanceOf(AcmeHttpException.class)
                .satisfies(error -> assertThat(((AcmeHttpException) error).status()).isEqualTo(422));
    }

    @Test
    void doesNotRetryServiceUnavailable() {
        server.stubFor(post(urlEqualTo("/v1/postings"))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client.post(samplePosting()))
                .isInstanceOf(AcmeHttpException.class)
                .satisfies(error -> assertThat(((AcmeHttpException) error).status()).isEqualTo(503));
        server.verify(1, postRequestedFor(urlEqualTo("/v1/postings")));
    }

    private static LedgerPosting samplePosting() {
        return new LedgerPosting("pay-123", "customer-1", "merchant-2",
                new BigDecimal("100.00"), "CAD", "Settlement pay-123");
    }
}
