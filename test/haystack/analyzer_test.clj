(ns haystack.analyzer-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [are deftest is testing]]
   [haystack.analyzer :as sut]
   [haystack.parser :as parser]
   [orchard.spec :as spec]))

;; # Utils

(defn- fixture [resource]
  (str (io/file "haystack" "parser" (str (name resource) ".txt"))))

(defn- read-fixture [name]
  (some-> name fixture io/resource slurp))

(defn- analyze-resource [name]
  (some-> name read-fixture parser/parse sut/analyze))

(defn causes
  [form]
  (sut/analyze
   (try (eval form)
        (catch Exception e
          e))
   sut/pprint))

(defn stack-frames
  [form]
  (-> (try (eval form)
           (catch Exception e
             e))
      sut/analyze first :stacktrace))

;; ## Test fixtures

(def form1 '(throw (ex-info "oops" {:x 1} (ex-info "cause" {:y 2}))))
(def form2 '(let [^long num "2"] ;; Type hint to make eastwood happy
              (defn oops [] (+ 1 num))
              (oops)))
(def form3 '(not-defined))
(def form4 '(divi 1 0))

(def frames1 (stack-frames form1))
(def frames2 (stack-frames form2))
(def frames4 (stack-frames form4))
(def causes1 (causes form1))
(def causes2 (causes form2))
(def causes3 (causes form3))

(def email-regex
  #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,63}$")

(def broken-musk {::first-name "Elon"
                  ::last-name  "Musk"
                  ::email      "n/a"})

(def broken-musk-causes
  (when (resolve (symbol "clojure.spec.alpha" "get-spec"))
    (eval `(do
             ~(cond
                spec/clojure-spec-alpha?
                (require '[clojure.spec.alpha :as s])
                spec/clojure-spec?
                (require '[clojure.spec :as s]))
             (s/check-asserts true)
             (s/def ::email-type
               (s/and string? #(re-matches email-regex %)))

             (s/def ::first-name string?)
             (s/def ::last-name string?)
             (s/def ::email ::email-type)

             (s/def ::person
               (s/keys :req [::first-name ::last-name ::email]
                       :opt [::phone]))

             (causes `(s/assert ::person broken-musk))))))

;; ## Tests

(when spec/spec?
  (deftest spec-assert-stacktrace-test
    (testing "Spec assert components"
      (is (= 1 (count broken-musk-causes)))
      (is (:stacktrace (first broken-musk-causes)))
      (is (:message (first broken-musk-causes)))
      (is (:spec (first broken-musk-causes))))

    (testing "Spec assert data components"
      (let [spec (:spec (first broken-musk-causes))]
        (is (:spec spec))
        (is (string? (:value spec)))
        (is (= 1 (count (:problems spec))))))

    (testing "Spec assert problems components"
      (let [probs (->> broken-musk-causes first :spec :problems first)]
        (is (:in probs))
        (is (:val probs))
        (is (:predicate probs))
        (is (:spec probs))
        (is (:at probs))))))

(deftest stacktrace-frames-test
  (testing "File types"
    ;; Should be clj and java only.
    (let [ts1 (group-by :type frames1)
          ts2 (group-by :type frames2)]
      (is (= #{:clj :java} (set (keys ts1))))
      (is (= #{:clj :java} (set (keys ts2))))))
  (testing "Full file mappings"
    (is (every?
         #(-> % ^String (:file-url) (.endsWith "!/clojure/core.clj"))
         (filter #(= "clojure.core" (:ns %))
                 frames1)))
    (is (->> (filter #(some-> % ^String (:ns) (.contains "cider")) frames1)
             (remove (comp #{"invoke" "invokeStatic"} :method)) ;; these don't have a file-url
             (every?
              #(-> % ^String (:file-url) (.startsWith "file:/"))))))
  (testing "Clojure ns, fn, and var"
    ;; All Clojure frames should have non-nil :ns :fn and :var attributes.
    (is (every? #(every? identity ((juxt :ns :fn :var) %))
                (filter #(= :clj (:type %)) frames1)))
    (is (every? #(every? identity ((juxt :ns :fn :var) %))
                (filter #(= :clj (:type %)) frames2))))
  (testing "Clojure name demunging"
    ;; Clojure fn names should be free of munging characters.
    (is (not-any? #(re-find #"[_$]|(--\d+)" (:fn %))
                  (filter :fn frames1)))
    (is (not-any? #(re-find #"[_$]|(--\d+)" (:fn %))
                  (filter :fn frames2)))))

(deftest stacktrace-frame-flags-test
  (testing "Flags"
    (testing "for file type"
      ;; Every frame should have its file type added as a flag.
      (is (every? #(contains? (:flags %) (:type %)) frames1))
      (is (every? #(contains? (:flags %) (:type %)) frames2)))
    (testing "for tooling"
      ;; Tooling frames are classes named with 'clojure' or 'nrepl',
      ;; or are java thread runners...or calls made from these.
      (is (some #(re-find #"(clojure|nrepl|run)" (:name %))
                (filter (comp :tooling :flags) frames1)))
      (is (some #(re-find #"(clojure|nrepl|run)" (:name %))
                (filter (comp :tooling :flags) frames2))))
    (testing "for project"
      (is (seq (filter (comp :project :flags) frames4))))
    (testing "for duplicate frames"
      ;; Index frames. For all frames flagged as :dup, the frame above it in
      ;; the stack (index i - 1) should be substantially the same source info.
      (let [ixd1 (zipmap (iterate inc 0) frames1)
            ixd2 (zipmap (iterate inc 0) frames2)
            dup? #(or (= (:name %1) (:name %2))
                      (and (= (:file %1) (:file %2))
                           (= (:line %1) (:line %2))))]
        (is (every? (fn [[i v]] (dup? v (get ixd1 (dec ^long i))))
                    (filter (comp :dup :flags val) ixd1)))
        (is (every? (fn [[i v]] (dup? v (get ixd2 (dec ^long i))))
                    (filter (comp :dup :flags val) ixd2)))))))

(deftest exception-causes-test
  (testing "Exception cause unrolling"
    (is (= 2 (count causes1)))
    (is (= 1 (count causes2))))
  (testing "Exception data"
    ;; If ex-data is present, the cause should have a :data attribute.
    (is (:data (first causes1)))
    (is (not (:data (first causes2))))))

(deftest ex-data-filtering-test
  (is (= {:a :b :c :d}
         (#'sut/filtered-ex-data {:a :b :c :d :repl-env :e}))))

(deftest cause-data-pretty-printing-test
  (testing "print-length"
    (is (= "{:a (0 1 2 ...)}"
           (:data (first (sut/analyze (ex-info "" {:a (range)})
                                      (fn [value writer]
                                        (sut/pprint value writer {:length 3}))))))))
  (testing "print-level"
    (is (= "{:a {#}}"
           (:data (first (sut/analyze (ex-info "" {:a {:b {:c {:d {:e nil}}}}})
                                      (fn [value writer]
                                        (sut/pprint value writer {:level 3})))))))))

(deftest compilation-errors-test
  (let [clojure-version ((juxt :major :minor) *clojure-version*)]
    (if (< (compare clojure-version [1 10]) 0)
      ;; 1.8 / 1.9
      (is (re-find #"Unable to resolve symbol: not-defined in this context"
                   (:message (first causes3))))

      ;; 1.10+
      (is (re-find #"Syntax error compiling at \(haystack/analyzer_test\.clj:"
                   (:message (first causes3))))))

  (testing "extract-location"
    (is (= {:class "clojure.lang.Compiler$CompilerException"
            :message "java.lang.RuntimeException: Unable to resolve symbol: foo in this context"
            :file "/foo/bar/baz.clj"
            :file-url nil
            :path "/foo/bar/baz.clj"
            :line 1
            :column 42}
           (#'sut/extract-location {:class "clojure.lang.Compiler$CompilerException"
                                    :message "java.lang.RuntimeException: Unable to resolve symbol: foo in this context, compiling:(/foo/bar/baz.clj:1:42)"})))

    (is (= {:class "clojure.lang.Compiler$CompilerException"
            :message "java.lang.NegativeArraySizeException"
            :file "/foo/bar/baz.clj"
            :file-url nil
            :path "/foo/bar/baz.clj"
            :line 1
            :column 42}
           (#'sut/extract-location {:class "clojure.lang.Compiler$CompilerException"
                                    :message "java.lang.NegativeArraySizeException, compiling:(/foo/bar/baz.clj:1:42)"}))))
  (testing "extract-location with location-data already present"
    (is (= {:class    "clojure.lang.Compiler$CompilerException"
            :location {:clojure.error/line 1
                       :clojure.error/column 42
                       :clojure.error/source "/foo/bar/baz.clj"
                       :clojure.error/phase :macroexpand
                       :clojure.error/symbol 'clojure.core/let},
            :message  "Syntax error macroexpanding clojure.core/let at (1:1)."
            :file     "/foo/bar/baz.clj"
            :file-url nil
            :path     "/foo/bar/baz.clj"
            :line     1
            :column   42}
           (#'sut/extract-location {:class    "clojure.lang.Compiler$CompilerException"
                                    :location {:clojure.error/line   1
                                               :clojure.error/column 42
                                               :clojure.error/source "/foo/bar/baz.clj"
                                               :clojure.error/phase  :macroexpand
                                               :clojure.error/symbol 'clojure.core/let}
                                    :message  "Syntax error macroexpanding clojure.core/let at (1:1)."})))))

(deftest analyze-cause-test
  (testing "check that location-data is returned"
    (let [e (ex-info "wat?" {:clojure.error/line 1
                             :clojure.error/column 42
                             :clojure.error/source "/foo/bar/baz.clj"
                             :clojure.error/phase :macroexpand
                             :clojure.error/symbol 'clojure.core/let})
          cause (first (sut/analyze e (fn [v _] v)))]
      (is (= {:clojure.error/line 1
              :clojure.error/column 42
              :clojure.error/source "/foo/bar/baz.clj"
              :clojure.error/phase :macroexpand
              :clojure.error/symbol 'clojure.core/let}
             (:location cause))))))

(deftest ns-common-prefix*-test
  (are [input expected] (= expected
                           (#'sut/ns-common-prefix* input))
    []             {:valid false :common :missing}
    '[a b]         {:valid false :common :missing}
    '[a.c b.c]     {:valid false :common :missing}
    ::not-a-coll   {:valid false :common :error}

    ;; single-segment namespaces are considered to never have a common part:
    '[user]        {:valid false :common :missing}
    '[dev]         {:valid false :common :missing}
    '[test-runner] {:valid false :common :missing}

    '[a.a]         {:valid true :common "a.a"}
    '[a.a a.b]     {:valid true :common "a"}
    '[a.a.b a.a.c] {:valid true :common "a.a"}

    ;; single-segment namespaces cannot foil the rest of the calculation:
    '[dev user test-runner a.a]         {:valid true :common "a.a"}
    '[dev user test-runner a.a a.b]     {:valid true :common "a"}
    '[dev user test-runner a.a.b a.a.c] {:valid true :common "a.a"}))

(deftest test-analyze-aviso
  (let [causes (analyze-resource :boom.aviso)]
    (is (= 3 (count causes)))
    (testing "first cause"
      (let [{:keys [class data message stacktrace]} (first causes)]
        (testing "class"
          (is (= "clojure.lang.ExceptionInfo" class)))
        (testing "message"
          (is (= "BOOM-1" message)))
        (testing "data"
          (is (= "{:boom \"1\"}" data)))
        (testing "stacktrace"
          (is (= 7 (count stacktrace)))
          (testing "first frame"
            (is (= {:type :unknown, :flags #{:unknown}}
                   (dissoc (first stacktrace) :file-url))))
          (testing "last frame"
            (is (= {:fn "fn"
                    :method "fn"
                    :ns "nrepl.middleware.interruptible-eval"
                    :name "nrepl.middleware.interruptible-eval/fn"
                    :file "interruptible_eval.clj"
                    :type :clj
                    :line 87
                    :var "nrepl.middleware.interruptible-eval/fn"
                    :class "nrepl.middleware.interruptible-eval"
                    :flags #{:tooling :clj}}
                   (dissoc (last stacktrace) :file-url)))))))
    (testing "second cause"
      (let [{:keys [class data message stacktrace]} (second causes)]
        (testing "class"
          (is (= "clojure.lang.ExceptionInfo" class)))
        (testing "message"
          (is (= "BOOM-2" message)))
        (testing "data"
          (is (= "{:boom \"2\"}" data)))
        (testing "stacktrace"
          (is (= 0 (count stacktrace))))))
    (testing "thrid cause"
      (let [{:keys [class data message stacktrace]} (nth causes 2)]
        (testing "class"
          (is (= "clojure.lang.ExceptionInfo" class)))
        (testing "message"
          (is (= "BOOM-3" message)))
        (testing "data"
          (is (= "{:boom \"3\"}" data)))
        (testing "stacktrace"
          (is (= 0 (count stacktrace))))))))

(deftest test-analyze-clojure-tagged-literal
  (let [causes (analyze-resource :boom.clojure.tagged-literal)]
    (is (= 3 (count causes)))
    (testing "first cause"
      (let [{:keys [class data message stacktrace]} (first causes)]
        (testing "class"
          (is (= "clojure.lang.ExceptionInfo" class)))
        (testing "message"
          (is (= "BOOM-1" message)))
        (testing "data"
          (is (= "{:boom \"1\"}" data)))
        (testing "stacktrace"
          (is (= 36 (count stacktrace)))
          (testing "first frame"
            (is (= {:name "clojure.lang.AFn/applyToHelper"
                    :file "AFn.java"
                    :line 156
                    :class "clojure.lang.AFn"
                    :method "applyToHelper"
                    :type :java
                    :flags #{:java :tooling}}
                   (dissoc (first stacktrace) :file-url))))
          (testing "last frame"
            (is (= {:name "java.lang.Thread/run"
                    :file "Thread.java"
                    :line 829
                    :class "java.lang.Thread"
                    :method "run"
                    :type :java
                    :flags #{:java :tooling}}
                   (dissoc (last stacktrace) :file-url)))))))
    (testing "second cause"
      (let [{:keys [class data message stacktrace]} (second causes)]
        (testing "class"
          (is (= "clojure.lang.ExceptionInfo" class)))
        (testing "message"
          (is (= "BOOM-2" message)))
        (testing "data"
          (is (= "{:boom \"2\"}" data)))
        (testing "stacktrace"
          (is (= 1 (count stacktrace)))
          (testing "first frame"
            (is (= {:name "clojure.lang.AFn/applyToHelper"
                    :file "AFn.java"
                    :line 160
                    :class "clojure.lang.AFn"
                    :method "applyToHelper"
                    :type :java
                    :flags #{:java :tooling}}
                   (dissoc (first stacktrace) :file-url)))))))
    (testing "third cause"
      (let [{:keys [class data message stacktrace]} (nth causes 2)]
        (testing "class"
          (is (= "clojure.lang.ExceptionInfo" class)))
        (testing "message"
          (is (= "BOOM-3" message)))
        (testing "data"
          (is (= "{:boom \"3\"}" data)))
        (testing "stacktrace"
          (is (= 1 (count stacktrace)))
          (testing "first frame"
            (is (= {:name "clojure.lang.AFn/applyToHelper"
                    :file "AFn.java"
                    :line 156
                    :class "clojure.lang.AFn"
                    :method "applyToHelper"
                    :type :java
                    :flags #{:java :tooling}}
                   (dissoc (first stacktrace) :file-url)))))))))

(deftest test-analyze-short-clojure-tagged-literal-println
  (let [causes (analyze-resource :short.clojure.tagged-literal.println)]
    (is (= 1 (count causes)))
    (testing "first cause"
      (let [{:keys [class data message stacktrace]} (first causes)]
        (testing "class"
          (is (= "clojure.lang.ExceptionInfo" class)))
        (testing "message"
          (is (= "BOOM-1" message)))
        (testing "data"
          (is (= "{:boom 1}" data)))
        (testing "stacktrace"
          (is (= 1 (count stacktrace)))
          (testing "first frame"
            (is (= {:name "java.lang.Thread/run"
                    :file "Thread.java"
                    :line 829
                    :class "java.lang.Thread"
                    :method "run"
                    :type :java
                    :flags #{:java :tooling}}
                   (dissoc (first stacktrace) :file-url)))))))))

(deftest test-analyze-java
  (let [causes (analyze-resource :boom.java)]
    (is (= 3 (count causes)))
    (testing "first cause"
      (let [{:keys [class data message stacktrace]} (first causes)]
        (testing "class"
          (is (= "clojure.lang.ExceptionInfo" class)))
        (testing "message"
          (is (= "BOOM-1" message)))
        (testing "data"
          (is (= "{:boom \"1\"}" data)))
        (testing "stacktrace"
          (is (= 34 (count stacktrace)))
          (testing "first frame"
            (is (= {:name "clojure.lang.AFn/applyToHelper"
                    :file "AFn.java"
                    :line 160
                    :class "clojure.lang.AFn"
                    :method "applyToHelper"
                    :type :java
                    :flags #{:java :tooling}}
                   (dissoc (first stacktrace) :file-url))))
          (testing "last frame"
            (is (= {:name "java.lang.Thread/run"
                    :file "Thread.java"
                    :line 829
                    :class "java.lang.Thread"
                    :method "run"
                    :type :java
                    :flags #{:java :tooling}}
                   (dissoc (last stacktrace) :file-url)))))))
    (testing "second cause"
      (let [{:keys [class data message stacktrace]} (second causes)]
        (testing "class"
          (is (= "clojure.lang.ExceptionInfo" class)))
        (testing "message"
          (is (= "BOOM-2" message)))
        (testing "data"
          (is (= "{:boom \"2\"}" data)))
        (testing "stacktrace"
          (is (= 4 (count stacktrace)))
          (testing "first frame"
            (is (= {:name "clojure.lang.AFn/applyToHelper"
                    :file "AFn.java"
                    :line 160
                    :class "clojure.lang.AFn"
                    :method "applyToHelper"
                    :type :java
                    :flags #{:java :tooling}}
                   (dissoc (first stacktrace) :file-url))))
          (testing "last frame"
            (is (= {:name "clojure.lang.Compiler$InvokeExpr/eval"
                    :file "Compiler.java"
                    :line 3705
                    :class "clojure.lang.Compiler$InvokeExpr"
                    :method "eval"
                    :type :java
                    :flags #{:dup :tooling :java}}
                   (dissoc (last stacktrace) :file-url)))))))
    (testing "third cause"
      (let [{:keys [class data message stacktrace]} (nth causes 2 nil)]
        (testing "class"
          (is (= "clojure.lang.ExceptionInfo" class)))
        (testing "message"
          (is (= "BOOM-3" message)))
        (testing "data"
          (is (= "{:boom \"3\"}" data)))
        (testing "stacktrace"
          (is (= 4 (count stacktrace)))
          (testing "first frame"
            (is (= {:name "clojure.lang.AFn/applyToHelper"
                    :file "AFn.java"
                    :line 156
                    :class "clojure.lang.AFn"
                    :method "applyToHelper"
                    :type :java
                    :flags #{:java :tooling}}
                   (dissoc (first stacktrace) :file-url))))
          (testing "last frame"
            (is (= {:name "clojure.lang.Compiler$InvokeExpr/eval"
                    :file "Compiler.java"
                    :line 3705
                    :class "clojure.lang.Compiler$InvokeExpr"
                    :method "eval"
                    :type :java
                    :flags #{:dup :tooling :java}}
                   (dissoc (last stacktrace) :file-url)))))))))

(deftest test-analyze-throwable
  (let [causes (sut/analyze
                '{:via
                  [{:type clojure.lang.ExceptionInfo
                    :message "BOOM-1"
                    :data {:boom "1"}
                    :at [clojure.lang.AFn applyToHelper "AFn.java" 160]}
                   {:type clojure.lang.ExceptionInfo
                    :message "BOOM-2"
                    :data {:boom "2"}
                    :at [clojure.lang.AFn applyToHelper "AFn.java" 160]}
                   {:type clojure.lang.ExceptionInfo
                    :message "BOOM-3"
                    :data {:boom "3"}
                    :at [clojure.lang.AFn applyToHelper "AFn.java" 156]}]
                  :trace
                  [[clojure.lang.AFn applyToHelper "AFn.java" 156]
                   [clojure.lang.AFn applyTo "AFn.java" 144]
                   [clojure.lang.Compiler$InvokeExpr eval "Compiler.java" 3706]
                   [clojure.lang.Compiler$InvokeExpr eval "Compiler.java" 3705]
                   [clojure.lang.Compiler$InvokeExpr eval "Compiler.java" 3705]]
                  :cause "BOOM-3"
                  :data {:boom "3"}
                  :stacktrace-type :throwable})]
    (is (= 3 (count causes)))
    (testing "first cause"
      (let [{:keys [class data message stacktrace]} (first causes)]
        (testing "class"
          (is (= "clojure.lang.ExceptionInfo" class)))
        (testing "message"
          (is (= "BOOM-1" message)))
        (testing "data"
          (is (= "{:boom \"1\"}" data)))
        (testing "stacktrace"
          (is (= 5 (count stacktrace)))
          (testing "first frame"
            (is (= {:name "clojure.lang.AFn/applyToHelper"
                    :file "AFn.java"
                    :line 156
                    :class "clojure.lang.AFn"
                    :method "applyToHelper"
                    :type :java
                    :flags #{:java :tooling}}
                   (dissoc (nth stacktrace 0) :file-url))))
          (testing "2nd frame"
            (is (= {:class "clojure.lang.AFn"
                    :file "AFn.java"
                    :flags #{:java :tooling}
                    :line 144
                    :method "applyTo"
                    :name "clojure.lang.AFn/applyTo"
                    :type :java}
                   (dissoc (nth stacktrace 1) :file-url)))))))
    (testing "second cause"
      (let [{:keys [class data message stacktrace]} (second causes)]
        (testing "class"
          (is (= "clojure.lang.ExceptionInfo" class)))
        (testing "message"
          (is (= "BOOM-2" message)))
        (testing "data"
          (is (= "{:boom \"2\"}" data)))
        (testing "stacktrace"
          (is (= 1 (count stacktrace)))
          (testing "first frame"
            (is (= {:name "clojure.lang.AFn/applyToHelper"
                    :file "AFn.java"
                    :class "clojure.lang.AFn"
                    :line 160
                    :method "applyToHelper"
                    :type :java
                    :flags #{:java :tooling}}
                   (dissoc (nth stacktrace 0) :file-url)))))))
    (testing "third cause"
      (let [{:keys [class data message stacktrace]} (nth causes 2 nil)]
        (testing "class"
          (is (= "clojure.lang.ExceptionInfo" class)))
        (testing "message"
          (is (= "BOOM-3" message)))
        (testing "data"
          (is (= "{:boom \"3\"}" data)))
        (testing "stacktrace"
          (is (= 1 (count stacktrace)))
          (testing "first frame"
            (is (= {:name "clojure.lang.AFn/applyToHelper"
                    :file "AFn.java"
                    :class "clojure.lang.AFn"
                    :line 156
                    :method "applyToHelper"
                    :type :java
                    :flags #{:java :tooling}}
                   (dissoc (nth stacktrace 0) :file-url))))))))

  (let [{:keys [major minor]} *clojure-version*]
    (when-not (and (= 1 major)
                   (< (long minor) 10))
      (testing "Includes a `:phase` for the causes that include it"
        (is (= [:macro-syntax-check nil]
               (->> (try
                      (eval '(let [1]))
                      (catch Throwable e
                        (sut/analyze e)))
                    (map :phase)))))
      (testing "Does not include `:phase` for vanilla runtime exceptions"
        (is (= [nil]
               (->> (try
                      (throw (ex-info "" {}))
                      (catch Throwable e
                        (sut/analyze e)))
                    (map :phase)))))))

  (testing "`:compile-like`"
    (testing "For non-existing fields"
      (is (= ["true"]
             (->> (try
                    (eval '(.-foo ""))
                    (catch Throwable e
                      (sut/analyze e)))
                  (map :compile-like)))))
    (testing "For non-existing methods"
      (is (= ["true"]
             (->> (try
                    (eval '(-> "" (.foo 1 2)))
                    (catch Throwable e
                      (sut/analyze e)))
                  (map :compile-like)))))
    (testing "For vanilla exceptions"
      (is (= ["false"]
             (->> (try
                    (throw (ex-info "." {}))
                    (catch Throwable e
                      (sut/analyze e)))
                  (map :compile-like)))))
    (testing "For vanilla `IllegalArgumentException`s"
      (is (= ["false"]
             (->> (try
                    (throw (IllegalArgumentException. "foo"))
                    (catch Throwable e
                      (sut/analyze e)))
                  (map :compile-like)))))
    (testing "For exceptions with a `:phase`"
      (is (#{["false" "false"] ;; normal expectation
             ["false"]} ;; clojure 1.8
           (->> (try
                  (eval '(let [1]))
                  (catch Throwable e
                    (sut/analyze e)))
                (map :compile-like)))))))

(deftest tooling-frame-name?
  (are [frame-name expected] (testing frame-name
                               (is (= expected
                                      (#'sut/tooling-frame-name? frame-name false)))
                               true)
    "cider.foo"                                                 true
    "refactor-nrepl.middleware/wrap-refactor"                   true
    "shadow.cljs.devtools.server.nrepl/shadow-inint"            true
    "acider.foo"                                                false
    ;; `+` is "application" level, should not be hidden:
    "clojure.core/+"                                            false
    ;; `apply` typically is internal, should be hidden:
    "clojure.core/apply"                                        true
    "clojure.core/binding-conveyor-fn/fn"                       true
    "clojure.core.protocols/iter-reduce"                        true
    "clojure.core/eval"                                         true
    "clojure.core/with-bindings*"                               true
    "clojure.lang.MultiFn/invoke"                               true
    "clojure.lang.LazySeq/sval"                                 true
    "clojure.lang.Var/invoke"                                   true
    "clojure.lang.AFn/applyTo"                                  true
    "clojure.lang.AFn/applyToHelper"                            true
    "clojure.lang.RestFn/invoke"                                true
    "clojure.main/repl"                                         true
    "clojure.main$repl$read_eval_print__9234$fn__9235/invoke"   true
    "nrepl.foo"                                                 true
    "nrepl.middleware.interruptible_eval$evaluate/invokeStatic" true
    "anrepl.foo"                                                false
    ;; important case - `Numbers` is relevant, should not be hidden:
    "clojure.lang.Numbers/divide"                               false)

  (is (not (#'sut/tooling-frame-name? "java.lang.Thread/run" false)))
  (is (#'sut/tooling-frame-name? "java.lang.Thread/run" true)))

(deftest flag-tooling
  (is (= [{:name "cider.foo", :flags #{:tooling}}
          {:name "java.lang.Thread/run"} ;; does not get the flag because it's not the root frame
          {:name "don't touch me 1"}
          {:name "nrepl.foo", :flags #{:tooling}}
          {:name "clojure.lang.RestFn/invoke", :flags #{:tooling}}
          {:name "don't touch me 2"}
          ;; gets the flag because it's the root frame:
          {:name "java.lang.Thread/run", :flags #{:tooling}}]
         (#'sut/flag-tooling [{:name "cider.foo"}
                              {:name "java.lang.Thread/run"}
                              {:name "don't touch me 1"}
                              {:name "nrepl.foo"}
                              {:name "clojure.lang.RestFn/invoke"}
                              {:name "don't touch me 2"}
                              {:name "java.lang.Thread/run"}]))
      "Adds the flag when appropiate, leaving other entries untouched")

  (let [frames [{:name "don't touch me"}
                {:name "java.util.concurrent.FutureTask/run"}
                {:name "java.util.concurrent.ThreadPoolExecutor/runWorker"}
                {:name "java.util.concurrent.ThreadPoolExecutor$Worker/run"}]]
    (is (= [{:name "don't touch me"}
            {:name "java.util.concurrent.FutureTask/run", :flags #{:tooling}}
            {:name "java.util.concurrent.ThreadPoolExecutor/runWorker", :flags #{:tooling}}
            {:name "java.util.concurrent.ThreadPoolExecutor$Worker/run", :flags #{:tooling}}]
           (#'sut/flag-tooling frames))
        "Three j.u.concurrent frames get the flag if they're at the bottom")
    (is (= [{:name "don't touch me"}
            {:name "java.util.concurrent.FutureTask/run"}
            {:name "java.util.concurrent.ThreadPoolExecutor/runWorker"}
            {:name "java.util.concurrent.ThreadPoolExecutor$Worker/run"}
            {:name "x"}]
           (#'sut/flag-tooling (conj frames {:name "x"})))
        "The j.u.concurrent frames don't get the flag if they're not at the bottom")))
