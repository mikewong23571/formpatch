(ns formpatch.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]))

(def ^:private trivia-tags
  #{:comma :newline :whitespace})

(defn- fail
  [error details]
  (throw (ex-info (name error) (assoc details :error error))))

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
  (merge {:id idx
          :hash (short-hash text)
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

(defn- state-from-text
  [file text]
  {:file (canonical-path file)
   :snapshot (short-hash text)
   :objects (objects-from-text text)
   :text text})

(defn read-state
  [file]
  (state-from-text file (read-file-text file)))

(defn list-objects
  [file]
  (dissoc (read-state file) :text))

(defn- require-matching-snapshot!
  [{:keys [snapshot] :as state} expected]
  (when-not (= snapshot expected)
    (fail :snapshot-mismatch
          {:snapshot expected
           :actual snapshot
           :file (:file state)})))

(defn- require-object!
  [state id]
  (or (get (:objects state) id)
      (fail :object-not-found
            {:id id
             :file (:file state)})))

(defn- require-hash!
  [state id expected]
  (let [object (require-object! state id)]
    (when-not (= (:hash object) expected)
      (fail :object-hash-mismatch
            {:id id
             :expected expected
             :actual (:hash object)
             :file (:file state)}))
    object))

(defn- ensure-contiguous-targets!
  [targets]
  (let [ids (mapv :id targets)]
    (when-not (= ids (vec (range (first ids) (inc (last ids)))))
      (fail :non-contiguous-targets {:targets targets}))))

(defn parse-stdin-objects
  [source]
  (try
    (mapv #(select-keys % [:text :readable? :head :name])
          (objects-from-text source))
    (catch Throwable t
      (fail :invalid-new-object
            {:message (.getMessage t)}))))

(defn- apply-edit
  [{:keys [file snapshot dry-run?]} build-next]
  (let [state (read-state file)]
    (require-matching-snapshot! state snapshot)
    (let [next-objects (build-next state)
          next-text (normalize-file-text next-objects)
          changed? (not= (:text state) next-text)
          next-state (state-from-text file next-text)]
      (when (and changed? (not dry-run?))
        (spit file next-text))
      {:file (:file next-state)
       :snapshot (:snapshot next-state)
       :changed? changed?
       :objects (:objects next-state)
       :before-text (:text state)
       :after-text next-text})))

(defn insert-objects!
  [{:keys [file snapshot anchor-id anchor-hash position new-source dry-run?] :as opts}]
  (let [new-objects (parse-stdin-objects (or new-source ""))]
    (apply-edit
     (assoc opts :file file :snapshot snapshot :dry-run? dry-run?)
     (fn [state]
       (require-hash! state anchor-id anchor-hash)
       (let [objects (:objects state)
             split-at (case position
                        :before anchor-id
                        :after (inc anchor-id)
                        (fail :invalid-position {:position position}))
             before (subvec objects 0 split-at)
             after (subvec objects split-at)]
         (into [] (concat before new-objects after)))))))

(defn replace-objects!
  [{:keys [file snapshot targets new-source dry-run?] :as opts}]
  (let [delete? (:empty? opts)
        new-objects (if delete?
                      []
                      (parse-stdin-objects (or new-source "")))]
    (apply-edit
     (assoc opts :file file :snapshot snapshot :dry-run? dry-run?)
     (fn [state]
       (when (empty? targets)
         (fail :object-not-found {:targets [] :file (:file state)}))
       (doseq [{:keys [id hash]} targets]
         (require-hash! state id hash))
       (let [sorted-targets (vec (sort-by :id targets))
             _ (ensure-contiguous-targets! sorted-targets)
             start-id (:id (first sorted-targets))
             end-id (:id (last sorted-targets))
             objects (:objects state)
             before (subvec objects 0 start-id)
             after (subvec objects (inc end-id))]
         (into [] (concat before new-objects after)))))))
