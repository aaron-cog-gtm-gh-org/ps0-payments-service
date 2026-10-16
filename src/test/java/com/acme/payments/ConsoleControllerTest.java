package com.acme.payments;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.commons.http.AcmeHttpClient;
import com.acme.commons.http.HttpClientFactory;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ConsoleControllerTest {
    private WireMockServer upstream;
    private AcmeHttpClient http;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstream.start();
        http = HttpClientFactory.create(upstream.baseUrl());
        mockMvc = controllerWith(http);
    }

    @AfterEach
    void tearDown() {
        http.close();
        upstream.stop();
    }

    @Test
    void proxiesRateFromFxGateway() throws Exception {
        upstream.stubFor(get(urlEqualTo("/v1/rates/EUR/USD"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"from":"EUR","to":"USD","rate":1.0852}
                                """)));

        mockMvc.perform(MockMvcRequestBuilders.get("/console/api/rates/EUR/USD"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.from").value("EUR"))
                .andExpect(jsonPath("$.to").value("USD"))
                .andExpect(jsonPath("$.rate").value(1.0852));

        upstream.verify(1, getRequestedFor(urlEqualTo("/v1/rates/EUR/USD")));
    }

    @Test
    void proxiesUntypedBalanceFromLedger() throws Exception {
        upstream.stubFor(get(urlEqualTo("/v1/accounts/ACC-100234/balance"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "accountId": "ACC-100234",
                                  "currency": "USD",
                                  "availableBalance": 1250.75,
                                  "ledgerBalance": 1300.00,
                                  "asOf": "2024-10-15T10:15:30Z"
                                }
                                """)));

        mockMvc.perform(MockMvcRequestBuilders.get("/console/api/accounts/ACC-100234/balance"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accountId").value("ACC-100234"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.availableBalance").value(1250.75))
                .andExpect(jsonPath("$.ledgerBalance").value(1300.00))
                .andExpect(jsonPath("$.asOf").value("2024-10-15T10:15:30Z"));

        upstream.verify(1, getRequestedFor(urlEqualTo("/v1/accounts/ACC-100234/balance")));
    }

    @Test
    void mapsUpstreamServiceUnavailableToBadGateway() throws Exception {
        upstream.stubFor(get(urlEqualTo("/v1/rates/EUR/USD"))
                .willReturn(aResponse().withStatus(503)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"Rate provider unavailable\"}")));

        mockMvc.perform(MockMvcRequestBuilders.get("/console/api/rates/EUR/USD"))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.upstreamStatus").value(503));

        upstream.verify(3, getRequestedFor(urlEqualTo("/v1/rates/EUR/USD")));
    }

    @Test
    void mapsConnectionRefusedToBadGateway() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        try (AcmeHttpClient refusedHttp = HttpClientFactory.create(
                "http://127.0.0.1:" + port, Duration.ofMillis(500))) {
            MockMvc refusedMvc = controllerWith(refusedHttp);

            refusedMvc.perform(MockMvcRequestBuilders.get("/console/api/accounts/ACC-100234/balance"))
                    .andExpect(status().isBadGateway())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.upstreamStatus").doesNotExist());
        }
    }

    private static MockMvc controllerWith(AcmeHttpClient client) {
        return MockMvcBuilders.standaloneSetup(
                new ConsoleController(new FxRateClient(client), new LedgerClient(client)))
                .build();
    }
}
