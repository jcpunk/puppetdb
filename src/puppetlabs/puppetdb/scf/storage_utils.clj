(ns puppetlabs.puppetdb.scf.storage-utils
  (:require [cheshire.factory :refer [*json-factory*]]
            [clojure.java.jdbc :as sql]
            [clojure.tools.logging :as log]
            [honeysql.core :as hcore]
            [honeysql.format :as hfmt]
            [clojure.string :as str]
            [puppetlabs.i18n.core :refer [trs tru]]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.kitchensink.core :as kitchensink]
            [schema.core :as s])
  (:import [java.sql Connection]
           [java.util UUID]
           [org.postgresql.util PGobject]))

;; SCHEMA

(defn array-to-param
  [col-type java-type values]
  (.createArrayOf ^Connection (:connection (jdbc/db))
                  col-type
                  (into-array java-type values)))

(defn ast-path->array-path
  "Converts integers in path to strings so that the result is suitable
  for a text[]."
  [path]
  (mapv #(cond
           (string? %) %
           (int? %) (str %)
           :else
           (throw (IllegalArgumentException.
                   (tru "Path array element wasn't string or integer in {0}"
                        (pr-str path)))))
        path))

(def pg-extension-map
  "Maps to the table definition in postgres, but only includes some of the
   columns:

     Table pg_catalog.pg_extension
   Column     |  Type   | Modifiers
   ----------------+---------+-----------
   extname        | name    | not null
   extrelocatable | boolean | not null
   extversion     | text    |"
  {:name s/Str
   :relocatable s/Bool
   :version (s/maybe s/Str)})

(def db-version
  "A list containing a major and minor version for the database"
  [s/Int])

(defn db-metadata []
  (let [db-metadata (.getMetaData ^Connection (:connection (jdbc/db)))]
    {:database (.getDatabaseProductName db-metadata)
     :version [(.getDatabaseMajorVersion db-metadata)
               (.getDatabaseMinorVersion db-metadata)]}))

(defn sql-current-connection-table-names
  "Returns the names of all of the tables in the public schema of the
  current connection's database.  This is most useful for debugging /
  testing purposes to allow introspection on the database.  (Some of
  our unit tests rely on this.)."
  []
  (let [query   "SELECT table_name FROM information_schema.tables WHERE LOWER(table_schema) = 'public'"
        results (jdbc/with-db-transaction [] (jdbc/query-to-vec query))]
    (map :table_name results)))

(defn sql-current-connection-sequence-names
  "Returns the names of all of the sequences in the public schema of
  the current connection's database.  This is most useful for
  debugging / testing purposes to allow introspection on the
  database.  (Some of our unit tests rely on this.)."
  []
  (let [query   "SELECT sequence_name FROM information_schema.sequences WHERE LOWER(sequence_schema) = 'public'"
        results (jdbc/with-db-transaction [] (jdbc/query-to-vec query))]
    (map :sequence_name results)))

(defn sql-current-connection-function-names
  "Returns the names of all of the functions in the public schema of
  the current connection's database.  This is most useful for
  debugging / testing purposes to allow introspection on the
  database.  (Some of our unit tests rely on this.)."
  []
  (let [query (str "SELECT pp.proname as name, pg_catalog.pg_get_function_arguments(pp.oid) as args "
                   "FROM pg_proc pp "
                   "INNER JOIN pg_namespace pn ON (pp.pronamespace = pn.oid) "
                   "INNER JOIN pg_language pl ON (pp.prolang = pl.oid) "
                   "WHERE pl.lanname NOT IN ('c') "
                   (if (> 11 (-> (db-metadata) :version first))
                     ;; proisagg boolean was replaced with prokind char in pg11
                     "AND pp.proisagg = 'f'"
                     "AND pp.prokind = 'f'")
                   "AND pn.nspname NOT LIKE 'pg_%'"
                   "AND pn.nspname <> 'information_schema'")
        results (jdbc/with-db-transaction [] (jdbc/query-to-vec query))]
    (map (fn [{:keys [name args]}] (str name "(" args ")"))
         results)))

