(ns haystack.stacktrace.parser.java-test
  (:require [clojure.test :refer [deftest is testing]]
            [haystack.stacktrace.parser.java :as parser]
            [haystack.stacktrace.parser.test :as test]))

(defn- parse-fixture [name]
  (some-> name test/read-fixture parser/parse-stacktrace))

(deftest parse-stacktrace-boom-test
  (let [{:keys [cause data trace stacktrace-type via]} (parse-fixture :boom.java)]
    (testing ":stacktrace-type"
      (is (= :java stacktrace-type)))
    (testing "throwable cause"
      (is (= "BOOM-3" cause)))
    (testing ":data"
      (is (= {:boom "3"} data)))
    (testing ":via"
      (is (= 3 (count via)))
      (testing "first cause"
        (let [{:keys [at data message trace type]} (nth via 0)]
          (is (= '[clojure.lang.AFn applyToHelper "AFn.java" 160] at))
          (is (= {:boom "1"} data))
          (is (= "BOOM-1" message))
          (is (= 'clojure.lang.ExceptionInfo type))
          (is (= '[[clojure.lang.AFn applyToHelper "AFn.java" 160]
                   [clojure.lang.AFn applyTo "AFn.java" 144]
                   [clojure.lang.Compiler$InvokeExpr eval "Compiler.java" 3706]]
                 (take 3 trace)))))
      (testing "second cause"
        (let [{:keys [at data message trace type]} (nth via 1)]
          (is (= '[clojure.lang.AFn applyToHelper "AFn.java" 160] at))
          (is (= {:boom "2"} data))
          (is (= "BOOM-2" message))
          (is (= 'clojure.lang.ExceptionInfo type))
          (is (= '[[clojure.lang.AFn applyToHelper "AFn.java" 160]
                   [clojure.lang.AFn applyTo "AFn.java" 144]
                   [clojure.lang.Compiler$InvokeExpr eval "Compiler.java" 3706]]
                 (take 3 trace)))))
      (testing "third cause"
        (let [{:keys [at data message trace type]} (nth via 2)]
          (is (= '[clojure.lang.AFn applyToHelper "AFn.java" 156] at))
          (is (= {:boom "3"} data))
          (is (= "BOOM-3" message))
          (is (= 'clojure.lang.ExceptionInfo type))
          (is (= '[[clojure.lang.AFn applyToHelper "AFn.java" 156]
                   [clojure.lang.AFn applyTo "AFn.java" 144]
                   [clojure.lang.Compiler$InvokeExpr eval "Compiler.java" 3706]]
                 (take 3 trace))))))
    (testing ":trace"
      (doseq [element trace]
        (is (test/stacktrace-element? element) (pr-str element)))
      (testing "first frame"
        (is (= '[clojure.lang.AFn applyToHelper "AFn.java" 156]
               (first trace))))
      (testing "last frame"
        (is (= '[clojure.lang.Compiler$InvokeExpr eval "Compiler.java" 3705]
               (last trace)))))))

(deftest parse-stacktrace-divide-by-zero-test
  (let [{:keys [cause data trace stacktrace-type via]} (parse-fixture :divide-by-zero.java)]
    (testing ":stacktrace-type"
      (is (= :java stacktrace-type)))
    (testing "throwable cause"
      (is (= "Divide by zero" cause)))
    (testing ":data"
      (is (= nil data)))
    (testing ":via"
      (is (= 1 (count via)))
      (testing "first cause"
        (let [{:keys [at data message type]} (nth via 0)]
          (is (= '[clojure.lang.Numbers divide "Numbers.java" 188] at))
          (is (= nil data))
          (is (= "Divide by zero" message))
          (is (= 'java.lang.ArithmeticException type)))))
    (testing ":trace"
      (doseq [element trace]
        (is (test/stacktrace-element? element) (pr-str element)))
      (testing "first frame"
        (is (= '[clojure.lang.Numbers divide "Numbers.java" 188] (first trace))))
      (testing "last frame"
        (is (= '[java.base/java.lang.Thread run "Thread.java" 829] (last trace)))))))

(deftest parse-stacktrace-short-test
  (let [{:keys [cause data trace stacktrace-type via]} (parse-fixture :short.java)]
    (testing ":stacktrace-type"
      (is (= :java stacktrace-type)))
    (testing "throwable cause"
      (is (= "BOOM-1" cause)))
    (testing ":data"
      (is (= {:boom "1"} data)))
    (testing ":via"
      (is (= 1 (count via)))
      (testing "first cause"
        (let [{:keys [at data message type]} (nth via 0)]
          (is (= '[java.base/java.lang.Thread run "Thread.java" 829] at))
          (is (= {:boom "1"} data))
          (is (= "BOOM-1" message))
          (is (= 'clojure.lang.ExceptionInfo type)))))
    (testing ":trace"
      (doseq [element trace]
        (is (test/stacktrace-element? element) (pr-str element)))
      (testing "first frame"
        (is (= '[java.base/java.lang.Thread run "Thread.java" 829] (first trace))))
      (testing "last frame"
        (is (= '[java.base/java.lang.Thread run "Thread.java" 829] (last trace)))))))

(deftest parse-stacktrace-incorrect-input-test
  (testing "parsing a string not matching the grammar"
    (let [{:keys [error failure input type]} (parser/parse-stacktrace "")]
      (is (= :incorrect error))
      (is (= :incorrect-input type))
      (is (= "" input))
      (is (= {:index 0
              :reason
              [{:tag :regexp, :expecting "[a-zA-Z0-9_$/-]"}
               {:tag :regexp, :expecting "[^\\S\\r\\n]+"}]
              :line 1
              :column 1
              :text nil}
             (test/stringify-regexp failure))))))

(deftest parse-stacktrace-unsupported-input-test
  (testing "parsing unsupported input"
    (let [{:keys [error input type]} (parser/parse-stacktrace 1)]
      (is (= :unsupported error))
      (is (= :unsupported-input type))
      (is (= 1 input)))))