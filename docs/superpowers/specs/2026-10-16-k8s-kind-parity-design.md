# Kubernetes parity: the same fleet on kind, at more than one replica

Status: approved in conversation 2026-10-16, written for review. Target release `0.3.0`.

## Goal

Prove the fleet works outside the laptop's host processes: the same images, the same configuration
names and the same money path, on Kubernetes, with every scalable service at two or more replicas,
autoscaling on, and a chaos monkey that can be switched on and off. One sweep run is the proof, and
its log is committed.

## Success criteria

1. `./k8s/kube.sh sweep` on a machine with Docker, kind and kubectl builds the images, creates the
   cluster, deploys, and exits 0 only when every check below passed. Its log, with each check's
   exit code, is committed under `docs/k8s/verification/`.
2. A deposit, quote and conversion through the gateway completes, and ledger-svc holds the posting.
3. gateway-svc, ledger-svc and conversion-svc each run 2 replicas, and each replica serves traffic.
4. Killing any pod mid-traffic leaves no duplicate posting or conversion, and the merchant-facing
   error count stays inside the budget stated by the check (section 6).
5. A ledger-svc outage pages through Grafana alerting (the notification counter moves), when a
   Slack webhook is configured; without one, the rule reaching Alerting is the asserted end.
6. The chaos monkey ships paused, and `./k8s/kube.sh chaos on|off|run` is documented in
   `docs/k8s.md`, linked from the README.
7. Nothing in the repository names any other project as the source of these patterns.

## 1. Code changes that more than one replica needs

A sweep of `services/*/src/main/java` and the served configuration (2026-10-16) for per-replica
state found the business state already shared: rate-limit buckets are Bucket4j compare-and-swap in
Redis; the pending sweeper claims rows with `FOR UPDATE SKIP LOCKED`; idempotency is a database
unique constraint on every write path; merchant accounts open with `ON CONFLICT DO NOTHING` then
`FOR UPDATE`; metric identity is `HOSTNAME:port:PID`; dashboards read the database-derived gauge with
`max` and the store probe with `min`; the gRPC channel's 30s connection age lets a new ledger replica
in. These stay as they are.

What breaks at two or more replicas under Kubernetes churn (kills, rolling updates, scale-in):

| # | Defect | Severity | Fix |
|---|---|---|---|
| R1 | A pod that dies without deregistering stays in Eureka for the default 90s lease, and longer while self-preservation holds, so the gateway's `lb://` routes and conversion's gRPC resolver keep sending calls to an address nothing answers | Critical | Lease renewal 5s and expiry 15s on every client; server eviction every 5s and self-preservation off (a five-service fleet is exactly where self-preservation misjudges a kill as a network partition); gateway retry on connection failure only, never on a request that reached a service |
| R2 | A pod that is stopped on purpose (rolling update, scale-in) is still in the registry while it shuts down | High | `server.shutdown: graceful`, a `preStop` sleep so the registry and client caches drop the pod before the process stops accepting, and `terminationGracePeriodSeconds` above the sum |
| R3 | Liquibase holds its lock as a row in `DATABASECHANGELOGLOCK`. Two replicas start at once and one migrates; if that one is SIGKILLed mid-migration the row stays locked, and every later pod of the service waits on it forever | High | A Liquibase `LockService` on a PostgreSQL session advisory lock (`pg_advisory_lock`), released by the database when the connection dies. The published extension for this targets Liquibase 4 (last release 1.6.9, 2023) and this build runs 5.0.x, so the lock service is ours: one class, registered through `META-INF/services`, in a new shared source directory `persistence/` compiled into ledger-svc and conversion-svc only, the way `observability/` is compiled into all five (the other three have no Liquibase on the classpath) |
| R4 | `SERVER_ADDRESS` is both the bind address and the address registered in Eureka, with a loopback default | Config | Kubernetes sets it to the pod IP from the downward API. No code change; recorded so nobody "fixes" the loopback default |
| R5 | Resilience4j breaker and retry state is per replica | Accepted | Each caller's breaker measures its own calls; a ledger outage opens every replica's breaker within its own window. Marked `[!REPLICA-LOCAL]` on the configuration with that verdict |
| R6 | config-svc and eureka-svc are single-instance by design (Eureka here is not peer-aware) | Gate | replicas 1 and no autoscaler, enforced by a check, not a comment |

Each fix lands red first: a test that fails on the current code, then the fix.

## 2. Cluster and layout

