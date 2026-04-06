(ns formpatch-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [formpatch.core :as sut]))

(def ^:private sample-source
  (str ";; header\n"
       "(ns demo.core)\n\n"
       "(defn alpha\n"
       "  [x]\n"
       "  x)\n\n"
       "(def beta 1)\n"))

(defn- temp-dir
  []
  (.getCanonicalPath
   (.toFile (java.nio.file.Files/createTempDirectory
             "formpatch-test-"
             (make-array java.nio.file.attribute.FileAttribute 0)))))

(defn- temp-file
  [text]
  (let [dir (java.io.File. (temp-dir))
        file (java.io.File. dir "core.clj")]
    (spit file text)
    (.getCanonicalPath file)))

(defn- with-temp-state-dir
  [f]
  (let [dir (temp-dir)
        previous (System/getProperty "formpatch.state-dir")]
    (System/setProperty "formpatch.state-dir" dir)
    (try
      (f)
      (finally
        (if previous
          (System/setProperty "formpatch.state-dir" previous)
          (System/clearProperty "formpatch.state-dir"))))))

(use-fixtures :each with-temp-state-dir)

(deftest list-objects-exposes-top-level-objects
  (let [path (temp-file sample-source)
        result (sut/list-objects path)]
    (is (= path (:file result)))
    (is (re-matches #"[0-9a-f]{8}" (:file-rev result)))
    (is (= 3 (count (:objects result))))
    (is (= ["demo.core" "alpha" "beta"]
           (mapv :name (:objects result))))
    (is (= ";; header\n(ns demo.core)"
           (:text (first (:objects result)))))
    (is (every? #(re-matches #"[0-9A-Za-z]{6}" (:oid %))
                (:objects result)))
    (is (every? #(re-matches #"[0-9a-f]{8}" (:rev %))
                (:objects result)))))

(deftest get-objects-exposes-full-objects
  (let [path (temp-file sample-source)
        {:keys [file-rev objects]} (sut/list-objects path)
        alpha (second objects)
        beta (nth objects 2)
        result (sut/get-objects
                {:file path
                 :file-rev file-rev
                 :objects [{:oid (:oid alpha)}
                           {:oid (:oid beta) :rev (:rev beta)}]})]
    (is (= path (:file result)))
    (is (= file-rev (:file-rev result)))
    (is (= [alpha beta] (:objects result)))))

(deftest insert-objects-preserves-untouched-oids
  (let [path (temp-file sample-source)
        {:keys [objects]} (sut/list-objects path)
        alpha (second objects)
        beta (nth objects 2)
        result (sut/insert-objects!
                {:file path
                 :anchor {:oid (:oid alpha) :rev (:rev alpha)}
                 :position :after
                 :new-source (str "(defn helper-a\n"
                                  "  []\n"
                                  "  :a)\n\n"
                                  "(defn helper-b\n"
                                  "  []\n"
                                  "  :b)\n")})
        result-objects (:objects result)]
    (is (:changed? result))
    (is (= ["demo.core" "alpha" "helper-a" "helper-b" "beta"]
           (mapv :name result-objects)))
    (is (= ["helper-a" "helper-b"]
           (mapv :name (:touched result))))
    (is (= [] (:deleted result)))
    (is (= (:oid alpha) (:oid (:before result))))
    (is (= (:oid beta) (:oid (:after result))))
    (is (= (:oid alpha) (:oid (second result-objects))))
    (is (= (:oid beta) (:oid (last result-objects))))
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

(deftest handles-survive-external-insertions
  (let [path (temp-file sample-source)
        {:keys [objects]} (sut/list-objects path)
        beta (nth objects 2)]
    (spit path
          (str ";; header\n"
               "(ns demo.core)\n\n"
               "(defn alpha\n"
               "  [x]\n"
               "  x)\n\n"
               "(def helper :ok)\n\n"
               "(def beta 1)\n"))
    (let [result (sut/replace-objects!
                  {:file path
                   :targets [{:oid (:oid beta) :rev (:rev beta)}]
                   :new-source "(def beta 2)\n"})]
      (is (:changed? result))
      (is (= ["demo.core" "alpha" "helper" "beta"]
             (mapv :name (:objects result))))
      (is (= ["beta"]
             (mapv :name (:touched result))))
      (is (= [] (:deleted result)))
      (is (= (:oid beta)
             (:oid (last (:objects result)))))
      (is (str/includes? (slurp path) "(def beta 2)")))))

(deftest replace-supports-split-merge-delete-and-no-op
  (let [path (temp-file sample-source)
        {:keys [objects]} (sut/list-objects path)
        alpha (second objects)
        beta (nth objects 2)
        split-result (sut/replace-objects!
                      {:file path
                       :targets [{:oid (:oid alpha) :rev (:rev alpha)}]
                       :new-source (str "(defn helper\n"
                                        "  [x]\n"
                                        "  (inc x))\n\n"
                                        "(defn alpha\n"
                                        "  [x]\n"
                                        "  (helper x))\n")})]
    (is (= ["demo.core" "helper" "alpha" "beta"]
           (mapv :name (:objects split-result))))
    (let [helper (second (:objects split-result))
          alpha' (nth (:objects split-result) 2)
          beta' (nth (:objects split-result) 3)
          merged (sut/replace-objects!
                  {:file path
                   :targets [{:oid (:oid alpha') :rev (:rev alpha')}
                             {:oid (:oid beta') :rev (:rev beta')}]
                   :new-source "(defn alpha+beta [] {:alpha true :beta 1})\n"})]
      (is (:changed? merged))
      (is (= ["demo.core" "helper" "alpha+beta"]
             (mapv :name (:objects merged))))
      (let [helper' (second (:objects merged))
            merged-object (nth (:objects merged) 2)
            no-op (sut/replace-objects!
                   {:file path
                    :targets [{:oid (:oid merged-object) :rev (:rev merged-object)}]
                    :new-source (:text merged-object)})
            file-after-no-op (slurp path)
            deleted (sut/replace-objects!
                     {:file path
                      :targets [{:oid (:oid helper') :rev (:rev helper')}]
                      :empty? true})]
        (is (false? (:changed? no-op)))
        (is (= file-after-no-op (:after-text no-op)))
        (is (= [] (:deleted no-op)))
        (is (= [(:oid helper')]
               (:deleted deleted)))
        (is (= ["demo.core" "alpha+beta"]
               (mapv :name (:objects deleted))))))))

(deftest insert-dry-run-keeps-file-unchanged
  (let [path (temp-file sample-source)
        original (slurp path)
        {:keys [objects]} (sut/list-objects path)
        anchor (first objects)
        result (sut/insert-objects!
                {:file path
                 :anchor {:oid (:oid anchor) :rev (:rev anchor)}
                 :position :before
                 :dry-run? true
                 :new-source "(def prepended true)\n"})]
    (is (:changed? result))
    (is (= ["prepended" "demo.core" "alpha" "beta"]
           (mapv :name (:objects result))))
    (is (= original (slurp path)))))

(deftest replace-validates-revs-file-revs-ranges-and-new-source
  (let [path (temp-file sample-source)
        {:keys [file-rev objects]} (sut/list-objects path)
        ns-object (first objects)
        alpha (second objects)
        beta (nth objects 2)]
    (sut/replace-objects!
     {:file path
      :targets [{:oid (:oid alpha)}]
      :new-source "(defn alpha [x] (inc x))\n"})
    (testing "object rev mismatches are rejected"
      (let [error (try
                    (sut/replace-objects!
                     {:file path
                      :targets [{:oid (:oid alpha) :rev (:rev alpha)}]
                      :empty? true})
                    nil
                    (catch clojure.lang.ExceptionInfo ex
                      (ex-data ex)))]
        (is (= :object-rev-mismatch (:error error)))))
    (testing "strict file rev mismatches are rejected"
      (let [current-beta (->> (:objects (sut/list-objects path))
                              (filter #(= "beta" (:name %)))
                              first)
            error (try
                    (sut/replace-objects!
                     {:file path
                      :file-rev file-rev
                      :targets [{:oid (:oid current-beta) :rev (:rev current-beta)}]
                      :empty? true})
                    nil
                    (catch clojure.lang.ExceptionInfo ex
                      (ex-data ex)))]
        (is (= :file-rev-mismatch (:error error)))))
    (testing "non-contiguous target ranges are rejected"
      (let [current-objects (:objects (sut/list-objects path))
            current-beta (last current-objects)
            error (try
                    (sut/replace-objects!
                     {:file path
                      :targets [{:oid (:oid ns-object) :rev (:rev ns-object)}
                                {:oid (:oid current-beta) :rev (:rev current-beta)}]
                      :empty? true})
                    nil
                    (catch clojure.lang.ExceptionInfo ex
                      (ex-data ex)))]
        (is (= :non-contiguous-targets (:error error)))))
    (testing "invalid replacement source is rejected"
      (let [current-alpha (->> (:objects (sut/list-objects path))
                               (filter #(= "alpha" (:name %)))
                               first)
            error (try
                    (sut/replace-objects!
                     {:file path
                      :targets [{:oid (:oid current-alpha)}]
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
  (let [missing (.getCanonicalPath (java.io.File. "/tmp/definitely-missing-formpatch-file.clj"))
        error (try
                (sut/list-objects missing)
                nil
                (catch clojure.lang.ExceptionInfo ex
                  (ex-data ex)))]
    (is (= :file-not-found (:error error)))
    (is (= missing (:file error)))))

(deftest insert-rejects-invalid-position
  (let [path (temp-file sample-source)
        {:keys [objects]} (sut/list-objects path)
        anchor (first objects)
        error (try
                (sut/insert-objects!
                 {:file path
                  :anchor {:oid (:oid anchor)}
                  :position :middle
                  :new-source "(def bad true)\n"})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  (ex-data ex)))]
    (is (= :invalid-position (:error error)))))

(deftest replace-rejects-empty-targets
  (let [path (temp-file sample-source)
        error (try
                (sut/replace-objects!
                 {:file path
                  :targets []
                  :empty? true})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  (ex-data ex)))]
    (is (= :object-not-found (:error error)))))

(deftest parse-stdin-objects-allows-empty-source
  (is (= [] (sut/parse-stdin-objects ""))))

(deftest insert-at-head-prepends-to-file
  (let [path (temp-file sample-source)
        {:keys [objects]} (sut/list-objects path)
        first-object (first objects)
        result (sut/insert-objects!
                {:file path
                 :anchor nil
                 :position :before
                 :new-source "(def prepended true)\n"})]
    (is (:changed? result))
    (is (= ["prepended" "demo.core" "alpha" "beta"]
           (mapv :name (:objects result))))
    (is (= ["prepended"] (mapv :name (:touched result))))
    (is (nil? (:before result)))
    (is (= (:oid first-object) (:oid (:after result))))
    (is (str/starts-with? (slurp path) "(def prepended true)"))))

(deftest insert-at-tail-appends-to-file
  (let [path (temp-file sample-source)
        {:keys [objects]} (sut/list-objects path)
        last-object (last objects)
        result (sut/insert-objects!
                {:file path
                 :anchor nil
                 :position :after
                 :new-source "(def appended true)\n"})]
    (is (:changed? result))
    (is (= ["demo.core" "alpha" "beta" "appended"]
           (mapv :name (:objects result))))
    (is (= ["appended"] (mapv :name (:touched result))))
    (is (= (:oid last-object) (:oid (:before result))))
    (is (nil? (:after result)))
    (is (str/ends-with? (slurp path) "(def appended true)\n"))))

(deftest insert-into-empty-file
  (let [path (temp-file "")
        result (sut/insert-objects!
                {:file path
                 :anchor nil
                 :position :after
                 :new-source "(ns foo.core)\n\n(def x 1)\n"})]
    (is (:changed? result))
    (is (= ["foo.core" "x"] (mapv :name (:objects result))))
    (is (= ["foo.core" "x"] (mapv :name (:touched result))))
    (is (nil? (:before result)))
    (is (nil? (:after result)))))

(deftest deleted-oids-are-not-reused
  (let [path (temp-file sample-source)
        {:keys [objects]} (sut/list-objects path)
        alpha (second objects)
        inserted (sut/insert-objects!
                  {:file path
                   :anchor {:oid (:oid alpha) :rev (:rev alpha)}
                   :position :after
                   :new-source "(def helper :first)\n"})
        helper (nth (:objects inserted) 2)
        _ (sut/replace-objects!
           {:file path
            :targets [{:oid (:oid helper) :rev (:rev helper)}]
            :empty? true})
        reinserted (sut/insert-objects!
                    {:file path
                     :anchor {:oid (:oid alpha) :rev (:rev alpha)}
                     :position :after
                     :new-source "(def helper :second)\n"})
        helper' (nth (:objects reinserted) 2)]
    (is (= "helper" (:name helper')))
    (is (not= (:oid helper) (:oid helper')))))

(deftest new-oids-are-sequential-and-fixed-width
  (let [path (temp-file sample-source)
        {:keys [objects]} (sut/list-objects path)
        alpha (second objects)
        first-insert (sut/insert-objects!
                      {:file path
                       :anchor {:oid (:oid alpha) :rev (:rev alpha)}
                       :position :after
                       :new-source "(def helper-a :a)\n"})
        helper-a (nth (:objects first-insert) 2)
        second-insert (sut/insert-objects!
                       {:file path
                        :anchor {:oid (:oid helper-a) :rev (:rev helper-a)}
                        :position :after
                        :new-source "(def helper-b :b)\n"})
        helper-b (nth (:objects second-insert) 3)]
    (is (= ["000001" "000002" "000003"]
           (mapv :oid objects)))
    (is (= "000004" (:oid helper-a)))
    (is (= "000005" (:oid helper-b)))
    (is (every? #(= 6 (count %))
                [(:oid helper-a) (:oid helper-b)]))))

(deftest list-objects-rejects-invalid-next-oid-in-state-store
  (let [path (temp-file sample-source)
        _ (sut/list-objects path)
        state-dir (java.io.File. (System/getProperty "formpatch.state-dir"))
        state-file (first (seq (.listFiles state-dir)))]
    (spit state-file
          (pr-str {:version 2
                   :file path
                   :file-rev "deadbeef"
                   :next-oid 0
                   :objects []}))
    (let [error (try
                  (sut/list-objects path)
                  nil
                  (catch clojure.lang.ExceptionInfo ex
                    (ex-data ex)))]
      (is (= :identity-store-corrupt (:error error)))
      (is (= path (:file error)))
      (is (= "Invalid next oid counter" (:message error))))))
