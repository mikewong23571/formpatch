(ns mike.code-editor-cli-test
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [mike.code-editor.core :as core]))

(def ^:private sample-source
  (str "(ns demo.core)\n\n"
       "(defn alpha\n"
       "  [x]\n"
       "  x)\n\n"
       "(def beta 1)\n"))

(defn- temp-file
  [text]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "code-editor-cli-test-"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        file (java.io.File. dir "core.clj")]
    (spit file text)
    (.getCanonicalPath file)))

(defn- run-cli
  [args & {:keys [in]}]
  (let [cmd (into [(str (.getCanonicalPath (java.io.File. "bin/clj-objects")))] args)]
    (apply shell/sh
           (concat cmd
                   [:dir (.getCanonicalPath (java.io.File. "."))]
                   (when (some? in) [:in in])))))

(defn- run-bb-main
  [args & {:keys [in]}]
  (let [cmd (concat ["bb" "--classpath" "src" "-m" "mike.code-editor.cli/-main"]
                    args)]
    (apply shell/sh
           (concat cmd
                   [:dir (.getCanonicalPath (java.io.File. "."))]
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

(deftest cli-list-emits-machine-readable-json
  (let [path (temp-file sample-source)
        {:keys [exit out err]} (run-cli ["list" "--file" path])]
    (is (zero? exit))
    (is (str/blank? err))
    (is (contains-field? out "ok" "true"))
    (is (contains-string-field? out "file" path))
    (is (contains-string-field? out "name" "demo.core"))
    (is (contains-string-field? out "name" "alpha"))
    (is (contains-string-field? out "name" "beta"))))

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
        {:keys [snapshot objects]} (core/list-objects path)
        anchor (second objects)
        {:keys [exit out err]} (run-cli ["insert"
                                         "--file" path
                                         "--snapshot" snapshot
                                         "--after" (str (:id anchor) ":" (:hash anchor))
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
        {:keys [snapshot objects]} (core/list-objects path)
        beta (nth objects 2)
        {:keys [exit out err]} (run-cli ["replace"
                                         "--file" path
                                         "--snapshot" snapshot
                                         "--targets" (str (:id beta) ":" (:hash beta))
                                         "--empty"])]
    (is (zero? exit))
    (is (str/blank? err))
    (is (contains-field? out "ok" "true"))
    (is (contains-field? out "changed" "true"))
    (is (not (str/includes? (slurp path) "(def beta 1)")))))

(deftest cli-errors-remain-machine-readable
  (let [path (temp-file sample-source)
        {:keys [snapshot objects]} (core/list-objects path)
        anchor (second objects)
        {:keys [exit out err]} (run-cli ["insert"
                                         "--file" path
                                         "--snapshot" snapshot
                                         "--before" (str (:id anchor) ":" (:hash anchor))
                                         "--after" (str (:id anchor) ":" (:hash anchor))]
                                        :in "(def invalid true)\n")]
    (is (= 1 exit))
    (is (str/blank? out))
    (is (contains-field? err "ok" "false"))
    (is (contains-string-field? err "error" "invalid_args"))))

(deftest cli-errors-on-invalid-handle-and-stale-snapshot
  (let [path (temp-file sample-source)
        {:keys [snapshot objects]} (core/list-objects path)
        alpha (second objects)
        invalid (run-cli ["replace"
                          "--file" path
                          "--snapshot" snapshot
                          "--targets" "bad-handle"
                          "--empty"])
        stale (run-cli ["replace"
                        "--file" path
                        "--snapshot" "deadbeef"
                        "--targets" (str (:id alpha) ":" (:hash alpha))
                        "--empty"])]
    (testing "invalid handles"
      (is (= 1 (:exit invalid)))
      (is (contains-string-field? (:err invalid) "error" "invalid_handle")))
    (testing "stale snapshots"
      (is (= 1 (:exit stale)))
      (is (contains-string-field? (:err stale) "error" "snapshot_mismatch")))))
