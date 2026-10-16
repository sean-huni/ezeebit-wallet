# Kubernetes Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run the Ezeebit fleet on a kind cluster at 2+ replicas with HPA and a switchable chaos monkey, fix the code defects that only appear at 2+ replicas, and prove all of it with one committed sweep.

**Architecture:** The replica fixes land first in the Spring code and configuration (Eureka leases and graceful deregistration, gateway retry on a refused connection, a session-scoped Liquibase lock). Then `k8s/` gains a kustomize base and a `local` overlay, a bash `kube.sh` that owns the kind cluster and nothing else, build gates that keep the two fleets apart and the manifests honest, a paused chaos CronJob, and a sweep whose passing log is committed.

**Tech Stack:** Spring Boot 4.1.1, Java 25, Gradle 9.8.1, Liquibase 5.0.3, Spring Cloud Netflix Eureka, Spring Cloud Gateway Server WebMVC, kind, kubectl with built-in kustomize, metrics-server, bash.

**Spec:** `docs/superpowers/specs/2026-10-16-k8s-kind-parity-design.md`

## Global Constraints

- Never mention any other project as the source of these patterns, in code, comments, docs or commits.
- `fleet.sh`/compose and `k8s/` share no commands, state, volumes, Docker objects or ports (spec 2a). `k8s/**` never runs `docker compose` or `fleet.sh`; `fleet.sh` and `compose.yaml` never reference `kind`, `kubectl` or `.kube-fleet`.
- Every kubectl call passes `--context kind-ezeebit`; every kind call passes `--name ezeebit`.
- Scripts under `k8s/` are bash with `#!/usr/bin/env bash` and `set -uo pipefail`; capture each command's own status (`cmd; rc=$?`), never a pipeline's or a command substitution's.
- Third-party image tags and digests in `k8s/**` are byte-equal to `compose.yaml`'s.
- Pod budget: CPU limit 2, memory limit 1Gi. Liveness `/actuator/health/liveness`; readiness `/actuator/health/readiness`.
- HPA: gateway 2 to 4, ledger 2 to 3, conversion 2 to 3, CPU 70% of request. config-svc and eureka-svc: replicas 1, no HPA.
- Connection invariant: sum(maxReplicas x 10) over ledger and conversion + 10 <= 100.
- Code comments: only a WHY the code cannot say, no block over 5 lines, no method-header restatements.
- No em dash (U+2014) in any file or commit. Commits: one-line title (no ticket prefix), one-line intro, bullets, summary; no `Co-Authored-By`; last line `Claude-Session: https://claude.ai/code/session_01DpaPR35PxFK4gpEaTSDb9U`.
- Never read or print `.env` or a Slack webhook value.
- Bug fixes are red first: capture the failing test output in the task report before the fix.
- Implementers commit only on `feat-k8s-kind-parity`; never touch `dev`, `main` or tags; never push.
- Foreground commands only, 10 minutes or less each; write the report file before returning.

## Review Focus

1. A POST that reached a service and then failed (read timeout, 5xx) is never sent again by the gateway retry: tested in Task 2.
2. A stale `DATABASECHANGELOGLOCK` row left by a killed migrator does not block the next start: tested in Task 3.
3. `kube.sh up` while `fleet.sh` runs refuses and names the holder instead of half-starting: tested in Task 5.
4. A chaos run that kills nothing exits non-zero: tested in Task 8.
5. A sweep check that cannot reach the cluster reports FAIL, never PASS: every check in Task 9 maps an empty or unrecognised answer to FAIL, and Task 9 Step 4 shows one check failing.

---

## File structure

| Path | Job |
|---|---|
| `services/config-svc/src/main/resources/served/application.yml` | Eureka client lease and graceful shutdown for every client |
| `services/eureka-svc/src/main/resources/application.yml` | Eviction interval, self-preservation off |
| `services/config-svc/src/main/resources/served/gateway-svc.yml` | Retry on a refused connection on merchant routes |
| `persistence/src/main/java/io/ezeebit/persistence/AdvisoryLockService.java` | Liquibase lock on a PostgreSQL session advisory lock |
| `persistence/src/main/resources/META-INF/services/liquibase.lockservice.LockService` | Registers it |
| `build.gradle` | Wires `persistence/` into ledger-svc and conversion-svc; `checkK8s` |
| `k8s/kind-cluster.yaml` | The one cluster definition |
| `k8s/base/{namespaces,data,obs,app,metrics-server,policy,chaos}/` | Manifests per concern |
| `k8s/overlays/local/kustomization.yaml` | The only overlay |
| `k8s/lib/common.sh` | Context, logging and refusal helpers shared by kube.sh, gates and sweep |
| `k8s/kube.sh` | up, down, status, sweep, chaos |
| `k8s/chaos/monkey.sh` | Victim draw and kill, shared by the CronJob and the sweep |
| `k8s/gates/*.sh` | Static build gates run by `checkK8s` |
| `k8s/sweep/*.sh` | One file per sweep check family |
| `docs/k8s.md` | How to run, what is proven, chaos on/off |
| `docs/k8s/verification/2026-10-16-sweep.log` | The passing sweep |

