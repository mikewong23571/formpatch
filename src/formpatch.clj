(ns formpatch)

(defn usage
  [_]
  (println "Use the Babashka CLI entrypoint: ./bin/formpatch --help"))

(defn -main
  [& _]
  (usage nil))