(defn sql-current-connection-aggregate-names
  "Returns the names of all of the functions in the public schema of
  the current connection's database.  This is most useful for
  debugging / testing purposes to allow introspection on the
  database.  (Some of our unit tests rely on this.)."
  []
  (let [query (str "SELECT pp.proname as name, pg_catalog.pg_get_function_arguments(pp.oid) as args "
                   "FROM pg_proc pp "
                   "INNER JOIN pg_namespace pn ON (pp.pronamespace = pn.oid) "
                   "INNER JOIN pg_language pl ON (pp.prolang = pl.oid) "
                   "WHERE pl.lanname NOT IN ('c') "
                   (if (> 11 (-> (db-metadata) :version first))
                     ;; proisagg boolean was replaced with prokind char in pg11
                     "AND pp.proisagg = 't'"
                     "AND pp.prokind = 'a'")
                   "AND pn.nspname NOT LIKE 'pg_%'"
                   "AND pn.nspname <> 'information_schema'")
        results (jdbc/with-db-transaction [] (jdbc/query-to-vec query))]
    (map (fn [{:keys [name args]}] (str name "(" args ")"))
         results)))

(pls/defn-validated pg-installed-extensions :- {s/Str pg-extension-map}
  "Obtain the extensions installed and metadata about each extension for
   the current database."
  []
  (let [query "SELECT extname as name,
                      extversion as version,
                      extrelocatable as relocatable
               FROM pg_extension"
        results (jdbc/with-db-transaction [] (jdbc/query-to-vec query))]
    (zipmap (map :name results)
            results)))

(pls/defn-validated pg-extension? :- s/Bool
  "Returns true if the named PostgreSQL extension is installed."
  [extension :- s/Str]
  (let [extensions (pg-installed-extensions)]
    (not= (get extensions extension) nil)))

(defn current-schema
  "Returns the current schema of the database connection on postgres."
  []
  (->> (jdbc/query-to-vec "select current_schema")
       first
       :current_schema))

(pls/defn-validated index-exists? :- s/Bool
  "Returns true if the index exists. Only supported on PostgreSQL currently."
  ([index :- s/Str]
   (let [schema (current-schema)]
     (index-exists? index schema)))
  ([index :- s/Str
    namespace :- s/Str]
     (let [query "SELECT c.relname
                    FROM   pg_index as idx
                    JOIN   pg_class as c ON c.oid = idx.indexrelid
                    JOIN   pg_namespace as ns ON ns.oid = c.relnamespace
                    WHERE  ns.nspname = ?
                      AND  c.relname = ?"
           results (jdbc/query-to-vec [query namespace index])]
       (= (:relname (first results))
          index))))

(pls/defn-validated constraint-exists? :- s/Bool
  ([table :- s/Str
    constraint :- s/Str]
   (let [schema (current-schema)]
     (constraint-exists? schema table constraint)))

  ([schema :- s/Str
    table :- s/Str
    constraint :- s/Str]
   (let [query (str "select count(*)"
                    "  from information_schema.constraint_column_usage"
                    "  where table_schema = ?"
                    "  and table_name = ?"
                    "  and constraint_name = ?")
         results (jdbc/query-to-vec [query schema table constraint])]
     (pos? (:count (first results))))))

(defn to-jdbc-varchar-array
  "Takes the supplied collection and transforms it into a
  JDBC-appropriate VARCHAR array."
  [coll]
  (->> coll
       (into-array Object)
       (.createArrayOf ^Connection (:connection (jdbc/db)) "varchar")))

(defn legacy-sql-regexp-match
  "Returns the SQL for performing a regexp match."
  [column]
  (format "(%s ~ ? AND %s IS NOT NULL)" column column))

(defn sql-as-numeric
  "Returns the SQL for converting the given column to a number, or to
  NULL if it is not numeric."
  [column]
  (hcore/raw (format (str "CASE WHEN %s~E'^\\\\d+$' THEN %s::bigint "
                          "WHEN %s~E'^\\\\d+\\\\.\\\\d+$' THEN %s::float "
                          "ELSE NULL END")
                     column column column column)))

(defn sql-array-type-string
  "Returns the SQL to declare an array of the supplied base database
  type."
  [basetype]
  (format "%s ARRAY[1]" basetype))

(defn sql-array-query-string
  "Returns an SQL fragment representing a query for a single value
  being found in an array column in the database.

    (str \"SELECT ... WHERE \" (sql-array-query-string \"column_name\"))

  The returned SQL fragment will contain *one* parameter placeholder,
  which must be supplied as the value to be matched."
  [column]
  (hcore/raw
   (format "ARRAY[?::text] <@ %s" (name column))))

(defn ast-path-type-sig
  [path]
  (->> path
       (map #(cond
               (string? %) \s
               (int? %) \i
               :else
               (throw (IllegalArgumentException.
                       (tru "Path array element wasn't string or integer in {0}"
                            (pr-str path))))))
       (apply str)))

