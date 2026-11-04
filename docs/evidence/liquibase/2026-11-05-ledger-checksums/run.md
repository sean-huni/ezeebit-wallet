# Ledger 2026/10 changeset checksums, before and after the reformat

Run on 2026-11-05 at HEAD `5fe4d69`, branch `feat-pyroscope-profiling`. The reformat of the six ledger-svc
changelogs under `services/ledger-svc/src/main/resources/db/changelog/2026/10/` landed in `e997664`; it is the
only commit since `5d11dfd` (dev) that touches them (`git log 5d11dfd..HEAD -- <that dir>`).

## Question

Does the reformat change any changeset's Liquibase checksum? A changed checksum would make Liquibase refuse to
start against a database that already applied the old version.

## Classpath

The 194 jars of `services/ledger-svc/build/libs/ledger-svc-0.3.2.jar`, `BOOT-INF/lib/`, so the checksum code is
the one the service runs: liquibase-core 5.0.4 (sha256 `00d0a18af81a12583d03524b6e685b26e3734183d965a6d79c1e9479d5e1c115`).

## Commands

```sh
unzip -q -o -j services/ledger-svc/build/libs/ledger-svc-0.3.2.jar 'BOOT-INF/lib/*' -d libs
for f in $(git ls-tree --name-only HEAD services/ledger-svc/src/main/resources/db/changelog/2026/10/); do
  git show HEAD:$f    > head/db/changelog/2026/10/$(basename $f)
  git show 5d11dfd:$f > base/db/changelog/2026/10/$(basename $f)
done
F=001-currency.xml,002-account.xml,003-journal.xml,004-entry.xml,005-conversion.xml,006-journal-refused.xml
javac -cp 'libs/*' -d src src/Sum.java                      # javac=0
java -cp 'src:libs/*' Sum base $F > 5d11dfd.txt             # exit 0, stderr empty
java -cp 'src:libs/*' Sum head $F > head.txt                # exit 0, stderr empty
cmp 5d11dfd.txt head.txt                                    # cmp=0
```

`Sum.java` parses each file with `XMLChangeLogSAXParser` and prints every changeset's
`generateCheckSum(ChecksumVersion.latest())`, the v9 checksum Liquibase 5.0.4 stores.

## Positive control

A copy of the HEAD tree with one SQL digit changed in `001-currency.xml`, changeset `001-currency-checks`:

```
-            ADD CONSTRAINT ck_currency_scale CHECK (scale BETWEEN 0 AND 18);</sql>
+            ADD CONSTRAINT ck_currency_scale CHECK (scale BETWEEN 0 AND 19);</sql>
```

`java -cp 'src:libs/*' Sum ctl $F > ctl.txt` exited 0; `diff head.txt ctl.txt` exited 1 with exactly one line
changed, `001-currency-checks` from `9:c03d1f1c3a3389c40c61f7363a95adab` to `9:23ab15e93e0e0228fc412d54acb062c0`.
So the harness sees a one-character SQL change.

## Result

16 of 16 changesets have the same v9 checksum at `5d11dfd` and at HEAD (`5d11dfd.txt`, `head.txt`); the control
changed 1 of 16 (`ctl.txt`). The reformat is checksum-neutral under liquibase-core 5.0.4.

An earlier run of the same harness on 2026-11-05, against the working tree before `e997664` was committed,
produced byte-identical `head.txt`, working-tree and `ctl.txt` outputs (compared with `cmp`).
