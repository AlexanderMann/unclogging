(ns alexandermann.unclogging-test
  "Herein, wherever we are setting up tests to look for output, we
  use FATAL level. This is not because things are _actually_ fatal
  but rather that when comparing logging levels, and looking to
  avoid filtering due to, we wish to use the largest such level."
  (:require [alexandermann.unclogging :as u]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.string :as string]
            [clojure.test :refer :all]
            [clojure.test.check.generators :as gen]
            [taoensso.timbre :as log])
  (:import [io.netty.util.internal.logging
            InternalLoggerFactory]
           [java.util.logging
            Logger]))

(def logs-atom (atom []))

(defn appender
  [{:keys [vargs ?ns-str] :as logging-data}]
  (swap! logs-atom conj {:vargs   vargs
                         :?ns-str ?ns-str}))

(def filter-str (str *ns*))

(defn logs
  []
  (->> @logs-atom
       (filter (fn [{:keys [?ns-str]}]
                 (= ?ns-str filter-str)))
       (mapv :vargs)))

(defn start-test
  []
  (log/merge-config! {:appenders {:hijack-appender {:enabled? true
                                                    :fn appender}}})
  (reset! logs-atom []))

(use-fixtures :each (fn [f]
                      (start-test)
                      (f)
                      (log/merge-config! {:appenders {:hijack-appender {:enabled? false}}
                                          :middleware []})))

(deftest basic-1
  (testing "regex remove AKIA keys"
    (u/merge-config! (u/prevent-hazard {:level :info}
                                       (fn [v]
                                         (and (string? v)
                                              (re-find #"AKIA\w*" v)))))
    (log/fatal "hello world AKIA1234")
    (is (not-empty (logs)))
    (is (-> (logs)
            pr-str
            (string/includes? "AKIA")
            not))
    (is (-> (logs)
            pr-str
            (string/includes? u/default-redacted-message)))))

(deftest basic-2
  (testing "regex remove single hazardous element"
    (u/merge-config! (u/prevent-hazard {:level :info}
                                       (fn [v]
                                         (and (string? v)
                                              (re-find #"AKIA\w*" v)))))
    (log/fatal "hello world AKIA1234" "another string")
    (is (-> (logs)
            pr-str
            (string/includes? "another string")))))

(deftest basic-3
  (testing "finds and replaces values deeply nested"
    (u/merge-config! (u/prevent-hazard {:level :info}
                                       :hazard
                                       (fn [v _]
                                         (-> v
                                             (dissoc :hazard)
                                             (assoc :hazard "special-redacted")))))
    (log/fatal #{[{:hello {"a" {"2" {:there {"1" {:this :is :a 123 :hazard "danger"}}}}}}]})
    (is (->> (logs)
             flatten
             (some set?)))
    (is (-> (logs)
            pr-str
            (string/includes? "special-redacted")))))

(deftest jul-to-timbre
  (testing "Using java.util.logging redirects through Timbre"
    (u/merge-config! {:level :info})
    (let [test-message "are you seeing this timbre?"]
      (.severe (Logger/getLogger filter-str)
               test-message)
      (is (-> (logs)
              pr-str
              (string/includes? test-message))))))

(deftest netty-to-timbre
  (testing "Using java.util.logging redirects through Timbre"
    (u/merge-config! {:level :info})
    (let [test-message "are you seeing this timbre?"]
      (.error (InternalLoggerFactory/getInstance ^String filter-str)
              test-message)
      (is (-> (logs)
              pr-str
              (string/includes? test-message))))))

(defn logtest
  [string-to-print string-to-prevent]
  (start-test)
  (u/merge-config! (u/prevent-hazard {:level :info}
                                     (fn [v]
                                       (and (string? v)
                                            (string/includes? v string-to-prevent)))))
  (log/fatal string-to-print string-to-prevent)
  (mapv (partial zipmap [:printed :redacted])
        (logs)))

(s/fdef
  logtest
  :args (s/and (s/cat :prints string?
                      :redacts string?)
               (fn [{s0 :prints s1 :redacts}]
                 (not (string/includes? s0 s1))))
  :ret vector?
  :fn (fn [{{prints  :prints
             redacts :redacts} :args
            logs :ret}]
        (and (->> logs
                  (map :printed)
                  (every? (partial = prints)))
             (->> logs
                  (map :redacted)
                  (every? (partial = u/default-redacted-message))))))

(deftest gen-spec-testing
  (testing "regex remove"
    (is (-> `logtest
            stest/check
            stest/summarize-results
            (dissoc :total)
            (dissoc :check-passed)
            empty?))))
