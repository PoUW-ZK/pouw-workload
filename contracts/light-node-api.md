# Light Node API Contract

The workload client's read paths and JSON fields follow the route constants and
view types in `pouw-node/crates/pouw-api`. The node source currently declares
these routes but its HTTP server is not implemented yet. Successful reads use
HTTP `200 OK`; missing resources use the node's not-found response.

## `GET /v1/blocks/head`

Returns the tip header as a `HeaderView`.

```json
{
  "id": "blk:...",
  "header": {
    "height": 100
  }
}
```

## `GET /v1/blocks/{height_or_id}/header`

Returns the same `HeaderView` structure for one block header.

## `GET /v1/blocks/{height_or_id}`

Returns a block as a `BlockView`. The complete header and transaction records
are omitted here; `txs` and `proof_txs` contain the node's JSON transaction
views.

```json
{
  "id": "blk:...",
  "header": {
    "height": 100
  },
  "txs": [],
  "proof_txs": []
}
```

## `GET /v1/accounts/{addr}`

Returns both account values in one `AccountView`.

```json
{
  "address": "...",
  "balance": 5000,
  "nonce": 3
}
```

The workload's `balance` and `nonce` operations independently issue this read
and select the corresponding field.

## `GET /v1/txs/{txid}`

Returns a `TxView`. `status` is `pending` or `included`; `height` is present
when included. An included proof request also has the flattened proof fields,
including the base64-encoded `proof`.

```json
{
  "txid": "...",
  "status": "included",
  "height": 100,
  "tx": {}
}
```

The node does not declare a separate proof-task status route. For the workload
scenario's `proof_task_status` operation, `proof_task_ids` therefore contains
transaction IDs of proof requests and the client reads their `TxView` from this
same endpoint.
