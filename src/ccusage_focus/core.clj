(ns ccusage-focus.core
  "Pure conversion from parsed `ccusage daily --json` data to FOCUS 1.2 rows.
  Every number arrives as a BigDecimal (the JSON is read with :bigdec true), so
  no amount passes through a double on the way to the CSV."
  (:require [clojure.string :as str])
  (:import (java.math BigDecimal RoundingMode)
           (java.time LocalDate)))

(def focus-columns
  ["BilledCost" "BillingAccountId" "BillingAccountName" "BillingCurrency"
   "BillingPeriodEnd" "BillingPeriodStart" "ChargeCategory" "ChargeClass"
   "ChargeDescription" "ChargeFrequency" "ChargePeriodEnd" "ChargePeriodStart"
   "ConsumedQuantity" "ConsumedUnit" "ContractedCost" "EffectiveCost" "InvoiceId"
   "InvoiceIssuerName" "ListCost" "PricingCategory" "PricingQuantity" "PricingUnit"
   "ProviderName" "PublisherName" "ResourceId" "ResourceName" "ServiceCategory"
   "ServiceName" "SkuId" "SubAccountId" "SubAccountName"])

(def extension-columns
  ["x_ccusage_cost_raw" "x_ccusage_cost_allocation" "x_ccusage_missing_pricing"])

;; Order matters: rows are emitted in this order, and a rounding residual goes
;; to the first of the largest-weight types.
(def token-types
  [{:id "input" :label "input" :count-column "x_ccusage_input_tokens" :json-key "inputTokens" :price-key "input_cost_per_token"}
   {:id "output" :label "output" :count-column "x_ccusage_output_tokens" :json-key "outputTokens" :price-key "output_cost_per_token"}
   {:id "cache_creation" :label "cache creation" :count-column "x_ccusage_cache_creation_tokens" :json-key "cacheCreationTokens"
    :price-key "cache_creation_input_token_cost"}
   {:id "cache_read" :label "cache read" :count-column "x_ccusage_cache_read_tokens" :json-key "cacheReadTokens"
    :price-key "cache_read_input_token_cost"}])

(defn columns
  "The default grain carries the four token counts; the split grain has one
  count per row already."
  [split?]
  (cond-> (into focus-columns extension-columns)
    (not split?) (into (map :count-column token-types))))

;; ccusage-cli-parser/src/parser.rs `agent_display_name`.
(def agent-display-names
  {"claude" "Claude Code" "codex" "Codex" "opencode" "OpenCode" "amp" "Amp"
   "droid" "Droid" "codebuff" "Codebuff" "hermes" "Hermes" "pi" "pi-agent"
   "goose" "Goose" "kilo" "Kilo" "copilot" "GitHub Copilot CLI"
   "gemini" "Gemini CLI" "antigravity" "Antigravity" "kimi" "Kimi" "qwen" "Qwen"
   "openclaw" "OpenClaw" "grok" "Grok" "zcode" "ZCode"})

