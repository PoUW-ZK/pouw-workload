# pouw-workload
Synthetic client workload generator

## Run locally

```bash
./gradlew build          # compile and tests
LIGHT_NODE_URL=http://localhost:8000 ./gradlew run   # /health on port 8000
```

Variables: `LIGHT_NODE_URL` (default `http://localhost:8080`), `MAX_CONCURRENCY`
(100), `TIMEOUT_MS` (5000).

Scenarios are YAML files using `scenarios/example.yaml` as a template. A
scenario sets its duration, target request rate, `constant`, `poisson`, or
`burst` arrival model, concurrency cap, fixture pools, and weighted read mix.
Height reads are bounded by the configured range and the node's observed head;
account and transaction reads use the configured fixture pools. Run a scenario
from Java with `WorkloadScenarioRunner`; saturation is counted as rejected
dispatches rather than blocking the open-loop scheduler. HdrHistogram reports
planned-send-to-response p50/p95/p99 latency in microseconds per operation.

`Dockerfile` builds the image that pouw-deploy runs as `workload`
(`ghcr.io/pouw-zk/pouw-workload`, published from `main` by `docker.yml`). To run the
whole network with your local changes, clone pouw-deploy next to this repository and
run `make dev` there.
