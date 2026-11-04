package com.azylivraison.dailyworkflow.api;

import java.time.LocalDate;

import com.azylivraison.dailyworkflow.api.DailyOperationResponse.DailyPackageResponse;
import com.azylivraison.dailyworkflow.repository.DailyWorkflowRepository.PostponedPackage;
import com.fasterxml.jackson.annotation.JsonProperty;

public record PostponedPackageResponse(
        LocalDate serviceDate, @JsonProperty("package") DailyPackageResponse deliveryPackage) {

    static PostponedPackageResponse from(PostponedPackage item) {
        return new PostponedPackageResponse(
                item.serviceDate(), DailyPackageResponse.from(item.deliveryPackage()));
    }
}
