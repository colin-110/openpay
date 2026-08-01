# CI/CD

Two workflows. The split is about cost: one runs on every push and finishes in a couple of minutes,
the other builds and starts the entire platform and does not.

## `ci.yml` — every push and pull request

Five jobs, four of which run in parallel.

### `build-and-verify`

`./mvnw clean verify` on **JDK 21 and JDK 25**. 21 is the compile target; 25 is what people
actually run locally, and testing both is what stops a runtime-only difference from being
discovered on somebody's laptop.

`verify` rather than `test`, because that is the difference between running the unit tests and
running the integration tests. The `*IT` suites start a real PostgreSQL — and, for the dead-letter
tests, a real Kafka — through Testcontainers on the runner's Docker daemon. They exist because a
mocked repository cannot see a schema mismatch: a `jsonb` column bound as `varchar` passed a fully
green unit suite and failed on every single request at runtime.

Test reports upload on failure *and* success, because a flaky test is only diagnosable if the run
that passed was also kept.

### `dashboard`

`npm ci`, lint, build. `ci` rather than `install`: it installs exactly what the lockfile says and
fails if the lockfile and `package.json` disagree, which is the difference between a reproducible
build and one that quietly picks up a new minor version. `tsc -b` runs as part of the build script,
so a type error fails here rather than shipping a bundle that only breaks in a browser.

### `manifests`

Three checks on the files that are only exercised at deploy time, which is the worst moment to find
a typo in them:

- every YAML file under `platform/` and `.github/workflows/` parses;
- `kubectl kustomize platform/k8s` renders, and the result survives a client-side dry run. Client-
  side, so it catches malformed manifests and unknown fields but not admission-policy problems;
- the secrets template still contains only placeholders. A real token committed there stays in git
  history long after it is rotated, and the check is one regular expression.

### `shell`

ShellCheck over `scripts/`. The acceptance suite is what catches the behaviour unit tests cannot, so
a syntax error in it is a gap in coverage that looks like a passing build.

### `images`

Twelve container images to GHCR, **on pushes to `main` only**. Building them on every pull request
costs minutes and produces artifacts nobody deploys.

Tagged with the commit SHA, never `latest`. A tag that moves makes a rollback a guess about what was
actually running.

One shared Buildx cache scope across the matrix. Every service's image has an identical build stage,
so the Maven reactor is built once for all of them rather than eleven times — which is the same
reason the `ARG MODULE` declarations sit in the runtime stage of the Dockerfile rather than at the
top.

## `acceptance.yml` — main, nightly, and on demand

Builds every image, starts the whole platform with `docker compose up --wait`, and runs
`scripts/e2e.sh` against it.

This is the only thing in the pipeline that tests the filters, the token tiers, and the asynchronous
flow together, in the arrangement they actually run in. It is also slow enough that putting it in
front of every pull request would make the pipeline something people work around.

The nightly schedule is there for the failures that are not caused by a commit: a base image moving,
a dependency published with a regression. Those should be found by a build, not by a person.

Credentials are generated per run from `github.run_id` rather than stored as repository secrets.
Nothing here outlives the job, and a real secret in a workflow that runs on every push is a secret
in every fork's logs the first time a trigger is misconfigured.

Compose logs upload on failure, and teardown runs with `if: always()` so a failed run does not leave
volumes behind on a self-hosted runner.

## What a merge should require

The pipeline enforces nothing on its own — branch protection does, and that is configured in the
repository settings rather than in a file here. The checks worth marking required:

- `Build & Verify (JDK 21)` and `Build & Verify (JDK 25)`
- `Dashboard`
- `Manifests & configuration`
- `Shell scripts`

`Container images` and `End-to-end acceptance` deliberately are not: they run after merge, and
requiring a job that only runs on `main` would block every pull request forever.

## What is not gated, and why

**Coverage.** No threshold, on purpose. A coverage gate is easy to satisfy without testing anything
— the tests that matter in this repo are the ones that assert behaviour a mock would have hidden,
and a percentage cannot tell the difference.

**Load tests.** `tests/performance/` needs a running platform and produces numbers that depend on
the machine. A shared runner's numbers are not comparable between runs, so a threshold there would
either be so loose it never fires or so tight it fires constantly.

**Dependency and container scanning.** Worth adding. Not here yet, and saying so is better than
implying the images have been checked.
