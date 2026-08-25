#!/bin/sh
set -eu

: "${APP_DOMAIN:?APP_DOMAIN is required}"

HTTP_TEMPLATE=/etc/nginx/edge-http.conf.template
HTTPS_TEMPLATE=/etc/nginx/edge-https.conf.template
OUTPUT=/etc/nginx/conf.d/default.conf
CERT_DIR="/etc/letsencrypt/live/${APP_DOMAIN}"

render_config() {
    if [ -s "${CERT_DIR}/fullchain.pem" ] && [ -s "${CERT_DIR}/privkey.pem" ]; then
        echo "[edge] TLS certificate found for ${APP_DOMAIN}; enabling HTTPS."
        envsubst '${APP_DOMAIN}' < "$HTTPS_TEMPLATE" > "$OUTPUT"
    else
        echo "[edge] No TLS certificate yet for ${APP_DOMAIN}; ACME HTTP bootstrap mode."
        envsubst '${APP_DOMAIN}' < "$HTTP_TEMPLATE" > "$OUTPUT"
    fi
}

certificate_hash() {
    if [ -s "${CERT_DIR}/fullchain.pem" ] && [ -s "${CERT_DIR}/privkey.pem" ]; then
        cat "${CERT_DIR}/fullchain.pem" "${CERT_DIR}/privkey.pem" | sha256sum | awk '{print $1}'
    fi
}

render_config
nginx -t

# Certbot renouvelle dans un autre container. Ce watcher détecte l'apparition
# ou le renouvellement du certificat et recharge Nginx sans redémarrer l'app.
(
    last_hash="$(certificate_hash || true)"
    while sleep 60; do
        new_hash="$(certificate_hash || true)"
        if [ -n "$new_hash" ] && [ "$new_hash" != "$last_hash" ]; then
            echo "[edge] TLS certificate changed; reloading Nginx."
            render_config
            if nginx -t; then
                nginx -s reload
                last_hash="$new_hash"
            fi
        fi
    done
) &

exec nginx -g 'daemon off;'
