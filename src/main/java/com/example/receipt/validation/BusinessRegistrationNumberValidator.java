package com.example.receipt.validation;

import org.springframework.stereotype.Component;

@Component
public class BusinessRegistrationNumberValidator {
    private static final int[] WEIGHTS = {1, 3, 7, 1, 3, 7, 1, 3, 5};

    public boolean isValid(String value) {
        if (value == null) {
            return false;
        }
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.length() != 10) {
            return false;
        }
        if (digits.chars().distinct().count() == 1) {
            return false;
        }
        int sum = 0;
        for (int index = 0; index < 9; index++) {
            sum += Character.digit(digits.charAt(index), 10) * WEIGHTS[index];
        }
        int ninthProduct = Character.digit(digits.charAt(8), 10) * 5;
        sum += ninthProduct / 10;
        int expectedCheckDigit = (10 - (sum % 10)) % 10;
        return expectedCheckDigit == Character.digit(digits.charAt(9), 10);
    }
}
