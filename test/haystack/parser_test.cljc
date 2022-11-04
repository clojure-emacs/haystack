(ns haystack.parser-test
  (:require
   #?(:clj [clojure.string :as str])
   [clojure.test :refer [deftest is testing]]
   [haystack.parser :as parser]
   [haystack.parser.test :as test]
   [haystack.parser.test.fixtures :refer [fixtures]]))

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
