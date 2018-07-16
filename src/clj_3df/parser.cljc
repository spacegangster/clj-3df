(ns clj-3df.parser
  (:refer-clojure :exclude [resolve])
  (:require
   [clojure.string :as str]
   [clojure.set :as set]
   #?(:clj [clojure.spec.alpha :as s]
      :cljs [cljs.spec.alpha :as s])))

;; UTIL

(def log-level :info)

(defmacro info [& args]
  `(println ~@args))

(defmacro trace [& args]
  (when (= log-level :trace)
    `(println ~@args)))

(defn- pipe-log
  ([ctx] (pipe-log ctx "log:"))
  ([ctx message]
   (info message ctx)
   ctx))

(def ^{:arglists '([pred] [pred coll])} separate (juxt filter remove))

;; GRAMMAR

(s/def ::query (s/keys :req-un [::find ::where]
                       :opt-un [::in]))

(s/def ::find (s/alt ::find-rel ::find-rel))
(s/def ::find-rel (s/+ ::find-elem))
(s/def ::find-elem (s/or :var ::variable :aggregate ::aggregate))

(s/def ::in (s/+ ::variable))

(s/def ::where (s/+ ::clause))

(s/def ::clause
  (s/or ::and (s/cat :marker #{'and} :clauses (s/+ ::clause))
        ::or (s/cat :marker #{'or} :clauses (s/+ ::clause))
        ::or-join (s/cat :marker #{'or-join} :symbols (s/and vector? (s/+ ::variable)) :clauses (s/+ ::clause))
        ::not (s/cat :marker #{'not} :clauses (s/+ ::clause))
        ::pred-expr (s/tuple (s/cat :predicate ::predicate :fn-args (s/+ ::fn-arg)))
        ::lookup (s/tuple ::eid keyword? ::variable)
        ::entity (s/tuple ::eid ::variable ::variable)
        ::hasattr (s/tuple ::variable keyword? ::variable)
        ::filter (s/tuple ::variable keyword? ::value)
        ::rule-expr (s/cat :rule-name ::rule-name :fn-args (s/+ ::fn-arg))))

(s/def ::aggregate (s/cat :aggregation-fn ::aggregation-fn :fn-args (s/+ ::fn-arg)))

(s/def ::rules (s/and vector? (s/+ ::rule)))
(s/def ::rule (s/and vector?
                     (s/+ (s/cat :head ::rule-head
                                 :clauses (s/+ ::clause)))))
(s/def ::rule-head (s/and list? (s/cat :name ::rule-name :vars (s/+ ::variable))))
(s/def ::rule-name (s/and symbol? #(-> % name (str/starts-with? "?") (not))))

(s/def ::eid number?)
;; (s/def ::symbol (s/or ::placeholder #{'_}
;;                       ::variable ::variable))
(s/def ::variable (s/and symbol?
                         #(-> % name (str/starts-with? "?"))))
(s/def ::value (s/or :number number?
                     :string string?
                     :bool   boolean?))
(s/def ::predicate '#{<= < > >= = not=})
(s/def ::aggregation-fn '#{min})
(s/def ::fn-arg (s/or :var ::variable :const ::value))

;; QUERY PLAN GENERATION

;; When resolving queries we have a single goal: determining a unique
;; binding for every logic variable. Therefore we need to specify how
;; to unify two competing bindings in all possible contexts
;; (e.g. 'and', 'or', 'not').

(s/def ::unification-method #{:unify/conjunction :unify/disjunction})

;; 1. Step: In the first pass the tree of (potentially) nested,
;; context-modifying operators is navigated and all clauses are
;; extracted into a flat list. Context information is preserverd by
;; tagging clauses. Constant bindings are transformed into inputs.

(s/def ::tag (s/tuple ::unification-method number?))

(defrecord NormalizationContext [inputs clauses tag])
(defrecord Clause [id tag type symbols clause negated? deps plan])
(defrecord Relation [tag symbols negated? deps plan]
  Object
  (toString [this] (str "Rel" symbols)))

(defn- make-normalization-context
  ([] (make-normalization-context [[:unify/conjunction :root]]))
  ([root-tag] (NormalizationContext. {} #{} root-tag)))

(defn- make-clause [^NormalizationContext ctx {:keys [type clause symbols plan] :as props}]
  (map->Clause (merge {:id       (. clojure.lang.RT (nextID))
                       :tag      (.-tag ctx)
                       :negated? false
                       :deps     nil}
                      props)))

(defn- generate-tag [method]
  [method (. clojure.lang.RT (nextID))])

(defn- const->in
  "Transforms any constant arguments into inputs."
  [args]
  (->> args
       (reduce
        (fn [state [typ arg]]
          (case typ
            :var   (update state :normalized-args conj arg)
            :const (let [in (gensym "?in_")]
                     (-> state
                         (update :inputs assoc in [:const arg])
                         (update :normalized-args conj in)))))
        {:inputs {} :normalized-args []})))

(defmulti normalize (fn [^NormalizationContext ctx clause] (first clause)))

(defmethod normalize ::and [ctx [_ {:keys [clauses]}]]
  (as-> ctx nested
    (update nested :tag conj (generate-tag :unify/conjunction))
    (reduce normalize nested clauses)
    (assoc ctx :clauses (.-clauses nested))))

(defmethod normalize ::or [ctx [_ {:keys [clauses]}]]
  (as-> ctx nested
    (update nested :tag conj (generate-tag :unify/disjunction))
    (reduce normalize nested clauses)
    (assoc ctx :clauses (.-clauses nested))))

(defmethod normalize ::or-join [ctx [_ {:keys [symbols clauses]}]]
  (as-> ctx nested
    (update nested :tag conj (with-meta (generate-tag :unify/disjunction) {:projection symbols}))
    (reduce normalize nested clauses)
    (assoc ctx :clauses (.-clauses nested))))

(defmethod normalize ::not [ctx [_ {:keys [clauses]}]]
  (as-> ctx nested
    (update nested :tag conj (generate-tag :unify/conjunction))
    (reduce normalize nested clauses)
    (let [clauses (->> (.-clauses nested)
                       (into #{} (map (fn [clause]
                                        (if (contains? (.-clauses ctx) clause)
                                          clause
                                          (assoc clause
                                                 :negated? true
                                                 :deps (.-symbols clause)))))))]
      (assoc ctx :clauses clauses))))

(defmethod normalize ::pred-expr [ctx [_ predicate-expr]]
  (let [[{:keys [predicate fn-args]}]    predicate-expr
        {:keys [inputs normalized-args]} (const->in fn-args)
        tagged                           (make-clause ctx {:type    ::pred-expr
                                                           :clause  [{:predicate predicate
                                                                      :fn-args   normalized-args}]
                                                           :symbols normalized-args
                                                           :deps    (set normalized-args)})]
    (-> ctx
        (update :inputs merge inputs)
        (update :clauses conj tagged))))

(defmethod normalize ::lookup [ctx [_ [e a sym-v :as clause]]]
  (update ctx :clauses conj (make-clause ctx {:type    ::lookup
                                              :clause  clause
                                              :symbols [sym-v]})))

(defmethod normalize ::entity [ctx [_ [e sym-a sym-v :as clause]]]
  (update ctx :clauses conj (make-clause ctx {:type    ::entity
                                              :clause  clause
                                              :symbols [sym-a sym-v]})))

(defmethod normalize ::hasattr [ctx [_ [sym-e a sym-v :as clause]]]
  (update ctx :clauses conj (make-clause ctx {:type    ::hasattr
                                              :clause  clause
                                              :symbols [sym-e sym-v]})))

(defmethod normalize ::filter [ctx [_ [sym-e a v :as clause]]]
  (update ctx :clauses conj (make-clause ctx {:type    ::filter
                                              :clause  clause
                                              :symbols [sym-e]})))

(defmethod normalize ::rule-expr [ctx [_ rule-expr]]
  (let [{:keys [rule-name fn-args]}      rule-expr
        {:keys [inputs normalized-args]} (const->in fn-args)
        tagged                           (make-clause ctx {:type    ::rule-expr
                                                           :clause  {:rule-name rule-name
                                                                     :fn-args   normalized-args}
                                                           :symbols normalized-args})]
    (-> ctx
        (update :inputs merge inputs)
        (update :clauses conj tagged))))

;; 2. Step: Optimize clause order. @TODO

;; (defn optimize [clauses] (into [] clauses))

;; 3. Step: Sort clauses according to any dependencies between them,
;; ofcourse while attempting to preserve as much of the ordering from
;; the previous step. Dependencies occur, whenever a clause does not
;; produce bindings of its own, as is the case with ::pred-expr.

(defn reorder [clauses]
  (->> clauses
       (sort-by (fn [tagged] (conj (.-tag tagged) (.-id tagged))))
       (reverse)))

;; 4. Step: Unification. We process the list until all conflicts are
;; resolved. Clauses are in conflict iff they share one or more
;; symbols. Conflicts must be resolved according to the unification
;; method of the most specific (i.e. nested) context they share.

(defrecord UnificationContext [symbols inputs attr->int relations deferred])

(defn- make-unification-context [db inputs]
  (UnificationContext. {} inputs (:attr->int db) #{} []))

(defn- resolve [ctx sym]
  (if-let [pair (find (.-symbols ctx) sym)]
    (val pair)
    (if-let [pair (find (.-inputs ctx) sym)]
      (val pair)
      (throw (ex-info "Unknown symbol." {:ctx ctx :sym sym})))))

(defn- resolve-all [ctx syms] (mapv #(resolve ctx %) syms))

(defn- attr-id [ctx a]
  (if-let [pair (find (:attr->int ctx) a)]
    (val pair)
    (throw (ex-info "Unknown attribute." {:ctx ctx :attr a}))))

(defn- render-value [[type v]]
  (case type
    :string {:String v}
    :number {:Number v}
    :bool   {:Bool v}))

(defn- extract-relation
  "Extracts the final remaining relation from a context. Will throw if
  more than one relation is still present."
  [^UnificationContext ctx]
  (if (> (count (.-relations ctx)) 1)
    (throw (ex-info "More than one relation present in context." ctx))
    (first (.-relations ctx))))

(defn- binds? [^Relation rel sym] (some #{sym} (.-symbols rel)))
(defn- binds-all? [^Relation rel syms] (set/subset? syms (set (.-symbols rel))))

(defn- bound?
  "Returns true iff the specified symbol is bound by a relation."
  [^UnificationContext {:keys [inputs relations]} symbol]
  (or (contains? inputs symbol)
      (some? (some #(binds? % symbol) relations))))

(defn- all-bound?
  "Returns true iff all specified symbols are bound by a relation."
  [^UnificationContext ctx symbols]
  (every? #(bound? ctx %) symbols))

(defn- bound-together?
  "Returns true iff the specified symbols appear together inside a
  single relation. This is therefore a stronger condition than
  all-bound?, as required by aggregates."
  [^UnificationContext {:keys [inputs relations]} symbols]
  (if (nil? symbols)
    true
    (let [deps (into #{} (remove inputs) symbols)]
      (some #(binds-all? % deps) relations))))

(defn- shared-symbols [^Relation r1 ^Relation r2]
  (set/intersection (set (.-symbols r1)) (set (.-symbols r2))))

(defn- conflicting? [^Relation r1 ^Relation r2]
  (some? (seq (shared-symbols r1 r2))))

(defn- introduce-symbol [^UnificationContext ctx symbol]
  (if (contains? (.-symbols ctx) symbol)
    ctx
    (let [last-id (or (some->> (.-symbols ctx) (vals) (apply max)) -1)]
      (assoc-in ctx [:symbols symbol] (inc last-id)))))

(defn- shared-context
  "Identifies the most specific context shared by two tags."
  [tag1 tag2]
  (let [count (min (count tag1) (count tag2))
        pos   (dec count)]
    (cond
      (and (empty? tag1) (empty? tag2)) (throw (ex-info "Clauses must share a context." {:tag1 tag1 :tag2 tag2}))
      (= (nth tag1 pos) (nth tag2 pos)) (into [] (take count) tag1)
      :else                             (shared-context (take pos tag1) (take pos tag2)))))

;; (shared-context [[:unify/conjunction :root] [:unify/disjunction 25684] [:unify/conjunction 25685]]
;;                 [[:unify/conjunction :root] [:unify/disjunction 25684] ])
;; => [[:unify/conjunction :root] [:unify/disjunction 25684]]

(defn- join
  "Unifies two conflicting relations by equi-joining them."
  [^UnificationContext ctx r1 r2]
  (trace "joining" r1 r2)
  (let [shared      (shared-symbols r1 r2)
        ;; @TODO join on more than one variable
        join-sym    (first shared)
        result-syms (concat [join-sym] (remove shared (.-symbols r1)) (remove shared (.-symbols r2)))
        plan        {:Join [(.-plan r1) (.-plan r2) (resolve ctx join-sym)]}
        deps        (set/union (.-deps r1) (.-deps r2))]
    (->Relation (shared-context (.-tag r1) (.-tag r2)) result-syms false deps plan)))

(defn- antijoin
  "Unifies two conflicting relations by anti-joining them."
  [^UnificationContext ctx r1 r2]
  (trace "anti-joining" (:plan r1) (:plan r2))
  (let [shared-syms (shared-symbols r1 r2)
        join-syms   (into [] shared-syms)
        result-syms (concat join-syms (remove shared-syms (:symbols r1)))
        plan        {:Antijoin [(:plan r1) (:plan r2) (resolve-all ctx join-syms)]}
        deps        (set/union (.-deps r1) (.-deps r2))]
    (->Relation (shared-context (.-tag r1) (.-tag r2)) result-syms false deps plan)))

(defn- merge-unions [^Relation r1 ^Relation r2]
  (let [[resolved-syms plans] (get-in r1 [:plan :Union])
        deps                  (set/union (.-deps r1) (.-deps r2))
        plan                  {:Union [resolved-syms (conj plans (:plan r2))]}]
    (->Relation (shared-context (.-tag r1) (.-tag r2)) (.-symbols r1) false deps plan)))

(defn- union
  "Unifies two conflicting relations by taking their union."
  [^UnificationContext ctx r1 r2]
  (trace "union of" (:plan r1) (:plan r2))
  (let [shared-ctx    (shared-context (.-tag r1) (.-tag r2))
        projection    (get (meta (last shared-ctx)) :projection)
        symbols       (if (some? projection) projection (:symbols r1))
        _             (when-not (and (binds-all? r1 symbols) (binds-all? r2 symbols))
                        (throw (ex-info "Relations must be union compatible inside of an or-clause. Insert suitable projections."
                                        {:r1 r1 :r2 r2})))
        resolved-syms (resolve-all ctx symbols)
        union?        (fn [rel] (= (get-in rel [:plan :Union 0]) resolved-syms))
        
        [r1 r2] (cond
                  (and (union? r1)
                       (union? r2)) (throw (ex-info "Shouldn't be unifying two unions." {:r1 r1 :r2 r2}))
                  (union? r1)       [r1 r2]
                  (union? r2)       [r2 r1]
                  :else             (let [deps (.-deps r1)
                                          plan {:Union [resolved-syms [(.-plan r1)]]}]
                                      [(->Relation shared-ctx symbols false deps plan) r2]))]
    (merge-unions r1 r2)))

(defn- aggregate
  [^UnificationContext ctx aggregation-fn target-syms ^Relation {:keys [tag symbols negated? plan deps]}]
  (trace "aggregate" aggregation-fn target-syms)
  (if-not (bound-together? ctx target-syms)
    (throw (ex-info "All aggregate arguments must be bound by a single relation." {:ctx ctx :args target-syms}))
    (let [plan {:Aggregate [(name aggregation-fn) plan (resolve-all ctx target-syms)]}]
      (->Relation tag symbols negated? (set/union deps target-syms) plan))))

(defn- project [^UnificationContext ctx target-syms ^Relation {:keys [tag symbols negated? plan deps] :as rel}]
  (trace "project" target-syms)
  (cond
    (= symbols target-syms)            rel ; relation already matches the projection
    (not (all-bound? ctx target-syms)) (throw (ex-info "Find spec contains unbound symbols."
                                                       {:unbound   (into [] (remove #(bound? ctx %)) target-syms)
                                                        :relations (.-relations ctx)
                                                        :ctx       ctx}))
    :else
    (let [plan {:Project [plan (resolve-all ctx target-syms)]}]
      (->Relation tag target-syms negated? (set/union deps target-syms) plan))))

(defn- plan-clause
  "Maps clauses to relations."
  [^UnificationContext ctx ^Clause {:keys [tag type symbols clause negated? deps]}]
  (let [resolve (partial resolve ctx)
        attr-id (partial attr-id ctx)
        plan    (case type
                  ::lookup    (let [[e a sym-v] clause] {:Lookup [e (attr-id a) (resolve sym-v)]})
                  ::entity    (let [[e sym-a sym-v] clause] {:Entity [e (resolve sym-a) (resolve sym-v)]})
                  ::hasattr   (let [[sym-e a sym-v] clause] {:HasAttr [(resolve sym-e) (attr-id a) (resolve sym-v)]})
                  ::filter    (let [[sym-e a v] clause] {:Filter [(resolve sym-e) (attr-id a) (render-value v)]})
                  ::rule-expr (let [{:keys [rule-name fn-args]} clause] {:RuleExpr [(str rule-name) (resolve-all ctx fn-args)]}))]
    (->Relation tag symbols negated? deps plan)))

(defn- unify-with
  [^UnificationContext ctx ^Clause tagged]
  (let [rel                (plan-clause ctx tagged)
        _                  (trace "unifying" (str rel))
        ;; for optimization purposes we do want to aggregate here, but
        ;; we mus be careful to aggregate without ignoring a more
        ;; specific context
        [conflicting free] (separate #(conflicting? rel %) (.-relations ctx))
        _                  (trace "conflicting" (mapv str conflicting))
        unified            (if (empty? conflicting)
                             rel
                             (reduce
                              (fn [rel conflicting]
                                (let [shared-ctx (shared-context (.-tag rel) (.-tag conflicting))
                                      [method _] (last shared-ctx)
                                      _          (trace "method" method (.-negated? rel) (.-negated? conflicting))
                                      rel'       (case [method (.-negated? rel) (.-negated? conflicting)]
                                                   [:unify/conjunction false false] (join ctx conflicting rel)
                                                   [:unify/conjunction false true]  (antijoin ctx rel conflicting)
                                                   [:unify/conjunction true false]  (antijoin ctx conflicting rel)
                                                   [:unify/disjunction false false] (union ctx conflicting rel)
                                                   [:unify/disjunction false true]  (throw (ex-info "Unbound not" {:rel rel :conflicting conflicting}))
                                                   [:unify/disjunction true false]  (throw (ex-info "Unbound not" {:rel rel :conflicting conflicting})))]
                                  rel'))
                              rel conflicting))]
    (trace "unified" (conj (set free) unified))
    (assoc ctx :relations (conj (set free) unified))))

(defmulti introduce-clause (fn [^UnificationContext ctx ^Clause tagged] (.-type tagged)))

(defmethod introduce-clause :default [^UnificationContext ctx ^Clause tagged]
  (as-> ctx ctx
    (reduce introduce-symbol ctx (.-symbols tagged))
    (unify-with ctx tagged)))

(defmethod introduce-clause ::pred-expr [ctx {:keys [clause tag]}]
  ;; assume for now, that input symbols are bound at this point
  (let [[{:keys [predicate fn-args]}] clause
        encode-predicate              {'< "LT" '<= "LTE" '> "GT" '>= "GTE" '= "EQ" 'not= "NEQ"}
        deps                          (into #{} (remove (.-inputs ctx)) fn-args)
        [matching other]              (separate #(binds-all? % deps) (.-relations ctx))]
    (if (not= (count matching) 1)
      (throw (ex-info "All predicate inputs must be bound in a single relation." {:predicate predicate
                                                                                  :deps      deps
                                                                                  :matching  matching
                                                                                  :other     other}))
      (let [rel     (first matching)
            wrap    (fn [plan] {:PredExpr [(encode-predicate predicate) (resolve-all ctx fn-args) plan]})
            wrapped (update rel :plan wrap)]
        (assoc ctx :relations (conj (set other) wrapped))))))

(defn- skip-clause [^UnificationContext ctx ^Clause clause]
  (trace "skipping" (.-type clause))
  (update ctx :deferred conj clause))

(defn unify [ctx clauses]
  (let [clauses         (reorder clauses) ;; @TODO should eventually be redundant...
        process-clauses (fn [ctx clauses]
                          (reduce (fn [ctx clause]
                                    (if (bound-together? ctx (.-deps clause))
                                      (introduce-clause ctx clause)
                                      (skip-clause ctx clause))) ctx clauses))
        ctx             (process-clauses ctx clauses)]
    (if (empty? (.-deferred ctx))
      ctx
      (loop [ctx ctx clauses (.-deferred ctx)]
        (trace "Deferred clauses present, looping")
        (let [ctx' (process-clauses (assoc ctx :deferred []) clauses)]
          (cond
            (empty? (.-deferred ctx'))    ctx'
            (= (.-deferred ctx') clauses) (throw (ex-info "Un-introducable clauses" {:clauses (.-deferred ctx') :ctx ctx'}))
            :else                         (recur ctx' (.-deferred ctx'))))))))

;; 5. Step: Resolve find specification.

(defn- extract-find-symbols [[typ pattern]]
  (case typ
    ::find-rel (mapcat extract-find-symbols pattern)
    :var       [pattern]
    :aggregate (mapcat extract-find-symbols (:fn-args pattern))))

(defmulti impl-find (fn [^UnificationContext ctx find-spec] (first find-spec)))

(defmethod impl-find ::find-rel [ctx [_ find-elems :as find-spec]]
  (let [;; aggregates need to be resolved prior to projecting
        ctx                   (reduce impl-find ctx find-elems)
        symbols               (extract-find-symbols find-spec)
        [relevant irrelevant] (separate #(binds-all? % symbols) (.-relations ctx))]
    ;; there can only ever be a single relevant relation at this point
    (-> ctx
        (assoc :relations (set irrelevant))
        (update :relations conj (project ctx symbols (first relevant))))))

(defmethod impl-find :var [ctx _] ctx)

(defmethod impl-find :aggregate [ctx [_ {:keys [aggregation-fn fn-args]}]]
  (let [{:keys [inputs normalized-args]} (const->in fn-args)
        [relevant irrelevant]            (separate #(binds-all? % normalized-args) (.-relations ctx))]
    ;; there can only ever be a single relevant relation at this point
    (-> ctx
        (update :inputs merge inputs)
        (assoc :relations (set irrelevant))
        (update :relations conj (aggregate ctx aggregation-fn normalized-args (first relevant))))))

;; PUBLIC API

(defrecord CompiledQuery [plan in])
(defrecord Rule [name plan])

(defn query->map [query]
  (loop [parsed {}, key nil, qs query]
    (if-let [q (first qs)]
      (if (keyword? q)
        (recur parsed q (next qs))
        (recur (update-in parsed [key] (fnil conj []) q) key (next qs)))
      parsed)))

(defn parse-query [query]
  (let [query     (if (sequential? query) (query->map query) query)
        conformed (s/conform ::query query)]
    (if (s/invalid? conformed)
      (throw (ex-info "Couldn't parse query" (s/explain-data ::query query)))
      conformed)))

(defn compile-query [db query]
  (let [ir                       (parse-query query)
        {:keys [clauses inputs]} (reduce normalize (make-normalization-context) (:where ir))
        inputs                   (merge inputs (zipmap (:in ir) (map #(vector :input %) (range))))
        ordered-clauses          (->> clauses (reorder))
        find-symbols             (extract-find-symbols (:find ir))
        unification-ctx          (as-> (make-unification-context db inputs) ctx
                                   (reduce introduce-symbol ctx find-symbols)
                                   (unify ctx ordered-clauses)
                                   (impl-find ctx (:find ir)))
        plan                     (-> unification-ctx extract-relation :plan)]
    (CompiledQuery. plan inputs)))

(defn parse-rules [rules]
  (let [conformed (s/conform ::rules rules)]
    (if (s/invalid? conformed)
      (throw (ex-info "Couldn't parse rules" (s/explain-data ::rules rules)))
      conformed)))

(defn compile-rules [db rules]
  (let [ir                (parse-rules rules)
        get-head          #(get-in % [0 :head])
        by-head           (group-by get-head ir)

        ;; We perform a transformation here, wrapping body clauses
        ;; with (and) and rule definitions with (or). Note that :Union
        ;; with a single relation is equivalent to a :Project.
        get-clauses       #(get-in % [0 :clauses])
        wrap-and          (fn [clauses] [::and {:clauses clauses}])
        rewrite-rule      (fn [rewritten head rules]
                            (let [wrapped-clauses (map (comp wrap-and get-clauses) rules)]
                              (if (= (count rules) 1)
                                (assoc rewritten head (get-clauses (first rules)))
                                (assoc rewritten head [[::or-join {:symbols (:vars head)
                                                                   :clauses wrapped-clauses}]]))))
        rewritten         (reduce-kv rewrite-rule {} by-head)
        
        compile-rewritten (fn [compiled head ir]
                            (let [{:keys [inputs clauses]}
                                  (reduce normalize (make-normalization-context) ir)

                                  ordered-clauses
                                  (->> clauses (reorder))
                                  
                                  unification-ctx
                                  (as-> (make-unification-context db inputs) ctx
                                    (reduce introduce-symbol ctx (:vars head))
                                    (unify ctx ordered-clauses))]
                              (assoc compiled head unification-ctx)))
        compiled  (reduce-kv compile-rewritten {} rewritten)

        rel->rule (fn [rules head ctx]
                    (let [rule-name (str (:name head))
                          plan      (->> ctx extract-relation (project ctx (:vars head)) :plan)]
                      (conj rules (Rule. rule-name plan))))
        rules     (reduce-kv rel->rule #{} compiled)]
    rules))

(comment
  (compile-rules {:attr->int {:admin? 100}} '[[(admin? ?user) [?user :admin? true]]])

  (compile-rules {:attr->int {:node 100 :edge 200}}
                 '[[(propagate ?x ?y) [?x :node ?y]]
                   [(propagate ?x ?y) [?z :edge ?y] (propagate ?x ?z)]])

  (let [q0 '[:find ?t1 ?t2
             :where
             [?op :time ?t1]
             [?op :time ?t2]
             [(< ?t2 100)]
             (or [(< ?t1 ?t2)]
                 [(< ?t2 ?t1)]
                 (and [(= ?t1 ?t2)]
                      (not [?op2 :time ?t2])
                      (some-rule ?t1 ?t2)
                      (another-rule ?op "ADD")))]
        q1 '[:find ?op ?op2
             :where
             [?op :time ?t]
             [?op2 :time ?t]]
        q2 '[:find ?t1 ?t2
             :where
             [?op :time ?t1]
             [?op2 :time ?t2]
             (yarule ?t1 ?t2)
             [(< ?t1 ?t2)]]]
    (compile-query {:attr->int {:time 100}} q2)))

