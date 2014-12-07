(ns clj-browserchannel-messaging.server
  (:refer-clojure :exclude [send])
  (:require [clojure.edn :as edn]
            [clojure.core.async :refer [chan pub sub <! put! go-loop]]
            [net.thegeez.browserchannel :as browserchannel]))

(defonce incoming-messages (chan))
(defonce incoming-messages-pub (pub incoming-messages :topic))

(defn encode-message
  "encodes a message made up of a topic and body into a format that can be sent
   via browserchannel to a client. topic should be a keyword, while body can be
   anything. returns nil if the message could not be encoded."
  [topic body]
  (if-let [topic (name topic)]
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
       :body  (edn/read-string body)})))

(defn send
  "sends a browserchannel message to a client identified by the given
   browserchannel session id. topic should be a keyword, while body can be
   anything. returns nil if the message was not sent."
  [browserchannel-session-id topic body]
  (if-let [encoded (encode-message topic body)]
    (browserchannel/send-map browserchannel-session-id encoded)))

(defn message-handler
  "listens for incoming browserchannel messages with the specified topic.
   executes the passed handler function when any are received. handler should
   be a function which accepts the received decoded message. the decoded
   message will contain the browserchannel session id of the client that
   sent the message under :browserchannel-session-id.
   note that the handler is executed asynchronously."
  [topic handler]
  (let [incoming-topic-messages (chan)]
    (sub incoming-messages-pub topic incoming-topic-messages)
    (go-loop []
      (when-let [msg (<! incoming-topic-messages)]
        (handler msg)
        (recur)))))

(defn- handle-session [browserchannel-session-id req {:keys [on-open on-close on-receive]}]
  (if on-open (on-open browserchannel-session-id req))
  (browserchannel/add-listener
    browserchannel-session-id
    :close
    (fn [request reason]
      (if on-close (on-close browserchannel-session-id request reason))))
  (browserchannel/add-listener
    browserchannel-session-id
    :map
    (fn [request m]
      (if-let [decoded (decode-message m)]
        (let [msg (assoc decoded :browserchannel-session-id browserchannel-session-id)]
          (if on-receive (on-receive browserchannel-session-id request msg))
          (put! incoming-messages msg))))))

(defn wrap-browserchannel
  "Middleware to handle server-side browserchannel session and message
   processing.

   You can specify the same set of options that
   net.thegeez.browserchannel/wrap-browserchannel accepts, except for
   :on-session (which will be overridden even if you do try to pass it).
   See net.thegeez.browserchannel/default-options for more info.

   Note that if :base is not specified, the default is '/browserchannel'
   (this differs from net.thegeez.browserchannel/wrap-browserchannel).

   In addition, you can pass event handler functions. Note that the return
   value for all of these handlers is not used.

   :on-open
   Occurs when a new browserchannel session has been established. Receives 2
   arguments: the browserchannel session id and the request map (for the
   request that resulted in the browserchannel session being established) as
   arguments.

   :on-receive
   Occurs when a new message is received from a client. Receives 3 arguments:
   the browsercannel session id, the request map (for the client request that
   the message was sent with), and the actual decoded message as arguments.
   the browserchannel session id of the client that sent the message is
   automatically added to the message under :browserchannel-session-id.

   :on-close
   Occurs when the browserchannel session is closed. Receives 3 arguments: the
   browserchannel session id, the request map (for the request sent by the
   client causing the session to be closed, if any), and a string containing
   the reason for the session close. Note that, this may or may not be
   initiated directly by the client. The request argument will be nil if the
   session is being closed as part of some server-side operation (e.g.
   browserchannel session timeout)."
  [handler & [opts]]
  (-> handler
      (browserchannel/wrap-browserchannel
        (assoc
          opts
          :base (or (:base opts) "/browserchannel")
          :on-session
          (fn [browserchannel-session-id request]
            (handle-session
              browserchannel-session-id request
              (select-keys opts [:on-open :on-close :on-receive])))))))
