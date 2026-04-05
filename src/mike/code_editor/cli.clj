(ns mike.code-editor.cli
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [mike.code-editor.core :as core]))

(def ^:private usage-text
  (str/join
   "\n"
   ["Usage:"
    "  clj-objects list --file PATH"
    "  clj-objects insert --file PATH --snapshot SNAPSHOT (--before ID:HASH | --after ID:HASH) [--dry-run] [--diff] < forms.clj"
    "  clj-objects replace --file PATH --snapshot SNAPSHOT --targets ID:HASH[,ID:HASH...] [--empty] [--dry-run] [--diff] < forms.clj"
    ""
    "Notes:"
    "  - insert/replace read raw top-level forms from stdin"
    "  - use --empty with replace to delete objects without stdin"
    "  - all successful commands emit JSON on stdout"
    "  - failures emit JSON on stderr and exit non-zero"]))

(defn- keyword->json-key
  [k]
  (-> (name k)
      (str/replace "?" "")
      (str/replace "-" "_")))

(defn- json-ready
  [x]
  (cond
    (map? x)
    (into {}
          (map (fn [[k v]]
                 [(if (keyword? k) (keyword->json-key k) (str k))
                  (json-ready v)]))
          x)

    (vector? x) (mapv json-ready x)
    (seq? x) (mapv json-ready x)
    (keyword? x) (keyword->json-key x)
    :else x))

(defn- emit-json!
  [stream data]
  (binding [*out* stream]
    (println (json/generate-string (json-ready data) {:pretty true}))))

(defn- parse-handle
  [value]
  (let [[id hash & more] (str/split value #":" 3)]
    (when (or (seq more) (str/blank? id) (str/blank? hash))
      (throw (ex-info "Invalid handle"
                      {:error :invalid-handle
                       :value value})))
    (try
      {:id (Integer/parseInt id)
       :hash hash}
      (catch NumberFormatException _
        (throw (ex-info "Invalid handle"
                        {:error :invalid-handle
                         :value value}))))))

(defn- parse-targets
  [value]
  (when (str/blank? value)
    (throw (ex-info "Missing targets"
                    {:error :invalid-handle
                     :value value})))
  (mapv parse-handle (str/split value #",")))

(defn- parse-args
  [argv]
  (loop [opts {}
         [arg & more] argv]
    (cond
      (nil? arg) opts

      (= arg "--help")
      (assoc opts :help? true)

      (#{"--dry-run" "--diff" "--empty"} arg)
      (recur (assoc opts
                    (case arg
                      "--dry-run" :dry-run?
                      "--diff" :diff?
                      "--empty" :empty?)
                    true)
             more)

      (#{"--file" "--snapshot" "--before" "--after" "--targets"} arg)
      (let [value (first more)]
        (when (nil? value)
          (throw (ex-info "Missing option value"
                          {:error :invalid-args
                           :option arg})))
        (recur (assoc opts
                      (case arg
                        "--file" :file
                        "--snapshot" :snapshot
                        "--before" :before
                        "--after" :after
                        "--targets" :targets)
                      value)
               (rest more)))

      :else
      (throw (ex-info "Unknown argument"
                      {:error :invalid-args
                       :argument arg})))))

(defn- stdin-text
  []
  (slurp *in*))

(defn- temp-path
  [prefix]
  (.toFile (java.nio.file.Files/createTempFile prefix ".clj"
                                               (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- unified-diff
  [before after]
  (let [before-file (temp-path "clj-objects-before-")
        after-file (temp-path "clj-objects-after-")]
    (try
      (spit before-file before)
      (spit after-file after)
      (let [{:keys [exit out err]}
            (process/sh {:out :string :err :string}
                        "diff" "-u"
                        "--label" "before"
                        (.getAbsolutePath before-file)
                        "--label" "after"
                        (.getAbsolutePath after-file))]
        (cond
          (zero? exit) ""
          (= 1 exit) out
          :else (str/trim err)))
      (finally
        (.delete before-file)
        (.delete after-file)))))

(defn- require-option
  [opts k]
  (or (get opts k)
      (throw (ex-info "Missing required option"
                      {:error :invalid-args
                       :option k}))))

(defn- run-list
  [opts]
  (core/list-objects (require-option opts :file)))

(defn- run-insert
  [opts]
  (let [before (:before opts)
        after (:after opts)]
    (when (= (boolean before) (boolean after))
      (throw (ex-info "Exactly one of --before or --after is required"
                      {:error :invalid-args})))
    (core/insert-objects!
     {:file (require-option opts :file)
      :snapshot (require-option opts :snapshot)
      :position (if before :before :after)
      :anchor-id (:id (parse-handle (or before after)))
      :anchor-hash (:hash (parse-handle (or before after)))
      :dry-run? (:dry-run? opts)
      :new-source (stdin-text)})))

(defn- run-replace
  [opts]
  (let [empty? (:empty? opts)]
    (core/replace-objects!
     {:file (require-option opts :file)
      :snapshot (require-option opts :snapshot)
      :targets (parse-targets (require-option opts :targets))
      :empty? empty?
      :dry-run? (:dry-run? opts)
      :new-source (when-not empty? (stdin-text))})))

(defn- enrich-response
  [response opts]
  (let [base (-> response
                 (dissoc :before-text :after-text)
                 (assoc :ok? true))]
    (cond-> base
      (and (:diff? opts) (contains? response :before-text))
      (assoc :diff (unified-diff (:before-text response) (:after-text response))))))

(defn -main
  [& argv]
  (let [[command & rest-args] argv]
    (try
      (when (or (nil? command) (= command "--help"))
        (println usage-text)
        (System/exit 0))
      (let [opts (parse-args rest-args)]
        (when (:help? opts)
          (println usage-text)
          (System/exit 0))
        (let [response (case command
                         "list" (run-list opts)
                         "insert" (run-insert opts)
                         "replace" (run-replace opts)
                         (throw (ex-info "Unknown command"
                                         {:error :invalid-args
                                          :command command})))
              response' (if (map? response)
                          (enrich-response response opts)
                          response)]
          (emit-json! *out* response')
          0))
      (catch Throwable t
        (emit-json! *err*
                    {:ok? false
                     :error (or (some-> (ex-data t) :error) :unexpected-error)
                     :message (.getMessage t)
                     :details (dissoc (ex-data t) :error)})
        1))))