(defn ast-rx-path-type-pattern
  [path]
  (->> path
       (map #(cond
               ;; If the pattern is a string that represents an
               ;; integer (e.g. "456") we require a string by setting
               ;; the relevant signature component to "s".  If it's
               ;; anything else (even something like "4[5]6" that will
               ;; match an integer), we allow any type by placing the
               ;; like operator's "_" (match any single char) syntax
               ;; into the signature pattern.  This is all in support
               ;; of the currently defined behavior of the ~> (regexp
               ;; array match) operator.  (See ast.markdown for
               ;; additional information.)
               (string? %) (if (re-matches #"\d+" %) \s \_)
               (int? %) \i
               :else
               (throw (IllegalArgumentException.
                       (tru "Path array element wasn't string or integer in {0}"
                            (pr-str path))))))
       (apply str)))

(defn path-array-col-matches-ast-path
  [column sig-col path]
  ;; (path = array['w', 'x', '0', '1', 'y'] and type = 'ssiss')
  (let [column (name column)
        sig-col (name sig-col)
        sig (-> path ast-path-type-sig jdbc/single-quote)]
    (-> (str "("
             column " = " (jdbc/str-vec->array-literal path)
             " and "
             sig-col " = " sig
             ")")
        hcore/raw)))

(defn path-array-col-matches-any-ast-path
  ;; ((path = array['w', 'x', '0', '1', 'y'] and type = 'ssiss')
  ;;  or (path = array['a', 'b', 'c'] and type = 'sss')
  ;;  or (path = array['w', '3', '11'] and type = 'sii'))
  [column sig-col paths]
  (let [column (name column)
        arrays (map ast-path->array-path paths)
        sigs (map ast-path-type-sig paths)
        sig-col (name sig-col)]
    (-> (str "("
             (->> (map (fn [array sig]
                         (str "("
                              column " = " (jdbc/str-vec->array-literal array)
                              " and "
                              sig-col " = " (jdbc/single-quote sig)
                              ")"))
                       arrays sigs)
                  (str/join " or "))
             ")")
        hcore/raw)))

(defn path-array-col-matches-rx-vec
  [column sig-col path-rx]
  ;; Produces results like this:
  ;; -- need case statement for guaranteed short-circuit...
  ;; case when array_length(path) >= array_length(rx)
  ;;   then type like 'sss_sis'
  ;;        and path[3] ~ 'rx-3'
  ;;        and path[2] ~ 'rx-2'
  ;;        and path[1] ~ 'rx-1'
  ;;   else false
  ;; end
  (let [column (name column)
        sig-col (name sig-col)
        sig (-> path-rx ast-rx-path-type-pattern jdbc/single-quote)
        ast->db #(cond
                   (string? %) %
                   (int? %) (str %)
                   :else
                   (throw (IllegalArgumentException.
                           (tru "Regex array element wasn't string or integer in {0}"
                                (pr-str path-rx)))))
        matches-target (fn [i rx]
                         (format "\n       and %s[%d] ~ %s"
                                 column
                                 (inc i)
                                 (-> rx ast->db jdbc/single-quote)))]
    (hcore/raw
     (str "(case when array_length(" column ", 1) = " (count path-rx) "\n"
          "   then " sig-col " like " sig
          (apply str
                 (->> (map-indexed matches-target path-rx)
                      reverse)) "\n"
          "   else false\n"
          " end)"))))

(defn sql-regexp-match
  "Returns db code for performing a regexp match."
  [column]
  [:and
   [(keyword "~") column "?"]
   [:is-not column nil]])

(defn sql-regexp-array-match
  "Returns SQL for performing a regexp match against the contents of
  an array. If any of the array's items match the supplied regexp,
  then that satisfies the match."
  [column]
  (hcore/raw
   (format "EXISTS(SELECT 1 FROM UNNEST(%s) WHERE UNNEST ~ ?)" (name column))))

(defn sql-cast
  [type]
  (fn [column]
    (if (= type "jsonb")
      (hcore/raw (format "to_jsonb(%s)" column))
      (hcore/raw (format "CAST(%s AS %s)" column type)))))

(defn jsonb-null?
  "A predicate determining whether the json types of a jsonb column are null."
  [column null?]
  (let [op (if null? "=" "<>")]
    (hcore/raw (format "jsonb_typeof(%s) %s 'null'" (name column) op))))

(defn sql-in-array
  [column]
  (hcore/raw
   (format "%s = ANY(?)" (first (hfmt/format column)))))

