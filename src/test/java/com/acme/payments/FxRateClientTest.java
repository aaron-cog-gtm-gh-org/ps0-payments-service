package com.acme.payments;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.commons.http.AcmeHttpClient;
import com.acme.commons.http.AcmeHttpException;
import com.acme.commons.http.HttpClientFactory;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FxRateClientTest {
    private WireMockServer server;
    private AcmeHttpClient http;
    private FxRateClient client;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        http = HttpClientFactory.create(server.baseUrl());
        client = new FxRateClient(http);
    }

    @AfterEach
    void tearDown() {
        http.close();
        server.stop();
    }

    @Test
    void parsesRateAndIgnoresUnknownProperties() {
        server.stubFor(get(urlEqualTo("/v1/rates/USD/CAD"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"from":"USD","to":"CAD","rate":1.3655,"asOf":"2024-09-01"}
                                """)));

        FxRate rate = client.getRate("USD", "CAD");

        assertThat(rate.from()).isEqualTo("USD");
        assertThat(rate.to()).isEqualTo("CAD");
        assertThat(rate.rate().compareTo(new BigDecimal("1.3655"))).isZero();
        server.verify(getRequestedFor(urlEqualTo("/v1/rates/USD/CAD")));
    }

    @Test
    void throwsForNotFound() {
        server.stubFor(get(urlEqualTo("/v1/rates/USD/CAD"))
                .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> client.getRate("USD", "CAD"))
                .isInstanceOf(AcmeHttpException.class)
                .satisfies(error -> assertThat(((AcmeHttpException) error).status()).isEqualTo(404));
    }

    @Test
    void retriesServiceUnavailableTwice() {
        server.stubFor(get(urlEqualTo("/v1/rates/USD/CAD"))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client.getRate("USD", "CAD"))
                .isInstanceOf(AcmeHttpException.class)
                .satisfies(error -> assertThat(((AcmeHttpException) error).status()).isEqualTo(503));
        server.verify(3, getRequestedFor(urlEqualTo("/v1/rates/USD/CAD")));
    }

    @Test
    void wrapsSocketTimeoutAsUncheckedIoException() {
        server.stubFor(get(urlEqualTo("/v1/rates/USD/CAD"))
                .willReturn(aResponse().withFixedDelay(1000).withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"from\":\"USD\",\"to\":\"CAD\",\"rate\":1.0}")));
        try (AcmeHttpClient shortTimeoutHttp = HttpClientFactory.create(server.baseUrl(),
                Duration.ofMillis(200))) {
            FxRateClient shortTimeoutClient = new FxRateClient(shortTimeoutHttp);

            assertThatThrownBy(() -> shortTimeoutClient.getRate("USD", "CAD"))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasCauseInstanceOf(SocketTimeoutException.class);
        }
    }

    @Test
    void wrapsConnectionRefusedAsUncheckedIoException() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        try (AcmeHttpClient refusedHttp = HttpClientFactory.create(
                "http://localhost:" + port, Duration.ofMillis(500))) {
            FxRateClient refusedClient = new FxRateClient(refusedHttp);

            assertThatThrownBy(() -> refusedClient.getRate("USD", "CAD"))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasCauseInstanceOf(java.net.ConnectException.class);
        }
    }

    @Test
    void rejectsInvalidCurrencyWithoutRequest() {
        assertThatThrownBy(() -> client.getRate("usd", "CAD"))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify(0, getRequestedFor(urlEqualTo("/v1/rates/USD/CAD")));
    }
}