---

### Task 1: Eureka leases, eviction and graceful shutdown (R1 registry half, R2)

**Files:**
- Modify: `services/config-svc/src/main/resources/served/application.yml`
- Modify: `services/eureka-svc/src/main/resources/application.yml`
- Test: `services/config-svc/src/test/java/io/ezeebit/config/ServedRegistryTimingTest.java` (create)
- Test: `services/eureka-svc/src/test/java/io/ezeebit/eureka/EvictionSettingsTest.java` (create)

**Interfaces:**
- Produces: clients renew every 5s and expire after 15s; the server evicts every 5s with self-preservation off; every service shuts down gracefully with a 20s phase timeout. Task 6 sets `preStop` 15s and `terminationGracePeriodSeconds: 40` against these numbers.

- [ ] **Step 1: Write the failing tests**

```java
package io.ezeebit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

// A killed replica must leave every client's view within seconds, not the 90s default lease.
class ServedRegistryTimingTest {

	private static Properties served() {
		YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new ClassPathResource("served/application.yml"));
		return yaml.getObject();
	}

	@Test
	void clientsRenewEveryFiveSecondsAndExpireAfterFifteen() {
		assertThat(served()).containsEntry("eureka.instance.lease-renewal-interval-in-seconds", 5)
				.containsEntry("eureka.instance.lease-expiration-duration-in-seconds", 15);
	}

	@Test
	void everyServiceShutsDownGracefully() {
		assertThat(served()).containsEntry("server.shutdown", "graceful")
				.containsEntry("spring.lifecycle.timeout-per-shutdown-phase", "20s");
	}

}
```

```java
package io.ezeebit.eureka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

// Self-preservation reads a five-service fleet losing one pod as a partition and stops evicting.
class EvictionSettingsTest {

	@Test
	void evictsEveryFiveSecondsWithoutSelfPreservation() {
		YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new ClassPathResource("application.yml"));
		Properties props = yaml.getObject();
		assertThat(props).containsEntry("eureka.server.enable-self-preservation", false)
				.containsEntry("eureka.server.eviction-interval-timer-in-ms", 5000);
	}

}
```

- [ ] **Step 2: Run them and capture the red**

Run: `./gradlew :services:config-svc:test --tests '*ServedRegistryTimingTest' :services:eureka-svc:test --tests '*EvictionSettingsTest'`
Expected: FAIL, the entries are absent. Paste the failing assertion lines into the task report.

- [ ] **Step 3: Add the settings**

`served/application.yml`, merged into the existing `eureka.instance` map and as new top-level keys:

```yaml
eureka:
  instance:
    # A replica killed without deregistering leaves every client's view in 15s, not the 90s default.
    lease-renewal-interval-in-seconds: 5
    lease-expiration-duration-in-seconds: 15
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 20s
```

`eureka-svc/src/main/resources/application.yml`, under `eureka.server`:

```yaml
    # Self-preservation reads one lost pod of a five-service fleet as a partition and stops evicting.
    enable-self-preservation: false
    eviction-interval-timer-in-ms: 5000
```

Do not create a duplicate top-level key: YAML keeps the last one silently.

- [ ] **Step 4: Run green, then both services' `check`**

Run: `./gradlew :services:config-svc:check :services:eureka-svc:check`
Expected: PASS.

- [ ] **Step 5: Commit** (`Eureka: 5s/15s leases, 5s eviction, no self-preservation, graceful shutdown`)

---

### Task 2: Gateway retries a merchant request only when the connection was refused (R1 edge half)

**Files:**
- Modify: `services/config-svc/src/main/resources/served/gateway-svc.yml` (the `ledger` and `conversion` routes)
- Test: `services/gateway-svc/src/acceptanceTest/java/io/ezeebit/gateway/DeadInstanceRetryIT.java` (create)
- Modify if needed: `UpstreamStub.java`, `Gateways.java` in the same source set

**Interfaces:**
- Consumes: `Gateways`, `UpstreamStub`, `RedisStore` (read them first; `UpstreamFailureIT` shows how routes are set with `@DynamicPropertySource`).
- Produces: a dead registered instance costs a merchant no error; a request that reached an instance is never repeated.

- [ ] **Step 1: Write the failing IT**

Route `lb://conversion-svc` with Spring Cloud's simple discovery client listing two instances (`spring.cloud.discovery.client.simple.instances.conversion-svc[0].uri=http://127.0.0.1:<a port nothing listens on>`, `[1].uri=<stub>`), Eureka client off (`eureka.client.enabled=false`), `spring.http.clients.read-timeout=500ms`. The stub answers 201 and counts requests; a second stub delays 2s and counts.