- One kind cluster, `ezeebit`, defined in `k8s/kind-cluster.yaml`; every kubectl and kind call
  names the context `kind-ezeebit` so no other cluster on the machine is touched.
- Manifests are applied with `kubectl apply -k` (kustomize built into kubectl): `k8s/base/` plus one
  overlay `k8s/overlays/local/`. No further overlays until an environment exists to need one.
- Namespaces: `ezeebit-data` (PostgreSQL, Redis, Kafka), `ezeebit-app` (the five services and the
  WireMock rate feed), `ezeebit-obs` (otel-lgtm), `ezeebit-chaos` (the monkey).
- Images are the Paketo images from `./gradlew bootBuildImageAll` (`ezeebit/<svc>:<version>`),
  loaded with `kind load docker-image`, `imagePullPolicy: Never`. Third-party images use the same
  tags and digests as `compose.yaml`; a check fails the sweep when the two disagree.
- Configuration uses the same variable names as `.env.example`. Secrets (database, Redis, gateway
  shared secret, Slack webhook) are created by `kube.sh` from the caller's environment with
  committed local defaults, never from a committed file holding a real value.
- Grafana provisioning comes from the same `docker/lgtm` files, loaded as ConfigMaps by `kube.sh`
  with server-side apply (no 256 KiB last-applied annotation), and the same `start.sh` decides
  whether the Slack contact point is installed.
- Host ports: gateway 8080 and Grafana 3000 through kind `extraPortMappings` on 127.0.0.1. `up`
  refuses while `fleet.sh` holds them, and says which process holds the port.

## 2a. Two fleets, no shared resources

`fleet.sh` (host JVMs plus the compose project) and `k8s/kube.sh` (the kind cluster) are separate
fleets that share only the images they read. Neither may create, change or stop anything of the
other's:

| Resource | fleet.sh owns | kube.sh owns |
|---|---|---|
| Control commands | `docker compose`, host JVM processes | `kind`, `kubectl --context kind-ezeebit` |
| Run state on disk | `.fleet/` (gitignored) | `.kube-fleet/` (gitignored), nothing else |
| Data | compose volumes `pgdata`, `kafkadata`, `lgtmdata` | PVCs inside the kind node; never a host bind mount |
| Docker objects | the compose project's containers and network | the `ezeebit-control-plane` node container and the `kind` network |
| Host ports | compose's published ports and the service ports | only 127.0.0.1:8080 and 127.0.0.1:3000, through the kind node |
| Images | builds `ezeebit/<svc>:<version>` | reads the same tags and `kind load`s a copy; never retags or removes |

- Mutual refusal: `kube.sh up` refuses while any compose container of this project runs or a
  service port is held, and `fleet.sh up` already refuses a port held by anything but its own
  compose project, which includes the kind node. Both name the holder.
- A build gate keeps it that way: `k8s/**` contains no `docker compose`, `fleet.sh` or compose
  volume name, and `fleet.sh` and `compose.yaml` contain no `kind`, `kubectl` or `.kube-fleet`
  reference. Red-proofed by inserting one of each.
- `kube.sh down` deletes the `ezeebit` cluster only, by name and context; it never runs a
  `docker ... prune` or touches a container it did not create.

## 3. Workloads

| Workload | Kind | Replicas | Autoscaler |
|---|---|---|---|
| postgres | StatefulSet + PVC | 1 | none |
| redis | Deployment | 1 | none |
| kafka (KRaft) | StatefulSet + PVC | 1 | none |
| wiremock | Deployment | 1 | none |
| otel-lgtm | Deployment + PVC | 1 | none |
| config-svc, eureka-svc | Deployment | 1 (gated) | none (gated) |
| gateway-svc | Deployment | 2 | HPA 2 to 4, CPU 70% of request |
| ledger-svc | Deployment | 2 | HPA 2 to 3, CPU 70% of request |
| conversion-svc | Deployment | 2 | HPA 2 to 3, CPU 70% of request |

- Pod budget: CPU limit 2, memory 1Gi. Liveness is `/actuator/health/liveness` only; readiness is
  the readiness group. `RollingUpdate` with `maxUnavailable: 0`; a PodDisruptionBudget of
  `minAvailable: 1` on each two-replica service.
- Connection invariant, checked in the build: sum over database services of HPA maxReplicas x
  HikariCP pool size, plus 10 reserved for migrations and operators, stays at or below PostgreSQL
  `max_connections` (3 x 10 + 3 x 10 + 10 = 70 of 100).
- kind ships no metrics-server; `kube.sh` installs a pinned one so the HPAs have a signal, and the
  sweep asserts each HPA reports `ScalingActive=True`.

