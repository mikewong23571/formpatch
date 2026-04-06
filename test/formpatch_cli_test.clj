(ns formpatch-cli-test
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [formpatch.core :as core]))

(def ^:dynamic *state-dir* nil)

(def ^:private long-payload
  "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz")

(def ^:private sample-source
  (str "(ns demo.core)\n\n"
       "(defn alpha\n"
       "  []\n"
       "  {:payload \"" long-payload "\"})\n\n"
       "(def beta 1)\n"))

(defn- temp-dir
  []
  (.getCanonicalPath
   (.toFile (java.nio.file.Files/createTempDirectory
             "formpatch-cli-test-"
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
    (binding [*state-dir* dir]
      (try
        (f)
        (finally
          (if previous
            (System/setProperty "formpatch.state-dir" previous)
            (System/clearProperty "formpatch.state-dir")))))))

(use-fixtures :each with-temp-state-dir)

(defn- command-env
  []
  (assoc (into {} (System/getenv))
         "FORMPATCH_STATE_DIR" *state-dir*))

(defn- run-cli
  [args & {:keys [in]}]
  (let [cmd (into [(str (.getCanonicalPath (java.io.File. "bin/formpatch")))] args)]
    (apply shell/sh
           (concat cmd
                   [:dir (.getCanonicalPath (java.io.File. "."))]
                   [:env (command-env)]
                   (when (some? in) [:in in])))))

(defn- run-bb-main
  [args & {:keys [in]}]
  (let [cmd (concat ["bb" "--classpath" "src" "-m" "formpatch.cli/-main"]
                    args)]
    (apply shell/sh
           (concat cmd
                   [:dir (.getCanonicalPath (java.io.File. "."))]
                   [:env (command-env)]
                   (when (some? in) [:in in])))))

(defn- contains-field?
  [s field value]
  (boolean
   (re-find (re-pattern (str "\"" (java.util.regex.Pattern/quote field)
                            "\"\\s*:\\s*"
                            value))
            s)))

(defn- contains-string-field?
  [s field value]
  (contains-field? s field (str "\"" (java.util.regex.Pattern/quote value) "\"")))

(defn- handle
  [object]
  (str (:oid object) "@" (:rev object)))

(deftest cli-list-emits-machine-readable-json
  (let [path (temp-file sample-source)
        {:keys [exit out err]} (run-cli ["list" "--file" path])]
    (is (zero? exit))
    (is (str/blank? err))
    (is (contains-field? out "ok" "true"))
    (is (contains-string-field? out "file" path))
    (is (contains-field? out "file_rev" "\"[0-9a-f]{8}\""))
    (is (contains-string-field? out "name" "demo.core"))
    (is (contains-string-field? out "name" "alpha"))
    (is (contains-string-field? out "name" "beta"))
    (is (contains-field? out "text_truncated" "true"))
    (is (str/includes? out "oid_"))
    (is (not (str/includes? out long-payload)))))

(deftest cli-get-returns-full-objects
  (let [path (temp-file sample-source)
        {:keys [file-rev objects]} (core/list-objects path)
        alpha (second objects)
        beta (nth objects 2)
        {:keys [exit out err]} (run-cli ["get"
                                         "--file" path
                                         "--file-rev" file-rev
                                         "--objects" (str (:oid alpha) "," (handle beta))])]
    (is (zero? exit))
    (is (str/blank? err))
    (is (contains-field? out "ok" "true"))
    (is (contains-string-field? out "file" path))
    (is (contains-string-field? out "file_rev" file-rev))
    (is (contains-string-field? out "oid" (:oid alpha)))
    (is (contains-string-field? out "oid" (:oid beta)))
    (is (contains-string-field? out "rev" (:rev alpha)))
    (is (contains-string-field? out "rev" (:rev beta)))
    (is (contains-string-field? out "name" "alpha"))
    (is (contains-string-field? out "name" "beta"))
    (is (str/includes? out long-payload))
    (is (not (str/includes? out "\"text_truncated\"")))))

(deftest bb-main-entrypoint-works-like-installed-tool
  (let [path (temp-file sample-source)
        {:keys [exit out err]} (run-bb-main ["list" "--file" path])]
    (is (zero? exit))
    (is (str/blank? err))
    (is (contains-field? out "ok" "true"))
    (is (contains-string-field? out "name" "alpha"))))

(deftest cli-insert-supports-dry-run-and-diff
  (let [path (temp-file sample-source)
        original (slurp path)
        {:keys [objects]} (core/list-objects path)
        alpha (second objects)
        {:keys [exit out err]} (run-cli ["insert"
                                         "--file" path
                                         "--after" (handle alpha)
                                         "--dry-run"
                                        "--diff"]
                                        :in "(defn helper [] :ok)\n")]
    (is (zero? exit))
    (is (str/blank? err))
    (is (contains-field? out "ok" "true"))
    (is (contains-field? out "changed" "true"))
    (is (contains-string-field? out "name" "helper"))
    (is (str/includes? out "@@"))
    (is (= original (slurp path)))))

(deftest cli-replace-empty-writes-file
  (let [path (temp-file sample-source)
        {:keys [objects]} (core/list-objects path)
        beta (nth objects 2)
        {:keys [exit out err]} (run-cli ["replace"
                                         "--file" path
                                         "--targets" (handle beta)
                                         "--empty"])]
    (is (zero? exit))
    (is (str/blank? err))
    (is (contains-field? out "ok" "true"))
    (is (contains-field? out "changed" "true"))
    (is (str/includes? out (:oid beta)))
    (is (not (str/includes? (slurp path) "(def beta 1)")))))

(deftest cli-handles-survive-across-edits
  (let [path (temp-file sample-source)
        {:keys [file-rev objects]} (core/list-objects path)
        alpha (second objects)
        beta (nth objects 2)
        insert-result (run-cli ["insert"
                                "--file" path
                                "--file-rev" file-rev
                                "--after" (handle alpha)]
                               :in "(def helper :ok)\n")
        replace-result (run-cli ["replace"
                                 "--file" path
                                 "--targets" (handle beta)]
                                :in "(def beta 2)\n")]
    (is (zero? (:exit insert-result)))
    (is (zero? (:exit replace-result)))
    (is (contains-field? (:out insert-result) "ok" "true"))
    (is (contains-field? (:out replace-result) "ok" "true"))
    (is (str/includes? (slurp path) "(def helper :ok)"))
    (is (str/includes? (slurp path) "(def beta 2)"))))

(deftest cli-errors-remain-machine-readable
  (let [path (temp-file sample-source)
        invalid (run-cli ["get"
                          "--file" path
                          "--objects" "bad@rev@extra"])]
    (is (= 1 (:exit invalid)))
    (is (str/blank? (:out invalid)))
    (is (contains-field? (:err invalid) "ok" "false"))
    (is (contains-string-field? (:err invalid) "error" "invalid_handle"))))

(deftest cli-insert-head-prepends-to-file
  (let [path (temp-file sample-source)
        {:keys [objects]} (core/list-objects path)
        first-object (first objects)
        {:keys [exit out err]} (run-cli ["insert" "--file" path "--head"]
                                        :in "(def prepended true)\n")]
    (is (zero? exit))
    (is (str/blank? err))
    (is (contains-field? out "ok" "true"))
    (is (contains-field? out "changed" "true"))
    (is (contains-string-field? out "name" "prepended"))
    (is (contains-string-field? out "oid" (:oid first-object)))
    (is (str/starts-with? (slurp path) "(def prepended true)"))))

(deftest cli-insert-tail-appends-to-file
  (let [path (temp-file sample-source)
        {:keys [objects]} (core/list-objects path)
        last-object (last objects)
        {:keys [exit out err]} (run-cli ["insert" "--file" path "--tail"]
                                        :in "(def appended true)\n")]
    (is (zero? exit))
    (is (str/blank? err))
    (is (contains-field? out "ok" "true"))
    (is (contains-field? out "changed" "true"))
    (is (contains-string-field? out "name" "appended"))
    (is (contains-string-field? out "oid" (:oid last-object)))
    (is (str/ends-with? (slurp path) "(def appended true)\n"))))

(deftest cli-insert-head-on-empty-file
  (let [path (temp-file "")
        {:keys [exit out err]} (run-cli ["insert" "--file" path "--head"]
                                        :in "(ns foo.core)\n\n(def x 1)\n")]
    (is (zero? exit))
    (is (str/blank? err))
    (is (contains-field? out "ok" "true"))
    (is (contains-string-field? out "name" "foo.core"))
    (is (= "(ns foo.core)\n\n(def x 1)\n" (slurp path)))))

(deftest cli-insert-requires-exactly-one-position-flag
  (let [path (temp-file sample-source)
        no-flag (run-cli ["insert" "--file" path] :in "(def x 1)\n")
        two-flags (run-cli ["insert" "--file" path "--head" "--tail"] :in "(def x 1)\n")]
    (is (= 1 (:exit no-flag)))
    (is (str/blank? (:out no-flag)))
    (is (contains-field? (:err no-flag) "ok" "false"))
    (is (= 1 (:exit two-flags)))
    (is (contains-field? (:err two-flags) "ok" "false"))))

(deftest cli-errors-on-file-rev-mismatch-and-rev-mismatch
  (let [path (temp-file sample-source)
        {:keys [file-rev objects]} (core/list-objects path)
        alpha (second objects)
        beta (nth objects 2)]
    (run-cli ["insert"
              "--file" path
              "--after" (handle alpha)]
             :in "(def helper :ok)\n")
    (testing "strict file rev mismatch"
      (let [current-beta (->> (:objects (core/list-objects path))
                              (filter #(= "beta" (:name %)))
                              first)
            result (run-cli ["replace"
                             "--file" path
                             "--file-rev" file-rev
                             "--targets" (handle current-beta)]
                            :in "(def beta 2)\n")]
        (is (= 1 (:exit result)))
        (is (contains-string-field? (:err result) "error" "file_rev_mismatch"))))
    (testing "object rev mismatch"
      (let [result (run-cli ["replace"
                             "--file" path
                             "--targets" (handle alpha)]
                            :in "(defn alpha [] :changed)\n")
            stale (run-cli ["replace"
                            "--file" path
                            "--targets" (handle alpha)]
                           :in "(defn alpha [] :again)\n")]
        (is (zero? (:exit result)))
        (is (= 1 (:exit stale)))
        (is (contains-string-field? (:err stale) "error" "object_rev_mismatch"))))))
