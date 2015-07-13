(ns muse.test.runner
  (:require [clojure.string :as str]
            [cljs.test :as test]
            [muse.test.core :as core-spec]
            [muse.test.core :as cats-spec]))

(enable-console-print!)

(defn main []
  (test/run-tests (test/empty-env)
                  'core-spec
                  'cats-spec))

(set! *main-cli-fn* main)
