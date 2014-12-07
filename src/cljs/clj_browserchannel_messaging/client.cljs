(ns clj-browserchannel-messaging.client
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require
    [cljs.reader :as reader]
    [cljs.core.async :refer [pub sub chan <! put!]]
    goog.net.BrowserChannel
    [goog.events :as events]))

(defonce browser-channel (goog.net.BrowserChannel.))

(defonce incoming-messages (chan))
(defonce incoming-messages-pub (pub incoming-messages :topic))
(defonce outgoing-messages (chan))

(defn encode-message
  "encodes a message composed of a topic and body into a format that can be
   sent via browserchannel. topic should be a keyword while body can be
   anything. returns nil if the message could not be encoded."
  [{:keys [topic body] :as msg}]
  (if-let [topic (name topic)]
    (clj->js {"topic" topic
              "body"  (pr-str body)})))

(defn decode-message
  "decodes a message received via browserchannel into a map composed of a
   topic and body. returns nil if the message could not be decoded."
  [msg]
  (let [msg   (js->clj msg)
        topic (keyword (get msg "topic"))
        body  (get msg "body")]
    (if topic
      {:topic topic
       :body  (reader/read-string body)})))

(defn send
  "sends a browserchannel message to the server asynchronously."
  [topic body]
  (put! outgoing-messages {:topic topic :body body}))

(defn message-handler
  "listens for incoming browserchannel messages with the specified topic.
   executes the passed handler function when any are received. handler should
   be a function which accepts the received decoded message.
   note that the handler is executed asynchronously"
  [topic handler]
  (let [incoming-topic-messages (chan)]
    (sub incoming-messages-pub topic incoming-topic-messages)
    (go-loop []
      (when-let [msg (<! incoming-topic-messages)]
        (handler msg)
        (recur)))))

(defn- handle-outgoing [channel on-send]
  (go-loop []
    (when-let [msg (<! outgoing-messages)]
      (when-let [encoded (encode-message msg)]
        (if on-send (on-send msg))
        (.sendMap channel encoded))
      (recur))))

(defn- handle-incoming [channel msg on-receive]
  (when-let [decoded (decode-message msg)]
    (if on-receive (on-receive decoded))
    (put! incoming-messages decoded)))

; see: http://docs.closure-library.googlecode.com/git/local_closure_goog_net_browserchannel.js.source.html#line521
(def bch-error-enum-to-keyword
  {0  :ok
   2  :request-failed
   4  :logged-out
   5  :no-data
   6  :unknown-session-id
   7  :stop
   8  :network
   9  :blocked
   10 :bad-data
   11 :bad-response
   12 :active-x-blocked})

(defn- bch-error-enum->keyword [error-code]
  (or (get bch-error-enum-to-keyword error-code)
      :unknown))

(defn- handler [{:keys [on-open on-send on-receive on-close on-error]}]
  (let [h (goog.net.BrowserChannel.Handler.)]
    (set! (.-channelOpened h)
          (fn [channel]
            (if on-open (on-open))
            (handle-outgoing channel on-send)))
    (set! (.-channelHandleArray h)
          (fn [channel msg]
            (handle-incoming channel msg on-receive)))
    (set! (.-channelClosed h)
          (fn [channel pending undelivered]
            (if on-close (on-close pending undelivered))))
    (set! (.-channelError h)
          (fn [channel error]
            (if on-error (on-error (bch-error-enum->keyword error)))))
    h))

(defn- set-debug-logger! [level]
  (if-let [logger (-> browser-channel .getChannelDebug .getLogger)]
    (.setLevel logger level)))

(defn init!
  "sets up browserchannel for use, creating a handler with the specified
   properties. this function should be called once on page load.

   properties:

   :base - the base URL on which the server's browserchannel routes are
           located at. default is '/browserchannel'

   callbacks:

   :on-open
   occurs when a browserchannel session with the server is established

   :on-close
   occurs when the browserchannel session is closed (e.g. terminated by the
   server due to error, timeout, etc).
   receives 2 arguments: array of pending messages that may or may not
   have been sent to the server, and an array of undelivered messages that
   have definitely not been delivered to the server. note that these
   arguments will both be javascript arrays containing
   goog.net.BrowserChannel.QueuedMap objects.

   :on-error
   occurs when an error occurred on the browserchannel. receives 1 argument:
   a keyword indicating the type of error

   :on-send
   raised whenever a message is sent via the send function. receives 1
   argument: the message that is to be sent. this is probably only useful for
   debugging/logging purposes. note that this event is only raised for messages
   which can be encoded by encode-message

   :on-receive
   occurs whenever a browserchannel message is received from the server.
   receives 1 argument: the message that was received. note that this event is
   only raised for messages which can be decoded by decode-message. also note
   that this event is raised for all messages received, regardless of any
   listeners created via message-handler."
  [& [{:keys [base] :as opts}]]
  (let [base (or base "/browserchannel")]
    (events/listen
      js/window "unload"
      (fn []
        (.disconnect browser-channel)
        (events/removeAll)))
    (set-debug-logger! goog.debug.Logger.Level.OFF)
    (.setHandler browser-channel (handler opts))
    (.connect browser-channel
              (str base "/test")
              (str base "/bind"))))
