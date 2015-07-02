## Muse

[![Build Status](https://travis-ci.org/kachayev/muse.svg?branch=master)](https://travis-ci.org/kachayev/muse)

[![Clojars Project](http://clojars.org/muse/latest-version.svg)](http://clojars.org/muse)

*Muse* is a Clojure library that works hard to make your relationship with remote data simple & enjoyable. We believe that concurrent code can be elegant and efficient at the same time.

Often times your business logic relies on remote data that you need to fetch from different sources: databases, caches, web services or 3rd party APIs, and you can't mess things up. *Muse* helps you to keep your business logic clear of low-level details while performing efficiently:

* batch multiple requests to the same data source
* request data from multiple data sources concurrently
* cache previous requests

Having all this gives you the ability to access remote data sources in a concise and consistent way, while the library handles batching and overlapping requests to multiple data sources behind the scenes.

Heavily inspired by:

* [Haxl](https://github.com/facebook/Haxl) - Haskell library, Facebook, open-sourced
* [Stitch](https://www.youtube.com/watch?v=VVpmMfT8aYw) - Scala library, Twitter, not open-sourced

Talks:

* [Efficient, Concurrent and Concise Data Access](https://goo.gl/h4Zuvr) at EuroClojure 2015

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
             (fmap (partial apply set/union)))))
```

You can also use monad interface with `cats` library:

```clojure
(defn get-post
  [id]
  (run! (m/mlet [post (fetch-post id)
                 author (fetch-user (:author-id post))]
          (m/return (assoc post :author author)))))
```

## Usage

*Attention! API is subject to change*

Include the following to your lein `project.clj` dependencies:

```clojure
[muse "0.3.3"]
```

All functions are located in `muse.core`:

```clojure
(require '[muse.core :as muse])
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

Let's imaging we have another data source: users' activity score by given user id.

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

And now few amaizing facts.

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

2) `muse` detects duplicated requests and cache results to avoid redundant work:

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
  (fetch-multi [_ users]
    (let [ids (cons id (map :id users))]
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

## Misc

If you came from Haskell you will probably like shortcuts:

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
- [ ] applicative functors interface

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
