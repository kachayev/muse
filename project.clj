(defproject muse2/muse "0.4.5-alpha3"
  :description "A Clojure library that simplifies access to remote data"
  :url "https://github.com/kachayev/muse"
  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/MIT"
            :distribution :repo}
  :global-vars {*warn-on-reflection* true}
  :dependencies [[manifold "0.1.6"]]
  :test-paths ["test"]
  :cljsbuild {:test-commands {"test" ["node" "output/tests.js"]}
              :builds [{:id "test"
                        :source-paths ["src" "test"]
                        :notify-command ["node" "output/tests.js"]
                        :compiler {:output-to "output/tests.js"
                                   :output-dir "output"
                                   :source-map true
                                   :static-fns true
                                   :cache-analysis false
                                   :main muse.runner
                                   :optimizations :none
                                   :target :nodejs
                                   :pretty-print true}}]}
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.9.0"]
                                  [org.clojure/core.async "0.4.474"]
                                  [cats "0.4.0"]
                                  [org.clojure/clojurescript "1.9.946"]]
                   :source-paths ["src" "dev"]
                   :repl-options {:init-ns user}
                   :plugins [[lein-cljsbuild "1.1.4"]
                             [lein-jammin "0.1.1"]]}}
  :repositories [["clojars" {:sign-releases false}]])