```java
@Test
void aRefusedInstanceIsSkippedAndEveryPostArrivesOnce() {
	for (int i = 0; i < 20; i++) {
		ResponseEntity<String> answer = Gateways.post(gateway, "/api/v1/merchants/%s/quotes".formatted(MERCHANT), "{}");
		assertThat(answer.getStatusCode().value()).as("request %d", i).isEqualTo(201);
	}
	assertThat(STUB.count()).isEqualTo(20);
}

@Test
void aRequestThatReachedAnInstanceIsNeverRepeated() {
	ResponseEntity<String> answer = Gateways.post(gateway, "/api/v1/merchants/%s/conversions".formatted(MERCHANT), "{}");
	assertThat(answer.getStatusCode().value()).isEqualTo(504);
	assertThat(SLOW_STUB.count()).isEqualTo(1);
}
```

Route the conversions path to a discovery entry whose only instance is the slow stub. Add `Gateways.post(context, rawPath, body, headers...)` mirroring `get`.

- [ ] **Step 2: Run red**

Run: `./gradlew :services:gateway-svc:integrationTest --tests '*DeadInstanceRetryIT'`
Expected: the first test FAILS with 5xx answers (round-robin sends about half to the refused port). The second may already pass; it guards the fix.

- [ ] **Step 3: Find the mechanism that re-picks an instance, then apply it**

Read the Spring Cloud Gateway Server WebMVC jar on the classpath (`RetryFilterFunctions` and its config) and the LoadBalancer retry support. The fix must (a) retry only on `java.net.ConnectException`, (b) choose an instance per attempt, (c) allow POST (the body was never sent), (d) never retry a read timeout, a 5xx or any other IOException. Record the class and property that give (b), with the jar version, in the report.

Expected shape on both merchant routes, if the `Retry` filter re-resolves `lb://` per attempt:

```yaml
                # Only a refused connection: the request never reached a service, so another instance is safe.
                - name: Retry
                  args:
                    retries: 2
                    methods: GET,POST
                    series: ''
                    exceptions: java.net.ConnectException
                    cacheBody: true
```

If it does not re-resolve, use the LoadBalancer's own retry restricted to the same exception class, and say so in the report.

- [ ] **Step 4: Run green, then gateway `check`**

Run: `./gradlew :services:gateway-svc:check`
Expected: PASS, including `RouteCoverageIT` and `UpstreamFailureIT` (the silent-socket route still answers 504 after one attempt).

- [ ] **Step 5: Commit** (`Gateway: retry a merchant request only on a refused connection`)

---

### Task 3: Liquibase lock that dies with its connection (R3)

