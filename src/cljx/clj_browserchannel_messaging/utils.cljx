(ns clj-browserchannel-messaging.utils
  (:require
    #+clj [clojure.edn :as edn]
    #+cljs [cljs.reader :as reader]))

#+clj
(defn decode-edn [s]
  (edn/read-string s))

#+cljs
(defn decode-edn [s]
  (reader/read-string s))

(defn encode-message
  "encodes a message made up of a topic and body into a format that can be sent
   via browserchannel. topic should be a keyword, while body can be
   any Clojure data structure. returns nil if the message could not be encoded."
  [{:keys [topic body] :as msg}]
  (if-let [topic (if topic (name topic))]
    {"topic" topic
     "body"  (pr-str body)}))

(defn decode-message
  "decodes a message received via browserchannel into a map composed of a
   topic and body. returns nil if the message could not be decoded."
  [msg]
  (let [topic (get msg "topic")
        body  (get msg "body")]
    (if topic
      {:topic (keyword topic)
       :body  (decode-edn body)})))

(defn run-middleware [middleware final-handler & args]
  (let [wrap    (fn [handler [f & more]]
                  (if f
                    (recur (f handler) more)
                    handler))
        handler (wrap final-handler middleware)]
    (apply handler args)))

(defn get-handlers [middleware k]
  (->> middleware (map k) (remove nil?) (doall)))