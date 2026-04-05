(ns mike.code-editor)

(defn usage
  [_]
  (println "Use the Babashka CLI entrypoint: ./bin/clj-objects --help"))

(defn -main
  [& _]
  (usage nil))
