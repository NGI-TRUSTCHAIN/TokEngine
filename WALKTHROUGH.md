# Walkthrough

This is a guided example of a cross-chain token exchange using Tokengine. It uses:
- EVM: Ethereum Sepolia test network
- CVM: A Local Convex test network as launched by TokEngine

And token exchange occurs with the `USDC` coin ()

## Pre-requesites

1. A modern version of Java (21+ minimum, 25+ recommended)
2. A copy of `tokengine.jar`
3. A copy of `convex.jar` (needed for key import etc.)

`tokenegine.jar` and `convex.jar` can be built from source with `mvn clean install` or downloaded from a trusted source, e.g. the Convex snapshots drive at `https://drive.google.com/drive/folders/1AZdyuZOmC70i_TtuEW3uEKvjYLOqIMiv`

## Config

Create a config file `config.json` using the contents of [walkthrough.json](walkthrough.json).

This is a JSON5 config file that sets up TokEngine for operation as described in this walkthrough.

## Convex Key

You will need to import a convex key into your keystore. The test network uses a default Ed25519 with public key `0x5c8ebd5ca7190a9a683e54db8d5b6cf51660e0a649f13de894dc37a38591740c`

You can 

```
java -jar convex.jar key import --type=seed --text 0x00370031325baa88fef0ff932aaa2fa89b120c2ec309557d76d8433401ee5203
```

## Start TokEngine

Run the command to start TokEngine with the:

```bash
java -jar tokengine.jar walkthrough.json  
```

TokEngine should now have started. You should see a number of log entries including a line like:

```
2025-12-17T17:44:30.689Z INFO  [main] tokengine.adapter.AAdapter -- Added asset USDC on network convex:local with Asset ID cad29:132
```

This indicates that the `USDC` asset CAD29 token has been successfully created, and has been assigned account `#132`. **Take a note of this number if different**

## Check API

Navigate to `http://localhost:8080` in a browser. You should see the TokEngine server page.

Go to the `API Explorer` to see the Swagger user interface.

Test out the `api/v1/balance` to ensure the CAD29 USDC is working. `POST` a request like:

```json
{
  "source": {
    "account": "#11",
    "network": "convex",
    "token": "cad29:132"
  }
}
```

If successful, you should see an `HTTP 200` result with the CAD29 balance, something like:

```json
{
  "value": 1000000000000
}
```

The value is the balance of the genesis user `#11` which deployed the test CAD29 token with an initial balance of $1m (since USDC uses 6 decimals)

> [!NOTE]
> If you use a different `operatorAddress` or `receiverAddress` in the config 
> you will need to change `#11` and if you have a different CAD29 USDC token you will need to change 
> `cad29:132` accordingly.

## Deposit USDC on Sepolia

To make a deposit of `USDC` from the EVM, you can use the following transaction with a post to `/api/v1/deposit` as follows:

```json
{
  "source": {
    "account": "0xa72018ba06475aCa284ED98AB0cE0E07878521a3",
    "network": "eip155:11155111",
    "token": "erc20:0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"
  },
  "deposit": {
    "tx": "0x9d3a3663d32b9ff5cf2d393e433b7b31489d13b398133a35c4bb6e2085bd8e83"
  }
}
```

This transaction already exists on Sepolia (we have provided it for testing purposes. If successful, you should see a result like:

```json
{
  "value": 6510
}
```

i.e. 6510 units of USDC deposited, which is correct for the given test transaction.

> [!NOTE]
> If you make multiple deposits from the same transaction, subsequent attempts will fail
> since the deposit has already been made

## Check Credit

After making the deposit, there should be virtual credit available for the EVM account. Check this with a POST to `api/v1/credit` with the following request:

```json
{
  "source": {
    "account": "0xa72018ba06475aCa284ED98AB0cE0E07878521a3",
    "network": "sepolia",
    "token": "erc20:0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"
  }
}
```

You should again see a result like:

If successful, you should see a result like:

```json
{
  "value": 6510
}
```

## Payout USDC on Convex

To payout USDC oin Convex, make a `POST` request to `api/v1/payout` like:

```json
{
  "source": {
    "account": "0xa72018ba06475aCa284ED98AB0cE0E07878521a3",
    "network": "sepolia",
    "token": "USDC"
  },
  "destination": {
    "account": "#12",
    "network": "convex",
    "token": "USDC"
  },
  "deposit": {
    "tx": "0x9d3a3663d32b9ff5cf2d393e433b7b31489d13b398133a35c4bb6e2085bd8e83",
    "msg": "Transfer 100 USDC to #13 on convex",
    "sig": "0xdd48188b1647010d908e9fed4b6726cebd0d65e20f412b8b9ff4868386f05b0a28a9c0e35885c95e2322c2c670743edd07b0e1450ae65c3f6708b61bb3e582371c"
  },
  "quantity": "100"
}
```

## Check payout balance

After the payout, there should be a balance on the destination network. Check this with the `api/v1/balance` endpoint with a POST of:

```json
{
  "source": {
    "account": "#12",
    "network": "convex",
    "token": "USDC"
  }
}
```

You can likewise verify that the operatorAdress (`#11`) has a remaining balance of `999999999900` after `100` units was paid out.



