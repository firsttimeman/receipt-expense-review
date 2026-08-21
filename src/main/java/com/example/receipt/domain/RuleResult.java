package com.example.receipt.domain;

public record RuleResult(String code, RuleOutcome outcome, String message) {
    public boolean failed() {
        return outcome == RuleOutcome.FAIL;
    }
}
