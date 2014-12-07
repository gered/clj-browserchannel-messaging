# clj-browserchannel-messaging

Helper utilities and Ring middleware for using [BrowserChannel](http://thegeez.net/2012/04/03/why_browserchannel.html)
as a real-time client-server messaging protocol in your Clojure/ClojureScript web apps.

**Note: This library is currently "beta status." As such some of this setup may be a bit overly complex
        and the documentation a bit rough.**

## Usage

### Dependencies

None of the current versions of Clojure BrowserChannel libraries we need (including this library at the moment)
are available on Clojars.

You will need to install **clj-browserchannel-server** and **clj-browserchannel-jetty-adapter** manually via 
`lein install`. This library depends on the versions of these libraries 
[located here](https://github.com/gered/clj-browserchannel) currently.

Then you will need to locally install this library via `lein install` as well.

### `project.clj`

Add these to your dependencies.

```clojure
[net.thegeez/clj-browserchannel-jetty-adapter "0.0.5"]
[clj-browserchannel-messaging "0.0.1"]
```

## Message Format

This library wraps messages sent/received by client and server in a lightly structured format:

```clojure
{:topic <<keyword describing the contents of the message>>
 :body  <<any clojure data structure or value>>}
```

The topic is kind of like a message type or category. Similar types of messages communicating the same types of
information should share the same message topic.

## Server-side Setup

### Jetty Async

Both clj-browserchannel and this library _require_ use of an async HTTP server. The 
**clj-browserchannel-jetty-adapter** library you installed previously contains the Jetty Async adapter that can be
used by **clj-browserchannel-server**.

```clojure
(ns yourapp
  (:gen-class)
  (:require [net.thegeez.jetty-async-adapter :refer [run-jetty-async]]))

(defn -main [& args]
  (run-jetty-async handler {:port 8080 :join? false}))
```

### Ring Middleware

You need to add the `clj-browserchannel-messaging.server/wrap-browserchannel` middleware to your Ring app handler.

Note that currently, this library does not play nice with Ring's `wrap-anti-forgery` middleware. If you are using
lib-noir or ring-defaults, then this middleware is enabled by default when using the `site-defaults` settings
from ring-defaults. You will need to disable this by setting, e.g.:

```clojure
(assoc-in site-defaults [:security :anti-forgery] false)
```

Otherwise you will get 403 access denied responses when sending BrowserChannel messages from client to server. This
will be addressed properly in this library in a future release.

See the doc comments for `clj-browserchannel-messaging.server/wrap-browserchannel` for more detailed descriptions
of the options you can pass to this middleware. Example usage:

```clojure
(wrap-browserchannel
  {:on-open    (fn [browserchannel-session-id request]
                 (println "browserchannel session opened with client:" browserchannel-session-id))
   :on-close   (fn [browserchannel-session-id request reason]
                 (println "browserchannel session closed for client:" browserchannel-session-id ", reason:" reason))
   :on-receive (fn [browserchannel-session-id request message]
                 (println "received message from client" browserchannel-session-id ":" message))})
```

### Sending and Receiving Messages

The `:on-receive` handler passed to `wrap-browserchannel` will be invoked when any message is received from any
BrowserChannel client. It's basically your main event handler.

However, you can also use `clj-browserchannel-messaging.server/message-handler` anywhere in your application code
to listen for specific types of messages and provide a separate handler function to run when they are received.

```clojure
(message-handler
  :foobar
  (fn [msg]
    (println "received :foobar message:" msg)))
```

To send a message to a client, you must have the "BrowserChannel session id" associated with that client. This is
first generated and passed to the `:on-open` event and becomes invalid after `:on-close`. All messages received
from the client will automatically have the BrowserChannel session id of the sending client included in the message
under the `:browserchannel-session-id` key.

Use the `clj-browserchannel-messaging.server/send` function to send a message to a client. If the message could not
be sent for any reason, a nil value is returned.

#### BrowserChannel Sessions

Just a quick note about BrowserChannel Sessions. They are essentially tied to the length of time that a user has
a single page of the web app open in their browser, so obviously it goes without saying that BrowserChannel should be
used by Single Page Apps or other applications that keep the user on a single page for a lengthy time and have heavy 
client-side scripting driving the UI.

When the page is first loaded, the BrowserChannel setup occurs and a session id is generated (`:on-open`). The user
then continues using the app in their browser and if they leave the page or close their browser, the BrowserChannel
connection and session is closed (`:on-close`). If the user refreshes the page with their browser, the existing
session is closed and a new one is created when the page reloads.

Also, BrowserChannel sessions can expire after a period of inactivity (the `:on-close` reason will be "Timed out").

## Client-side Setup

### Page Load

When the page loads, in your ClojureScript code you should call `clj-browserchannel-messaging.client/init!`. This
function takes some options, the most important of which are callbacks (similar in idea to the callbacks you specify 
to the `wrap-browserchannel` middleware on the server-side).

See the doc comments for `clj-browserchannel-messaging.client/init!` for more detailed descriptions of the options
available. Example usage:

```clojure
(init!
  {:on-open (fn []
              (println "on-open"))
   :on-send (fn [msg]
              (println "sending" msg))
   :on-receive (fn [msg]
                 (println "receiving" msg))
   :on-close (fn [pending undelivered]
               (println "closed" pending undelivered))
   :on-error (fn [error]
               (println "error:" error))})
```

On the client-side, you'll probably care most about `:on-receive` and possibly `:on-close` and `:on-error` to help
gracefully deal with connection loss / server timeouts.

### Sending and Receiving Messages

Note that, unlike on the server, the client does not deal with any "BrowserChannel session ids." That is because
it only sends and receives messages to/from the server, not directly to other clients.

Like on the server, the `:on-receive` handler will be invoked when any message is received from the server.

You can also use `clj-browserchannel-messaging.client/message-handler` which works in exactly the same manner as the
server-side version mentioned above.

To send a message to the server, use the `clj-browserchannel-messaging.client/send` function. If the message could
not be sent for any reason, a nil value is returned.

## License

Copyright © 2014 Gered King

Distributed under the the MIT License (the same as clj-browserchannel). See LICENSE for more details.
