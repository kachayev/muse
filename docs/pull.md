# Pull API

Pull API is an extension build on top of Muse API as a higher level layer to help you to
simplify data sources definitions and provide you with even more flexible way to optimize
fetches when actual data usage is not defined in advance (yep, waving to GraghQL and friends
right now).

Effectively it contains from 2 parts:

1. new way to define `DataSource` by providing direct lazy "reference" to the data
2. declarative syntax to describe what "references" do you need to realize

Let's check typical practical example to see how it works step by step.

Assume you build a chat application. You have access to the list of messages in a given
chat thread (from your DB or from a microservice, that's irrelevant for now), where each
message contains `:sender-id` key holding user ID who sent it.

```clojure
user=> @(fetch-messages {:thread-id "9V5tUAhnL5XCOC1i4W"})
[{:id "9V5tUAhnL5XCOC1i4Z"
  :text "Hello there!"
  :reactions [...]
  :sender-id "9V5tUAhnL5XCOC1i8b"}
 {:id "9V5tY4rP0r0nQ4Nne4"
  :text "Long time no see!"
  :reactions [...]
  :sender-id "9V5tYAjp9wVzeIspfM"}]
```

And you have access to another data source where you can get information about the user
passing user ID.

```clojure
user=> @(fetch-user {:user-id "9V5tUAhnL5XCOC1i4Z"})
{:id "9V5tUAhnL5XCOC1i4Z"
 :firstName "Anthony"
 :lastName "Ross"
 :company "Licorice Pizza"
 :position "Agent"
 :countryCode 1}
```

Typically you want to denormalize this data and return to the client messages with information on
the sender already included, so you can e.g. render a nice UI from it. Perfect use case for the
`muse`! How we can do that? Well...


```clojure
(defrecord User [id]
  DataSource
  (fetch [_] (fetch-user {:user-id id})))

(defrecord ChatThread [id]
  DataSource
  (fetch [_] (fetch-messages {:thread-id id})))

(defn inject-sender [{:keys [sender-id] :as message}]
  (muse/fmap #(assoc message :sender %) (User. sender-id)))

(defn fetch-thread [id]
  (muse/run! (->> (ChatThread. id) (muse/traverse inject-sender))))
```

`muse` will do its job:

1. requests to fetch users will be deduplicated, which is necessary when you have just a couple
   of users having a long conversation :) 
2. all requests to fetch users will be sent simultaneously

But there're a few problems here. The most notable one: `traverse` and `fmap` force you to work
with data in a pretty counterintuitive way: fetch **nested** data and `fmap` over it later, when
in fact you usually think about it from the top to bottom not the way around. It's harder to write
and way harder to read afterwards.

With a new Pull API you can do the following:

```clojure
(defrecord ChatThread [id]
  DataSource
  (fetch [_]
    (d/chain'
      (fetch-messages {:thread-id id}))
      (fn [messages]
        (map (fn [{:keys [sender-id] :as message}]
               ;; data source is used here as some kind of "reference":
               ;; declaring that the value for key `:sender` might be find there
               (assoc messages :sender (User. sender-id)))
          message))))

(muse/run! (pull/pull (ChatThread. "9V5x5xxpS")))
```

Note, that now you work with your data structures in a logical top-to-bottom manner. When done, 
`pull/pull` takes a Muse AST and wraps it in a way that all such "references" would be resolved
when necessary into appropriate data blocks, remaining the rest of `muse` functionality, like
caching, batching and smart requests scheduling.

Being more consice and straightforward this approach opens to us another opportunity: what if in
some cases you don't need fetch `:sender` (working on `git shortlog` or implementing GraphQL?). Well
in such a case you can just skip those references and avoid redundant requests! Introducing the same
functionality into generic muse AST is a pretty clumsy task, as you need to branch all of you
`fmap` and `flat-map` wrappers properly. Doing this with Pull API is a pretty simple and intuitive:
`pull/pull` accepts as a second argument "specification" of what data you actually need, using
format similar to [Datomic Pull](https://docs.datomic.com/on-prem/pull.html) hence the name.

```clojure
user=> (muse/run!! (pull/pull (ChatThread. "9V5x5xxpS") [:text]))
[{:text "Hello there!"}
 {:text "Long time no see!"}]
```

Note, that in this case `:sender` fetches are simply ignored (as we do not need them in the
output). But they will be performed if we asked for that:

```clojure
user=> (muse/run!! (pull/pull (ChatThread. "9V5x5xxpS") [:text {:sender [:firstName]}]))
[{:text "Hello there!"
  :sender {:firstName "Steve"}}
 {:text "Long time no see!"
  :sender {:firstName "Shannon"}}]
```

Quick rules how to define pull spec:

 - `'*` means resolve all references and return "as is"
 - `[:text]` equals to `[{:text '*}]`
 - `[{:text *spec*}]` for the value of `:text` key apply `*spec*` (yep, recursively)
 - `[:text :sender]` contains rules for 2 keys: `:text` and `:sender` (here: pull everything)
 
When dealing with any sequences (vectors, lists, sets) spec is applied to each item in the sequence.

Hope that helps!
