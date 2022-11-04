(ns haystack.parser.test
  (:require
   #?(:clj [clojure.java.io :as io])
   [clojure.walk :as walk]))

(def exceptions
  "The fixture exceptions."
  #{:boom :divide-by-zero :short})

(def formats
  "The fixture formats."
  #{:aviso :clojure.repl :clojure.stacktrace :clojure.tagged-literal :java})

(def fixtures
  "The fixture names as keywords."
  (for [exception exceptions, format formats]
    (keyword (str (name exception) "." (name format)))))

#?(:clj (defn fixture-path
          "Return the fixture path for the parser `resource`."
          [resource]
          (str (io/file "test-resources" "haystack" "parser" (str (name resource) ".txt")))))

#?(:clj (defmacro fixture
          "Read the fixture `name`."
          [name]
          (some-> name fixture-path slurp)))

(defn stacktrace-element?
  "Return true if `element` is a stacktrace element, otherwise false."
  [element]
  (let [[class method file] element]
    (and (symbol? class)
         (symbol? method)
         (string? file))))

(defn- pattern?
  "Return true if `x` is a regular expression, otherwise false."
  [x]
  (instance? #?(:clj java.util.regex.Pattern :cljs js/RegExp) x))

(defn stringify-regexp
  "Post-walk `x` and replace all instances of `java.util.regex.Pattern`
  in it by applying `clojure.core/str` on them."
  [x]
  (cond->> (walk/postwalk #(if (pattern? %) (str %) %) x)
    (map? x) (into {})))
