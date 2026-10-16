package com.acme.payments;

import java.math.BigDecimal;

public record LedgerPosting(String reference, String debitAccount, String creditAccount,
                            BigDecimal amount, String currency, String narrative) {
}
