# Kubernetes: the same fleet on kind, at more than one replica

Back to [README](../README.md). The design is
[the Kubernetes parity spec](superpowers/specs/2026-10-16-k8s-kind-parity-design.md); this page is
how to run it, what it does, and what it does not prove.

`./k8s/kube.sh` runs the five services, their backing services and the observability stack on one
local [kind](https://kind.sigs.k8s.io/) cluster named `ezeebit`, from the same images and the same
configuration names as `./fleet.sh`. gateway-svc, ledger-svc and conversion-svc run at 2 replicas
under a HorizontalPodAutoscaler (HPA), and a chaos monkey that ships paused can be switched on and
off. Facts below were read from the files named beside them on 2026-10-16 at `e7aed35`.

[Prerequisites](#prerequisites) · [Commands](#commands) · [One fleet at a time](#one-fleet-at-a-time-the-environment-guard) ·
[Namespaces and workloads](#namespaces-and-workloads) · [Configuration and images](#configuration-and-images) ·
[Replica fixes R1 to R6](#replica-fixes-r1-to-r6) · [Network policy](#network-policy) ·
[Chaos monkey](#chaos-monkey) · [The sweep](#the-sweep) · [Parity exceptions](#parity-exceptions) ·
[Known limits](#known-limits) · [Build gates](#build-gates)

## Prerequisites

Docker, kind and kubectl, plus the tools the scripts call. The versions are the ones this branch was
built and gated with, read with `kind version`, `kubectl version --client`,
`docker version --format '{{.Server.Version}} {{.Server.Os}}/{{.Server.Arch}}'`, `bash --version`,
`curl --version`, `jq --version` and `perl -v` on 2026-10-16:

| Tool | Observed | Used by |
|---|---|---|
| kind | v0.32.0 | `kube.sh`: create, load images, delete |
| kubectl | v1.36.1 (Kustomize v5.8.1 built in) | every cluster call, always with `--context kind-ezeebit` |
| Docker Engine | 29.8.2, linux/arm64 | the kind node, image builds (`./gradlew bootBuildImageAll`), `docker save` of third-party images |
| bash | 3.2.57 (`/bin/bash` on macOS) | `kube.sh`, the sweep and the guard are written for it (`k8s/kube.sh:4`) |
| curl, jq, perl, uuidgen, awk | curl 8.7.1, jq 1.7.1, perl 5.34.1 | the sweep checks (`k8s/sweep/lib.sh`) |

The node image is `kindest/node:v1.36.1`, pinned by digest in `k8s/kind-cluster.yaml:11`. The
cluster pins its pod range `10.244.0.0/16` and service range `10.96.0.0/12`
(`k8s/kind-cluster.yaml:7-8`) because the network policies name them.

The host needs **127.0.0.1:8080 and 127.0.0.1:3000** free: they are the only host ports the cluster
binds (`k8s/kind-cluster.yaml:13-14`). PostgreSQL, Redis, Kafka and WireMock are not published to
the host at all.

## Commands

```sh
./k8s/kube.sh up        # guard, cluster, image build and load, secrets, configmaps, then every stage until Ready
./k8s/kube.sh status    # pods in the four namespaces, then every HPA
./k8s/kube.sh down      # deletes the ezeebit cluster and .kube-fleet/, then releases the guard
./k8s/kube.sh sweep     # from no cluster to a deleted one: up, seven checks, down (see The sweep)
```

`up` takes `--no-build` (load the `ezeebit/<svc>:<version>` images already in Docker) and
`--only data,obs,app,chaos` (a subset of the stages: the selected ones are applied together, then
waited on in that order). Step by step
(`k8s/kube.sh:182-227`):

1. `scripts/env-guard.sh acquire k8s`; a refusal ends `up` with the guard's exit code.
2. `kind create cluster --name ezeebit --config k8s/kind-cluster.yaml`, unless it exists.
3. `./gradlew bootBuildImageAll` and `kind load docker-image` of the five service images, only when
   the `app` stage is selected and `--no-build` is absent.
4. Every third-party image the manifests name is loaded from the host's Docker into the node
   (pulled to the host once when absent), and `crictl inspecti` on the node must resolve each one, so
   the kubelet never needs a registry (`k8s/kube.sh:44-89`).
5. The namespaces and their deny-all policies, then the Secrets and ConfigMaps, then
   `kubectl apply -k k8s/overlays/local` for the selected stages.
6. `kubectl rollout status` (or `wait --for=condition=complete` for a Job) on every workload of
   each stage, 300 s each. The table is printed and kept in `.kube-fleet/last-up.txt`; any workload
   not Ready fails `up`.

`up` on a running cluster re-applies everything and changes only what differs. A rebuilt image under
the same tag is loaded but not rolled out, because the pod template does not change
(`imagePullPolicy: Never`, same `ezeebit/<svc>:<version>`): run
`kubectl --context kind-ezeebit -n ezeebit-app rollout restart deployment/<svc>`, or `down` then `up`.
Grafana is at
http://127.0.0.1:3000 and the gateway at http://127.0.0.1:8080, on the same routes as under
`fleet.sh`. A deposit has no host path: `/internal/**` is never routed, and ledger-svc
binds its pod address, so the sweep's `deposit` helper (`k8s/sweep/lib.sh:89-103`) posts it from an
ephemeral container inside a ledger pod.

Data lives in PersistentVolumeClaims inside the kind node. `down` deletes the cluster, so it deletes
every row, dead letter and metric with it; there is no data-keeping stop.

`kind create cluster` writes the `kind-ezeebit` context into `$KUBECONFIG` or `~/.kube/config` (its
`--kubeconfig` help says so) and makes it the current context: kind v0.32.0's kubeconfig merge does
`existing.CurrentContext = kind.CurrentContext`
([merge.go](https://github.com/kubernetes-sigs/kind/blob/v0.32.0/pkg/cluster/internal/kubeconfig/internal/kubeconfig/merge.go)). Nothing here relies on that: every call passes `--context kind-ezeebit`
(`k8s/lib/common.sh:9`). Switch back with `kubectl config use-context <yours>`.

## One fleet at a time: the environment guard

`./fleet.sh` (host JVMs plus the compose project) and `./k8s/kube.sh` (the kind cluster) share no
commands, state, volumes, Docker objects or ports, and only one of them may run at a time, in both
directions.

| Resource | fleet.sh | kube.sh |
|---|---|---|
| Control commands | `docker compose`, host JVM processes (`./gradlew bootJar`) | `kind`, `kubectl --context kind-ezeebit` |
| Run state on disk | `.fleet/` (gitignored) | `.kube-fleet/` (gitignored) |
| Data | compose volumes `pgdata`, `kafkadata`, `lgtmdata` | PVCs inside the kind node, never a host bind mount |
| Docker objects | the compose project's containers and network | the `ezeebit-control-plane` node container and kind's network |
| Host ports | the compose and service ports (README, Prerequisites) | 127.0.0.1:8080 and 127.0.0.1:3000 only, through the node |
| Images | third-party images by the `compose.yaml` refs | the same refs, copied from the host into the node; builds and loads `ezeebit/<svc>:<version>` |

`scripts/env-guard.sh` is the one file that knows both fleets. `fleet.sh up` calls
`acquire compose` (`fleet.sh:464`) and `kube.sh up` calls `acquire k8s` (`k8s/kube.sh:198`);
`fleet.sh down|purge` release the lease only when the stop succeeded (`fleet.sh:465-466`), and
`kube.sh down` releases it after deleting the cluster (`k8s/kube.sh:238`).

```sh
scripts/env-guard.sh acquire <compose|k8s>
scripts/env-guard.sh release <compose|k8s>
scripts/env-guard.sh state     # lease, compose, jvms, ports, kind: one line each
```

- **Exit codes** (`scripts/env-guard.sh:4`): 0 the lease is held (or released); 1 refused, with the
  blocker named on stderr; 2 could not look (Docker unreachable, an unreadable pid file, `lsof`
  failing, a lease with no readable or an unknown owner). It fails closed: "could not look" is never
  "nothing runs".
- **The lease** is the directory `.env-lease/`, taken with an atomic `mkdir`, holding the owner, the
  pid and start time of the process that called the guard, and when. The same owner acquiring again is
  idempotent.
- **What blocks `acquire k8s`** (the compose fleet, `scripts/env-guard.sh:149-158`): a running
  container of this compose project (by its project label, or its working-directory label naming
  this checkout); a live JVM from `.fleet/*.pid` whose command line runs this checkout's jars; and any
  listener on a fleet port that is not the kind node. The ports are each service's `SERVER_PORT`
  default, the ledger gRPC port and every host port `compose.yaml` publishes, read from those files.
- **What blocks `acquire compose`** (the kind fleet): the node container labelled
  `io.x-k8s.kind.cluster=ezeebit`, running or stopped, because a stopped node rebinds its ports when
  Docker next starts.
- A refusal reads, for example,
  `[env-guard] refused compose: the k8s fleet holds the lease and still runs: kind node ezeebit-control-plane is running. Stop it first: ./k8s/kube.sh down`.
- **A stale lease is reclaimed.** If the lease's process is gone and nothing of its owner fleet runs
  (a crash, a reboot), the next acquire logs `stale lease of <owner> ... reclaimed` and takes it,
  under a second lock (`.env-lease.reclaim/`) so two reclaimers cannot both win. A lease whose
  holder still runs is never taken over.
- **Running the guard by hand** records your interactive shell as the holder (the guard stores its
  parent's pid, `scripts/env-guard.sh:177`). While that shell lives, the other fleet is refused with
  `its up (pid <n>) is still running`. Release it with `scripts/env-guard.sh release <owner>`.
- **Manual recovery.** If `state` shows neither fleet running and a refusal names a leftover
  `.env-lease` with no readable or an unknown owner, `rm -r .env-lease`. If a refusal says
  `another acquire is reclaiming the lease; if none is running, rmdir .env-lease.reclaim`, and no
  `fleet.sh` or `kube.sh` is running, do exactly that.
- The manual steps under the README's "Without zsh" do not take the lease; only `fleet.sh` does.
- `checkEnvGuard` runs `scripts/test/env-guard-test.sh` against stubbed `docker`, `ps` and `lsof`:
  `PASS 39/39` on 2026-10-16 (`./gradlew checkEnvGuard`).

## Namespaces and workloads

| Namespace | Workload | Kind | Replicas | HPA | Stage |
|---|---|---|---|---|---|
| `ezeebit-data` | postgres | StatefulSet + 2Gi PVC | 1 | none | data |
| `ezeebit-data` | redis | Deployment | 1 | none | data |
| `ezeebit-data` | kafka (KRaft) | StatefulSet + 2Gi PVC | 1 | none | data |
| `ezeebit-data` | kafka-topics | Job | 1 run | none | data |
| `ezeebit-app` | wiremock | Deployment | 1 | none | data |
| `ezeebit-app` | config-svc, eureka-svc | Deployment | 1 each | none | app |
| `ezeebit-app` | gateway-svc | Deployment | 2 | 2 to 4 at 70% CPU of request | app |
| `ezeebit-app` | ledger-svc | Deployment | 2 | 2 to 3 at 70% CPU of request | app |
| `ezeebit-app` | conversion-svc | Deployment | 2 | 2 to 3 at 70% CPU of request | app |
| `ezeebit-obs` | lgtm (`grafana/otel-lgtm:0.36.0`) | Deployment + 2Gi PVC, `Recreate` | 1 | none | obs |
| `ezeebit-chaos` | chaos-monkey | CronJob, `suspend: true` | n/a | none | chaos |
| `kube-system` | metrics-server v0.9.0 | Deployment | 1 | none | app |

Sources: `k8s/base/data/*.yaml`, `k8s/base/app/*.yaml`, `k8s/base/app/hpa.yaml`,
`k8s/base/obs/lgtm.yaml`, `k8s/base/chaos/cronjob.yaml`, `k8s/base/metrics-server/`. WireMock is a
third-party stand-in, so it carries the `data` stage and comes up before the services that call it.

- **Every Spring service**: requests 250m CPU and 512Mi, limits 2 CPUs and 1Gi; a startup probe and
  a liveness probe on `/actuator/health/liveness`, readiness on `/actuator/health/readiness`;
  `RollingUpdate` with `maxUnavailable: 0`, `maxSurge: 1`; `terminationGracePeriodSeconds: 40`;
  `imagePullPolicy: Never`.
- **A PodDisruptionBudget of `minAvailable: 1`** on each of the three scaled services
  (`k8s/base/app/pdb.yaml`).
- **kind ships no metrics-server**, so a pinned v0.9.0 is applied with the app stage; it is the
  upstream manifest plus `--kubelet-insecure-tls`, because kind's kubelet serves a self-signed
  certificate (`k8s/base/metrics-server/metrics-server.yaml:1-3`).
- **Connection budget**: (3 ledger + 3 conversion) maxReplicas x 10 pooled connections + 10 reserved
  = 70, at or below PostgreSQL `max_connections=100` (`k8s/base/app/hpa.yaml:1`,
  `k8s/base/data/postgres.yaml:35`), enforced by the `pool-budget` gate.

## Configuration and images

**Variables.** `kube.sh` reads the caller's environment only; it never reads `.env` (a grep of
`k8s/kube.sh`, `k8s/lib/common.sh` and `k8s/sweep/` for `.env` finds nothing). It creates the
Secrets from the same variable names as the README's configuration table, each with the committed
local default (`k8s/kube.sh:111-131`):

| Secret (namespace) | From |
|---|---|
| `postgres-admin` (data) | `POSTGRES_USER` (default `postgres`), `POSTGRES_PASSWORD` (default `postgres`) |
| `redis` (data and app) | `REDIS_PASSWORD` (default `redis-local`) |
| `ledger-db`, `conversion-db` (app) | fixed `ledger`/`ledger` and `conversion`/`conversion`: `docker/postgres/initdb` creates the roles with exactly these |
| `gateway-proof` (app) | `GATEWAY_SHARED_SECRET` (default `local-gateway-proof`) |
| `slack` (obs) | `SLACK_WEBHOOK_URL`, only when set; otherwise the Secret is deleted |

Values travel on a file descriptor, never in argv or on disk, and are applied server-side.
`docker/lgtm/start.sh` installs the Slack contact point only when the webhook is set, as under
compose; it decides when the lgtm container starts.

**Fixed per pod** (manifests): `SERVER_ADDRESS` is the pod IP and `HOSTNAME` the pod name, both from
the downward API; `CONFIG_SERVER_URL`, `EUREKA_URL`, `OTLP_URL`, `DB_URL`, `REDIS_HOST`, `RATES_URL`
and `KAFKA_BOOTSTRAP` name the in-cluster Services. Grafana provisioning comes from the same
`docker/lgtm/` files, loaded as ConfigMaps.

**Images.** The services are the Paketo images from `./gradlew bootBuildImageAll`,
`ezeebit/<svc>:<version>`, where `<version>` is the `version` in `build.gradle`, loaded into the node with
`kind load docker-image`. The manifests name that tag literally, so the `app-image-version` gate fails
`checkK8s` when any `ezeebit/<svc>` ref under `k8s/` differs from it, and `kube.sh up` runs the same gate
first and refuses to apply on a mismatch, naming both versions. A version bump edits `build.gradle`
and the five manifests in one commit. Third-party images use byte-identical refs to `compose.yaml`
(the `image-parity` gate), and are preloaded so a bring-up does not depend on Docker Hub once the host
has a copy. **Apple Silicon builds arm64**: `docker image inspect ezeebit/gateway-svc:<version>` reads
`linux/arm64` on this machine, and kind here runs arm64 too. A real cloud cluster on amd64 nodes
needs `linux/amd64` images, which nothing here builds.

## Replica fixes R1 to R6

What breaks at two or more replicas under kills, rolling updates and scale-in, and what was built for
it.

| # | Defect | Fix as built | Tests, and the sweep check that covers it |
|---|---|---|---|
| R1 | A pod that dies without deregistering stays in Eureka (default lease 90 s, longer under self-preservation), so routes keep choosing it | Clients renew every 5 s with a 15 s lease (`services/config-svc/src/main/resources/served/application.yml:13-14`); the server evicts every 5 s, self-preservation off (`services/eureka-svc/src/main/resources/application.yml:20-21`). The gateway's merchant routes resend a request **only when the connect was refused or timed out** (1 s connect timeout), never after bytes were sent (`RetryUnsent=3`, `served/gateway-svc.yml:34,46`) | `ServedRegistryTimingTest`, `EvictionSettingsTest`, `DeadInstanceRetryIT`, `UnsentRetryFilterFunctionsTest`; on the cluster, the sweep's chaos check (no committed run yet, [The sweep](#the-sweep)) |
| R2 | A pod stopped on purpose is still registered while it shuts down | Deregister first: `DeregisterBeforeDrain` (in `discovery/`, compiled into gateway, ledger and conversion) sets the instance DOWN on `ContextClosedEvent`, before any lifecycle bean stops, and keeps serving 10 s (5 s server response cache + 5 s client fetch); then the graceful drain | `DeregisterBeforeDrainTest` |
| R3 | Liquibase's lock row outlives a SIGKILLed migrator and blocks every later pod | `AdvisoryLockService` (in `persistence/`, compiled into ledger and conversion): the lock is a PostgreSQL session advisory lock, released by the database when the connection dies | `AdvisoryLockIT` |
| R4 | `SERVER_ADDRESS` is both the bind address and the registered address, defaulting to loopback | The manifests set it to the pod IP. No code change: the loopback default protects `/internal/**` on a laptop | manifests |
| R5 | Resilience4j breaker and retry state is per replica | Accepted, marked `[!REPLICA-LOCAL]` with its verdict (`served/conversion-svc.yml:70-71`): a ledger outage opens every replica's breaker within its own window | ARCHITECTURE section 5 |
| R6 | config-svc and eureka-svc are single-instance by design (Eureka here is not peer-aware) | replicas 1 and no HPA, enforced by the `replicas` gate; the sweep's parity check also reads it live (no committed run yet) | `k8s/gates/replicas.sh`; `k8s/sweep/parity.sh` |

**R1 in seconds.** A SIGKILLed pod leaves the server's registry about 30 s after its last renewal,
not 15 s: eureka-core 2.0.6 counts the lease duration twice (`Lease.renew()` stores now + duration,
and `isExpired` adds the duration again, as its own javadoc says). Eviction runs every 5 s, then the
server's 5 s response cache and the callers' 5 s fetch apply. The RetryUnsent resend, not eviction,
is what keeps merchants from seeing the dead pod; the sweep's chaos check is built to record every
merchant failure with its delay after the kill, and has no committed run yet. With round-robin
and one dead instance of two, every request after the first tries the dead one first
(`DeadInstanceRetryIT`, its comment at the 19-or-20 assertion), so while a deleted pod's IP swallows
connects, each request on that route pays about the 1 s connect timeout until the instance leaves
the callers' registry. Each resend is counted in `ezeebit_gateway_upstream_resend_total{route}`
([docs/observability.md](observability.md#business-metrics)). A request whose 3 attempts all fail to
connect answers **503 `UPSTREAM_UNAVAILABLE`**; a read timeout or reset answers 504 and is never
resent.

**R2 timeline**, inside the 40 s grace (`k8s/base/app/gateway-svc.yaml:62-65`):

```
0 s    preStop sleep 5 s: endpoint removal reaches kube-proxy (the gateway NodePort, ClusterIP callers)
5 s    SIGTERM, ContextClosedEvent: DeregisterBeforeDrain sets the instance DOWN in Eureka, serves 10 s
15 s   graceful shutdown: in-flight requests drain
35 s   done, if the drain is one 20 s phase: 5 s inside terminationGracePeriodSeconds 40
```

`spring.lifecycle.timeout-per-shutdown-phase: 20s` bounds each shutdown phase, not the whole
shutdown, so several slow phases could run past the 40 s grace and be killed.

config-svc and eureka-svc are not in Eureka: their preStop is the same 5 s, then the graceful drain.

**R3 runbook: a lock held by another session.** The lock ends only when the session that holds it
ends. A pod that waited out Liquibase's lock timeout fails its start with
`Could not acquire change log lock.  Currently locked by pid <n> (<application name>) since ...`
(the holder comes from `AdvisoryLockService.listLocks`, the message from liquibase-core 5.0.3's
`StandardLockService.waitForLock`). If that pid is not a migration in progress, end it as the
PostgreSQL admin user (from any database):

```sh
kubectl --context kind-ezeebit exec -n ezeebit-data postgres-0 -- \
  sh -c 'psql -U "$POSTGRES_USER" -d ledger -c "SELECT pg_terminate_backend(<pid>)"'
```

The lock is key `0x657a656562697400`, which `pg_locks` shows as `classid` 1702520165 and `objid`
1651078144 (`AdvisoryLockService.java:24`). `AdvisoryLockIT.theLockIsReleasedWhenItsConnectionDies`
proves that ending the session frees it.

## Network policy

Every namespace starts at deny-all, ingress and egress, plus DNS to `kube-dns` on 53
(`k8s/base/policy/default-deny.yaml`); the deny-all lands with the namespaces, before any workload.
Then only these edges are open (`k8s/base/policy/{app,data,obs,chaos}.yaml`, 20 NetworkPolicy
objects in the rendered overlay:
`kubectl kustomize k8s/overlays/local | awk '$0 == "kind: NetworkPolicy" {n++} END {print n + 0}'`):

| From | To | Port |
|---|---|---|
| the host, through the NodePort | gateway-svc | 8080 |
| the host, through the NodePort | lgtm (Grafana) | 3000 |
| gateway-svc | ledger-svc / conversion-svc | 8081 / 8082 |
| gateway-svc | redis | 6379 |
| conversion-svc | ledger-svc (gRPC) | 9081 |
| conversion-svc | wiremock / kafka / postgres | 8080 / 9092 / 5432 |
| ledger-svc | postgres | 5432 |
| gateway-svc, ledger-svc, conversion-svc | config-svc / eureka-svc | 8888 / 8761 |
| the five services | lgtm (OTLP HTTP) | 4318 |
| kafka-topics Job, kafka itself | kafka | 9092 |
| lgtm | any public address (Slack) | 443 |
| chaos-monkey pods | any address (the API server) | 443, 6443 |

Nothing else: wiremock, postgres and redis have no egress beyond DNS, and OTLP gRPC 4317 is not open.

- **The NodePort rule is broad.** kindnet judges a NodePort packet before source NAT, from the kind
  network's gateway address, which differs per machine, so the rule admits any address except the
  pod range (`ipBlock 0.0.0.0/0 except 10.244.0.0/16`) on 8080 and 3000. Any container on kind's
  Docker network can therefore reach both; the host side binds 127.0.0.1 only.
- **lgtm's 443 egress** excludes the private ranges and link-local metadata
  (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.0.0/16`): an `ipBlock` cannot name
  `hooks.slack.com`, so it is any public address on 443.
- **Kubelet probes need no rule on kindnet**, which accepts every packet from a root-owned socket on
  the node (`k8s/base/policy/app.yaml:1`). A different CNI would need probe-port rules; the symptom
  is probes failing, and pods never turning Ready, right after the policies are applied.
- **The chaos namespace's API-server rule appears inert on kindnet.** Observed once during
  development, with no committed log: a pod in `ezeebit-chaos` reached the API server with the rule's
  ports removed, while the same namespace could not reach a pod, Grafana or the kubelet. RBAC is the
  control on what the monkey may do either way; the rule is kept for a CNI that enforces it. To
  reproduce on a running cluster (not run in exactly this form; the image is already on the node):

  ```sh
  kubectl --context kind-ezeebit -n ezeebit-chaos patch networkpolicy api-server --type json \
    -p '[{"op":"replace","path":"/spec/egress/0/ports","value":[{"protocol":"TCP","port":1}]}]'
  sleep 30   # let the CNI reprogram
  kubectl --context kind-ezeebit -n ezeebit-chaos run api-probe --rm -i --restart=Never \
    --image='alpine/k8s:1.36.5@sha256:094adf64cdc4827377cdbe7ea5747472ce115f9fbc64a4e35970ec66777f9a00' -- \
    sh -c 'kubectl get --raw /version; echo api_exit=$?; wget -T 5 -qO- http://lgtm.ezeebit-obs:3000/api/health; echo control_exit=$?'
  kubectl --context kind-ezeebit apply -k k8s/overlays/local -l ezeebit.io/stage=namespaces   # restore
  ```

  `api_exit=0` with `control_exit` non-zero means the API server is reached while default-deny
  still blocks everything else, so the rule decides nothing.
- The sweep's policy check tests enforcement with one denied and one allowed edge (below).

## Chaos monkey

```bash
./k8s/kube.sh chaos status   # suspended or not, last run, last victims
./k8s/kube.sh chaos on       # unpause: a random pod is killed every 10 minutes
./k8s/kube.sh chaos off      # pause again (the shipped state)
./k8s/kube.sh chaos run      # one run now, whatever the schedule says
```

- **Schedule `*/10 * * * *`**, `suspend: true` as shipped, `concurrencyPolicy: Forbid`
  (`k8s/base/chaos/cronjob.yaml:11-13`). `on` and `off` patch `suspend` and read it back.
- **Arming.** `startingDeadlineSeconds: 30` stops `chaos on` from firing the slot missed while
  paused, with one residual: arming within 30 s after a :x0 minute still fires that slot.
- **A later `kube.sh up` re-applies the manifest and so pauses it again** (`suspend` back to true).
- **`chaos run`** creates `chaos-manual-<epoch>` from the CronJob, waits up to 180 s, prints its log,
  and fails unless the Job is Complete. A run is never retried (`backoffLimit: 0`), is ended after
  120 s, and its Job is deleted an hour after it finishes, which `chaos status` then says.
- **The draw** (`k8s/chaos/monkey.sh`): a service is drawn uniformly over every Deployment and
  StatefulSet labelled `app.kubernetes.io/part-of=ezeebit` in `ezeebit-app` and `ezeebit-data`
  (9 in the rendered overlay: the five services, wiremock, postgres, redis, kafka;
  `kubectl kustomize k8s/overlays/local | awk '/^kind:/ {k = $2} /^  namespace: ezeebit-(app|data)$/ && (k == "Deployment" || k == "StatefulSet") {n++} END {print n + 0}'`),
  then one Running
  pod of it, force-deleted with grace 0. A single-replica service is therefore drawn as often as a
  scaled one, and killing postgres, kafka, redis, config-svc or eureka-svc is a real outage that can
  page. Each kill prints `KILL <ns>/<pod> service=<svc>`, then `KILLED <n>`.
- **A run fails** (exit 1) when it killed fewer pods than asked, and refuses (exit 2) when any of the
  two namespaces cannot be listed, rather than drawing over the rest and reporting success.
- **RBAC** (`k8s/base/chaos/rbac.yaml`): one ServiceAccount in `ezeebit-chaos`, a namespaced Role in
  `ezeebit-app` and in `ezeebit-data` only, never a ClusterRole: `list` and `delete` on pods, `get`
  and `list` on deployments and statefulsets. Nothing in `ezeebit-obs`, no Secrets, no `patch` or
  `scale` (a patched pod template is an indirect path to every Secret in the namespace). The sweep's
  outage step scales from the host's own context instead.
- The pod runs as uid 65534, read-only root filesystem, all capabilities dropped, on
  `alpine/k8s:1.36.5` pinned by digest.
- `checkChaosMonkey` runs `k8s/chaos/test/monkey-test.sh` against a stubbed kubectl: `PASS 14/14`
  on 2026-10-16 (`./gradlew checkChaosMonkey`).

## The sweep

```sh
./k8s/kube.sh sweep               # add --no-build to load the images already built
```

One command from no cluster to a deleted one (`k8s/kube.sh:363-397`). **It deletes an existing
`ezeebit` cluster first**, so it always starts from a fresh bring-up. Each step appends its output and
a `<step> exit=<code>` line to `docs/k8s/verification/<YYYY-MM-DD>-sweep.log` (that day's file is
truncated at the start), and the last line is `sweep exit=0` only when every step passed. Once `up` has begun,
the cluster is deleted and the lease released on every exit path, Ctrl-C included.

| Step | Script | What it asserts |
|---|---|---|
| up | `kube.sh up` | every workload of every stage Ready |
| monitoring begin | `k8s/sweep/monitoring.sh` | exactly one lgtm pod (its uid and restart count recorded); Grafana `/api/health` 200 |
| parity | `k8s/sweep/parity.sh` | the third-party images running in the cluster equal `compose.yaml`'s, both ways (the chaos image, metrics-server and kind's own images excepted); exactly 5 distinct `ezeebit/*` images, all at the `build.gradle` version; config-svc and eureka-svc at 1 desired and 1 ready, with no HPA targeting them; each of the three HPAs `ScalingActive=True` within 180 s; zero kubelet `Pulling` events |
| money | `k8s/sweep/money.sh` | a 100.00 ZAR deposit (201, from inside a ledger pod); a 50.00 ZAR to USDT quote and conversion through the gateway (201, COMPLETED); balances through the gateway read ZAR 50.00 and USDT equal to the conversion's `buyAmount`; exactly one POSTED CONVERSION journal keyed by the conversion id in the ledger database |
| replicas | `k8s/sweep/replicas.sh` | a live snapshot of each scaled service's Ready pods (at least 2 each); 60 gateway requests answer 200/201 and every snapshotted pod's own request counter rises, with no pod joining or leaving mid-check; a parallel burst of twice the rate-limit capacity for one merchant admits at most capacity + refill x elapsed seconds (one bucket across both gateway pods), and both gateway pods served it; a conversion written once reads back 10 of 10 through the gateway, and every conversion-svc pod served one of those reads |
| policy | `k8s/sweep/policy.sh` | from inside a wiremock pod, a connect to postgres:5432 times out (denied); from inside a ledger pod, PostgreSQL answers (allowed, the positive control); a DNS failure is never read as a closed port |
| chaos | `k8s/sweep/chaos.sh` | quote-and-convert traffic from 3 merchants runs throughout; one pod each of gateway-svc, ledger-svc and conversion-svc is killed 30 s apart with `monkey.sh --service`, and the check fails unless exactly 3 kills happened; every 5xx or unanswered request lands at most 20 s after the latest kill before it, none before the first; each 30 s kill window carries at least 20 requests; conversions complete before the first kill and after the last kill's 20 s; the three Deployments recover; PENDING conversions settle within 120 s; then the cross-store reconciliation: every COMPLETED conversion has exactly one POSTED CONVERSION journal keyed by its id and every such journal a COMPLETED conversion, none posted twice, and the conversion rows lie between the 201/202 answers and the requests sent |
| outage | `k8s/sweep/outage.sh` | ConversionLedgerDeadLettered has no Alerting instance before the outage (waits up to 360 s); ledger-svc is scaled to 0 from the host's context; conversions run until `ezeebit_conversion_ledger_deadletter_total` rises (up to 180 s); the rule reaches firing with an Alerting instance whose `activeAt` is after the scale-down, within its `for` + 120 s; with the `slack` Secret present, `grafana_alerting_notifications_total{integration="slack"}` rises within 120 s, and without it the log says delivery was not asserted; the declared replica count is restored and read back on every exit path |
| monitoring end | `k8s/sweep/monitoring.sh` | the lgtm pod uid and restart count unchanged; Grafana `/api/health` 200 at the start, after every check and at the end |
| down | `kube.sh down` | the cluster deleted, `.kube-fleet/` removed, the lease released |

**The release evidence is the committed passing log** under `docs/k8s/verification/`. As of
2026-10-16 at `e7aed35` no sweep log is committed.

**What a passing run would show** (none is committed yet): the fleet comes up from nothing on Kubernetes with the compose images; the
money path works through the gateway; every replica of the three scaled services serves; the rate
limit is one bucket across gateway pods; one denied and one allowed network edge behave; pod kills
mid-traffic leave the two databases agreeing, with merchant failures bounded in time; an outage
reaches Grafana alerting; monitoring survives all of it.

**What it cannot show, even passing:** no service mesh or mutual TLS; no secret store (Secrets are created
from the environment); a single node, so no node loss, zone spread or anti-affinity; the arm64
images of this machine, not the amd64 a cloud cluster would run; one environment (`local`), no
registry push; Slack delivery only when `SLACK_WEBHOOK_URL` is set for the run; an HPA scaling out
under load (it asserts only that each HPA has its CPU signal); Grafana's health only at check
boundaries, not continuously.

## Parity exceptions

Where the kind fleet deliberately differs from compose:

| What | Kubernetes | Why |
|---|---|---|
| lgtm memory | request 1Gi, limit 3Gi (`k8s/base/obs/lgtm.yaml:103-104`) | the 1Gi pod budget is the Spring services'; lgtm runs six backends in one container |
| Kafka heap | `KAFKA_HEAP_OPTS=-Xms512m -Xmx512m` (`k8s/base/data/kafka.yaml:49`) | the start script's default 1G heap alone fills the 1Gi limit; compose sets no limit |
| Kafka topic | created by the `kafka-topics` Job | compose creates it in the healthcheck, but here that probe would go through the Service address, which has no endpoint until the probe passes |
| Kafka listeners | one advertised `kafka.ezeebit-data.svc:9092`, no host listener | nothing outside the cluster connects |
| Postgres readiness | `pg_isready` and a `SELECT 1` in both databases, over TCP to 127.0.0.1 | compose's healthcheck also checks both databases; TCP because initdb's temporary server listens on the socket only, so a socket check can pass before the init script has created them (`k8s/base/data/postgres.yaml:44-47`) |
| Postgres `max_connections` | set to 100 explicitly | the pool budget gate reads it |
| Grafana | NodePort 30300 to 127.0.0.1:3000; OTLP stays inside the cluster; analytics reporting off | only Grafana needs the host |
| WireMock | Service port 8080, no host port | compose publishes 8089 for the host |
| File names | `.yaml` under `k8s/` | `[!CONVENTION-OVERRIDE]` the repository's `.yml` rule is for Spring configuration; Kubernetes and Kustomize use `.yaml` (`kustomization.yaml` is the name Kustomize looks for) |

## Known limits

They are kept in one list with the rest of the project's gaps:
[SOLUTION.md section 7](../SOLUTION.md#7-known-gaps-and-unfinished-work), under "Kubernetes" and
"Security and deployment".

## Build gates

`./gradlew check` runs three static gates for this tree, no cluster needed (`build.gradle:536-560`).
On 2026-10-16 at `e7aed35`, `./gradlew checkK8s checkEnvGuard checkChaosMonkey` exited 0:

| Gate | What it checks | Result |
|---|---|---|
| `checkK8s` / `fleet-separation` | no `docker compose`, `fleet.sh` or compose volume name in `k8s/**`; no `kind`, `kubectl`, `.kube-fleet`, `kind-ezeebit` or `ezeebit-control-plane` in `fleet.sh` or `compose.yaml` | PASS, 48 files under `k8s/` scanned |
| `checkK8s` / `image-parity` | third-party image refs in `k8s/base` and `compose.yaml` identical both ways, except the chaos image, metrics-server and the kind node | PASS, 5 and 5 compared |
| `checkK8s` / `app-image-version` | every `ezeebit/<svc>` image ref under `k8s/` is `ezeebit/<svc>:<version>` with `build.gradle`'s version, all five services named, no kustomize tag override | PASS, 5 refs (added in the final fix wave) |
| `checkK8s` / `replicas` | config-svc and eureka-svc at 1 replica with no HPA; the scaled three at 2 under HPA 2-4, 2-3, 2-3 at 70% | PASS |
| `checkK8s` / `pool-budget` | maxReplicas x pool per database service + 10 reserved <= `max_connections` | PASS, 30 + 30 + 10 = 70 of 100 |
| `checkEnvGuard` | the guard against stubbed docker, ps and lsof | PASS 39/39 |
| `checkChaosMonkey` | the draw and its refusals against a stubbed kubectl | PASS 14/14 |