(defn jsonb-path-binary-expression
  "Produce a predicate that compares against nested value with op and checks the
  existence of a (presumably) top-level value. The existence check is necessary
  because -> is not indexable (with GIN) but ? is. Assumes a GIN index on the
  column supplied."
  [op column qmarks]
  (if (= "~" (name op))
    (let [path-elts (cons column qmarks)
          path (apply str
                      (str/join "->" (butlast path-elts))
                      (when-let [x (last path-elts)] ["->>" x]))]
      (hcore/raw (str/join \space
                           [(str "(" path ")") (name op) "(?#>>'{}')"
                            "and" column "??" "?"])))
    (let [delimited-qmarks (str/join "->" qmarks)]
      (hcore/raw (str/join \space
                           [(str "(" column "->" delimited-qmarks ")")
                            (name op) "?"
                            "and" column "??" "?"])))))

(defn jsonb-scalar-cast
  [typ]
  (fn
    [column]
    (hcore/raw (format "(%s#>>'{}')::%s" column typ))))

(defn jsonb-scalar-regex
  "Produce a predicate that matches a regex against a scalar jsonb value "
  [column]
  ;; This gets the unwrapped json value as text
  (hcore/raw (format "(%s#>>'{}')::text ~ ?" column)))

(defn db-serialize
  "Serialize `value` into a form appropriate for querying against a
  serialized database column."
  [value]
  (json/generate-string (kitchensink/sort-nested-maps value)))

(defn fix-identity-sequence
  "Resets a sequence to the maximum value used in a column. Useful when a
  sequence gets out of sync due to a bug or after a transfer."
  [table column]
  {:pre [(string? table)
         (string? column)]}
  (jdbc/with-db-transaction []
    (jdbc/do-commands (str "LOCK TABLE " table " IN ACCESS EXCLUSIVE MODE"))
    (jdbc/query-with-resultset
     [(format
       "select setval(pg_get_serial_sequence(?, ?), (select max(%s) from %s))"
       column table)
      table column]
     (constantly nil))))

(defn sql-hash-as-str
  [column]
  (format "encode(%s::bytea, 'hex')" column))

(defn vacuum-analyze
  [db]
  (sql/with-db-connection [_conn db]
    (sql/execute! db ["vacuum analyze"] {:transaction? false})))

(defn parse-db-hash
  [^PGobject db-hash]
  (str/replace (.getValue db-hash) "\\x" ""))

(defn parse-db-uuid
  [^UUID db-uuid]
  (.toString db-uuid))

(pls/defn-validated parse-db-json
  "Produce a function for parsing an object stored as json."
  [^PGobject db-json :- (s/maybe (s/cond-pre s/Str PGobject))]
  (some-> db-json .getValue (json/parse-string true)))

(pls/defn-validated str->pgobject :- PGobject
  [type :- s/Str
   value]
  (doto (PGobject.)
    (.setType type)
    (.setValue value)))

(defn munge-uuid-for-storage
  [value]
  (str->pgobject "uuid" value))

(defn bytea-escape [s]
  (format "\\x%s" s))

(defn munge-hash-for-storage
  [hash]
  (str->pgobject "bytea" (bytea-escape hash)))

(defn munge-json-for-storage
  "Prepare a clojure object for storage depending on db type."
  [value]
  (let [json-str (json/generate-string value)]
    (str->pgobject "json" json-str)))

(defn munge-jsonb-for-storage
  "Prepare a clojure object for storage.  Rewrite all null (\\u0000)
  characters to the replacement character (\\ufffd) because Postgres
  cannot handle them in its JSON values."
  [value]
  (binding [*json-factory* json/null-replacing-json-factory]
    (str->pgobject "jsonb" (json/generate-string value))))

(defn db-up?
  [db-spec]
  (let [f (future
            (try
              (jdbc/with-db-connection db-spec
                (= 42
                   (-> "SELECT (a - b) AS answer FROM (VALUES ((7 * 7), 7)) AS x(a, b)"
                       jdbc/query
                       first
                       :answer)))
              (catch Exception e
                (log/debug e (trs "Query to check if the database is up failed"))
                false)))
        result (deref f 1000 ::timed-out)]
     (if (= ::timed-out result)
       (do
         (log/debug (trs "Query to check if the database is up timed out"))
         false)
       result)))

(defn analyze-small-tables
  [small-tables]
  (log/info (trs "Analyzing small tables"))
  (apply jdbc/do-commands-outside-txn
         (map #(str "analyze " %) small-tables)))
