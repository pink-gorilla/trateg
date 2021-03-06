(ns ta.data.alphavantage
  (:require
   [clj-http.client :as client]
   [cheshire.core] ; JSON Encoding
   [cljc.java-time.local-date :as ld]
   [throttler.core]))

;; https://www.alphavantage.co/documentation/#

; 5 API requests per minute
; 500 requests per day

;; Fingerprint Cards (STO: FING-B) == FING-B.STO
;; Novo Nordisk (CPH: NOVO-B) == NOVO-B.CPH
;; BMW (DE: BMW) == BMW.DE

;; valid symbols:
;; NYSE:WIT
;; BSE:507685
;; FLN.AX
;; NSE:HDFC
;; NSE:SBIN
;; NSE:LT&
;; NSE:ARVSMART
;; GBG.L
;; LON:RR

;; http://www.nasdaq.com/screening/company-list.aspx
;; NASDAQTrader
;; ftp://ftp.nasdaqtrader.com/SymbolDirectory
;; ftp://ftp.nasdaqtrader.com/SymbolDirectory/nasdaqlisted.txt
;; ftp://ftp.nasdaqtrader.com/SymbolDirectory/otherlisted.txt
;; ftp://ftp.nasdaqtrader.com/symboldirectory
;; ftp://nasdaqtrader.com/SymbolDirectory/nasdaqlisted.txt
;; ftp://nasdaqtrader.com/SymbolDirectory/otherlisted.txt



;; https://github.com/RomelTorres/alpha_vantage/issues/13

;; AlphaVantage ApiKey Management


(def api-key (atom "demo"))

(defn set-key!
  "to use alphavantage api, call at least once set-key! api-key"
  [key]
  (reset! api-key key)
  nil ; Important not to return by chance the key, as this would be shown in the notebook.
  )

;; helper

(defn- fix-keywords-
  "Alphavantage responses are unusually structured.
   field '1. open'  will be converted to :open keyword"
  [m]
  (let [ks (keys m)
        vs (vals m)]
    (zipmap (map #(keyword (second (re-find #"\d+.\s(\w+)" (name %)))) ks) vs)))

; Response of throtteled:
;
; {:Note "Thank you for using Alpha Vantage! 
;        Our standard API call frequency is 5 calls per minute and 500 calls per day. 
;        Please visit https://www.alphavantage.co/premium/ if you would like to target 
;        a higher API call frequency."}

(defn- throtteled? [response]
  (contains? response :Note))

(defn- success-if [response process-success]
  (if (throtteled? response)
    :throttled ;awb99: perhaps better return :throttled ???
    (process-success response)))

(defn- get-av-raw [params process-success]
  (-> (client/get "https://www.alphavantage.co/query"
                  {:accept :json
                   :query-params (assoc params :apikey @api-key)})
      (:body)
      (cheshire.core/parse-string true)
      (success-if process-success)))

(def get-av-throttled
  (throttler.core/throttle-fn get-av-raw 5 :minute))

(defn get-av [params process-success] ; throtteled version
  (get-av-throttled
   params
   (fn [result]
     (if (= result :throttled)
       (do (println "alphavantage request was throttled, retrying..")
           (get-av-throttled params (fn [result2]
                                      (if (= result2 :throttled)
                                        (do (println "alphavantage request was throttled the second time.")
                                            nil)
                                        (process-success result2)))))
       (process-success result)))))

;; Search Symbol

(defn search
  "searches for available symbols by keyword"
  [keywords]
  (get-av {:function "SYMBOL_SEARCH"
           :keywords keywords}
          (fn [response]
            (->> response
                 :bestMatches
                 (map fix-keywords-)
                 (into [])))))

;; Timeseries Requests

(def MetaData- (keyword "Meta Data"))
(def TimeSeriesDaily- (keyword "Time Series (Daily)"))
(def TimeSeriesFXDaily- (keyword "Time Series FX (Daily)"))
(def TimeSeriesDigitalCurrencyDaily- (keyword "Time Series (Digital Currency Daily)"))

(def bar-format-standard- {:open (keyword "1. open")
                           :high (keyword "2. high")
                           :low (keyword "3. low")
                           :close (keyword "4. close")
                           :volume (keyword "5. volume")})

(def bar-format-crypto- {:open (keyword "1a. open (USD)")
                         :high (keyword "2a. high (USD)")
                         :low (keyword "3a. low (USD)")
                         :close (keyword "4a. close (USD)")
                         :volume (keyword "5. volume")
                         :marketcap (keyword "6. market cap (USD)")})

;(def row-date-format-
;  (fmt/formatter "yyyy-MM-dd")) ; 2019-08-09

(defn- as-float [str]
  (if (nil? str)
    nil
    (Float/parseFloat str)))

(defn- convert-bar- [bar-format volume? item]
  (let [bars (second item)
        bar {:date (ld/parse (subs (str (first item)) 1))
             :open (as-float ((:open bar-format) bars))
             :high (as-float ((:high bar-format) bars))
             :low (as-float ((:low bar-format) bars))
             :close (as-float ((:close bar-format) bars))}]
    (if volume?
      (assoc bar :volume (as-float ((:volume bar-format) bars)))
      bar)))

(defn- convert-bars- [request-type response]
  (let [bar-format (if (= request-type TimeSeriesDigitalCurrencyDaily-) bar-format-crypto- bar-format-standard-)
        volume? (= request-type TimeSeriesDaily-)
        ;_ (println "Bar format: " bar-format)
        ]
    (->> response
         (request-type)
         (seq)
       ;(first)
       ;(convert-bar)
         (map (partial convert-bar- bar-format volume?))
         (sort-by :date))))

(defn get-daily
  "size: compact=last 100 days. full=entire history"
  [size symbol]
  (get-av {:function "TIME_SERIES_DAILY"
           :symbol symbol
           :outputsize (name size)
           :datatype "json"}
          (fn [response]
            (convert-bars- TimeSeriesDaily- response))))

(defn get-daily-fx
  "size: compact=last 100 days. full=entire history"
  [size symbol]
  (get-av {:function "FX_DAILY"
           :from_symbol (subs symbol 0 3)
           :to_symbol (subs symbol 3)
           :outputsize (name size)
           :datatype "json"}
          (fn [response]
            (convert-bars- TimeSeriesFXDaily- response))))

(defn get-daily-crypto
  "size: compact=last 100 days. full=entire history"
  [size symbol]
  (get-av {:function "DIGITAL_CURRENCY_DAILY"
           :symbol symbol
           :market "USD"
           :outputsize (name size)
           :datatype "json"}
          (fn [response]
            (convert-bars- TimeSeriesDigitalCurrencyDaily- response))))

(def kwCryptoRating- (keyword "Crypto Rating (FCAS)"))

(defn get-crypto-rating
  "size: compact=last 100 days. full=entire history"
  [symbol]
  (get-av {:function "CRYPTO_RATING"
           :symbol symbol
           :datatype "json"}
          (fn [response]
            (-> response
                kwCryptoRating-
                fix-keywords-))))

