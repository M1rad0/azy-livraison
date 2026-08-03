package com.azylivraison.routing.domain;

public record UnreachablePair(
        int fromIndex,
        int toIndex,
        String fromId,
        String toId) {
}
