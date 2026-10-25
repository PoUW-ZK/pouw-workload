# Light Node API Contract

This contract defines the read-only REST endpoints assumed by the workload
generator for a Bitcoin-like L1 light node. JSON responses use HTTP `200 OK`
for successful reads and `404 Not Found` when the requested resource does not
exist.

## `GET /chain/head`

Returns the current chain head.

```json
{
  "height": 100,
  "hash": "0xabc..."
}
```

## `GET /block/{height}`

Returns a block by height.

```json
{
  "height": 100,
  "hash": "0xabc...",
  "previousHash": "0xdef...",
  "timestamp": 1700000000,
  "txs": []
}
```

## `GET /account/{address}/balance`

Returns the account balance in the node's smallest unit.

```json
{
  "balance": 5000
}
```

## `GET /account/{address}/nonce`

Returns the account nonce.

```json
{
  "nonce": 3
}
```

## `GET /tx/{txId}/status`

Returns the status of a transaction.

```json
{
  "status": "CONFIRMED",
  "blockHeight": 100
}
```

## `GET /proof-task/{taskId}/status`

Returns the status of a proof task associated with the node.

```json
{
  "status": "COMPLETED",
  "proof": "0x123..."
}
```

## Assumptions to confirm

The workload generator is built against these structures because the official
light-node contract is not yet available. Ilia should confirm or adjust the
paths, field names, field types, status values, and error behavior when the
official contract is published.
