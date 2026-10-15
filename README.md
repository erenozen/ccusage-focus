# ccusage-focus

Converts ccusage's daily JSON report into a FOCUS 1.2 CSV. By default it writes one row per day,
per agent, per model, carrying ccusage's cost for that model-day unchanged. A per-token-type grain
(input, output, cache creation, cache read) is available as an opt-in approximation.

It reads ccusage's public `--json` output and nothing else. It does not patch, wrap, or link
ccusage.

## Run it

```sh
nix develop            # provides babashka, pinned to the nixpkgs revision ccusage uses
ccusage daily --json --by-agent --mode calculate --timezone UTC > daily.json
bin/ccusage-focus daily.json > focus.csv
```

For project-level rows (Claude Code only):

```sh
ccusage claude daily --instances --json --mode calculate --timezone UTC > instances.json
bin/ccusage-focus instances.json > focus.csv
```

For one row per token type (see "Per-token-type rows"):

```sh
curl -sSfo prices.json https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json
bin/ccusage-focus --split-by-token-type --prices prices.json daily.json > focus.csv
```

Options:

| Option | Meaning |
| --- | --- |
| `--split-by-token-type` | One row per token type instead of per model. Requires `--prices`. |
| `--prices FILE` | LiteLLM-format price file, used only as weights for `--split-by-token-type`. Refused without it. |
| `--billing-account-id ID` | `BillingAccountId`. Default `ccusage-local`. |
| `--billing-account-name NAME` | `BillingAccountName`. Default: the same value as `BillingAccountId`. |

Input is a file path, or stdin when none is given. CSV goes to stdout; warnings and errors go to
stderr. Exit codes: `0` success; `1` unusable input or arguments; `2` with `--split-by-token-type`,
at least one model-day cost could not be split (nothing is written in that case).

## Why these ccusage flags

Checked against ccusage 20.0.32 (commit `cd79612b`):

- `--by-agent` is required. Without it the unified report has no per-agent rows and the
  converter exits `1`.
- `--timezone UTC`. ccusage dates each record in the system timezone unless told otherwise, and
  the JSON does not say which timezone it used. The converter writes every `period` as a UTC
  day (`ChargePeriodStart` = that day at `00:00:00Z`). Run with any other timezone and the
  periods are mislabeled; the converter cannot detect it.
- `--mode calculate`. In the default `auto` mode a single model-day `cost` can mix figures
  calculated from a price table with figures read from the log's stored `costUSD`, and the
  JSON does not say which. `calculate` makes every figure come from the price table.
  `--mode` works on `ccusage daily` although its `--help` does not list it.
- `--breakdown` is accepted but makes no difference to `ccusage daily --json`; model
  breakdowns are always present.
- `--instances` exists only on `ccusage claude daily`. `ccusage daily --instances` exits `2`.

## Column rules

These apply to the default grain. "Per-token-type rows" lists what changes with
`--split-by-token-type`.

| Column | Value |
| --- | --- |
| `BilledCost`, `EffectiveCost`, `ListCost`, `ContractedCost` | ccusage's `cost` for the model-day, rounded (see "Rounding"). All four are the same figure: ccusage produces one cost per model-day, an estimate at list price under `--mode calculate`. |
| `BillingAccountId`, `BillingAccountName` | From the options above. |
| `BillingCurrency` | `USD`. ccusage has no currency setting; its docs describe cost fields as "Estimated cost in US dollars" (`docs/guide/json-output.md`) and its table output prints `$`. |
| `BillingPeriodStart`, `BillingPeriodEnd` | First instant of the period's UTC month, first instant of the next month. |
| `ChargePeriodStart`, `ChargePeriodEnd` | The period's day at `00:00:00Z`, the next day at `00:00:00Z`. |
| `ChargeCategory` | `Usage` |
| `ChargeClass` | empty (no corrections) |
| `ChargeDescription` | `<ServiceName> <model> tokens` |
| `ChargeFrequency` | `Usage-Based` |
| `ConsumedQuantity`, `PricingQuantity` | Input + output + cache creation + cache read tokens. |
| `ConsumedUnit`, `PricingUnit` | `Tokens` (single tokens, not thousands, so quantities stay exact integers). |
| `InvoiceId` | empty |
| `InvoiceIssuerName`, `ProviderName`, `PublisherName` | The model vendor when the model name's family says it (table below), otherwise the agent's display name. Agent logs do not record who invoices the usage (the vendor, a cloud marketplace, or a reseller), so this is a convention. |
| `PricingCategory` | `Standard` |
| `ResourceId`, `ResourceName` | The ccusage project name for `--instances` input, otherwise empty. |
| `ServiceCategory` | `AI and Machine Learning` |
| `ServiceName` | The agent's display name, as ccusage prints it (`Claude Code`, `Codex`, `Gemini CLI`, ...). |
| `SkuId` | `<agent>/<model>` |
| `SubAccountId`, `SubAccountName` | empty |
| `x_ccusage_cost_raw` | The model-day `cost` exactly as ccusage printed it, written without an exponent. |
| `x_ccusage_cost_allocation` | `exact`: the cost cells carry ccusage's figure, unsplit. |
| `x_ccusage_missing_pricing` | `true` when ccusage marked the model `missingPricing` (its cost is `0` because no price was known, not because it was free), otherwise empty. |
| `x_ccusage_input_tokens`, `x_ccusage_output_tokens`, `x_ccusage_cache_creation_tokens`, `x_ccusage_cache_read_tokens` | The four token counts behind `ConsumedQuantity`. |

