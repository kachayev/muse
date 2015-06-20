## Muse

*Muse* is a Clojure library that works hard to make your relationship with remote data simple & enjoyable. We believe that concurrent code can be elegant and efficient at the same time.

Often times your business logic relies on remote data that you need to fetch from different sources: databases, caches, web services or 3rd party APIs, and you can't mess things up. *Muse* helps you to keep your business logic clear of low-level details while performing efficiently:

* batch multiple requests to the same data source
* request data from multiple data sources concurrently
* cache previous requests

Having all this gives you the ability to access remote data sources in a concise and consistent way, while the library handles batching and overlapping requests to multiple data sources behind the scenes.

Heavily inspired by:

* [Haxl](https://github.com/facebook/Haxl) - Haskell library, Facebook, open-sourced
* [Stitch](https://www.youtube.com/watch?v=VVpmMfT8aYw) - Scala library, Twitter, not open-sourced

## The Idea

A core problem of many systems is balancing expressiveness against performance.

```clojure
(defn num-common-friends
  [x y]
  (count (set/intersection (friends-of x) (friends-of y))))
```

Here, `(friends-of x)` and `(friends-of y)` are independent, and you want it to be fetched concurrently in a single batch. Furthermore, if `x` and `y` refer to the same person, you don't want redundantly re-fetch their friend list.

*Muse* allows your data fetches to be implicitly concurrent:

```clojure
(defn num-common-friends
  [x y]
  (run! (fmap count (fmap set/intersection (friends-of x) (friends-of y)))))
```

Mapping over list will also run concurrently:

```clojure
(defn friends-of-friends
  [id]
  (run! (->> id
             friends-of
             (traverse friends-of)
             (fmap (partial apply concat)))))
```

You can also use monad interface with `cats` library:

```clojure
(defn num-common-friends
  [x y]
  (run! (m/mlet [fx (friends-of x)
                 fy (friends-of y)]
          (m/return (count (set/intersection fx fy))))))
```

## Usage

*Attention! API is subject to change*

Include the following to your lein `project.clj` dependencies:

```clojure
[muse "0.3.2"]
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
user=> (defrecord Range [id]
  #_=>   DataSource
  #_=>   (fetch [_] (go (range id))))
user.Range
user=> (Range. 10)
#user.Range{:id 10}
user=> (run! (Range. 10))
#<ManyToManyChannel clojure.core.async.impl.channels.ManyToManyChannel@63a01449>
user=> (<!! (run! (Range. 10)))
(0 1 2 3 4 5 6 7 8 9)
user=> (run!! (Range. 5))
(0 1 2 3 4)
user=> (fmap count (Range. 10))
#<MuseMap (clojure.core$count@3d804ede user.Range[10])>
user=> (run!! (fmap count (Range. 10)))
10
user=> (fmap inc (fmap count (Range. 3)))
#<MuseMap (clojure.core$comp$fn__4192@58dc9797 user.Range[3])>
user=> (run!! (fmap inc (fmap count (Range. 3))))
4
```

Nested data fetches:

```clojure
user=> (defrecord Inc [id]
  #_=>   DataSource
  #_=>   (fetch [_] (go (inc id))))
user=> (flat-map ->Inc (->Inc 3))
#<MuseFlatMap (user$eval10466$__GT_Inc__10498@411c0aeb user.Inc[3])>
user=> (run!! (flat-map ->Inc (->Inc 3)))
5
user=> (run!! (flat-map ->Inc (fmap count (Range. 4))))
5
user=> (traverse ->Inc (Range. 3))
#<MuseFlatMap (muse.core$traverse$fn__10255@7ed127e0 user.Range[3])>
user=> (run!! (traverse ->Inc (Range. 3)))
[1 2 3]
```

If you came from Haskell you will probably like shortcuts:

```clojure
user=> (<$> inc (<$> count (Range. 3)))
#<MuseMap (clojure.core$comp$fn__4192@6f2c4a58 user.Range[3])>
user=> (run!! (<$> inc (<$> count (Range. 3))))
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

## Cats

`MuseAST` monad is compatible with `cats` library, so you can use `mlet/mreturn` interface as well as `fmap` & `bind` functions provided by `cats.core`:

```clojure
user=> (require '[muse.core :refer :all])
nil
user=> (require '[clojure.core.async :refer [go <!!]])
nil
user=> (require '[cats.core :as m])
nil
user=> (defrecord Num [id] DataSource (fetch [_] (go id)))
user.Num
user=> (Num. 10)
#user.Num{:id 10}
user=> (run!! (Num. 10))
10
user=> (run!! (m/mlet [x (Num. 10)
  #_=>                 y (Num. 20)]
  #_=>           (m/return (+ x y))))
30
```

## Real-World Data Sources

HTTP calls:

```clojure
(require '[muse.core :refer :all])
(require '[org.httpkit.client :as http])
(require '[clojure.core.async :refer [chan put!]])

(defn async-get [url]
  (let [c (chan 1)] (http/get url (fn [res] (put! c res))) c))

(defrecord Gist [id]
  DataSource
  (fetch [_] (async-get (str "https://gist.github.com/" id))))

(defn gist-size [{:keys [headers]}]
  (get headers "Content-Size"))

(run!! (fmap gist-size (Gist. "21e7fe149bc5ae0bd878")))

(defn gist [id] (fmap gist-size (Gist. id)))

;; will fetch 2 gists concurrently
(run!! (fmap compare (gist "21e7fe149bc5ae0bd878") (gist "b5887f66e2985a21a466")))
```

SQL databases:

```clojure
(require '[clojure.string :as s])
(require '[clojure.core.async :as async :refer [<! go]])
(require '[muse.core :refer :all])
(require '[postgres.async :refer :all])

(defrecord Post [limit]
  DataSource
  (fetch [_]
    (async/map :rows [(execute! db ["select id, user, title, text from posts limit $1" limit])]))
  LabeledSource
  (resource-id [_] limit))

(defrecord User [id]
  DataSource
  (fetch [_]
    (async/map :rows [(execute! db ["select id, name from users where id = $1" id])]))

  BatchedSource
  (fetch-multi [_ users]
    (let [all-ids (cons id (map :id users))
          query (str "select id, name from users where id IN (" (s/join "," all-ids) ")")]
      (go
        (let [{:keys [rows]} (<! (execute! db [query]))]
          (into {} (map (fn [{:keys [id] :as row}] [id row]) rows)))))))

(defn attach-author [{:keys [user] :as post}]
  (fmap #(assoc post :user %) (User. user)))

(defn fetch-posts [limit]
  (traverse attach-author (Posts. limit)))

;; will execute 2 SQL queries instead of 11
(run!! (fetch-posts 10))
```

You can do the same tricks with [Redis](https://github.com/benashford/redis-async).

## How Does It Work?

* You define data sources that you want to work with using `DataSource` protocol (describe how `fetch` should be executed).

* You declare what do you want to do with the result of each data source fetch. Yeah, right, your data source is a functor now.

* You build an AST of all operations placing data source fetching points as leaves using `muse` low-level building blocks (`value`/`fmap`/`flat-map`) and higher-level API (`collect`/`traverse`/etc). Read more about [free monads](http://goo.gl/1ubHUa) approach.

* `muse` implicitely rebuilds AST to work with tree levels instead of separated leave that gives ability to batch requests and run independent fetches concurrently.

* `muse/run!` is an interpreter that reduce AST level by level until the whole computation is finished (it returns `core.async` channel that you can read from).

## TODO & Ideas

- [ ] catch & propagate exceptions
- [ ] clean up code, test coverage, better high-level API
- [ ] article about N+1 selects problem
- [ ] article about `FriendsOf` tracking with random timeouts

## Known Restrictions

* works with `core.async` library only (if you use other async mechanism, like `future`s you can easialy turn your code to be compatible with `core.async`, i.e. with `async/thread`)
* assumes your operations with data sources are "side-effects free", so you don't really care about the order of fetches
* yes, you need enough memory to store the whole data fetched during a single `run!` call (in case it's impossible you should probably look into other ways to solve your problem, i.e. data stream libraries)

## License

Release under the MIT license. See LICENSE for the full license.

## Contribute

* Check for open issues or open a fresh issue to start a discussion around a feature idea or a bug.
* Fork the repository on Github & fork master to `feature-*` branch to start making your changes.
* Write a test which shows that the bug was fixed or that the feature works as expected.

or simply...

* Use it.
* Enjoy it.
* Spread the word.

## Thanks

Thanks go to Simon Marlow for creating/leading Haxl project (and talking about it). And to Facebook for open-sourcing it.
