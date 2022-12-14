(ns haystack.parser.clojure.throwable
  "Convert `java.lang.Throwable` instances into the `Throwable->map` data format."
  {:added "0.1.0"
   :author "r0man"}
  (:refer-clojure :exclude [StackTraceElement->vec Throwable->map])
  (:require [haystack.parser.util :as util]
            #?(:cljs [cljs.repl :as repl])))

;; Throwable

;; The `StackTraceElement->vec` and `Throwable->map` functions were copied from
;; Clojure, because `StackTraceElement->vec` was introduced in Clojure version
;; 1.9 and we want to support it in older Clojure versions as well.

#?(:clj (defn StackTraceElement->vec
          "Constructs a data representation for a StackTraceElement: [class method file line]"
          {:added "0.1.0"}
          [^StackTraceElement o]
          [(symbol (.getClassName o)) (symbol (.getMethodName o)) (.getFileName o) (.getLineNumber o)]))

#?(:clj (defn Throwable->map
          "Constructs a data representation for a Throwable with keys:
            :cause - root cause message
            :phase - error phase
            :via - cause chain, with cause keys:
                     :type - exception class symbol
                     :message - exception message
                     :data - ex-data
                     :at - top stack element
            :trace - root cause stack elements"
          {:added "0.1.0"}
          [^Throwable o]
          (let [base (fn [^Throwable t]
                       (merge {:type (symbol (.getName (class t)))}
                              (when-let [msg (.getLocalizedMessage t)]
                                {:message msg})
                              (when-let [ed (ex-data t)]
                                {:data ed})
                              (let [st (.getStackTrace t)]
                                (when (pos? (alength st))
                                  {:at (StackTraceElement->vec (aget st 0))
                                   ;; This is an additional key not present in
                                   ;; Throwable->map. We added it to have the complete
                                   ;; trace available for analysis.
                                   :trace (vec (map StackTraceElement->vec st))}))))
                via (loop [via [], ^Throwable t o]
                      (if t
                        (recur (conj via t) (.getCause t))
                        via))
                ^Throwable root (peek via)]
            (merge {:via (vec (map base via))
                    :trace (vec (map StackTraceElement->vec
                                     (.getStackTrace (or root o))))}
                   (when-let [root-msg (.getLocalizedMessage root)]
                     {:cause root-msg})
                   (when-let [data (ex-data root)]
                     {:data data})
                   (when-let [phase (-> o ex-data :clojure.error/phase)]
                     {:phase phase})))))

(defn parse-stacktrace
  "Parse `input` as a `java.lang.Throwable` instance."
  {:added "0.1.0"}
  [input]
  (if #?(:clj (instance? Throwable input)
         :cljs (or (instance? ExceptionInfo input)
                   (instance? js/Error input)))
    (assoc (#?(:clj Throwable->map :cljs repl/Error->map) input)
           :stacktrace-type :throwable)
    (util/error-unsupported-input input)))
