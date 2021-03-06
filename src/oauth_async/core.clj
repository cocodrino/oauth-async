(ns oauth-async.core
  (:import (java.net URLEncoder URI URL))
  (:require [clojure.string :as str]
            [org.httpkit.client :as http]
            [clojure.core.async :as !]
            [cheshire.core :refer :all]))

;util macros
(defmacro and-let![bindings expr error]
  (if (seq bindings)
    `(if-let [~(first bindings) ~(second bindings)]
       (and-let! ~(drop 2 bindings) ~expr ~error)
       ~error)
    expr))



(defmacro and-let [bindings expr]
  (if (seq bindings)
    `(if-let [~(first bindings) ~(second bindings)]
       (and-let ~(drop 2 bindings) ~expr))
    expr))


(defn url-encode
  "Returns an UTF-8 URL encoded version of the given string."
  [unencoded]
  (URLEncoder/encode unencoded "UTF-8"))


(defn generate-query-string [params]
  (str/join
    "&"
    (mapcat (fn [[k v]]
              (if (sequential? v)
                (map #(str (url-encode (name %1))
                           "="
                           (url-encode (str %2)))
                     (repeat k) v)
                [(str (url-encode (name k))
                      "="
                      (url-encode (str v)))]))
            params)))

(defn assoc-query-params
  "Add map of parameters to query section of url.
  It does not attempt to remove duplicates in existing query string
  "
  [url params]
  (let [u  (URI. url)
        q  (.getRawQuery u)
        nq (generate-query-string params)
        fr (.getRawFragment u)]
    (str (URI.
           (.getScheme u)
           (.getUserInfo u)
           (.getHost u)
           (.getPort u)
           (.getPath u)
           nil nil)                                         ;; Have to add query and path manually as URI reencodes the query and fragment
         "?"
         (if q
           (str q "&" nq)
           nq)
         (if fr
           (str "#" fr))
         )))

(defn create-authorization-url [authorization-url client-id response-type redirect-uri params]
  (let [scope (if (:scope params) (name (:scope params)))
        qp    (->>
                (-> params
                    (dissoc :client-id :authorization-url :response-type :redirect-uri :scope :state)
                    (assoc :client_id client-id :response_type (name (or response-type "code")) :redirect_uri redirect-uri :scope scope :state (:state params)))
                (filter #(val %1)))]
    (assoc-query-params authorization-url qp)))

(defn- keyword-or-class
  [this _] (if (keyword? this) this (class this)))

(defmulti build-authorization-url "Create a OAuth authorization url for redirection or link" keyword-or-class)

(defmethod build-authorization-url java.util.Map [this params]
  (build-authorization-url (:authorization-url this) (merge (select-keys this [:client-id]) params)))

(defmethod build-authorization-url :default
  [authorization-url params]
  (create-authorization-url (str authorization-url) (:client-id params)
                            (:response-type params)
                            (:redirect-uri params)
                            (dissoc params :client-id :response-type :redirect-uri)))

(defn grant-type
  "Return the grant-type request"
  [params]
  (cond
    (:service params) (:service params)                     ;; Use :service to create a token-request for a specific service. EG Stripe has very different ways of handling it.
    (:grant-type params) (keyword (:grant-type params))
    (:code params) :authorization-code
    (:refresh-token params) :refresh-token
    (:username params) :password
    :else :client-credentials))

(defmulti token-request "Create a token request map" grant-type)

;; http://tools.ietf.org/html/draft-ietf-oauth-v2-31#section-4.1.3
(defmethod token-request :authorization-code
  [params]
  {:accept      :json :as :json
   :form-params (-> params
                    (assoc :redirect_uri (:redirect-uri params))
                    (select-keys [:code :scope :redirect_uri])
                    (assoc :grant_type "authorization_code"))
   :basic-auth  [(:client-id params) (:client-secret params)]})

;; http://tools.ietf.org/html/draft-ietf-oauth-v2-31#section-4.4
(defmethod token-request :client-credentials
  [params]
  {:accept      :json :as :json
   :form-params (-> params
                    (select-keys [:scope])
                    (assoc :grant_type "client_credentials"))
   :basic-auth  [(:client-id params) (:client-secret params)]})

;; http://tools.ietf.org/html/draft-ietf-oauth-v2-31#section-4.3
(defmethod token-request :password
  [params]
  {:accept      :json :as :json
   :form-params (-> params
                    (select-keys [:username :password :scope])
                    (assoc :grant_type "password"))

   :basic-auth  [(:client-id params) (:client-secret params)]})

;; http://tools.ietf.org/html/draft-ietf-oauth-v2-31#section-6
(defmethod token-request :refresh-token
  [params]
  {:accept      :json :as :json
   :form-params (-> params
                    (select-keys [:scope])
                    (assoc :refresh_token (:refresh-token params) :grant_type "refresh_token"))
   :basic-auth  [(:client-id params) (:client-secret params)]})

(defmulti fetch-token "Fetch an oauth token from server" keyword-or-class)

(defmethod fetch-token java.util.Map [this params]
  (fetch-token (:token-url this) (merge (select-keys this [:client-id :client-secret]) params)))

(definline check-url! [url]
  `(when (nil? ~url)
     (throw (IllegalArgumentException. "Host URL cannot be nil"))))

(defn request [{:keys [url form-params] [client_id client_secret] :basic-auth}]
  (let [chn     (!/chan 1)
        data    (generate-query-string (merge form-params {:client_id client_id :client_secret client_secret}))
        options {:timeout 10000
                 :headers {
                           "Content-Type"  "application/x-www-form-urlencoded"
                           "Cache-Control" "no-cache"
                           "charset"       "utf-8'"
                           }
                 :body    data}]
    (http/post
      url
      options
      (fn [r]
        (let [status-code (get r :status 400)]
          (if (not= 200 status-code)
            (!/put! chn {:success false :status status-code})

            (!/put!
              chn
              {:success  true
               :response (clojure.walk/keywordize-keys (parse-string (:body r)))})))))
    chn))



(defn post
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (check-url! url)
  (request (merge req {:method :post :url url})))

(defmethod fetch-token :default
  [url params]
  (post url (token-request params)))



(defn get-user-info [url token]
  (let [chn   (!/chan 1)
        _auth (str "Bearer " token)
        data  {:headers {"Authorization" _auth}}]
    (http/get
      url
      data
      (fn [r]
        (let [status-code (get r :status 400)]
          (if (not= 200 status-code)
            ;(!/put! chn {:success false :status status-code})
            (!/put! chn false)

            (!/put!
              chn
              {:success  true
               :response (clojure.walk/keywordize-keys (parse-string (:body r)))}))
          )))
    chn))




