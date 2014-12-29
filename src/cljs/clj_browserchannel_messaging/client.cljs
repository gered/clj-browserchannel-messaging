(ns clj-browserchannel-messaging.client
  (:require-macros
    [cljs.core.async.macros :refer [go-loop]])
  (:require
    [cljs.reader :as reader]
    [cljs.core.async :refer [pub sub chan <! put!]]
    goog.net.BrowserChannel
    goog.net.BrowserChannel.State
    [goog.events :as events]
    [dommy.core :refer-macros [sel1]]
    [clj-browserchannel-messaging.utils :refer [run-middleware get-handlers encode-message decode-message]]))

(defonce ^:private handler-middleware (atom nil))

(defonce ^:private connect-opts (atom {}))
(defonce browser-channel (goog.net.BrowserChannel.))

(defonce incoming-messages (chan))
(defonce incoming-messages-pub (pub incoming-messages :topic))

(defn send
  "sends a browserchannel message to the server asynchronously."
  [topic body]
  (let [msg {:topic topic
             :body  body}]
    (run-middleware
      (:on-send @handler-middleware)
      (fn [msg]
        (if-let [encoded (encode-message msg)]
          (.sendMap browser-channel (clj->js encoded))))
      msg)))

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

(defn- handle-incoming [channel msg]
  (when-let [decoded (decode-message (js->clj msg))]
    (run-middleware
      (:on-receive @handler-middleware)
      (fn [msg]
        (if msg
          (put! incoming-messages msg)))
      decoded)))

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

(defn- ->handler []
  (let [h (goog.net.BrowserChannel.Handler.)]
    (set! (.-channelOpened h)
          (fn [channel]
            (if-let [on-connect (:on-connect @connect-opts)]
              (on-connect))
            (run-middleware (:on-open @handler-middleware) (fn []))))
    (set! (.-channelHandleArray h)
          (fn [channel msg]
            (handle-incoming channel msg)))
    (set! (.-channelClosed h)
          (fn [channel pending undelivered]
            (if-let [on-disconnect (:on-disconnect @connect-opts)]
              (on-disconnect))
            (run-middleware
              (:on-close @handler-middleware)
              (fn [pending undelivered]
                ; no-op
                )
              pending undelivered)))
    (set! (.-channelError h)
          (fn [channel error]
            (run-middleware
              (:on-error @handler-middleware)
              (fn [error]
                ; no-op
                )
              (bch-error-enum->keyword error))))
    h))

(defn- set-debug-logger! [level]
  (if-let [logger (-> browser-channel .getChannelDebug .getLogger)]
    (.setLevel logger level)))

(defn- register-middleware! [middleware]
  (reset!
    handler-middleware
    {:on-open    (get-handlers middleware :on-open)
     :on-close   (get-handlers middleware :on-close)
     :on-error   (get-handlers middleware :on-error)
     :on-receive (get-handlers middleware :on-receive)
     :on-send    (get-handlers middleware :on-send)}))

(defn- get-anti-forgery-token []
  (if-let [tag (sel1 "meta[name='anti-forgery-token']")]
    (.-content tag)))

(defn connected?
  "Returns true if a browserchannel session is currently established with
   the server."
  []
  (= (.getState browser-channel) goog.net.BrowserChannel.State/OPENED))

(defn connect!
  "Initiates a browserchannel connection with the server. Note that
   init! calls this function, so you only need to use this if the
   connection closes for any reason and needs to be reopened.

   When a connection is established, the on-connect callback provided to
   init! will be invoked (if it was provided). This will occur before
   any middleware(s) are processed."
  []
  (let [state (.getState browser-channel)]
    (if (or (= state goog.net.BrowserChannel.State/CLOSED)
            (= state goog.net.BrowserChannel.State/INIT))
      (.connect
        browser-channel
        (:test-path @connect-opts)
        (:channel-path @connect-opts)))))

(defn disconnect!
  "Closes the browserchannel connection with the server.

   When the disconnection finishes, the on-disconnect callback provided
   to init! will be invoked (if it was provided). This will occur before
   any middleware(s) are processed."
  []
  (if (not= (.getState browser-channel) goog.net.BrowserChannel.State/CLOSED)
    (.disconnect browser-channel)))

(defn init!
  "Sets up browserchannel for use, creating a handler with the specified
   properties. this function should be called once on page load.

   :base - the base URL on which the server's browserchannel routes are
           located at. default if not specified is '/browserchannel'

   :on-connect - optional function that will be called once a
                 browserchannel session is established with the server.
                 this function receives no arguments. it will be invoked
                 before any middleware(s) are processed.

   :on-disconnect - optional function that will be called once the
                    browserchannel session is closed for any reason. this
                    function receives no arguments. it will be invoked
                    before any middleware(s) are processed.

   :middleware - a vector of middleware maps.

   Middleware is optional. If specificed, each middleware is provided as a
   'middleware map'. This is a map where functions are specified for one
   or more of :on-open, :on-close, :on-error, :on-send, :on-receive. A
   middleware map need not provide a function for any events it is
   not doing any processing for.

   Each middleware function looks like a Ring middleware function. They
   are passed a handler and should return a function which performs the
   actual middleware processing and calls handler to continue on down
   the chain of middleware. e.g.

   {:on-send (fn [handler]
               (fn [{:keys [topic body] :as msg]
                 ; do something here with the message to be sent
                 (handler msg)))}

   Remember that middleware is run in the reverse order that they appear
   in the vector you pass in.

   Middleware function descriptions:

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
   argument: the message that is to be sent.

   :on-receive
   occurs whenever a browserchannel message is received from the server.
   receives 1 argument: the message that was received. note that this event is
   only raised for messages which can be decoded by decode-message. also note
   that this event is raised for all messages received, regardless of any
   listeners created via message-handler.

   CSRF Anti-Forgery Tokens

   If the page contains a <meta> tag with a name of 'anti-forgery-token',
   the value of this meta tag will be automatically included under the
   X-CSRF-Token HTTP header on all BrowserChannel POST requests to work with
   any CSRF protection your web app uses (e.g. Ring's wrap-anti-forgery
   middleware)."
  [& {:keys [base middleware on-connect on-disconnect]}]
  (let [base               (or base "/browserchannel")
        anti-forgery-token (get-anti-forgery-token)]
    (register-middleware! middleware)
    (events/listen
      js/window "unload"
      (fn []
        (disconnect!)
        (events/removeAll)))
    (set-debug-logger! goog.debug.Logger.Level.OFF)
    ; this seems to help prevent premature session timeouts from occuring (vs the default of 3)
    (set! goog.net.BrowserChannel/BACK_CHANNEL_MAX_RETRIES 20)
    (.setHandler browser-channel (->handler))
    (if anti-forgery-token
      (.setExtraHeaders browser-channel (js-obj "X-CSRF-Token" anti-forgery-token)))
    (reset! connect-opts {:test-path     (str base "/test")
                          :channel-path  (str base "/bind")
                          :on-connect    on-connect
                          :on-disconnect on-disconnect})
    (connect!)))
