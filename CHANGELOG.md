# Changelog

## master (unreleased)

## 0.2.0 (2023-08-20)

## Changes

* `analyzer`: include a `:phase` key for the causes that include a `:clojure.error/phase`.
* Categorize more frames as `:tooling`
  * `:tooling` now intends to more broadly hide things that are commonly Clojure-internal / irrelevant to the application programmer.
  * New exhaustive list:
    * `cider.*`
    * `clojure.core/apply`
    * `clojure.core/binding-conveyor-fn`
    * `clojure.core/eval`
    * `clojure.core/with-bindings`
    * `clojure.lang.Compiler`
    * `clojure.lang.RT`
    * `clojure.main/repl`
    * `nrepl.*`
    * `java.lang.Thread/run` (if it's the root element of the stacktrace)

## 0.1.0 (2023-08-18)

### Bugs fixed

* [#9](https://github.com/clojure-emacs/haystack/issues/9): handle unloadable classes.

## 0.0.1 (2022-11-25)

**Note:** First "official" release.

### New features

* Extract stacktrace code from `cider-nrepl`.
* Add stacktrace analyzer and parsers.
