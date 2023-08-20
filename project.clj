;; PROJECT_VERSION is set by .circleci/deploy/deploy_release.clj,
;; whenever we perform a deployment.
(defproject mx.cider/haystack (or (not-empty (System/getenv "PROJECT_VERSION"))
                                  "0.0.0")
  :description "Let's make the most of Clojure's infamous stacktraces!"
  :url "https://github.com/clojure-emacs/haystack"
  :license {:name "Eclipse Public License"
            :url "https://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[cider/orchard "0.11.0"]
                 [instaparse "1.4.12" :exclusions [org.clojure/clojure]]]
  :pedantic? ~(if (System/getenv "CI")
                :abort
                ;; :pedantic? can be problematic for certain local dev workflows:
                false)
  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                    :username :env/clojars_username
                                    :password :env/clojars_password
                                    :sign-releases false}]]

  :profiles {:provided {:dependencies [[org.clojure/clojure "1.11.1"]
                                       [org.clojure/clojurescript "1.11.4"]]}

             :1.8 {:dependencies [[org.clojure/clojure "1.8.0"]]}

             :1.9 {:dependencies [[org.clojure/clojure "1.9.0"]]}

             :1.10 {:dependencies [[org.clojure/clojure "1.10.3"]]}

             :1.11 {:dependencies [[org.clojure/clojure "1.11.1"]]}

             :master {:repositories [["snapshots"
                                      "https://oss.sonatype.org/content/repositories/snapshots"]]
                      :dependencies [[org.clojure/clojure "1.12.0-master-SNAPSHOT"]
                                     [org.clojure/clojure "1.12.0-master-SNAPSHOT" :classifier "sources"]]}
             :cljsbuild {:plugins   [[lein-cljsbuild "1.1.8"]]
                         :dependencies [[lein-doo "0.1.11"]]
                         :cljsbuild {:builds
                                     [{:id "test"
                                       :compiler
                                       {:main haystack.test.runner
                                        :output-dir "target/cljs/test"
                                        :output-to "target/cljs/test.js"
                                        :target :nodejs}
                                       :source-paths ["src" "test"]}]}}
             :cljfmt [:test
                      {:plugins [[lein-cljfmt "0.9.0" :exclusions [org.clojure/clojure
                                                                   org.clojure/clojurescript]]]}]
             :eastwood {:plugins [[jonase/eastwood "1.3.0"]]
                        :eastwood {;; :implicit-dependencies would fail spuriously when the CI matrix runs for Clojure < 1.10,
                                   ;; because :implicit-dependencies can only work for a certain corner case starting from 1.10.
                                   :exclude-linters [:implicit-dependencies]
                                   :exclude-namespaces [refactor-nrepl.plugin]
                                   :add-linters [:boxed-math :performance]}}
             :clj-kondo [:test
                         {:dependencies [[clj-kondo "2022.10.14"
                                          :exclusions [com.cognitect/transit-clj
                                                       org.clojure/tools.reader]]]}]

             :deploy {:source-paths [".circleci/deploy"]}
             :repl {:resource-paths ["test-resources"]}
             :test {:resource-paths ["test-resources"]}})
