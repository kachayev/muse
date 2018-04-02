## Muse

[![Build Status](https://travis-ci.org/kachayev/muse.svg?branch=master)](https://travis-ci.org/kachayev/muse)

Add to your project (note weird `muse2` artefact group id as Clojars now denies shadowing of Maven Central artifacts):

```clojure
[muse2/muse "0.4.4"]
```

Latest pre-release version if you want to play with the latest features:

```clojure
[muse2/muse "0.4.5-alpha3"]
```

## What's That?

*Muse* is a Clojure library that works hard to make your relationship with remote data simple & enjoyable. We believe that concurrent code can be elegant and efficient at the same time.

Oftentimes, your business logic relies on remote data that you need to fetch from different sources: databases, caches, web services or 3rd party APIs, and you can't mess things up. *Muse* helps you to keep your business logic clear of low-level details while performing efficiently:

* batch multiple requests to the same data source
* request data from multiple data sources concurrently
* cache previous requests

Having all this gives you the ability to access remote data sources in a concise and consistent way, while the library handles batching and overlapping requests to multiple data sources behind the scenes.

Heavily inspired by:

* [Haxl](https://github.com/facebook/Haxl) - Haskell library, Facebook, open-sourced
* [Stitch](https://www.youtube.com/watch?v=VVpmMfT8aYw) - Scala library, Twitter, not open-sourced

Talks:

* "Reinventing Haxl: Efficient, Concurrent and Concise Data Access" at EuroClojure 2015: [Video](https://goo.gl/masrsz), [Slides](https://goo.gl/h4Zuvr)

## The Idea

A core problem of many systems is balancing expressiveness against performance.

```clojure
(defn num-common-friends [x y]
  (count (set/intersection (friends-of x) (friends-of y))))
```

Here, `(friends-of x)` and `(friends-of y)` are independent, and you want it to be fetched concurrently in a single batch. Furthermore, if `x` and `y` refer to the same person, you don't want to redundantly re-fetch their friend list.

*Muse* allows your data fetches to be implicitly concurrent:

```clojure
(defn num-common-friends [x y]
  (run! (fmap count (fmap set/intersection (friends-of x) (friends-of y)))))
```

Mapping over lists will also run concurrently:

```clojure
(defn friends-of-friends [id]
  (run! (->> id
             friends-of
             (traverse friends-of)
             (fmap (partial apply set/union)))))
```

You can also use monad interface with `cats` library:

```clojure
(defn get-post [id]
  (run! (m/mlet [post (fetch-post id)
                 author (fetch-user (:author-id post))]
          (m/return (assoc post :author author)))))
```

## Usage

*Attention! API is subject to change*

Include the following to your lein `project.clj` dependencies:

```clojure
[muse2/muse "0.4.4"]
```

or experimental `alpha` build, if you're brave enough:

```clojure
[muse2/muse "0.4.5-alpha3"]
```

All functions are located in `muse.core`:

```clojure
(require '[muse.core :as muse])
```

If you need to use [manifold](https://github.com/ztellman/manifold)-based version, please do
the following:

```clojure
(require '[muse.deferred :as muse])
```

## Quickstart

Simple helper to emulate async request to the remote source with unpredictable response latency:

```clojure
(require '[clojure.core.async :refer [go <!! <! timeout]])

(defn remote-req [id result]
  (let [wait (rand 1000)]
    (println "-->" id ".." wait)
    (go
     (<! (timeout wait))
     (println "<--" id)
     result)))
```

Define data source (list of friends by given user id):

```clojure
(require '[muse.core :refer :all])

(defrecord FriendsOf [id]
  DataSource
  (fetch [_] (remote-req id (set (range id)))))
```

Run simplest scenario:

```clojure
core> (FriendsOf. 10)
#core.FriendsOf{:id 10}
core> (run! (FriendsOf. 10)) ;; returns a channel
#<ManyToManyChannel clojure.core.async.impl.channels.ManyToManyChannel@1aeaa839>
core> (<!! (run! (FriendsOf. 10)))
--> 10 .. 342.97080768100585
<-- 10
#{0 7 1 4 6 3 2 9 5 8}
core> (run!! (FriendsOf. 10)) ;; blocks until done
--> 10 .. 834.4564727277141
<-- 10
#{0 7 1 4 6 3 2 9 5 8}
```

There is nothing special about it (yet), let's do something more interesting:

```clojure
core> (fmap count (FriendsOf. 10))
#<MuseMap (clojure.core$count@1b932280 core.FriendsOf[10])>
core> (run!! (fmap count (FriendsOf. 10)))
--> 10 .. 844.5086574753595
<-- 10
10
core> (fmap inc (fmap count (FriendsOf. 3)))
#<MuseMap (clojure.core$comp$fn__4192@4275ef0b core.FriendsOf[3])>
core> (run!! (fmap inc (fmap count (FriendsOf. 3))))
--> 3 .. 334.5374146247876
<-- 3
4
```

Let's imagine we have another data source: users' activity score by given user id.

```clojure
(defrecord ActivityScore [id]
  DataSource
  (fetch [_] (remote-req id (inc id))))
```

Nested data fetches (you can see 2 levels of execution):

```clojure
(defn first-friend-activity []
  (->> (FriendsOf. 10)
       (fmap sort)
       (fmap first)
       (flat-map #(ActivityScore. %))))

core> (run!! (first-friend-activity))
--> 10 .. 576.5833162596521
<-- 10
--> 0 .. 275.28637368204966
<-- 0
1
```

And now a few amazing facts.

```clojure
(require '[clojure.set :refer [intersection]])

(defn num-common-friends [x y]
  (fmap count (fmap intersection (FriendsOf. x) (FriendsOf. y))))
```

1) `muse` automatically runs fetches concurrently:

```clojure
core> (run!! (num-common-friends 3 4))
--> 3 .. 374.6445696819365
--> 4 .. 162.1603407048976
<-- 4
<-- 3
3
```

2) `muse` detects duplicated requests and caches results to avoid redundant work:

```clojure
core> (run!! (num-common-friends 5 5))
--> 5 .. 781.2024344113081
<-- 5
5
```

3) seq operations will also run concurrently:

```clojure
(defn friends-of-friends [id]
  (->> (FriendsOf. id)
       (traverse #(FriendsOf. %))
       (fmap (partial apply set/union))))

core> (run!! (friends-of-friends 5))
--> 5 .. 942.2654519658018
<-- 5
--> 0 .. 429.0184498546441
--> 1 .. 316.54859989009765
--> 4 .. 365.7622736084006
--> 3 .. 752.5111238688877
--> 2 .. 618.4316806897967
<-- 1
<-- 4
<-- 0
<-- 2
<-- 3
#{0 1 3 2}
```

4) you can implement `BatchedSource` protocol to tell `muse` how to batch requests:

```clojure
(defrecord FriendsOf [id]
  DataSource
  (fetch [_] (remote-req id (set (range id))))

  BatchedSource
  (fetch-multi [this others]
    (let [ids (cons id (map :id (cons this others)))]
      (->> ids
           (map #(vector %1 (set (range %1))))
           (into {})
           (remote-req ids)))))

core> (run!! (frieds-of-friends 5))
--> 5 .. 13.055500150089605
<-- 5
--> (0 1 4 3 2) .. 436.6121922156462
<-- (0 1 4 3 2)
#{0 1 3 2}
```

A few notes on `BatchedSource` protocol as it might be kinda tricky from the first glance:

 - `fetch-multi` excepts first node as a first argument (usually, `this`) and all others as a second argument (usually, `others`)
 - you have an option to return either map id -> resource (make sure it's the same id you would return from `resource-id`) or a seq of resources preserving the order of identifiers given your as an argument (AST runner would double check that the size of your output is actually equal to the size of input params)

## Manifold

`core.async` is a decent abstraction for working with async code, but it's not flexible enough to cover all cases. `muse` provides a separate namespace `muse.deferred` that gives you ability to define resources in terms of `manifold.deferred`. Just use import aliasing and you code will look the same. See the following:

```clojure
(require '[muse.deferred :as muse])
(require '[manifold.deferred :as d])

(defrecord Numeric [n]
  muse/DataSource
  ;; note that fetch returns a deferred value instead of a channel
  (fetch [_] (d/future (* 2 n)))

  muse/LabeledSource
  (resource-id [_] n))

(muse/run! (muse/fmap inc (Numeric. 21)))
user=> << 43 >>

(muse/run!! (muse/fmap inc (Numeric. 21)))
user=> 43
```

Read more about `manifold` library [here](https://github.com/ztellman/manifold). Please note, that muse does not allow to mix different execution strategies in a single AST. In case you mess channels and deferred in your code, you have explitely convert them into a single source of truth before passing them to `muse`.

## Pull API

Pull API is an extension build on top of Muse API as a higher level layer to help you to simplify data sources definitions and provide you with even more flexible way to optimize fetches when actual data usage is not defined in advance (yep, waving to GraghQL and friends right now).

Find more in [documentation](https://github.com/kachayev/muse/blob/master/docs/pull.md#pull-api).

## Misc

If you come from Haskell you will probably like shortcuts:

```clojure
core> (<$> inc (<$> count (FriendsOf. 3)))
#<MuseMap (clojure.core$comp$fn__4192@6f2c4a58 core.FriendsOf[3])>
core> (run!! (<$> inc (<$> count (FriendsOf. 3))))
4
```

Custom response cache id:

```clojure
(defrecord Timeline [username]
  DataSource
  (fetch [_] (remote-req username (str username "'s timeline ")))

  LabeledSource
  (resource-id [_] username))

core> (fmap count (Timeline. "@kachayev"))
#<MuseMap (clojure.core$count@1b932280 core.Timeline[@kachayev])>
euroclojure.core> (run!! (fmap count (Timeline. "@kachayev")))
--> @kachayev .. 326.7199583652264
<-- @kachayev
20
core> (run!! (fmap str (Timeline. "@kachayev") (Timeline. "@kachayev")))
--> @kachayev .. 809.035607308747
<-- @kachayev
"@kachayev's timeline @kachayev's timeline "

```

Find more examples in `test` directory and check `muse-examples` repo.

## ClojureScript

`Muse` can be used from ClojureScript code with few minor differences:

* `run!!` macro isn't provided (as we don't have blocking experience)
* all data sources should implement namespaced version of `LabeledSource` protocol (return pair `[resource-name id]`)

## Cats

`MuseAST` monad is compatible with `cats` library, so you can use `mlet/return` interface as well as `fmap` & `bind` functions provided by `cats.core`:

```clojure
(require '[muse.core :refer :all])
(require '[clojure.core.async :refer [go <!!]])
(require '[cats.core :as m])

(defrecord Post [id]
  DataSource
  (fetch [_] (remote-req id {:id id :author-id (inc id) :title "Muse"})))

(defrecord User [id]
  DataSource
  (fetch [_] (remote-req id {:id id :name "Alexey"})))

(defn get-post [id]
  (run! (m/mlet [post (Post. id)
                 user (User. (:author-id post))]
                (m/return (assoc post :author user)))))

core> (<!! (get-post 10))
--> 10 .. 254.02115766996968
<-- 10
--> 11 .. 80.1692964764319
<-- 11
{:author {:id 11, :name "Alexey"}, :id 10, :author-id 11, :title "Muse"}
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

SQL databases (see more detailed example here: ["Solving the N+1 Selects Problem with Muse"](https://github.com/kachayev/muse/blob/master/docs/sql.md)):

```clojure
(require '[clojure.string :as s])
(require '[clojure.core.async :as async :refer [<! go]])
(require '[muse.core :refer :all])
(require '[postgres.async :refer :all])

(def posts-sql "select id, user, title, text from posts limit $1")
(def user-sql "select id, name from users where id = $1")

(defrecord Posts [limit]
  DataSource
  (fetch [_]
    (async/map :rows [(execute! db [posts-sql limit])]))

  LabeledSource
  (resource-id [_] limit))

(defrecord User [id]
  DataSource
  (fetch [_]
    (async/map :rows [(execute! db [user-sql id])]))

  BatchedSource
  (fetch-multi [this others]
    (let [all-ids (cons id (map :id (cons this others)))
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

* `muse` implicitly rebuilds AST to work with tree levels instead of separate leaves that gives ability to batch requests and run independent fetches concurrently.

* `muse/run!` is an interpreter that reduces AST level by level until the whole computation is finished (it returns a `core.async` channel that you can read from).

## TODO & Ideas

(any support is very welcome)

- [ ] catch & propagate exceptions, provide a simple way to deal with timeouts
- [ ] debuggability with nice visualization for AST & fetching (attaching special meta variables to each AST node during the execution)
- [ ] clean up code, tests coverage
- [ ] build node-relations delarative notation on top of low-level API to describe your data

## Known Restrictions

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