;; Model-family prefixes that name their vendor. Matched against the model
;; name's last `/` segment, lowercased. Anything else falls back to the agent.
(def vendor-patterns
  [[#"^claude" "Anthropic"]
   [#"^(gpt-|chatgpt|codex-|o[1-9](-|$))" "OpenAI"]
   [#"^(gemini|gemma)" "Google"]
   [#"^grok" "xAI"]
   [#"^deepseek" "DeepSeek"]
   [#"^qwen" "Alibaba"]
   [#"^(kimi|moonshot)" "Moonshot AI"]
   [#"^glm" "Zhipu AI"]
   [#"^(mistral|codestral|devstral)" "Mistral AI"]])

(def amount-scale 12)

(defn ->dec ^BigDecimal [x]
  (cond (instance? BigDecimal x) x
        (integer? x) (BigDecimal/valueOf (long x))
        :else (throw (ex-info (str "expected a JSON number, got " (pr-str x)) {}))))

(defn round-amount
  "Round to `amount-scale` places, half-even."
  ^BigDecimal [^BigDecimal x]
  (.setScale x (int amount-scale) RoundingMode/HALF_EVEN))

(defn format-amount
  "Decimal string with at most 12 places, no exponent, no trailing zeros; zero is `0`."
  [^BigDecimal x]
  (let [s (.toPlainString (.stripTrailingZeros (round-amount x)))]
    (if (re-matches #"-?0(\.0*)?" s) "0" s)))

(defn format-raw [^BigDecimal x] (.toPlainString x))

(defn display-name [agent] (get agent-display-names agent agent))

(defn vendor [model agent]
  (let [family (str/lower-case (last (str/split model #"/")))]
    (or (some (fn [[re name]] (when (re-find re family) name)) vendor-patterns)
        (display-name agent))))

(defn- day-bounds [period]
  (let [day (LocalDate/parse period)
        month (.withDayOfMonth day 1)
        ts #(str % "T00:00:00Z")]
    {:charge-start (ts day) :charge-end (ts (.plusDays day 1))
     :billing-start (ts month) :billing-end (ts (.plusMonths month 1))}))

(defn- price-for [prices model]
  (or (get prices model) (get prices (last (str/split model #"/")))))

(defn allocate
  "Split one model-day `cost` over its token types.
  `quantities` is a seq of [token-type tokens] with tokens > 0. Returns
  {:allocation \"exact\"|\"price-weighted\" :amounts {type-id BigDecimal}} or
  {:error message}. Price-weighted amounts sum to `cost` rounded to 12 places."
  [cost quantities price]
  (cond
    (zero? (.signum ^BigDecimal cost))
    {:allocation "exact" :amounts (into {} (map (fn [[t _]] [(:id t) BigDecimal/ZERO])) quantities)}

    (empty? quantities)
    {:error "has a cost but no tokens in any token-type bucket"}

    (= 1 (count quantities))
    {:allocation "exact" :amounts {(:id (ffirst quantities)) cost}}

    :else
    (let [missing (remove #(get price (:price-key (first %))) quantities)]
      (if (or (nil? price) (seq missing))
        {:error (str "needs a per-token price to split its cost; "
                     (if price
                       (str "missing " (str/join ", " (map (comp :price-key first) missing)))
                       "model not found in --prices"))}
        (let [weights (mapv (fn [[t tokens]] [t (.multiply (->dec tokens) (->dec (get price (:price-key t))))])
                            quantities)
              total (reduce #(.add ^BigDecimal %1 ^BigDecimal %2) BigDecimal/ZERO (map second weights))]
          (if (zero? (.signum ^BigDecimal total))
            {:error "has all-zero prices for its token types in --prices"}
            (let [shares (mapv (fn [[t w]] [t (.divide (.multiply cost ^BigDecimal w) total
                                                       (int amount-scale) RoundingMode/HALF_EVEN)])
                               weights)
                  residual (.subtract (round-amount cost)
                                      (reduce #(.add ^BigDecimal %1 ^BigDecimal %2) BigDecimal/ZERO (map second shares)))
                  max-w (reduce #(if (pos? (compare %2 %1)) %2 %1) (map second weights))
                  target (:id (first (first (filter #(zero? (compare (second %) max-w)) weights))))]
              {:allocation "price-weighted"
               :amounts (into {} (map (fn [[t amt]]
                                        [(:id t) (if (= (:id t) target) (.add ^BigDecimal amt residual) amt)]))
                              shares)})))))))

(defn- focus-row
  "The FOCUS cells shared by both grains for one model-day; the caller adds
  quantities, costs, description, SKU, and allocation."
  [{:keys [period agent breakdown project]} {:keys [billing-account-id billing-account-name]}]
  (let [model (get breakdown "modelName")
        bounds (day-bounds period)
        provider (vendor model agent)]
    {"BillingAccountId" billing-account-id
     "BillingAccountName" billing-account-name
     "BillingCurrency" "USD"
     "BillingPeriodEnd" (:billing-end bounds)
     "BillingPeriodStart" (:billing-start bounds)
     "ChargeCategory" "Usage"
     "ChargeClass" ""
     "ChargeFrequency" "Usage-Based"
     "ChargePeriodEnd" (:charge-end bounds)
     "ChargePeriodStart" (:charge-start bounds)
     "ConsumedUnit" "Tokens"
     "InvoiceId" ""
     "InvoiceIssuerName" provider
     "PricingCategory" "Standard"
     "PricingUnit" "Tokens"
     "ProviderName" provider
     "PublisherName" provider
     "ResourceId" (or project "")
     "ResourceName" (or project "")
     "ServiceCategory" "AI and Machine Learning"
     "ServiceName" (display-name agent)
     "SubAccountId" ""
     "SubAccountName" ""
     "x_ccusage_cost_raw" (format-raw (->dec (get breakdown "cost" 0)))
     "x_ccusage_missing_pricing" (if (true? (get breakdown "missingPricing")) "true" "")}))

(defn- with-amount [row amount quantity]
  (assoc row
         "BilledCost" amount "EffectiveCost" amount "ListCost" amount "ContractedCost" amount
         "ConsumedQuantity" quantity "PricingQuantity" quantity))

(defn- model-day-rows
  "Default grain: one row per model-day, carrying ccusage's cost unsplit."
  [{:keys [agent breakdown] :as group} opts]
  (let [model (get breakdown "modelName")
        counts (map #(get breakdown (:json-key %) 0) token-types)]
    {:rows [(-> (focus-row group opts)
                (with-amount (format-amount (->dec (get breakdown "cost" 0))) (str (reduce + counts)))
                (assoc "ChargeDescription" (str (display-name agent) " " model " tokens")
                       "SkuId" (str agent "/" model)
                       "x_ccusage_cost_allocation" "exact")
                (into (map (fn [t n] [(:count-column t) (str n)]) token-types counts)))]}))

(defn- token-type-rows
  "Opt-in grain: one row per token type, the model-day cost split by `allocate`."
  [{:keys [period agent breakdown] :as group} {:keys [prices] :as opts}]
  (let [model (get breakdown "modelName")
        quantities (for [t token-types
                         :let [n (get breakdown (:json-key t) 0)]
                         :when (pos? n)]
                     [t n])
        result (allocate (->dec (get breakdown "cost" 0)) quantities (price-for prices model))]
    (if-let [err (:error result)]
      {:errors [(str period " " agent " " model " " err)]}
      (let [base (focus-row group opts)]
        {:rows (for [[t n] quantities]
                 (-> base
                     (with-amount (format-amount (get-in result [:amounts (:id t)])) (str n))
                     (assoc "ChargeDescription" (str (display-name agent) " " model " " (:label t) " tokens")
                            "SkuId" (str agent "/" model "/" (:id t))
                            "x_ccusage_cost_allocation" (:allocation result))))}))))

(defn- unbucketed-warning [period agent summary]
  (let [bucketed (reduce + (map #(get summary (:json-key %) 0) token-types))
        total (get summary "totalTokens" bucketed)]
    (when (> total bucketed)
      (str period " " agent ": " (- total bucketed)
           " tokens counted in totalTokens are in no token-type bucket and are not in the CSV"))))

(defn- unified-groups
  "One group per agent per day from `ccusage daily --json --by-agent`."
  [data]
  (for [row (sort-by #(get % "period") (get data "daily"))
        :let [period (get row "period")
              agents (or (get row "agents")
                         (throw (ex-info (str "row " period " has no `agents` array; run ccusage with --by-agent") {})))]
        agent agents]
    {:period period :agent (get agent "agent") :summary agent}))

(defn- instances-groups
  "One group per project per day from `ccusage claude daily --instances --json`.
  Only the claude daily command accepts --instances."
  [data]
  (sort-by :period
           (for [[project days] (sort-by key (get data "projects"))
                 day days]
             {:period (get day "date") :agent "claude" :project project :summary day})))

(defn convert
  "Returns {:rows [...] :errors [...] :warnings [...]}. `:split?` in opts picks
  the per-token-type grain."
  [data opts]
  (let [groups (cond (contains? data "daily") (unified-groups data)
                     (contains? data "projects") (instances-groups data)
                     :else (throw (ex-info "input has neither `daily` nor `projects`; expected `ccusage daily --json --by-agent` or `ccusage claude daily --instances --json`" {})))
        results (for [{:keys [summary] :as g} groups
                      breakdown (get summary "modelBreakdowns")]
                  ((if (:split? opts) token-type-rows model-day-rows) (assoc g :breakdown breakdown) opts))]
    {:rows (vec (mapcat :rows results))
     :errors (vec (mapcat :errors results))
     :warnings (vec (keep #(unbucketed-warning (:period %) (or (:project %) (:agent %)) (:summary %)) groups))}))

(defn- csv-field [s]
  (if (re-find #"[,\"\r\n]" s)
    (str "\"" (str/replace s "\"" "\"\"") "\"")
    s))

(defn ->csv [columns rows]
  (let [line #(str (str/join "," (map csv-field %)) "\n")]
    (apply str (line columns) (map #(line (map % columns)) rows))))
