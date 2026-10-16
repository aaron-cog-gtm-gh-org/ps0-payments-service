package com.acme.payments;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SettlementController.class)
class SettlementControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SettlementService settlementService;

    @Test
    void settlesRequestAndReturnsPostingReceipt() throws Exception {
        when(settlementService.settle(any(SettlementRequest.class)))
                .thenReturn(new PostingReceipt("PST-000001", "accepted"));

        mockMvc.perform(post("/v1/settlements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reference": "ref-1",
                                  "debitAccount": "debit-1",
                                  "creditAccount": "credit-1",
                                  "amount": 100.00,
                                  "sourceCurrency": "USD",
                                  "settlementCurrency": "CAD"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.postingId").value("PST-000001"))
                .andExpect(jsonPath("$.status").value("accepted"));
    }

    @Test
    void mapsInvalidSettlementToBadRequest() throws Exception {
        when(settlementService.settle(any(SettlementRequest.class)))
                .thenThrow(new IllegalArgumentException("Settlement amount must exceed the fee"));

        mockMvc.perform(post("/v1/settlements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reference": "ref-1",
                                  "debitAccount": "debit-1",
                                  "creditAccount": "credit-1",
                                  "amount": 2.00,
                                  "sourceCurrency": "CAD",
                                  "settlementCurrency": "CAD"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("Settlement amount must exceed the fee"));
    }
}
