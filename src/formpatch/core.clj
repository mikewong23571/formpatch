(ns formpatch.core
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]))

(def ^:private trivia-tags
  #{:comma :newline :whitespace})

(def ^:private identity-store-version 2)

(def ^:private oid-width 6)

(def ^:private oid-alphabet
  "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")

(def ^:private oid-base
  (count oid-alphabet))

(def ^:private max-oid-value
  (reduce *' 1 (repeat oid-width oid-base)))

(defn- fail
  [error details]
  (throw (ex-info (name error) (assoc details :error error))))

(defn- valid-next-oid?
  [n]
  (and (integer? n)
       (<= 1 n)
       (<= n max-oid-value)))

(defn- allocate-oid
  [n]
  (when-not (valid-next-oid? n)
    (fail :identity-store-corrupt
          {:message "Invalid next oid counter"
           :next-oid n}))
  (when (= n max-oid-value)
    (fail :oid-exhausted
          {:message "Exhausted file-local oid space"
           :width oid-width}))
  [(loop [remaining n
          chars '()]
     (if (= (count chars) oid-width)
       (apply str chars)
       (let [idx (int (mod remaining oid-base))]
         (recur (quot remaining oid-base)
                (conj chars (.charAt oid-alphabet idx))))))
   (inc n)])

(defn- canonical-path
  [path]
  (.getCanonicalPath (io/file path)))

(defn- sha1
  [s]
  (let [digest (java.security.MessageDigest/getInstance "SHA-1")]
    (.update digest (.getBytes (str s) "UTF-8"))
    (format "%040x" (java.math.BigInteger. 1 (.digest digest)))))

(defn- short-hash
  [s]
  (subs (sha1 s) 0 8))

(defn- safe-sexpr
  [node]
  (try
    (n/sexpr node)
    (catch Throwable _
      ::unreadable)))

(defn- semantic-child?
  [node]
  (let [tag (n/tag node)]
    (and (not (trivia-tags tag))
         (not= :comment tag))))

(defn- list-head-and-name
  [node]
  (let [children (filterv semantic-child? (n/children node))
        head-sexp (some-> children first safe-sexpr)
        name-sexp (some-> children second safe-sexpr)]
    {:head (when (symbol? head-sexp) (str head-sexp))
     :name (when (symbol? name-sexp) (str name-sexp))}))

(defn- token-head-and-name
  [node]
  (let [sexpr (safe-sexpr node)]
    {:head (when (symbol? sexpr) (str sexpr))
     :name nil}))

(defn- summarize-main-node
  [node]
  (let [{:keys [head name]}
        (case (n/tag node)
          :list (list-head-and-name node)
          :token (token-head-and-name node)
          {:head nil :name nil})]
    {:readable? (not= ::unreadable (safe-sexpr node))
     :head head
     :name name}))

(defn- trim-object-text
  [s]
  (str/replace s #"\s+\z" ""))

(defn- object-fragments
  [text]
  (let [children (n/children (p/parse-string-all (or text "")))]
    (loop [remaining children
           pending []
           fragments []]
      (if-let [node (first remaining)]
        (let [tag (n/tag node)]
          (cond
            (= :comment tag)
            (recur (rest remaining) (conj pending node) fragments)

            (trivia-tags tag)
            (if (some #(= :comment (n/tag %)) pending)
              (recur (rest remaining) (conj pending node) fragments)
              (recur (rest remaining) pending fragments))

            :else
            (let [text' (->> (concat pending [node])
                             (map n/string)
                             (apply str)
                             trim-object-text)]
              (recur (rest remaining)
                     []
                     (conj fragments {:main-node node
                                      :text text'})))))
        (if (some #(= :comment (n/tag %)) pending)
          (conj fragments {:main-node (first pending)
                           :text (->> pending
                                      (map n/string)
                                      (apply str)
                                      trim-object-text)})
          fragments)))))

(defn- fragment->object
  [idx {:keys [main-node text]}]
  (merge {:index idx
          :rev (short-hash text)
          :text text}
         (summarize-main-node main-node)))

(defn- objects-from-text
  [text]
  (mapv fragment->object (range) (object-fragments text)))

(defn- normalize-file-text
  [objects]
  (if (seq objects)
    (str (str/join "\n\n" (map (comp trim-object-text :text) objects)) "\n")
    "\n"))

(defn- read-file-text
  [path]
  (let [file (io/file path)]
    (when-not (.exists file)
      (fail :file-not-found {:file (canonical-path path)}))
    (slurp file)))

(defn- identity-store-dir
  []
  (io/file (or (System/getenv "FORMPATCH_STATE_DIR")
               (System/getProperty "formpatch.state-dir")
               (str (System/getProperty "user.home") "/.cache/formpatch/state"))))

(defn- identity-store-file
  [file]
  (io/file (identity-store-dir)
           (str (sha1 file) ".edn")))

(defn- ensure-parent-dir!
  [file]
  (let [parent (.getParentFile (io/file file))]
    (when parent
      (.mkdirs parent))))

(defn- read-identity-store
  [file]
  (let [store-file (identity-store-file file)]
    (when (.exists store-file)
      (try
        (let [store (edn/read-string (slurp store-file))]
          (when-not (= identity-store-version (:version store))
            (fail :identity-store-corrupt
                  {:file file
                   :state-file (.getCanonicalPath store-file)
                   :message "Unsupported identity store version"}))
          (when-not (= file (:file store))
            (fail :identity-store-corrupt
                  {:file file
                   :state-file (.getCanonicalPath store-file)
                   :message "Identity store path mismatch"}))
          (when-not (valid-next-oid? (:next-oid store))
            (fail :identity-store-corrupt
                  {:file file
                   :state-file (.getCanonicalPath store-file)
                   :message "Invalid next oid counter"}))
          store)
        (catch clojure.lang.ExceptionInfo ex
          (throw ex))
        (catch Throwable t
          (fail :identity-store-corrupt
                {:file file
                 :state-file (.getCanonicalPath store-file)
                 :message (.getMessage t)}))))))

(defn- lcs-matches
  [previous current]
  (let [n (count previous)
        m (count current)
        table (vec (repeatedly (inc n) #(int-array (inc m))))]
    (doseq [i (range (dec n) -1 -1)]
      (let [row ^ints (nth table i)]
        (doseq [j (range (dec m) -1 -1)]
          (aset-int row
                    j
                    (if (= (:rev (nth previous i))
                           (:rev (nth current j)))
                      (inc (aget ^ints (nth table (inc i)) (inc j)))
                      (max (aget ^ints (nth table (inc i)) j)
                           (aget row (inc j))))))))
    (loop [i 0
           j 0
           matches []]
      (cond
        (or (= i n) (= j m))
        matches

        (= (:rev (nth previous i)) (:rev (nth current j)))
        (recur (inc i) (inc j) (conj matches [i j]))

        (>= (aget ^ints (nth table (inc i)) j)
            (aget ^ints (nth table i) (inc j)))
        (recur (inc i) j matches)

        :else
        (recur i (inc j) matches)))))

(defn- finalize-object
  [idx object]
  (let [text (trim-object-text (:text object))
        oid (:oid object)]
    (when-not oid
      (fail :missing-oid
            {:index idx
             :name (:name object)
             :head (:head object)}))
    (-> object
        (assoc :index idx
               :oid oid
               :rev (short-hash text)
               :text text)
        (select-keys [:oid :index :rev :text :readable? :head :name]))))

(defn- finalize-objects
  [objects]
  (mapv finalize-object (range) objects))

(defn- compact-object
  [object]
  (select-keys object [:oid :index :rev :head :name]))

(defn- assign-oids
  [next-oid objects]
  (reduce (fn [{:keys [next-oid objects]} object]
            (if (:oid object)
              {:next-oid next-oid
               :objects (conj objects object)}
              (let [[oid next-oid'] (allocate-oid next-oid)]
                {:next-oid next-oid'
                 :objects (conj objects (assoc object :oid oid))})))
          {:next-oid next-oid
           :objects []}
          objects))

(defn- attach-identities
  [store objects]
  (let [previous-objects (or (:objects store) [])
        oid-by-index (into {}
                           (map (fn [[previous-index current-index]]
                                  [current-index (:oid (nth previous-objects previous-index))]))
                           (lcs-matches previous-objects objects))
        {:keys [objects next-oid]}
        (assign-oids (or (:next-oid store) 1)
                     (map-indexed (fn [idx object]
                                    (assoc object :oid (get oid-by-index idx)))
                                  objects))]
    {:objects (finalize-objects objects)
     :next-oid next-oid}))

(defn- write-identity-store!
  [state]
  (let [store-file (identity-store-file (:file state))
        payload {:version identity-store-version
                 :file (:file state)
                 :file-rev (:file-rev state)
                 :next-oid (:next-oid state)
                 :objects (mapv #(select-keys % [:oid :rev]) (:objects state))}]
    (ensure-parent-dir! store-file)
    (spit store-file (pr-str payload))))

(defn- state-from-text
  [file text]
  (let [file' (canonical-path file)
        store (read-identity-store file')
        objects (objects-from-text text)
        {:keys [objects next-oid]} (attach-identities store objects)
        state {:file file'
               :file-rev (short-hash text)
               :objects objects
               :next-oid next-oid
               :text text}]
    state))

(defn read-state
  [file]
  (let [state (state-from-text file (read-file-text file))]
    (write-identity-store! state)
    state))

(defn list-objects
  [file]
  (dissoc (read-state file) :text :next-oid))

(defn- require-matching-file-rev!
  [{:keys [file-rev file]} expected]
  (when (and expected (not= file-rev expected))
    (fail :file-rev-mismatch
          {:file file
           :expected expected
           :actual file-rev})))

(defn- require-object!
  [state oid]
  (or (some #(when (= (:oid %) oid) %) (:objects state))
      (fail :object-not-found
            {:oid oid
             :file (:file state)})))

(defn- require-rev!
  [state object expected]
  (when (and expected (not= (:rev object) expected))
    (fail :object-rev-mismatch
          {:oid (:oid object)
           :file (:file state)
           :expected expected
           :actual (:rev object)}))
  object)

(defn- require-handle!
  [state {:keys [oid rev]}]
  (let [object (require-object! state oid)]
    (require-rev! state object rev)))

(defn get-objects
  [{:keys [file file-rev objects]}]
  (let [state (read-state file)
        resolved-objects (mapv #(require-handle! state %) objects)]
    (require-matching-file-rev! state file-rev)
    {:file (:file state)
     :file-rev (:file-rev state)
     :objects resolved-objects}))

(defn- ensure-contiguous-targets!
  [targets]
  (let [indexes (mapv :index targets)]
    (when-not (= indexes (vec (range (first indexes) (inc (last indexes)))))
      (fail :non-contiguous-targets
            {:targets (mapv #(select-keys % [:oid :index]) targets)}))))

(defn parse-stdin-objects
  [source]
  (try
    (mapv #(select-keys % [:text :readable? :head :name])
          (objects-from-text source))
    (catch Throwable t
      (fail :invalid-new-object
            {:message (.getMessage t)}))))

(defn- shape-replacement-objects
  [targets new-objects]
  (cond
    (empty? new-objects)
    []

    (= (count targets) (count new-objects))
    (mapv (fn [target object]
            (assoc object :oid (:oid target)))
          targets
          new-objects)

    (= 1 (count targets) (count new-objects))
    [(assoc (first new-objects) :oid (:oid (first targets)))]

    :else
    new-objects))

(defn- object-by-oid
  [objects oid]
  (some #(when (= (:oid %) oid) %) objects))

(defn- object-at-index
  [objects idx]
  (when (and (some? idx) (<= 0 idx) (< idx (count objects)))
    (nth objects idx)))

(defn- build-mutation-delta
  [next-objects {:keys [touched-oids deleted-oids span-start span-count]}]
  {:touched (mapv (comp compact-object #(object-by-oid next-objects %))
                  touched-oids)
   :deleted (vec deleted-oids)
   :before (some-> (object-at-index next-objects (dec span-start))
                   compact-object)
   :after (some-> (object-at-index next-objects (+ span-start span-count))
                  compact-object)})

(defn- apply-edit
  [{:keys [file file-rev dry-run?]} build-next]
  (let [state (read-state file)]
    (require-matching-file-rev! state file-rev)
    (let [{:keys [objects delta next-oid]} (build-next state)
          next-objects (finalize-objects objects)
          next-text (normalize-file-text next-objects)
          changed? (not= (:text state) next-text)
          next-state {:file (:file state)
                      :file-rev (short-hash next-text)
                      :next-oid (or next-oid (:next-oid state))
                      :objects next-objects
                      :text next-text}
          mutation-delta (build-mutation-delta next-objects delta)]
      (when (and changed? (not dry-run?))
        (spit file next-text)
        (write-identity-store! next-state))
      {:file (:file next-state)
       :file-rev (:file-rev next-state)
       :changed? changed?
       :touched (:touched mutation-delta)
       :deleted (:deleted mutation-delta)
       :before (:before mutation-delta)
       :after (:after mutation-delta)
       :objects (:objects next-state)
       :before-text (:text state)
       :after-text next-text})))

(defn insert-objects!
  [{:keys [file file-rev anchor position new-source dry-run?] :as opts}]
  (let [new-objects (parse-stdin-objects (or new-source ""))]
    (apply-edit
     (assoc opts :file file :file-rev file-rev :dry-run? dry-run?)
     (fn [state]
       (let [state-objects (:objects state)
             split-at (if anchor
                        (let [anchor-object (require-handle! state anchor)]
                          (case position
                            :before (:index anchor-object)
                            :after (inc (:index anchor-object))
                            (fail :invalid-position {:position position})))
                        (case position
                          :before 0
                          :after (count state-objects)
                          (fail :invalid-position {:position position})))
             {:keys [objects next-oid]} (assign-oids (:next-oid state) new-objects)
             before (subvec state-objects 0 split-at)
             after (subvec state-objects split-at)
             output (into [] (concat before objects after))]
         {:objects output
          :next-oid next-oid
          :delta {:touched-oids (mapv :oid objects)
                  :deleted-oids []
                  :span-start split-at
                  :span-count (count objects)}})))))

(defn replace-objects!
  [{:keys [file file-rev targets new-source dry-run?] :as opts}]
  (let [delete? (:empty? opts)
        new-objects (if delete?
                      []
                      (parse-stdin-objects (or new-source "")))]
    (apply-edit
     (assoc opts :file file :file-rev file-rev :dry-run? dry-run?)
     (fn [state]
       (when (empty? targets)
         (fail :object-not-found {:targets [] :file (:file state)}))
       (let [resolved-targets (mapv #(require-handle! state %) targets)
             sorted-targets (vec (sort-by :index resolved-targets))
             {:keys [objects next-oid]}
             (assign-oids (:next-oid state)
                          (shape-replacement-objects sorted-targets new-objects))
             start-index (:index (first sorted-targets))
             end-index (:index (last sorted-targets))
             before (subvec (:objects state) 0 start-index)
             after (subvec (:objects state) (inc end-index))
             output (into [] (concat before objects after))]
         (ensure-contiguous-targets! sorted-targets)
         {:objects output
          :next-oid next-oid
          :delta {:touched-oids (mapv :oid objects)
                  :deleted-oids (vec (remove (set (map :oid objects))
                                             (map :oid sorted-targets)))
                  :span-start start-index
                  :span-count (count objects)}})))))
