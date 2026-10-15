package com.acme.payments;

import java.math.BigDecimal;

public record SettlementRequest(String reference, String debitAccount, String creditAccount,
                               BigDecimal amount, String sourceCurrency, String settlementCurrency) {
}
