#! /usr/bin/env bb

(require '[clojure.pprint :as pp])
(import '[java.io File]
        '[java.time Duration])

(defn summarize []
  (let [timing-for-prefix (fn [file-prefix]
                            (->> (file-seq (File. "target/test-results"))
                                 (filter (fn [^File f]
                                           (and (.isFile f)
                                                (.startsWith (.getName f) file-prefix))))
                                 (map (comp read-string slurp))
                                 ;; TODO throw on overlapping keys
                                 (apply merge)))
        ns-timing (timing-for-prefix "ns-timing")
        sorted-ns-timing (sort-by (comp :elapsed-ns val) > ns-timing)
        var-timing (timing-for-prefix "var-timing")
        sorted-var-timing (sort-by (comp :elapsed-ns val) > var-timing)
        humanize-ns (fn [ns]
                      (str (Duration/ofNanos ns)))
        humanize (fn [ts]
                   (mapv (fn [[k {:keys [elapsed-ns] :as t}]]
                           (assert (number? elapsed-ns) (pr-str t))
                           [k (assoc t :ISO-8601 (humanize-ns elapsed-ns))])
                         ts))]
    (when-some [expected (let [f (File. "dev-resources/ctia_test_timings.edn")]
                           (when (.exists f)
                             (-> f
                                 slurp
                                 read-string)))]
      (println (str "Expected test duration: "
                    (/ (apply + (map :elapsed-ns (vals expected)))
                       1e9)
                    " seconds")))
    (println (str "Actual test duration: "
                  (/ (apply + (map :elapsed-ns (vals ns-timing)))
                     1e9)
                  " seconds"))
    (println "\nTest namespace summary (slowest to fastest):")
    (pp/pprint sorted-ns-timing)
    (println "\nTest var summary (slowest to fastest):")
    (pp/pprint sorted-var-timing)
    (-> (File. "target/test-results") .mkdirs)
    (spit "target/test-results/all-test-var-timings.edn" var-timing)
    (spit "target/test-results/all-test-timings.edn" ns-timing)))

(summarize)
