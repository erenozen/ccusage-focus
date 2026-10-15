(ns ccusage-focus.core-test
  (:require [ccusage-focus.core :as core]
            [ccusage-focus.main :as main]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def fixtures "test/fixtures/")

(def gemini-warning
  "warning: 2026-02-01 gemini: 30 tokens counted in totalTokens are in no token-type bucket and are not in the CSV")

(deftest header-matches-the-focus-golden-header
  (is (= (str "BilledCost,BillingAccountId,BillingAccountName,BillingCurrency,BillingPeriodEnd,"
              "BillingPeriodStart,ChargeCategory,ChargeClass,ChargeDescription,ChargeFrequency,"
              "ChargePeriodEnd,ChargePeriodStart,ConsumedQuantity,ConsumedUnit,ContractedCost,"
              "EffectiveCost,InvoiceId,InvoiceIssuerName,ListCost,PricingCategory,PricingQuantity,"
              "PricingUnit,ProviderName,PublisherName,ResourceId,ResourceName,ServiceCategory,"
              "ServiceName,SkuId,SubAccountId,SubAccountName")
         (str/join "," core/focus-columns))))

(deftest default-grain-is-one-row-per-model-day-without-a-price-file
  (let [[code out err] (main/run [(str fixtures "daily-by-agent.json")])]
    (is (= 0 code))
    (is (= (slurp (str fixtures "daily-by-agent.expected.csv")) out))
    (is (= [gemini-warning] (vec err)))))

(deftest default-grain-instances-fill-resource-and-account-columns
  (let [[code out err] (main/run [(str fixtures "claude-instances.json")
                                  "--billing-account-id" "acct-42"
                                  "--billing-account-name" "Example Org"])]
    (is (= 0 code))
    (is (= (slurp (str fixtures "claude-instances.expected.csv")) out))
    (is (empty? err))))

(deftest split-mode-output-matches-hand-written-csv
  (let [[code out err] (main/run [(str fixtures "daily-by-agent.json")
                                  "--split-by-token-type"
                                  "--prices" (str fixtures "prices.json")])]
    (is (= 0 code))
    (is (= (slurp (str fixtures "daily-by-agent.split.expected.csv")) out))
    (is (= [gemini-warning] (vec err)))))

(deftest split-mode-instances-match-hand-written-csv
  (let [[code out err] (main/run [(str fixtures "claude-instances.json")
                                  "--split-by-token-type"
                                  "--prices" (str fixtures "prices.json")
                                  "--billing-account-id" "acct-42"
                                  "--billing-account-name" "Example Org"])]
    (is (= 0 code))
    (is (= (slurp (str fixtures "claude-instances.split.expected.csv")) out))
    (is (empty? err))))

(defn- are-equal [cases]
  (doseq [[expected input] cases]
    (is (= expected (core/format-amount input)) (str input))))

(deftest amounts-round-half-even-to-twelve-places
  (are-equal
   [["0" 0M] ["0" 0.0M] ["3" 3.0M] ["0.0231375" 0.023137500000000002M]
    ["0.000000000002" 1.5E-12M] ["0.000000000002" 2.5E-12M]
    ["0" 4E-13M] ["0.000000000001" 5.000001E-13M] ["0" 5E-13M]]))

(deftest split-mode-refuses-to-guess-a-missing-price
  (let [[code out err] (main/run [(str fixtures "daily-by-agent.json")
                                  "--split-by-token-type"
                                  "--prices" (str fixtures "prices-empty.json")])]
    (is (= 2 code))
    (is (= "" out))
    (is (= [gemini-warning
            "error: 2026-01-31 claude claude-sonnet-4-20250514 needs a per-token price to split its cost; model not found in --prices"
            "error: 2026-01-31 claude claude-opus-4-1-20250805 needs a per-token price to split its cost; model not found in --prices"
            "error: 2026-01-31 codex gpt-5 needs a per-token price to split its cost; model not found in --prices"]
           (vec err)))))

(deftest split-mode-requires-prices
  (is (= [1 "" ["error: --split-by-token-type needs --prices"]]
         (update (main/run [(str fixtures "daily-by-agent.json") "--split-by-token-type"]) 2 vec))))

(deftest prices-without-split-mode-is-refused
  (is (= [1 "" ["error: --prices is only used with --split-by-token-type"]]
         (update (main/run [(str fixtures "daily-by-agent.json")
                            "--prices" (str fixtures "prices.json")]) 2 vec))))

(deftest missing-price-key-is-named
  (is (= {:error "needs a per-token price to split its cost; missing cache_creation_input_token_cost"}
         (core/allocate 1M
                        [[(nth core/token-types 0) 10] [(nth core/token-types 2) 10]]
                        {"input_cost_per_token" 1E-6M}))))

(deftest cost-without-any-bucketed-tokens-is-refused-in-split-mode
  (is (= {:error "has a cost but no tokens in any token-type bucket"}
         (core/allocate 0.5M [] nil))))

(deftest unified-input-without-by-agent-is-refused
  (let [[code _ err] (main/run [(str fixtures "daily-without-agents.json")])]
    (is (= 1 code))
    (is (= ["error: row 2026-01-31 has no `agents` array; run ccusage with --by-agent"] (vec err)))))

(deftest vendor-comes-from-model-family-else-agent
  (is (= "Anthropic" (core/vendor "anthropic/claude-sonnet-4" "opencode")))
  (is (= "OpenAI" (core/vendor "o3-mini" "codex")))
  (is (= "OpenAI" (core/vendor "gpt-5" "copilot")))
  (is (= "Codex" (core/vendor "o3x" "codex")))
  (is (= "OpenCode" (core/vendor "openrouter/auto-beta" "opencode"))))
