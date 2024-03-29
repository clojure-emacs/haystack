(ns haystack.analyzer
  "Cause and stacktrace analysis for exceptions"
  {:added "0.1.0"
   :author "Jeff Valk"}
  (:require
   [clojure.pprint :as pp]
   [clojure.repl :as repl]
   [clojure.set :as set]
   [clojure.string :as str]
   [haystack.parser.clojure.throwable :as throwable]
   [orchard.info :as info]
   [orchard.java :as java]
   [orchard.java.resource :as resource]
   [orchard.namespace :as namespace])
  (:import
   (java.io StringWriter)))

;;; ## Stacktraces

(defn pprint
  "A simple wrapper around `clojure.pprint/write`."
  {:added "0.1.0"}
  ([value writer]
   (pprint value writer {}))
  ([value writer options]
   (apply pp/write value (mapcat identity (assoc options :stream writer)))))

;; Java stacktraces don't expose column number.
(defn- stack-frame
  "Return a map describing the stack frame."
  [frame]
  (let [[class method file line] frame]
    (when (and class method file line)
      {:name   (str (name class) "/" (name method))
       :file   file
       :line   line
       :class  (name class)
       :method (name method)})))

(defn- flag-frame
  "Update frame's flags vector to include the new flag."
  [frame flag]
  (update frame :flags (comp set conj) flag))

