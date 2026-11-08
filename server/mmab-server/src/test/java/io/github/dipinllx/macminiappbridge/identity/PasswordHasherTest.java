package io.github.dipinllx.macminiappbridge.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dipinllx.macminiappbridge.TestSupport;
import io.github.dipinllx.macminiappbridge.config.MmabProperties;
import io.github.dipinllx.macminiappbridge.protocol.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    @Test
    void hashVerifiesOnlyTheRightPassword() {
        char[] pw = Tokens.random().toCharArray();
        String hash = PasswordHasher.hash(pw, PasswordHasher.MIN_ITERATIONS);
        assertThat(hash).startsWith("pbkdf2-sha256$100000$").doesNotContain(new String(pw));
        assertThat(PasswordHasher.verify(pw, hash)).isTrue();
        assertThat(PasswordHasher.verify("wrong".toCharArray(), hash)).isFalse();
        assertThat(PasswordHasher.isWellFormed(hash)).isTrue();
        assertThat(PasswordHasher.isWellFormed("pbkdf2-sha256$10$AAAA$BBBB")).isFalse();
        assertThatThrownBy(() -> PasswordHasher.hash(pw, 1000)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void authenticatorRateLimitsAndRequiresSetup() {
        String hash = PasswordHasher.hash("right-one".toCharArray(), PasswordHasher.MIN_ITERATIONS);
        MmabProperties p = new MmabProperties(null, null, List.of(), null, null, null,
                new MmabProperties.Operator(hash), new MmabProperties.Testing(false, true), null);
        OperatorAuthenticator auth = new OperatorAuthenticator(p, TestSupport.clock());
        for (int i = 0; i < OperatorAuthenticator.MAX_FAILURES; i++) {
            assertThat(auth.verify("nope".toCharArray())).contains(ErrorCode.UNAUTHENTICATED);
        }
        assertThat(auth.verify("right-one".toCharArray())).as("locked even for the right password")
                .contains(ErrorCode.RATE_LIMITED);

        OperatorAuthenticator unset = new OperatorAuthenticator(TestSupport.properties(), TestSupport.clock());
        assertThat(unset.verify("x".toCharArray())).contains(ErrorCode.SETUP_REQUIRED);
    }
}
