#!/usr/bin/env bb

;; Example:
;; LOG_PATH=$(mktemp -d) ./scripts/actions/setup-env.clj

(ns actions.setup-env
  (:require [actions.actions-helpers :refer [add-env getenv]]
            [clojure.java.shell :as sh]))

(let [log-path (getenv "LOG_PATH")]
  (assert log-path)
  (-> (sh/sh "mkdir" "-p" log-path)
      :exit
      #{0}
      assert))

(assert (not (getenv "TRAVIS_EVENT_TYPE")) "Actions only")
