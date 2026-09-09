# Kubernetes deployment templates

These are application templates, not a managed infrastructure installer. Provision Kafka, Redis, six independently owned
PostgreSQL databases and an OIDC realm first. Load/push each built image and set images through a Kustomize overlay.
Configure the actual issuer, JWKS, broker and Redis addresses in config.yml. Create one `{service}-database` Secret for
each stateful service with url/username/password keys and a simulator-credential Secret with key. Supply these from your
secret manager, never commit production values.

Render with `kubectl kustomize deploy/k8s`; apply with `kubectl apply -k deploy/k8s` only to the intended cluster.
Gateway access for a local cluster: `kubectl -n fulfillment port-forward service/api-gateway 8080:8080`. Configure the
OIDC redirect URI/CORS for your actual frontend origin. Templates run two instances to exercise local DB/Redis
coordination, have resource bounds, non-root users, read-only roots and health probes.

Production requires HTTPS ingress, private metrics access, network policies, authenticated Kafka and Redis, PostgreSQL
TLS, a production identity deployment, image digests/signing, workload-specific HPA/PDB settings, backups, and
replacement of the simulator. The local OIDC client and provider URL are intentionally not presented as production
configuration.
