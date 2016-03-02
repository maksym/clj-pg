(ns clj-pg.honey
  (:refer-clojure :exclude [update])
  (:require [clj-pg.errors :refer [pr-error]]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as cs]
            [clj-pg.coerce :as coerce]
            [clojure.string :as str]
            [honeysql.core :as sql]
            [honeysql.format :as sqlf]
            [honeysql.helpers :as sqlh]))

(sqlf/register-clause! :returning 230)

(defmethod sqlf/format-clause :returning [[_ fields] sql-map]
  (str "RETURNING "
       (when (:modifiers sql-map)
         (str (sqlf/space-join (map (comp clojure.string/upper-case name)
                               (:modifiers sql-map)))
              " "))
       (sqlf/comma-join (map sqlf/to-sql fields))))

(sqlf/register-clause! :create-table 1)

(defmethod sqlf/format-clause :create-table [[_ tbl-name] sql-map]
  (str "CREATE TABLE " (sqlf/to-sql tbl-name)))

(sqlf/register-clause! :columns 2)

(defmethod sqlf/format-clause :columns [[_ cols] sql-map]
  (str "("
       (str/join ", " (map #(str/join " " (map name %)) cols))
   ")"))

(sql/format {:create-table :users
             :columns [[:id :serial :primary-key]]})


(defmethod sqlf/format-clause :drop-table [[_ tbl-name] sql-map]
  (str "DROP TABLE " (when (:if-exists sql-map) " IF EXISTS ") (sqlf/to-sql tbl-name)))

(sqlf/register-clause! :drop-table 1)

(defmethod sqlf/fn-handler "ilike" [_ col qstr]
  (str (sqlf/to-sql col) " ilike " (sqlf/to-sql qstr)))

(defmethod sqlf/fn-handler "not-ilike" [_ col qstr]
  (str (sqlf/to-sql col) " not ilike " (sqlf/to-sql qstr)))

(defn honetize [hsql]
  (cond (map? hsql) (sql/format hsql)
        (vector? hsql) (if (keyword? (first hsql)) (sql/format (apply sql/build hsql)) hsql)
        (string? hsql) [hsql]))

(defn query
  "query honey SQL"
  ([db hsql]
   (pr-error (let [sql (honetize hsql)]
               (println sql)
               (jdbc/query db sql))))
  ([db h & more]
   (query db (into [h] more))))

(defn execute
  "execute honey SQL"
  [db hsql]
  (pr-error (jdbc/execute! db (honetize hsql))))

(defn coerce-entry [ent]
  (reduce (fn [acc [k v]]
            (assoc acc k (cond
                           (vector? v) (coerce/to-pg-array v)
                           (map? v) (coerce/to-pg-json v)
                           :else v))
            ) {} ent))

(defn create [db tbl data]
  (let [values (if (vector? data) data [data])
        values (map coerce-entry values)
        res (->> {:insert-into tbl
                  :values values
                  :returning [:*]}
                (query db))]
    (if (vector? data) res (first res))))

(defn update [db tbl data]
  (->> {:update tbl
        :set (coerce-entry (dissoc data :id))
        :where [:= :id (:id data)]
        :returning [:*]}
       (query db)
       (first)))

(defn delete [db tbl id]
  (->> {:delete-from tbl :where [:= :id id] :returning [:*]}
       (query db)
       (first)))

