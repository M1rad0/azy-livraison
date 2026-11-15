package com.azylivraison.delivery.api;

import java.math.BigDecimal;
import java.time.LocalTime;
import com.azylivraison.delivery.domain.DeliveryTimePreference;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record UpdateDeliveryPackageRequest(
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotBlank @Size(max = 200) String recipientName,
        @NotNull Boolean faritra,
        @NotNull @Valid ConfirmedLocationRequest destination,
        @NotBlank @Size(max = 100) String contact,
        @NotNull @PositiveOrZero Long amountToCollectAriary,
        @NotNull Boolean mobileMoneyPaid,
        LocalTime desiredDeliveryTime,
        @DecimalMin(value = "0", inclusive = false) @DecimalMax("9999999.999") BigDecimal weightKg,
        LocalTime desiredDeliveryEndTime,
        DeliveryTimePreference deliveryTimePreference,
        @Valid ConfirmedLocationRequest pickup) {

    public CreateDeliveryPackageRequest packageInput() {
        return new CreateDeliveryPackageRequest(recipientName, faritra, destination, contact,
                amountToCollectAriary, mobileMoneyPaid, desiredDeliveryTime, weightKg,
                desiredDeliveryEndTime, deliveryTimePreference);
    }
}
