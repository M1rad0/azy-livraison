package com.azylivraison.account.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.azylivraison.account.api.UpdateClientContactNumbersRequest;
import com.azylivraison.account.domain.ClientContactNumber;

/** Shared rules for profile and administrator contact edits. */
public final class ClientContactNumberNormalizer {
    private ClientContactNumberNormalizer() {}

    public static List<ClientContactNumber> normalize(List<UpdateClientContactNumbersRequest.Item> items) {
        if (items.isEmpty() || items.stream().noneMatch(UpdateClientContactNumbersRequest.Item::active)) {
            throw new InvalidClientProfileException("Conservez au moins un numéro de contact actif.");
        }
        Set<UUID> ids = new HashSet<>();
        Set<String> numbers = new HashSet<>();
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).priority() == null || items.get(index).priority() != index + 1) {
                throw new InvalidClientProfileException("Les priorités doivent être uniques et ordonnées de 1 à " + items.size() + ".");
            }
        }
        return items.stream().map(item -> {
            UUID id = item.id() == null ? UUID.randomUUID() : item.id();
            if (!ids.add(id)) throw new InvalidClientProfileException("Un numéro de contact est présent plusieurs fois.");
            String number = item.number().trim();
            if (!numbers.add(number.replaceAll("\\s+", ""))) {
                throw new InvalidClientProfileException("Deux contacts utilisent le même numéro.");
            }
            String label = item.label() == null || item.label().isBlank() ? null : item.label().trim();
            return new ClientContactNumber(id, label, number, item.priority(), item.active());
        }).toList();
    }
}
