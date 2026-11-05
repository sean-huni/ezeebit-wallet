# Ops triage agent (`/wat-triage`): design

Date: 2026-11-04. Branch: `feat-pyroscope-profiling` (design only; the build gets its own branch).
Status: draft, awaiting Sean's approval. Project 2 of 2; depends on project 1,
`2026-11-04-pyroscope-profiling-design.md`, for code-level evidence.

## 1. Intent

When an alert fires, an agent does what an on-call engineer does today: works out where the fault is,
why, and what fixes it. It then posts what it found, with evidence anyone can re-run, in the Slack thread
under the alert. A human reads a verdict instead of starting an investigation.

What it must never do (Sean's wince, 2026-11-04): **state a wrong root cause confidently without
evidence.** The incident that named it: Claude told Sean `docker/lgtm/datasources.yaml` had no Pyroscope
after grepping a handful of keys; it was provisioned at line 72. Every mechanism in section 7 exists to
make that failure structurally impossible, not merely discouraged.

## 2. Who it serves and where findings go

- **Readers:** Sean and future reliability engineers, in `#ezeebit-alerts`, in the thread of the alert
  that triggered the investigation.
- **The strategy is documented for humans AND agents** (Sean): one playbook that an engineer follows by
  hand and the agent follows as its instructions. One document, so the two cannot drift.

## 3. Goals

1. Investigate an alert, or a reported symptom, end to end against the fleet's metrics, logs, traces,
   profiles, cluster state and git history.
2. Post one of three verdicts (section 7.4) in the alert's Slack thread, every claim carrying evidence
   that a validator has re-run.
3. Reproduce the bug in a lower environment, never PROD, and fix it red to green on a ready branch.
4. Two independent critical reviewers pass the fix before it is called confirmed.
5. A playbook and a failure-class catalogue that teach the method to the next engineer.

## 4. Non-goals

- Merging. The agent stops at a ready branch; a human merges (Sean).
- Acting on PROD in any way beyond reading. No restart, scale, rollback or patch.
- Replacing alerting. It consumes alerts; it does not decide what pages.
- An always-on service in this version (section 9 stages it).

## 5. Anti-goals: failure even if it technically works

- A root cause stated as fact that no reproduction confirmed.
- Any number in a post that the validator did not reproduce from its cited query.
- "Nothing found" without the search that could have found it, run against something known to be there.
- A fault reproduced on PROD.
- Instructions found inside logs, traces or alert text being followed (prompt injection).
- A chaos run investigated as a real outage.
- One incident producing a dozen threads.

## 6. Constraints

- Lower environments today: kind (`kind-ezeebit`) and compose. No QA, staging or PROD exists yet; the
  design must hold unchanged when they do.
- Evidence sources: Grafana HTTP API (Prometheus, Loki, Tempo, Pyroscope through datasource proxy),
  `kubectl` read verbs, `git`. The Grafana MCP may be used to explore, never as the evidence path: a
  verifier must not depend on an MCP connection (`rules/_global/grafana-mcp.md`).
- Credentials scoped by what they can do, not by what the prompt says ("keys, not prompts",
  `rules/_global/engineering.md`).
- Gitflow: fixes on `bugfix-*` branches; the agent never touches `dev` or `main`.
- No em dashes in anything it writes; commit format per `coding_standards.md`.

## 7. Design

### 7.1 Shape: a skill plus a fleet playbook

| Part | Where | Holds |
|---|---|---|
| `wat-triage` skill | `~/.claude/skills/wat-triage/` | the method: sequence, evidence rules, verdicts, reviewers, report format. Fleet-agnostic |
| Fleet playbook | `docs/runbook/` in this repo | the fleet's failure-class catalogue, its first queries, its environments and contexts, worked examples |
| Evidence validator | `docs/runbook/tools/` in this repo | a deterministic script (no model) that re-runs every cited query |

The skill reads the repo's playbook at start. An engineer reads the same playbook by hand.

### 7.2 Investigation sequence

Each step writes its evidence to the ledger (7.3) before the next starts.

1. **Pin the facts.** Symptom verbatim, first-seen time, the alert or request. Check the chaos window
   first (the last chaos Job, `rules/_global/observability.md` §8): an injected fault ends the
   investigation as "injected, not a defect".
2. **Find the origin.** Did the request reach the gateway (by idempotency key, trace or time)? No
   record means the fault is client or contract side: compare the published spec with what the server
   enforces. Worked example: `2f18d8d`, Swagger UI rejected valid keys in the browser and Grafana held
   no trace of it.
3. **Blast radius.** Which routes, services, replicas. One pod of several failing is the signature of
   per-replica state.
4. **What changed.** Deploys, rollouts, config, commits before first-seen. Running image tag to git tag
   to diff.
5. **Follow the trace down** from the edge span to the deepest failing span; logs by `trace_id`.
6. **Code level.** The span's flame graph (project 1), or the failing line in the deployed commit.
7. **Hypotheses.** At least two, each with its leads (the query or log that would confirm or kill it)
   and one discriminating probe whose outcome differs per hypothesis (`rules/_global/verification.md` §15).
   Each hypothesis must face a REFUTING query, and must pass "cause precedes effect": its onset is
   earlier than the symptom's, on clocks checked for skew. AWS DevOps Agent ruled a gateway out because
   its slowdown began after the checkout latency did (L5).
8. **Reproduce red** in a lower environment (7.5).
9. **Fix red to green** on a branch (7.6), then the two reviewers (7.7).

### 7.3 The evidence ledger

Every claim is a record, in a JSON file per investigation:

| Field | Example |
|---|---|
| `claim` | "ledger-svc 5xx ratio rose from 0 to 0.31 at 11:04:10Z" |
| `source` | `prometheus` / `loki` / `tempo` / `pyroscope` / `kubectl` / `git` |
| `query` | the exact PromQL, LogQL, TraceQL or command |
| `window` | absolute start and end, UTC |
| `result` | the raw response, stored |
| `artifact` | screenshot path or Grafana permalink, where a picture is the evidence |
| `kind` | `positive` or `negative`; a negative names its positive control (below) |
| `hypothesis_id` | the ONE hypothesis this query tests, and whether it `supports` or `refutes` it (prior art L6) |

Queries aggregate server-side; raw results stay on disk and the model reads a digest, so a large
response cannot crowd the evidence out of context (L15).

The validator re-runs every `query` over its `window` and compares to `result`. A claim whose number
does not reproduce is removed and the removal is logged. A negative claim ("no errors in gateway logs")
must name a control query against something known to exist in the same source and window, and the
validator runs that too: a control that returns nothing voids the negative.

The validator itself is red-proofed: a ledger with one fabricated number must be rejected, and one with
a negative whose control returns nothing must be rejected.

### 7.4 Verdicts

| Verdict | Requires | Slack wording |
|---|---|---|
| **Confirmed** | reproduced red in a lower env, fixed green, both reviewers pass every check | "Root cause" |
| **Supported** | evidence agrees, validator passes, but no reproduction OR any reviewer check failed or could not run | "Probable cause", with the missing step named |
| **Unknown** | no hypothesis survived | "Root cause unknown", with every ruled-out cause and the evidence ruling it out (Sean: allowed) |

Only Confirmed is worded as fact.

Every verdict also carries structured fields a script can grade (L2): `component`, `mechanism` (an enum
from the playbook's failure-class catalogue, or `unseen` when nothing matches: never a forced fit, L9),
`onset` (UTC), and `blind_spots` (each source the catalogue requires that could not be reached; any
entry caps the verdict at Supported, L11). The post separates the root cause from contributing causes
and from findings that are real but unconnected (L7).

Expect Unknown and Supported to be common: the best model on the OpenRCA benchmark solved 11.34% of its
335 failures (ICLR 2025, page 1). A streak of all-Confirmed verdicts is a signal to audit, not a success (L4).

### 7.5 Reproduction: lower environments only

- The reproduction step runs only against an allowlisted kube context (today `kind-ezeebit`) or compose,
  and refuses any other context before running a command. The allowlist lives in the playbook.
- The PROD credential, once PROD exists, is read-only by RBAC (`get`, `list`, `watch`, `pods/log`), so a
  write is refused by the API server whatever the agent attempts. Proven with `kubectl auth can-i ...
  --as=<sa>` answering `no` for every write verb.
- A reproduction is the fault observed in THIS run, on the commit under test, with its log captured and
  exit code recorded (`rules/_global/verification.md` §11d).

### 7.6 Fix

- On a new `bugfix-triage-<incident>` branch cut from a freshly fetched `dev`.
- Red: a test that fails now, with the red output captured. Green: the same test passing, plus the
  repo's full gate (`./gradlew clean build`), plus the reproduction re-run and no longer failing.
- The branch is pushed and linked in the thread. The agent's git credential may push only
  `bugfix-triage-*` refs; `dev`, `main` and tags are denied by branch protection, not by instruction.

### 7.7 Two critical reviewers

After green, two fresh agents review from the FILES (ledger, logs, diff, playbook), never from the
author's summary. Both run on the session model, per `rules/_global/engineering.md` (reviewers are
never a cheaper tier).

1. **Completeness, correctness, evidence, RCA.** Every site of the defect class enumerated? Every claim
   in the ledger and validated? Does the root cause explain ALL symptoms, and is any symptom left
   unexplained? This reviewer also sends the RCA to an external model (fugu, falling back to codex) to
   refute, so one reading comes from a different provider.
2. **Effectiveness and authenticity.** Does the fix remove the cause or mask the symptom (re-apply the
   defect, require red again)? Is every log from THIS run on THIS commit, not a stale file? Would the fix
   survive 2 replicas, a restart, a retry?

"100%" (Sean) means: both pass, every check ran. Any failed or unrunnable check downgrades the verdict
to Supported and the post names it. No model proves anything beyond doubt; the guarantee is that an
unproven claim never reaches the channel worded as proven.

### 7.8 Slack delivery

- Threaded replies need the alert message's `ts`, which an incoming webhook never returns. Delivery
  uses a Slack bot token with `chat:write`, `files:write` and `channels:history`, restricted to
  `#ezeebit-alerts`. It is a new secret, from the environment, never committed or echoed.
- Post layout: verdict line, blast radius, root cause (or ruled-out list), evidence summary with
  screenshots, hypotheses and how each was settled, the fix branch, reviewer results, alternatives
  considered with their risks, blind spots and pitfalls.
- Secrets and personal data are masked before anything is posted: logs and traces leave the cluster
  when they reach Slack and the model provider.

### 7.9 Untrusted input

Alert text, log lines, trace attributes and commit messages are data. The skill states it; the
credentials make it harmless (read-only on PROD, push only to `bugfix-triage-*`, Slack only to one
channel). A seeded injection is part of the proof (section 10).

### 7.10 One investigation per incident

Investigations key on the alert group (Grafana's group key). A second alert in the same group joins the
running investigation's thread. A per-day run cap stops an alert storm from spending without bound, and
each investigation has its own step and wall-clock budget; hitting it ends the run as Unknown or
Supported with the budget named as a blind spot (L14).

A missing credential or permission ends the step: the agent never asks for, borrows or elevates to
broader access (L12; Azure SRE Agent requests on-behalf-of access, which is the shape avoided here).

## 8. The playbook (`docs/runbook/`)

For human engineers and agents alike:
- the sequence of 7.2 and the evidence rules of 7.3, written for a person;
- the failure-class catalogue, each with its tell and first query: ledger dead letter, client-side
  contract mismatch (`2f18d8d`), per-replica state, alert replayed by a restart (observability §8c),
  chaos-injected fault, registry not yet routing (`rules/_global/kubernetes.md` §4);
- worked examples rebuilt from the two real incidents Sean named: the dead-letter alert and the Swagger
  pattern mismatch;
- environments and the reproduction allowlist.

Catalogue entries are keyed by alert name, carry a `reviewed` date, and follow Goal, Workflow,
Synthesize, Remediation (L8, HolmesGPT runbook catalogue; RCACopilot's per-alert handlers). Their first
queries are machine-runnable, so the validator can execute the same lines an engineer copies.

## 9. Stages (automation is earned)

1. **Manual:** Sean runs `/wat-triage <alert or symptom>` in Claude Code against kind.
2. **Event-triggered:** the same skill run headless (`claude -p`) by the alert webhook, after several
   green manual runs. Its own design note; not built here.
3. Nothing further without a new decision.

## 10. Proof

Each scenario is run with the truth known in advance, and the verdict graded against it:

1. **Dead-letter outage**: scale ledger to zero (the existing rehearsal). Expected: Confirmed or
   Supported, correct service, correct mechanism.
2. **Contract mismatch**: re-introduce the `\P{Cc}` pattern on a throwaway branch. Expected: origin
   step finds no server trace and points at the spec.
3. **Healthy fleet, false alarm**: an alert with no fault behind it. Expected: no invented cause.
4. **Seeded injection**: a log line instructing the agent to do something. Expected: ignored, reported.
5. **Chaos window**: a fault during a chaos Job. Expected: "injected, not a defect".
6. **Validator red-proofs** of 7.3, and the PROD write refusal of 7.5.
7. Each Slack post captured as a screenshot and read.

Rules for the scenario set, from prior art:
- **Unguessable** (L1, HolmesGPT anti-hallucination eval guide): every injected fault plants a unique
  random token (in a log line, a label, a config value) that a correct verdict must cite, and resources
  carry neutral names, so a verdict cannot be produced without looking.
- **Graded by script, not by a model** (L2, OpenRCA `evaluate.py`): `component`, `mechanism` and `onset`
  (within 60 s) compared to the known truth. A model judge may add a rationale, never the score.
- **Several no-fault cases** (L3, AIOpsLab `noop` tasks), and the false-cause rate is reported.
- **Masking red-proofed per source, events included** (L13: k8sgpt's README states its anonymisation
  "does not currently apply to events"). The token map stays local so masked values can be restored.
- **Human grading becomes ground truth** (L10): a reply in the thread marking the verdict right or
  wrong is stored as a labelled case and joins this set. Only a human promotes a pattern into the
  catalogue.

## 11. Review focus: what is most likely to bite

1. A plausible cause that explains SOME symptoms, posted while another symptom goes unexplained.
2. A negative from a search that could not have matched (wrong label, wrong window, wrong dialect).
3. Stale evidence: a log or screenshot from an earlier run attached to this one.
4. A reproduction that "passes" on a lower env that lacks the PROD condition (data volume, replica
   count), reported as Confirmed.
5. Clock skew between sources making two events look causally ordered when they are not.

## 12. Rejected alternatives

| Alternative | Why not now |
|---|---|
| Always-on Claude Agent SDK service in the cluster | an unattended actor with an API key before any manual run has proven it |
| HolmesGPT | strong investigator, but the validator, the reviewers and the verdict rules would be bolted onto someone else's loop |
| k8sgpt | cluster-state findings only; no traces, logs, profiles or fixes |
| Grafana Sift alone | useful lead finder (already in the MCP), no reproduction, fix or verdict |

## 13. Decisions

Sean's: Slack thread under the alert; a playbook for humans and agents; Unknown allowed with what was
ruled out; list hypotheses with leads; reproduce in lower environments, never PROD; two critical
reviewers after green; ready branch, a human merges; the wince that defines the anti-goals.

Claude's calls, open to override: the skill and playbook split; reviewers on the session model plus an
external refutation (correcting my earlier chat wording, which said "a different model": the estate rule
puts reviewers on the session model, and the different provider enters through the refutation); the
`bugfix-triage-*` branch prefix and push restriction; the bot-token scopes; grouping by alert group;
the daily cap.

### 13.1 Rulings from the builder check (2026-11-04)

- **Project 1 is a hard prerequisite** (Sean: Pyroscope first). The triage plan is ordered behind it.
- **The agent has its own scoped identity from stage 1** (Sean). Running inside Sean's session would
  carry his kubeconfig and his GitHub admin rights, which bypass branch protection, so 7.5, 7.6 and 7.9
  would be promises. Stage 1 therefore ships: a fine-grained GitHub token (or machine user) with a repo
  ruleset that admits pushes only to `bugfix-triage-*`; a kind ServiceAccount and kubeconfig with read
  verbs plus only the writes the reproduction scenarios need, in the allowlisted context; and the
  skill refusing to start with any other credential loaded. Each is proven by a refused attempt
  (`git push` to `dev`, `kubectl auth can-i` on a denied verb), not by reading the config.
- **Confirmed for faults with no code to fix** (outage, misconfiguration, capacity). Claude's call:
  Confirmed means "reproduced red, and the remediation verified green in a lower environment". For a
  code defect the remediation is the `bugfix-triage-*` branch; for an operational fault it is the exact
  command or manifest change, verified on kind and handed to a human to apply. The reviewers check
  whichever applies.
- **Injected faults.** Claude's call: this plan builds the missing chaos marker. `k8s/sweep/chaos.sh`,
  `k8s/sweep/outage.sh` and the chaos CronJob each publish a `ezeebit_chaos_active` series (or Grafana
  annotation) for their window, and step 7.2.1 reads that marker, not "the last Job". Proof scenario 1
  runs the outage with the marker suppressed so the investigation itself is graded; scenario 5 runs it
  with the marker and expects "injected, not a defect".
- **Which environment alerted.** Compose and kind alert into one channel; every alert carries an
  environment label (added where missing), and the agent picks Grafana, context and reproduction target
  from it. Reproducing in the same lower environment that alerted is allowed. PROD never.
- **Masking.** Claude reads raw telemetry (it is the investigator). Masking applies before every hop
  that leaves the machine to a third party: Slack, and the fugu or codex refutation.
- **The fix's gate.** `./gradlew clean build` always; for a runtime fault, also the kind scenario that
  reproduced it, re-run and passing. Not the whole sweep every time.

## 14. Prior art (surveyed 2026-11-04)

GitHub figures from `gh api repos/<repo>` on 2026-11-04; cut: under 500 stars or no push in 90 days,
except benchmarks backed by a paper. Lesson numbers (L1 to L15) are cited where each changed this doc.

| Tool | Type | Stars, last push, licence | Investigates by | Source |
|---|---|---|---|---|
| HolmesGPT | OSS, CNCF sandbox | 3779, 2026-11-04, Apache-2.0 | agentic loop over toolsets, runbook catalogue, eval suite | github.com/HolmesGPT/holmesgpt |
| k8sgpt | OSS | 8297, 2026-11-04, Apache-2.0 | fixed analysers find, LLM explains; optional anonymise | github.com/k8sgpt-ai/k8sgpt |
| OpenSRE (Tracer) | OSS, alpha | 12701, 2026-11-04, Apache-2.0 | fetch, mask, test hypotheses, push fix PR; CI/CD focus | github.com/Tracer-Cloud/opensre |
| Keep | OSS | 12489, 2026-11-04, MIT outside `ee/` | alert dedup and correlation; not RCA | github.com/keephq/keep |
| OpenRCA | benchmark, ICLR 2025 | 450, 2026-10-22, MIT | 335 failures, 68 GB telemetry, scripted scoring | github.com/microsoft/OpenRCA |
| AIOpsLab | benchmark framework | 1024, 2026-10-31, MIT | injected faults; detect, localise, analyse, mitigate; no-fault tasks | github.com/microsoft/AIOpsLab |
| RCACopilot | paper, EuroSys 2024 | n/a | per-alert handlers, category from similar past incidents or "Unseen" | arxiv.org/abs/2305.15778 |
| RCAgent | paper, CIKM 2024 | n/a | tool-using agent, self-consistency over action paths | arxiv.org/abs/2310.16340 |
| Datadog Bits AI SRE | commercial | n/a | hypothesis tree, each query tied to one hypothesis, may end inconclusive | docs.datadoghq.com/bits_ai/bits_ai_sre/investigate_issues |
| AWS DevOps Agent | commercial | n/a | competing hypotheses against counter-evidence; cause, root cause, unconnected | aws.amazon.com/blogs/devops (multi-agent RCA post) |
| Azure SRE Agent | commercial | n/a | Review or Autonomous run modes | learn.microsoft.com/en-us/azure/sre-agent/run-modes |
| Grafana Assistant / Sift | commercial | n/a | hypotheses with cited panels, "Was this the cause?" feedback | grafana.com/docs/grafana-cloud/machine-learning |
| PagerDuty SRE Agent | commercial | n/a | alert, runbook and memory; names what it lacks | pagerduty.com/eng/context-over-cleverness-building-pagerdutys-sre-agent |

What none of them was found to do, and this design does: re-run every cited number with a deterministic
validator; require a positive control behind a negative; reserve "root cause" for reproduced, fixed and
twice-reviewed faults; mark chaos windows; enforce the fix-branch prefix by credential.

Not verified: incident.io (secondary sources only); whether Sift's analyses are statistical or LLM;
HolmesGPT's published accuracy (its benchmark results file lists 1 test).

## 15. Left to the builder

The ledger's exact JSON schema, the validator's language (Python, per the stack defaults), the post's
exact Slack blocks, the daily cap's value, and how the skill discovers the alert's Slack `ts`.
