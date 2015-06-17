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

*Attention! API is subject to change*

Include the following to your lein `project.clj` dependencies:

```clojure
[muse "0.3.1"]
```

All functions are located in `muse.core`:

```clojure
(require '[muse.core :as muse])
```

## Quickstart

Simplest operations:

```clojure
user=> (require '[muse.core :refer :all] :reload)
nil
user=> (require '[clojure.core.async :refer [go <!!]])
nil
user=> (defrecord Timeline [id]
  #_=>   DataSource
  #_=>   (fetch [_] (go (range id))))
user.Timeline
user=> (Timeline. 10)
#user.Timeline{:id 10}
user=> (run! (Timeline. 10))
#<ManyToManyChannel clojure.core.async.impl.channels.ManyToManyChannel@63a01449>
user=> (<!! (run! (Timeline. 10)))
(0 1 2 3 4 5 6 7 8 9)
user=> (run!! (Timeline. 5))
(0 1 2 3 4)
user=> (fmap count (Timeline. 10))
#<MuseMap (clojure.core$count@3d804ede user.Timeline[10])>
user=> (run!! (fmap count (Timeline. 10)))
10
user=> (fmap inc (fmap count (Timeline. 3)))
#<MuseMap (clojure.core$comp$fn__4192@58dc9797 user.Timeline[3])>
user=> (run!! (fmap inc (fmap count (Timeline. 3))))
4
```

Nested data fetches:

```clojure
user=> (defrecord Post [id]
  #_=>   DataSource
  #_=>   (fetch [_] (go (inc id))))
user=> (flat-map ->Post (->Post 3))
#<MuseFlatMap (user$eval10466$__GT_Post__10498@411c0aeb user.Post[3])>
user=> (run!! (flat-map ->Post (->Post 3)))
5
user=> (run!! (flat-map ->Post (fmap count (Timeline. 4))))
5
user=> (traverse ->Post (Timeline. 3))
#<MuseFlatMap (muse.core$traverse$fn__10255@7ed127e0 user.Timeline[3])>
user=> (run!! (traverse ->Post (Timeline. 3)))
[1 2 3]
```

If you came from Haskell you will probably like shortcuts:

```clojure
user=> (<$> inc (<$> count (Timeline. 3)))
#<MuseMap (clojure.core$comp$fn__4192@6f2c4a58 user.Timeline[3])>
user=> (run!! (<$> inc (<$> count (Timeline. 3))))
4
```

Custom labeling:

```clojure
user=> (defrecord User [username]
  #_=>   DataSource
  #_=>   (fetch [_] (go (str "My name is " username)))
  #_=>   LabeledSource
  #_=>   (resource-id [_] username))
user.User
user=> (fmap #(str "What I've got: " %) (User. "Alexey"))
#<MuseMap (user$eval10562$fn__10563@184f2656 user.User[Alexey])>
user=> (run!! (fmap #(str "What I've got is: " %) (User. "Alexey")))
"What I've got is: My name is Alexey"
```

Find more examples in `test` directory and check `muse-examples` repo.

## How Does It Work?

* You define data sources that you want to work with using `DataSource` protocol (describe how `fetch` should be executed).

* You declare what do you want to do with the result of each data source fetch. Yeah, right, your data source is a functor now.

* You build an AST of all operations placing data source fetching points as leaves using `muse` low-level building blocks (`value`/`fmap`/`flat-map`) and higher-level API (`collect`/`traverse`/etc). Read more about [free monads](http://goo.gl/1ubHUa) approach.

* `muse` implicitely rebuilds AST to work with tree levels instead of separated leave that gives ability to batch requests and run independent fetches concurrently.

* `muse/run!` is an interpreter that reduce AST level by level until the whole computation is finished (it returns `core.async` channel that you can read from).

## TODO & Ideas

- [ ] catch & propagate exceptions
- [ ] composibility with `core.algo` and `cats` libraries
- [ ] clean up code, test coverage, better high-level API

## Known Restrictions

* works with `core.async` library only (if you use other async mechanism, like `future`s you can easialy turn your code to be compatible with `core.async`, i.e. with `async/thread`)
* assumes your operations with data sources are "side-effects free", so you don't really care about the order of fetches
* yes, you need enough memory to store the whole data fetched during a single `run!` call (in case it's impossible you should probably look into other ways to solve your problem, i.e. data stream libraries)

## License

Release under the MIT license. See LICENSE for the full license.

## Contribute

* Check for open issues or open a fresh issue to start a discussion around a feature idea or a bug.
* Fork the repository on Github to start making your changes to the master branch (or branch off of it).
* Write a test which shows that the bug was fixed or that the feature works as expected.

or simply...

* Use it.
* Enjoy it.
* Spread the word.

## Thanks

Thanks go to Simon Marlow for creating/leading Haxl project (and talking about it). And to Facebook for open-sourcing it.
