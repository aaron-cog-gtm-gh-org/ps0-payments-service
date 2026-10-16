package com.acme.payments;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.commons.http.AcmeHttpClient;
import com.acme.commons.http.AcmeHttpException;
import com.acme.commons.http.HttpClientFactory;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.math.BigDecimal;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SettlementServiceTest {
    private WireMockServer fxServer;
    private WireMockServer ledgerServer;
    private AcmeHttpClient fxHttp;
    private AcmeHttpClient ledgerHttp;
    private SettlementService settlementService;

    @BeforeEach
    void setUp() {
        fxServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        ledgerServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        fxServer.start();
        ledgerServer.start();
        fxHttp = HttpClientFactory.create(fxServer.baseUrl());
        ledgerHttp = HttpClientFactory.create(ledgerServer.baseUrl());
        settlementService = new SettlementService(new FxRateClient(fxHttp),
                new LedgerClient(ledgerHttp));
    }

    @AfterEach
    void tearDown() {
        fxHttp.close();
        ledgerHttp.close();
        fxServer.stop();
        ledgerServer.stop();
    }

    @Test
    void convertsDeductsFeeAndPostsNetAmount() {
        fxServer.stubFor(get(urlEqualTo("/v1/rates/USD/CAD"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"from\":\"USD\",\"to\":\"CAD\",\"rate\":1.3655}")));
        stubLedgerReceipt();

        PostingReceipt receipt = settlementService.settle(request("1000.00", "USD", "CAD"));

        assertThat(receipt).isEqualTo(new PostingReceipt("post-1", "accepted"));
        ledgerServer.verify(postRequestedFor(urlEqualTo("/v1/postings"))
                .withRequestBody(equalToJson("""
                        {
                          "reference": "ref-1",
                          "debitAccount": "debit-1",
                          "creditAccount": "credit-1",
                          "amount": 1359.59,
                          "currency": "CAD",
                          "narrative": "Settlement ref-1: 1000.00 USD @ 1.3655, fee 2.50 + fx 3.41 CAD"
                        }
                        """)));
    }

    @Test
    void roundsFxFeeHalfEvenBeforeSubtractingBothFees() {
        FxRateClient fxRateClient = mock(FxRateClient.class);
        LedgerClient ledgerClient = mock(LedgerClient.class);
        when(fxRateClient.getRate("USD", "CAD"))
                .thenReturn(new FxRate("USD", "CAD", new BigDecimal("1.25")));
        when(ledgerClient.post(any(LedgerPosting.class)))
                .thenReturn(new PostingReceipt("post-1", "accepted"));
        SettlementService service = new SettlementService(fxRateClient, ledgerClient);

        service.settle(request("1000.00", "USD", "CAD"));

        ArgumentCaptor<LedgerPosting> posting = ArgumentCaptor.forClass(LedgerPosting.class);
        verify(ledgerClient).post(posting.capture());
        assertThat(posting.getValue().amount()).isEqualTo(new BigDecimal("1244.38"));
        assertThat(posting.getValue().narrative())
                .contains("fee 2.50 + fx 3.12 CAD");
    }

    @Test
    void roundsHalfEvenBeforeSubtractingFee() {
        fxServer.stubFor(get(urlEqualTo("/v1/rates/USD/CAD"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"from\":\"USD\",\"to\":\"CAD\",\"rate\":1.1}")));
        stubLedgerReceipt();

        settlementService.settle(request("10.15", "USD", "CAD"));

        ledgerServer.verify(postRequestedFor(urlEqualTo("/v1/postings"))
                .withRequestBody(equalToJson("""
                        {
                          "reference": "ref-1",
                          "debitAccount": "debit-1",
                          "creditAccount": "credit-1",
                          "amount": 8.63,
                          "currency": "CAD",
                          "narrative": "Settlement ref-1: 10.15 USD @ 1.1, fee 2.50 + fx 0.03 CAD"
                        }
                        """)));
    }

    @Test
    void skipsFxForSameCurrency() {
        stubLedgerReceipt();

        settlementService.settle(request("500.00", "CAD", "CAD"));

        fxServer.verify(0, getRequestedFor(urlEqualTo("/v1/rates/CAD/CAD")));
        ledgerServer.verify(postRequestedFor(urlEqualTo("/v1/postings"))
                .withRequestBody(equalToJson("""
                        {
                          "reference": "ref-1",
                          "debitAccount": "debit-1",
                          "creditAccount": "credit-1",
                          "amount": 497.50,
                          "currency": "CAD",
                          "narrative": "Settlement ref-1: 500.00 CAD @ 1, fee 2.50 + fx 0.00 CAD"
                        }
                        """)));
    }

    @Test
    void rejectsSettlementThatDoesNotExceedFees() {
        fxServer.stubFor(get(urlEqualTo("/v1/rates/USD/CAD"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"from\":\"USD\",\"to\":\"CAD\",\"rate\":1.25}")));

        assertThatThrownBy(() -> settlementService.settle(request("2.00", "USD", "CAD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Settlement amount must exceed the fees");
        ledgerServer.verify(0, postRequestedFor(urlEqualTo("/v1/postings")));
    }

    @Test
    void propagatesFxFailureWithoutPostingToLedger() {
        fxServer.stubFor(get(urlEqualTo("/v1/rates/USD/CAD"))
                .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> settlementService.settle(request("100.00", "USD", "CAD")))
                .isInstanceOf(AcmeHttpException.class)
                .satisfies(error -> assertThat(((AcmeHttpException) error).status()).isEqualTo(404));
        ledgerServer.verify(0, postRequestedFor(urlEqualTo("/v1/postings")));
    }

    private void stubLedgerReceipt() {
        ledgerServer.stubFor(post(urlEqualTo("/v1/postings"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"postingId\":\"post-1\",\"status\":\"accepted\"}")));
    }

    private static SettlementRequest request(String amount, String source, String settlement) {
        return new SettlementRequest("ref-1", "debit-1", "credit-1",
                new BigDecimal(amount), source, settlement);
    }
}
