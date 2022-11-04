(ns haystack.parser.util-test
  (:require
   [clojure.test :refer [deftest is]]
   [haystack.parser.util :as util]))

(deftest safe-read-edn-test
  (is (= nil (util/safe-read-edn "[")))
  (is (= [1 2 3] (util/safe-read-edn "[1 2 3]")))
  (let [{:keys [form tag]} (util/safe-read-edn "#error {:a 1}")]
    (is (= 'error tag))
    (is (= {:a 1} form))))