(defn- source-path
  "Return the relative source path for the class without extension."
  [class]
  (-> (str/replace (str class) #"\$.*" "")
      (str/replace "." "/")))

(defn- path->url
  "Return a url for the path, either relative to classpath, or absolute."
  [path]
  (or (info/file-path path) (second (resource/resource-path-tuple path))))

(defn- frame->url
  "Return a `java.net.URL` to the file referenced in the frame, if possible.
  Useful for handling clojure vars which may not exist. Uncomprehensive list of
  reasons for this:
  * Failed refresh
  * Top-level evaluation"
  [frame]
  (some-> (:name frame)
          source-path
          (str "." (last (.split ^String (:file frame)
                                 "\\.")))
          path->url
          str))

(defn- analyze-fn
  "Add namespace, fn, and var to the frame map when the source is a Clojure
  function."
  [{:keys [type class method] :as frame}]
  (if (or (= :clj type)
          (= :cljc type))
    (let [[ns fn & anons] (-> (repl/demunge class)
                              (str/replace #"--\d+" "")
                              (str/split #"/"))
          fn (or fn method)] ; protocol functions are not munged
      (binding [java/*analyze-sources* false]
        (assoc frame
               :ns  ns
               :fn  (str/join "/" (cons fn anons))
               :var (str ns "/" fn)
               ;; Full file path
               :file-url (or (some-> (info/info* {:ns 'user :sym (symbol ns fn)})
                                     :file
                                     path->url
                                     str)
                             (str (frame->url frame))))))
    (assoc frame :file-url (try
                             (binding [java/*analyze-sources* false]
                               (some->> frame
                                        :name
                                        symbol
                                        (java/resolve-symbol 'user)
                                        :file
                                        path->url
                                        str))
                             (catch Throwable _
                               ;; `java/resolve-symbol` can throw exceptions when the underlying class cannot be loaded.
                               ;; See https://github.com/clojure-emacs/haystack/issues/9
                               nil)))))

(defn- analyze-file
  "Associate the file type (extension) of the source file to the frame map, and
  add it as a flag. If the name is `NO_SOURCE_FILE`, type `clj` is assumed."
  [{:keys [file] :as frame}]
  (let [type (keyword
              (cond (nil? file)                "unknown"
                    (= file "NO_SOURCE_FILE")  "clj"
                    (neg? (.indexOf ^String file ".")) "unknown"
                    :else (last (.split ^String file "\\."))))]
    (-> frame
        (assoc :type type)
        (flag-frame type))))

(defn- flag-repl
  "Flag the frame if its source is a REPL eval."
  [{:keys [file] :as frame}]
  (if (and file
           (or (= file "NO_SOURCE_FILE")
               (.startsWith ^String file "form-init")))
    (flag-frame frame :repl)
    frame))

(def ^:private tooling-frame-re
  #"^clojure\.lang\.LazySeq|^clojure\.lang\.Var|^clojure\.lang\.MultiFn|^clojure\.lang\.AFn|^clojure\.lang\.RestFn|^clojure\.lang\.RT|clojure\.lang\.Compiler|^nrepl\.|^cider\.|^refactor-nrepl\.|^shadow.cljs\.|^clojure\.core/eval|^clojure\.core/apply|^clojure\.core/with-bindings|^clojure\.core\.protocols|^clojure\.core\.map/fn|^clojure\.core/binding-conveyor-fn|^clojure\.main/repl")

(defn- tooling-frame-name? [frame-name last?]
  (let [demunged (repl/demunge frame-name)]
    (boolean (or (re-find tooling-frame-re demunged)
                 (and last?
                      ;; Everything runs from a Thread, so this frame, if at root, is irrelevant.
                      ;; However one can invoke this method 'by hand', which is why we also observe `last?`.
                      (re-find #"^java\.lang\.Thread/run|^java\.util\.concurrent" demunged))))))

(defn- flag-tooling
  "Given a collection of stack `frames`, marks the 'tooling' ones as such.

  A 'tooling' frame is one that generally represents Clojure, JVM, nREPL or CIDER internals,
  and that is therefore not relevant to application-level code."
  [frames]
  (let [results (volatile! {})]
    (->> frames
         reverse
         (into []
               (map-indexed (fn [^long i {frame-name :name :as frame}]
                              (let [;; A frame is considered the last if it's literally the last one,
                                    ;; or if the previous element was marked as tooling.
                                    last? (or (zero? i)
                                              (some-> @results (get (dec i))))
                                    tooling? (some-> frame-name (tooling-frame-name? last?))]
                                (vswap! results assoc i tooling?)
                                (cond-> frame
                                  tooling?
                                  (flag-frame :tooling))))))
         reverse
         vec)))

(defn directory-namespaces
  "Looks for all namespaces inside of directories on the class
  path, ignoring jars.

  It's a defn because this set is always subject to change.

  NOTE: depending on the use case, you might want to filter out
  namespaces such as `user` which while belong to the project,
  don't share a common naming scheme with the other namespaces."
  {:added "0.1.0"}
  []
  (into #{} (namespace/project-namespaces)))

(defn- ns-common-prefix* [namespaces]
  (let [common
        (try
          (->> namespaces
               (pmap (fn [ns-sym]
                       (let [segments (-> ns-sym
                                          str
                                          (str/split #"\."))]
                         ;; remove single-segment namespaces
                         ;; (such as `dev`, `test`, `test-runner`)
                         ;; that would break the commonality:
                         (when (-> segments count (> 1))
                           segments))))
               (filter identity)
               (reduce (fn [prev curr]
                         (if (= ::placeholder prev)
                           curr
                           (let [found-commonality
                                 (reduce-kv (fn [result k v]
                                              (if (= (get prev k)
                                                     (get curr k))
                                                (conj result v)
                                                (reduced result)))
                                            []
                                            prev)]
                             (if (seq found-commonality)
                               found-commonality
                               (reduced :missing)))))
                       ::placeholder))
          (catch Throwable _e :error))]
    (condp = common
      ::placeholder
      {:valid false :common :missing}

      :missing
      {:valid false :common :missing}

      :error
      {:valid false :common :error}

      {:valid true :common (str/join "." common)})))

(def ^{:added "0.1.0"} ns-common-prefix
  "In order to match more namespaces, we look for a common namespace
  prefix across the ones we have identified."
  (delay (ns-common-prefix* (directory-namespaces))))

(defn- flag-project
  "Flag the frame if it is from the users project. From a users
  project means that the namespace is one we have identified or it
  begins with the identified common prefix."
  [namespaces {:keys [ns] :as frame}]
  (if (and ns
           (or (contains? namespaces (symbol ns))
               (when (:valid @ns-common-prefix)
                 (.startsWith ^String ns (:common @ns-common-prefix)))))
    (flag-frame frame :project)
    frame))

(defn- flag-duplicates
  "Where a parent and child frame represent substantially the same source
  location, flag the parent as a duplicate."
  [frames]
  (into [(first frames)]
        (map (fn [[frame child]]
               (if (or (= (:name frame) (:name child))
                       (and (= (:file frame) (:file child))
                            (= (:line frame) (:line child))))
                 (flag-frame frame :dup)
                 frame)))
        (mapv vector (rest frames) frames)))

(defn analyze-frame
  "Return the stacktrace as a sequence of maps, each describing a stack frame."
  {:added "0.1.0"}
  [namespaces frame]
  (let [f (comp flag-repl (partial flag-project namespaces) analyze-fn analyze-file stack-frame)]
    (f frame)))

;;; ## Causes

(defn- relative-path
  "If the path is under the project root, return the relative path; otherwise
  return the original path."
  [path]
  (let [dir (str (System/getProperty "user.dir")
                 (System/getProperty "file.separator"))]
    (str/replace-first path dir "")))

(defn- extract-location
  "If the cause is a compiler exception, extract the useful location information
  from its message or from `:location` if provided.
  Include relative path for simpler reporting."
  [{:keys [class message location] :as cause}]
  (if (= class "clojure.lang.Compiler$CompilerException")
    (if (seq location)
      (assoc cause
             :file (:clojure.error/source location)
             :file-url (some-> (:clojure.error/source location)
                               path->url
                               str)
             :path (relative-path (:clojure.error/source location))
             :line (:clojure.error/line location)
             :column (:clojure.error/column location))
      (let [[_ msg file line column]
            (re-find #"(.*?), compiling:\((.*):(\d+):(\d+)\)" message)]
        (assoc cause
               :message msg
               :file file
               :file-url (some-> file
                                 path->url
                                 str)
               :path (relative-path file)
               :line (Integer/parseInt line)
               :column (Integer/parseInt column))))
    cause))

;; CLJS REPLs use :repl-env to store huge amounts of analyzer/compiler state
(def ^:private ex-data-blacklist
  #{:repl-env})

(defn- filtered-ex-data
  "Filter keys from the exception `data` which are
  blacklisted (generally for containing data not intended for reading
  by a human)."
  [data]
  (when data
    (into {} (filter (comp (complement ex-data-blacklist) key) data))))

(def ^{:added "0.1.0"} spec-abbrev
  (delay (if (try (require 'clojure.spec) true
                  (catch Throwable _ false))
           (resolve 'clojure.spec/abbrev)
           (if (try (require 'clojure.spec.alpha) true
                    (catch Throwable _ false))
             (resolve 'clojure.spec.alpha/abbrev)
             #'identity))))

(defn- prepare-spec-data
  "Prepare spec problems for display in user stacktraces.
  Take in a map `ed` as returned by `clojure.spec/explain-data` and return a map
  of pretty printed problems. The content of the returned map is modeled after
  `clojure.spec/explain-printer`."
  [ed pprint-str]
  (let [problems (sort-by #(count (:path %))
                          (or (:clojure.spec/problems ed)
                              (:clojure.spec.alpha/problems ed)))]
    {:spec (pr-str (or (:clojure.spec/spec ed)
                       (:clojure.spec.alpha/spec ed)))
     :value (pprint-str (or (:clojure.spec/value ed)
                            (:clojure.spec.alpha/value ed)))
     :problems (for [{:keys [in val
                             pred reason
                             via path]
                      :as prob} problems]
                 (->> {:in (when (seq in) (pr-str in))
                       :val (pprint-str val)
                       :predicate (pr-str (@spec-abbrev pred))
                       :reason reason
                       :spec (when (seq via) (pr-str (last via)))
                       :at (when (seq path) (pr-str path))
                       :extra (let [extras (->> #{:in :val :pred :reason :via :path
                                                  :clojure.spec/failure
                                                  :clojure.spec.alpha/failure}
                                                (set/difference (set (keys prob)))
                                                (select-keys prob))]
                                (when (seq extras)
                                  (pprint-str extras)))}
                      (filter clojure.core/val)
                      (into {})))}))

(defn- analyze-stacktrace-data
  "Return the stacktrace as a sequence of maps, each describing a stack frame."
  [trace]
  (when (seq trace)
    (let [namespaces (directory-namespaces)]
      (-> (pmap (partial analyze-frame namespaces) trace)
          (flag-duplicates)
          (flag-tooling)))))

(defn- compile-like-exception?
  "'Compile-like' exceptions are those that happen at runtime
  (and therefore never include a `:phase`) which however,
  represent code that cannot possibly work,
  and therefore are a 'compile-like' exception (i.e. a linter could have caught them)."
  [{cause-type    :type
    ^String
    cause-message :message}]
  (and (= cause-type 'java.lang.IllegalArgumentException)
       (or (some-> cause-message (.startsWith "No matching field"))
           (some-> cause-message (.startsWith "No matching method")))))

(defn- analyze-cause
  "Analyze the `cause-data` of an exception, in `Throwable->map` format."
  [cause-data print-fn]
  (let [pprint-str #(let [writer (StringWriter.)]
                      (print-fn % writer)
                      (str writer))
        phase (-> cause-data :data :clojure.error/phase)
        m {:class (name (:type cause-data))
           :phase phase
           :compile-like (pr-str (boolean (and (not phase)
                                               (compile-like-exception? cause-data))))
           :message (:message cause-data)
           :stacktrace (analyze-stacktrace-data
                        (cond (seq (:trace cause-data))
                              (:trace cause-data)
                              (:at cause-data)
                              [(:at cause-data)]))}]
    (if-let [data (filtered-ex-data (:data cause-data))]
      (if (or (:clojure.spec/failure data)
              (:clojure.spec.alpha/failure data))
        (assoc m
               :message "Spec assertion failed."
               :spec (prepare-spec-data data pprint-str))
        (-> m
            (assoc :data (pprint-str data)
                   :location (select-keys data [:clojure.error/line
                                                :clojure.error/column
                                                :clojure.error/phase
                                                :clojure.error/source
                                                :clojure.error/symbol]))))
      m)))

(defn- analyze-causes
  "Analyze the cause chain of the `exception-data` in `Throwable->map` format."
  [exception-data print-fn]
  (let [causes (vec (:via exception-data))]
    (into [] (comp (map #(analyze-cause % print-fn))
                   (map extract-location))
          (cond-> causes
            (not (:trace (first causes)))
            (assoc-in [0 :trace] (:trace exception-data))))))

(defn analyze
  "Return the analyzed cause chain for `exception` beginning with the
  thrown exception. `exception` can be an instance of `Throwable` or a
  map in the same format as `Throwable->map`. For `ex-info`
  exceptions, the response contains a `:data` slot with the pretty
  printed data. For clojure.spec asserts, the `:spec` slot contains a
  map of pretty printed components describing spec failures."
  {:added "0.1.0"}
  ([exception]
   (analyze exception pprint))
  ([exception print-fn]
   (cond (instance? Throwable exception)
         (analyze-causes (throwable/Throwable->map exception) print-fn)
         (and (map? exception) (:trace exception))
         (analyze-causes exception print-fn))))
