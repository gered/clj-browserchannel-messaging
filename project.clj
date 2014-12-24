(defproject clj-browserchannel-messaging "0.0.2"
  :description  "Tools for quickly using BrowserChannel for bi-directional client-server messaging."
  :url          "https://github.com/gered/clj-browserchannel-messaging"
  :license      {:name "MIT License"
                 :url "http://opensource.org/licenses/MIT"}

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-2371" :scope "provided"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [net.thegeez/clj-browserchannel-server "0.1.0"]]

  :source-paths   ["src/clj"]
  :resource-paths ["src/cljs"])
