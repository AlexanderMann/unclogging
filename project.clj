(defproject alexandermann/unclogging "0.1.0-SNAPSHOT"
  :description "A repo designed to help you unclog your Clojure logging woes"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [com.taoensso/timbre "4.10.0"]
                 [com.fzakaria/slf4j-timbre "0.3.8"]
                 [org.slf4j/slf4j-api "1.7.25"]
                 [org.slf4j/log4j-over-slf4j "1.7.25"]
                 [org.slf4j/jul-to-slf4j "1.7.25"]
                 [org.slf4j/jcl-over-slf4j "1.7.25"]
                 [io.netty/netty-common "4.1.20.Final"]]

  :min-lein-version "2.0.0"
  :jvm-opts ["-Xmx2g" "-XX:+UseConcMarkSweepGC"]
  :monkeypatch-clojure-test false

  :aliases {"cci-test" ["with-profile" "dev" "run" "-m" "circleci.test/dir" :project/test-paths]
            "cci-retest" ["with-profile" "dev" "run" "-m" "circleci.test.retest"]}

  :profiles {:dev {:dependencies   [[org.clojure/test.check "0.9.0"]
                                    [pjstadig/humane-test-output "0.8.1"]
                                    [circleci/circleci.test "0.4.0"]]
                   :injections     [(require 'pjstadig.humane-test-output)
                                    (pjstadig.humane-test-output/activate!)]
                   :resource-paths ["test/resources" "dev-resources"]}})
