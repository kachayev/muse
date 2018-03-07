# Solving the "N+1 Selects Problem" with Muse

The so-called [N+1 selects problem](http://ocharles.org.uk/blog/posts/2014-03-24-queries-in-loops-without-a-care-in-the-world.html) is characterized by a set of queries in a loop.

```clojure
(defn attach-author [{:keys [user] :as post}]
  (assoc post :user (get-user user)))

(defn latest-posts []
  (map attach-author (get-posts 10)))
```

The most generic version of this code (i.e. with Korma library) would perform one data fetch for `get-posts`, then another for each call to `get-user`; assuming each one is implemented with something like the SQL select statement, that means **N+1 selects**.

Using similar code, the Muse implementation will perform exactly two data fetches: one to `get-posts` and one with all the `get-user` calls batched together.

## The DataSource

Boilerplate code (connect to database):

```clojure
(require '[clojure.string :as s])
(require '[clojure.core.async :as async :refer [<! go]])
(require '[muse.core :refer :all])
(require '[postgres.async :refer :all])

(def db (open-db {:hostname "db.example.com"
                  :database "exampledb"
                  :username "user"
                  :password "pass"}))
```

Define `Posts` data source as a record that implements two protocols `muse/DataSource` and `muse/LabeledSource`. First one defines actually mehanism for fetching data from remote source. `fetch` function should return a `core.async` channel and here we're using `execute!` function from `postgres.async` package. Second one defines data source identifier to be used as a key in requests cache.

```clojure
(def post-sql "select id, user, title, text from posts limit $1")

(defrecord Posts [limit]
  DataSource
  (fetch [_]
    (async/map :rows [(execute! db [post-sql limit])]))

  LabeledSource
  (resource-id [_] limit))
```

Define `User` data source that additionally implemented `muse/BatchedSource` protocol. `fetch-multi` function should return `core.async` channel as well as `fetch`. The difference is that `muse` assumes to read from this channel a mapping from id to individual fetch result. `Muse` library will automatically figure out all cases when it's possible to batch multiple individual requests into a signle one.

```clojure
(def user-sql "select id, name from users where id = $1")

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
```

## Tying it All Together

All that remains to make the original example work is to define `get-posts` and `get-user` helpers.

```clojure
(defn get-posts [limit] (Posts. limit))
(defn get-user [id] (User. id))
```

The naïve code that looks like it will do N+1 fetches will now do just two.

```clojure
(defn attach-author [{:keys [user] :as post}]
  (fmap #(assoc post :user %) (get-user user)))

(defn latest-posts []
  (traverse attach-author (get-posts 10)))
```

The only change is that we have to place a call to `muse/run!`  interpreter (or its blocking version `muse/run!!`):

```clojure
(run!! (latest-posts))
```
