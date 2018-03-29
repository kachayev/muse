(ns user
  (:require [muse.deferred :as muse]
            [muse.protocol :as proto]
            [muse.pull :as pull]
            [manifold.deferred :as d])
  (:refer-clojure :exclude (run!)))