## 4. Network policy

Default deny in each namespace, then only the edges the fleet uses: gateway to ledger and
conversion HTTP; conversion to ledger gRPC, WireMock, Kafka and PostgreSQL; ledger to PostgreSQL;
gateway to Redis; every service to config-svc, eureka-svc and the OTLP ports; DNS. The host reaches
only the gateway and Grafana. The sweep asserts one denied edge (WireMock to PostgreSQL) answers
nothing and one allowed edge answers, so a policy that enforces nothing cannot pass.

## 5. Chaos monkey

- A CronJob in `ezeebit-chaos`, every 10 minutes, `suspend: true` as shipped.
- Its ServiceAccount holds namespaced Roles in `ezeebit-app` and `ezeebit-data` only: `get`,
  `list`, `delete` on pods; `get`, `patch` on deployments and their scale. No Secrets, no access to
  `ezeebit-obs`. The sweep proves both with `kubectl auth can-i --as=...`.
- The victim is drawn uniformly over services, then a running pod of that service is picked, so a
  single-replica service is not starved of faults. The draw and the kill live in one script shared
  by the CronJob and the sweep (shipped to the cluster as a ConfigMap), so the two cannot differ.
- `kube.sh chaos on` / `off` patch `suspend`; `chaos run` creates one Job from the CronJob now;
  `chaos status` prints suspend, the last run and its victim. `docs/k8s.md` documents all four.

## 6. The single sweep

`./k8s/kube.sh sweep`, from no cluster to a deleted one, each check printing its own exit code:

1. Build images, create the cluster, deploy, wait for every workload Ready.
2. Parity: image tags in the cluster equal `compose.yaml`'s; config-svc and eureka-svc at 1 replica
   with no HPA; every HPA `ScalingActive=True`.
3. Money path through the gateway: deposit, quote, convert; the ledger posting read back.
4. Replica checks: each of the six replicas of the three scaled services serves at least one request
   (per-pod counters on each pod's own actuator endpoint); a burst across both gateway pods admits at
   most capacity plus refill over the elapsed time; a conversion written through one conversion pod
   reads back through the other.
5. Network policy: one denied edge, one allowed edge.
6. Chaos: traffic runs throughout; one kill per scaled service (the run fails if fewer kills happened
   than were asked for); the duplicate audit afterwards (`count(*) = count(DISTINCT business key)` on
   ledger postings and conversions); merchant 5xx during the kill window reported with its count,
   and the R1 budget asserted: no 5xx later than 20s after a kill.
7. Outage: ledger-svc scaled to zero, held past the dead-letter rule's wait, the rule reaching
   Alerting asserted, and the Grafana notification counter moving when a webhook is set; then the
   declared replica count restored and read back.
8. Monitoring survived: otel-lgtm restart count unchanged and Grafana answering throughout.
9. Delete the cluster.

The sweep is run once for the release. If it fails, the failure is fixed and the sweep runs again;
the committed log is the passing run, with the earlier failures listed in `docs/k8s.md`.

## 7. Files

- `k8s/kind-cluster.yaml`, `k8s/kube.sh` (bash), `k8s/base/**`, `k8s/overlays/local/**`,
  `k8s/chaos/monkey.sh`, `k8s/sweep/*.sh` (one file per check family).
- `persistence/src/main/java` and `persistence/src/main/resources`: the advisory `LockService` and its
  `META-INF/services` entry, wired as a source directory into the two database services.
- Served configuration: Eureka lease and gateway retry settings.
- `docs/k8s.md` (how to run, what the sweep proves and does not, chaos on/off), linked from
  `README.md`; `ARCHITECTURE.md` and `docs/project-layout.md` gain the new files.
- `docs/k8s/verification/2026-10-16-sweep.log` (the passing run).

## 8. Not in scope

Service mesh and mutual TLS, a secret store, more environments than `local`, image registry push,
multi-node clusters. Each is named in `docs/k8s.md` as not proven by this sweep.

## 9. Risks

- First bring-up on a fresh cluster is where defects hide (oversized ConfigMaps, ordering); the
  sweep always starts from no cluster for that reason.
- Apple Silicon builds arm64 images; kind on the same machine runs arm64, so they match. A real
  cloud cluster would need `linux/amd64` images, stated in `docs/k8s.md`.
- The R1 retry must not repeat a request that reached a service. It is limited to connection
  failures, and a test proves a POST that reached ledger-svc is not sent twice.
