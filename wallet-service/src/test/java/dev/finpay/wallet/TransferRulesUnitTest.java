package dev.finpay.wallet;

import dev.finpay.wallet.core.TransferRules;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

@Tag("unit")
class TransferRulesUnitTest {
    @ParameterizedTest
    @CsvSource({"1,1,0,OK", "5000000,5000000,0,OK", "0,100,0,INVALID_AMOUNT",
            "-1,100,0,INVALID_AMOUNT", "5000001,10000000,0,HIGH_RISK_AMOUNT",
            "100,99,0,INSUFFICIENT_FUNDS", "100,100,99999999900,OK",
            "100,100,99999999901,BALANCE_LIMIT", "1,100,9223372036854775807,BALANCE_LIMIT"})
    void rejection_boundaryCases_returnsExpectedDecision(long amount, long source, long target, String expected) {
        // Given
        String expectedReason = "OK".equals(expected) ? null : expected;

        // When
        String reason = TransferRules.rejection(amount, source, target);

        // Then
        assertThat(reason).isEqualTo(expectedReason);
    }

    @Test
    void fundedBalance_validFunding_addsExactMinorUnits() {
        // Given
        long balance = 100;

        // When
        long funded = TransferRules.fundedBalance(balance, 250);

        // Then
        assertThat(funded).isEqualTo(350);
    }

    @ParameterizedTest
    @CsvSource({"0,0", "0,-1", "0,5000001", "100000000000,1", "9223372036854775807,1"})
    void fundedBalance_invalidFunding_rejectsWithoutOverflow(long balance, long amount) {
        // Given
        var operation = (org.assertj.core.api.ThrowableAssert.ThrowingCallable) () -> TransferRules.fundedBalance(balance, amount);

        // When / Then
        assertThatThrownBy(operation).isInstanceOf(IllegalArgumentException.class);
    }
}
