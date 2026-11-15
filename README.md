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

The HTTP API listens on port 8000. `GET /scenarios` lists available files;
`POST /api/v1/start` accepts `{"scenario_name":"example"}`, `POST /api/v1/stop`
stops arrivals and drains in-flight reads, and `GET /api/v1/status` returns
live per-operation statistics. The unprefixed `/start`, `/stop`, `/status` and
`/results` routes are also available; results are CSV after a completed or
stopped run. Only one run can be active at a time. Scenario names are file
basenames under `scenarios`, not arbitrary paths.

Prometheus can scrape `http://localhost:8001/metrics`: sent, successful,
failed and rejected reads are counted per operation, with a latency histogram
and an in-flight gauge. Counters accumulate across runs. `scenarios/soak.yaml`
provides a one-hour, read-only chain-head workload; observe errors, latency,
and both processes' memory in Grafana before considering a soak successful.

`Dockerfile` builds the image that pouw-deploy runs as `workload`
(`ghcr.io/pouw-zk/pouw-workload`, published from `main` by `docker.yml`). To run the
whole network with your local changes, clone pouw-deploy next to this repository and
run `make dev` there.
