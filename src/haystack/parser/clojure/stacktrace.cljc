(ns haystack.parser.clojure.stacktrace
  "Parser for stacktraces in the `clojure.stacktrace` format."
  {:added "0.1.0"
   :author "r0man"}
  (:require [clojure.edn :as edn]
            [haystack.parser.util :as util]
            [instaparse.core #?(:clj :refer :cljs :refer-macros) [defparser]]))

(def ^:private stacktrace-start-regex
  "The regular expression matching the start of a `clojure.stacktrace`
  formatted stacktrace."
  #"(?s)(([^\s]+):\s([^\n\r]+).*at+)")

(defparser ^:private parser
  "S = <whitespace*> stacktrace <garbage>?

   <stacktrace> = (exception <newline> causes)
   <garbage> = (newline | whitespace | #'.')+

   exception = type <double-colon> <whitespace> message (<newline> data)? <newline> <whitespace> <at> trace
   type = class
   message = #'.'*
   data = lbrace #'.'* rbrace

   <causes> = Epsilon | cause causes
   <cause> = <caused-by> <whitespace> exception <newline>

   trace = frame frames
   <frames> = Epsilon | <newline> frame frames
   <frame> = <whitespace> call
   call = invocation <whitespace> <lparen> file <double-colon> number <rparen>
   <invocation> = class (<dot> | <slash>) method

   at = 'at'
   caused-by = 'Caused by:'
   class = simple-name (dot simple-name)*
   file = simple-name (dot simple-name)
   method = simple-name
   more = 'more'

   <digit> = #'[0-9]'
   <dot> = '.'
   <slash> = '/'
   <eof> = <#'\\Z'>

   double-colon = ':'
   newline = #'[\\n\\r]' | <eof>
   number = '-'? digit+
   lparen = '('
   rparen = ')'
   <lbrace> = '{'
   <rbrace> = '}'
   simple-name = #'[a-zA-Z0-9_$/-]'+
   whitespace = #'[^\\S\\r\\n]+'")

(defn- transform-data
  "Transform a :data node into the `Throwable->map` format."
  [& data]
  (when-let [content (util/safe-read-edn (apply str data))]
    [:data content]))

(defn- transform-stacktrace
  "Transform the :S node into the `Throwable->map` format."
  [& causes]
  (let [root (last causes)]
    {:cause (:message root)
     :data (:data root)
     :trace (:trace root)
     :via (mapv (fn [{:keys [data type message trace]}]
                  (cond-> {:at (first trace)
                           :message message
                           :type type
                           :trace trace}
                    data (assoc :data data)))
                causes)}))

(defn- transform-exception
  "Transform a :exception node into the `Throwable->map` format."
  [& exceptions]
  (reduce (fn [m [k v]] (assoc m k v)) {} exceptions))

(def ^:private transform-file
  "Transform a :file node into the `Throwable->map` format."
  (partial apply str))

(def ^:private transform-class
  "Transform a :class node into the `Throwable->map` format."
  (comp symbol (partial apply str)))

(defn- transform-message
  "Transform a :message node into the `Throwable->map` format."
  [& content]
  [:message (apply str content)])

(def ^:private transform-number
  "Transform a :number node into the `Throwable->map` format."
  (comp edn/read-string (partial apply str)))

(defn- transform-trace
  "Transform a :trace node into the `Throwable->map` format."
  [& frames]
  [:trace (vec frames)])

(def ^:private transformations
  "The Instaparse `clojure.stacktrace` transformations."
  {:S transform-stacktrace
   :call vector
   :class transform-class
   :data transform-data
   :exception transform-exception
   :file transform-file
   :message transform-message
   :method symbol
   :number transform-number
   :simple-name str
   :simple-symbol symbol
   :trace transform-trace})

(defn parse-stacktrace
  "Parse `input` as a stacktrace in `clojure.stacktrace` format."
  {:added "0.1.0"}
  [input]
  (util/parse-stacktrace parser transformations :clojure.stacktrace stacktrace-start-regex input))
