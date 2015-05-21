(ns edn-ld.core
  (:require [clojure.string :as string]
            [clojure.test :refer :all]
            [schema.core :as s]
            [edn-ld.common :refer [rdf xsd]]))

;; EDN-LD uses [Prismatic Schema](https://github.com/Prismatic/schema)
;; to specify and validate the data structures that we use.
;; Clojure is not a strongly typed language like Java.
;; Instead we build our data structures from a rich set of primitives,
;; and use schemas to ensure that our data has the right shape.


;; # Identifiers

;; Linked data consists of a network of links between resources
;; named by Internationalized Resource Identifiers (IRIs).
;; IRIs extend the more familiar URL and URI to use UNICODE characters.
;; [RFC3987](http://tools.ietf.org/html/rfc3987)
;; provides a grammar for parsing IRIs,
;; but for now we will cut corners and allow any String.

(def IRI s/Str)

;; IRIs provide explicit, globally unique names for things.
;; Anything that we want to talk about should have an IRI.
;; But sometimes all we need is a local, implicit link, without a global name.
;; In this case we can use a blank node.
;; We'll use the [Turtle](http://www.w3.org/TR/turtle/#BNodes) syntax
;; and say that a blank node is a string that starts with "_:"

(def BlankNode #"^_:.*$")

;; IRIs can be long and cumbersome to work with,
;; so we'll defined a Contraction to be a keyword that we can expand to an IRI.

(def Contraction s/Keyword)

;; To move between IRIs and Contractions we'll use a Context.
;; A Context is just a map from Contractions to Contractions or IRIs.

(def Context {Contraction (s/either Contraction IRI)})

;; We'll expand a contracted IRI by recursively looking up keys.
;; Since the recursion should not be very deep, we won't bother using `loop`.
;; Since Contractions are all keywords, we just return any other type of input.
;; We'll ignore some special keywords used later for Literals.
;; If the Contraction contains a colon (:),
;; then we'll split it and look up the prefix part.
;; E.g. `:rdfs:label` will resolve the prefix `:rdfs` and then append `label`.
;; Be careful not to build a loop into your Context!
;; For example: `(expand {:foo :foo} :foo)`

(defn expand
  "Given a Context and some input (usually a Contraction),
   try to return an IRI string.
   If the input is not a keyword then just return it;
   if the input is a key in the Context then return the expanded value;
   if the input has a prefix in the Context then return the joined value;
   otherwise use the default prefix."
  [context input]
  (try
    (cond
      (not (keyword? input))
      input
      (contains? #{:value :type :lang} input)
      input
      (find context input)
      (expand context (get context input))
      (.contains (name input) ":")
      (let [[prefix local] (string/split (name input) #":" 2)]
        (str (get context (keyword prefix)) local))
      :else
      (str (expand context (get context nil)) (name input)))
  (catch StackOverflowError e input)))

(defn expand-all
  "Given a Context and some collection,
   try to expand all Contractions in the collection,
   and return the updated collection."
  [context coll]
  (clojure.walk/prewalk (partial expand context) coll))

;; Contracting an IRI is a little trickier.
;; First we consider the case where the IRI is exactly a value in the Context.
;; So we expand and reverse the context to map from IRIs to Contractions,
;; then look up keys in the reversed Context.

;; Since these functions are likely to be called a lot on small inputs,
;; we'll use `memoize` to trade time for space.

(defn reverse-context
  "Given a context map from prefixes to IRIs,
   return a map from IRIs to prefixes."
  [context]
  (->> context
       (map (juxt #(expand context (val %)) key))
       (into {})))

(def memoized-reverse-context (memoize reverse-context))

;; Second we consider the case where the IRI starts with a prefix.
;; We'll use the longest prefix we can find,
;; which requires us to sort them by alphanumerically then by length.

(defn sort-prefixes
  "Given a Context,
   return a sequence of (IRI prefix) pairs, from longest IRI to shortest."
  [context]
  (->> context
       (map (juxt #(expand context (val %)) key))
       sort
       (sort-by (comp count first) >)))

(def memoized-sort-prefixes (memoize sort-prefixes))

;; The `get-prefixed` uses lazy sequences,
;; so the minimal number of maps and filters will be used.

(defn get-prefixed
  "Given Context and an input (usually an IRI string),
   try to return a Contraction using the longest prefix."
  [context input]
  (->> context
       memoized-sort-prefixes
       (filter #(.startsWith input (first %)))
       (map
         (fn [[uri prefix]]
           (string/replace-first input uri (str (name prefix) ":"))))
       (map keyword)
       first))

;; Now we define our `contract` function to handle both cases.

(defn contract
  "Given a Context and an input (usually and IRI string),
   try to return a Contraction.
   If the input is not a string, just return it;
   if the input exactly matches a value in the context map, return the key;
   otherwise try to use the longest matching prefix."
  [context input]
  (cond
    (not (string? input))
    input
    (find (memoized-reverse-context context) input)
    (get (memoized-reverse-context context) input)
    (get-prefixed context input)
    (get-prefixed context input)
    :else
    input))


;; # Literals

;; We can also link things to literal data, such as strings and numbers.
;; We represent literals as a map with special keys.
;;
;; - :value is the lexical value of the data, and must be present
;; - :type is an IRI specifying the type of data
;; - :lang is a code for the language of the data, which must conform to
;;   [BCP 47](http://tools.ietf.org/html/bcp47#section-2.2.9)
;;
;; Again, we'll cut corners for now and allow any string to be a language tag.
;; If the :type is xsd:string we won't include it.
;; If the :lang key is present, then the :type must be rdf:langString.
;; So we have three cases:

(def Lexical s/Str)

(def Datatype IRI)

(def Lang s/Str)

(def DefaultLiteral {:value Lexical}) ; implicit :type :xsd:string

(def TypedLiteral {:value Lexical :type Datatype})

(def LangLiteral {:value Lexical
                  :type  (s/enum (str rdf "langString"))
                  :lang  Lang})

(def Literal (s/either DefaultLiteral LangLiteral TypedLiteral))

;; For convenience, we'll define a multimethod that takes a Clojure value
;; and returns its datatype IRI.
;; The default value is xsd:string.
;; You can extend this multimethod as desired: http://clojure.org/multimethods

(defmulti get-type class)

(defmethod get-type :default [_] :xsd:string)

(defmethod get-type String   [_] :xsd:string)

(defmethod get-type Integer  [_] :xsd:integer)

(defmethod get-type Long     [_] :xsd:integer)

(defmethod get-type Float    [_] :xsd:float)

(defmethod get-type Double   [_] :xsd:float)

;; Now we define a convenience function to create a Literal
;; with an explicit or implicit type.

(defn literal
  "Given a value and an optional type or language tag, return a Literal.
   If the second argument starts with '@', consider it a language tag,
   otherwise consider it a type IRI."
  ([value] (literal (str value) (get-type value)))
  ([value type-or-lang]
   (if (.startsWith (str type-or-lang) "@")
     (literal value nil (.substring (str type-or-lang) 1))
     (literal value type-or-lang nil)))
  ([value type lang]
   (cond
     lang
     {:value value
      :type  :rdf:langString
      :lang  lang}
     (= type :xsd:string)
     {:value value}
     type
     {:value value
      :type  type}
     :else
     (throw (Exception. (format "'%s' is not a valid type" type))))))


;; # Triples

;; A triple contains a Subject, a Predicate, and an Object.
;; It's a statement that asserts that the Subject stands in a relationship
;; to the Object as specified by the Predicate.
;; A triple is also a directed edge in a graph,
;; forming a link from the Subject to the Object.

;; A Subject must be a resource, either named with an IRI or anonymous
;; with a BlankNode.

(def ExpandedSubject (s/either IRI BlankNode))

(def ContractedSubject (s/either IRI BlankNode Contraction))

;; A Predicate must be a named resource, an IRI.

(def ExpandedPredicate IRI)

(def ContractedPredicate (s/either IRI Contraction))

;; An Object can either be a resource (IRI or BlankNode) or a Literal.

(def ExpandedObject (s/either IRI BlankNode Literal))

(def ContractedObject (s/either IRI BlankNode Contraction Literal))

;; Since an Object can be any one of these types,
;; we define a ResourceMap as a map from any value to an IRI or Contraction,
;; then `objectify` function that tries to use the ResourceMap
;; and returns a literal if it fails.

(def ResourceMap {s/Any (s/either IRI Contraction)})

(defn objectify
  "Given an optional ResourceMap and an input value,
   return the resource if possible, otherwise a Literal."
  ([input]
   (objectify nil input))
  ([resource-map input]
   (cond
     (keyword? input)
     input
     (find resource-map input)
     (get resource-map input)
     :else
     (literal input))))

;; Now we can define a triple:

(def ExpandedTriple [ExpandedSubject ExpandedPredicate ExpandedObject])

(def ContractedTriple [ContractedSubject ContractedPredicate ContractedObject])

;; These definitions of a triples are the clearest, but not the most convenient.
;; Instead, we usually want to work with a "FlatTriple",
;; which is just a sequence with three, four, or five values:
;;
;; - three values when the object is not a Literal
;; - four values when the object is a TypedLiteral
;;   (to avoid ambiguity, we always specify the type, even if it's xsd:string)
;; - five values when the object is a LangLiteral
;;   (in which case the type must be rdf:langString)
;;
;; FlatTriples are convenient for streaming and working with lazy sequences.

(def FlatTriple
  (s/either
    [ContractedSubject ContractedPredicate ContractedSubject]
    [ContractedSubject ContractedPredicate Lexical Datatype]
    [ContractedSubject ContractedPredicate Lexical Datatype Lang]))

(def FlatTriples [FlatTriple])

;; The most interesting part of EDN-LD
;; is converting from general EDN data to triples.
;; Now we define some functions to make that easy.

(defn triplify-one
  "Given an optional ResourceMap, a subject, a predicate, and an object,
   return a FlatTriple.
   Tries to avoid circular references where subject and object are the same."
  ([subject predicate object]
   (triplify-one nil subject predicate object))
  ([resources subject predicate object]
   (let [{:keys [value type lang] :as result} (objectify resources object)]
     (cond
       (= subject result)
       (triplify-one nil subject predicate object)
       lang
       [subject predicate value type lang]
       type
       [subject predicate value type]
       value
       [subject predicate value :xsd:string]
       :else
       [subject predicate result]))))

(defn triplify
  "Given an optional ResourceMap, a subject, and a map of data,
   return a lazy sequence of FlatTriples."
  ([subject input-map]
   (triplify nil subject input-map))
  ([resources subject input-map]
   (map (partial apply triplify-one resources subject) input-map)))

(defn triplify-all
  "Given an optional ResourceMap, a 'subj', and a sequence of input maps,
   return a lazy sequence of triples.
   If 'subj' is a sequence of subject names, it will be used;
   if 'subj' is a keyword, it will be check in the input and resource maps;
   if 'subj' is a function, it will be called on each input map,
   and used to get the subject."
  ([subjects input-maps]
   (triplify-all nil subjects input-maps))
  ([resources subj input-maps]
   (cond
     (seq? subj)
     (mapcat (partial apply triplify resources) subj input-maps)
     (keyword? subj)
     (mapcat #(triplify resources (get resources (get input-maps subj)))
             input-maps)
     (function? subj)
     (mapcat #(triplify resources (subj input-maps)) input-maps)
     :else
     (throw (Exception. (format "Unknown type for 'subj': %" subj))))))

;; FlatTriples are convenient for streaming, but not for everything.
;; They can be quite redundant.
;; We also define a nested data structure called a SubjectMap.
;; From the inside out, it works like this:
;;
;; - ObjectSet: the set of object with the same subject and predicate
;; - PredicateMap: a map from predicate IRIs to ObjectSets
;; - SubjectMap: map from subject IRIs to PredicateMaps

(def ObjectSet #{ContractedObject})

(def PredicateMap {ContractedPredicate ObjectSet})

(def SubjectMap {ContractedSubject PredicateMap})

;; The `subjectify` function rolls a sequence of FlatTriples into a SubjectMap.

(defn subjectify
  "Given a sequence of FlatTriples, return a SubjectMap."
  [flat-triples]
  (reduce
    (fn [coll [subject predicate object datatype lang]]
      (update-in
        coll
        [subject predicate]
        (fnil conj #{})
        (if datatype
          (literal object datatype lang)
          object)))
    nil
    flat-triples))

;; We can also go the other way, from SubjectMap to FlatTriples.

(defn squash-objects
  "Given a Subject, Predicate, and ObjectSet,
   return a lazy sequnce of FlatTriples."
  [subject predicate object-set]
  (->> object-set
       (map (juxt (constantly subject)
                  (constantly predicate)
                  :value
                  :type
                  :lang))
       (map (partial remove nil?))))

(defn squash-predicates
  "Given a Subject and PredicateMap, return a lazy sequnce of FlatTriples."
  [subject predicate-map]
  (mapcat (partial apply squash-objects subject) predicate-map))

(defn squash
  "Given a SubjectMap, return a lazy sequnce of FlatTriples."
  [subject-map]
  (mapcat (partial apply squash-predicates) subject-map))
