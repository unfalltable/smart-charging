ARG KEYCLOAK_IMAGE=quay.io/keycloak/keycloak:26.7.4

FROM ${KEYCLOAK_IMAGE} AS builder
ENV KC_DB=postgres
ENV KC_HEALTH_ENABLED=true
ENV KC_METRICS_ENABLED=true
WORKDIR /opt/keycloak
RUN /opt/keycloak/bin/kc.sh build

FROM ${KEYCLOAK_IMAGE}
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
