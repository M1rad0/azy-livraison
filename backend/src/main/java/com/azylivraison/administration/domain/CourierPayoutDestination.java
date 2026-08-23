package com.azylivraison.administration.domain;

public record CourierPayoutDestination(
        String operator,
        String accountHolder,
        String number) {
}
