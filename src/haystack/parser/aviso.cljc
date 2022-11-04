(ns haystack.parser.aviso
  "Parser for stacktraces in the Aviso format."
  {:added "0.1.0"
   :author "r0man"}
  (:require [haystack.parser.util :as util]
            [instaparse.core #?(:clj :refer :cljs :refer-macros) [defparser]]))

(def ^:private stacktrace-start-regex
  "The regular expression matching the start of an Aviso stacktrace."
  #"(?s)([^\s]+\s+[^\s]+:\s+\d+[\s])")

(defparser ^:private parser
  "S = <whitespace*> stacktrace <garbage>?

   <stacktrace> = (traces causes)
   <garbage> = (newline | whitespace | #'.')+

   traces = trace-seq
   <trace-seq> = trace Epsilon | trace trace-seq
   trace = frames

   <frames> = Epsilon | <more> | (frame frames)
   frame = <whitespace>? call <whitespace> file frame-location? <newline>
   <frame-location> = <double-colon> <whitespace> number (<whitespace> frame-repeats)?
   <frame-repeats> = <lparen> <'repeats'> <whitespace> times <rparen>

   <call> = call-clojure / call-java
   <call-clojure> = class <slash> method
   <call-java> = class <dot> method

   causes = cause-seq
   <cause-seq> = cause Epsilon | cause cause-seq
   cause = type <double-colon> <whitespace> message <newline> data
   message = #'.'+

   data = data-block
   <data-block> = Epsilon | data-entry data-block
   <data-entry> = <whitespace> data-key <double-colon> <whitespace> data-value <newline>
   data-key = simple-name
   data-value = #'.'+

   type = class
   class = (simple-name (dot simple-name)+)
   file = 'REPL Input' | (simple-name (dot simple-name))
   method = simple-name (slash simple-name)*
   more = <whitespace> <dot> <dot> <dot> <newline>
   <times> = number <whitespace> <'time'> <'s'>?

   <digit> = #'[0-9]'
   <dot> = '.'
   <eof> = <#'\\Z'>
   <letter> = #'[a-zA-Z]'
   <slash> = '/'

   double-colon = ':'
   newline = #'[\\n\\r]' | <eof>
   number = '-'? digit+
   lparen = '('
   rparen = ')'
   simple-name = #'[a-zA-Z0-9_$*-]'+
   whitespace = #'[^\\S\\r\\n]+'")

(def ^:private transform-class
  "Transform a :class node into the `Throwable->map` format."
  (comp symbol (partial apply str)))

(defn- transform-cause
  "Transform a :cause node into the `Throwable->map` format."
  [& args]
  (into {} args))

(defn- transform-data
  "Transform a :data node into the `Throwable->map` format."
  [& args]
  [:data (some->> args (apply hash-map))])

(defn- transform-exception
  "Transform a :exception node into the `Throwable->map` format."
  [& args]
  (into {} args))

(def ^:private transform-file
  "Transform a :file node into the `Throwable->map` format."
  (partial apply str))

(def ^:private transform-number
  "Transform a :number node into the `Throwable->map` format."
  (comp util/safe-read-edn (partial apply str)))

(def ^:private transform-method
  "Transform a :method node into the `Throwable->map` format."
  (comp symbol (partial apply str)))

(defn- transform-message
  "Transform a :message node into the `Throwable->map` format."
  [& content]
  [:message (apply str content)])

(def ^:private transform-stacktrace
  "Transform a stacktrace node into the `Throwable->map` format."
  (fn [[_ & traces] [_ & causes]]
    (let [causes (reverse causes)
          traces (remove empty? traces)
          root   (last causes)]
      {:cause (:message root)
       :data  (:data root)
       :trace (vec (reverse (apply concat traces)))
       :via   (vec causes)})))

(defn- transform-trace
  "Transform a :trace node into the `Throwable->map` format."
  [& frames]
  (vec (mapcat (fn [frame]
                 (if-let [n (nth frame 4 nil)]
                   (repeat n (vec (butlast frame)))
                   [frame]))
               frames)))

(def ^:private transformations
  "The Aviso stacktrace transformations."
  {:S transform-stacktrace
   :cause transform-cause
   :class transform-class
   :data transform-data
   :data-key keyword
   :data-value (comp util/safe-read-edn (partial apply str))
   :exception transform-exception
   :file transform-file
   :frame vector
   :message transform-message
   :method transform-method
   :number transform-number
   :simple-name str
   :simple-symbol symbol
   :trace transform-trace})

(defn parse-stacktrace
  "Parse `input` as a stacktrace in the Aviso format."
  {:added "0.1.0"}
  [input]
  (util/parse-stacktrace parser transformations :aviso stacktrace-start-regex input))
