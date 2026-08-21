package com.example.receipt.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessRegistrationNumberValidatorTest {
    private final BusinessRegistrationNumberValidator validator = new BusinessRegistrationNumberValidator();

    @Test
    void validatesKoreanBusinessNumberChecksum() {
        assertThat(validator.isValid("220-81-62517")).isTrue();
        assertThat(validator.isValid("220-81-62518")).isFalse();
        assertThat(validator.isValid("000-00-00000")).isFalse();
    }
}
