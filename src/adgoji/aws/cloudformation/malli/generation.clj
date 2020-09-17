(ns adgoji.aws.cloudformation.malli.generation
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.pprint])
  (:import [java.io InputStreamReader BufferedReader]))


(defn read-json-stream [input {:keys [keywordize]}]
  (with-open [input input]
    (cheshire.core/parse-stream (BufferedReader. (InputStreamReader. input "UTF-8")) keywordize)))


(defn read-compressed-json-file [input opts]
  (read-json-stream (java.util.zip.GZIPInputStream. (io/input-stream input)) opts))


(defn read-json-file [input opts]
  (read-json-stream (io/input-stream input) opts))


 (defn pp-str [x]
   (with-out-str (clojure.pprint/pprint x)))


(defn pp-spit [f x & options]
  (with-open [file-out (apply clojure.java.io/writer f options)]
    (binding [*out* file-out]
      (clojure.pprint/pprint x))))


(defn pr-spit [f x & options]
  (with-open [file-out (apply clojure.java.io/writer f options)]
    (binding [*out* file-out]
      (pr x))))


(defn find-best-match [properties needle]
  (let [k [(last needle)]]
    (loop [parent (butlast needle)]
      (if-not parent
        k
        (or (let [ks (concat parent k)]
              (when (get-in properties ks)
                ks))
            (recur (butlast parent)))))))


(defn lookup-type [indexed-property-types ks type]
  (when-let [t (find-best-match indexed-property-types (conj ks type))]
    (str (when-let [prefix  (butlast t)]
           (str (clojure.string/join "::" prefix) "."))
         (last t))))


(defn aws-type->ks [t]
  (clojure.string/split (name t) #"::|\."))


(defn resource->schema [indexed-property-types t resource]
  ;; TODO do something with UpdateType
  ;; TODO do something "DuplicatesAllowed": false,
  (let [ks (aws-type->ks t)]
    (into [:map {:closed :true}]
          (for [[k {:keys [PrimitiveType Type ItemType PrimitiveItemType] :as spec}] (:Properties resource)]
            (vec (remove nil? [(name k)
                               (when (not (:Required k))
                                 {:optional true})
                               (cond
                                 PrimitiveType [:ref PrimitiveType]

                                 PrimitiveItemType
                                 (case Type
                                   ("List" "Map")
                                   [:ref (str PrimitiveItemType Type)])

                                 ItemType
                                 (case Type
                                   "List" [:or
                                           [:sequential [:ref (lookup-type indexed-property-types ks ItemType)]]
                                           "Fn::If"]
                                   "Map" [:map-of [:ref "String"] [:ref ItemType]])

                                 Type
                                 [:ref (lookup-type indexed-property-types ks Type)]

                                 :else
                                 [:ref "Unknown"])]))))))


(defn write-malli
  ([f]
   (write-malli f (read-compressed-json-file "https://d3teyb21fexa9r.cloudfront.net/latest/gzip/CloudFormationResourceSpecification.json" {:keywordize true})))

  ([f data]
   (let [property-types (:PropertyTypes data)
         indexed-property-types (reduce-kv (fn [acc k v]
                                             (assoc-in acc (aws-type->ks k) v))
                                           {}
                                           property-types)
         properties (for [[t resource] property-types]
                      [(name t)
                       (resource->schema indexed-property-types t resource)])
         resources (for [[t resource] (:ResourceTypes data)]
                     (let [n (name t)]
                       [n
                        [:map
                         ["Type" [:= n]]
                         ["Properties" (resource->schema indexed-property-types t resource)]]]))]
     (pp-spit f (into {} (concat properties resources))))))


(comment

  ;; From https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/cfn-resource-specification.html
  (def data (read-compressed-json-file "https://d3teyb21fexa9r.cloudfront.net/latest/gzip/CloudFormationResourceSpecification.json" {:keywordize true}))

  (write-malli "dev-resources/cloudformation.malli.edn" data)
  )