Vendor table, matched against the last `/`-separated part of the model name, case-insensitive:
`claude` Anthropic; `gpt-`, `chatgpt`, `codex-`, `o1`..`o9` OpenAI; `gemini`, `gemma` Google;
`grok` xAI; `deepseek` DeepSeek; `qwen` Alibaba; `kimi`, `moonshot` Moonshot AI; `glm` Zhipu AI;
`mistral`, `codestral`, `devstral` Mistral AI.

Every model breakdown in the input becomes one row. Rows are ordered by day, then in ccusage's
agent and model order.

## Per-token-type rows (`--split-by-token-type`)

ccusage reports one `cost` per model per day. It computes a product per token type internally but
returns only their sum (`calculate_cost_from_pricing` in `rust/crates/ccusage-core/src/cost.rs`),
so this mode has to split it. Its rows differ from the default:

- One row per token type with tokens; types with zero tokens are not written. Order within a
  model-day: input, output, cache creation, cache read.
- `ConsumedQuantity` and `PricingQuantity` are that type's token count.
- `ChargeDescription` is `<ServiceName> <model> <token type> tokens`.
- `SkuId` is `<agent>/<model>/<token type>`, token type one of `input`, `output`,
  `cache_creation`, `cache_read`.
- The four `x_ccusage_*_tokens` columns are not written.
- `x_ccusage_cost_raw` is repeated on every row cut from the same model-day, so do not sum it.
- `x_ccusage_cost_allocation` is one of:
  - **exact**: the cost is `0`, or only one token type has tokens. The whole figure goes on that
    row.
  - **price-weighted**: several token types have tokens. Each type gets
    `cost * tokens * unit price / sum(tokens * unit price)`, using the `--prices` file only for
    the ratio. The model-day total stays ccusage's figure; only the split between token types
    comes from the price file. Prices are looked up by the exact model name, then by its last `/`
    part, using `input_cost_per_token`, `output_cost_per_token`,
    `cache_creation_input_token_cost`, and `cache_read_input_token_cost`.

A model-day that needs a price-weighted split and has no usable price is an error, listed on
stderr; the converter does not guess a split.

A price-weighted split is close to, but not always equal to, what ccusage computed internally.
ccusage prices each request separately (long-context tiers, fast-mode multipliers, dated price
schedules, and user `pricingOverrides` all apply per request), and ccusage reads models.dev as
well as LiteLLM. The totals reconcile; the per-type split is an approximation, and
`x_ccusage_cost_allocation` says so on every row where it is one.

## Rounding

Every amount is a decimal string with at most 12 decimal places, rounded half-to-even, with
trailing zeros removed and no exponent. Zero is written `0`. JSON numbers are read as exact
decimals, never as binary floats, so the only rounding is this one step.

Example: ccusage prints `0.023137500000000002`; the converter writes `0.0231375` and keeps
`0.023137500000000002` in `x_ccusage_cost_raw`.

With `--split-by-token-type`, a price-weighted split rounds each share to 12 places, then adds the
difference between the rounded model-day total and the sum of the rounded shares to the share
with the largest weight (the first token type on a tie). The rows of one model-day therefore add
up to the model-day `cost` rounded to 12 places.

## Warnings

ccusage counts some tokens in an agent's `totalTokens` without putting them in any of the four
token-type fields (for example Gemini CLI reasoning tokens, which ccusage prices as output). They
are not in the CSV. When an agent-day's `totalTokens` exceeds the sum of its four token fields,
the converter prints a warning with the difference.

## Tests

```sh
nix develop --command bb test
```

The fixtures under `test/fixtures/` are hand-written synthetic data; no real log, project name, or
path is in this repository. Each `*.expected.csv` (default grain) and `*.split.expected.csv`
(`--split-by-token-type`) was written by hand from its input before the converter produced it,
and the tests compare the converter's output against it byte for byte.

## Why Babashka

ccusage's `AGENTS.md` picks Babashka for automation that has its own data model, several
transformation stages, and unit tests, which describes this converter. It also has exact decimal
arithmetic built in (`java.math.BigDecimal`), so costs never pass through a binary float.
