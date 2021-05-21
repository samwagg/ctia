(ns actions.actions-helpers-test
  (:require [clojure.test :refer [deftest is testing]]
            [actions.actions-helpers :as sut]
            [actions.test-helpers :as th]
            [cheshire.core :as json])
  (:import [java.io File]))

(deftest getenv-test
  (is (= (sut/getenv "PWD")
         (System/getenv "PWD")
         (System/getProperty "user.dir"))))

(deftest add-env-test
  (let [github-env-file (File/createTempFile "github-env" nil)
        {:keys [grab-history utils]} (th/mk-utils {"GITHUB_ENV" (.getPath github-env-file)})
        _ (sut/add-env utils "foo" "bar")
        _ (is (= (grab-history)
                 [{:op :getenv
                   :k "GITHUB_ENV"}]))
        _ (is (= (slurp github-env-file)
                 "foo=bar\n"))
        ]))
