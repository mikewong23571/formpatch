(ns mike.code-editor-test
  (:require [clojure.test :refer [deftest is testing]]
            [mike.code-editor.core :as sut]))

(def ^:private sample-source
  (str ";; header\n"
       "(ns demo.core)\n\n"
       "(defn alpha\n"
       "  [x]\n"
       "  x)\n\n"
       "(def beta 1)\n"))

(defn- temp-file
  [text]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "code-editor-test-"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        file (java.io.File. dir "core.clj")]
    (spit file text)
    (.getCanonicalPath file)))

(deftest list-objects-exposes-top-level-objects
  (let [path (temp-file sample-source)
        result (sut/list-objects path)]
    (is (= path (:file result)))
    (is (= 3 (count (:objects result))))
    (is (= ["demo.core" "alpha" "beta"]
           (mapv :name (:objects result))))
    (is (= ";; header\n(ns demo.core)"
           (:text (first (:objects result)))))
    (is (every? #(re-matches #"[0-9a-f]{8}" (:hash %))
                (:objects result)))))

(deftest insert-objects-supports-multi-object-source
  (let [path (temp-file sample-source)
        {:keys [snapshot objects]} (sut/list-objects path)
        anchor (second objects)
        result (sut/insert-objects!
                {:file path
                 :snapshot snapshot
                 :anchor-id (:id anchor)
                 :anchor-hash (:hash anchor)
                 :position :after
                 :new-source (str "(defn helper-a\n"
                                  "  []\n"
                                  "  :a)\n\n"
                                  "(defn helper-b\n"
                                  "  []\n"
                                  "  :b)\n")})]
    (is (:changed? result))
    (is (= ["demo.core" "alpha" "helper-a" "helper-b" "beta"]
           (mapv :name (:objects result))))
    (is (= (str ";; header\n"
                "(ns demo.core)\n\n"
                "(defn alpha\n"
                "  [x]\n"
                "  x)\n\n"
                "(defn helper-a\n"
                "  []\n"
                "  :a)\n\n"
                "(defn helper-b\n"
                "  []\n"
                "  :b)\n\n"
                "(def beta 1)\n")
           (slurp path)))))

(deftest replace-objects-supports-split-delete-and-snapshot-validation
  (let [path (temp-file sample-source)
        {:keys [snapshot objects]} (sut/list-objects path)
        target (second objects)
        split-result (sut/replace-objects!
                      {:file path
                       :snapshot snapshot
                       :targets [{:id (:id target)
                                  :hash (:hash target)}]
                       :new-source (str "(defn helper\n"
                                        "  [x]\n"
                                        "  (inc x))\n\n"
                                        "(defn alpha\n"
                                        "  [x]\n"
                                        "  (helper x))\n")})]
    (is (= ["demo.core" "helper" "alpha" "beta"]
           (mapv :name (:objects split-result))))
    (let [{:keys [snapshot objects]} (sut/list-objects path)
          beta (last objects)
          delete-result (sut/replace-objects!
                         {:file path
                          :snapshot snapshot
                          :targets [{:id (:id beta)
                                     :hash (:hash beta)}]
                          :empty? true})]
      (is (= ["demo.core" "helper" "alpha"]
             (mapv :name (:objects delete-result)))))
    (testing "stale snapshots are rejected"
      (let [error (try
                    (sut/replace-objects!
                     {:file path
                      :snapshot snapshot
                      :targets [{:id (:id target)
                                 :hash (:hash target)}]
                      :empty? true})
                    nil
                    (catch clojure.lang.ExceptionInfo ex
                      (ex-data ex)))]
        (is (= :snapshot-mismatch (:error error)))))))

(deftest insert-dry-run-keeps-file-unchanged
  (let [path (temp-file sample-source)
        original (slurp path)
        {:keys [snapshot objects]} (sut/list-objects path)
        anchor (first objects)
        result (sut/insert-objects!
                {:file path
                 :snapshot snapshot
                 :anchor-id (:id anchor)
                 :anchor-hash (:hash anchor)
                 :position :before
                 :dry-run? true
                 :new-source "(def prepended true)\n"})]
    (is (:changed? result))
    (is (= ["prepended" "demo.core" "alpha" "beta"]
           (mapv :name (:objects result))))
    (is (= original (slurp path)))))

