(defproject funcool/muse "0.4.0"
  :description "A Clojure library that simplifies access to remote data (db, cache, http services)"
  :url "https://github.com/funcool/muse"
  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/MIT"
            :distribution :repo}
  :global-vars {*warn-on-reflection* false}
  :dependencies []
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

  :profiles {:dev {:dependencies [[org.clojure/clojure "1.7.0"]
                                  [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                                  [funcool/cats "1.1.0-SNAPSHOT"]
                                  [funcool/promissum "0.3.1"]
                                  [funcool/promesa "0.5.1"]
                                  [org.clojure/clojurescript "0.0-3308"]]
                   :plugins [[lein-cljsbuild "1.0.6"]]}})
