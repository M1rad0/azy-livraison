package com.azylivraison.delivery.api;

import java.math.BigDecimal;
import java.time.LocalTime;
import com.azylivraison.delivery.domain.DeliveryTimePreference;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateDeliveryPackageRequest(
        @NotBlank @Size(max = 200) String recipientName,
        @NotNull Boolean faritra,
        @NotNull @Valid ConfirmedLocationRequest destination,
        @NotBlank @Size(max = 100) String contact,
        @NotNull @PositiveOrZero Long amountToCollectAriary,
        @NotNull Boolean mobileMoneyPaid,
        LocalTime desiredDeliveryTime,
        @DecimalMin(value = "0", inclusive = false) @DecimalMax("9999999.999") BigDecimal weightKg,
        LocalTime desiredDeliveryEndTime,
        DeliveryTimePreference deliveryTimePreference) {

    public CreateDeliveryPackageRequest(String recipientName, Boolean faritra,
            ConfirmedLocationRequest destination, String contact, Long amountToCollectAriary,
            Boolean mobileMoneyPaid, LocalTime desiredDeliveryTime, BigDecimal weightKg) {
        this(recipientName, faritra, destination, contact, amountToCollectAriary, mobileMoneyPaid,
                desiredDeliveryTime, weightKg, null, null);
    }
}
