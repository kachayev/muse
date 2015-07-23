(ns muse.runner
  (:require [clojure.string :as str]
            [cljs.test :as test]
            [muse.core-spec]
            [muse.cats-spec]))

(enable-console-print!)

(defn main []
  (test/run-tests (test/empty-env)
                  'muse.core-spec
                  'muse.cats-spec))

(set! *main-cli-fn* main)
