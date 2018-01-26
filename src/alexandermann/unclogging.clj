(ns alexandermann.unclogging
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [taoensso.timbre :as log]
            [taoensso.timbre.appenders.3rd-party.logstash :as logstash])
  (:import [clojure.lang
            ArraySeq
            LazySeq]
           [io.netty.util.internal.logging
            InternalLoggerFactory
            Slf4JLoggerFactory]
           [java.net
            URI
            URISyntaxException]
           [java.util.logging Level
            Logger
            LogManager]
           [org.slf4j.bridge
            SLF4JBridgeHandler]))

(defn- deep-merge
  [& maps]
  (apply merge-with
         (fn [x y]
           (cond (map? y) (deep-merge x y)
                 (vector? y) (concat x y)
                 :else y))
         maps))

(defn- bridge-jul-to-timbre
  "java.util.Logging is more stubborn than other bridges
  to SLF4J and requires additional maintenance to get setup.

  Relevant links to this can be found:
  https://stackoverflow.com/a/9117188"
  []
  (.reset (LogManager/getLogManager))
  (SLF4JBridgeHandler/removeHandlersForRootLogger)
  (SLF4JBridgeHandler/install)
  (.setLevel (Logger/getLogger "global")
             Level/ALL))
(bridge-jul-to-timbre)

(defn- bridge-netty-to-timbre
  "In order to get Netty to behave and use SLF4J you have to hook the
  logger factories up to each other. This fn performs that operation.

  Relevant links to this can be found:
  https://gist.github.com/isopov/4368608#file-nettylogginghandlertest-java-L37-L48"
  []
  (InternalLoggerFactory/setDefaultFactory (Slf4JLoggerFactory.)))
(bridge-netty-to-timbre)

(def default-redacted-message ">>REDACTED<<")

(defn- repack
  [x]
  (into [] (take 50 x)))

(defn- ->non-lazy-print
  "Prevents an interesting case with appenders in Timbre AND
  prevents us from walking an infinite list.
  https://github.com/ptaoussanis/timbre/issues/237"
  [x]
  (cond
    (instance? ArraySeq x) (repack x)
    (instance? LazySeq x) (repack x)
    :else x))

(defn- strip-hazard
  [is-hazard? rm-hazard]
  (let [redacted-msg default-redacted-message
        clean-walk (fn inner-fn [v]
                     (if (try
                           (is-hazard? v)
                           (catch Exception _
                             false))
                       (try
                         (rm-hazard v redacted-msg)
                         (catch Exception _
                           redacted-msg))
                       (walk/walk inner-fn identity
                                  (->non-lazy-print v))))]
    (fn [{passed-data :vargs
          :as         payload}]
      (assoc payload
        :vargs (clean-walk passed-data)))))

(defn prevent-hazard
  "Sets up logging middleware which strips hazards from logging
  data.
  is-hazard? : takes a single value which identifies whether the
    data is hazardous.
  rm-hazard : optional fn which takes a value which passed is-hazard?
    and a message to use for redaction. Formats arguments to make the
    prettier in logs.
    Default is simply to replace anything matching is-hazard? with a
    redacted message."
  ([is-hazard?]
   (prevent-hazard {}
                   is-hazard?))
  ([config is-hazard?]
   (prevent-hazard config
                   is-hazard?
                   (fn [_ redact-msg]
                     redact-msg)))
  ([config is-hazard? rm-hazard]
   (deep-merge config
               {:middleware [(strip-hazard is-hazard?
                                           rm-hazard)]})))

(defn default-output-fn
  "Just like Timbre's default-output-fn, except this adds in the MDC."
  ([data]
   (default-output-fn nil data))
  ([opts {ctx :context
          :as data}]
   (-> (log/default-output-fn opts data)
       ;; \S+ timestamp
       ;; \S+ hostname
       ;; \S+ level
       ;; <-- injected context
       ;; [... rest
       (string/replace-first #"(\S+ \S+ \S+) \["
                             (str "$1 " ctx " [")))))

(def ^:private levels #{:trace
                        :debug
                        :info
                        :warn
                        :error
                        :fatal
                        :report})

(defn- authority-resolves?
  [{:keys [host port] :as conformed-spec}]
  (and (try
         (.getAuthority (URI. nil nil host port nil nil nil))
         (catch URISyntaxException _
           false))
       conformed-spec))

(s/def ::host (s/and string? (complement string/blank?)))
;; Zero is a valid port-number, considered part of the "system ports" range
;; https://tools.ietf.org/html/rfc6335
(s/def ::port (s/int-in 0 65536))
(s/def ::authority authority-resolves?)
(s/def ::host-port (s/and (s/keys :req-un [::host ::port])
                          ::authority))


(s/def ::level (into levels (map str levels)))
(s/def ::middleware (s/nilable (s/coll-of fn?)))
(s/def ::logstash (s/nilable ::host-port))
(s/def ::ansi-enabled (s/or :nil nil?
                            :bool boolean?))

(defn merge-config!
  [{logstash-host-port :logstash
    ansi-enabled :ansi-enabled
    level :level
    :as config}]
  (log/merge-config!
    (deep-merge
      config
      {:level (-> level
                  name
                  keyword)
       :output-fn (partial default-output-fn
                           (when-not ansi-enabled
                             {:stacktrace-fonts {}}))}
      (when-let [{host :host port :port} logstash-host-port]
        {:appenders {:logstash (logstash/logstash-appender host port {:flush? true})}}))))

(s/fdef
  merge-config!
  :args (s/cat :config (s/keys :req-un [::level]
                               :opt-un [::middleware
                                        ::logstash
                                        ::ansi-enabled]))
  :ret map?)
