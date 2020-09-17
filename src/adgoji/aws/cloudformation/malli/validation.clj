(ns adgoji.aws.cloudformation.malli.validation
  (:require [malli.core :as m]
            [malli.registry :as mr]
            [malli.error :as me]
            [adgoji.aws.cloudformation.malli.generation :as generation]))


(do
  (def schema
    ;; TODO make the schema loading configurable (e.g. transit, lazy per namespace etc)
    (memoize (fn []
               (clojure.edn/read-string (slurp "dev-resources/cloudformation.malli.edn")))))


  (def Resource
    (into [:lazy-multi {:dispatch #(get % "Type")
                        :lazy true}]
          (keys (schema))))


  (def Resources
    [:and
     [:map-of string? Resource]
     [:fn {:error/message "At least one resource"} (fn [x] (>= (count x) 1))]])


  (def SParameter
    map? ;; TODO
    )


  (def SParameters
    [:map-of string? SParameter])


  (def Template
    [:map
     {:closed true}
     ["AWSTemplateFormatVersion" {:optional true} [:enum "2010-09-09"]]
     ["Description" string?]

     ["Parameters" {:optional true} SParameters]
     ["Resources" Resources]
     ["Conditions" {:optional true} any?]
     ["Mappings" {:optional true} any?]
     ["Outputs" {:optional true} any?]])


  (defn dispatch-fns [{:keys [fns invalid types]}]
    (let [fns-set (set fns)]
      (into [:multi {:dispatch (fn [x]
                                 (if (map? x)
                                   (let [v (ffirst x)]
                                     (or (get fns-set v)
                                         invalid))
                                   (or
                                    (some (fn [[t k]]
                                            (when (t x)
                                              k))
                                          types)
                                    invalid)))}

             invalid]
            (concat fns (vals types)))))


  (def invalid-schema [:fn (constantly false)])

  (let [string-fn-schemas
        {"Ref" [:map-of [:= "Ref"] "RawString"]
         ;; https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/intrinsic-function-reference-base64.html
         ;; TODO report stackoverflow error when not using :ref
         "Fn::Base64" [:map-of [:= "Fn::Base64"] [:ref "String"]]
         ;; https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/intrinsic-function-reference-cidr.html
         "Fn::Cidr" [:map-of [:= "Fn::Cidr"] [:tuple [:ref "String"] [:ref "String"] [:ref "String"]]]
         ;; https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/intrinsic-function-reference-getatt.html
         "Fn::GetAtt"  [:map-of [:= "Fn::GetAtt"] [:tuple [:ref "String"] [:ref "String"]]]
         ;; https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/intrinsic-function-reference-getavailabilityzones.html
         "Fn::GetAZs"  [:map-of [:= "Fn::GetAZs"] [:or [:= ""] [:ref "String"]]] ;; Only string and ref
         ;; https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/intrinsic-function-reference-importvalue.html
         "Fn::ImportValue" [:map-of [:= "Fn::ImportValue"] [:ref "String"]]
         ;; FIXME how come do we get invalid dispatch value ":type :malli.core/invalid-dispatch-value" when missing the first String for fn::Join
         "Fn::Join" [:map-of [:= "Fn::Join"] [:tuple [:ref "String"] [:ref "StringList"]]]
         "InvalidSelectElement" invalid-schema
         ;; https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/intrinsic-function-reference-select.html
         "Fn::SelectFirstElement" (dispatch-fns {:fns ["Ref" "Fn::FindInMap"]
                                                 :types {integer? "RawNumber"
                                                         string? "RawString"}
                                                 :invalid "InvalidSelectElement"})

         "Fn::SelectSecondElement" (dispatch-fns {:fns ["Fn::FindInMap"
                                                        "Fn::GetAtt"
                                                        "Fn::GetAZs"
                                                        "Fn::If"
                                                        "Fn::Split"
                                                        "Ref"]
                                                  :invalid "InvalidSelectElement"})
         "Fn::Select"
         [:map-of [:= "Fn::Select"] [:tuple [:ref "Fn::SelectFirstElement"] [:ref "Fn::SelectSecondElement"]]]
         ;; https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/intrinsic-function-reference-sub.html
         "Fn::Sub"  [:or
                     [:map-of [:= "Fn::Sub"] [:ref "String"]]
                     [:map-of [:= "Fn::Sub"] [:tuple [:ref "String"] [:map-of string? [:ref "String"]]]  ]]
         ;; https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/intrinsic-function-reference-findinmap.html
         "Fn::FindInMapElement" [:multi {:dispatch (fn [x]
                                                     (if (and (map? x)
                                                              (contains? #{"Ref" "Fn::FindInMap"} (ffirst x)))
                                                       (ffirst x)
                                                       "RawString"))}
                                 "Ref"
                                 "Fn::FindInMap"
                                 "RawString"]
         "Fn::FindInMap" [:map-of [:= "Fn::FindInMap"] [:tuple string? [:ref "Fn::FindInMapElement"] [:ref "Fn::FindInMapElement"]]]

         ;; Fn::If can be put anywhere and contain anything, hard to spec (better suited for types)
         "Fn::If" [:map-of [:= "Fn::If"]  [:tuple string?
                                           any?
                                           any?]]}

        number-fn-schemas
        (select-keys string-fn-schemas ["Ref" "Fn::ImportValue" "Fn::If" "Fn::GetAtt" "Fn::FindInMap"])

        boolean-fn-schemas number-fn-schemas

        coll-fn-schemas
        (merge
         (select-keys string-fn-schemas ["Fn::ImportValue" "Fn::If" "Fn::GetAZs" "Ref"])
         ;; https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/intrinsic-function-reference-split.html
         {"Fn::Split" [:map-of [:= "Fn::Split"] [:tuple [:ref "String"] [:ref "String"]]]})]


    (def base-schema
      (merge string-fn-schemas
             coll-fn-schemas
             {
              "RawString" string?
              "Json" map?
              "JsonList" [:sequential "Json"]
              "StringMap" [:map-of string? [:ref "String"]]
              "StringList*"  [:sequential [:ref "String"]]
              "InvalidStringList" [:fn (constantly false)]
              ;; Use multi here to get a better error over :or, malli.error/humanize cannot deal with this it seems
              #_[:or [:sequential "String"]
                 "Fn::Split"
                 "Fn::ImportValue"]
              ;; maybe because it gives :malli.core/invalid-dispatch-value https://github.com/metosin/malli/blob/master/src/malli/core.cljc#L821
              "StringList"
              (into [:multi {:dispatch (fn [x]
                                         (cond (sequential? x) "StringList*"
                                               (and (map? x)
                                                    (contains? coll-fn-schemas (ffirst x)))
                                               (ffirst x)

                                               :else "InvalidStringList"))}
                     "StringList*"
                     "InvalidStringList"]
                    (keys coll-fn-schemas))

              "RawNumber" number?
              "InvalidString" [:fn (constantly false)]
              "String" (into [:multi {:dispatch (fn [x]
                                                  (cond (string? x) "RawString"
                                                        ;; CFN doesn't take it very precise
                                                        (number? x) "RawNumber"

                                                        :else
                                                        (or (when-let [v (and (map? x)
                                                                              (name (ffirst x)))]
                                                              (when (contains? string-fn-schemas v)
                                                                v))
                                                            "InvalidString")))}


                              "RawString"
                              "RawNumber"
                              "InvalidString"]
                             (keys string-fn-schemas))

              "InvalidNumber" [:fn (constantly false)]
              "Number" (into [:multi {:dispatch (fn [x]
                                                  (if (number? x)
                                                    "RawNumber"
                                                    (or
                                                     (when-let [v (and (map? x)
                                                                       (name (ffirst x)))]
                                                       (when (contains? number-fn-schemas v)
                                                         v))
                                                     "InvalidNumber")))}
                              "RawNumber"
                              "InvalidNumber"]
                             (keys number-fn-schemas))

              "InvalidBoolean" [:fn (constantly false)]
              "RawBoolean" boolean?
              "Boolean" (into [:multi {:dispatch (fn [x]
                                                   (cond ;; CFN doesn't take it very precise
                                                     (boolean? x) "RawBoolean"
                                                     (contains? #{"true" "false"} x) "RawString"
                                                     :else
                                                     (or (when-let [v (and (map? x)
                                                                           (name (ffirst x)))]
                                                           (when (contains? boolean-fn-schemas v)
                                                             v))
                                                         "InvalidBoolean")))}
                               "RawBoolean"
                               "RawString"
                               "InvalidBoolean"]
                              (keys number-fn-schemas))
              "Unknown" any?
              ;; REVIEW make number more specific, what doesn CFN think about this
              "Integer" "Number"
              "Long" "Integer"
              "Double" "Number"
              "Timestamp" "Number"
              "Tag" [:map
                     ["Key" "String"]
                     ["Value" "String"]
                     ]})))


  (def base-registry
    (mr/composite-registry
     ;; the defaults
     (m/default-schemas)
     ;; Base schema
     base-schema
     ;; new :multi variant, which is lazy & has default dispatch key set
     {:lazy-multi (m/-multi-schema
                   {:type :lazy-multi
                    :lazy-refs true
                    :naked-keys true
                    :dispatch :Type})}))


  (defn lookup-type
    [type registry]
    ;; TODO Split definition in multiple files (or not) and see if it makes a difference in boot time
    ; (println "Lookup " type)
    (let [lookup (schema)
          definition (lookup type)
          schema (when definition
                   (try
                     (m/schema definition {:registry registry})
                     (catch Exception e
                       (throw (ex-info (str "Error while loading " type ": " (pr-str (ex-message e)))

                                       (assoc (ex-data e) :definition definition))))))]
      ; (println "loaded" (pr-str type))
      (when-not schema
        (throw (ex-info "Could not find type" {:type type})))
      schema))

  (defn cfn-registry []
    (mr/lazy-registry
     base-registry
     lookup-type))

  (mr/set-default-registry! (cfn-registry)))


(defn validation [schema data]
  (-> schema
      (m/explain data)
      (me/with-spell-checking)
      :errors))


(defn human-validation [schema data]
  ;; REVIEW can we improve spelling suggestion by using keywords and use :Type instead?
  (-> schema
      (m/explain data)
      (me/with-spell-checking)
      (me/humanize)))


(defn validate-template [tpl]
  (when-let [errors (validation #_human-validation Template tpl)]
    (throw (ex-info "Invalid" {:data errors}))))


(defn validate-dir [dir]
  (doseq [f (filter #(.isFile %) (file-seq (clojure.java.io/file dir)))]
    (println "validating file " (.getPath f))
    (validate-template (generation/read-json-file f {:keywordize false}))))


(comment

  (get (schema) "AWS::WAFRegional::ByteMatchSet.FieldToMatch")
  (lookup-type "AWS::WAFRegional::ByteMatchSet.FieldToMatch" m/default-registry)

  (validate-dir "/Users/jeroen/Projects/AdGoji/adgoji-cloudformation/json-templates")


  )
