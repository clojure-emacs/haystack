(ns haystack.test.runner
  (:require [doo.runner :refer-macros [doo-tests]]
            [haystack.parser-test]
            [haystack.parser.aviso-test]
            [haystack.parser.clojure.repl-test]
            [haystack.parser.clojure.stacktrace-test]
            [haystack.parser.clojure.tagged-literal-test]
            [haystack.parser.clojure.throwable-test]
            [haystack.parser.java-test]
            [haystack.parser.util-test]))

(doo-tests
 'haystack.parser-test
 'haystack.parser.aviso-test
 'haystack.parser.clojure.repl-test
 'haystack.parser.clojure.stacktrace-test
 'haystack.parser.clojure.tagged-literal-test
 'haystack.parser.clojure.throwable-test
 'haystack.parser.java-test
 'haystack.parser.util-test)
