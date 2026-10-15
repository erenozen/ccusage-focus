(ns ccusage-focus.main
  (:require [ccusage-focus.core :as core]
            [clojure.data.json :as json]
            [clojure.string :as str]))

(def usage
  "Usage: ccusage-focus [options] [INPUT.json]

Reads `ccusage daily --json --by-agent` (or `ccusage claude daily --instances --json`)
from INPUT.json or stdin and writes FOCUS 1.2 CSV to stdout.

Options:
  --split-by-token-type         One row per token type instead of per model, the model-day
                                cost split by --prices weights (an approximation; see README)
  --prices FILE                 LiteLLM-format price file; required by, and only used with,
                                --split-by-token-type
  --billing-account-id ID       BillingAccountId (default: ccusage-local)
  --billing-account-name NAME   BillingAccountName (default: the BillingAccountId)
  -h, --help                    Show this help")

(defn- read-json [source]
  (json/read-str (slurp source) :bigdec true))

(defn- parse-args [args]
  (loop [[a & more :as args] args opts {}]
    (cond
      (empty? args) opts
      (#{"-h" "--help"} a) (recur more (assoc opts :help true))
      (= "--split-by-token-type" a) (recur more (assoc opts :split? true))
      (#{"--prices" "--billing-account-id" "--billing-account-name"} a)
      (if (seq more)
        (recur (rest more) (assoc opts (keyword (subs a 2)) (first more)))
        (throw (ex-info (str a " needs a value") {})))
      (str/starts-with? a "-") (if (= a "-")
                                 (recur more (assoc opts :input *in*))
                                 (throw (ex-info (str "unknown option " a) {})))
      (:input opts) (throw (ex-info "only one input file is accepted" {}))
      :else (recur more (assoc opts :input a)))))

(defn run
  "Returns [exit-code stdout stderr-lines]."
  [args]
  (try
    (let [{:keys [help split? input prices billing-account-id billing-account-name]} (parse-args args)]
      (cond
        help [0 (str usage "\n") []]
        (and split? (not prices)) [1 "" ["error: --split-by-token-type needs --prices"]]
        (and prices (not split?)) [1 "" ["error: --prices is only used with --split-by-token-type"]]
        :else
        (let [account-id (or billing-account-id "ccusage-local")
              {:keys [rows errors warnings]}
              (core/convert (read-json (or input *in*))
                            {:split? split?
                             :prices (some-> prices read-json)
                             :billing-account-id account-id
                             :billing-account-name (or billing-account-name account-id)})
              notes (map #(str "warning: " %) warnings)]
          (if (seq errors)
            [2 "" (concat notes (map #(str "error: " %) errors))]
            [0 (core/->csv (core/columns split?) rows) notes]))))
    (catch clojure.lang.ExceptionInfo e
      [1 "" [(str "error: " (ex-message e))]])))

(defn -main [& args]
  (let [[code out err] (run args)]
    (print out)
    (flush)
    (binding [*out* *err*]
      (doseq [line err] (println line)))
    (System/exit code)))
