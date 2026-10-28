package com.azylivraison.geography.api;

import java.util.UUID;

import com.azylivraison.delivery.domain.DeliveryRequestKind;
import com.azylivraison.geography.domain.GeocodingPurpose;
import com.azylivraison.geography.service.GeocodingService;
import com.azylivraison.geography.service.GeocodingSearchScope;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/geocoding")
public class GeocodingController {

    private final GeocodingService geocodingService;

    public GeocodingController(GeocodingService geocodingService) {
        this.geocodingService = geocodingService;
    }

    @GetMapping("/search")
    public GeocodingSearchResponse search(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(defaultValue = "GENERAL") GeocodingPurpose purpose,
            @RequestParam(defaultValue = "NORMAL") DeliveryRequestKind requestKind,
            @RequestParam(required = false) UUID clientAccountId,
            @AuthenticationPrincipal Jwt jwt) {
        GeocodingSearchScope scope = purpose == GeocodingPurpose.DESTINATION
                ? GeocodingSearchScope.destination(
                        requestKind,
                        effectiveClientId(clientAccountId, jwt))
                : GeocodingSearchScope.general();
        return GeocodingSearchResponse.from(geocodingService.search(query, scope));
    }

    private UUID effectiveClientId(UUID requestedClientId, Jwt jwt) {
        String role = jwt == null ? null : jwt.getClaimAsString("role");
        if ("ADMIN".equals(role)) {
            return requestedClientId;
        }
        if ("CLIENT".equals(role)) {
            UUID accountId = UUID.fromString(jwt.getClaimAsString("accountId"));
            if (requestedClientId != null && !requestedClientId.equals(accountId)) {
                throw new AccessDeniedException("Cette grille tarifaire n'est pas accessible.");
            }
            return accountId;
        }
        if (requestedClientId != null) {
            throw new AccessDeniedException("Authentification administrateur requise.");
        }
        return null;
    }
}
