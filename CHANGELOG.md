# Changelog

## master (unreleased)

* `analyzer`: include a `:compile-like` key which indicates if the error happened at a "compile-like" phase.
  * It represents exceptions that happen at runtime (and therefore never include a `:phase`) which however, represent code that cannot possibly work, and therefore are a "compile-like" exception (i.e. a linter could have caught them).
  * The set of conditions which are considered a 'compile-like' exception is private and subject to change. 
* Use Orchard [0.15.1](https://github.com/clojure-emacs/orchard/blob/v0.15.1/CHANGELOG.md#0151-2023-09-21).

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
