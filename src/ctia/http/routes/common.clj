(ns ctia.http.routes.common
  (:require [clj-http.headers :refer [canonicalize]]
            [clj-momo.lib.clj-time.core :as t]
            [clojure.string :as str]
            [ctia.schemas.search-agg :refer [MetricResult
                                             RangeQueryOpt
                                             SearchQuery]]
            [ctia.schemas.sorting :as sorting]
            [ring.swagger.schema :refer [describe]]
            [ring.util.codec :as codec]
            [ring.util.http-response :as http-res]
            [ring.util.http-status :refer [ok]]
            [schema-tools.core :as st]
            [schema.core :as s]))

(def search-options [:sort_by
                     :sort_order
                     :offset
                     :limit
                     :fields
                     :search_after
                     :query_mode
                     :search_fields])

(def filter-map-search-options
  (conj search-options :query :simple_query :from :to))

(s/defschema BaseEntityFilterParams
  {(s/optional-key :id) s/Str
   (s/optional-key :from) s/Inst
   (s/optional-key :to) s/Inst
   (s/optional-key :revision) s/Int
   (s/optional-key :language) s/Str
   (s/optional-key :tlp) s/Str})

(s/defschema SourcableEntityFilterParams
  {(s/optional-key :source) s/Str})

(s/defschema SearchableEntityParams
  {(s/optional-key :query) s/Str

   (s/optional-key :simple_query)
   (describe s/Str "Query String with simple query format")})

(s/defschema PagingParams
  "A schema defining the accepted paging and sorting related query parameters."
  {(s/optional-key :sort_by) (describe (apply s/enum sorting/default-entity-sort-fields)
                                       "Sort results on a field")
   (s/optional-key :sort_order) (describe (s/enum :asc :desc) "Sort direction")
   (s/optional-key :offset) (describe Long "Pagination Offset")
   (s/optional-key :search_after) (describe [s/Str] "Pagination stateless cursor")
   (s/optional-key :limit) (describe Long "Pagination Limit")})

(s/defn prep-es-fields-schema :- (s/protocol s/Schema)
  "Conjoins Elasticsearch fields parameter into search-q-params schema"
  [{{:keys [get-store]} :StoreService}
   {:keys [search-q-params entity] :as _entity-crud-config}]
  (let [searchable-fields (-> entity get-store :state :searchable-fields)
        default-fields-schema (->> searchable-fields
                                   (map name)
                                   (apply s/enum))]
    (cond-> search-q-params
      (seq searchable-fields)
      (st/merge
       {;; We cannot name the parameter :fields, because we already have :fields (part
        ;; of search-q-params). That key is to select a subsets of fields of the
        ;; retrieved document and it gets passed to the `_source` parameter of
        ;; Elasticsearch. For more: www.elastic.co/guide/en/elasticsearch/reference/current/mapping-source-field.html
        (s/optional-key :search_fields)
        (describe [default-fields-schema] "'fields' key of Elasticsearch Fulltext Query.")}))))

(def paging-param-keys
  "A list of the paging and sorting related parameters, we can use
  this to filter them out of query-param lists."
  (map :k (keys PagingParams)))


(defn map->paging-header-value [m]
  (str/join "&" (map (fn [[k v]]
                       (str (name k) "=" v))
                     m)))

(defn map->paging-headers
  "transform a map to a headers map
  {:total-hits 42}
  --> {'X-Total-Hits' '42'}"
  [headers]
  (into {} (map (fn [[k v]]
                  {(->> k
                        name
                        (str "x-")
                        canonicalize)

                   (if (map? v)
                     (codec/form-encode v)
                     (str v))}))
        headers))

(defn paginated-ok
  "returns a 200 with the supplied response
   and its metas as headers"
  [{:keys [data paging]
    :or {data []
         paging {}}}]
  {:status ok
   :body data
   :headers (map->paging-headers paging)})

(defn created
  "set a created response, using the id as the location header,
   and the full resource as body"
  [{:keys [id] :as resource}]
  (http-res/created id resource))

(s/defn now :- s/Inst
  []
  (java.util.Date.))

(s/defn coerce-date-range :- {:gte s/Inst
                              :lt s/Inst}
  "coerce from to limit interval querying to one year"
  [from :- s/Inst
   to :- (s/maybe s/Inst)]
  (let [to-or-now (or to (now))
        to-minus-one-year (t/minus to-or-now (t/years 1))
        from (t/latest from to-minus-one-year)]
    {:gte from
     :lt to-or-now}))

(s/defn search-query :- SearchQuery
  ([date-field search-params]
   (search-query date-field
                 search-params
                 (s/fn :- RangeQueryOpt
                   [from :- (s/maybe s/Inst)
                    to :- (s/maybe s/Inst)]
                   (cond-> {}
                     from (assoc :gte from)
                     to   (assoc :lt to)))))
  ([date-field
    {:keys [query
            from to
            simple_query
            search_fields] :as search-params}
    make-date-range-fn :- (s/=> RangeQueryOpt
                                (s/named (s/maybe s/Inst) 'from)
                                (s/named (s/maybe s/Inst) 'to))]
   (let [filter-map (apply dissoc search-params filter-map-search-options)
         date-range (make-date-range-fn from to)]
     (cond-> {}
       (seq date-range)        (assoc-in [:range date-field] date-range)
       (seq filter-map)        (assoc :filter-map filter-map)
       (or query simple_query) (assoc :full-text
                                      (->> (cond-> []
                                             query        (conj {:query query, :query_mode :query_string})
                                             simple_query (conj {:query_mode :simple_query_string
                                                                 :query      simple_query}))
                                           (mapv #(cond-> % 
                                                    search_fields (assoc :fields search_fields)))))))))

(s/defn format-agg-result :- MetricResult
  [result
   agg-type
   aggregate-on
   {:keys [range full-text filter-map]} :- SearchQuery]
  (let [nested-fields (map keyword (str/split (name aggregate-on) #"\."))
        {from :gte to :lt} (-> range first val)
        filters (cond-> (into {:from from :to to} filter-map)
                  (seq full-text) (assoc :full-text (map (fn [ft] (update ft :query_mode #(or % :query_string)))
                                                         full-text)))]
    {:data (assoc-in {} nested-fields result)
     :type agg-type
     :filters filters}))

(defn wait_for->refresh
  [wait_for]
  (cond-> {}
    (boolean? wait_for) (assoc :refresh (if wait_for "wait_for" "false"))))

(s/defschema Capability
  (s/conditional
    keyword? (s/pred simple-keyword?)
    nil? (s/pred nil?)
    set? #{(s/pred simple-keyword?)}))

(s/defn capabilities->string :- s/Str
  "Does not add leading or trailing new lines."
  [capabilities :- Capability]
  (if-some [capabilities (not-empty
                           (cond-> capabilities
                             (keyword? capabilities) hash-set))]
    (->> capabilities
         sort
         (map name)
         (str/join ", "))
    (throw (ex-info "Missing capabilities!" {}))))

(s/defn capabilities->description :- s/Str
  "Does not add leading or trailing new lines."
  [capabilities :- Capability]
  (str "Requires capability " (capabilities->string capabilities) "."))
