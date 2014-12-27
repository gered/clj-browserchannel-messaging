(ns clj-browserchannel-messaging.server
  (:refer-clojure :exclude [send])
  (:require [clojure.edn :as edn]
            [clojure.core.async :refer [chan pub sub <! put! go-loop]]
            [net.thegeez.browserchannel :as browserchannel]
            [clj-browserchannel-messaging.utils :refer [run-middleware get-handlers encode-message decode-message]]))

(defonce ^:private handler-middleware (atom nil))

(defonce incoming-messages (chan))
(defonce incoming-messages-pub (pub incoming-messages :topic))

(defn send
  "sends a browserchannel message to a client identified by the given
   browserchannel session id. topic should be a keyword, while body can be
   anything. returns nil if the message was not sent."
  [browserchannel-session-id topic body]
  (let [msg {:topic topic
             :body  body}]
    (run-middleware
      (:on-send @handler-middleware)
      (fn [browserchannel-session-id msg]
        (if-let [encoded (encode-message msg)]
          (browserchannel/send-map browserchannel-session-id encoded)))
      browserchannel-session-id msg)))

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

(defn- handle-session [browserchannel-session-id req]
  (run-middleware
    (:on-open @handler-middleware)
    (fn [browserchannel-session-id request]
      ; no-op
      )
    browserchannel-session-id req)

  (browserchannel/add-listener
    browserchannel-session-id
    :close
    (fn [request reason]
      (run-middleware
        (:on-close @handler-middleware)
        (fn [browserchannel-session-id request reason]
          ; no-op
          )
        browserchannel-session-id request reason)))

  (browserchannel/add-listener
    browserchannel-session-id
    :map
    (fn [request m]
      (if-let [decoded (decode-message m)]
        (let [msg (assoc decoded :browserchannel-session-id browserchannel-session-id)]
          (run-middleware
            (:on-receive @handler-middleware)
            (fn [browserchannel-session-id request msg]
              (if msg
                (put! incoming-messages msg)))
            browserchannel-session-id request msg))))))

(defn wrap-browserchannel
  "Middleware to handle server-side browserchannel session and message
   processing.

   You can specify the same set of options that
   net.thegeez.browserchannel/wrap-browserchannel accepts, except for
   :on-session (which will be overridden even if you do try to pass it).

   See net.thegeez.browserchannel/default-options for more info on available
   options and what the defaults are. Some defaults are different when
   using this middleware:

   :base \"/browserchannel\"
   :keep-alive-interval 20
   :session-timeout-interval 30"
  [handler & [opts]]
  (let [opts (merge
               {:base                     "/browserchannel"
                :keep-alive-interval      20
                :session-timeout-interval 30}
               opts)]
    (-> handler
        (browserchannel/wrap-browserchannel
          (assoc
            opts
            :on-session
            (fn [browserchannel-session-id request]
              (handle-session browserchannel-session-id request)))))))

(defn init!
  "Sets up browserchannel for server-side use. This function should be called
   once during application startup.

   :middleware - a vector of middleware maps.

   Middleware is optional. If specificed, each middleware is provided as a
   'middleware map'. This is a map where functions are specified for one
   or more of :on-open, :on-close, :on-send, :on-receive. A middleware map
   need not provide a function for any events it is not doing any processing
   for.

   Each middleware function looks like a Ring middleware function. They
   are passed a handler and should return a function which performs the
   actual middleware processing and calls handler to continue on down
   the chain of middleware. e.g.

   {:on-send (fn [handler]
               (fn [session-id request {:keys [topic body] :as msg]
                 ; do something here with the message to be sent
                 (handler session-id request msg)))}

   Remember that middleware is run in the reverse order that they appear
   in the vector you pass in.

   Middleware function descriptions:

   :on-open
   Occurs when a new browserchannel session has been established. Receives 2
   arguments: the browserchannel session id and the request map (for the
   request that resulted in the browserchannel session being established) as
   arguments.

   :on-receive
   Occurs when a new message is received from a client. Receives 3 arguments:
   the browserchannel session id, the request map (for the client request that
   the message was sent with), and the actual decoded message as arguments.
   the browserchannel session id of the client that sent the message is
   automatically added to the message under :browserchannel-session-id.

   :on-send
   Occurs when a message is to be sent to a client. Receives 2 arguments:
   the browserchannel session id and the actual message to be sent.

   :on-close
   Occurs when the browserchannel session is closed. Receives 3 arguments: the
   browserchannel session id, the request map (for the request sent by the
   client causing the session to be closed, if any), and a string containing
   the reason for the session close. Note that, this may or may not be
   initiated directly by the client. The request argument will be nil if the
   session is being closed as part of some server-side operation (e.g.
   browserchannel session timeout)."
  [& {:keys [middleware]}]
  (reset!
    handler-middleware
    {:on-open    (get-handlers middleware :on-open)
     :on-close   (get-handlers middleware :on-close)
     :on-receive (get-handlers middleware :on-receive)
     :on-send    (get-handlers middleware :on-send)}))