**Files:**
- Create: `persistence/src/main/java/io/ezeebit/persistence/AdvisoryLockService.java`
- Create: `persistence/src/main/resources/META-INF/services/liquibase.lockservice.LockService`
- Modify: `build.gradle`
- Test: `services/ledger-svc/src/acceptanceTest/java/io/ezeebit/ledger/AdvisoryLockIT.java` (create; ledger-svc is the one owner of the shared class's test)

**Interfaces:**
- Produces: `io.ezeebit.persistence.AdvisoryLockService extends liquibase.lockservice.StandardLockService`, `public static final long KEY`, priority above every core lock service, `supports` only `PostgresDatabase`.

- [ ] **Step 1: Write the failing IT**

Against the module's Testcontainers PostgreSQL (`TestcontainersConfiguration`), connecting as the `ledger` role. `runLiquibase(Duration wait)` runs Liquibase `update` on ledger's master changelog through `liquibase.command.CommandScope`, with the lock wait set from `wait` (Liquibase's unit is whole minutes: set the lock service's `setChangeLogLockWaitTime(wait.toMillis())` via a scoped `LockServiceFactory` lookup, or set the `liquibase.changelogLockWaitTimeInMinutes` global to 1 and bound the call with an `assertTimeoutPreemptively`). Raw `DriverManager` connections, so no pool hides a session.

```java
@Test
void theFactoryChoosesTheAdvisoryLockForPostgres() {
	assertThat(LockServiceFactory.getInstance().getLockService(database())).isInstanceOf(AdvisoryLockService.class);
}

@Test
void aStaleLockRowFromAKilledMigratorDoesNotBlockTheNextStart() {
	runLiquibase(Duration.ofSeconds(5));
	jdbc.update("UPDATE databasechangeloglock SET locked = TRUE, lockgranted = now(), lockedby = 'killed-pod' WHERE id = 1");
	assertTimeoutPreemptively(Duration.ofSeconds(20), () -> runLiquibase(Duration.ofSeconds(5)));
}

@Test
void twoMigratorsAtOnceSerialiseOnTheAdvisoryLock() throws Exception {
	try (Connection holder = connect()) {
		holder.createStatement().execute("SELECT pg_advisory_lock(%d)".formatted(AdvisoryLockService.KEY));
		CompletableFuture<Void> second = CompletableFuture.runAsync(() -> runLiquibase(Duration.ofSeconds(60)));
		Thread.sleep(1500);
		assertThat(second).isNotDone();
		holder.createStatement().execute("SELECT pg_advisory_unlock(%d)".formatted(AdvisoryLockService.KEY));
		second.get(30, TimeUnit.SECONDS);
	}
}

@Test
void theLockIsReleasedWhenItsConnectionDies() throws Exception {
	Connection doomed = connect();
	doomed.createStatement().execute("SELECT pg_advisory_lock(%d)".formatted(AdvisoryLockService.KEY));
	int pid = pidOf(doomed);   // SELECT pg_backend_pid()
	try (Connection admin = connectAsAdmin()) {
		admin.createStatement().execute("SELECT pg_terminate_backend(%d)".formatted(pid));
	}
	assertTimeoutPreemptively(Duration.ofSeconds(20), () -> runLiquibase(Duration.ofSeconds(5)));
}
```

- [ ] **Step 2: Run red**

Run: `./gradlew :services:ledger-svc:integrationTest --tests '*AdvisoryLockIT'`
Expected: compile failure first. Then with an empty `AdvisoryLockService extends StandardLockService` (priority 0, so not chosen), the stale-row test FAILS with `Could not acquire change log lock. Currently locked by killed-pod`. Paste that line into the report.

- [ ] **Step 3: Implement**

```java
package io.ezeebit.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import liquibase.database.Database;
import liquibase.database.core.PostgresDatabase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LockException;
import liquibase.lockservice.DatabaseChangeLogLock;
import liquibase.lockservice.StandardLockService;

// A lock row outlives a SIGKILLed migrator and blocks every later pod; a session advisory lock dies with its connection.
public class AdvisoryLockService extends StandardLockService {

	public static final long KEY = 0x657a656562697400L;

	private boolean held;

	@Override
	public int getPriority() {
		return PRIORITY_DATABASE + 10;
	}

	@Override
	public boolean supports(final Database database) {
		return database instanceof PostgresDatabase;
	}

	@Override
	public boolean hasChangeLogLock() {
		return held;
	}

	@Override
	public void waitForLock() throws LockException {
		if (!held) {
			execute("SELECT pg_advisory_lock(%d)".formatted(KEY));
			held = true;
		}
	}

	@Override
	public boolean acquireLock() throws LockException {
		if (!held) {
			held = execute("SELECT pg_try_advisory_lock(%d)".formatted(KEY));
		}
		return held;
	}

	@Override
	public void releaseLock() throws LockException {
		if (held) {
			execute("SELECT pg_advisory_unlock(%d)".formatted(KEY));
			held = false;
		}
	}

	@Override
	public DatabaseChangeLogLock[] listLocks() {
		return new DatabaseChangeLogLock[0];
	}

	@Override
	public void forceReleaseLock() throws LockException {
		releaseLock();
	}

	@Override
	public void reset() {
		held = false;
	}

	private boolean execute(final String sql) throws LockException {
		try {
			Connection connection = ((JdbcConnection) database.getConnection()).getUnderlyingConnection();
			try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
				return result.next() && result.getBoolean(1);
			}
		}
		catch (Exception failure) {
			throw new LockException(failure);
		}
	}

}
```

`pg_advisory_lock` returns void, which reads as false, so `waitForLock` sets `held` itself. The inherited `init()` still creates the lock table, so operators who look for it find it. Services file content, one line: `io.ezeebit.persistence.AdvisoryLockService`.

`build.gradle`, after the block that adds `observability/` to every service:

```groovy
// One copy of the migration lock, compiled into the two services that own a database; see persistence/.
configure([project(':services:ledger-svc'), project(':services:conversion-svc')]) {
	sourceSets.main.java.srcDir rootProject.file('persistence/src/main/java')
	sourceSets.main.resources.srcDir rootProject.file('persistence/src/main/resources')
}
```

Give `persistence/` the same Sonar, SpotBugs, Checkstyle and JaCoCo treatment `observability/` has (ledger-svc owns it for Sonar). If SpotBugs flags the formatted SQL, the argument is a compile-time constant: add a narrow `<Match>` with that reason, like the existing entries.

- [ ] **Step 4: Run green, then both services' `check`**

Run: `./gradlew :services:ledger-svc:check :services:conversion-svc:check`
Expected: PASS.

- [ ] **Step 5: Commit** (`Liquibase lock on a PostgreSQL session advisory lock: a killed migrator no longer blocks later pods`)

---

### Task 4: Replica verdicts written where they apply (R5, R6)

**Files:**
- Modify: `services/config-svc/src/main/resources/served/conversion-svc.yml` (above `resilience4j.circuitbreaker`)
- Modify: `services/config-svc/src/main/resources/served/application.yml` (beside `defaultZone`)

- [ ] **Step 1:** Above the circuit breaker block:

```yaml
# [!REPLICA-LOCAL] Each conversion replica's breaker counts its own calls to ledger-svc. Accepted: a ledger outage
# opens every replica's breaker within its own window, and dashboards read the state with max() across replicas.
```

- [ ] **Step 2:** Beside `defaultZone`: `# One registry and one config server by design: Eureka here is not peer-aware. k8s gates both at replicas 1.`
- [ ] **Step 3:** `./gradlew :services:config-svc:test` PASS. Commit (`Replica verdicts: per-replica breakers accepted, one registry and one config server`).

---

### Task 5: Cluster, data plane, observability, and `kube.sh up|down|status`

**Files:**
- Create: `k8s/kind-cluster.yaml`, `k8s/lib/common.sh`, `k8s/kube.sh`
- Create: `k8s/base/namespaces/{kustomization.yaml,namespaces.yaml}`
- Create: `k8s/base/data/{kustomization.yaml,postgres.yaml,redis.yaml,kafka.yaml}`, `k8s/base/app/wiremock.yaml`
- Create: `k8s/base/obs/{kustomization.yaml,lgtm.yaml}`
- Create: `k8s/overlays/local/kustomization.yaml`
- Modify: `.gitignore` (add `.kube-fleet/`)

**Interfaces:**
- Produces: `k8s/lib/common.sh` functions `kctl` (kubectl with `--context kind-ezeebit`), `log`, `fail <msg>` (exit 1), `port_holder <port>` (prints the holding process or nothing), `compose_running` (0 when any container of this compose project runs, found by the compose project label, read-only); `kube.sh up [--no-build] [--only <stages>] | down | status`; namespaces `ezeebit-data`, `ezeebit-app`, `ezeebit-obs`, `ezeebit-chaos`; Services `postgres.ezeebit-data:5432`, `redis.ezeebit-data:6379`, `kafka.ezeebit-data:9092`, `wiremock.ezeebit-app:8080`, `lgtm.ezeebit-obs:{3000,4317,4318}`. Every object labelled `app.kubernetes.io/name=<name>` and `app.kubernetes.io/part-of=ezeebit`.

- [ ] **Step 1: Cluster definition**

```yaml
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
name: ezeebit
nodes:
  - role: control-plane
    image: kindest/node:<tag>@sha256:<digest>
    extraPortMappings:
      - {containerPort: 30080, hostPort: 8080, listenAddress: "127.0.0.1", protocol: TCP}
      - {containerPort: 30300, hostPort: 3000, listenAddress: "127.0.0.1", protocol: TCP}
```

Pin the node image to the default that the installed kind release documents (the release notes on GitHub list tag and digest for `kind version`'s release). Record the source URL in the report.

- [ ] **Step 2: Data plane**

PostgreSQL: StatefulSet `postgres`, 1 replica, image byte-equal to compose, `POSTGRES_USER`/`POSTGRES_PASSWORD` from Secret `postgres-admin`, `args: ["-c", "max_connections=100"]`, PVC 2Gi at `/var/lib/postgresql`, initdb from ConfigMap `postgres-initdb` (kube.sh creates it from `docker/postgres/initdb/`), readiness `pg_isready -U $POSTGRES_USER`. Redis: Deployment, image byte-equal, the compose command with `REDIS_PASSWORD` from Secret `redis`. Kafka: StatefulSet, image byte-equal, compose's KRaft env except listeners `PLAINTEXT://:9092,CONTROLLER://:9093`, advertised `PLAINTEXT://kafka.ezeebit-data.svc:9092`, quorum `1@localhost:9093`, PVC 2Gi at `/var/lib/kafka/data`; plus a Job `kafka-topics` that creates the dead-letter topic with exactly the name, partitions and retention the compose setup uses (find them in `fleet.sh`, compose or the conversion config, and cite the file). WireMock: Deployment in `ezeebit-app`, image byte-equal, mappings from ConfigMap `wiremock-mappings` (from `docker/wiremock/mappings/`).

- [ ] **Step 3: Observability**

Deployment `lgtm` in `ezeebit-obs`, image byte-equal, command `/ezeebit/start.sh`, the `docker/lgtm` tree mounted at `/ezeebit` and `dashboards.yaml` at the path compose mounts it, PVC for `/data`, `SLACK_WEBHOOK_URL` from Secret `slack` key `url` with `optional: true`. Service NodePort 30300 for 3000 and ClusterIP 4317/4318. A ConfigMap cannot hold a directory tree: kube.sh creates one ConfigMap per directory of `docker/lgtm` with `kubectl create configmap ... --from-file=<dir> --dry-run=client -o yaml | kctl apply --server-side -f -` and the Deployment mounts each with `subPath` or a projected volume restoring the tree. List the ConfigMaps and their sizes in the report (each must stay under 1 MiB; split dashboards if needed).

- [ ] **Step 4: `common.sh` and `kube.sh`**

`kube.sh up`:
1. Refuse when `compose_running` or when `port_holder 8080` / `port_holder 3000` names anything but the `ezeebit-control-plane` container: `fail "<port> is held by <holder>; stop it first (./fleet.sh down if it is the compose fleet)"`.
2. Create the cluster when `kind get clusters` lacks `ezeebit`: `kind create cluster --name ezeebit --config k8s/kind-cluster.yaml --wait 120s`.
3. Unless `--no-build`: `./gradlew bootBuildImageAll`; then `kind load docker-image --name ezeebit ezeebit/<svc>:<version>` for the five (version read from `build.gradle`).
4. Secrets from the caller's environment with compose's committed defaults, via `kubectl create secret generic <name> --from-literal=... --dry-run=client -o yaml | kctl apply -f -`: `postgres-admin`, `redis`, `ledger-db`, `conversion-db`, `gateway-proof`, and `slack` only when `SLACK_WEBHOOK_URL` is non-empty (delete it otherwise). Never echo a value; `set +x` around it.
5. ConfigMaps from `docker/` files, then `kctl apply -k k8s/overlays/local`.
6. Wait in order: data, obs, then app (`kctl rollout status` per workload, 300s each); print a table of workload, ready/desired, and the command's own exit code.

`down`: `kind delete cluster --name ezeebit`, then remove `.kube-fleet/`. `status`: pods per namespace and the HPA table. All run state under `.kube-fleet/`.

- [ ] **Step 5: Red-proof the refusal**

`./fleet.sh up`, then `./k8s/kube.sh up --no-build; echo exit=$?`. Expected: exit 1 and the holder named. Paste both lines into the report. Then `./fleet.sh down`.

- [ ] **Step 6:** `./k8s/kube.sh up --no-build --only data,obs`, confirm Ready and Grafana `curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:3000/api/health` is 200, then `./k8s/kube.sh down`. Commit (`k8s: kind cluster, data plane, observability and kube.sh up/down/status`).

---

### Task 6: The five services, probes, PDBs, HPAs and metrics-server

**Files:**
- Create: `k8s/base/app/{kustomization.yaml,config-svc.yaml,eureka-svc.yaml,gateway-svc.yaml,ledger-svc.yaml,conversion-svc.yaml,hpa.yaml,pdb.yaml}`
- Create: `k8s/base/metrics-server/{kustomization.yaml,metrics-server.yaml}` (pinned upstream release manifest, with `--kubelet-insecure-tls` added because kind's kubelet serves self-signed certificates; cite the release URL)

**Interfaces:**
- Consumes: Task 1 timings, Task 5 Services and Secrets.
- Produces: Deployments and Services named as the services in `ezeebit-app`; gateway NodePort 30080.

- [ ] **Step 1: One Deployment shape for all five**

```yaml
spec:
  replicas: <n>
  strategy: {type: RollingUpdate, rollingUpdate: {maxUnavailable: 0, maxSurge: 1}}
  template:
    spec:
      terminationGracePeriodSeconds: 40
      containers:
        - name: <svc>
          image: ezeebit/<svc>:<version>
          imagePullPolicy: Never
          ports: [{name: http, containerPort: <port>}]
          env:
            - {name: SERVER_ADDRESS, valueFrom: {fieldRef: {fieldPath: status.podIP}}}
            - {name: HOSTNAME, valueFrom: {fieldRef: {fieldPath: metadata.name}}}
          resources: {requests: {cpu: 250m, memory: 512Mi}, limits: {cpu: "2", memory: 1Gi}}
          startupProbe: {httpGet: {path: /actuator/health/liveness, port: http}, periodSeconds: 5, failureThreshold: 60}
          livenessProbe: {httpGet: {path: /actuator/health/liveness, port: http}, periodSeconds: 10}
          readinessProbe: {httpGet: {path: /actuator/health/readiness, port: http}, periodSeconds: 5}
          lifecycle:
            # Eureka expiry (15s) plus a client fetch (5s) is when callers stop choosing this pod; until then it keeps
            # serving, and graceful shutdown (20s) then drains what is in flight.
            preStop: {sleep: {seconds: 15}}
```

| Service | Replicas | Ports | Extra env |
|---|---|---|---|
| config-svc | 1 | 8888 | none |
| eureka-svc | 1 | 8761 | none |
| gateway-svc | 2 | 8080, NodePort 30080 | `REDIS_HOST=redis.ezeebit-data`, `REDIS_PASSWORD`, `GATEWAY_SHARED_SECRET` |
| ledger-svc | 2 | 8081, gRPC 9081 | `DB_URL=jdbc:postgresql://postgres.ezeebit-data:5432/ledger`, `DB_USER`, `DB_PASSWORD` |
| conversion-svc | 2 | 8082 | `DB_URL=jdbc:postgresql://postgres.ezeebit-data:5432/conversion`, `DB_USER`, `DB_PASSWORD`, `RATES_URL=http://wiremock.ezeebit-app:8080`, `KAFKA_BOOTSTRAP=kafka.ezeebit-data.svc:9092` |

Every service but config-svc: `CONFIG_SERVER_URL=http://config-svc.ezeebit-app:8888`. Every service but eureka-svc: `EUREKA_URL=http://eureka-svc.ezeebit-app:8761/eureka/`. Every service: `OTLP_URL` pointing at `lgtm.ezeebit-obs` in the exact form `observability/src/main/resources/config/application.yml` expects. If config-svc or eureka-svc has no readiness health group, check `management.endpoint.health` before choosing its readiness path. A variable this table misses shows in the startup log: add it and list it in the report.

- [ ] **Step 2: HPAs and PDBs** per Global Constraints; PDB `minAvailable: 1` for gateway, ledger and conversion only.
- [ ] **Step 3: Bring up the whole fleet** with `./k8s/kube.sh up`. Then `curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/actuator/health` expects 200, and within 2 minutes `kctl get hpa -A` shows a percentage, not `<unknown>`. Paste both.
- [ ] **Step 4:** `./k8s/kube.sh down`. Commit (`k8s: the five services at their replica counts, probes, PDBs, HPAs and metrics-server`).

---

### Task 7: Network policies

**Files:**
- Create: `k8s/base/policy/{kustomization.yaml,default-deny.yaml,app.yaml,data.yaml,obs.yaml,chaos.yaml}`

- [ ] **Step 1:** Default deny ingress and egress in all four namespaces; DNS egress to `kube-system` UDP/TCP 53 everywhere.
- [ ] **Step 2:** Allow exactly the edges in spec section 4, selecting pods by `app.kubernetes.io/name` and namespaces by `kubernetes.io/metadata.name`. The chaos namespace's egress is the API server only (TCP 443 and 6443 to `0.0.0.0/0`, commented: an ipBlock cannot name a Service, so RBAC is the control). Ingress from the node to the gateway and lgtm NodePorts, and to every pod's probe port from the node.
- [ ] **Step 3: Prove it enforces.** After `./k8s/kube.sh up --no-build`: an ephemeral pod in `ezeebit-app` labelled `app.kubernetes.io/name=policy-probe` (a label no workload selects) gets `closed` for `postgres.ezeebit-data:5432`; the same probe labelled as ledger-svc's DB client would be a selector clash, so the allowed arm runs inside a real ledger pod with `kctl debug --target=ledger-svc` and gets `open`. Paste both. `down`. Commit (`k8s: default-deny network policy and the fleet's edges`).

---

### Task 8: Chaos monkey (paused) and `kube.sh chaos on|off|run|status`

**Files:**
- Create: `k8s/chaos/monkey.sh`, `k8s/base/chaos/{kustomization.yaml,rbac.yaml,cronjob.yaml}`
- Modify: `k8s/kube.sh` (chaos subcommands; ConfigMap `chaos-monkey` from `k8s/chaos/monkey.sh`)

**Interfaces:**
- Produces: `monkey.sh [--victims N] [--service S]`: one line per kill `KILL <namespace>/<pod> service=<svc>`, then `KILLED <n>`; exit 1 when `n` is below N; exit 2 when asked for a namespace outside `ezeebit-app` and `ezeebit-data`.

- [ ] **Step 1:** monkey.sh: services = Deployments in `ezeebit-app` and StatefulSets in `ezeebit-data` labelled `app.kubernetes.io/part-of=ezeebit`; draw a service uniformly, then one Running pod of it uniformly; `kubectl delete pod --grace-period=0 --force`; count kills. In-cluster it uses its service account; from the host kube.sh exports `KCTL_CONTEXT=kind-ezeebit` and monkey.sh adds `--context` when it is set. Write that branch once.
- [ ] **Step 2:** CronJob `chaos-monkey` in `ezeebit-chaos`: `schedule: "*/10 * * * *"`, `suspend: true`, `concurrencyPolicy: Forbid`, `ttlSecondsAfterFinished: 3600`, a kubectl image pinned by tag and digest (cite its source), script mounted from ConfigMap `chaos-monkey`. RBAC per spec section 5: namespaced Roles and RoleBindings in `ezeebit-app` and `ezeebit-data`, nothing else.
- [ ] **Step 3:** `kube.sh chaos on|off` patch `suspend` and read it back; `run` creates `chaos-manual-<epoch>` from the CronJob, waits for completion and prints its log; `status` prints suspend, last schedule time and the last Job's `KILL` lines.
- [ ] **Step 4: Red-proof.** `k8s/chaos/monkey.sh --victims 1 --service does-not-exist; echo exit=$?` gives exit 1 with `KILLED 0`. With the fleet up: `kctl auth can-i delete pods -n ezeebit-obs --as=system:serviceaccount:ezeebit-chaos:chaos-monkey` is `no`; `get secrets -n ezeebit-app` is `no`; `delete pods -n ezeebit-app` is `yes`; `kube.sh chaos run` prints one `KILL` and `KILLED 1`. Paste all. `down`. Commit (`k8s: paused chaos monkey with namespaced RBAC and kube.sh chaos on/off/run/status`).

---

### Task 9: Build gates (`checkK8s`) and the sweep

**Files:**
- Create: `k8s/gates/{fleet-separation.sh,image-parity.sh,replicas.sh,pool-budget.sh}`
- Create: `k8s/sweep/{parity.sh,money.sh,replicas.sh,policy.sh,chaos.sh,outage.sh,monitoring.sh}`
- Modify: `build.gradle` (`checkK8s` wired into root `check`), `k8s/kube.sh` (`sweep`)

- [ ] **Step 1: Gates (static, no cluster), each taking the repository root as `$1`**
  - fleet-separation: `k8s/**` holds no `docker compose`, `fleet.sh`, `pgdata`, `kafkadata` or `lgtmdata`; `fleet.sh` and `compose.yaml` hold no `kind`, `kubectl` or `.kube-fleet`. Word-bounded; print each hit as file:line.
  - image-parity: every non-`ezeebit/` image in `k8s/base/**` (the chaos kubectl image and metrics-server excepted, listed by name in the gate) appears verbatim in `compose.yaml`, and every compose image appears in `k8s/base/**`.
  - replicas: config-svc and eureka-svc `replicas: 1` and no HPA targets them; gateway, ledger and conversion `replicas: 2` with an HPA each at the Global Constraints bounds.
  - pool-budget: HikariCP pool per DB service (10 unless `maximum-pool-size` appears in served config) times its HPA maxReplicas, summed, plus 10, is at most the `max_connections` in `postgres.yaml`.
  - Each prints `PASS <gate>` or `FAIL <gate>: <why>` and exits 0 or 1; `checkK8s` runs all four and fails when any fails.
- [ ] **Step 2: Red-proof each gate.** Copy the needed files into a temp root, make one violating edit, run the gate against it, expect `FAIL`. Paste the four FAIL lines. Then `./gradlew checkK8s` PASS.
- [ ] **Step 3: Sweep checks.** Each is a function that returns 0 only on an enumerated pass; an empty or unrecognised answer is FAIL. The services bind the pod IP, so read per-pod numbers from Prometheus in lgtm (by `instance`, which is the pod name), through Grafana's datasource proxy on 127.0.0.1:3000.
  - parity: live image tags against compose; config-svc and eureka-svc at 1 replica; each HPA condition `ScalingActive=True`.
  - money: mirror `fleet.sh`'s real conversion check through `http://127.0.0.1:8080/api/v1/...` (the internal deposit via `kctl exec` into a ledger pod, against its pod IP), then read the balance back.
  - replicas: 60 requests through the gateway, then `sum by (instance) (increase(http_server_requests_seconds_count{job="<svc>"}[2m]))` for gateway, ledger and conversion: all six pod names present with counts above 0. Shared limit: a burst of capacity+10 requests for one merchant; admitted at most capacity plus refill over the elapsed time, and both gateway pods' instances recorded requests. Write-read: create a conversion, read it 10 times; both conversion instances served reads.
  - policy: Task 7's two probes.
  - chaos: background traffic (quotes and conversions with unique idempotency keys, one line per response with status and epoch ms); `monkey.sh --service <svc>` for gateway, ledger and conversion; require three `KILL` lines; stop traffic 30s after the last kill; audit via `kctl exec` into postgres: the ledger's conversion postings `count(*) = count(DISTINCT <business key>)` and conversions `count(*) = count(DISTINCT (merchant_id, idempotency_key))` (read the table and column names from the changelogs); report the 5xx count and each 5xx's delay after the latest kill; FAIL on any 5xx later than 20s after a kill.
  - outage: read ledger-svc `.spec.replicas`, scale to 0, drive conversions until dead letters are recorded, poll Grafana's alert rules API until `ConversionLedgerDeadLettered` is Alerting (deadline: the rule's `for` plus 120s); when Secret `slack` exists, `grafana_alerting_notifications_total{integration="slack"}` must rise; restore the read count and read it back.
  - monitoring: lgtm restart count equal before and after; Grafana `/api/health` 200 at every check boundary.
- [ ] **Step 4:** `kube.sh sweep`: `down` if the cluster exists, `up`, the checks in spec order, each appending `<check> exit=<rc>` to `docs/k8s/verification/<date>-sweep.log`, `down`, exit non-zero if any check failed. In this task do NOT run the whole sweep: `kube.sh up`, run each check once by hand to show it passes, scale wiremock to 0 and show money FAIL, restore, `down`. Commit (`k8s: build gates and the sweep`).

---

### Task 10: Docs

**Files:**
- Create: `docs/k8s.md`
- Modify: `README.md` (table-of-contents link and a short "Run on Kubernetes" section pointing to `docs/k8s.md`), `ARCHITECTURE.md` (runtime topology: the cluster and fixes R1 to R6), `docs/project-layout.md` (`k8s/` and `persistence/` in the repository tree)

- [ ] **Step 1:** `docs/k8s.md`: prerequisites with the versions observed; `kube.sh up|down|status|sweep`; the two-fleet separation table; namespaces and workloads; R1 to R6 with what each test proves; the chaos monkey with exactly:

```bash
./k8s/kube.sh chaos status   # suspended or not, last run, last victims
./k8s/kube.sh chaos on       # unpause: a random pod is killed every 10 minutes
./k8s/kube.sh chaos off      # pause again (the shipped state)
./k8s/kube.sh chaos run      # one run now, whatever the schedule says
```

  and what the sweep proves and does not (no mesh, no secret store, single node, arm64 images on this machine, one environment).
- [ ] **Step 2:** Every link and anchor resolves; every number has its command or the sweep log beside it; no other project named; no em dash. Commit (`Docs: Kubernetes guide, README link, architecture and layout`).

---

### Task 11: The sweep (the controller runs it)

- [ ] **Step 1:** `./fleet.sh down` if it is up, then `./k8s/kube.sh sweep; echo exit=$?` in the background with the log captured.
- [ ] **Step 2:** On FAIL: fix on the branch (red test first where the cause is code), run again. Each failed run is listed in `docs/k8s.md` with its cause.
- [ ] **Step 3:** Commit the passing log (`k8s: the passing sweep`).
