(defproject clj-browserchannel-messaging "0.0.4"
  :description  "Tools for quickly using BrowserChannel for bi-directional client-server messaging."
  :url          "https://github.com/gered/clj-browserchannel-messaging"
  :license      {:name "MIT License"
                 :url "http://opensource.org/licenses/MIT"}

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-2371" :scope "provided"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [net.thegeez/clj-browserchannel-server "0.1.0"]
                 [prismatic/dommy "1.0.0"]]

  :source-paths   ["target/generated/src/clj" "src/clj"]
  :resource-paths ["target/generated/src/cljs" "src/cljs"]

  :profiles {:dev {:plugins [[com.keminglabs/cljx "0.5.0"]]}}

  :cljx {:builds [{:source-paths ["src/cljx"]
                   :output-path "target/generated/src/clj"
                   :rules :clj}
                  {:source-paths ["src/cljx"]
                   :output-path "target/generated/src/cljs"
                   :rules :cljs}]}

  :prep-tasks [["cljx" "once"]])
