package com.azylivraison.account.service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.azylivraison.account.api.UpdateClientPayoutDestinationsRequest;
import com.azylivraison.account.domain.ClientPayoutDestination;

/** Shared ordering and duplicate rules for the client profile and its administration form. */
public final class ClientPayoutDestinationNormalizer {
    private ClientPayoutDestinationNormalizer() {}

    public static List<ClientPayoutDestination> normalize(
            List<UpdateClientPayoutDestinationsRequest.Item> requestedItems) {
        Set<UUID> ids = new HashSet<>();
        Set<String> destinations = new HashSet<>();
        for (int index = 0; index < requestedItems.size(); index++) {
            var item = requestedItems.get(index);
            if (item.priority() == null || item.priority() != index + 1) {
                throw new InvalidClientProfileException(
                        "Les priorités doivent être uniques et ordonnées de 1 à " + requestedItems.size() + ".");
            }
        }
        return requestedItems.stream().map(item -> {
            UUID id = item.id() == null ? UUID.randomUUID() : item.id();
            if (!ids.add(id)) {
                throw new InvalidClientProfileException("Une destination de versement est présente plusieurs fois.");
            }
            String operator = item.operator().trim();
            String number = item.number().trim();
            String identity = operator.toLowerCase(Locale.ROOT) + "\u0000" + number;
            if (!destinations.add(identity)) {
                throw new InvalidClientProfileException(
                        "Deux destinations de versement utilisent le même opérateur et le même numéro.");
            }
            return new ClientPayoutDestination(
                    id, operator, item.accountHolder().trim(), number, item.priority(), item.active());
        }).toList();
    }
}