(deftest replace-validates-hashes-target-ranges-and-new-source
  (let [path (temp-file sample-source)
        {:keys [snapshot objects]} (sut/list-objects path)
        alpha (second objects)
        beta (nth objects 2)]
    (testing "hash mismatches are rejected"
      (let [error (try
                    (sut/replace-objects!
                     {:file path
                      :snapshot snapshot
                      :targets [{:id (:id alpha)
                                 :hash "deadbeef"}]
                      :empty? true})
                    nil
                    (catch clojure.lang.ExceptionInfo ex
                      (ex-data ex)))]
        (is (= :object-hash-mismatch (:error error)))))
    (testing "non-contiguous target ranges are rejected"
      (let [error (try
                    (sut/replace-objects!
                     {:file path
                      :snapshot snapshot
                      :targets [{:id 0
                                 :hash (:hash (first objects))}
                                {:id (:id beta)
                                 :hash (:hash beta)}]
                      :empty? true})
                    nil
                    (catch clojure.lang.ExceptionInfo ex
                      (ex-data ex)))]
        (is (= :non-contiguous-targets (:error error)))))
    (testing "invalid replacement source is rejected"
      (let [error (try
                    (sut/replace-objects!
                     {:file path
                      :snapshot snapshot
                      :targets [{:id (:id alpha)
                                 :hash (:hash alpha)}]
                      :new-source "(defn broken [x]\n  (+ x 1)\n"})
                    nil
                    (catch clojure.lang.ExceptionInfo ex
                      (ex-data ex)))]
        (is (= :invalid-new-object (:error error)))))))

(deftest list-objects-exposes-comment-only-top-level-object
  (let [path (temp-file (str "(ns demo.core)\n\n"
                             "(def alpha 1)\n\n"
                             ";; trailing note\n"))
        result (sut/list-objects path)]
    (is (= 3 (count (:objects result))))
    (is (= ["demo.core" "alpha" nil]
           (mapv :name (:objects result))))
    (is (= ";; trailing note"
           (:text (last (:objects result)))))
    (is (false? (:readable? (last (:objects result)))))))

(deftest list-objects-fails-for-missing-file
  (let [missing (.getCanonicalPath (java.io.File. "/tmp/definitely-missing-code-editor-file.clj"))
        error (try
                (sut/list-objects missing)
                nil
                (catch clojure.lang.ExceptionInfo ex
                  (ex-data ex)))]
    (is (= :file-not-found (:error error)))
    (is (= missing (:file error)))))

(deftest replace-can-merge-objects-and-detect-no-op
  (let [path (temp-file sample-source)
        {:keys [snapshot objects]} (sut/list-objects path)
        alpha (second objects)
        beta (nth objects 2)
        merged (sut/replace-objects!
                {:file path
                 :snapshot snapshot
                 :targets [{:id (:id alpha) :hash (:hash alpha)}
                           {:id (:id beta) :hash (:hash beta)}]
                 :new-source "(defn alpha+beta [] {:alpha true :beta 1})\n"})]
    (is (:changed? merged))
    (is (= ["demo.core" "alpha+beta"]
           (mapv :name (:objects merged))))
    (let [{:keys [snapshot objects]} (sut/list-objects path)
          merged-object (second objects)
          no-op (sut/replace-objects!
                 {:file path
                  :snapshot snapshot
                  :targets [{:id (:id merged-object)
                             :hash (:hash merged-object)}]
                  :new-source (:text merged-object)})]
      (is (false? (:changed? no-op)))
      (is (= (slurp path)
             (:after-text no-op))))))

(deftest insert-rejects-invalid-position
  (let [path (temp-file sample-source)
        {:keys [snapshot objects]} (sut/list-objects path)
        anchor (first objects)
        error (try
                (sut/insert-objects!
                 {:file path
                  :snapshot snapshot
                  :anchor-id (:id anchor)
                  :anchor-hash (:hash anchor)
                  :position :middle
                  :new-source "(def bad true)\n"})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  (ex-data ex)))]
    (is (= :invalid-position (:error error)))))

(deftest replace-rejects-empty-targets
  (let [path (temp-file sample-source)
        {:keys [snapshot]} (sut/list-objects path)
        error (try
                (sut/replace-objects!
                 {:file path
                  :snapshot snapshot
                  :targets []
                  :empty? true})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  (ex-data ex)))]
    (is (= :object-not-found (:error error)))))

(deftest parse-stdin-objects-allows-empty-source
  (is (= [] (sut/parse-stdin-objects ""))))
