(ns haystack.parser-test
  (:require
   #?(:clj [clojure.string :as str])
   [clojure.test :refer [deftest is testing]]
   [haystack.parser :as parser]
   [haystack.parser.test :as test #?(:clj :refer :cljs :refer-macros) [fixture]]))

(def fixtures
  {:boom.aviso.full (fixture :boom.aviso.full)
   :boom.aviso (fixture :boom.aviso)
   :boom.clojure.repl (fixture :boom.clojure.repl)
   :boom.clojure.stacktrace (fixture :boom.clojure.stacktrace)
   :boom.clojure.tagged-literal (fixture :boom.clojure.tagged-literal)
   :boom.java (fixture :boom.java)
   :divide-by-zero.aviso (fixture :divide-by-zero.aviso)
   :divide-by-zero.clojure.repl (fixture :divide-by-zero.clojure.repl)
   :divide-by-zero.clojure.stacktrace (fixture :divide-by-zero.clojure.stacktrace)
   :divide-by-zero.clojure.tagged-literal (fixture :divide-by-zero.clojure.tagged-literal)
   :divide-by-zero.java (fixture :divide-by-zero.java)
   :short.aviso (fixture :short.aviso)
   :short.clojure.repl (fixture :short.clojure.repl)
   :short.clojure.stacktrace (fixture :short.clojure.stacktrace)
   :short.clojure.tagged-literal.println (fixture :short.clojure.tagged-literal.println)
   :short.clojure.tagged-literal (fixture :short.clojure.tagged-literal)
   :short.java (fixture :short.java)})

(deftest parse-test
  (doseq [[fixture text] fixtures]
    (testing (str "parse fixture " fixture)
      (let [{:keys [cause error trace]} (parser/parse text)]
        (testing "should succeed"
          (is (nil? error)))
        (testing "should parse the cause"
          (is (string? cause)))
        (testing "should parse the trace"
          (doseq [element trace]
            (is (test/stacktrace-element? element) (pr-str element))))))))

(deftest parse-garbage-test
  (doseq [[fixture text] fixtures]
    (testing (str "parse fixture " fixture " with")
      (let [expected (parser/parse text)]
        (testing "garbage at the beginning"
          (is (= expected (parser/parse (str "\n<garbage>\n<garbage>\n" text)))))
        (testing "garbage at the end"
          (is (= expected (parser/parse (str text "\n<garbage>\n<garbage>\n")))))
        (testing "white space in front"
          (is (= expected (parser/parse (str " \t " text)))))
        (testing "white space at the end"
          (is (= expected (parser/parse (str text " \t ")))))
        (testing "newlines in front"
          (is (= expected (parser/parse (str text "\n\n")))))
        (testing "newlines at the end"
          (is (= expected (parser/parse (str text "\n\n")))))))))

#?(:clj (deftest parse-trim-test
          (doseq [[fixture text] fixtures]
            (testing (str "parse fixture " fixture " with")
              (testing "trimmed input"
                (is (= (parser/parse text)
                       (parser/parse (str/trim text)))))))))

(deftest parse-input-transformation-test
  (doseq [[fixture text] fixtures]
    (testing (str "parse fixture " fixture " ")
      (testing "with input pr-str 1 level deep"
        (is (= (parser/parse text) (parser/parse (pr-str text)))))
      (testing "with input pr-str 2 levels deep"
        (is (= (parser/parse text) (parser/parse (pr-str (pr-str text)))))))))
