## Muse

*Muse* is a Clojure library that works hard to make your relationship with remote data simple & enjoyable. We believe that concurrent code can be elegant and efficient at the same time.

Often times your business logic relies on remote data that you need to fetch from different sources: databases, caches, web services or 3rd party APIs, and you can't mess things up. *Muse* helps you to keep your business logic clear of low-level details while performing efficiently:

* batch multiple requests (if batching protocol is defined for the data source)
* request data from multiple data sources concurrently
* cache previous results to reduce # of fetch requests

Having all this gives you the ability to access remote data sources in a concise and consistent way, while the library handles batching and overlapping requests to multiple data sources behind the scenes.

Heavily inspired by:

* [Haxl](https://github.com/facebook/Haxl) - Haskell library, Facebook, open-sourced
* [Stitch](https://www.youtube.com/watch?v=VVpmMfT8aYw) - Scala library, Twitter, not open-sourced

## Usage

Add this dependency to your project file:

```clojure
[muse "0.3.1"]
```

Use it in your code:

```clojure
(ns my.ns
  (:require [muse.core :as muse]
            [clojure.core.async :refer [go <!!]]))

(defrecord Timeline [username]
  muse/AResource
  (fetch [_] (go (range 10))))

(<!! (muse/fmap count (Timeline. "@alexey")))
```

## Example

```clojure
(defrecord UserScore [id]
  muse/AResource
  (fetch [_] (go (rand 100))))
```

Compare 2 users activity:

```clojure
(defn compare-users
  [id1 id2]
  (muse/run! (muse/fmap compare (UserScore. id1) (UserScore. id2))))
```

Find the most active user:

```clojure
(defn most-active-user []
  (muse/run! (->> (range 100)
                  (map (fn [id] (muse/fmap vector id (UserScore. id))))
                  (muse/fmap #(->> % (sort-by second) ffirst)))))
```

You can use monads library that you like, i.e. `core.algo` or `cats`:

```clojure
(defn fetch-post [id]
  (mlet [post (Post. id)
         user (User. (:author-id post))]
    (return (assoc post :author user))))
```

If you came from Haskell you will probably like shortcuts:

```clojure
(defn compare-users
  [id1 id2]
  (<$> compare (UserScore. id1) (UserScore. id2)))
```

Find more sophisticated examples in `test` directory:

* fetching social timeline

* calculating friends-of-friends list

* and more

## How Does It Work?

* you define data sources that you want to work with using `AResource` protocol (describe how `fetch` should be executed)

* you declare what do you want to do with the result of each data source fetch (yeah, your resource is a functor now)

* `muse` build AST of all operations placing data source fetching points as leaves (yeah, this is [free monads](http://goo.gl/1ubHUa) approach)

* `muse` implicitely rebuild AST to work with tree levels instead of separated leave that gives ability to batch requests and run independent fetches concurrently

* you call `muse/run!` interpreter that reduce AST level by level until the whole computation is finished

## Future Ideas

- [ ] `flat-map` operation
- [ ] split AResource to few protocols
- [ ] use ReadPort protocol to avoid explicit `run!` call
- [ ] composibility with `core.algo` and `cats` libraries
- [ ] catch & propagate exceptions
- [ ] clean up code, test coverage, better high-level API

## License

Release under the MIT license. See LICENSE for the full license.

## Thanks

Thanks go to Simon Marlow for creating/leading Haxl project (and talking about it). And to Facebook for open-sourcing it.