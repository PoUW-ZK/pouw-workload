# pouw-workload
Synthetic client workload generator

## Run locally

```bash
./gradlew build          # compile and tests
LIGHT_NODE_URL=http://localhost:8000 ./gradlew run   # /health on port 8000
```

Variables: `LIGHT_NODE_URL` (default `http://localhost:8080`),
`FULL_NODE_URLS` (comma-separated, defaulting to `node-1`, `node-2`, and
`node-3` on port 8000), `MAX_CONCURRENCY` (100), and `TIMEOUT_MS` (5000).

Scenarios are YAML files using `scenarios/example.yaml` as a template. A
scenario sets its duration, target request rate, `constant`, `poisson`, or
`burst` arrival model, concurrency cap, fixture pools, and weighted read mix.
Height reads are bounded by the configured range and the node's observed head;
account and transaction reads use the configured fixture pools. Run a scenario
from Java with `WorkloadScenarioRunner`; saturation is counted as rejected
dispatches rather than blocking the open-loop scheduler. HdrHistogram reports
planned-send-to-response p50/p95/p99 latency in microseconds per operation.

Transfer scenarios additionally set `transfer_rate_per_second`, `wallet_count`,
`transfer_amount`, `transfer_fee`, and `wallet_funding_amount`. At startup the
generator creates Ed25519 wallets, funds them from the devnet genesis key, and
waits for each funding transaction to enter a block. Load transactions are
signed as Borsh `Transfer` bodies and submitted round-robin to `FULL_NODE_URLS`.
Each wallet's nonce is read from the light node and refreshed after a
`bad_nonce` rejection without moving it backwards while other transactions may
still be pending. The 32 bytes of `0x01` funding seed and corresponding address
are specific to the devnet genesis. `scenarios/transfers.yaml` combines
transfer load with the standard read mix. `transfers-steady.yaml` runs a clean
2 tx/s constant profile, while `transfers-spike.yaml` alternates between high
and low phases around a 10 tx/s burst-model baseline.

The HTTP API listens on port 8000. `GET /scenarios` lists available files;
`POST /api/v1/start` accepts `{"scenario_name":"example"}`, `POST /api/v1/stop`
stops arrivals and drains in-flight reads, and `GET /api/v1/status` returns
live per-operation statistics. The unprefixed `/start`, `/stop`, `/status` and
`/results` routes are also available; results are CSV after a completed or
stopped run. Only one run can be active at a time. Scenario names are file
basenames under `scenarios`, not arbitrary paths.

For the live-demo status view, select the stable fields explicitly:

```bash
curl -s http://localhost:8000/api/v1/status | jq '{status, scenario, transfers}'
```

On a server network, `pouw-deploy` starts the passive `transfers-steady`
scenario through its `workload-driver`. Switching to another scenario recreates
only that driver; the workload service itself keeps running.

Prometheus can scrape `http://localhost:8001/metrics`: sent, successful,
failed and rejected reads are counted per operation, with a latency histogram
and an in-flight gauge. Counters accumulate across runs. `scenarios/soak.yaml`
provides a one-hour, read-only chain-head workload; observe errors, latency,
and both processes' memory in Grafana before considering a soak successful.
`scenarios/smoke.yaml` provides a 90-second chain-head run for local integration checks.
Transaction metrics expose submitted, accepted, included, rejected-by-reason,
failed, in-flight, accepted-by-node, and send-to-inclusion latency. `submitted`
counts HTTP
submission attempts, including retries and transfers
whose response is ambiguous, while inclusion latency runs from the first send
attempt to block inclusion.

`Dockerfile` builds the image that pouw-deploy runs as `workload`
(`ghcr.io/pouw-zk/pouw-workload`, published from `main` by `docker.yml`). To run the
whole network with your local changes, clone pouw-deploy next to this repository and
run `make dev` there.
