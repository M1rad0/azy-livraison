package com.azylivraison.optimization.service;

import com.azylivraison.planning.domain.DeliveryPackage;
import com.azylivraison.planning.domain.Location;

record MultiClientOptimizationJob(
        String clientId,
        String clientName,
        String deliveryGroupId,
        String deliveryGroupName,
        Location pickup,
        DeliveryPackage deliveryPackage) {
}
