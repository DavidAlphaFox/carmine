(ns taoensso.carmine "Clojure Redis client & message queue."
  {:author "Peter Taoussanis (@ptaoussanis)"}
  (:refer-clojure :exclude [time get set key keys type sync sort eval])
  (:require [clojure.string       :as str]
            [taoensso.encore      :as enc]
            [taoensso.timbre      :as timbre]
            [taoensso.nippy       :as nippy]
            [taoensso.nippy.tools :as nippy-tools]
            [taoensso.carmine
             (protocol    :as protocol)
             (connections :as conns)
             (commands    :as commands)]))

(if (vector? taoensso.encore/encore-version)
  (enc/assert-min-encore-version [3 1 0])
  (enc/assert-min-encore-version  3.1))

;;;; Connections

(enc/defalias with-replies protocol/with-replies)

(defmacro wcar
  "Evaluates body in the context of a fresh thread-bound pooled connection to
  Redis server. Sends Redis commands to server as pipeline and returns the
  server's response. Releases connection back to pool when done.

  `conn-opts` arg is a map with connection pool and spec options, e.g.:
    {:pool {}    :spec {:host \"127.0.0.1\" :port 6379}} ; Default
    {:pool :none :spec {:host \"127.0.0.1\" :port 6379}} ; No pool
    {:pool {} :spec {:uri \"redis://redistogo:pass@panga.redistogo.com:9475/\"}}
    {:pool {} :spec {:host \"127.0.0.1\"
                     :port 6379
                     :ssl-fn :default ; [1]
                     :password \"secret\"
                     :timeout-ms 6000
                     :db 3}}

  Note that because of thread-binding, you'll probably want to avoid lazy Redis
  command calls in `wcar`'s body unless you know what you're doing. Compare:
  `(wcar {} (for   [k [:k1 :k2]] (car/set k :val))` ; Lazy, NO commands run
  `(wcar {} (doseq [k [:k1 :k2]] (car/set k :val))` ; Not lazy, commands run

  See also `with-replies`.

  [1] Optional `ssl-fn` conn opt takes and returns a `java.net.Socket`:
    (fn [{:keys [^Socket socket host port]}]) -> ^Socket
    `:default` => use `taoensso.carmine.connections/default-ssl-fn`."

  {:arglists '([conn-opts :as-pipeline & body] [conn-opts & body])}
  [conn-opts & args] ; [conn-opts & [a1 & an :as args]]
  `(let [[pool# conn#] (conns/pooled-conn ~conn-opts)

         ;; To support `wcar` nesting with req planning, we mimic
         ;; `with-replies` stashing logic here to simulate immediate writes:
         ?stashed-replies#
         (when protocol/*context*
           (protocol/execute-requests :get-replies :as-pipeline))]

     (try
       (let [response# (protocol/with-context conn#
                         (protocol/with-replies ~@args))]
         (conns/release-conn pool# conn#)
         response#)

       (catch Throwable t# ; nb Throwable to catch assertions, etc.
         (conns/release-conn pool# conn# t#) (throw t#))

       ;; Restore any stashed replies to pre-existing context:
       (finally
         (when ?stashed-replies#
           (parse nil ; Already parsed on stashing
             (enc/run! return ?stashed-replies#)))))))

(comment
  (wcar {} (ping) "not-a-Redis-command" (ping))
  (with-open [p (conns/conn-pool {})] (wcar {:pool p} (ping) (ping)))
  (wcar {} (ping))
  (wcar {} :as-pipeline (ping))

  (wcar {} (echo 1) (println (with-replies (ping))) (echo 2))
  (wcar {} (echo 1) (println (with-replies :as-pipeline (ping))) (echo 2))
  (def setupf (fn [_] (println "boo")))
  (wcar {:spec {:conn-setup-fn setupf}}))

(comment
  (wcar {}
    (set "temp_foo" nil)
    (get "temp_foo"))

  (seq (wcar {} (parse-raw (get "temp_foo")))))

;;;; Misc core

;;; Mostly deprecated; prefer using encore stuff directly
(defn as-int   [x] (when x (enc/as-int   x)))
(defn as-float [x] (when x (enc/as-float x)))
(defn as-bool  [x] (when x (enc/as-bool  x)))

(enc/defalias parse       protocol/parse)
(enc/defalias parser-comp protocol/parser-comp)
(enc/defalias parse-raw   protocol/parse-raw)
(enc/defalias parse-nippy protocol/parse-nippy)

(defmacro parse-int      [& body] `(parse as-int   ~@body))
(defmacro parse-float    [& body] `(parse as-float ~@body))
(defmacro parse-bool     [& body] `(parse as-bool  ~@body))
(defmacro parse-keyword  [& body] `(parse keyword  ~@body))
(defmacro parse-suppress [& body]
  `(parse (fn [_#] protocol/suppressed-reply-kw) ~@body))

(comment (wcar {} (parse-suppress (ping)) (ping) (ping)))

(enc/compile-if enc/str-join
  (defn key* [parts] (enc/str-join ":" (map #(if (keyword? %) (enc/as-qname %) (str %))) parts))
  (defn key* [parts] (str/join     ":" (map #(if (keyword? %) (enc/as-qname %) (str %))  parts))))

(defn key
  "Joins parts to form an idiomatic compound Redis key name. Suggested style:
    * \"category:subcategory:id:field\" basic form.
    * Singular category names (\"account\" rather than \"accounts\").
    * Plural _field_ names when appropriate (\"account:friends\").
    * Dashes for long names (\"email-address\" rather than \"emailAddress\", etc.)."
  [& parts] (key* parts))

(comment (key :foo/bar :baz "qux" nil 10))

(enc/defalias raw            protocol/raw)
(enc/defalias with-thaw-opts nippy-tools/with-thaw-opts)
(enc/defalias freeze         nippy-tools/wrap-for-freezing
  "Forces argument of any type (incl. keywords, simple numbers, and binary types)
  to be subject to automatic de/serialization with Nippy.")

;;;; Issue #83

(def issue-83-workaround?
  "Workaround for Carmine issue #83.

  Correct/intended behaviour:
    - Byte arrays written with Carmine are read back as byte arrays.

  Break introduced in v2.6.0 (April 1st 2014), issue #83:
    - Byte arrays written with Carmine are accidentally serialized
      with Nippy, and read back as serialized byte arrays.

  Workaround introduced in v2.6.1 (May 1st 2014), issue #83:
    - To help folks who had written binary data under v2.6.0,
      Carmine started trying to auto-deserialize byte arrays that
      start with the standard 4-byte Nippy header byte sequence.

      Benefits:
        b1. Folks affected by the earlier breakage can now read back
            their byte arrays as expected.

      Costs:
        c1. A very minor performance hit when reading binary values
            (because of check for a possible Nippy header).

        c2. May encourage possible dependence on the workaround if
            folks start pre-serializing Nippy data sent to Carmine,
            expecting it to be auto-thawed on read.

  c2 in particular means that it will probably never be safe to
  disable this workaround by default.

  However folks starting with Carmine after v2.6.1 and who have
  never pre-serialized Nippy data written with Carmine may prefer
  to disable the workaround.

  If you're not sure what this is or if it's safe to change, you
  should probably leave it at the default (true) value.

  To change the default (true) value:

    - Call `(alter-var-root #'taoensso.carmine/issue-83-workaround? (fn [_] false))`,
    - or set one of the following to \"false\" or \"FALSE\":
      - `taoensso.carmine.issue-83-workaround` JVM property
      - `TAOENSSO_CARMINE_ISSUE_83_WORKAROUND` env var

  Ref. https://github.com/ptaoussanis/carmine/issues/83 for more info."

  (enc/get-sys-bool true
    "taoensso.carmine.issue-83-workaround"
    "TAOENSSO_CARMINE_ISSUE_83_WORKAROUND"))

(defn thaw-if-possible-nippy-bytes
  "If given agrgument is a byte-array starting with apparent NPY header,
  calls `nippy/thaw` against argument, otherwise passes it through.

  This util can be useful if you're manually disabling
  `issue-83-workaround` but still have some cases where you're possibly
  trying to read data affected by that issue.

  NB does not trap thaw exceptions.
  See `issue-83-workaround` for more info."

  ([x     ] (thaw-if-possible-nippy-bytes x nil))
  ([x opts] (if (protocol/possible-nippy-bytes? x) (nippy/thaw x opts) x)))

(enc/defalias return protocol/return)
(comment (wcar {} (return :foo) (ping) (return :bar))
         (wcar {} (parse name (return :foo)) (ping) (return :bar)))

;;;; Standard commands

(commands/defcommands) ; Defines 190+ Redis command fns as per official spec

(defn redis-call
  "Sends low-level requests to Redis. Useful for DSLs, certain kinds of command
  composition, and for executing commands that haven't yet been added to the
  official `commands.json` spec.

  (redis-call [:set \"foo\" \"bar\"] [:get \"foo\"])"
  [& requests]
  (enc/run!
    (fn [[cmd & args]]
      (let [cmd-parts (-> cmd name str/upper-case (str/split #"-"))
            request   (into (vec cmd-parts) args)]
        (commands/enqueue-request (count cmd-parts) request)))
    requests))

(comment (wcar {} (redis-call [:set "foo" "bar"] [:get "foo"]
                              [:config-get "*max-*-entries*"])))

;;;; Lua/scripting support

(defn- sha1-str [x]         (org.apache.commons.codec.digest.DigestUtils/sha1Hex (str x)))
(defn- sha1-ba  [^bytes ba] (org.apache.commons.codec.digest.DigestUtils/sha1Hex ba))

(def script-hash (memoize (fn [script] (sha1-str script))))

(defn- -evalsha* [script numkeys args]
  (redis-call (into [:evalsha (script-hash script) numkeys] args)))

(defn evalsha* "Like `evalsha` but automatically computes SHA1 hash for script."
  [script numkeys & args] (-evalsha* script numkeys args))

(defn- -eval* [script numkeys args]
  (let [parser ; Respect :raw-bulk, otherwise ignore parser:
        (if-not (:raw-bulk? (meta protocol/*parser*))
          nil ; As `parse-raw`:
          (with-meta identity {:raw-bulk? true}))
        [r & _] ; & _ for :as-pipeline
        (parse parser (with-replies :as-pipeline
                        (-evalsha* script numkeys args)))]

    (if (= (:prefix (ex-data r)) :noscript)
      ;;; Now apply context's parser:
      (redis-call (into [:eval script numkeys] args))
      (return r))))

(defn eval*
  "Optimistically tries to send `evalsha` command for given script. In the event
  of a \"NOSCRIPT\" reply, reattempts with `eval`. Returns the final command's
  reply. Redis Cluster note: keys need to all be on same shard."
  [script numkeys & args] (-eval* script numkeys args))

(comment
  (wcar {} (redis-call ["eval" "return 10;" 0]))
  (wcar {} (eval  "return 10;" 0))
  (wcar {} (eval* "return 10;" 0)))

(def ^:private script-subst-vars
  "Substitutes named variables for indexed KEYS[]s and ARGV[]s in Lua script."
  (memoize
   (fn [script key-vars arg-vars]
     (let [subst-map ; {match replacement} e.g. {"_:my-var" "ARRAY-NAME[1]"}
           (fn [vars array-name]
             (zipmap (map #(str "_:" (name %)) vars)
                     (map #(str array-name "[" % "]")
                          (map inc (range)))))]
       (reduce (fn [s [match replacement]] (str/replace s match replacement))
               (str script)
               (->> (merge (subst-map key-vars "KEYS")
                           (subst-map arg-vars "ARGV"))
                    ;; Stop ":foo" replacing ":foo-bar" w/o need for insane Regex:
                    (sort-by #(- (.length ^String (first %))))))))))

(comment
  (script-subst-vars "_:k1 _:a1 _:k2! _:a _:k3? _:k _:a2 _:a _:a3 _:a-4"
    [:k3? :k1 :k2! :k] [:a2 :a-4 :a3 :a :a1]))

(defn- script-prep-vars "-> [<key-vars> <arg-vars> <var-vals>]"
  [keys args]
  [(when (map? keys) (clojure.core/keys keys))
   (when (map? args) (clojure.core/keys args))
   (into (vec (if (map? keys) (vals keys) keys))
              (if (map? args) (vals args) args))])

(comment
  (script-prep-vars {:k1 :K1 :k2 :K2} {:a1 :A1 :a2 :A2})
  (script-prep-vars [:K1 :K2] {:a1 :A1 :a2 :A2}))

(defn lua
  "All singing, all dancing Lua script helper. Like `eval*` but allows script
  vars to be provided as {<var> <value> ...} maps:

  (lua \"redis.call('set', _:my-key, _:my-arg)\" {:my-key \"foo} {:my-arg \"bar\"})

  Keys are separate from other args as an implementation detail for clustering
  purposes (keys need to all be on same shard)."
  [script keys args]
  (let [[key-vars arg-vars var-vals] (script-prep-vars keys args)]
    (apply eval* (script-subst-vars script key-vars arg-vars)
      (count keys) ; Not key-vars!
      var-vals)))

(comment
  (wcar {}
    (lua "redis.call('set', _:my-key, _:my-val)
          return redis.call('get', 'foo')"
      {:my-key "foo"}
      {:my-val "bar"})))

(def ^:private scripts-loaded-locally "#{[<conn-spec> <sha>]...}" (atom #{}))
(defn lua-local
  "Alpha - subject to change.
  Like `lua`, but optimized for the single-server, single-client case: maintains
  set of Lua scripts previously loaded by this client and will _not_ speculate
  on script availability. CANNOT be used in environments where Redis servers may
  go down and come back up again independently of application servers (clients)."
  [script keys args]
  (let [conn-spec (get-in protocol/*context* [:conn :spec])
        [key-vars arg-vars var-vals] (script-prep-vars keys args)
        script* (script-subst-vars script key-vars arg-vars)
        sha     (script-hash script*)
        evalsha (fn [] (apply evalsha sha (count keys) var-vals))]
    (if (contains? @scripts-loaded-locally [conn-spec sha]) (evalsha)
      (do (with-replies (parse nil (script-load script*)))
          (swap! scripts-loaded-locally conj [conn-spec sha])
          (evalsha)))))

(comment
  (wcar {} (lua       "return redis.call('ping')" {:_ "_"} {}))
  (wcar {} (lua-local "return redis.call('ping')" {:_ "_"} {}))

  (enc/qb 1000
    (wcar {} (ping) (lua "return redis.call('ping')" {:_ "_"} {})
      (ping) (ping) (ping))) ; ~140

  (enc/qb 1000
    (wcar {} (ping) (lua-local "return redis.call('ping')" {:_ "_"} {})
      (ping) (ping) (ping))) ; ~135 (localhost)
  )

;;;; Misc helpers
;; These are pretty rusty + kept around mostly for back-compatibility

(defn hmset* "Like `hmset` but takes a map argument."
  [key m] (apply hmset key (reduce concat m)))

(defn info* "Like `info` but automatically coerces reply into a hash-map."
  [& [clojureize?]]
  (->> (info)
       (parse
        (fn [reply]
          (let [m (->> reply str/split-lines
                       (map #(str/split % #":"))
                       (filter #(= (count %) 2))
                       (into {}))]
            (if-not clojureize?
              m
              (reduce-kv (fn [m k v] (assoc m (keyword (str/replace k "_" "-")) v))
                         {} (or m {}))))))))

(comment (wcar {} (info* :clojurize)))

(defn zinterstore* "Like `zinterstore` but automatically counts keys."
  [dest-key source-keys & opts]
  (apply zinterstore dest-key (count source-keys) (concat source-keys opts)))

(defn zunionstore* "Like `zunionstore` but automatically counts keys."
  [dest-key source-keys & opts]
  (apply zunionstore dest-key (count source-keys) (concat source-keys opts)))

;; Adapted from redis-clojure
(defn- parse-sort-args [args]
  (loop [out [] remaining-args (seq args)]
    (if-not remaining-args
      out
      (let [[type & args] remaining-args]
        (case type
          :by    (let [[pattern & rest] args]      (recur (conj out "BY" pattern) rest))
          :limit (let [[offset count & rest] args] (recur (conj out "LIMIT" offset count) rest))
          :get   (let [[pattern & rest] args]      (recur (conj out "GET" pattern) rest))
          :mget  (let [[patterns & rest] args]     (recur (into out (interleave (repeat "GET")
                                                                      patterns)) rest))
          :store (let [[dest & rest] args]         (recur (conj out "STORE" dest) rest))
          :alpha (recur (conj out "ALPHA") args)
          :asc   (recur (conj out "ASC")   args)
          :desc  (recur (conj out "DESC")  args)
          (throw (ex-info (str "Unknown sort argument: " type) {:type type})))))))

(defn sort*
  "Like `sort` but supports idiomatic Clojure arguments: :by pattern,
  :limit offset count, :get pattern, :mget patterns, :store destination,
  :alpha, :asc, :desc."
  [key & sort-args] (apply sort key (parse-sort-args sort-args)))

;;;; Persistent stuff (monitoring, pub/sub, etc.)
;; Once a connection to Redis issues a command like `p/subscribe` or `monitor`
;; it enters an idiosyncratic state:
;;
;;     * It blocks while waiting to receive a stream of special messages issued
;;       to it (connection-local!) by the server.
;;     * It can now only issue a subset of the normal commands like
;;       `p/un/subscribe`, `quit`, etc. These do NOT issue a normal server reply.
;;
;; To facilitate the unusual requirements we define a Listener to be a
;; combination of persistent, NON-pooled connection and threaded message
;; handler.

;;;; Listeners
;; This whole API is a bit sketchy; would do differently today. Though not bad
;; enough to be worth breaking now. Might address in a future version of Carmine.

(declare close-listener)

(defrecord Listener [connection handler state future]
  java.io.Closeable
  (close [this] (close-listener this)))

(defn- get-keep-alive-fn
  "Returns (fn send-keep-alive!?) which
  records activity, returns true iff first activity recorded in last `msecs`."
  ;; Much simpler to implement only for listeners than as a general conns feature
  ;; (where hooking in to recording activity is non-trivial).
  [msecs]
  (let [msecs          (long msecs)
        last-activity_ (atom (System/currentTimeMillis))]

    (fn send-keep-alive!? []
      (let [now           (System/currentTimeMillis)
            last-activity (enc/reset-in! last-activity_ now)]
        (> (- now (long last-activity)) msecs)))))

(comment (def kaf (get-keep-alive-fn 2000)) (kaf))

(defn -with-new-listener
  "Implementation detail. Returns new Listener."
  [{:keys [conn-spec init-state handler-fn swapping-handler? body-fn error-fn
           keep-alive-ms]}]

  (let [state_      (atom init-state)
        handler-fn_ (atom handler-fn)

        {:keys [in] :as conn}
        (conns/make-new-connection
          (assoc (conns/conn-spec conn-spec)
            :listener? true))

        keep-alive-fn
        (when-let [ms keep-alive-ms]
          (get-keep-alive-fn ms))

        error-fn
        (fn [error-m]
          (when-let [ef error-fn]
            (try
              (ef error-m)
              true
              (catch Throwable t
                (timbre/error  t "Listener error-fn exception")
                false))))

        future_  (atom nil)
        broken?_ (atom false)
        break!
        (fn [t]
          (when (compare-and-set! broken?_ false true)
            (when-let [f @future_] (future-cancel f))
            (or
              (error-fn {:error :connection-broken :throwable t})
              (timbre/error "Listener connection broken"))))

        msg-polling-future
        (future-call
          (bound-fn []
            (loop []
              (when-not @broken?_

                (when-let [kaf keep-alive-fn] (kaf)) ; Record activity on conn
                (when-let [reply
                           (try
                             (protocol/get-unparsed-reply in {})

                             (catch java.net.SocketTimeoutException _
                               (if-let [ex (conns/-conn-error conn)]
                                 (break! ex)))

                             (catch Exception ex
                               (break! ex)))]

                  (try
                    (when-let [hf @handler-fn_]
                      (if swapping-handler?
                        (swap! state_ (fn [state] (hf reply  state)))
                        (do                       (hf reply @state_))))

                    (catch Throwable t
                      (or
                        (error-fn {:error :handler-exception :throwable t})
                        (timbre/error t "Listener handler exception")))))

                (recur)))))]

    (reset! future_ msg-polling-future)

    (protocol/with-context conn (body-fn)
      (protocol/execute-requests (not :get-replies) nil))

    (when-let [kaf keep-alive-fn]
      (let [f
            (bound-fn []
              (loop []
                (when-not @broken?_
                  (Thread/sleep (long keep-alive-ms))
                  (when (kaf) ; Should send keep-alive now?
                    (when-let [ex (conns/-conn-error conn)]
                      (break! ex)))

                  (recur))))]

        (doto (Thread. ^Runnable f)
          (.setDaemon true)
          (.start))))

    (Listener. conn handler-fn_ state_ msg-polling-future)))

(defmacro with-new-listener
  "Creates a persistent[1] connection to Redis server and a thread to listen for
  server messages on that connection.

  Incoming messages will be dispatched (with current listener state) to
  (fn handler [msg state]).

  Evaluates body within the context of the connection and returns a
  general-purpose Listener containing:
    1. The underlying persistent connection to facilitate `close-listener` and
       `with-open-listener`.
    2. An atom containing the function given to handle incoming server messages.
    3. An atom containing any other optional listener state.

  Useful for Pub/Sub, monitoring, etc.

  [1] You probably do *NOT* want a :timeout for your `conn-spec` here."
  [conn-spec handler initial-state & body]
  `(-with-new-listener
     {:conn-spec  ~conn-spec
      :init-state ~initial-state
      :handler    ~handler
      :body-fn    (fn [] ~@body)}))

(defmacro with-open-listener
  "Evaluates body within the context of given listener's pre-existing persistent
  connection."
  [listener & body]
  `(protocol/with-context (:connection ~listener) ~@body
     (protocol/execute-requests (not :get-replies) nil)))

(defn close-listener [listener]
  (conns/close-conn (:connection listener))
  (future-cancel (:future listener)))

(defn- -with-new-pubsub-listener
  "Implementation detail."
  [{:keys [conn-spec msg-handler-fns body-fn]}]
  (-with-new-listener
    {:conn-spec  (assoc conn-spec :pubsub-listener? true)
     :init-state msg-handler-fns ; {<chan-or-pattern> (fn [msg])}
     :body-fn    body-fn
     :handler-fn
     (fn [msg state]
       (let [[_msg-type chan-or-pattern _msg-content] msg]
         (when-let [hf (clojure.core/get msg-handler-fns chan-or-pattern)]
           (hf msg))))}))

(defmacro with-new-pubsub-listener
  "A wrapper for `with-new-listener`.

  Creates a persistent[1] connection to Redis server and a thread to
  handle messages published to channels that you subscribe to with
  `subscribe`/`psubscribe` calls in body.

  Handlers will receive messages of form:
    [<msg-type> <channel/pattern> <message-content>].

  (with-new-pubsub-listener
    {} ; Connection spec, as per `wcar` docstring [1]
    {\"channel1\" (fn [[type match content :as msg]] (prn \"Channel match: \" msg))
     \"user*\"    (fn [[type match content :as msg]] (prn \"Pattern match: \" msg))}
    (subscribe \"foobar\") ; Subscribe thread conn to \"foobar\" channel
    (psubscribe \"foo*\")  ; Subscribe thread conn to \"foo*\" channel pattern
   )

  Returns the Listener to allow manual closing and adjustments to
  message-handlers.

  [1] You probably do *NOT* want a :timeout for your `conn-spec` here."
  [conn-spec message-handlers & subscription-commands]
  `(-with-new-pubsub-listener
     {:conn-spec       ~conn-spec
      :msg-handler-fns ~message-handlers
      :body-fn         (fn [] ~@subscription-commands)}))

;;;; Atomic macro
;; The design here's a little on the heavy side; I'd suggest instead reaching
;; for the (newer) CAS tools or (if you need more flexibility), using a Lua
;; script or adhoc `multi`+`watch`+`exec` loop.

(defmacro atomic* "Alpha - subject to change. Low-level transaction util."
  [conn-opts max-cas-attempts on-success on-failure]
  `(let [conn-opts#  ~conn-opts
         max-idx#    ~max-cas-attempts
         _# (assert (>= max-idx# 1))
         prelude-result# (atom nil)
         exec-result#
         (wcar conn-opts# ; Hold 1 conn for all attempts
           (loop [idx# 1]
             (try (reset! prelude-result#
                    (protocol/with-replies :as-pipeline (do ~on-success)))
                  (catch Throwable t1# ; nb Throwable to catch assertions, etc.
                    ;; Always return conn to normal state:
                    (try (protocol/with-replies (discard))
                         (catch Throwable t2# nil) ; Don't mask t1#
                         )
                    (throw t1#)))
             (let [r# (protocol/with-replies (exec))]
               (if-not (nil? r#) ; => empty `multi` or watched key changed
                 ;; Was [] with < Carmine v3
                 (return r#)
                 (if (= idx# max-idx#)
                   (do ~on-failure)
                   (recur (inc idx#)))))))]

     [@prelude-result#
      (protocol/return-parsed-replies
        exec-result# (not :as-pipeline))]))

(defmacro atomic
  "Alpha - subject to change!
  Tool to ease Redis transactions for fun & profit. Wraps body in a `wcar`
  call that terminates with `exec`, cleans up reply, and supports automatic
  retry for failed optimistic locking.

  Body must contain a `multi` call and may contain calls to: `watch`, `unwatch`,
  `discard`, etc. Ref. http://redis.io/topics/transactions for more info.

  `return` and `parse` NOT supported after `multi` has been called.

  Like `swap!` fn, body may be called multiple times so should avoid impure or
  expensive ops.

  ;;; Atomically increment integer key without using INCR
  (atomic {} 100 ; Retry <= 100 times on failed optimistic lock, or throw ex

    (watch  :my-int-key) ; Watch key for changes
    (let [;; You can grab the value of the watched key using
          ;; `with-replies` (on the current connection), or
          ;; a nested `wcar` (on a new connection):
          curr-val (or (as-long (with-replies (get :my-int-key))) 0)]

      (return curr-val)

      (multi) ; Start the transaction
        (set :my-int-key (inc curr-val))
        (get :my-int-key)
      ))
  => [[\"OK\" nil \"OK\" \"QUEUED\" \"QUEUED\"] ; Prelude replies
      [\"OK\" \"1\"] ; Transaction replies (`exec` reply)
      ]

  See also `lua` as alternative way to get transactional behaviour."
  [conn-opts max-cas-attempts & body]
  `(atomic* ~conn-opts ~max-cas-attempts (do ~@body)
     (throw (ex-info (format "`atomic` failed after %s attempt(s)"
                       ~max-cas-attempts)
              {:nattempts ~max-cas-attempts}))))

(comment
  ;; Error before exec (=> syntax, etc.)
  (wcar {} (multi) (redis-call [:invalid]) (ping) (exec))
  ;; ["OK" #<Exception: ERR unknown command 'INVALID'> "QUEUED"
  ;;  #<Exception: EXECABORT Transaction discarded because of previous errors.>]

  ;; Error during exec (=> datatype, etc.)
  (wcar {} (set "aa" "string") (multi) (ping) (incr "aa") (ping) (exec))
  ;; ["OK" "OK" "QUEUED" "QUEUED" "QUEUED"
  ;;  ["PONG" #<Exception: ERR value is not an integer or out of range> "PONG"]]

  (wcar {} (multi) (ping) (discard)) ; ["OK" "QUEUED" "OK"]
  (wcar {} (multi) (ping) (discard) (exec))
  ;; ["OK" "QUEUED" "OK" #<Exception: ERR EXEC without MULTI>]

  (wcar {} (watch "aa") (set "aa" "string") (multi) (ping) (exec))
  ;; ["OK" "OK" "OK" "QUEUED" []]  ; Old reply fn
  ;; ["OK" "OK" "OK" "QUEUED" nil] ; New reply fn

  (wcar {} (watch "aa") (set "aa" "string") (unwatch) (multi) (ping) (exec))
  ;; ["OK" "OK" "OK" "OK" "QUEUED" ["PONG"]]

  (wcar {} (multi) (multi))
  ;; ["OK" #<Exception java.lang.Exception: ERR MULTI calls can not be nested>]

  (atomic {} 100 (/ 5 0)) ; Divide by zero
  )

(comment
  (defn swap "`multi`-based `swap`"
    [k f & [nmax-attempts abort-val]]
    (loop [idx 1]
      ;; (println idx)
      (parse-suppress (watch k))
      (let [[old-val ex] (parse nil (with-replies (get k) (exists k)))
            nx?          (= ex 0)
            [new-val return-val] (enc/swapped-vec (f old-val nx?))
            cas-success?
            (case new-val
              :swap/abort  (do (unwatch)         true)
              :swap/delete (do (unwatch) (del k) true)
              (parse nil
                (with-replies
                  (parse-suppress (multi) (set k new-val))
                  (exec))))]

        (if cas-success?
          (return return-val)
          (if (or (nil? nmax-attempts) (< idx (long nmax-attempts)))
            (recur (inc idx))
            (return abort-val)))))))

;;;; CAS tools (experimental!)
;; Working around the lack of simple CAS in Redis core, Ref http://goo.gl/M4Phx8

(defn- prep-cas-old-val [x]
  (let [^bytes bs (protocol/byte-str x)
        ;; Don't bother with sha when actual value would be shorter:
        ?sha      (when (> (alength bs) 40) (sha1-ba bs))]
    [?sha (raw bs)]))

(comment (enc/qb 1000 (prep-cas-old-val "hello there")))

(let [script (enc/have (enc/slurp-resource "taoensso/carmine/lua/cas-set.lua"))]
  (defn compare-and-set "Experimental."
    ([k old-val new-val]
     (let [[?sha raw-bs] (prep-cas-old-val old-val)]
       (compare-and-set k raw-bs ?sha new-val)))

    ([k old-val ?sha new-val]
     (let [delete? (= new-val :cas/delete)]
       (lua script {:k k}
         {:old-?sha (or ?sha "")
          :old-?val (if ?sha "" old-val)
          :delete   (if delete? 1 0)
          :new-val  (if delete? "" new-val)})))))

(let [script (enc/have (enc/slurp-resource "taoensso/carmine/lua/cas-hset.lua"))]
  (defn compare-and-hset "Experimental."
    ([k field old-val new-val]
     (let [[?sha raw-bs] (prep-cas-old-val old-val)]
       (compare-and-hset k field raw-bs ?sha new-val)))

    ([k field old-val ?sha new-val]
     (let [delete? (= new-val :cas/delete)]
       (lua script {:k k}
         {:field    field
          :old-?sha (or ?sha "")
          :old-?val (if ?sha "" old-val)
          :delete   (if delete? 1 0)
          :new-val  (if delete? "" new-val)})))))

(comment
  (wcar {} (del "cas-k") (set "cas-k" 0) (compare-and-set "cas-k" 0 1))
  (wcar {} (compare-and-set "cas-k" 1 2))
  (wcar {} (get "cas-k"))

  (wcar {} (del "cas-k") (hset "cas-k" "field" 0) (compare-and-hset "cas-k" "field" 0 1))
  (wcar {} (compare-and-hset "cas-k" "field" 1 2))
  (wcar {} (hget "cas-k" "field")))

(def swap "Experimental."
  (let [cas-get
        (let [script (enc/have (enc/slurp-resource "taoensso/carmine/lua/cas-get.lua"))]
          (fn [k] (lua script {:k k} {})))]

    (fn [k f & [nmax-attempts abort-val]]
      (loop [idx 1]
        (let [[old-val ex sha]     (parse nil (with-replies (cas-get k)))
              nx?                  (= ex 0)
              ?sha                 (when-not (= sha "") sha)
              [new-val return-val] (enc/swapped-vec (f old-val nx?))
              cas-success?
              (case new-val
                :swap/abort true
                :swap/delete
                (if nx?
                  true
                  (= 1
                    (parse nil
                      (with-replies
                        (compare-and-set k old-val ?sha :cas/delete)))))

                (= 1
                  (parse nil
                    (with-replies
                      (if nx?
                        (setnx k new-val)
                        (compare-and-set k old-val ?sha new-val))))))]

          (if cas-success?
            (return return-val)
            (if (or (nil? nmax-attempts) (< idx (long nmax-attempts)))
              (recur (inc idx))
              (return abort-val))))))))

(def hswap "Experimental."
  (let [cas-hget
        (let [script (enc/have (enc/slurp-resource "taoensso/carmine/lua/cas-hget.lua"))]
          (fn [k field] (lua script {:k k} {:field field})))]

    (fn [k field f & [nmax-attempts abort-val]]
      (loop [idx 1]
        (let [[old-val ex sha]     (parse nil (with-replies (cas-hget k field)))
              nx?                  (= ex 0)
              ?sha                 (when-not (= sha "") sha)
              [new-val return-val] (enc/swapped-vec (f old-val nx?))
              cas-success?
              (case new-val
                :swap/abort true
                :swap/delete
                (if nx?
                  true
                  (= 1
                    (parse nil
                      (with-replies
                        (compare-and-hset k field old-val ?sha :cas/delete)))))

                (= 1
                  (parse nil
                    (with-replies
                      (if nx?
                        (hsetnx k field new-val)
                        (compare-and-hset k field old-val ?sha new-val))))))]

          (if cas-success?
            (return return-val)
            (if (or (nil? nmax-attempts) (< idx (long nmax-attempts)))
              (recur (inc idx))
              (return abort-val))))))))

(comment
  (enc/qb 100 (wcar {} (swap "swap-k" (fn [?old _] ?old))))
  (wcar {} (get  "swap-k"))
  (wcar {} (swap "swap-k" (fn [?old _] :swap/delete))))

;;;;

(def hmsetnx "Experimental."
  (let [script (enc/have (enc/slurp-resource "taoensso/carmine/lua/hmsetnx.lua"))]
    (fn [key field value & more]
      (-eval* script 1 (into [key field value] more)))))

(comment
  (wcar {} (hgetall "foo"))
  (wcar {} (hmsetnx "foo" "f1" "v1" "f2" "v2")))

;;;;

(defn reduce-scan
  "For use with `scan`, `hscan`, `zscan`, etc. Takes:
    - (fn rf      [acc scan-result]) -> next accumulator
    - (fn scan-fn [cursor]) -> next scan result"
  ([rf          scan-fn] (reduce-scan rf nil scan-fn))
  ([rf acc-init scan-fn]
   (loop [cursor "0" acc acc-init]
     (let [[next-cursor in] (scan-fn cursor)]
       (if (= next-cursor "0")
         (rf acc in)
         (let [result (rf acc in)]
           (if (reduced? result)
             @result
             (recur next-cursor result))))))))

(comment
  (reduce-scan (fn rf [acc in] (into acc in))
    [] (fn scan-fn [cursor] (wcar {} (scan cursor)))))

(defn reduce-hscan
  "Experimental. Like `reduce-scan` but:
    - `rf` is (fn [acc k v]), as in `reduce-kv`.
    - `rf` will never be called with the same key twice
      (i.e. automatically de-duplicates elements)."
  ([rf          scan-fn] (reduce-hscan rf nil scan-fn))
  ([rf acc-init scan-fn]
   (let [keys-seen_ (volatile! (transient #{}))]

     (reduce-scan
       (fn wrapped-rf [acc kvs]
         (enc/reduce-kvs
           (fn [acc k v]
             (if (contains? @keys-seen_ k)
               acc
               (do
                 (vswap! keys-seen_ conj! k)
                 (enc/convey-reduced (rf acc k v)))))
           acc
           kvs))

       acc-init scan-fn))))

(comment
  (wcar {} (del "test:foo"))
  (wcar {} (doseq [i (range 1e4)] (hset "test:foo" (str "k" i) (str "v" i))))
  (count
    (reduce-hscan
      (fn rf [acc k v]
        (if #_true false
          (reduced (assoc acc k v))
          (do      (assoc acc k v))))
      {}
      (fn [cursor] (wcar {} (hscan "test:foo" cursor))))))

;;;; Deprecated

(enc/deprecated
  (def as-long   "DEPRECATED: Use `as-int` instead."   as-int)
  (def as-double "DEPRECATED: Use `as-float` instead." as-float)
  (defmacro parse-long "DEPRECATED: Use `parse-int` instead."
    [& body] `(parse as-long   ~@body))
  (defmacro parse-double "DEPRECATED: Use `parse-float` instead."
    [& body] `(parse as-double ~@body))

  (def hash-script "DEPRECATED: Use `script-hash` instead." script-hash)

  (defn kname "DEPRECATED: Use `key` instead. `key` does not filter nil parts."
    [& parts] (apply key (filter identity parts)))

  (comment (kname :foo/bar :baz "qux" nil 10))

  (def serialize "DEPRECATED: Use `freeze` instead." freeze)
  (def preserve  "DEPRECATED: Use `freeze` instead." freeze)
  (def remember  "DEPRECATED: Use `return` instead." return)
  (def ^:macro skip-replies "DEPRECATED: Use `with-replies` instead." #'with-replies)
  (def ^:macro with-reply   "DEPRECATED: Use `with-replies` instead." #'with-replies)
  (def ^:macro with-parser  "DEPRECATED: Use `parse` instead."        #'parse)

  (defn lua-script "DEPRECATED: Use `lua` instead." [& args] (apply lua args))

  (defn make-keyfn "DEPRECATED: Use `kname` instead."
    [& prefix-parts]
    (let [prefix (when (seq prefix-parts) (str (apply kname prefix-parts) ":"))]
      (fn [& parts] (str prefix (apply kname parts)))))

  (defn make-conn-pool "DEPRECATED: Use `wcar` instead."
    [& opts] (conns/conn-pool (apply hash-map opts)))

  (defn make-conn-spec "DEPRECATED: Use `wcar` instead."
    [& opts] (conns/conn-spec (apply hash-map opts)))

  (defmacro with-conn "DEPRECATED: Use `wcar` instead."
    [connection-pool connection-spec & body]
    `(wcar {:pool ~connection-pool :spec ~connection-spec} ~@body))

  (defmacro atomically "DEPRECATED: Use `atomic` instead."
    [watch-keys & body]
    `(do
       (with-replies ; discard "OK" and "QUEUED" replies
         (when-let [wk# (seq ~watch-keys)] (apply watch wk#))
         (multi)
         ~@body)

       ;; Body discards will result in an (exec) exception:
       (parse (parser-comp protocol/*parser*
                (-> #(if (instance? Exception %) [] %)
                  (with-meta {:parse-exceptions? true})))
         (exec))))

  (defmacro ensure-atomically "DEPRECATED: Use `atomic` instead."
    [{:keys [max-tries] :or {max-tries 100}}
     watch-keys & body]
    `(let [watch-keys# ~watch-keys
           max-idx#    ~max-tries]
       (loop [idx# 0]
         (let [result# (with-replies (atomically watch-keys# ~@body))]
           (if-not (nil? result#) ; Was [] with < Carmine v3
             (remember result#)
             (if (= idx# max-idx#)
               (throw
                 (ex-info (format "`ensure-atomically` failed after %s attempt(s)"
                            idx#)
                   {:nattempts idx#}))
               (recur (inc idx#))))))))

  (defn hmget* "DEPRECATED: Use `parse-map` instead."
    [key field & more]
    (let [fields (cons field more)
          inner-parser (when-let [p protocol/*parser*] #(mapv p %))
          outer-parser #(zipmap fields %)]
      (->> (apply hmget key fields)
        (parse (parser-comp outer-parser inner-parser)))))

  (defn hgetall* "DEPRECATED: Use `parse-map` instead."
    [key & [keywordize?]]
    (let [inner-parser (when-let [p protocol/*parser*] #(mapv p %))
          outer-parser (if keywordize?
                         #(enc/map-keys keyword (apply hash-map %))
                         #(apply hash-map %))]
      (->> (hgetall key)
        (parse (parser-comp outer-parser inner-parser)))))

  (comment
    (wcar {} (hgetall* "hkey"))
    (wcar {} (parse (fn [kvs] (enc/reduce-kvs assoc {} kvs))
               (hgetall "hkey")))

    (wcar {} (hmset* "hkey" {:a "aval" :b "bval" :c "cval"}))
    (wcar {} (hmset* "hkey" {})) ; ex
    (wcar {} (hmget* "hkey" :a :b))
    (wcar {} (parse str/upper-case (hmget* "hkey" :a :b)))
    (wcar {} (hmget* "hkey" "a" "b"))
    (wcar {} (hgetall* "hkey"))
    (wcar {} (parse str/upper-case (hgetall* "hkey"))))

  (defn as-map [x] (enc/as-map x))
  (defmacro parse-map [form & [kf vf]]
    `(parse #(enc/as-map % ~kf ~vf) ~form)))
