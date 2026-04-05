(ns formpatch.cli
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [formpatch.core :as core]))

(def ^:private usage-text
  (str/join
   "\n"
   ["Usage:"
    "  formpatch list --file PATH"
   "  formpatch get --file PATH --objects OID[@REV][,OID[@REV]...] [--file-rev FILE_REV]"
    "  formpatch insert --file PATH [--file-rev FILE_REV] (--before OID[@REV] | --after OID[@REV]) [--dry-run] [--diff] < forms.clj"
    "  formpatch replace --file PATH [--file-rev FILE_REV] --targets OID[@REV][,OID[@REV]...] [--empty] [--dry-run] [--diff] < forms.clj"
    ""
    "Notes:"
    "  - list emits JSON with truncated object text previews"
    "  - get emits JSON with full object text for one or more objects"
    "  - oid is the stable object identity; optional @rev guards against overwriting a changed object"
    "  - --file-rev enables strict whole-file optimistic locking"
    "  - insert/replace read raw top-level forms from stdin"
    "  - insert/replace emit a minimal mutation delta JSON on stdout"
    "  - --diff includes a unified diff in the JSON response"
    "  - use --empty with replace to delete objects without stdin"
    "  - failures emit JSON on stderr and exit non-zero"]))

(def ^:private text-preview-limit 120)

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
  (let [[oid rev & more] (str/split value #"@" 3)]
    (when (or (seq more) (str/blank? oid))
      (throw (ex-info "Invalid handle"
                      {:error :invalid-handle
                       :value value})))
    {:oid oid
     :rev (when-not (str/blank? rev) rev)}))

(defn- parse-handles
  [value]
  (when (str/blank? value)
    (throw (ex-info "Missing handles"
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

      (#{"--file" "--file-rev" "--before" "--after" "--targets" "--objects"} arg)
      (let [value (first more)]
        (when (nil? value)
          (throw (ex-info "Missing option value"
                          {:error :invalid-args
                           :option arg})))
        (recur (assoc opts
                      (case arg
                        "--file" :file
                        "--file-rev" :file-rev
                        "--before" :before
                        "--after" :after
                        "--targets" :targets
                        "--objects" :objects)
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
  (let [before-file (temp-path "formpatch-before-")
        after-file (temp-path "formpatch-after-")]
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

(defn- truncate-text
  [text]
  (if (> (count text) text-preview-limit)
    {:text (str (subs text 0 text-preview-limit) "...")
     :text-truncated? true}
    {:text text
     :text-truncated? false}))

(defn- preview-object
  [object]
  (merge (dissoc object :text)
         (truncate-text (:text object))))

(defn- run-list
  [opts]
  (update (core/list-objects (require-option opts :file))
          :objects
          (fn [objects]
            (mapv preview-object objects))))

(defn- run-get
  [opts]
  (core/get-objects
   {:file (require-option opts :file)
    :file-rev (:file-rev opts)
    :objects (parse-handles (require-option opts :objects))}))

(defn- run-insert
  [opts]
  (let [before (:before opts)
        after (:after opts)]
    (when (= (boolean before) (boolean after))
      (throw (ex-info "Exactly one of --before or --after is required"
                      {:error :invalid-args})))
    (core/insert-objects!
     {:file (require-option opts :file)
      :file-rev (:file-rev opts)
      :position (if before :before :after)
      :anchor (parse-handle (or before after))
      :dry-run? (:dry-run? opts)
      :new-source (stdin-text)})))

(defn- run-replace
  [opts]
  (let [empty? (:empty? opts)]
    (core/replace-objects!
     {:file (require-option opts :file)
      :file-rev (:file-rev opts)
      :targets (parse-handles (require-option opts :targets))
      :empty? empty?
      :dry-run? (:dry-run? opts)
      :new-source (when-not empty? (stdin-text))})))

(defn- mutation-diff
  [response opts]
  (when (and (:diff? opts) (contains? response :before-text))
    (unified-diff (:before-text response) (:after-text response))))

(defn- mutation-json-response
  [response opts]
  (cond-> (-> response
              (select-keys [:file :file-rev :changed? :touched :deleted :before :after])
              (assoc :ok? true))
    (:diff? opts)
    (assoc :diff (or (mutation-diff response opts) ""))))

(defn- emit-success!
  [command response opts]
  (cond
    (#{"list" "get"} command)
    (emit-json! *out* (assoc response :ok? true))

    (#{"insert" "replace"} command)
    (emit-json! *out* (mutation-json-response response opts))))

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
                         "get" (run-get opts)
                         "insert" (run-insert opts)
                         "replace" (run-replace opts)
                         (throw (ex-info "Unknown command"
                                         {:error :invalid-args
                                          :command command})))]
          (emit-success! command response opts)
          0))
      (catch Throwable t
        (emit-json! *err*
                    {:ok? false
                     :error (or (some-> (ex-data t) :error) :unexpected-error)
                     :message (.getMessage t)
                     :details (dissoc (ex-data t) :error)})
        1))))
