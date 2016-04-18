(ns ctia.http.handler-test
  (:refer-clojure :exclude [get])
  (:require [ctia.http.handler :as handler]
            [ctia.test-helpers.core :refer [delete get post put] :as helpers]
            [ctia.test-helpers.db :as db-helpers]
            [ctia.test-helpers.es :as es-helpers]
            [ctia.test-helpers.fake-whoami-service :as whoami-helpers]
            [clojure.test :refer [deftest is testing use-fixtures join-fixtures]]
            [ctia.schemas.common :as c]
            [ctia.test-helpers.auth :refer [all-capabilities]]
            [ctia.test-helpers.store :refer [deftest-for-each-store]]))

(use-fixtures :once (join-fixtures [helpers/fixture-schema-validation
                                    helpers/fixture-properties:clean
                                    whoami-helpers/fixture-server]))

(use-fixtures :each whoami-helpers/fixture-reset-state)


(deftest-for-each-store test-version-routes
  (testing "we can request different content types"
    (let [response (get "ctia/version" :accept :json)]
      (is (= "/ctia" (get-in response [:parsed-body "base"]))))

    (let [response (get "ctia/version" :accept :edn)]
      (is (= "/ctia" (get-in response [:parsed-body :base]) ))))

  (testing "GET /ctia/version"
    (let [response (get "ctia/version")]
      (is (= 200 (:status response)))
      (is (= "0.1" (get-in response [:parsed-body :version]))))))
