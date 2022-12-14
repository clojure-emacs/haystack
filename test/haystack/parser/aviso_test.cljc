(ns haystack.parser.aviso-test
  (:require [clojure.test :refer [deftest is testing]]
            [haystack.parser.aviso :as parser]
            [haystack.parser.test :as test]
            [haystack.parser.test.fixtures :refer [fixtures]]))

(defn- parse [s]
  (parser/parse-stacktrace s))

(deftest parse-stacktrace-boom-test
  (let [{:keys [cause data trace stacktrace-type via]} (parse (fixtures :boom.aviso))]
    (testing ":stacktrace-type"
      (is (= :aviso stacktrace-type)))
    (testing "throwable cause"
      (is (= "BOOM-3" cause)))
    (testing ":data"
      (is (= {:boom "3"} data)))
    (testing ":via"
      (is (= 3 (count via)))
      (testing "first cause"
        (let [{:keys [at data message type]} (nth via 0)]
          (is (nil? at))
          (is (= {:boom "1"} data))
          (is (= "BOOM-1" message))
          (is (= 'clojure.lang.ExceptionInfo type))))
      (testing "second cause"
        (let [{:keys [at data message type]} (nth via 1)]
          (is (nil? at))
          (is (= {:boom "2"} data))
          (is (= "BOOM-2" message))
          (is (= 'clojure.lang.ExceptionInfo type))))
      (testing "third cause"
        (let [{:keys [at data message type]} (nth via 2)]
          (is (nil? at))
          (is (= {:boom "3"} data))
          (is (= "BOOM-3" message))
          (is (= 'clojure.lang.ExceptionInfo type)))))
    (testing ":trace"
      (doseq [element trace]
        (is (test/stacktrace-element? element) (pr-str element)))
      (testing "first frame"
        (is (= '[haystack.parser.throwable-test eval12321 "REPL Input"] (first trace))))
      (testing "last frame"
        (is (= '[nrepl.middleware.interruptible-eval evaluate/fn "interruptible_eval.clj" 87] (last trace)))))))

(deftest parse-stacktrace-boom-full-test
  (let [{:keys [cause data trace stacktrace-type via]} (parse (fixtures :boom.aviso.full))]
    (testing ":stacktrace-type"
      (is (= :aviso stacktrace-type)))
    (testing "throwable cause"
      (is (= "BOOM-3" cause)))
    (testing ":data"
      (is (= {:boom "3"} data)))
    (testing ":via"
      (is (= 3 (count via)))
      (testing "first cause"
        (let [{:keys [at data message type]} (nth via 0)]
          (is (nil? at))
          (is (= {:boom "1"} data))
          (is (= "BOOM-1" message))
          (is (= 'clojure.lang.ExceptionInfo type))))
      (testing "second cause"
        (let [{:keys [at data message type]} (nth via 1)]
          (is (nil? at))
          (is (= {:boom "2"} data))
          (is (= "BOOM-2" message))
          (is (= 'clojure.lang.ExceptionInfo type))))
      (testing "third cause"
        (let [{:keys [at data message type]} (nth via 2)]
          (is (nil? at))
          (is (= {:boom "3"} data))
          (is (= "BOOM-3" message))
          (is (= 'clojure.lang.ExceptionInfo type)))))
    (testing ":trace"
      (doseq [element trace]
        (is (test/stacktrace-element? element) (pr-str element)))
      (testing "first frame"
        (is (= '[clojure.lang.AFn applyToHelper "AFn.java" 156] (first trace))))
      (testing "last frame"
        (is (= '[java.lang.Thread run "Thread.java" 829] (last trace)))))))

(deftest parse-stacktrace-divide-by-zero-test
  (let [{:keys [cause data trace stacktrace-type via]} (parse (fixtures :divide-by-zero.aviso))]
    (testing ":stacktrace-type"
      (is (= :aviso stacktrace-type)))
    (testing "throwable cause"
      (is (= "Divide by zero" cause)))
    (testing ":data"
      (is (= nil data)))
    (testing ":via"
      (is (= 1 (count via)))
      (testing "first cause"
        (let [{:keys [at data message type]} (nth via 0)]
          (is (nil? at))
          (is (= nil data))
          (is (= "Divide by zero" message))
          (is (= 'java.lang.ArithmeticException type)))))
    (testing ":trace"
      (doseq [element trace]
        (is (test/stacktrace-element? element) (pr-str element)))
      (testing "first frame"
        (is (= '[haystack.parser.throwable-test fn "throwable_test.clj" 13]
               (first trace))))
      (testing "last frame"
        (is (= '[nrepl.middleware.interruptible-eval evaluate/fn "interruptible_eval.clj" 87]
               (last trace)))))))

(deftest parse-stacktrace-short-test
  (let [{:keys [cause data trace stacktrace-type via]} (parse (fixtures :short.aviso))]
    (testing ":stacktrace-type"
      (is (= :aviso stacktrace-type)))
    (testing "throwable cause"
      (is (= "BOOM-1" cause)))
    (testing ":data"
      (is (= {:boom "1"} data)))
    (testing ":via"
      (is (= 1 (count via)))
      (testing "first cause"
        (let [{:keys [at data message type]} (nth via 0)]
          (is (nil? at))
          (is (= {:boom "1"} data))
          (is (= "BOOM-1" message))
          (is (= 'clojure.lang.ExceptionInfo type)))))
    (testing ":trace"
      (doseq [element trace]
        (is (test/stacktrace-element? element) (pr-str element)))
      (testing "first frame"
        (is (= '[java.lang.Thread run "Thread.java" 829] (first trace))))
      (testing "last frame"
        (is (= '[java.lang.Thread run "Thread.java" 829] (last trace)))))))

(deftest parse-stacktrace-incorrect-input-test
  (testing "parsing a string not matching the grammar"
    (let [{:keys [error failure input type]} (parse "")]
      (is (= :incorrect error))
      (is (= :incorrect-input type))
      (is (= "" input))
      (is (= {:index 0
              :reason
              #?(:clj
                 [{:tag :regexp, :expecting "[a-zA-Z0-9_$*-]"}
                  {:tag :regexp, :expecting "[^\\S\\r\\n]+"}]
                 :cljs
                 [{:tag :regexp, :expecting "/^[a-zA-Z0-9_$*-]/"}
                  {:tag :regexp, :expecting "/^[^\\S\\r\\n]+/"}])
              :line 1
              :column 1
              :text #?(:clj nil :cljs "")}
             (test/stringify-regexp failure))))))

(deftest parse-stacktrace-unsupported-input-test
  (testing "parsing unsupported input"
    (let [{:keys [error input type]} (parse 1)]
      (is (= :unsupported error))
      (is (= :unsupported-input type))
      (is (= 1 input)))))
